package `in`.driftzero.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPuckStyleTest {

    @Test
    fun fillIsSaturatedGoogleBlue() {
        assertEquals(0xFF1E6BFF, LocationPuckStyle.FILL_ARGB)
    }

    @Test
    fun haloAndOutlineAreHighContrastOnLightRoads() {
        assertEquals(0xFFFFFFFF, LocationPuckStyle.HALO_ARGB)
        assertEquals(0xFF0A2A6B, LocationPuckStyle.OUTLINE_ARGB)
        assertEquals(4, LocationPuckStyle.HALO_WIDTH_DP)
        assertTrue(LocationPuckStyle.OUTLINE_WIDTH_DP >= 3)
        assertTrue(LocationPuckStyle.FILL_RADIUS_DP >= 16)
    }

    @Test
    fun defaultTargetIsKoregaonParkBeforeGps() {
        assertEquals(18.5362, MapDefaults.KOREGAON_PARK_LATITUDE_DEG, 0.0)
        assertEquals(73.8938, MapDefaults.KOREGAON_PARK_LONGITUDE_DEG, 0.0)
        assertTrue(MapDefaults.OPEN_FREE_MAP_LIBERTY.contains("liberty"))
    }

    @Test
    fun iconScaleIsLargerThanDefaultLocationComponent() {
        assertTrue(LocationPuckStyle.ICON_SCALE > 1.0f)
        assertTrue(LocationPuckStyle.ACCURACY_ALPHA > 0f)
        assertTrue(LocationPuckStyle.ACCURACY_ALPHA < 0.4f)
    }
}
