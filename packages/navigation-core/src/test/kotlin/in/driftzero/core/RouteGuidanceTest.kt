package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class RouteGuidanceTest {
    @Test
    fun currentIsNullBeforeFirstUpdate() {
        val guidance = RouteGuidance(straightRoute(400.0))
        assertNull(guidance.current())
        assertEquals(2, guidance.remainingPoints().size)
    }

    @Test
    fun snapsAlongStraightRouteAndReportsAlongTrack() {
        val route = straightRoute(400.0, speedMps = 10.0)
        val guidance = RouteGuidance(route)
        val at0 = onRoute(guidance, 0.0, 0.0, headingRad = 0.0, timestampNs = 0L)
        assertEquals(0, at0.segmentIndex)
        assertEquals(0.0, at0.distanceAlongM, 0.5)
        assertEquals(400.0, at0.remainingM, 0.5)
        assertEquals(40.0, at0.remainingS, 0.2)
        assertEquals(1, at0.nextStepIndex)
        assertEquals(400.0, at0.distanceToNextStepM, 0.5)
        assertEquals(0.0, at0.crossTrackM, 0.5)

        val at100 = onRoute(guidance, 100.0, 0.0, headingRad = 0.0, timestampNs = 1_000_000_000L)
        assertEquals(100.0, at100.distanceAlongM, 0.5)
        assertEquals(300.0, at100.remainingM, 0.5)
        assertEquals(30.0, at100.remainingS, 0.2)
        assertEquals(300.0, at100.distanceToNextStepM, 0.5)

        val offset = onRoute(guidance, 250.0, 10.0, headingRad = 0.0, timestampNs = 2_000_000_000L)
        assertEquals(250.0, offset.distanceAlongM, 1.0)
        assertEquals(10.0, abs(offset.crossTrackM), 0.8)
        assertTrue(offset.crossTrackM > 0.0)
        assertEquals(guidance.current(), offset)
    }

    @Test
    fun headingBreaksTieOnParallelOverlappingSegments() {
        val route = outAndBackRoute(lengthM = 200.0, sepM = 8.0)
        val north = RouteGuidance(route)
        val south = RouteGuidance(route)
        val midNorth = 100.0
        val midEast = 4.0

        val goingNorth = onRoute(north, midNorth, midEast, headingRad = 0.0, timestampNs = 0L)
        assertEquals(0, goingNorth.segmentIndex)
        assertEquals(100.0, goingNorth.distanceAlongM, 2.0)

        val goingSouth = onRoute(south, midNorth, midEast, headingRad = PI, timestampNs = 0L)
        assertEquals(2, goingSouth.segmentIndex)
        assertTrue(goingSouth.distanceAlongM > 200.0)
        assertEquals(200.0 + 8.0 + 100.0, goingSouth.distanceAlongM, 3.0)
    }

    @Test
    fun loopRouteDoesNotSnapBackwards() {
        val route = outAndBackRoute(lengthM = 300.0, sepM = 1.0)
        val guidance = RouteGuidance(route)
        onRoute(guidance, 200.0, 0.0, headingRad = 0.0, timestampNs = 0L)
        onRoute(guidance, 300.0, 0.0, headingRad = 0.0, timestampNs = 1_000_000_000L)
        val inbound = onRoute(guidance, 200.0, 0.0, headingRad = PI, timestampNs = 2_000_000_000L)
        assertEquals(2, inbound.segmentIndex)
        assertEquals(401.0, inbound.distanceAlongM, 3.0)
        assertTrue(inbound.distanceAlongM > 300.0)

        val stillInbound = onRoute(guidance, 200.0, 0.0, headingRad = PI, timestampNs = 3_000_000_000L)
        assertEquals(2, stillInbound.segmentIndex)
        assertEquals(401.0, stillInbound.distanceAlongM, 3.0)
    }

    @Test
    fun offRouteRequiresDistanceAndDuration() {
        val route = straightRoute(400.0)
        val guidance = RouteGuidance(route, GuidanceConfig(offRouteDistanceM = 30.0, offRouteSeconds = 5.0))
        onRoute(guidance, 100.0, 0.0, headingRad = 0.0, timestampNs = 0L)

        val firstOff = guidance.update(
            lat(100.0, 50.0), lon(100.0, 50.0), headingRad = 0.0, speedMps = 10.0, timestampNs = 1_000_000_000L,
        )
        assertTrue(firstOff is GuidanceState.OnRoute)
        assertEquals(50.0, abs((firstOff as GuidanceState.OnRoute).crossTrackM), 2.0)

        val stillGrace = guidance.update(
            lat(100.0, 50.0), lon(100.0, 50.0), headingRad = 0.0, speedMps = 10.0, timestampNs = 5_000_000_000L,
        )
        assertTrue(stillGrace is GuidanceState.OnRoute)

        val off = guidance.update(
            lat(100.0, 50.0), lon(100.0, 50.0), headingRad = 0.0, speedMps = 10.0, timestampNs = 6_200_000_000L,
        )
        assertTrue(off is GuidanceState.OffRoute)
        val offRoute = off as GuidanceState.OffRoute
        assertEquals(50.0, abs(offRoute.crossTrackM), 2.0)
        assertEquals(5.2, offRoute.sinceS, 0.05)
    }

    @Test
    fun offRouteTimerResetsAfterRejoin() {
        val route = straightRoute(400.0)
        val guidance = RouteGuidance(route, GuidanceConfig(offRouteDistanceM = 30.0, offRouteSeconds = 5.0))
        onRoute(guidance, 80.0, 0.0, headingRad = 0.0, timestampNs = 0L)
        guidance.update(lat(80.0, 45.0), lon(80.0, 45.0), 0.0, 8.0, 1_000_000_000L)
        guidance.update(lat(80.0, 45.0), lon(80.0, 45.0), 0.0, 8.0, 3_500_000_000L)
        onRoute(guidance, 90.0, 0.0, headingRad = 0.0, timestampNs = 4_000_000_000L)
        val again = guidance.update(lat(90.0, 45.0), lon(90.0, 45.0), 0.0, 8.0, 7_000_000_000L)
        assertTrue(again is GuidanceState.OnRoute)
        val late = guidance.update(lat(90.0, 45.0), lon(90.0, 45.0), 0.0, 8.0, 12_200_000_000L)
        assertTrue(late is GuidanceState.OffRoute)
        assertEquals(5.2, (late as GuidanceState.OffRoute).sinceS, 0.05)
    }

    @Test
    fun doesNotArriveNearDestinationBeforeLastStep() {
        val route = straightRoute(200.0)
        val guidance = RouteGuidance(route, GuidanceConfig(arriveRadiusM = 25.0))
        val near = onRoute(guidance, 185.0, 0.0, headingRad = 0.0, timestampNs = 0L)
        assertEquals(15.0, near.remainingM, 1.5)
        assertTrue(guidance.current() is GuidanceState.OnRoute)
    }

    @Test
    fun arrivesWhenNearDestinationAndPastLastStep() {
        val route = straightRoute(200.0)
        val guidance = RouteGuidance(route, GuidanceConfig(arriveRadiusM = 25.0))
        onRoute(guidance, 170.0, 0.0, headingRad = 0.0, timestampNs = 0L)
        val arrived = guidance.update(lat(200.0, 4.0), lon(200.0, 4.0), 0.0, 2.0, 1_000_000_000L)
        assertTrue(arrived is GuidanceState.Arrived)
        val state = arrived as GuidanceState.Arrived
        assertEquals(4.0, state.distanceToDestinationM, 1.0)
        assertEquals(listOf(route.points.last()), guidance.remainingPoints())
    }

    @Test
    fun remainingPointsStartsAtSnap() {
        val a = point(0.0, 0.0)
        val b = point(200.0, 0.0)
        val c = point(200.0, 200.0)
        val route = GuidanceRoute(
            points = listOf(a, b, c),
            steps = listOf(
                RouteStep(ManeuverType.DEPART, ManeuverModifier.NONE, "North", null, 200.0, 20.0, 0),
                RouteStep(ManeuverType.TURN, ManeuverModifier.RIGHT, "East", null, 200.0, 20.0, 1),
                RouteStep(ManeuverType.ARRIVE, ManeuverModifier.NONE, null, null, 0.0, 0.0, 2),
            ),
            totalDistanceM = 400.0,
            totalDurationS = 40.0,
        )
        val guidance = RouteGuidance(route)
        onRoute(guidance, 100.0, 0.0, headingRad = 0.0, timestampNs = 0L)
        val remaining = guidance.remainingPoints()
        assertEquals(3, remaining.size)
        val snapNorth = Wgs84.northEastMetres(
            ORIGIN_LAT, ORIGIN_LON, remaining[0].latitudeDeg, remaining[0].longitudeDeg,
        )
        assertEquals(100.0, snapNorth.first, 1.0)
        assertEquals(0.0, snapNorth.second, 1.0)
        assertEquals(b.latitudeDeg, remaining[1].latitudeDeg, 1e-9)
        assertEquals(c.latitudeDeg, remaining[2].latitudeDeg, 1e-9)
        assertEquals(c.longitudeDeg, remaining[2].longitudeDeg, 1e-9)
    }

    @Test
    fun remainingTimeUsesPerStepSpeed() {
        val a = point(0.0, 0.0)
        val b = point(100.0, 0.0)
        val c = point(200.0, 0.0)
        val route = GuidanceRoute(
            points = listOf(a, b, c),
            steps = listOf(
                RouteStep(ManeuverType.DEPART, ManeuverModifier.NONE, "Fast", null, 100.0, 10.0, 0),
                RouteStep(ManeuverType.CONTINUE, ManeuverModifier.STRAIGHT, "Slow", null, 100.0, 50.0, 1),
                RouteStep(ManeuverType.ARRIVE, ManeuverModifier.NONE, null, null, 0.0, 0.0, 2),
            ),
            totalDistanceM = 200.0,
            totalDurationS = 60.0,
        )
        val guidance = RouteGuidance(route)
        val midFast = onRoute(guidance, 50.0, 0.0, headingRad = 0.0, timestampNs = 0L)
        assertEquals(150.0, midFast.remainingM, 1.0)
        assertEquals(55.0, midFast.remainingS, 0.4)
        val midSlow = onRoute(guidance, 150.0, 0.0, headingRad = 0.0, timestampNs = 1_000_000_000L)
        assertEquals(50.0, midSlow.remainingM, 1.0)
        assertEquals(25.0, midSlow.remainingS, 0.4)
    }

    @Test
    fun closedLoopDoesNotArriveAtTheStart() {
        val route = squareLoopRoute(200.0)
        val guidance = RouteGuidance(route, GuidanceConfig(arriveRadiusM = 25.0))
        val start = onRoute(guidance, 0.0, 0.0, headingRad = 0.0, timestampNs = 0L)
        assertTrue(start.distanceAlongM < 10.0)
        assertTrue(guidance.current() is GuidanceState.OnRoute)
    }

    private fun onRoute(
        guidance: RouteGuidance,
        northM: Double,
        eastM: Double,
        headingRad: Double?,
        timestampNs: Long,
        speedMps: Double? = 10.0,
    ): GuidanceState.OnRoute {
        val state = guidance.update(lat(northM, eastM), lon(northM, eastM), headingRad, speedMps, timestampNs)
        assertTrue("expected OnRoute, got $state", state is GuidanceState.OnRoute)
        return state as GuidanceState.OnRoute
    }

    private companion object {
        const val ORIGIN_LAT: Double = 18.52
        const val ORIGIN_LON: Double = 73.85

        fun point(northM: Double, eastM: Double): RoutePoint {
            val (lat, lon) = Wgs84.offsetMetres(ORIGIN_LAT, ORIGIN_LON, northM, eastM)
            return RoutePoint(lat, lon)
        }

        fun lat(northM: Double, eastM: Double): Double = point(northM, eastM).latitudeDeg

        fun lon(northM: Double, eastM: Double): Double = point(northM, eastM).longitudeDeg

        fun straightRoute(lengthM: Double, speedMps: Double = 10.0): GuidanceRoute {
            val duration = lengthM / speedMps
            return GuidanceRoute(
                points = listOf(point(0.0, 0.0), point(lengthM, 0.0)),
                steps = listOf(
                    RouteStep(ManeuverType.DEPART, ManeuverModifier.NONE, "Karve Road", null, lengthM, duration, 0),
                    RouteStep(ManeuverType.ARRIVE, ManeuverModifier.NONE, null, null, 0.0, 0.0, 1),
                ),
                totalDistanceM = lengthM,
                totalDurationS = duration,
            )
        }

        fun outAndBackRoute(lengthM: Double, sepM: Double): GuidanceRoute {
            val points = listOf(
                point(0.0, 0.0),
                point(lengthM, 0.0),
                point(lengthM, sepM),
                point(0.0, sepM),
            )
            val connector = Wgs84.distanceMetres(
                points[1].latitudeDeg, points[1].longitudeDeg,
                points[2].latitudeDeg, points[2].longitudeDeg,
            )
            val total = lengthM + connector + lengthM
            return GuidanceRoute(
                points = points,
                steps = listOf(
                    RouteStep(ManeuverType.DEPART, ManeuverModifier.NONE, "Out", null, lengthM, lengthM / 10.0, 0),
                    RouteStep(ManeuverType.UTURN, ManeuverModifier.UTURN, null, null, connector, 1.0, 1),
                    RouteStep(ManeuverType.CONTINUE, ManeuverModifier.STRAIGHT, "Back", null, lengthM, lengthM / 10.0, 2),
                    RouteStep(ManeuverType.ARRIVE, ManeuverModifier.NONE, null, null, 0.0, 0.0, 3),
                ),
                totalDistanceM = total,
                totalDurationS = lengthM / 5.0 + 1.0,
            )
        }

        fun squareLoopRoute(sideM: Double): GuidanceRoute {
            val points = listOf(
                point(0.0, 0.0),
                point(sideM, 0.0),
                point(sideM, sideM),
                point(0.0, sideM),
                point(2.0, 0.0),
            )
            val lastLeg = Wgs84.distanceMetres(
                points[3].latitudeDeg, points[3].longitudeDeg,
                points[4].latitudeDeg, points[4].longitudeDeg,
            )
            val total = sideM * 3.0 + lastLeg
            return GuidanceRoute(
                points = points,
                steps = listOf(
                    RouteStep(ManeuverType.DEPART, ManeuverModifier.NONE, "Loop", null, sideM, 20.0, 0),
                    RouteStep(ManeuverType.TURN, ManeuverModifier.RIGHT, "Loop", null, sideM, 20.0, 1),
                    RouteStep(ManeuverType.TURN, ManeuverModifier.RIGHT, "Loop", null, sideM, 20.0, 2),
                    RouteStep(ManeuverType.TURN, ManeuverModifier.RIGHT, "Loop", null, lastLeg, 20.0, 3),
                    RouteStep(ManeuverType.ARRIVE, ManeuverModifier.NONE, null, null, 0.0, 0.0, 4),
                ),
                totalDistanceM = total,
                totalDurationS = 80.0,
            )
        }
    }
}
