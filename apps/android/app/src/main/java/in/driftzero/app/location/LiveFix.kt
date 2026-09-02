package `in`.driftzero.app.location

/**
 * Last device location used only by the UI (puck is owned by MapLibre LocationComponent).
 *
 * @property latitudeDeg WGS84 latitude, degrees
 * @property longitudeDeg WGS84 longitude, degrees
 * @property speedMps speed in metres/second when the provider supplied it; null if unknown
 * @property hasTrustedFix whether [FixQuality] accepts this sample
 * @property elapsedRealtimeNs [android.location.Location.getElapsedRealtimeNanos]
 */
data class LiveFix(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val speedMps: Double?,
    val hasTrustedFix: Boolean,
    val elapsedRealtimeNs: Long,
)
