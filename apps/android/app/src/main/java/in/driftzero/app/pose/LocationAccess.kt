package `in`.driftzero.app.pose

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.MotionPseudoRuntime
import `in`.driftzero.core.ShadowMapStore
import `in`.driftzero.core.ZuptAccelMotionModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** App location grant. Coarse-only is not a GNSS fix. */
enum class LocationGrant {
    NONE,
    COARSE,
    FINE,
}

internal fun hasLocationPermission(context: Context): Boolean =
    readLocationGrant(context) != LocationGrant.NONE

internal fun hasPreciseLocation(context: Context): Boolean =
    readLocationGrant(context) == LocationGrant.FINE

internal fun readLocationGrant(context: Context): LocationGrant = readLocationGrant(
    fineGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED,
    coarseGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED,
)

internal fun readLocationGrant(fineGranted: Boolean, coarseGranted: Boolean): LocationGrant = when {
    fineGranted -> LocationGrant.FINE
    coarseGranted -> LocationGrant.COARSE
    else -> LocationGrant.NONE
}

internal const val GPS_PROVIDER = "gps"
internal const val NETWORK_PROVIDER = "network"
internal const val FUSED_PROVIDER = "fused"

internal data class LastKnownFix(
    val provider: String,
    val elapsedRealtimeNanos: Long,
    val latitude: Double,
    val longitude: Double,
)

/**
 * GPS first so emulator `geo fix` wins. Otherwise the newest fused/network
 * sample. Live empty plus a last-known sample still produces a fix.
 */
internal fun pickLastKnownFix(samples: List<LastKnownFix>): LastKnownFix? {
    if (samples.isEmpty()) {
        return null
    }
    samples.firstOrNull { it.provider == GPS_PROVIDER }?.let { return it }
    return samples.maxByOrNull { it.elapsedRealtimeNanos }
}

internal fun gnssFixProviders(sdkInt: Int, available: Collection<String>): List<String> {
    val wanted = ArrayList<String>(3)
    if (available.contains(GPS_PROVIDER)) {
        wanted += GPS_PROVIDER
    }
    if (available.contains(NETWORK_PROVIDER)) {
        wanted += NETWORK_PROVIDER
    }
    if (available.contains(FUSED_PROVIDER) || sdkInt >= 31) {
        if (FUSED_PROVIDER !in wanted) {
            wanted += FUSED_PROVIDER
        }
    }
    return wanted
}

@SuppressLint("MissingPermission")
internal fun newestLastKnownLocation(context: Context): Location? {
    if (!hasLocationPermission(context)) {
        return null
    }
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val available = try {
        manager.allProviders
    } catch (_: Exception) {
        emptyList()
    }
    val found = ArrayList<Pair<LastKnownFix, Location>>(3)
    for (provider in gnssFixProviders(Build.VERSION.SDK_INT, available)) {
        try {
            val loc = manager.getLastKnownLocation(provider) ?: continue
            found += LastKnownFix(
                provider = loc.provider ?: provider,
                elapsedRealtimeNanos = loc.elapsedRealtimeNanos,
                latitude = loc.latitude,
                longitude = loc.longitude,
            ) to loc
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }
    val chosen = pickLastKnownFix(found.map { it.first }) ?: return null
    return found.firstOrNull { it.first == chosen }?.second
}

/**
 * Pose store plus GNSS and IMU adapters. Ticks the filter at
 * [DeadReckoningFilter.OUTPUT_HZ]. IMU stays on during Simulate GPS off so the
 * motion pseudo-measurement hook can fire. LocationManager also stays on
 * during Hold GNSS so 1 Hz fixes can be logged as score-only truth. The
 * filter does not ingest those fixes. Location permission is requested by
 * the map surface.
 */
@Composable
fun rememberPoseStore(): PoseStore {
    val context = LocalContext.current
    val store = remember {
        PoseStore(
            filter = PoseStore.liveFilter(),
            clockNs = { SystemClock.elapsedRealtimeNanos() },
            motion = MotionPseudoRuntime(
                model = ZuptAccelMotionModel(MotionStudentAssets.load(context.applicationContext)),
                displacement = LearnedImuAssets.load(context.applicationContext),
            ),
            profiles = PrefsMountProfileStore.open(context.applicationContext),
            shadowStore = ShadowMapStore(
                File(context.applicationContext.filesDir, ShadowMapStore.FILE_NAME),
            ),
        )
    }
    val replaying by store.replayActive.collectAsState()
    val periodMs = (1000.0 / DeadReckoningFilter.OUTPUT_HZ).toLong()
    LaunchedEffect(store, replaying) {
        if (replaying) {
            return@LaunchedEffect
        }
        val source = GnssLocationSource(context, store)
        val imu = PhoneImuSource(context, store)
        imu.start()
        var lastPollElapsedMs = 0L
        var gnssStarted = false
        try {
            while (isActive) {
                if (hasLocationPermission(context)) {
                    if (!gnssStarted) {
                        gnssStarted = withContext(Dispatchers.Main.immediate) {
                            source.start()
                        }
                    }
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - lastPollElapsedMs >= 1_000L) {
                        withContext(Dispatchers.IO) {
                            source.pollLastKnown()
                        }
                        lastPollElapsedMs = nowMs
                    }
                } else {
                    source.stop()
                    gnssStarted = false
                }
                withContext(Dispatchers.Default) {
                    store.tick()
                }
                val shadowJson = store.takePendingShadowJson()
                if (shadowJson != null) {
                    withContext(Dispatchers.IO) {
                        store.writeShadowJson(shadowJson)
                    }
                }
                delay(periodMs)
            }
        } finally {
            source.stop()
            imu.stop()
        }
    }
    return store
}
