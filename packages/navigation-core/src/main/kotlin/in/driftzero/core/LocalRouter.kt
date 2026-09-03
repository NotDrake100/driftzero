package `in`.driftzero.core

import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/**
 * Causal on-device router over a directed [RoadGraph]. Oneway is already
 * encoded as missing reverse edges. Highway class scales travel cost.
 * Names come from OSM `name` tags stored in compact [graph.bin].
 */
class LocalRouter(
    private val graph: RoadGraph,
    private val namesByWayId: Map<Long, String> = emptyMap(),
) {
    fun route(
        fromLatitudeDeg: Double,
        fromLongitudeDeg: Double,
        toLatitudeDeg: Double,
        toLongitudeDeg: Double,
    ): GuidanceRoute? {
        if (graph.isEmpty()) {
            return null
        }
        val start = snap(fromLatitudeDeg, fromLongitudeDeg) ?: return null
        val end = snap(toLatitudeDeg, toLongitudeDeg) ?: return null
        val edges = connect(start, end) ?: return null
        if (edges.isEmpty()) {
            return null
        }
        val points = ArrayList<RoutePoint>()
        val edgeAtPoint = ArrayList<Int>()
        fun addPoint(point: GeoPoint, edgeIndex: Int) {
            val last = points.lastOrNull()
            if (last != null &&
                abs(last.latitudeDeg - point.latitude.value) < 1e-9 &&
                abs(last.longitudeDeg - point.longitude.value) < 1e-9
            ) {
                return
            }
            points += RoutePoint(point.latitude.value, point.longitude.value)
            edgeAtPoint += edgeIndex
        }
        for (piece in edges) {
            val pts = slice(graph.edges[piece.edgeIndex], piece.fromM, piece.toM)
            for (p in pts) {
                addPoint(p, piece.edgeIndex)
            }
        }
        if (points.size < 2) {
            return null
        }
        val steps = maneuvers(edges, points)
        val distance = edges.sumOf { abs(it.toM - it.fromM) }
        val duration = edges.sumOf { piece ->
            abs(piece.toM - piece.fromM) / speedMps(graph.edges[piece.edgeIndex].highway)
        }
        return GuidanceRoute(points, steps, distance, duration)
    }

    fun nameOf(edge: GraphEdge): String? {
        val named = edge.osmWayId?.let { namesByWayId[it] }
        return named?.takeIf { it.isNotEmpty() }
    }

    private fun snap(latitudeDeg: Double, longitudeDeg: Double): EdgeHit? {
        var best: EdgeHit? = null
        for (radius in SNAP_RADII_M) {
            val ids = graph.grid.query(latitudeDeg, longitudeDeg, radius)
            for (i in ids) {
                val hit = projectOntoEdge(latitudeDeg, longitudeDeg, i, graph.edges[i])
                if (hit.crossAbsM > radius) {
                    continue
                }
                if (best == null || hit.crossAbsM < best.crossAbsM) {
                    best = hit
                }
            }
            if (best != null) {
                return best
            }
        }
        return best
    }

    private fun connect(start: EdgeHit, end: EdgeHit): List<EdgePiece>? {
        if (start.edgeIndex == end.edgeIndex && end.alongM >= start.alongM - 1.0) {
            val to = max(end.alongM, start.alongM)
            return listOf(EdgePiece(start.edgeIndex, start.alongM, to))
        }
        val startEdge = graph.edges[start.edgeIndex]
        val endEdge = graph.edges[end.edgeIndex]
        val goal = endEdge.fromNodeId
        val origin = startEdge.toNodeId
        val firstCost = (startEdge.lengthM - start.alongM) * highwayWeight(startEdge.highway)
        if (origin == goal) {
            return listOf(
                EdgePiece(start.edgeIndex, start.alongM, startEdge.lengthM),
                EdgePiece(end.edgeIndex, 0.0, end.alongM),
            ).filter { abs(it.toM - it.fromM) > 0.5 }
        }
        data class NodeCost(val node: Long, val cost: Double, val heur: Double)
        val pq = PriorityQueue<NodeCost>(compareBy { it.cost + it.heur })
        val best = HashMap<Long, Double>()
        val via = HashMap<Long, Pair<Long, Int>>()
        fun heur(node: Long): Double {
            val n = graph.nodes[node] ?: return 0.0
            val e = graph.nodes[goal] ?: return 0.0
            return Wgs84.distanceMetres(
                n.latitude.value, n.longitude.value,
                e.latitude.value, e.longitude.value,
            ) * MIN_WEIGHT
        }
        best[origin] = firstCost
        pq.add(NodeCost(origin, firstCost, heur(origin)))
        var visited = 0
        while (pq.isNotEmpty() && visited < MAX_NODES) {
            val cur = pq.poll()
            if (cur.cost > (best[cur.node] ?: Double.POSITIVE_INFINITY) + 1e-6) {
                continue
            }
            visited += 1
            if (cur.node == goal) {
                break
            }
            val outgoing = graph.outgoing[cur.node] ?: continue
            for (edgeIndex in outgoing) {
                val edge = graph.edges[edgeIndex]
                val nextCost = cur.cost + edge.lengthM * highwayWeight(edge.highway)
                val prev = best[edge.toNodeId]
                if (prev != null && nextCost >= prev) {
                    continue
                }
                best[edge.toNodeId] = nextCost
                via[edge.toNodeId] = cur.node to edgeIndex
                pq.add(NodeCost(edge.toNodeId, nextCost, heur(edge.toNodeId)))
            }
        }
        if (goal !in via && origin != goal) {
            return null
        }
        val reversed = ArrayList<Int>()
        var node = goal
        while (node != origin) {
            val step = via[node] ?: return null
            reversed.add(step.second)
            node = step.first
        }
        reversed.reverse()
        val pieces = ArrayList<EdgePiece>(reversed.size + 2)
        pieces += EdgePiece(start.edgeIndex, start.alongM, startEdge.lengthM)
        for (edgeIndex in reversed) {
            val edge = graph.edges[edgeIndex]
            pieces += EdgePiece(edgeIndex, 0.0, edge.lengthM)
        }
        pieces += EdgePiece(end.edgeIndex, 0.0, end.alongM)
        return pieces.filter { abs(it.toM - it.fromM) > 0.5 }
    }

    private fun slice(edge: GraphEdge, fromM: Double, toM: Double): List<GeoPoint> {
        val lo = fromM.coerceIn(0.0, edge.lengthM)
        val hi = toM.coerceIn(0.0, edge.lengthM)
        if (hi <= lo + 0.2) {
            return emptyList()
        }
        val out = ArrayList<GeoPoint>()
        var walked = 0.0
        for (i in 0 until edge.points.size - 1) {
            val a = edge.points[i]
            val b = edge.points[i + 1]
            val (north, east) = Wgs84.northEastMetres(
                a.latitude.value, a.longitude.value,
                b.latitude.value, b.longitude.value,
            )
            val seg = kotlin.math.hypot(north, east)
            val start = walked
            val end = walked + seg
            if (end < lo - 1e-6) {
                walked = end
                continue
            }
            if (start > hi + 1e-6) {
                break
            }
            if (start >= lo - 1e-6 || out.isEmpty()) {
                if (lo > start && lo < end && seg > 1e-6) {
                    val t = (lo - start) / seg
                    out += interpolate(a, b, t)
                } else if (out.isEmpty()) {
                    out += a
                }
            }
            if (hi >= end - 1e-6) {
                out += b
            } else if (hi > start && seg > 1e-6) {
                val t = (hi - start) / seg
                out += interpolate(a, b, t)
                break
            }
            walked = end
        }
        if (out.size < 2 && edge.points.size >= 2) {
            return listOf(edge.points.first(), edge.points.last())
        }
        return out
    }

    private fun interpolate(a: GeoPoint, b: GeoPoint, t: Double): GeoPoint {
        val lat = a.latitude.value + t * (b.latitude.value - a.latitude.value)
        val lon = a.longitude.value + t * (b.longitude.value - a.longitude.value)
        return GeoPoint(LatitudeDeg(lat), LongitudeDeg(lon))
    }

    private fun maneuvers(pieces: List<EdgePiece>, points: List<RoutePoint>): List<RouteStep> {
        val steps = ArrayList<RouteStep>()
        val first = graph.edges[pieces.first().edgeIndex]
        steps += RouteStep(
            type = ManeuverType.DEPART,
            modifier = ManeuverModifier.STRAIGHT,
            roadName = nameOf(first),
            exitNumber = null,
            distanceM = abs(pieces.first().toM - pieces.first().fromM),
            durationS = abs(pieces.first().toM - pieces.first().fromM) / speedMps(first.highway),
            startIndex = 0,
        )
        var pointIndex = 0
        for (i in 0 until pieces.size - 1) {
            pointIndex = (pointIndex + 1).coerceAtMost(points.lastIndex)
            val a = graph.edges[pieces[i].edgeIndex]
            val b = graph.edges[pieces[i + 1].edgeIndex]
            val fromHdg = a.segmentHeadingsRad.last()
            val toHdg = b.segmentHeadingsRad.first()
            val delta = signedHeadingDeltaRad(fromHdg, toHdg)
            val modifier = modifierOf(delta)
            val nameChanged = nameOf(a) != nameOf(b)
            val junction = graph.isJunction(a.toNodeId)
            val type = when {
                abs(delta) > 2.6 -> ManeuverType.UTURN
                modifier == ManeuverModifier.STRAIGHT && nameChanged -> ManeuverType.NEW_NAME
                modifier == ManeuverModifier.STRAIGHT && !junction -> continue
                modifier == ManeuverModifier.STRAIGHT -> ManeuverType.CONTINUE
                else -> ManeuverType.TURN
            }
            val dist = abs(pieces[i + 1].toM - pieces[i + 1].fromM)
            steps += RouteStep(
                type = type,
                modifier = if (type == ManeuverType.UTURN) ManeuverModifier.UTURN else modifier,
                roadName = nameOf(b),
                exitNumber = null,
                distanceM = dist,
                durationS = dist / speedMps(b.highway),
                startIndex = pointIndex.coerceIn(0, points.lastIndex),
            )
        }
        steps += RouteStep(
            type = ManeuverType.ARRIVE,
            modifier = ManeuverModifier.NONE,
            roadName = nameOf(graph.edges[pieces.last().edgeIndex]),
            exitNumber = null,
            distanceM = 0.0,
            durationS = 0.0,
            startIndex = points.lastIndex,
        )
        return steps
    }

    private fun modifierOf(deltaRad: Double): ManeuverModifier {
        val deg = deltaRad * 180.0 / PI
        return when {
            deg > 170.0 || deg < -170.0 -> ManeuverModifier.UTURN
            deg >= 135.0 -> ManeuverModifier.SHARP_RIGHT
            deg >= 45.0 -> ManeuverModifier.RIGHT
            deg >= 20.0 -> ManeuverModifier.SLIGHT_RIGHT
            deg <= -135.0 -> ManeuverModifier.SHARP_LEFT
            deg <= -45.0 -> ManeuverModifier.LEFT
            deg <= -20.0 -> ManeuverModifier.SLIGHT_LEFT
            else -> ManeuverModifier.STRAIGHT
        }
    }

    private data class EdgePiece(val edgeIndex: Int, val fromM: Double, val toM: Double)

    companion object {
        private val SNAP_RADII_M = doubleArrayOf(75.0, 200.0, 400.0)
        private const val MAX_NODES: Int = 40_000
        private const val MIN_WEIGHT: Double = 0.8

        /**
         * Load [graph.bin] for A→B. Full pack when the heap can hold it.
         * Otherwise the in-memory clip is union(origin, dest) plus margin.
         */
        fun load(
            bytes: ByteArray,
            packageId: String,
            originLatDeg: Double?,
            originLonDeg: Double?,
            destLatDeg: Double?,
            destLonDeg: Double?,
            maxHeapBytes: Long,
            marginM: Double = LocalGraphPolicy.MARGIN_M,
        ): LocalRouter? {
            val window = LocalGraphPolicy.clipForLoad(
                packBytes = bytes.size.toLong(),
                maxHeapBytes = maxHeapBytes,
                originLatDeg = originLatDeg,
                originLonDeg = originLonDeg,
                destLatDeg = destLatDeg,
                destLonDeg = destLonDeg,
                marginM = marginM,
            )
            val (graph, names) = RoadGraphBin.load(bytes, packageId, window)
            if (graph.isEmpty()) {
                return null
            }
            return LocalRouter(graph, names)
        }

        fun highwayWeight(highway: String): Double = when (highway) {
            "motorway", "motorway_link" -> 0.8
            "trunk", "trunk_link" -> 0.9
            "primary", "primary_link" -> 1.0
            "secondary", "secondary_link" -> 1.15
            "tertiary", "tertiary_link" -> 1.3
            "unclassified", "residential" -> 1.5
            "living_street", "service", "road" -> 1.8
            else -> 2.0
        }

        fun speedMps(highway: String): Double = when (highway) {
            "motorway", "motorway_link" -> 25.0
            "trunk", "trunk_link" -> 20.0
            "primary", "primary_link" -> 14.0
            "secondary", "secondary_link" -> 11.0
            "tertiary", "tertiary_link" -> 9.0
            "unclassified", "residential" -> 8.0
            else -> 5.0
        }
    }
}
