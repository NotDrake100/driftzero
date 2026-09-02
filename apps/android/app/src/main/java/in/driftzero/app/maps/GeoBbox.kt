package `in`.driftzero.app.maps

/**
 * Axis-aligned WGS84 box. West must be less than east. Antimeridian spans
 * are out of scope for v1. This is the address of an area pack, not a city name.
 */
@ConsistentCopyVisibility
data class GeoBbox private constructor(
    val southLatDeg: Double,
    val westLonDeg: Double,
    val northLatDeg: Double,
    val eastLonDeg: Double,
) {
    fun contains(latitudeDeg: Double, longitudeDeg: Double): Boolean {
        return latitudeDeg in southLatDeg..northLatDeg &&
            longitudeDeg in westLonDeg..eastLonDeg
    }

    fun spanLatDeg(): Double = northLatDeg - southLatDeg

    fun spanLonDeg(): Double = eastLonDeg - westLonDeg

    companion object {
        fun of(
            southLatDeg: Double,
            westLonDeg: Double,
            northLatDeg: Double,
            eastLonDeg: Double,
        ): GeoBbox? {
            if (!southLatDeg.isFinite() || !westLonDeg.isFinite() ||
                !northLatDeg.isFinite() || !eastLonDeg.isFinite()
            ) {
                return null
            }
            if (southLatDeg !in -90.0..90.0 || northLatDeg !in -90.0..90.0) {
                return null
            }
            if (westLonDeg !in -180.0..180.0 || eastLonDeg !in -180.0..180.0) {
                return null
            }
            if (southLatDeg >= northLatDeg || westLonDeg >= eastLonDeg) {
                return null
            }
            return GeoBbox(southLatDeg, westLonDeg, northLatDeg, eastLonDeg)
        }

        fun around(latitudeDeg: Double, longitudeDeg: Double, halfSpanDeg: Double): GeoBbox? {
            if (!halfSpanDeg.isFinite() || halfSpanDeg <= 0.0) {
                return null
            }
            return of(
                southLatDeg = (latitudeDeg - halfSpanDeg).coerceIn(-90.0, 90.0),
                westLonDeg = (longitudeDeg - halfSpanDeg).coerceIn(-180.0, 180.0),
                northLatDeg = (latitudeDeg + halfSpanDeg).coerceIn(-90.0, 90.0),
                eastLonDeg = (longitudeDeg + halfSpanDeg).coerceIn(-180.0, 180.0),
            )
        }
    }
}
