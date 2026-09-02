package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class Wgs84Test {
    @Test
    fun tenMetresNorthAtEquatorMatchesRadiusScale() {
        val (lat, lon) = Wgs84.offsetMetres(0.0, 0.0, 10.0, 0.0)
        val expectedLat = (10.0 / Wgs84.MEAN_RADIUS_M) * 180.0 / PI
        assertEquals(expectedLat, lat, 1e-12)
        assertEquals(0.0, lon, 1e-12)
        val (north, east) = Wgs84.northEastMetres(0.0, 0.0, lat, lon)
        assertEquals(10.0, north, 1e-9)
        assertEquals(0.0, east, 1e-9)
    }

    @Test
    fun courseDueEastIsHalfPi() {
        val course = Wgs84.courseRad(0.0, 0.0, 0.0, 0.001)
        assertEquals(PI / 2.0, course, 1e-6)
    }

    @Test
    fun wrapHeadingMapsTwoPiToZero() {
        assertEquals(0.0, wrapHeadingRad(TWO_PI), 0.0)
        assertEquals(0.0, wrapHeadingRad(-TWO_PI), 0.0)
        assertEquals(PI, wrapHeadingRad(-PI), 1e-12)
    }

    @Test
    fun ellipsoidEastAtSeventyNorthUsesPrimeVertical() {
        val eastM = 10.0
        val (lat, lon, alt) = Wgs84.enuToGeodetic(70.0, 20.0, 0.0, eastM, 0.0, 0.0)
        val rn = Wgs84.primeVerticalRadiusM(70.0)
        val expectedLon = 20.0 + (eastM / (rn * kotlin.math.cos(70.0 * PI / 180.0))) * 180.0 / PI
        assertEquals(70.0, lat, 1e-9)
        assertEquals(expectedLon, lon, 1e-9)
        assertEquals(0.0, alt, 0.0)
        val (e, n, u) = Wgs84.geodeticToEnu(70.0, 20.0, 0.0, lat, lon, alt)
        assertEquals(10.0, e, 1e-6)
        assertEquals(0.0, n, 1e-6)
        assertEquals(0.0, u, 1e-9)
    }

    @Test
    fun gravityIsLargerAtThePoleThanTheEquator() {
        val equator = Wgs84.gravityMps2(0.0)
        val pole = Wgs84.gravityMps2(90.0)
        assertTrue(pole > equator)
        assertEquals(9.78, equator, 0.03)
    }

    @Test
    fun gravityDecreasesWithEllipsoidalHeight() {
        val surface = Wgs84.gravityMps2(45.0, 0.0)
        val high = Wgs84.gravityMps2(45.0, 8_000.0)
        assertTrue("Groves (2.139) g(h) < g(0): $high vs $surface", high < surface)
        val drop = surface - high
        assertEquals("free-air scale 2g h / a", 2.0 * surface * 8_000.0 / Wgs84.A_M, drop, 0.003)
    }
}
