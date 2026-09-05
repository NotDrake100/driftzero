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
            oneway = !bidirectional,
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
            oneway = !bidirectional,
        )
        val edges = ArrayList<GraphEdge>()
        edges.add(west)
        edges.add(east)
        if (bidirectional) {
            edges.add(reverse(west, "west:rev"))
            edges.add(reverse(east, "east:rev"))
        }
        return graphOf(packageId, edges)
    }

    /**
     * Northbound [northM] then a 90 degree turn east for [eastM].
     * The corner is at [northM] along-track.
     */
    fun rightAngleRoad(
        originLat: Double = ORIGIN_LAT,
        originLon: Double = ORIGIN_LON,
        northM: Double = 1270.0,
        eastM: Double = 200.0,
        packageId: String = "fixture-right-angle",
    ): RoadGraph {
        val origin = Wgs84.offsetMetres(originLat, originLon, 0.0, 0.0)
        val corner = Wgs84.offsetMetres(originLat, originLon, northM, 0.0)
        val end = Wgs84.offsetMetres(originLat, originLon, northM, eastM)
        val points = listOf(
            GeoPoint(LatitudeDeg(origin.first), LongitudeDeg(origin.second)),
            GeoPoint(LatitudeDeg(corner.first), LongitudeDeg(corner.second)),
            GeoPoint(LatitudeDeg(end.first), LongitudeDeg(end.second)),
        )
        val (length, headings) = polylineLengthAndHeadings(points)
        val fwd = GraphEdge(
            id = "elbow",
            fromNodeId = 1L,
            toNodeId = 2L,
            points = points,
            lengthM = length,
            segmentHeadingsRad = headings,
            highway = "residential",
            oneway = true,
        )
        return graphOf(packageId, listOf(fwd))
    }

    fun roadThenTunnel(
        originLat: Double = ORIGIN_LAT,
        originLon: Double = ORIGIN_LON,
        approachM: Double = 200.0,
        tunnelM: Double = 120.0,
        packageId: String = "fixture-road-tunnel",
    ): RoadGraph {
        val approach = edge(
            id = "approach",
            fromId = 1L,
            toId = 2L,
            originLat = originLat,
            originLon = originLon,
            startEast = 0.0,
            startNorth = 0.0,
            endEast = 0.0,
            endNorth = approachM,
            oneway = true,
        )
        val tunnel = edge(
            id = "bore",
            fromId = 2L,
            toId = 3L,
            originLat = originLat,
            originLon = originLon,
            startEast = 0.0,
            startNorth = approachM,
            endEast = 0.0,
            endNorth = approachM + tunnelM,
            tunnel = true,
            layer = -1,
            oneway = true,
        )
        return graphOf(packageId, listOf(approach, tunnel))
    }

    fun singleRoad(
        originLat: Double = ORIGIN_LAT,
        originLon: Double = ORIGIN_LON,
        lengthM: Double = LENGTH_M,
        packageId: String = "fixture-single",
        tunnel: Boolean = false,
        bridge: Boolean = false,
        layer: Int = 0,
        oneway: Boolean = true,
    ): RoadGraph {
        val fwd = edge(
            id = "road",
            fromId = 1L,
            toId = 2L,
            originLat = originLat,
            originLon = originLon,
            startEast = 0.0,
            startNorth = 0.0,
            endEast = 0.0,
            endNorth = lengthM,
            layer = layer,
            bridge = bridge,
            tunnel = tunnel,
            oneway = oneway,
        )
        val edges = ArrayList<GraphEdge>()
        edges.add(fwd)
        if (!oneway) {
            edges.add(reverse(fwd, "road:rev"))
        }
        return graphOf(packageId, edges)
    }

    /**
     * T-junction at local (0, 0): west approach, east departure, south leg.
     * Junction node id 3 has undirected degree 3.
     */
    fun tJunction(
        originLat: Double = ORIGIN_LAT,
        originLon: Double = ORIGIN_LON,
        armM: Double = 100.0,
        packageId: String = "fixture-t-junction",
    ): RoadGraph {
        val west = edge(
            id = "west",
            fromId = 1L,
            toId = 3L,
            originLat = originLat,
            originLon = originLon,
            startEast = -armM,
            startNorth = 0.0,
            endEast = 0.0,
            endNorth = 0.0,
            oneway = true,
        )
        val east = edge(
            id = "east",
            fromId = 3L,
            toId = 4L,
            originLat = originLat,
            originLon = originLon,
            startEast = 0.0,
            startNorth = 0.0,
            endEast = armM,
            endNorth = 0.0,
            oneway = true,
        )
        val south = edge(
            id = "south",
            fromId = 3L,
            toId = 5L,
            originLat = originLat,
            originLon = originLon,
            startEast = 0.0,
            startNorth = 0.0,
            endEast = 0.0,
            endNorth = -armM,
            oneway = true,
        )
        return graphOf(packageId, listOf(west, east, south))
    }

    /** Flyover (layer 1, bridge) occupying the same 2D centerline as a surface road. */
    fun flyoverOverSurface(
        originLat: Double = ORIGIN_LAT,
        originLon: Double = ORIGIN_LON,
        lengthM: Double = LENGTH_M,
        packageId: String = "fixture-flyover",
    ): RoadGraph {
        val surface = edge(
            id = "surface",
            fromId = 1L,
            toId = 2L,
            originLat = originLat,
            originLon = originLon,
            startEast = 0.0,
            startNorth = 0.0,
            endEast = 0.0,
            endNorth = lengthM,
            layer = 0,
            oneway = true,
        )
        val flyover = edge(
            id = "flyover",
            fromId = 3L,
            toId = 4L,
            originLat = originLat,
            originLon = originLon,
            startEast = 0.0,
            startNorth = 0.0,
            endEast = 0.0,
            endNorth = lengthM,
            layer = 1,
            bridge = true,
            oneway = true,
        )
        return graphOf(packageId, listOf(surface, flyover))
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

    fun tunnelOsmXml(
        originLat: Double = ORIGIN_LAT,
        originLon: Double = ORIGIN_LON,
        lengthM: Double = LENGTH_M,
    ): String {
        val a = Wgs84.offsetMetres(originLat, originLon, 0.0, 0.0)
        val b = Wgs84.offsetMetres(originLat, originLon, lengthM, 0.0)
        return """
            <?xml version="1.0"?>
            <osm version="0.6">
              <node id="1" lat="${a.first}" lon="${a.second}"/>
              <node id="2" lat="${b.first}" lon="${b.second}"/>
              <way id="40">
                <nd ref="1"/><nd ref="2"/>
                <tag k="highway" v="primary"/>
                <tag k="oneway" v="yes"/>
                <tag k="tunnel" v="yes"/>
                <tag k="layer" v="-1"/>
              </way>
            </osm>
        """.trimIndent()
    }

    fun tJunctionOsmXml(
        originLat: Double = ORIGIN_LAT,
        originLon: Double = ORIGIN_LON,
        armM: Double = 100.0,
    ): String {
        val j = Wgs84.offsetMetres(originLat, originLon, 0.0, 0.0)
        val w = Wgs84.offsetMetres(originLat, originLon, 0.0, -armM)
        val e = Wgs84.offsetMetres(originLat, originLon, 0.0, armM)
        val s = Wgs84.offsetMetres(originLat, originLon, -armM, 0.0)
        return """
            <?xml version="1.0"?>
            <osm version="0.6">
              <node id="1" lat="${w.first}" lon="${w.second}"/>
              <node id="3" lat="${j.first}" lon="${j.second}"/>
              <node id="4" lat="${e.first}" lon="${e.second}"/>
              <node id="5" lat="${s.first}" lon="${s.second}"/>
              <way id="21">
                <nd ref="1"/><nd ref="3"/>
                <tag k="highway" v="residential"/>
                <tag k="oneway" v="yes"/>
              </way>
              <way id="22">
                <nd ref="3"/><nd ref="4"/>
                <tag k="highway" v="residential"/>
                <tag k="oneway" v="yes"/>
              </way>
              <way id="23">
                <nd ref="3"/><nd ref="5"/>
                <tag k="highway" v="residential"/>
                <tag k="oneway" v="yes"/>
              </way>
            </osm>
        """.trimIndent()
    }

    private fun graphOf(packageId: String, edges: List<GraphEdge>): RoadGraph {
        val nodes = LinkedHashMap<Long, GraphNode>()
        for (item in edges) {
            val start = item.points.first()
            val end = item.points.last()
            nodes[item.fromNodeId] = GraphNode(item.fromNodeId, start.latitude, start.longitude)
            nodes[item.toNodeId] = GraphNode(item.toNodeId, end.latitude, end.longitude)
        }
        return RoadGraph(packageId, nodes, edges)
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
        layer: Int = 0,
        bridge: Boolean = false,
        tunnel: Boolean = false,
        oneway: Boolean = false,
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
            layer = layer,
            bridge = bridge,
            tunnel = tunnel,
            oneway = oneway,
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
            layer = edge.layer,
            bridge = edge.bridge,
            tunnel = edge.tunnel,
            oneway = false,
        )
    }
}
