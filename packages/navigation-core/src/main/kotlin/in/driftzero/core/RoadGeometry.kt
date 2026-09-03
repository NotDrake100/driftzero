package `in`.driftzero.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class EdgeHit(
    val edgeIndex: Int,
    val alongM: Double,
    val crossAbsM: Double,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val headingRad: Double,
    val fraction: Double,
)

internal fun projectOntoEdge(
    latitudeDeg: Double,
    longitudeDeg: Double,
    edgeIndex: Int,
    edge: GraphEdge,
): EdgeHit {
    var bestDist2 = Double.POSITIVE_INFINITY
    var bestAlong = 0.0
    var bestE = 0.0
    var bestN = 0.0
    var bestHeading = edge.segmentHeadingsRad[0]
    var walked = 0.0
    for (i in 0 until edge.points.size - 1) {
        val a = edge.points[i]
        val b = edge.points[i + 1]
        val (an, ae) = Wgs84.northEastMetres(
            latitudeDeg, longitudeDeg,
            a.latitude.value, a.longitude.value,
        )
        val (bn, be) = Wgs84.northEastMetres(
            latitudeDeg, longitudeDeg,
            b.latitude.value, b.longitude.value,
        )
        val abE = be - ae
        val abN = bn - an
        val ab2 = abE * abE + abN * abN
        val t = if (ab2 < 1e-12) {
            0.0
        } else {
            val raw = ((-ae) * abE + (-an) * abN) / ab2
            raw.coerceIn(0.0, 1.0)
        }
        val pe = ae + t * abE
        val pn = an + t * abN
        val d2 = pe * pe + pn * pn
        val segLen = hypot(abE, abN)
        if (d2 < bestDist2) {
            bestDist2 = d2
            bestAlong = walked + t * segLen
            bestE = pe
            bestN = pn
            bestHeading = edge.segmentHeadingsRad[i]
        }
        walked += segLen
    }
    val (lat, lon) = Wgs84.offsetMetres(latitudeDeg, longitudeDeg, bestN, bestE)
    val length = max(edge.lengthM, 1e-6)
    return EdgeHit(
        edgeIndex = edgeIndex,
        alongM = bestAlong.coerceIn(0.0, edge.lengthM),
        crossAbsM = hypot(bestE, bestN),
        latitudeDeg = lat,
        longitudeDeg = lon,
        headingRad = wrapHeadingRad(bestHeading),
        fraction = (bestAlong / length).coerceIn(0.0, 1.0),
    )
}

/**
 * Projection onto one geodesic segment. [alongM] is metres from A toward B,
 * clamped to the segment. [crossTrackM] is signed metres, positive to the
 * right of A→B (clockwise from the segment heading).
 */
internal data class SegmentProjection(
    val alongM: Double,
    val segmentLengthM: Double,
    val crossTrackM: Double,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val headingRad: Double,
    val fraction: Double,
)

internal fun projectOntoLatLonSegment(
    latitudeDeg: Double,
    longitudeDeg: Double,
    aLatitudeDeg: Double,
    aLongitudeDeg: Double,
    bLatitudeDeg: Double,
    bLongitudeDeg: Double,
): SegmentProjection {
    val (an, ae) = Wgs84.northEastMetres(
        latitudeDeg, longitudeDeg,
        aLatitudeDeg, aLongitudeDeg,
    )
    val (bn, be) = Wgs84.northEastMetres(
        latitudeDeg, longitudeDeg,
        bLatitudeDeg, bLongitudeDeg,
    )
    val abE = be - ae
    val abN = bn - an
    val ab2 = abE * abE + abN * abN
    val t = if (ab2 < 1e-12) {
        0.0
    } else {
        val raw = ((-ae) * abE + (-an) * abN) / ab2
        raw.coerceIn(0.0, 1.0)
    }
    val pe = ae + t * abE
    val pn = an + t * abN
    val segLen = hypot(abE, abN)
    val (lat, lon) = Wgs84.offsetMetres(latitudeDeg, longitudeDeg, pn, pe)
    val heading = if (ab2 < 1e-12) {
        0.0
    } else {
        courseEnuRad(abE, abN)
    }
    val distToClamp = hypot(pe, pn)
    val perp = if (segLen < 1e-12) {
        0.0
    } else {
        (abE * an - abN * ae) / segLen
    }
    val signed = when {
        distToClamp < 1e-12 -> 0.0
        abs(perp) < 1e-12 -> distToClamp
        perp > 0.0 -> distToClamp
        else -> -distToClamp
    }
    return SegmentProjection(
        alongM = t * segLen,
        segmentLengthM = segLen,
        crossTrackM = signed,
        latitudeDeg = lat,
        longitudeDeg = lon,
        headingRad = wrapHeadingRad(heading),
        fraction = t,
    )
}

/** Smallest signed heading change from [fromRad] to [toRad], in (-π, π]. */
internal fun signedHeadingDeltaRad(fromRad: Double, toRad: Double): Double {
    var d = (toRad - fromRad) % TWO_PI
    if (d > PI) {
        d -= TWO_PI
    }
    if (d <= -PI) {
        d += TWO_PI
    }
    return d
}

internal fun headingDeltaRad(a: Double, b: Double): Double = abs(signedHeadingDeltaRad(a, b))

/**
 * Metres from a point on [edge] to the nearer endpoint whose undirected
 * degree is at least [minDegree]. Null when neither endpoint is a junction.
 */
internal fun distanceToJunctionM(
    latitudeDeg: Double,
    longitudeDeg: Double,
    edge: GraphEdge,
    graph: RoadGraph,
    minDegree: Int = 3,
): Double? {
    var best: Double? = null
    for (nodeId in longArrayOf(edge.fromNodeId, edge.toNodeId)) {
        if (!graph.isJunction(nodeId, minDegree)) {
            continue
        }
        val node = graph.nodes[nodeId] ?: continue
        val d = Wgs84.distanceMetres(
            latitudeDeg,
            longitudeDeg,
            node.latitude.value,
            node.longitude.value,
        )
        if (best == null || d < best) {
            best = d
        }
    }
    return best
}

internal fun courseEnuRad(eastM: Double, northM: Double): Double =
    wrapHeadingRad(atan2(eastM, northM))

internal fun polylineLengthAndHeadings(points: List<GeoPoint>): Pair<Double, DoubleArray> {
    require(points.size >= 2)
    val headings = DoubleArray(points.size - 1)
    var length = 0.0
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val (north, east) = Wgs84.northEastMetres(
            a.latitude.value, a.longitude.value,
            b.latitude.value, b.longitude.value,
        )
        val seg = hypot(north, east)
        length += seg
        headings[i] = courseEnuRad(east, north)
    }
    require(length > 0.0) { "zero-length polyline" }
    return length to headings
}

internal fun edgeTouchesBbox(edge: GraphEdge, bbox: Wgs84Bbox): Boolean {
    for (p in edge.points) {
        if (bbox.contains(p.latitude.value, p.longitude.value)) {
            return true
        }
    }
    var minLat = 90.0
    var maxLat = -90.0
    var minLon = 180.0
    var maxLon = -180.0
    for (p in edge.points) {
        minLat = min(minLat, p.latitude.value)
        maxLat = max(maxLat, p.latitude.value)
        minLon = min(minLon, p.longitude.value)
        maxLon = max(maxLon, p.longitude.value)
    }
    if (minLat >= maxLat) {
        minLat -= 1e-9
        maxLat += 1e-9
    }
    if (minLon >= maxLon) {
        minLon -= 1e-9
        maxLon += 1e-9
    }
    return bbox.intersects(Wgs84Bbox(minLat, minLon, maxLat, maxLon))
}

internal class EdgeGrid(
    private val originLatDeg: Double,
    private val originLonDeg: Double,
    private val cellM: Double,
    private val cells: Map<Long, IntArray>,
) {
    fun query(latitudeDeg: Double, longitudeDeg: Double, radiusM: Double): IntArray {
        val (north, east) = Wgs84.northEastMetres(
            originLatDeg, originLonDeg, latitudeDeg, longitudeDeg,
        )
        val i0 = floor((east - radiusM) / cellM).toInt()
        val i1 = ceil((east + radiusM) / cellM).toInt()
        val j0 = floor((north - radiusM) / cellM).toInt()
        val j1 = ceil((north + radiusM) / cellM).toInt()
        val found = LinkedHashSet<Int>()
        for (i in i0..i1) {
            for (j in j0..j1) {
                val bucket = cells[packCell(i, j)] ?: continue
                for (edge in bucket) {
                    found.add(edge)
                }
            }
        }
        return found.toIntArray()
    }

    companion object {
        private const val DEFAULT_CELL_M: Double = 50.0

        fun build(
            nodes: Map<Long, GraphNode>,
            edges: List<GraphEdge>,
            cellM: Double = DEFAULT_CELL_M,
        ): EdgeGrid {
            val origin = nodes.values.firstOrNull()
            val originLat = origin?.latitude?.value ?: 0.0
            val originLon = origin?.longitude?.value ?: 0.0
            val buckets = HashMap<Long, MutableList<Int>>()
            edges.forEachIndexed { index, edge ->
                var minE = Double.POSITIVE_INFINITY
                var maxE = Double.NEGATIVE_INFINITY
                var minN = Double.POSITIVE_INFINITY
                var maxN = Double.NEGATIVE_INFINITY
                for (p in edge.points) {
                    val (n, e) = Wgs84.northEastMetres(
                        originLat, originLon, p.latitude.value, p.longitude.value,
                    )
                    minE = min(minE, e)
                    maxE = max(maxE, e)
                    minN = min(minN, n)
                    maxN = max(maxN, n)
                }
                val i0 = floor(minE / cellM).toInt()
                val i1 = ceil(maxE / cellM).toInt()
                val j0 = floor(minN / cellM).toInt()
                val j1 = ceil(maxN / cellM).toInt()
                for (i in i0..i1) {
                    for (j in j0..j1) {
                        buckets.getOrPut(packCell(i, j)) { ArrayList() }.add(index)
                    }
                }
            }
            return EdgeGrid(
                originLatDeg = originLat,
                originLonDeg = originLon,
                cellM = cellM,
                cells = buckets.mapValues { it.value.toIntArray() },
            )
        }

        private fun packCell(ix: Int, iy: Int): Long =
            (ix.toLong() shl 32) xor (iy.toLong() and 0xffffffffL)
    }
}
