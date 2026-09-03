package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmGraphLoaderTest {
    @Test
    fun londonXmlIsNotPuneLocked() {
        val xml = RoadFixtures.parallelOsmXml()
        val graph = OsmGraphLoader.loadXml(xml, "london-parallel")
        assertEquals("london-parallel", graph.packageId)
        assertEquals(2, graph.edges.size)
        assertTrue(graph.edges.all { it.highway == "residential" })
        val ids = graph.edges.map { it.id }.toSet()
        assertTrue(ids.any { it.startsWith("way/10") })
        assertTrue(ids.any { it.startsWith("way/11") })
        val lat = graph.nodes.values.first().latitude.value
        assertTrue(lat in 51.4..51.6)
    }

    @Test
    fun tokyoBboxDropsLondonWays() {
        val xml = RoadFixtures.parallelOsmXml()
        val tokyo = Wgs84Bbox(35.6, 139.6, 35.8, 139.9)
        val graph = OsmGraphLoader.loadXml(xml, "tokyo-clip", tokyo)
        assertTrue(graph.isEmpty())
    }

    @Test
    fun equatorExtractLoads() {
        val xml = RoadFixtures.parallelOsmXml(originLat = 0.0, originLon = 10.0)
        val box = Wgs84Bbox(-0.01, 9.99, 0.02, 10.02)
        val graph = OsmGraphLoader.loadXml(xml, "equator-sample", box)
        assertEquals(2, graph.edges.size)
    }

    @Test
    fun bidirectionalWayEmitsTwoDirectedEdges() {
        val xml = RoadFixtures.parallelOsmXml(oneway = false)
        val graph = OsmGraphLoader.loadXml(xml, "two-way")
        assertEquals(4, graph.edges.size)
        assertTrue(graph.edges.any { it.id.endsWith(":fwd") })
        assertTrue(graph.edges.any { it.id.endsWith(":rev") })
    }

    @Test
    fun footwayIsIgnored() {
        val xml = """
            <?xml version="1.0"?>
            <osm version="0.6">
              <node id="1" lat="40.7" lon="-74.0"/>
              <node id="2" lat="40.701" lon="-74.0"/>
              <way id="9">
                <nd ref="1"/><nd ref="2"/>
                <tag k="highway" v="footway"/>
              </way>
            </osm>
        """.trimIndent()
        val graph = OsmGraphLoader.loadXml(xml, "foot")
        assertTrue(graph.isEmpty())
    }

    @Test
    fun pbfRoundTripMatchesXmlEdgeCount() {
        val nodes = listOf(
            Triple(1L, 51.5, -0.12),
            Triple(2L, 51.5018, -0.12),
            Triple(3L, 51.5, -0.1197),
            Triple(4L, 51.5018, -0.1197),
        )
        val ways = listOf(
            PbfWay(10L, listOf(1L, 2L), mapOf("highway" to "residential", "oneway" to "yes")),
            PbfWay(11L, listOf(3L, 4L), mapOf("highway" to "residential", "oneway" to "yes")),
        )
        val bytes = OsmPbfWriter.write(nodes, ways)
        val graph = OsmGraphLoader.load(bytes, "pbf-london", OsmFormat.PBF)
        assertEquals(2, graph.edges.size)
        assertTrue(graph.nodes.isNotEmpty())
    }

    @Test
    fun matcherRunsOnLoadedXml() {
        val graph = OsmGraphLoader.loadXml(RoadFixtures.parallelOsmXml(), "osm-match")
        val matcher = HmmRoadMatcher()
        val states = (1..6).map { i ->
            RoadFixtures.atOffset(i * 20.0, 2.0, timestampNs = i * 1_000_000_000L)
        }
        val path = matcher.matchSequence(states, graph)
        assertTrue(path.all { it.match.status == MapMatchStatus.MATCHED })
        assertTrue(path.map { it.match.roadSegmentId }.distinct().size == 1)
    }

    @Test
    fun tunnelAndLayerTagsAreStored() {
        val graph = OsmGraphLoader.loadXml(RoadFixtures.tunnelOsmXml(), "tunnel-pack")
        assertEquals(1, graph.edges.size)
        val edge = graph.edges.single()
        assertTrue(edge.tunnel)
        assertTrue(!edge.bridge)
        assertEquals(-1, edge.layer)
        assertTrue(edge.oneway)
        assertEquals("primary", edge.highway)
    }

    @Test
    fun onewayFalseStoresTwoDirectedEdges() {
        val graph = OsmGraphLoader.loadXml(RoadFixtures.parallelOsmXml(oneway = false), "two-way-flag")
        assertTrue(graph.edges.all { !it.oneway })
        assertEquals(4, graph.edges.size)
    }

    @Test
    fun tJunctionNodeHasDegreeThree() {
        val graph = OsmGraphLoader.loadXml(RoadFixtures.tJunctionOsmXml(), "t-xml")
        assertEquals(3, graph.degreeOf(3L))
        assertTrue(graph.isJunction(3L))
        assertEquals(1, graph.degreeOf(1L))
        assertTrue(graph.edges.all { it.oneway })
    }

    @Test
    fun pbfStoresTunnelLayerAndOneway() {
        val originLat = 51.5
        val originLon = -0.12
        val a = Wgs84.offsetMetres(originLat, originLon, 0.0, 0.0)
        val b = Wgs84.offsetMetres(originLat, originLon, 200.0, 0.0)
        val nodes = listOf(
            Triple(1L, a.first, a.second),
            Triple(2L, b.first, b.second),
        )
        val ways = listOf(
            PbfWay(
                40L,
                listOf(1L, 2L),
                mapOf(
                    "highway" to "primary",
                    "oneway" to "yes",
                    "tunnel" to "yes",
                    "layer" to "-1",
                    "bridge" to "no",
                ),
            ),
        )
        val graph = OsmGraphLoader.load(OsmPbfWriter.write(nodes, ways), "pbf-tunnel", OsmFormat.PBF)
        assertEquals(1, graph.edges.size)
        val edge = graph.edges.single()
        assertTrue(edge.tunnel)
        assertTrue(!edge.bridge)
        assertEquals(-1, edge.layer)
        assertTrue(edge.oneway)
    }
}
