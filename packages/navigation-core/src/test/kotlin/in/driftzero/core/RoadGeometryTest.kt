package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class RoadGeometryTest {
    @Test
    fun signedHeadingWrapsAroundPi() {
        assertEquals(0.0, signedHeadingDeltaRad(0.0, 0.0), 0.0)
        assertEquals(PI / 2.0, signedHeadingDeltaRad(0.0, PI / 2.0), 1e-12)
        assertEquals(-PI / 2.0, signedHeadingDeltaRad(PI / 2.0, 0.0), 1e-12)
        val wrap = signedHeadingDeltaRad(0.1, TWO_PI - 0.1)
        assertTrue(wrap < 0.0)
        assertEquals(-0.2, wrap, 1e-9)
        assertEquals(headingDeltaRad(0.1, TWO_PI - 0.1), abs(wrap), 1e-12)
    }

    @Test
    fun distanceToJunctionUsesDegreeThreeEndpointsOnly() {
        val graph = RoadFixtures.tJunction()
        val west = graph.edges.first { it.id == "west" }
        val (lat, lon) = Wgs84.offsetMetres(RoadFixtures.ORIGIN_LAT, RoadFixtures.ORIGIN_LON, 0.0, -15.0)
        val d = distanceToJunctionM(lat, lon, west, graph)
        assertTrue(d != null && d <= 16.0)
        val farLatLon = Wgs84.offsetMetres(RoadFixtures.ORIGIN_LAT, RoadFixtures.ORIGIN_LON, 0.0, -80.0)
        val far = distanceToJunctionM(farLatLon.first, farLatLon.second, west, graph)
        assertTrue(far != null && far > 70.0)
        val single = RoadFixtures.singleRoad()
        val road = single.edges.single()
        val mid = Wgs84.offsetMetres(RoadFixtures.ORIGIN_LAT, RoadFixtures.ORIGIN_LON, 100.0, 0.0)
        assertNull(distanceToJunctionM(mid.first, mid.second, road, single))
    }
}
