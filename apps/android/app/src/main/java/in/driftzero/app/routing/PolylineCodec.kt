package `in`.driftzero.app.routing

import `in`.driftzero.app.geo.GeoPoint
import kotlin.math.pow

/**
 * Google encoded polyline, precision 5 (OSRM default).
 * Coordinates are WGS84 degrees.
 */
object PolylineCodec {
    fun decode(encoded: String, precision: Int = 5): List<GeoPoint> {
        if (encoded.isEmpty()) return emptyList()
        require(precision >= 1) { "precision must be >= 1" }
        val factor = 10.0.pow(precision)
        val out = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            val dlat = nextDelta(encoded, index)
            index = dlat.index
            lat += dlat.value
            if (index >= encoded.length) break
            val dlng = nextDelta(encoded, index)
            index = dlng.index
            lng += dlng.value
            val latitude = lat / factor
            val longitude = lng / factor
            if (!latitude.isFinite() || !longitude.isFinite()) continue
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) continue
            out += GeoPoint(latitudeDeg = latitude, longitudeDeg = longitude)
        }
        return out
    }

    private data class Delta(val value: Int, val index: Int)

    private fun nextDelta(encoded: String, start: Int): Delta {
        var result = 0
        var shift = 0
        var index = start
        var b: Int
        do {
            if (index >= encoded.length) {
                return Delta(0, index)
            }
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        val value = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return Delta(value, index)
    }
}
