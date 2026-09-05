package `in`.driftzero.app.pose

import `in`.driftzero.core.Wgs84

/**
 * True when a polled last-known fix should enter the filter. A newer
 * elapsed timestamp is a heartbeat even if the vehicle is still.
 * Emulator `geo fix` sometimes keeps the same elapsed while lat/lon
 * move, so a position change also counts.
 */
internal fun shouldIngestPolled(
    previousElapsedNs: Long?,
    incomingElapsedNs: Long,
    previousLat: Double? = null,
    previousLon: Double? = null,
    incomingLat: Double? = null,
    incomingLon: Double? = null,
): Boolean {
    if (incomingElapsedNs > 0L && (previousElapsedNs == null || incomingElapsedNs > previousElapsedNs)) {
        return true
    }
    val havePos = previousLat != null && previousLon != null && incomingLat != null && incomingLon != null
    if (!havePos) {
        return false
    }
    return incomingLat != previousLat || incomingLon != previousLon
}

/** Drop emulator / multi-provider bursts closer than 200 ms unless the fix jumped. */
internal const val LIVE_MIN_INTERVAL_NS: Long = 200_000_000L
internal const val LIVE_JUMP_M: Double = 2.0

internal fun shouldIngestLive(
    lastWallNs: Long?,
    nowWallNs: Long,
    lastLat: Double?,
    lastLon: Double?,
    lat: Double,
    lon: Double,
): Boolean {
    if (lastWallNs == null) {
        return true
    }
    if (nowWallNs - lastWallNs >= LIVE_MIN_INTERVAL_NS) {
        return true
    }
    if (lastLat == null || lastLon == null) {
        return true
    }
    return Wgs84.distanceMetres(lastLat, lastLon, lat, lon) >= LIVE_JUMP_M
}
