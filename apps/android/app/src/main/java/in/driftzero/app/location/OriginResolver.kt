package `in`.driftzero.app.location

import `in`.driftzero.app.geo.GeoPoint
import `in`.driftzero.app.product.DemoPlaces

enum class OriginSource {
    Gps,
    Fallback,
}

data class DeviceFix(
    val point: GeoPoint,
    val elapsedRealtimeMs: Long,
    val speedMetersPerSecond: Double?,
)

data class OriginDecision(
    val point: GeoPoint,
    val source: OriginSource,
    val speedMetersPerSecond: Double?,
    val gpsLost: Boolean,
    val waitingForFix: Boolean,
)

object OriginResolver {
    const val FALLBACK_WAIT_MS = 2_500L
    const val GPS_STALE_MS = 8_000L

    fun decide(
        fix: DeviceFix?,
        nowElapsedMs: Long,
        waitedMs: Long,
        hasPermission: Boolean,
        fallback: GeoPoint = DemoPlaces.FALLBACK_ORIGIN,
    ): OriginDecision {
        if (fix != null) {
            val ageMs = nowElapsedMs - fix.elapsedRealtimeMs
            val stale = ageMs > GPS_STALE_MS
            return OriginDecision(
                point = fix.point,
                source = OriginSource.Gps,
                speedMetersPerSecond = fix.speedMetersPerSecond.takeUnless { stale },
                gpsLost = stale,
                waitingForFix = false,
            )
        }
        val waiting = hasPermission && waitedMs < FALLBACK_WAIT_MS
        return OriginDecision(
            point = fallback,
            source = OriginSource.Fallback,
            speedMetersPerSecond = null,
            gpsLost = false,
            waitingForFix = waiting,
        )
    }
}
