package `in`.driftzero.app.geo

/**
 * Geographic point in WGS84.
 *
 * @property latitudeDeg latitude in degrees, north positive
 * @property longitudeDeg longitude in degrees, east positive
 */
data class GeoPoint(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
) {
    init {
        require(latitudeDeg.isFinite()) { "latitudeDeg must be finite" }
        require(longitudeDeg.isFinite()) { "longitudeDeg must be finite" }
        require(latitudeDeg in -90.0..90.0) { "latitudeDeg out of range: $latitudeDeg" }
        require(longitudeDeg in -180.0..180.0) { "longitudeDeg out of range: $longitudeDeg" }
    }
}
