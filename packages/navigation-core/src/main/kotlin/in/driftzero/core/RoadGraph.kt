package `in`.driftzero.core

/**
 * Compact directed road graph for HMM matching. Visual tiles live elsewhere.
 * Identity is [packageId] plus geometry, never a city name.
 */
class RoadGraph(
    val packageId: String,
    val nodes: Map<Long, GraphNode>,
    val edges: List<GraphEdge>,
    val bbox: Wgs84Bbox? = null,
) {
    init {
        require(packageId.isNotEmpty()) { "packageId must be non-empty" }
    }

    val outgoing: Map<Long, IntArray> = buildOutgoing(edges)

    /**
     * Undirected neighbor count. A through-road shape node is 2. A T-junction
     * or crossing is >= 3. Used by the matcher as the junction test.
     */
    val undirectedDegree: Map<Long, Int> = buildUndirectedDegree(edges)

    internal val grid: EdgeGrid = EdgeGrid.build(nodes, edges)

    fun isEmpty(): Boolean = edges.isEmpty()

    fun degreeOf(nodeId: Long): Int = undirectedDegree[nodeId] ?: 0

    fun isJunction(nodeId: Long, minDegree: Int = 3): Boolean = degreeOf(nodeId) >= minDegree

    companion object {
        fun empty(packageId: String = "empty"): RoadGraph =
            RoadGraph(packageId, emptyMap(), emptyList())
    }
}

data class GraphNode(
    val id: Long,
    val latitude: LatitudeDeg,
    val longitude: LongitudeDeg,
)

/** Directed centerline. Reverse travel is a separate edge when the way is two-way. */
data class GraphEdge(
    val id: String,
    val fromNodeId: Long,
    val toNodeId: Long,
    val points: List<GeoPoint>,
    val lengthM: Double,
    val segmentHeadingsRad: DoubleArray,
    val highway: String,
    val osmWayId: Long? = null,
    /** OSM `layer`, 0 when untagged. Flyover vs surface. */
    val layer: Int = 0,
    val bridge: Boolean = false,
    /** OSM tunnel. Matcher exposes this so GNSS age can be treated as physical. */
    val tunnel: Boolean = false,
    /** True when this directed edge is the only legal direction of the OSM way. */
    val oneway: Boolean = false,
) {
    init {
        require(id.isNotEmpty())
        require(points.size >= 2) { "edge $id needs at least two points" }
        require(lengthM.isFinite() && lengthM > 0.0) { "edge $id length" }
        require(segmentHeadingsRad.size == points.size - 1)
        require(highway.isNotEmpty())
    }
}

/** Inclusive WGS84 box. Any place. No city lock. */
data class Wgs84Bbox(
    val southLatDeg: Double,
    val westLonDeg: Double,
    val northLatDeg: Double,
    val eastLonDeg: Double,
) {
    init {
        require(southLatDeg.isFinite() && northLatDeg.isFinite())
        require(westLonDeg.isFinite() && eastLonDeg.isFinite())
        require(southLatDeg in -90.0..90.0 && northLatDeg in -90.0..90.0)
        require(westLonDeg in -180.0..180.0 && eastLonDeg in -180.0..180.0)
        require(southLatDeg < northLatDeg) { "south must be < north" }
        require(westLonDeg < eastLonDeg) { "west must be < east; no antimeridian wrap" }
    }

    fun contains(latitudeDeg: Double, longitudeDeg: Double): Boolean =
        latitudeDeg in southLatDeg..northLatDeg && longitudeDeg in westLonDeg..eastLonDeg

    fun intersects(other: Wgs84Bbox): Boolean =
        southLatDeg <= other.northLatDeg &&
            northLatDeg >= other.southLatDeg &&
            westLonDeg <= other.eastLonDeg &&
            eastLonDeg >= other.westLonDeg
}

data class DisplayPose(
    val position: GeoPoint,
    val heading: HeadingRadians,
    val alongTrackM: Double,
    val crossTrackAbs: Metres,
) {
    init {
        require(alongTrackM.isFinite() && alongTrackM >= 0.0)
    }
}

data class MapMatchResult(
    val match: MapMatch,
    val displayPose: DisplayPose? = null,
    val candidateEntropy: Double = 0.0,
    val candidateCount: Int = 0,
    val packageId: String? = null,
    val bestPosterior: Double = 0.0,
    val secondPosterior: Double = 0.0,
    val secondRoadSegmentId: String? = null,
    /** True when the chosen centerline is within [HmmMatchConfig.junctionRadiusM] of a degree >= 3 node. */
    val nearJunction: Boolean = false,
    val junctionDistanceM: Double? = null,
    val onTunnel: Boolean = false,
    val onBridge: Boolean = false,
    val layer: Int = 0,
) {
    init {
        require(candidateEntropy.isFinite() && candidateEntropy >= 0.0)
        require(candidateCount >= 0)
        require(bestPosterior in 0.0..1.0)
        require(secondPosterior in 0.0..1.0)
        junctionDistanceM?.let { require(it.isFinite() && it >= 0.0) }
    }
}

/**
 * Newson and Krumm (2009) defaults plus heading and an unmatched state.
 * [sigmaZMetres] is their GPS noise (4.07 m). [betaMetres] is the exponential
 * scale on |route − great-circle|. [maxSearchRadiusM] follows their 200 m
 * cutoff, clamped by the YAML search bounds.
 */
data class HmmMatchConfig(
    val beamWidth: Int = 8,
    val maxCandidatesPerEpoch: Int = 12,
    val minSearchRadiusM: Double = 15.0,
    val maxSearchRadiusM: Double = 250.0,
    val sigmaZMetres: Double = 4.07,
    val betaMetres: Double = 2.0,
    val sigmaHeadingRad: Double = 0.52,
    val headingMinSpeedMps: Double = 1.0,
    val headingFullSpeedMps: Double = 3.0,
    val unmatchedDistanceM: Double = 80.0,
    val matchedMinPosterior: Double = 0.55,
    val ambiguousSecondRatio: Double = 0.65,
    val maxRouteSlackM: Double = 2000.0,
    val maxSpeedMps: Double = 50.0,
    val maxDijkstraNodes: Int = 256,
    /** Newson and Krumm §4.1: drop HMM steps closer than this to the last kept point. */
    val minMoveMetres: Double = 2.0,
    /** Distance to a degree >= 3 node that marks [MapMatchResult.nearJunction]. Default 25 m. */
    val junctionRadiusM: Double = 25.0,
) {
    init {
        require(beamWidth >= 1)
        require(maxCandidatesPerEpoch >= 1)
        require(minSearchRadiusM > 0.0 && maxSearchRadiusM >= minSearchRadiusM)
        require(sigmaZMetres > 0.0 && betaMetres > 0.0)
        require(sigmaHeadingRad > 0.0)
        require(headingMinSpeedMps >= 0.0 && headingFullSpeedMps >= headingMinSpeedMps)
        require(unmatchedDistanceM > 0.0)
        require(matchedMinPosterior in 0.0..1.0)
        require(ambiguousSecondRatio in 0.0..1.0)
        require(maxRouteSlackM > 0.0 && maxSpeedMps > 0.0)
        require(maxDijkstraNodes >= 8)
        require(minMoveMetres >= 0.0 && minMoveMetres.isFinite())
        require(junctionRadiusM.isFinite() && junctionRadiusM >= 0.0)
        require(
            listOf(
                minSearchRadiusM, maxSearchRadiusM, sigmaZMetres, betaMetres,
                unmatchedDistanceM, maxRouteSlackM, maxSpeedMps,
            ).all { it.isFinite() },
        )
    }

    companion object {
        const val PAPER_SIGMA_Z_M: Double = 4.07
        private const val MAD_TO_SIGMA: Double = 1.4826

        /** Newson and Krumm eq. (5): σ_z = 1.4826 × median |z − x|. */
        fun sigmaFromResidualsM(distancesM: List<Double>): Double {
            require(distancesM.isNotEmpty())
            return MAD_TO_SIGMA * medianAbs(distancesM)
        }

        /** Gather and Schultze median estimator for the exponential β. */
        fun betaFromDeltasM(deltasM: List<Double>): Double {
            require(deltasM.isNotEmpty())
            return medianAbs(deltasM) / kotlin.math.ln(2.0)
        }

        private fun medianAbs(values: List<Double>): Double {
            val sorted = values.map { kotlin.math.abs(it) }.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                0.5 * (sorted[mid - 1] + sorted[mid])
            } else {
                sorted[mid]
            }
        }
    }
}

private fun buildOutgoing(edges: List<GraphEdge>): Map<Long, IntArray> {
    val buckets = HashMap<Long, MutableList<Int>>()
    edges.forEachIndexed { index, edge ->
        buckets.getOrPut(edge.fromNodeId) { ArrayList() }.add(index)
    }
    return buckets.mapValues { (_, list) -> list.toIntArray() }
}

private fun buildUndirectedDegree(edges: List<GraphEdge>): Map<Long, Int> {
    val neighbors = HashMap<Long, MutableSet<Long>>()
    fun link(a: Long, b: Long) {
        if (a == b) {
            return
        }
        neighbors.getOrPut(a) { HashSet() }.add(b)
        neighbors.getOrPut(b) { HashSet() }.add(a)
    }
    for (edge in edges) {
        link(edge.fromNodeId, edge.toNodeId)
    }
    return neighbors.mapValues { it.value.size }
}
