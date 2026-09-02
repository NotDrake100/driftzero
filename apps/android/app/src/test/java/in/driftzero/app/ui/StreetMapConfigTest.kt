package `in`.driftzero.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetMapConfigTest {
    @Test
    fun libertyIsPrimaryOpenFreeMapStyle() {
        assertEquals("https://tiles.openfreemap.org/styles/liberty", StreetMapConfig.STYLE_LIBERTY)
    }

    @Test
    fun brightIsFallbackOpenFreeMapStyle() {
        assertEquals("https://tiles.openfreemap.org/styles/bright", StreetMapConfig.STYLE_BRIGHT)
    }

    @Test
    fun nightSheetIsOpenFreeMapDarkOnTheSameHost() {
        assertEquals("https://tiles.openfreemap.org/styles/dark", StreetMapConfig.STYLE_DARK)
        assertEquals(StreetMapConfig.STYLE_LIBERTY, StreetMapConfig.hostedStyle(night = false))
        assertEquals(StreetMapConfig.STYLE_DARK, StreetMapConfig.hostedStyle(night = true))
        assertFalse(StreetMapConfig.STYLE_DARK.contains("tile.openstreetmap.org"))
    }

    @Test
    fun cameraFallbackIsWorldNotACity() {
        assertEquals(20.0, StreetMapConfig.WORLD_LAT_DEG, 0.0)
        assertEquals(0.0, StreetMapConfig.WORLD_LON_DEG, 0.0)
        assertTrue(StreetMapConfig.WORLD_ZOOM < 5.0)
        assertTrue(StreetMapConfig.STREET_ZOOM >= 15.0)
        assertEquals(StreetMapConfig.STREET_ZOOM, StreetMapConfig.CAMERA_ZOOM, 0.0)
        assertTrue(kotlin.math.abs(StreetMapConfig.WORLD_LAT_DEG - 18.5362) > 1.0)
        assertTrue(kotlin.math.abs(StreetMapConfig.WORLD_LON_DEG - 73.8938) > 1.0)
    }

    @Test
    fun ownVehiclePuckIsGoogleScaleBlue() {
        assertEquals(22, StreetMapConfig.PUCK_DISK_DP)
        assertEquals(3, StreetMapConfig.PUCK_RING_DP)
        assertEquals(0xFF1E6BFFL, StreetMapConfig.PUCK_COLOR_ARGB)
        assertEquals(InstrumentPalette.MARKER_BLUE, StreetMapConfig.PUCK_COLOR_ARGB)
        assertTrue(StreetMapConfig.TEXTURE_MODE)
    }

    @Test
    fun hostedSearchAndRouteArePublicMapApis() {
        assertEquals("https://photon.komoot.io/api/", StreetMapConfig.PHOTON_API)
        assertEquals("https://nominatim.openstreetmap.org/search", StreetMapConfig.NOMINATIM_SEARCH)
        assertEquals("https://router.project-osrm.org/route/v1/driving/", StreetMapConfig.OSRM_ROUTE)
        assertEquals(14, StreetMapConfig.SEARCH_BIAS_ZOOM)
        assertEquals(0.1, StreetMapConfig.SEARCH_BIAS_SCALE, 0.0)
        assertEquals(8.0, StreetMapConfig.SEARCH_CAMERA_MIN_ZOOM, 0.0)
        assertFalse(StreetMapConfig.USER_AGENT.contains("SIH"))
        assertFalse(StreetMapConfig.USER_AGENT.contains("LastKnown"))
        assertFalse(StreetMapConfig.USER_AGENT.contains("26168"))
        assertTrue(StreetMapConfig.USER_AGENT.contains("DriftZero"))
    }
}
