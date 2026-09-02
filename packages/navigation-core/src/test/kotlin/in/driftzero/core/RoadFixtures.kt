package `in`.driftzero.core

internal object RoadFixtures {
    const val ORIGIN_LAT: Double = 51.5
    const val ORIGIN_LON: Double = -0.12
    const val LENGTH_M: Double = 200.0
    const val SEP_M: Double = 20.0

    fun parallelRoads(
        originLat: Double = ORIGIN_LAT,
        originLon: Double = ORIGIN_LON,
        lengthM: Double = LENGTH_M,
        sepM: Double = SEP_M,
        bidirectional: Boolean = false,
        packageId: String = "fixture-parallel",
    ): RoadGraph {
        val west = edge(
            id = if (bidirectional) "west:fwd" else "west",
            fromId = 1L,
            toId = 2L,
            originLat = originLat,
            originLon = originLon,
            startEast = 0.0,
            startNorth = 0.0,
            endEast = 0.0,
            endNorth = lengthM,
        )
        val east = edge(
            id = if (bidirectional) "east:fwd" else "east",
            fromId = 3L,
            toId = 4L,
            originLat = originLat,
            originLon = originLon,
            startEast = sepM,
            startNorth = 0.0,
            endEast = sepM,
            endNorth = lengthM,
        )
        val edges = ArrayList<GraphEdge>()
        edges.add(west)
        edges.add(east)
        if (bidirectional) {
            edges.add(reverse(west, "west:rev"))
            edges.add(reverse(east, "east:rev"))
        }
        val nodes = LinkedHashMap<Long, GraphNode>()
        for (item in edges) {
            val start = item.points.first()
            val end = item.points.last()
            nodes[item.fromNodeId] = GraphNode(item.fromNodeId, start.latitude, start.longitude)
            nodes[item.toNodeId] = GraphNode(item.toNodeId, end.latitude, end.longitude)
        }
        return RoadGraph(packageId, nodes, edges)
    }

    fun snapshot(
        latitudeDeg: Double,
        longitudeDeg: Double,
        headingRad: Double = 0.0,
        speedMps: Double = 12.0,
        horizontal95M: Double = 8.0,
        timestampNs: Long = 0L,
    ): FilterSnapshot = FilterSnapshot(
        NavigationState(
            sequence = 0L,
            timestamp = Nanoseconds(timestampNs),
            mode = NavigationMode.DEAD_RECKONING,
            position = GeoPoint(LatitudeDeg(latitudeDeg), LongitudeDeg(longitudeDeg)),
            motion = Motion(MetresPerSecond(speedMps), HeadingRadians(wrapHeadingRad(headingRad))),
            uncertainty = Uncertainty(Metres(horizontal95M), 0.2, isCalibrated = true),
            gnssHealth = GnssHealth(0.4, 3.0),
            mapMatch = MapMatch(MapMatchStatus.NO_MAP, 0.0),
            health = ComponentHealth(
                sensorOk = true,
                modelOk = true,
                filterOk = true,
                mapOk = false,
            ),
            provenance = Provenance(CORE_VERSION, "b".repeat(64)),
        ),
    )

    fun atOffset(
        northM: Double,
        eastM: Double,
        headingRad: Double = 0.0,
        speedMps: Double = 12.0,
        horizontal95M: Double = 8.0,
        timestampNs: Long = 0L,
        originLat: Double = ORIGIN_LAT,
        originLon: Double = ORIGIN_LON,
    ): FilterSnapshot {
        val (lat, lon) = Wgs84.offsetMetres(originLat, originLon, northM, eastM)
        return snapshot(lat, lon, headingRad, speedMps, horizontal95M, timestampNs)
    }

    fun parallelOsmXml(
        originLat: Double = ORIGIN_LAT,
        originLon: Double = ORIGIN_LON,
        lengthM: Double = LENGTH_M,
        sepM: Double = SEP_M,
        oneway: Boolean = true,
    ): String {
        val west0 = Wgs84.offsetMetres(originLat, originLon, 0.0, 0.0)
        val west1 = Wgs84.offsetMetres(originLat, originLon, lengthM, 0.0)
        val east0 = Wgs84.offsetMetres(originLat, originLon, 0.0, sepM)
        val east1 = Wgs84.offsetMetres(originLat, originLon, lengthM, sepM)
        val onewayTag = if (oneway) """<tag k="oneway" v="yes"/>""" else ""
        return """
            <?xml version="1.0"?>
            <osm version="0.6">
              <node id="1" lat="${west0.first}" lon="${west0.second}"/>
              <node id="2" lat="${west1.first}" lon="${west1.second}"/>
              <node id="3" lat="${east0.first}" lon="${east0.second}"/>
              <node id="4" lat="${east1.first}" lon="${east1.second}"/>
              <way id="10">
                <nd ref="1"/><nd ref="2"/>
                <tag k="highway" v="residential"/>
                $onewayTag
              </way>
              <way id="11">
                <nd ref="3"/><nd ref="4"/>
                <tag k="highway" v="residential"/>
                $onewayTag
              </way>
            </osm>
        """.trimIndent()
    }

    private fun edge(
        id: String,
        fromId: Long,
        toId: Long,
        originLat: Double,
        originLon: Double,
        startEast: Double,
        startNorth: Double,
        endEast: Double,
        endNorth: Double,
    ): GraphEdge {
        val a = Wgs84.offsetMetres(originLat, originLon, startNorth, startEast)
        val b = Wgs84.offsetMetres(originLat, originLon, endNorth, endEast)
        val points = listOf(
            GeoPoint(LatitudeDeg(a.first), LongitudeDeg(a.second)),
            GeoPoint(LatitudeDeg(b.first), LongitudeDeg(b.second)),
        )
        val (length, headings) = polylineLengthAndHeadings(points)
        return GraphEdge(
            id = id,
            fromNodeId = fromId,
            toNodeId = toId,
            points = points,
            lengthM = length,
            segmentHeadingsRad = headings,
            highway = "residential",
        )
    }

    private fun reverse(edge: GraphEdge, id: String): GraphEdge {
        val points = edge.points.asReversed()
        val (length, headings) = polylineLengthAndHeadings(points)
        return GraphEdge(
            id = id,
            fromNodeId = edge.toNodeId,
            toNodeId = edge.fromNodeId,
            points = points,
            lengthM = length,
            segmentHeadingsRad = headings,
            highway = edge.highway,
            osmWayId = edge.osmWayId,
        )
    }
}
