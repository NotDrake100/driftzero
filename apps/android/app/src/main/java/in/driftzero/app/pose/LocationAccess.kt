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
import `in`.driftzero.app.maps.AreaPackStore
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.MotionPseudoRuntime
import `in`.driftzero.core.OsmGraphLoader
import `in`.driftzero.core.ZuptAccelMotionModel
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}

internal const val FUSED_PROVIDER = "fused"
internal const val GPS_PROVIDER = "gps"
internal const val NETWORK_PROVIDER = "network"

internal fun gnssFixProviders(sdkInt: Int, available: Collection<String>): List<String> {
    val wanted = ArrayList<String>(3)
    if (sdkInt >= 31 && available.contains(FUSED_PROVIDER)) {
        wanted += FUSED_PROVIDER
    }
    if (available.contains(GPS_PROVIDER)) {
        wanted += GPS_PROVIDER
    }
    if (available.contains(NETWORK_PROVIDER)) {
        wanted += NETWORK_PROVIDER
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
    val candidates = ArrayList<Location>(3)
    for (provider in gnssFixProviders(Build.VERSION.SDK_INT, available)) {
        try {
            manager.getLastKnownLocation(provider)?.let { candidates += it }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }
    return candidates.maxByOrNull { it.elapsedRealtimeNanos }
}

/**
 * Pose store plus GNSS and IMU adapters. Ticks the filter at
 * [DeadReckoningFilter.OUTPUT_HZ]. IMU stays on during Simulate GPS off so the
 * motion pseudo-measurement hook can fire. Location permission is requested
 * by the map surface.
 */
@Composable
fun rememberPoseStore(): PoseStore {
    val context = LocalContext.current
    val store = remember {
        val poses = PoseStore(
            clockNs = { SystemClock.elapsedRealtimeNanos() },
            motion = MotionPseudoRuntime(
                model = ZuptAccelMotionModel(MotionStudentAssets.load(context.applicationContext)),
                displacement = LearnedImuAssets.load(context.applicationContext),
            ),
        )
        val packs = AreaPackStore(File(context.applicationContext.filesDir, "area-packs"))
        val graphFile = packs.graphFile(packs.active())
        if (graphFile != null) {
            try {
                poses.setRoadGraph(
                    OsmGraphLoader.load(graphFile.toPath(), packs.active()?.manifest?.id?.value ?: graphFile.name),
                )
            } catch (_: Exception) {
                // Dummy or non-OSM graph.bin stays unused. Puck stays on ESKF.
            }
        }
        poses
    }
    val gpsOff by store.simulateGpsOff.collectAsState()
    val periodMs = (1000.0 / DeadReckoningFilter.OUTPUT_HZ).toLong()
    LaunchedEffect(store, gpsOff) {
        val source = GnssLocationSource(context, store)
        val imu = PhoneImuSource(context, store)
        imu.start()
        try {
            while (isActive) {
                if (!gpsOff && hasLocationPermission(context)) {
                    source.start()
                } else {
                    source.stop()
                }
                store.tick()
                delay(periodMs)
            }
        } finally {
            source.stop()
            imu.stop()
        }
    }
    return store
}
