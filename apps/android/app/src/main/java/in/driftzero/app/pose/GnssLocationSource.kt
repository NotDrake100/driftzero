package `in`.driftzero.app.pose

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import `in`.driftzero.core.CoastFix
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.TWO_PI
import kotlin.math.PI

/**
 * LocationManager adapter. Copies a fix into [PoseStore] only. Stays
 * running during Hold GNSS so the trip log can store 1 Hz score-only
 * truth. [PoseStore] does not feed those fixes to the filter.
 * [GnssStatus] rows are copied into [NavicMonitor] only when GNSS is not
 * held. IRNSS membership is logged. Counts do not enter the filter and
 * are not integrity.
 */
class GnssLocationSource(
    context: Context,
    private val store: PoseStore,
) : LocationListener {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running: Boolean = false
    private var seeded: Boolean = false
    private var lastPolledElapsedNs: Long? = null
    private var lastPolledLat: Double? = null
    private var lastPolledLon: Double? = null
    private var lastLiveWallNs: Long? = null
    private var lastGnssLogNs: Long = Long.MIN_VALUE
    private var locThread: HandlerThread? = null
    private var locHandler: Handler? = null
    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            if (!running || store.simulateGpsOff.value) {
                return
            }
            val rows = ArrayList<GnssSatRow>(status.satelliteCount)
            var i = 0
            while (i < status.satelliteCount) {
                rows.add(
                    GnssSatRow(
                        constellation = NavicMonitor.constellationName(status.getConstellationType(i)),
                        usedInFix = status.usedInFix(i),
                        cn0DbHz = status.getCn0DbHz(i).toDouble().let { cn0 ->
                            if (cn0 > 0.0 && cn0.isFinite()) cn0 else null
                        },
                    ),
                )
                i += 1
            }
            val line = store.navic.ingest(rows)
            if (line != null) {
                Log.i(NavicMonitor.LOG_TAG, line)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) {
            return true
        }
        val thread = HandlerThread("dz-gnss")
        thread.start()
        locThread = thread
        locHandler = Handler(thread.looper)
        if (!hasLocationPermission(appContext)) {
            quitLocThread()
            return false
        }
        running = true
        val available = try {
            manager.allProviders
        } catch (_: Exception) {
            emptyList()
        }
        val precise = hasPreciseLocation(appContext)
        var registered = false
        for (provider in gnssFixProviders(Build.VERSION.SDK_INT, available)) {
            registered = requestUpdates(provider, precise) || registered
            requestCurrent(provider)
        }
        if (!available.contains(GPS_PROVIDER)) {
            registered = requestUpdates(GPS_PROVIDER, precise) || registered
            requestCurrent(GPS_PROVIDER)
        }
        if (!registered) {
            Log.i(LOG_TAG, "no location listener; polling last known")
        }
        try {
            registerStatus()
        } catch (_: SecurityException) {
            // Fixes still flow. Constellation rows are optional.
        }
        locHandler?.post {
            if (!running || seeded) {
                return@post
            }
            seedLastKnown()
            seeded = true
        }
        return true
    }

    fun stop() {
        if (!running) {
            return
        }
        running = false
        seeded = false
        lastPolledElapsedNs = null
        lastPolledLat = null
        lastPolledLon = null
        lastLiveWallNs = null
        try {
            manager.removeUpdates(this)
        } catch (_: Exception) {
        }
        try {
            manager.unregisterGnssStatusCallback(statusCallback)
        } catch (_: Exception) {
        }
        store.navic.clear()
        quitLocThread()
    }

    private fun quitLocThread() {
        locHandler = null
        locThread?.quitSafely()
        locThread = null
    }

    companion object {
        const val LOG_TAG: String = "DriftZero"
    }

    @SuppressLint("MissingPermission")
    private fun registerStatus() {
        val handler = locHandler ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.registerGnssStatusCallback(statusCallback, handler)
        } else {
            @Suppress("DEPRECATION")
            manager.registerGnssStatusCallback(statusCallback)
        }
    }

    override fun onLocationChanged(location: Location) {
        ingestLive(location)
    }

    /**
     * Emulator `geo fix` writes last-known GPS even when no listener is
     * attached. Ingest when the elapsed timestamp or the coordinates move.
     */
    @SuppressLint("MissingPermission")
    fun pollLastKnown() {
        if (!running || !hasLocationPermission(appContext)) {
            return
        }
        val loc = newestLastKnownLocation(appContext) ?: return
        val elapsed = loc.elapsedRealtimeNanos
        if (!shouldIngestPolled(
                lastPolledElapsedNs,
                elapsed,
                lastPolledLat,
                lastPolledLon,
                loc.latitude,
                loc.longitude,
            )
        ) {
            return
        }
        rememberPolled(loc)
        logGnss(loc)
        store.ingestGnss(loc.toCoastFix())
    }

    private fun ingestLive(location: Location) {
        if (!running) {
            return
        }
        val now = SystemClock.elapsedRealtimeNanos()
        if (!shouldIngestLive(lastLiveWallNs, now, lastPolledLat, lastPolledLon, location.latitude, location.longitude)) {
            return
        }
        lastLiveWallNs = now
        rememberPolled(location)
        logGnss(location)
        store.ingestGnss(location.toCoastFix())
    }

    private fun logGnss(location: Location) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (lastGnssLogNs != Long.MIN_VALUE && now - lastGnssLogNs < 1_000_000_000L) {
            return
        }
        lastGnssLogNs = now
        Log.i(LOG_TAG, "gnss ${location.latitude},${location.longitude}")
    }

    private fun rememberPolled(location: Location) {
        lastPolledElapsedNs = location.elapsedRealtimeNanos.takeIf { it > 0L }
        lastPolledLat = location.latitude
        lastPolledLon = location.longitude
    }

    @SuppressLint("MissingPermission")
    private fun requestCurrent(provider: String) {
        if (Build.VERSION.SDK_INT < 30) {
            return
        }
        try {
            manager.getCurrentLocation(
                provider,
                null,
                { r -> (locHandler ?: Handler(Looper.getMainLooper())).post(r) },
            ) { loc ->
                if (loc != null) {
                    ingestLive(loc)
                }
            }
        } catch (_: IllegalArgumentException) {
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(provider: String, precise: Boolean): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val quality = if (precise) {
                    LocationRequest.QUALITY_HIGH_ACCURACY
                } else {
                    LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                }
                val request = LocationRequest.Builder(1_000L)
                    .setMinUpdateIntervalMillis(1_000L)
                    .setMinUpdateDistanceMeters(0f)
                    .setQuality(quality)
                    .build()
                val looper = locHandler?.looper ?: Looper.getMainLooper()
                manager.requestLocationUpdates(
                    provider,
                    request,
                    { r -> Handler(looper).post(r) },
                    this,
                )
            } else {
                manager.requestLocationUpdates(
                    provider,
                    1_000L,
                    0f,
                    this,
                    locHandler?.looper ?: Looper.getMainLooper(),
                )
            }
            true
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnown() {
        if (!running) {
            return
        }
        newestLastKnownLocation(appContext)?.let { loc ->
            rememberPolled(loc)
            logGnss(loc)
            store.ingestGnss(loc.toCoastFix())
        }
    }
}

internal fun Location.toCoastFix(): CoastFix {
    val elapsed = elapsedRealtimeNanos.takeIf { it > 0L }
        ?: SystemClock.elapsedRealtimeNanos()
    val speed = if (hasSpeed() && speed >= 0f) speed.toDouble() else null
    val heading = if (hasBearing()) {
        val rad = bearing.toDouble() * PI / 180.0
        val wrapped = ((rad % TWO_PI) + TWO_PI) % TWO_PI
        wrapped
    } else {
        null
    }
    val accuracy = if (hasAccuracy()) accuracy.toDouble().coerceAtLeast(0.0) else 25.0
    val speedAcc = if (Build.VERSION.SDK_INT >= 26 && hasSpeedAccuracy()) {
        optionalNonNegAccuracy(speedAccuracyMetersPerSecond.toDouble())
    } else {
        null
    }
    val bearingAcc = if (Build.VERSION.SDK_INT >= 26 && hasBearingAccuracy()) {
        bearingAccuracyDegToRad(bearingAccuracyDegrees.toDouble())
    } else {
        null
    }
    val verticalAcc = if (Build.VERSION.SDK_INT >= 26 && hasVerticalAccuracy()) {
        optionalNonNegAccuracy(verticalAccuracyMeters.toDouble())
    } else {
        null
    }
    val mock = if (Build.VERSION.SDK_INT >= 31) {
        isMock
    } else {
        @Suppress("DEPRECATION")
        isFromMockProvider
    }
    return CoastFix(
        timestamp = Nanoseconds(elapsed),
        latitudeDeg = latitude,
        longitudeDeg = longitude,
        speedMps = speed,
        headingRad = heading,
        horizontalAccuracyM = accuracy,
        altitudeM = if (hasAltitude()) altitude else null,
        speedAccuracyMps = speedAcc,
        bearingAccuracyRad = bearingAcc,
        isMock = mock,
        verticalAccuracyM = verticalAcc,
    )
}

internal fun optionalNonNegAccuracy(value: Double): Double? =
    value.takeIf { it.isFinite() && it >= 0.0 }

internal fun bearingAccuracyDegToRad(degrees: Double): Double? {
    val deg = optionalNonNegAccuracy(degrees) ?: return null
    return deg * PI / 180.0
}
