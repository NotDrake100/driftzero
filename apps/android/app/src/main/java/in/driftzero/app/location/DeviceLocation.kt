package `in`.driftzero.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Foreground GNSS/network location for driver copy (speed + GPS health).
 * Units: latitude/longitude degrees, speed metres/second, time elapsed-realtime nanoseconds.
 */
object DeviceLocation {
    fun observe(context: Context): Flow<LiveFix?> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(toLiveFix(location, SystemClock.elapsedRealtimeNanos()))
            }

            @Deprecated("Deprecated in API 29 but still required on older listeners")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                trySend(null)
            }
        }

        @SuppressLint("MissingPermission")
        fun request(provider: String) {
            if (!manager.isProviderEnabled(provider)) return
            manager.requestLocationUpdates(
                provider,
                1_000L,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        }

        try {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val lastKnown = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
            ).mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.elapsedRealtimeNanos }
            trySend(lastKnown?.let { toLiveFix(it, nowNs) })
            request(LocationManager.GPS_PROVIDER)
            request(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            trySend(null)
        }

        awaitClose {
            manager.removeUpdates(listener)
        }
    }

    internal fun toLiveFix(location: Location, nowElapsedRealtimeNs: Long): LiveFix {
        val speedMps = if (location.hasSpeed() && location.speed.isFinite() && location.speed >= 0f) {
            location.speed.toDouble()
        } else {
            null
        }
        val accuracyM = if (location.hasAccuracy() && location.accuracy.isFinite()) {
            location.accuracy
        } else {
            null
        }
        return LiveFix(
            latitudeDeg = location.latitude,
            longitudeDeg = location.longitude,
            speedMps = speedMps,
            hasTrustedFix = FixQuality.hasTrustedFix(
                accuracyM = accuracyM,
                elapsedRealtimeNs = location.elapsedRealtimeNanos,
                nowElapsedRealtimeNs = nowElapsedRealtimeNs,
            ),
            elapsedRealtimeNs = location.elapsedRealtimeNanos,
        )
    }
}
