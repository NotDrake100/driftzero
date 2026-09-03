package `in`.driftzero.app.ui

import `in`.driftzero.core.ComponentHealth
import `in`.driftzero.core.CORE_VERSION
import `in`.driftzero.core.GeoPoint
import `in`.driftzero.core.GnssHealth
import `in`.driftzero.core.GuidanceRoute
import `in`.driftzero.core.GuidanceState
import `in`.driftzero.core.HeadingRadians
import `in`.driftzero.core.LatitudeDeg
import `in`.driftzero.core.LongitudeDeg
import `in`.driftzero.core.ManeuverModifier
import `in`.driftzero.core.ManeuverType
import `in`.driftzero.core.MapMatch
import `in`.driftzero.core.MapMatchStatus
import `in`.driftzero.core.Metres
import `in`.driftzero.core.MetresPerSecond
import `in`.driftzero.core.Motion
import `in`.driftzero.core.Nanoseconds
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.NavigationState
import `in`.driftzero.core.Provenance
import `in`.driftzero.core.RoutePoint
import `in`.driftzero.core.RouteStep
import `in`.driftzero.core.Uncertainty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceNavTest {
    @Test
    fun snapsOnlyOnLiveGnssAndTightCrossTrack() {
        val on = onRoute(crossTrackM = 8.0)
        assertTrue(GuidanceNav.shouldSnapPuck(NavigationMode.GNSS_FUSED, on))
        assertTrue(GuidanceNav.shouldSnapPuck(NavigationMode.GNSS_DEGRADED, on))
        assertFalse(GuidanceNav.shouldSnapPuck(NavigationMode.DEAD_RECKONING, on))
        assertFalse(GuidanceNav.shouldSnapPuck(NavigationMode.REACQUIRING, on))
        assertFalse(GuidanceNav.shouldSnapPuck(NavigationMode.LOW_CONFIDENCE, on))
        assertFalse(GuidanceNav.shouldSnapPuck(NavigationMode.GNSS_FUSED, onRoute(crossTrackM = 15.0)))
        assertFalse(GuidanceNav.shouldSnapPuck(NavigationMode.GNSS_FUSED, GuidanceState.OffRoute(40.0, 6.0)))
        assertFalse(GuidanceNav.shouldSnapPuck(NavigationMode.GNSS_FUSED, GuidanceState.Arrived(4.0)))
    }

    @Test
    fun displayUsesEstimatorInDeadReckoning() {
        val pose = pose(NavigationMode.DEAD_RECKONING, lat = 18.51, lon = 73.88)
        val on = onRoute(crossTrackM = 4.0, snapLat = 18.52, snapLon = 73.89)
        assertEquals(18.51, GuidanceNav.displayLatitudeDeg(pose, on), 1e-9)
        assertEquals(73.88, GuidanceNav.displayLongitudeDeg(pose, on), 1e-9)
    }

    @Test
    fun displaySnapsOnGnssWhenCrossTrackIsUnder15m() {
        val pose = pose(NavigationMode.GNSS_FUSED, lat = 18.51, lon = 73.88)
        val on = onRoute(crossTrackM = 9.0, snapLat = 18.52, snapLon = 73.89)
        assertEquals(18.52, GuidanceNav.displayLatitudeDeg(pose, on), 1e-9)
        assertEquals(73.89, GuidanceNav.displayLongitudeDeg(pose, on), 1e-9)
        val far = onRoute(crossTrackM = 16.0, snapLat = 18.52, snapLon = 73.89)
        assertEquals(18.51, GuidanceNav.displayLatitudeDeg(pose, far), 1e-9)
    }

    @Test
    fun rerouteUsesTenSecondCooldown() {
        val off = GuidanceState.OffRoute(40.0, 6.0)
        assertTrue(GuidanceNav.shouldReroute(off, NavigationMode.GNSS_FUSED, lastRerouteNs = null, nowNs = 0L))
        assertFalse(GuidanceNav.shouldReroute(off, NavigationMode.GNSS_FUSED, lastRerouteNs = 0L, nowNs = 9_999_999_999L))
        assertTrue(GuidanceNav.shouldReroute(off, NavigationMode.GNSS_FUSED, lastRerouteNs = 0L, nowNs = 10_000_000_000L))
        assertFalse(GuidanceNav.shouldReroute(off, NavigationMode.DEAD_RECKONING, lastRerouteNs = null, nowNs = 20_000_000_000L))
        assertFalse(GuidanceNav.shouldReroute(onRoute(0.0), NavigationMode.GNSS_FUSED, lastRerouteNs = null, nowNs = 20_000_000_000L))
    }

    @Test
    fun bannerUsesNounsAndNumbers() {
        val route = GuidanceRoute(
            points = listOf(RoutePoint(18.51, 73.88), RoutePoint(18.53, 73.83)),
            steps = listOf(
                RouteStep(ManeuverType.DEPART, ManeuverModifier.NONE, "Start", null, 0.0, 0.0, 0),
                RouteStep(ManeuverType.TURN, ManeuverModifier.LEFT, "Senapati Bapat Road", null, 200.0, 20.0, 0),
                RouteStep(ManeuverType.ARRIVE, ManeuverModifier.NONE, null, null, 0.0, 0.0, 1),
            ),
            totalDistanceM = 200.0,
            totalDurationS = 20.0,
        )
        val far = GuidanceNav.banner(onRoute(0.0, distanceToNext = 200.0), route)
        assertEquals("200 m", far.distanceText)
        assertTrue(far.instruction.contains("left"))
        assertFalse(far.instruction.contains("\u2014"))
        val arrived = GuidanceNav.banner(GuidanceState.Arrived(3.0), route)
        assertEquals("You have arrived", arrived.instruction)
        assertTrue(arrived.arrived)
        val off = GuidanceNav.banner(GuidanceState.OffRoute(40.0, 6.0), route)
        assertEquals("Off route", off.instruction)
        assertTrue(off.offRoute)
    }

    private fun onRoute(
        crossTrackM: Double,
        snapLat: Double = 18.52,
        snapLon: Double = 73.85,
        distanceToNext: Double = 200.0,
    ): GuidanceState.OnRoute = GuidanceState.OnRoute(
        snappedLatitudeDeg = snapLat,
        snappedLongitudeDeg = snapLon,
        segmentIndex = 0,
        distanceAlongM = 0.0,
        remainingM = 200.0,
        remainingS = 20.0,
        nextStepIndex = 1,
        distanceToNextStepM = distanceToNext,
        crossTrackM = crossTrackM,
    )

    private fun pose(mode: NavigationMode, lat: Double, lon: Double): NavigationState = NavigationState(
        sequence = 0L,
        timestamp = Nanoseconds(0L),
        mode = mode,
        position = GeoPoint(LatitudeDeg(lat), LongitudeDeg(lon)),
        motion = Motion(MetresPerSecond(8.0), HeadingRadians(0.0)),
        uncertainty = Uncertainty(Metres(12.0), 0.1, isCalibrated = true),
        gnssHealth = GnssHealth(1.0, 0.4),
        mapMatch = MapMatch(MapMatchStatus.NO_MAP, 0.0),
        health = ComponentHealth(sensorOk = true, modelOk = false, filterOk = true, mapOk = false),
        provenance = Provenance(CORE_VERSION, "0".repeat(64)),
    )
}
