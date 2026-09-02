package `in`.driftzero.app.pose

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import `in`.driftzero.core.CoastFix
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.TWO_PI
import kotlin.math.PI

/**
 * LocationManager adapter. Copies a fix into [PoseStore] only. Stops when
 * Simulate GPS off is armed so the filter can propagate without new GNSS.
 * [GnssStatus] rows are copied into [NavicMonitor]. IRNSS membership is
 * logged. Counts do not enter the filter and are not integrity.
 */
class GnssLocationSource(
    context: Context,
    private val store: PoseStore,
) : LocationListener {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running: Boolean = false
    private var seeded: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            if (!running) {
                return
            }
            val rows = ArrayList<GnssSatRow>(status.satelliteCount)
            var i = 0
            while (i < status.satelliteCount) {
                rows.add(
                    GnssSatRow(
                        constellation = NavicMonitor.constellationName(status.getConstellationType(i)),
                        usedInFix = status.usedInFix(i),
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
    fun start() {
        if (running || !hasLocationPermission(appContext)) {
            return
        }
        if (!seeded) {
            seedLastKnown()
            seeded = true
        }
        val available = try {
            manager.allProviders
        } catch (_: Exception) {
            emptyList()
        }
        var registered = false
        for (provider in gnssFixProviders(Build.VERSION.SDK_INT, available)) {
            try {
                manager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    this,
                    Looper.getMainLooper(),
                )
                registered = true
            } catch (_: IllegalArgumentException) {
            } catch (_: SecurityException) {
            }
        }
        if (!registered) {
            return
        }
        running = true
        try {
            registerStatus()
        } catch (_: SecurityException) {
            // Fixes still flow. Constellation rows are optional.
        }
    }

    fun stop() {
        if (!running) {
            return
        }
        running = false
        seeded = false
        manager.removeUpdates(this)
        try {
            manager.unregisterGnssStatusCallback(statusCallback)
        } catch (_: Exception) {
        }
        store.navic.clear()
    }

    @SuppressLint("MissingPermission")
    private fun registerStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.registerGnssStatusCallback(statusCallback, mainHandler)
        } else {
            @Suppress("DEPRECATION")
            manager.registerGnssStatusCallback(statusCallback)
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!running) {
            return
        }
        store.ingestGnss(location.toCoastFix())
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnown() {
        newestLastKnownLocation(appContext)?.let { store.ingestGnss(it.toCoastFix()) }
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
    return CoastFix(
        timestamp = Nanoseconds(elapsed),
        latitudeDeg = latitude,
        longitudeDeg = longitude,
        speedMps = speed,
        headingRad = heading,
        horizontalAccuracyM = accuracy,
        altitudeM = if (hasAltitude()) altitude else null,
    )
}
