package `in`.driftzero.app.ui

import `in`.driftzero.core.ComponentHealth
import `in`.driftzero.core.CORE_VERSION
import `in`.driftzero.core.GeoPoint
import `in`.driftzero.core.GnssHealth
import `in`.driftzero.core.HeadingRadians
import `in`.driftzero.core.LatitudeDeg
import `in`.driftzero.core.LongitudeDeg
import `in`.driftzero.core.MapMatch
import `in`.driftzero.core.MapMatchStatus
import `in`.driftzero.core.Metres
import `in`.driftzero.core.MetresPerSecond
import `in`.driftzero.core.Motion
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.NavigationState
import `in`.driftzero.core.Provenance
import `in`.driftzero.core.Uncertainty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class PuckInterpolatorTest {
    @Test
    fun noTargetDrawsNothing() {
        assertNull(PuckInterpolator().sample(0L))
    }

    @Test
    fun firstTargetDrawsImmediatelyThenSlidesToTheNext() {
        val puck = PuckInterpolator(catchUpNs = 100_000_000L)
        puck.target(state(lat = 10.0, lon = 20.0, headingRad = 0.0, radiusM = 10.0), nowNs = 0L)
        val first = puck.sample(0L)!!
        assertEquals(10.0, first.latitudeDeg, 1e-12)
        puck.target(state(lat = 10.001, lon = 20.0, headingRad = 0.0, radiusM = 20.0), nowNs = 100_000_000L)
        val mid = puck.sample(150_000_000L)!!
        assertEquals(10.0005, mid.latitudeDeg, 1e-9)
        assertEquals(15.0, mid.radiusM, 1e-9)
        val done = puck.sample(200_000_000L)!!
        assertEquals(10.001, done.latitudeDeg, 1e-12)
        assertEquals(20.0, done.radiusM, 1e-12)
    }

    @Test
    fun retargetStartsFromTheDrawnPoseNotTheOldTarget() {
        val puck = PuckInterpolator(catchUpNs = 100_000_000L)
        puck.target(state(lat = 0.0, lon = 0.0, headingRad = 0.0), nowNs = 0L)
        puck.sample(0L)
        puck.target(state(lat = 1.0, lon = 0.0, headingRad = 0.0), nowNs = 0L)
        puck.sample(50_000_000L)
        puck.target(state(lat = 0.5, lon = 0.0, headingRad = 0.0), nowNs = 50_000_000L)
        val drawn = puck.sample(50_000_000L)!!
        assertEquals(0.5, drawn.latitudeDeg, 1e-9)
    }

    @Test
    fun headingTakesTheShortestArc() {
        val acrossNorth = PuckInterpolator.lerpHeading(2 * PI - 0.2, 0.2, 0.5)
        assertTrue(acrossNorth < 1e-9 || acrossNorth > 2 * PI - 1e-9)
        assertEquals(2 * PI - 0.1, PuckInterpolator.lerpHeading(2 * PI - 0.2, 0.2, 0.25), 1e-9)
        assertEquals(PI, PuckInterpolator.lerpHeading(PI / 2, 3 * PI / 2, 0.5), 1e-9)
        assertEquals(179.5, PuckInterpolator.lerpLongitude(179.0, -180.0, 0.5), 1e-9)
    }

    @Test
    fun reduceMotionDrawsTheTargetEveryFrame() {
        val puck = PuckInterpolator(catchUpNs = 100_000_000L, reduceMotion = true)
        puck.target(state(lat = 0.0, lon = 0.0, headingRad = 0.0), nowNs = 0L)
        puck.sample(0L)
        puck.target(state(lat = 1.0, lon = 0.0, headingRad = 0.0), nowNs = 10L)
        assertEquals(1.0, puck.sample(11L)!!.latitudeDeg, 1e-12)
    }

    @Test
    fun displayPoseAppliesEveryVsync() {
        assertEquals(0L, PuckInterpolator.DISPLAY_MIN_APPLY_NS)
        assertTrue(PuckInterpolator.shouldApplyDisplayPose(0L, 1L))
        assertTrue(PuckInterpolator.shouldApplyDisplayPose(1_000_000L, 2_000_000L))
        assertTrue(PuckInterpolator.shouldApplyDisplayPose(100_000_000L, 100_000_001L))
        assertFalse(
            PuckInterpolator.shouldApplyDisplayPose(
                lastApplyNs = 1L,
                frameNs = 50_000_000L,
                minNs = 100_000_000L,
            ),
        )
    }

    private fun state(lat: Double, lon: Double, headingRad: Double, radiusM: Double = 12.0): NavigationState =
        NavigationState(
            sequence = 0L,
            timestamp = Nanoseconds(0L),
            mode = NavigationMode.GNSS_FUSED,
            position = GeoPoint(LatitudeDeg(lat), LongitudeDeg(lon)),
            motion = Motion(MetresPerSecond(10.0), HeadingRadians(headingRad)),
            uncertainty = Uncertainty(Metres(radiusM), 0.2, isCalibrated = false),
            gnssHealth = GnssHealth(0.5, 0.4),
            mapMatch = MapMatch(MapMatchStatus.NO_MAP, 0.0),
            health = ComponentHealth(sensorOk = true, modelOk = false, filterOk = true, mapOk = false),
            provenance = Provenance(CORE_VERSION, "a".repeat(64)),
        )
}
