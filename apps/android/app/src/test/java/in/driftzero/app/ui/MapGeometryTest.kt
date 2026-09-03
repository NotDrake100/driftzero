package `in`.driftzero.app.ui

import `in`.driftzero.core.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class MapGeometryTest {
    @Test
    fun metresPerDpHalvesPerZoomAndShrinksWithLatitude() {
        val z16 = MapGeometry.metresPerDp(0.0, 16.0)
        val z17 = MapGeometry.metresPerDp(0.0, 17.0)
        assertEquals(1.1943, z16, 1e-3)
        assertEquals(z16 / 2.0, z17, 1e-9)
        assertEquals(z16 * 0.5, MapGeometry.metresPerDp(60.0, 16.0), 1e-6)
    }

    @Test
    fun haloCircleIsClosedTrueRadiusAndNeverSmallerThanFloor() {
        val ring = MapGeometry.circle(18.5362, 73.8938, radiusM = 40.0)
        assertEquals(MapGeometry.HALO_VERTICES + 1, ring.size)
        assertEquals(ring.first(), ring.last())
        ring.dropLast(1).forEach { p ->
            val d = Wgs84.distanceMetres(18.5362, 73.8938, p.latitudeDeg, p.longitudeDeg)
            assertEquals(40.0, d, 0.05)
        }
        val tiny = MapGeometry.circle(18.5362, 73.8938, radiusM = 0.1)
        val d = Wgs84.distanceMetres(18.5362, 73.8938, tiny[0].latitudeDeg, tiny[0].longitudeDeg)
        assertEquals(MapGeometry.HALO_MIN_RADIUS_M, d, 0.01)
    }

    @Test
    fun wedgeClampsHalfAngleAndPointsAlongHeading() {
        val north = MapGeometry.wedge(0.0, 0.0, headingRad = 0.0, halfAngleRad = 0.1, lengthM = 50.0, arcSteps = 2)
        assertEquals(5, north.size)
        assertEquals(north.first(), north.last())
        val tip = north[2]
        assertTrue(tip.latitudeDeg > 0.0)
        assertEquals(0.0, tip.longitudeDeg, 1e-9)
        val wide = MapGeometry.wedge(0.0, 0.0, headingRad = 0.0, halfAngleRad = 2.0, lengthM = 50.0, arcSteps = 2)
        val left = wide[1]
        val course = Wgs84.courseRad(0.0, 0.0, left.latitudeDeg, left.longitudeDeg)
        val expectedLeft = 2 * PI - MapGeometry.CONE_MAX_HALF_ANGLE_RAD
        assertEquals(expectedLeft, course, 1e-3)
    }

    @Test
    fun coneHidesOnlyWhenStoppedAndHeadingIsUnknown() {
        assertTrue(MapGeometry.coneVisible(speedMps = 0.0, heading95Rad = 0.2))
        assertTrue(MapGeometry.coneVisible(speedMps = 10.0, heading95Rad = 2.0))
        assertFalse(MapGeometry.coneVisible(speedMps = 0.1, heading95Rad = 2.0))
    }

    @Test
    fun zoomBandsHoldUntilSpeedLeavesThem() {
        assertEquals(16.0, MapGeometry.zoomForSpeed(2.0, null), 0.0)
        assertEquals(16.0, MapGeometry.zoomForSpeed(5.5, 16.0), 0.0)
        assertEquals(16.0, MapGeometry.zoomForSpeed(6.5, 16.0), 0.0)
        assertEquals(16.0, MapGeometry.zoomForSpeed(4.5, 16.0), 0.0)
        assertEquals(15.0, MapGeometry.zoomForSpeed(20.0, 16.0), 0.0)
        assertEquals(15.0, MapGeometry.zoomForSpeed(14.5, 15.0), 0.0)
        assertEquals(16.0, MapGeometry.zoomForSpeed(13.0, 15.0), 0.0)
    }

    @Test
    fun followBearingIsNorthWhenSlowOrLocked() {
        assertEquals(0.0, MapGeometry.followBearingDeg(1.5, headingRad = 1.0, northUp = false), 0.0)
        assertEquals(0.0, MapGeometry.followBearingDeg(10.0, headingRad = 1.0, northUp = true), 0.0)
        assertEquals(90.0, MapGeometry.followBearingDeg(10.0, headingRad = PI / 2.0, northUp = false), 1e-9)
    }
}
