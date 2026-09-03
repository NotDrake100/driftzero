package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRouterTest {
    @Test
    fun tJunctionWestToSouthIsRightTurn() {
        val graph = RoadFixtures.tJunction()
        val router = LocalRouter(graph)
        val origin = Wgs84.offsetMetres(RoadFixtures.ORIGIN_LAT, RoadFixtures.ORIGIN_LON, 0.0, -50.0)
        val dest = Wgs84.offsetMetres(RoadFixtures.ORIGIN_LAT, RoadFixtures.ORIGIN_LON, -50.0, 0.0)
        val route = router.route(origin.first, origin.second, dest.first, dest.second)
        assertNotNull(route)
        val built = route!!
        assertTrue(built.points.size >= 2)
        assertTrue(built.totalDistanceM in 80.0..140.0)
        val turn = built.steps.first { it.type == ManeuverType.TURN || it.type == ManeuverType.END_OF_ROAD }
        assertEquals(ManeuverModifier.RIGHT, turn.modifier)
        assertEquals(ManeuverType.ARRIVE, built.steps.last().type)
    }

    @Test
    fun onewayBlocksReverseTravel() {
        val graph = RoadFixtures.parallelRoads(bidirectional = false)
        val router = LocalRouter(graph)
        val south = Wgs84.offsetMetres(RoadFixtures.ORIGIN_LAT, RoadFixtures.ORIGIN_LON, 10.0, 0.0)
        val north = Wgs84.offsetMetres(RoadFixtures.ORIGIN_LAT, RoadFixtures.ORIGIN_LON, 190.0, 0.0)
        val forward = router.route(south.first, south.second, north.first, north.second)
        assertNotNull(forward)
        val reverse = router.route(north.first, north.second, south.first, south.second)
        assertNull(reverse)
    }

    @Test
    fun namesFromCompactBinSurviveLoad() {
        val a = Wgs84.offsetMetres(RoadFixtures.ORIGIN_LAT, RoadFixtures.ORIGIN_LON, 0.0, 0.0)
        val b = Wgs84.offsetMetres(RoadFixtures.ORIGIN_LAT, RoadFixtures.ORIGIN_LON, 200.0, 0.0)
        val bytes = OsmPbfWriter.write(
            listOf(Triple(1L, a.first, a.second), Triple(2L, b.first, b.second)),
            listOf(
                PbfWay(
                    10L,
                    listOf(1L, 2L),
                    mapOf("highway" to "residential", "oneway" to "yes", "name" to "High Street"),
                ),
            ),
        )
        val (loaded, names) = RoadGraphBin.load(bytes, "named-road")
        assertEquals(1, loaded.edges.size)
        val router = LocalRouter(loaded, names)
        assertEquals("High Street", router.nameOf(loaded.edges.first()))
    }
}

class RoadGraphBinTest {
    @Test
    fun compactBinKeepsTunnelLayerOnewayAndLoadsViaOsmGraphLoader() {
        val graph = RoadFixtures.singleRoad(tunnel = true, layer = -1, oneway = true)
        val bytes = RoadGraphBin.write(graph)
        val viaLoader = OsmGraphLoader.load(bytes, "bin-tunnel", OsmFormat.PBF)
        assertEquals(1, viaLoader.edges.size)
        val edge = viaLoader.edges.single()
        assertTrue(edge.tunnel)
        assertEquals(-1, edge.layer)
        assertTrue(edge.oneway)
        assertEquals("residential", edge.highway)
        val tmp = java.nio.file.Files.createTempFile("graph", ".bin")
        try {
            java.nio.file.Files.write(tmp, bytes)
            val fromName = OsmGraphLoader.load(tmp, "bin-path")
            assertEquals(1, fromName.edges.size)
            assertTrue(fromName.edges.single().tunnel)
        } finally {
            java.nio.file.Files.deleteIfExists(tmp)
        }
    }
}
