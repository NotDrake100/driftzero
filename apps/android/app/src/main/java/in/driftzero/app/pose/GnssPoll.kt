package `in`.driftzero.app.pose

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
