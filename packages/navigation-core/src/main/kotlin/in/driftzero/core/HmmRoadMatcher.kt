package `in`.driftzero.core

import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Online / batch HMM map matcher after Newson and Krumm (ACM GIS 2009).
 *
 * Emission is a Gaussian of distance to the candidate polyline, plus a heading
 * term (Hummel / Quddus) that is ignored at low speed. Transition is an
 * exponential of |network route − great-circle| with graph connectivity.
 * Viterbi is log-space with a bounded beam.
 *
 * The ESKF lat/lon is never overwritten. [MapMatchResult.displayPose] is the
 * centerline projection for the map overlay only. Heading-only feedback is
 * [RoadHeadingFeedback]. Tunnel edges set
 * [MapMatchResult.onTunnel] so a GNSS gap can be treated as physical, not as
 * a reason to snap. Junction proximity is [MapMatchResult.nearJunction].
 */
class HmmRoadMatcher(
    private val config: HmmMatchConfig = HmmMatchConfig(),
) : RoadMatcher {
    private var previous: LatticeColumn? = null
    private var lastIncluded: Observation? = null
    private var lastResult: MapMatchResult? = null
    private val pathCache = HashMap<LongPair, Double?>()

    override fun reset() {
        previous = null
        lastIncluded = null
        lastResult = null
        pathCache.clear()
    }

    override fun update(state: FilterSnapshot, graph: RoadGraph): MapMatchResult {
        if (graph.isEmpty()) {
            return MapMatchResult(
                match = MapMatch(MapMatchStatus.NO_MAP, 0.0),
                packageId = graph.packageId,
            )
        }
        val observation = observationOf(state.state)
        val held = lastIncluded
        if (held != null && config.minMoveMetres > 0.0) {
            val moved = Wgs84.distanceMetres(
                held.latitudeDeg,
                held.longitudeDeg,
                observation.latitudeDeg,
                observation.longitudeDeg,
            )
            if (moved < config.minMoveMetres) {
                val replayed = lastResult?.let { reproject(observation, graph, it) }
                if (replayed != null) {
                    lastResult = replayed
                    return replayed
                }
            }
        }
        val column = step(observation, graph, previous)
        previous = column
        lastIncluded = observation
        val result = decodeChosen(column, graph, chosenIndex = 0)
        lastResult = result
        return result
    }

    /**
     * Batch Viterbi plus backtrack. This is the Newson-Krumm inference.
     * [update] is the live sliding-window form they speculated about.
     */
    fun matchSequence(
        states: List<FilterSnapshot>,
        graph: RoadGraph,
    ): List<MapMatchResult> {
        reset()
        if (graph.isEmpty()) {
            return List(states.size) {
                MapMatchResult(MapMatch(MapMatchStatus.NO_MAP, 0.0), packageId = graph.packageId)
            }
        }
        if (states.isEmpty()) {
            return emptyList()
        }
        val observations = states.map { observationOf(it.state) }
        val kept = ArrayList<Int>(observations.size)
        for (i in observations.indices) {
            val prevIdx = kept.lastOrNull()
            if (prevIdx == null || config.minMoveMetres <= 0.0) {
                kept.add(i)
                continue
            }
            val a = observations[prevIdx]
            val b = observations[i]
            val moved = Wgs84.distanceMetres(
                a.latitudeDeg, a.longitudeDeg, b.latitudeDeg, b.longitudeDeg,
            )
            if (moved >= config.minMoveMetres) {
                kept.add(i)
            }
        }
        val columns = ArrayList<LatticeColumn>(kept.size)
        var prev: LatticeColumn? = null
        for (index in kept) {
            val column = step(observations[index], graph, prev)
            columns.add(column)
            prev = column
        }
        previous = prev
        lastIncluded = kept.lastOrNull()?.let { observations[it] }
        val path = backtrack(columns)
        val decoded = columns.mapIndexed { index, column ->
            decodeChosen(column, graph, path[index])
        }
        lastResult = decoded.lastOrNull()
        var keptPos = 0
        return observations.indices.map { i ->
            if (keptPos < kept.size - 1 && i >= kept[keptPos + 1]) {
                keptPos += 1
            }
            val base = decoded[keptPos]
            if (kept[keptPos] == i) {
                base
            } else {
                reproject(observations[i], graph, base)
            }
        }
    }

    private fun step(
        observation: Observation,
        graph: RoadGraph,
        prev: LatticeColumn?,
    ): LatticeColumn {
        val radius = searchRadiusM(observation.horizontal95M)
        val hits = candidates(observation, graph, radius)
        val slots = ArrayList<Candidate>(hits.size + 1)
        for (hit in hits) {
            slots.add(Candidate.road(hit, emissionLog(observation, hit)))
        }
        slots.add(Candidate.unmatched(emissionLogUnmatched()))
        val scored = ArrayList<Scored>(slots.size)
        for (slot in slots) {
            if (prev == null) {
                scored.add(Scored(slot, slot.logEmit, -1))
                continue
            }
            var best = Double.NEGATIVE_INFINITY
            var back = -1
            prev.states.forEachIndexed { pi, parent ->
                val logT = transitionLog(parent.candidate, slot, observation, prev.observation, graph)
                val value = parent.logDelta + logT
                if (value > best) {
                    best = value
                    back = pi
                }
            }
            scored.add(Scored(slot, slot.logEmit + best, back))
        }
        val roadConnected = scored.any { row ->
            row.candidate.hit != null && row.logDelta.isFinite() && row.logDelta > UNLIKELY
        }
        if (prev != null && !roadConnected) {
            scored.clear()
            for (slot in slots) {
                scored.add(Scored(slot, slot.logEmit, -1))
            }
        }
        scored.sortByDescending { it.logDelta }
        val kept = scored.take(config.beamWidth)
        return LatticeColumn(observation, kept)
    }

    private fun candidates(
        observation: Observation,
        graph: RoadGraph,
        radiusM: Double,
    ): List<EdgeHit> {
        val nearby = graph.grid.query(observation.latitudeDeg, observation.longitudeDeg, radiusM)
        val hits = ArrayList<EdgeHit>(nearby.size)
        for (index in nearby) {
            val hit = projectOntoEdge(
                observation.latitudeDeg,
                observation.longitudeDeg,
                index,
                graph.edges[index],
            )
            if (hit.crossAbsM <= radiusM) {
                hits.add(hit)
            }
        }
        hits.sortBy { it.crossAbsM }
        return hits.take(config.maxCandidatesPerEpoch)
    }

    private fun emissionLog(observation: Observation, hit: EdgeHit): Double {
        val sigma = max(config.sigmaZMetres, observation.horizontal95M / 2.0)
        val distTerm = -0.5 * (hit.crossAbsM / sigma) * (hit.crossAbsM / sigma)
        val weight = headingWeight(observation.speedMps)
        if (weight <= 0.0 || observation.headingRad == null) {
            return distTerm
        }
        val dHead = headingDeltaRad(observation.headingRad, hit.headingRad)
        if (dHead > PI / 2.0) {
            return UNLIKELY
        }
        return distTerm + weight * (-0.5 * (dHead / config.sigmaHeadingRad) * (dHead / config.sigmaHeadingRad))
    }

    private fun emissionLogUnmatched(): Double {
        val sigma = config.sigmaZMetres
        val d = config.unmatchedDistanceM
        return -0.5 * (d / sigma) * (d / sigma)
    }

    private fun headingWeight(speedMps: Double): Double {
        if (speedMps <= config.headingMinSpeedMps) {
            return 0.0
        }
        if (speedMps >= config.headingFullSpeedMps) {
            return 1.0
        }
        val span = config.headingFullSpeedMps - config.headingMinSpeedMps
        return ((speedMps - config.headingMinSpeedMps) / span).coerceIn(0.0, 1.0)
    }

    private fun transitionLog(
        from: Candidate,
        to: Candidate,
        observation: Observation,
        previous: Observation,
        graph: RoadGraph,
    ): Double {
        if (from.hit == null && to.hit == null) {
            return 0.0
        }
        if (from.hit == null || to.hit == null) {
            return -abs(config.unmatchedDistanceM) / config.betaMetres
        }
        val dtS = (observation.timestampNs - previous.timestampNs).toDouble() / 1_000_000_000.0
        if (dtS < 0.0) {
            return Double.NEGATIVE_INFINITY
        }
        val greatCircle = Wgs84.distanceMetres(
            previous.latitudeDeg,
            previous.longitudeDeg,
            observation.latitudeDeg,
            observation.longitudeDeg,
        )
        val route = routeMetres(from.hit, to.hit, graph, greatCircle) ?: return Double.NEGATIVE_INFINITY
        if (route - greatCircle > config.maxRouteSlackM) {
            return Double.NEGATIVE_INFINITY
        }
        if (dtS > 1e-6 && route / dtS > config.maxSpeedMps) {
            return Double.NEGATIVE_INFINITY
        }
        val delta = abs(route - greatCircle)
        return -delta / config.betaMetres
    }

    private fun routeMetres(
        from: EdgeHit,
        to: EdgeHit,
        graph: RoadGraph,
        greatCircle: Double,
    ): Double? {
        val fromEdge = graph.edges[from.edgeIndex]
        val toEdge = graph.edges[to.edgeIndex]
        if (from.edgeIndex == to.edgeIndex) {
            val along = to.alongM - from.alongM
            return if (along >= -1.0) max(0.0, along) else null
        }
        if (isReversePair(fromEdge, toEdge)) {
            val fromAlongUndirected = from.alongM
            val toAlongUndirected = (toEdge.lengthM - to.alongM).coerceAtLeast(0.0)
            return abs(toAlongUndirected - fromAlongUndirected)
        }
        val remain = (fromEdge.lengthM - from.alongM).coerceAtLeast(0.0)
        val cutoff = greatCircle + config.maxRouteSlackM
        val via = shortestPathM(graph, fromEdge.toNodeId, toEdge.fromNodeId, cutoff) ?: return null
        return remain + via + to.alongM
    }

    private fun isReversePair(a: GraphEdge, b: GraphEdge): Boolean =
        a.fromNodeId == b.toNodeId && a.toNodeId == b.fromNodeId && a.fromNodeId != a.toNodeId

    private fun shortestPathM(
        graph: RoadGraph,
        fromNode: Long,
        toNode: Long,
        cutoff: Double,
    ): Double? {
        if (fromNode == toNode) {
            return 0.0
        }
        val key = LongPair(fromNode, toNode)
        if (pathCache.containsKey(key)) {
            return pathCache[key]
        }
        val dist = HashMap<Long, Double>()
        val pq = PriorityQueue<NodeCost>(compareBy { it.cost })
        dist[fromNode] = 0.0
        pq.add(NodeCost(fromNode, 0.0))
        var expanded = 0
        while (pq.isNotEmpty()) {
            val cur = pq.poll()
            if (cur.cost != dist[cur.node]) {
                continue
            }
            if (cur.node == toNode) {
                pathCache[key] = cur.cost
                return cur.cost
            }
            if (cur.cost > cutoff) {
                continue
            }
            expanded += 1
            if (expanded > config.maxDijkstraNodes) {
                break
            }
            val outs = graph.outgoing[cur.node] ?: continue
            for (edgeIndex in outs) {
                val edge = graph.edges[edgeIndex]
                val next = cur.cost + edge.lengthM
                if (next > cutoff) {
                    continue
                }
                val seen = dist[edge.toNodeId]
                if (seen == null || next < seen) {
                    dist[edge.toNodeId] = next
                    pq.add(NodeCost(edge.toNodeId, next))
                }
            }
        }
        pathCache[key] = null
        return null
    }

    private fun decodeChosen(
        column: LatticeColumn,
        graph: RoadGraph,
        chosenIndex: Int,
    ): MapMatchResult {
        val posteriors = softmax(column.states.map { it.logDelta })
        val entropy = entropyOf(posteriors)
        val chosen = column.states.getOrNull(chosenIndex) ?: column.states.first()
        val bestP = posteriors.getOrElse(chosenIndex) { posteriors.maxOrNull() ?: 0.0 }
        val ranked = posteriors.sortedDescending()
        val second = ranked.getOrElse(1) { 0.0 }
        val rankedIdx = posteriors.indices.sortedByDescending { posteriors[it] }
        val secondState = rankedIdx.getOrNull(1)?.let { column.states[it] }
        val hit = chosen.candidate.hit
        val status = when {
            hit == null -> MapMatchStatus.UNMATCHED
            bestP < config.matchedMinPosterior -> MapMatchStatus.AMBIGUOUS
            second > config.ambiguousSecondRatio * bestP -> MapMatchStatus.AMBIGUOUS
            else -> MapMatchStatus.MATCHED
        }
        val confidence = when (status) {
            MapMatchStatus.MATCHED -> bestP.coerceIn(0.0, 1.0)
            MapMatchStatus.AMBIGUOUS -> min(bestP, 0.49)
            MapMatchStatus.UNMATCHED -> 0.0
            MapMatchStatus.NO_MAP -> 0.0
        }
        val display = hit?.let { toDisplay(it, graph) }
        val edge = hit?.let { graph.edges[it.edgeIndex] }
        val junctionM = hit?.let { distanceToJunctionM(it.latitudeDeg, it.longitudeDeg, graph.edges[it.edgeIndex], graph) }
        val nearJunction = junctionM != null && junctionM <= config.junctionRadiusM
        return MapMatchResult(
            match = MapMatch(
                status = status,
                confidence = confidence,
                roadSegmentId = edge?.id,
            ),
            displayPose = display,
            candidateEntropy = entropy,
            candidateCount = column.states.size,
            packageId = graph.packageId,
            bestPosterior = bestP.coerceIn(0.0, 1.0),
            secondPosterior = second.coerceIn(0.0, 1.0),
            secondRoadSegmentId = secondState?.candidate?.hit?.let { graph.edges[it.edgeIndex].id },
            nearJunction = nearJunction,
            junctionDistanceM = junctionM,
            onTunnel = edge?.tunnel == true,
            onBridge = edge?.bridge == true,
            layer = edge?.layer ?: 0,
        )
    }

    private fun backtrack(columns: List<LatticeColumn>): IntArray {
        val path = IntArray(columns.size)
        if (columns.isEmpty()) {
            return path
        }
        var index = 0
        var best = Double.NEGATIVE_INFINITY
        columns.last().states.forEachIndexed { i, scored ->
            if (scored.logDelta > best) {
                best = scored.logDelta
                index = i
            }
        }
        for (t in columns.lastIndex downTo 0) {
            path[t] = index
            val back = columns[t].states.getOrNull(index)?.backIndex ?: -1
            index = if (back >= 0) back else 0
        }
        return path
    }

    private fun toDisplay(hit: EdgeHit, graph: RoadGraph): DisplayPose {
        val edge = graph.edges[hit.edgeIndex]
        return DisplayPose(
            position = GeoPoint(LatitudeDeg(hit.latitudeDeg), LongitudeDeg(hit.longitudeDeg)),
            heading = HeadingRadians(wrapHeadingRad(hit.headingRad)),
            alongTrackM = hit.alongM.coerceIn(0.0, edge.lengthM),
            crossTrackAbs = Metres(hit.crossAbsM),
        )
    }

    private fun reproject(
        observation: Observation,
        graph: RoadGraph,
        previous: MapMatchResult,
    ): MapMatchResult {
        val id = previous.match.roadSegmentId ?: return previous
        val index = graph.edges.indexOfFirst { it.id == id }
        if (index < 0) {
            return previous
        }
        val hit = projectOntoEdge(
            observation.latitudeDeg,
            observation.longitudeDeg,
            index,
            graph.edges[index],
        )
        val edge = graph.edges[index]
        val junctionM = distanceToJunctionM(hit.latitudeDeg, hit.longitudeDeg, edge, graph)
        return previous.copy(
            displayPose = toDisplay(hit, graph),
            nearJunction = junctionM != null && junctionM <= config.junctionRadiusM,
            junctionDistanceM = junctionM,
            onTunnel = edge.tunnel,
            onBridge = edge.bridge,
            layer = edge.layer,
        )
    }

    private fun searchRadiusM(horizontal95M: Double): Double {
        val fromCov = max(horizontal95M * 2.5, config.minSearchRadiusM)
        return min(config.maxSearchRadiusM, fromCov)
    }

    private fun observationOf(state: NavigationState): Observation = Observation(
        timestampNs = state.timestamp.value,
        latitudeDeg = state.position.latitude.value,
        longitudeDeg = state.position.longitude.value,
        headingRad = state.motion.heading.value,
        speedMps = state.motion.speed.value,
        horizontal95M = state.uncertainty.horizontal95.value,
    )

    private fun softmax(logValues: List<Double>): DoubleArray {
        if (logValues.isEmpty()) {
            return DoubleArray(0)
        }
        val peak = logValues.maxOrNull() ?: 0.0
        val weights = DoubleArray(logValues.size) { i ->
            val x = logValues[i] - peak
            if (x < -40.0) 0.0 else exp(x)
        }
        val z = weights.sum().let { if (it < 1e-18) 1.0 else it }
        for (i in weights.indices) {
            weights[i] /= z
        }
        return weights
    }

    private fun entropyOf(p: DoubleArray): Double {
        var h = 0.0
        for (value in p) {
            if (value > 1e-15) {
                h -= value * ln(value)
            }
        }
        return h
    }

    private data class Observation(
        val timestampNs: Long,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val headingRad: Double?,
        val speedMps: Double,
        val horizontal95M: Double,
    )

    private data class Candidate(
        val hit: EdgeHit?,
        val logEmit: Double,
    ) {
        companion object {
            fun road(hit: EdgeHit, logEmit: Double): Candidate = Candidate(hit, logEmit)
            fun unmatched(logEmit: Double): Candidate = Candidate(null, logEmit)
        }
    }

    private data class Scored(
        val candidate: Candidate,
        val logDelta: Double,
        val backIndex: Int,
    )

    private data class LatticeColumn(
        val observation: Observation,
        val states: List<Scored>,
    )

    private data class NodeCost(val node: Long, val cost: Double)

    private data class LongPair(val a: Long, val b: Long)

    companion object {
        private const val UNLIKELY: Double = -1e8
    }
}
