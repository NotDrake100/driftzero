package `in`.driftzero.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStartTest {
    @Test
    fun liveGpsWinsOverStoredFix() {
        val start = CameraStartResolver.resolve(
            liveGps = TravelLatLng(40.758, -73.985),
            lastFix = TravelLatLng(18.5362, 73.8938),
        )
        assertEquals(40.758, start.latitudeDeg, 0.0001)
        assertEquals(-73.985, start.longitudeDeg, 0.0001)
        assertEquals(StreetMapConfig.STREET_ZOOM, start.zoom, 0.0)
        assertTrue(start.isStreetLevel)
    }

    @Test
    fun storedFixUsedWhenGpsMissing() {
        val start = CameraStartResolver.resolve(
            liveGps = null,
            lastFix = TravelLatLng(51.5074, -0.1278),
        )
        assertEquals(51.5074, start.latitudeDeg, 0.0001)
        assertEquals(-0.1278, start.longitudeDeg, 0.0001)
        assertTrue(start.isStreetLevel)
    }

    @Test
    fun worldOverviewWhenNothingKnown() {
        val start = CameraStartResolver.resolve(liveGps = null, lastFix = null)
        assertEquals(StreetMapConfig.WORLD_LAT_DEG, start.latitudeDeg, 0.0)
        assertEquals(StreetMapConfig.WORLD_LON_DEG, start.longitudeDeg, 0.0)
        assertEquals(StreetMapConfig.WORLD_ZOOM, start.zoom, 0.0)
        assertFalse(start.isStreetLevel)
        assertTrue(kotlin.math.abs(start.latitudeDeg - 18.5362) > 1.0)
        assertTrue(kotlin.math.abs(start.longitudeDeg - 73.8938) > 1.0)
    }

    @Test
    fun invalidStoredFixIsIgnored() {
        val start = CameraStartResolver.resolve(
            liveGps = null,
            lastFix = TravelLatLng(91.0, 0.0),
        )
        assertEquals(StreetMapConfig.WORLD_ZOOM, start.zoom, 0.0)
    }

    @Test
    fun searchBiasPrefersFixThenCamera() {
        val fromFix = searchBiasLatLon(35.68, 139.76, 40.0, -74.0)
        assertEquals(35.68, fromFix!!.first, 0.0)
        assertEquals(139.76, fromFix.second, 0.0)
        val fromCamera = searchBiasLatLon(null, null, 48.86, 2.35)
        assertEquals(48.86, fromCamera!!.first, 0.0)
        assertEquals(2.35, fromCamera.second, 0.0)
        assertNull(searchBiasLatLon(null, null, null, null))
    }

    @Test
    fun searchBiasIgnoresWorldOverviewCamera() {
        assertNull(searchBiasLatLon(null, null, 20.0, 0.0, 2.0))
        val city = searchBiasLatLon(null, null, 51.5074, -0.1278, 14.0)
        assertEquals(51.5074, city!!.first, 0.0)
        assertEquals(-0.1278, city.second, 0.0)
        val fromFix = searchBiasLatLon(35.68, 139.76, 20.0, 0.0, 2.0)
        assertEquals(35.68, fromFix!!.first, 0.0)
        assertEquals(139.76, fromFix.second, 0.0)
    }

    @Test
    fun routeOriginPrefersFusedPose() {
        val fromPose = routeOrigin(40.758, -73.985, TravelLatLng(18.5362, 73.8938))
        assertEquals(40.758, fromPose!!.latitudeDeg, 0.0001)
        assertEquals(-73.985, fromPose.longitudeDeg, 0.0001)
        val fromMap = routeOrigin(null, null, TravelLatLng(51.5074, -0.1278))
        assertEquals(51.5074, fromMap!!.latitudeDeg, 0.0001)
        assertNull(routeOrigin(null, null, null))
    }

    @Test
    fun searchBiasKeyQuantizesToAboutAKilometre() {
        assertEquals("", searchBiasKey(null))
        assertEquals(searchBiasKey(40.758 to -73.985), searchBiasKey(40.756 to -73.987))
        assertTrue(searchBiasKey(40.758 to -73.985) != searchBiasKey(null))
        assertTrue(searchBiasKey(40.758 to -73.985) != searchBiasKey(18.52 to 73.85))
    }
}
