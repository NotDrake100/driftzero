package `in`.driftzero.core

import kotlin.math.max
import kotlin.math.min

/**
 * How much of [graph.bin] sits in RAM for a route A→B.
 *
 * A 4.5 MB / 312k-edge pack OOM'd a 512 MB emulator heap when loaded
 * whole. Low-RAM loads clip. The clip is the union of origin and dest
 * pads, not an origin-only square. A phone that can hold the pack still
 * loads it whole.
 */
object LocalGraphPolicy {
    const val MARGIN_M: Double = 500.0

    /**
     * In-memory blow-up versus packed bytes. 4.5e6 * 80 is 360 MB, over
     * a third of a 512 MB heap, so that pack stays windowed there.
     */
    const val RAM_PER_PACK_BYTE: Long = 80L

    fun canLoadFull(packBytes: Long, maxHeapBytes: Long): Boolean {
        if (packBytes <= 0L || maxHeapBytes <= 0L) {
            return false
        }
        val budget = maxHeapBytes / 3L
        if (budget <= 0L) {
            return false
        }
        if (packBytes > budget / RAM_PER_PACK_BYTE) {
            return false
        }
        return packBytes * RAM_PER_PACK_BYTE < budget
    }

    /**
     * Inclusive WGS84 box around every finite endpoint plus [marginM].
     * Dest is not optional when it is known. One origin pad is not enough.
     */
    fun routeWindow(
        originLatDeg: Double?,
        originLonDeg: Double?,
        destLatDeg: Double?,
        destLonDeg: Double?,
        marginM: Double = MARGIN_M,
    ): Wgs84Bbox? {
        if (!marginM.isFinite() || marginM <= 0.0) {
            return null
        }
        val points = ArrayList<Pair<Double, Double>>(2)
        if (finitePoint(originLatDeg, originLonDeg)) {
            points += originLatDeg!! to originLonDeg!!
        }
        if (finitePoint(destLatDeg, destLonDeg)) {
            points += destLatDeg!! to destLonDeg!!
        }
        if (points.isEmpty()) {
            return null
        }
        var south = 90.0
        var north = -90.0
        var west = 180.0
        var east = -180.0
        for ((lat, lon) in points) {
            val s = Wgs84.offsetMetres(lat, lon, -marginM, 0.0)
            val n = Wgs84.offsetMetres(lat, lon, marginM, 0.0)
            val w = Wgs84.offsetMetres(lat, lon, 0.0, -marginM)
            val e = Wgs84.offsetMetres(lat, lon, 0.0, marginM)
            south = min(south, s.first)
            north = max(north, n.first)
            west = min(west, w.second)
            east = max(east, e.second)
        }
        south = south.coerceIn(-90.0, 90.0)
        north = north.coerceIn(-90.0, 90.0)
        west = west.coerceIn(-180.0, 180.0)
        east = east.coerceIn(-180.0, 180.0)
        if (south >= north || west >= east) {
            return null
        }
        return Wgs84Bbox(south, west, north, east)
    }

    /**
     * Null means load the pack unclipped. Otherwise the A→B union window.
     */
    fun clipForLoad(
        packBytes: Long,
        maxHeapBytes: Long,
        originLatDeg: Double?,
        originLonDeg: Double?,
        destLatDeg: Double?,
        destLonDeg: Double?,
        marginM: Double = MARGIN_M,
    ): Wgs84Bbox? {
        if (canLoadFull(packBytes, maxHeapBytes)) {
            return null
        }
        return routeWindow(originLatDeg, originLonDeg, destLatDeg, destLonDeg, marginM)
    }

    /** [have] null is a full pack and covers any needed clip. */
    fun covers(have: Wgs84Bbox?, need: Wgs84Bbox?): Boolean {
        if (need == null) {
            return have == null
        }
        if (have == null) {
            return true
        }
        return have.southLatDeg <= need.southLatDeg &&
            have.westLonDeg <= need.westLonDeg &&
            have.northLatDeg >= need.northLatDeg &&
            have.eastLonDeg >= need.eastLonDeg
    }

    private fun finitePoint(latDeg: Double?, lonDeg: Double?): Boolean {
        if (latDeg == null || lonDeg == null) {
            return false
        }
        return latDeg.isFinite() && lonDeg.isFinite() &&
            latDeg in -90.0..90.0 && lonDeg in -180.0..180.0
    }
}

/**
 * Cached pack bytes plus the last clip. Reloads when A→B does not fit.
 */
class LocalGraphSession(
    private val bytes: ByteArray,
    private val packageId: String,
    private val maxHeapBytes: Long,
) {
    var window: Wgs84Bbox? = null
        private set
    var graph: RoadGraph? = null
        private set
    var router: LocalRouter? = null
        private set

    fun covering(
        originLatDeg: Double?,
        originLonDeg: Double?,
        destLatDeg: Double?,
        destLonDeg: Double?,
        marginM: Double = LocalGraphPolicy.MARGIN_M,
    ): LocalRouter? {
        val need = LocalGraphPolicy.clipForLoad(
            packBytes = bytes.size.toLong(),
            maxHeapBytes = maxHeapBytes,
            originLatDeg = originLatDeg,
            originLonDeg = originLonDeg,
            destLatDeg = destLatDeg,
            destLonDeg = destLonDeg,
            marginM = marginM,
        )
        val hit = router
        if (hit != null && LocalGraphPolicy.covers(window, need)) {
            return hit
        }
        val (loaded, names) = RoadGraphBin.load(bytes, packageId, need)
        if (loaded.isEmpty()) {
            return null
        }
        window = need
        graph = loaded
        val next = LocalRouter(loaded, names)
        router = next
        return next
    }
}
