package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCuesTest {
    @Test
    fun instructionCoversEveryManeuverType() {
        for (type in ManeuverType.entries) {
            val step = RouteStep(
                type = type,
                modifier = ManeuverModifier.LEFT,
                roadName = "Karve Road",
                exitNumber = if (type == ManeuverType.ROUNDABOUT || type == ManeuverType.ROTARY) 2 else null,
                distanceM = 80.0,
                durationS = 8.0,
                startIndex = 0,
            )
            val far = ManeuverText.instruction(step, 200.0, CueStage.FAR)
            val now = ManeuverText.instruction(step, 20.0, CueStage.NOW)
            assertTrue("$type far", far.isNotEmpty())
            assertTrue("$type now", now.isNotEmpty())
            assertTrue("$type far starts with In", far.startsWith("In 200 metres,"))
            assertTrue("$type has no em dash", !far.contains("\u2014") && !now.contains("\u2014"))
        }
        val arrive = RouteStep(ManeuverType.ARRIVE, ManeuverModifier.NONE, null, null, 0.0, 0.0, 1)
        assertEquals("You have arrived", ManeuverText.instruction(arrive, null, CueStage.ARRIVED))
        assertEquals("You have arrived", ManeuverText.instruction(arrive, 10.0, CueStage.NOW))
        assertEquals("In 200 metres, you will arrive", ManeuverText.instruction(arrive, 200.0, CueStage.FAR))
    }

    @Test
    fun instructionCoversEveryModifier() {
        for (modifier in ManeuverModifier.entries) {
            val step = RouteStep(ManeuverType.TURN, modifier, "FC Road", null, 40.0, 4.0, 0)
            val far = ManeuverText.instruction(step, 200.0, CueStage.FAR)
            val now = ManeuverText.instruction(step, 20.0, CueStage.NOW)
            assertTrue("$modifier far", far.startsWith("In 200 metres,"))
            assertTrue("$modifier now ends with now", now.endsWith(" now") || now == "You have arrived")
            when (modifier) {
                ManeuverModifier.LEFT -> {
                    assertEquals("In 200 metres, turn left onto FC Road", far)
                    assertEquals("Turn left now", now)
                }
                ManeuverModifier.RIGHT -> assertTrue(far.contains("right"))
                ManeuverModifier.SHARP_LEFT -> assertTrue(far.contains("sharp left"))
                ManeuverModifier.SHARP_RIGHT -> assertTrue(far.contains("sharp right"))
                ManeuverModifier.SLIGHT_LEFT -> assertTrue(far.contains("slight left"))
                ManeuverModifier.SLIGHT_RIGHT -> assertTrue(far.contains("slight right"))
                ManeuverModifier.STRAIGHT -> assertTrue(far.contains("straight"))
                ManeuverModifier.UTURN -> assertTrue(far.contains("U-turn"))
                ManeuverModifier.NONE -> assertTrue(far.contains("turn"))
            }
        }
    }

    @Test
    fun instructionFormatsRoundaboutExitsAndNullRoadName() {
        val named = RouteStep(
            ManeuverType.ROUNDABOUT, ManeuverModifier.NONE, "Karve Road", 2, 30.0, 8.0, 1,
        )
        val unnamed = RouteStep(
            ManeuverType.ROUNDABOUT, ManeuverModifier.NONE, null, 1, 30.0, 8.0, 1,
        )
        assertEquals(
            "In 200 metres, at the roundabout, take the second exit onto Karve Road",
            ManeuverText.instruction(named, 200.0, CueStage.FAR),
        )
        assertEquals("Take the second exit now", ManeuverText.instruction(named, 20.0, CueStage.NOW))
        assertEquals(
            "In 150 metres, at the roundabout, take the first exit",
            ManeuverText.instruction(unnamed, 150.0, CueStage.NEAR),
        )
        val turn = RouteStep(ManeuverType.TURN, ManeuverModifier.LEFT, null, null, 40.0, 4.0, 0)
        assertEquals("In 200 metres, turn left", ManeuverText.instruction(turn, 200.0, CueStage.FAR))
        assertEquals("Turn left now", ManeuverText.instruction(turn, 25.0, CueStage.NOW))
        val rotary = RouteStep(ManeuverType.ROTARY, ManeuverModifier.NONE, null, 3, 20.0, 5.0, 1)
        assertTrue(ManeuverText.instruction(rotary, 200.0, CueStage.FAR).contains("third exit"))
    }

    @Test
    fun instructionRoundsDistances() {
        val step = RouteStep(ManeuverType.TURN, ManeuverModifier.LEFT, "Karve Road", null, 80.0, 8.0, 0)
        assertEquals(
            "In 10 metres, turn left onto Karve Road",
            ManeuverText.instruction(step, 12.0, CueStage.NEAR),
        )
        assertEquals(
            "In 50 metres, turn left onto Karve Road",
            ManeuverText.instruction(step, 45.0, CueStage.NEAR),
        )
        assertEquals(
            "In 200 metres, turn left onto Karve Road",
            ManeuverText.instruction(step, 200.0, CueStage.FAR),
        )
        assertEquals(
            "In 1.2 kilometres, turn left onto Karve Road",
            ManeuverText.instruction(step, 1200.0, CueStage.FAR),
        )
        assertEquals("10 metres", ManeuverText.spokenDistance(4.0))
        assertEquals("150 metres", ManeuverText.spokenDistance(150.0))
        assertEquals("1.0 kilometres", ManeuverText.spokenDistance(1000.0))
    }

    @Test
    fun cueEmitsOncePerStepStage() {
        val route = cueRoute(lengthM = 800.0)
        val scheduler = VoiceCueScheduler()
        val farState = onRouteState(distanceToNextStepM = 480.0, remainingM = 480.0, remainingS = 48.0)
        val first = scheduler.onGuidance(farState, route, speedMps = 10.0)
        assertNotNull(first)
        assertEquals(CueStage.FAR, first!!.stage)
        assertEquals(1, first.stepIndex)
        assertTrue(first.text.startsWith("In 500 metres,"))
        assertNull(scheduler.onGuidance(farState, route, speedMps = 10.0))
        assertNull(scheduler.onGuidance(farState.copy(distanceToNextStepM = 400.0, remainingM = 400.0), route, 10.0))

        val nearState = onRouteState(distanceToNextStepM = 140.0, remainingM = 140.0, remainingS = 14.0)
        val near = scheduler.onGuidance(nearState, route, speedMps = 10.0)
        assertNotNull(near)
        assertEquals(CueStage.NEAR, near!!.stage)
        assertNull(scheduler.onGuidance(nearState, route, speedMps = 10.0))

        val nowState = onRouteState(distanceToNextStepM = 20.0, remainingM = 20.0, remainingS = 2.0)
        val now = scheduler.onGuidance(nowState, route, speedMps = 10.0)
        assertNotNull(now)
        assertEquals(CueStage.NOW, now!!.stage)
        assertEquals("Turn left now", now.text)
        assertNull(scheduler.onGuidance(nowState, route, speedMps = 10.0))
    }

    @Test
    fun cueThresholdsScaleWithSpeed() {
        val route = cueRoute(lengthM = 2000.0)
        val slow = VoiceCueScheduler()
        val fast = VoiceCueScheduler()
        val at700 = onRouteState(distanceToNextStepM = 700.0, remainingM = 700.0, remainingS = 70.0)
        assertNull(slow.onGuidance(at700, route, speedMps = 8.0))
        val farFast = fast.onGuidance(at700, route, speedMps = 30.0)
        assertNotNull(farFast)
        assertEquals(CueStage.FAR, farFast!!.stage)
        assertEquals(1, farFast.stepIndex)

        val at200 = onRouteState(distanceToNextStepM = 200.0, remainingM = 200.0, remainingS = 10.0)
        val midSlow = VoiceCueScheduler().onGuidance(at200, route, speedMps = null)
        assertNotNull(midSlow)
        assertEquals(CueStage.FAR, midSlow!!.stage)
        val nearFast = VoiceCueScheduler().onGuidance(at200, route, speedMps = 30.0)
        assertNotNull(nearFast)
        assertEquals(CueStage.NEAR, nearFast!!.stage)
    }

    @Test
    fun arrivedStateEmitsArrivedCueOnce() {
        val route = cueRoute(lengthM = 100.0)
        val scheduler = VoiceCueScheduler()
        val arrived = GuidanceState.Arrived(4.0)
        val first = scheduler.onGuidance(arrived, route, speedMps = 1.0)
        assertEquals(CueStage.ARRIVED, first!!.stage)
        assertEquals(route.steps.lastIndex, first.stepIndex)
        assertEquals("You have arrived", first.text)
        assertNull(scheduler.onGuidance(arrived, route, speedMps = 1.0))
        assertNull(scheduler.onGuidance(GuidanceState.OffRoute(40.0, 6.0), route, speedMps = 5.0))
    }

    @Test
    fun onRerouteReturnsReroutingCue() {
        val cue = VoiceCueScheduler().onReroute()
        assertEquals(CueStage.REROUTING, cue.stage)
        assertEquals(0, cue.stepIndex)
        assertEquals("Rerouting", cue.text)
        assertEquals(
            "Rerouting",
            ManeuverText.instruction(
                RouteStep(ManeuverType.UNKNOWN, ManeuverModifier.NONE, null, null, 0.0, 0.0, 0),
                null,
                CueStage.REROUTING,
            ),
        )
    }

    private fun cueRoute(lengthM: Double): GuidanceRoute {
        val origin = Wgs84.offsetMetres(18.52, 73.85, 0.0, 0.0)
        val end = Wgs84.offsetMetres(18.52, 73.85, lengthM, 0.0)
        return GuidanceRoute(
            points = listOf(RoutePoint(origin.first, origin.second), RoutePoint(end.first, end.second)),
            steps = listOf(
                RouteStep(ManeuverType.DEPART, ManeuverModifier.NONE, "Start", null, 0.0, 0.0, 0),
                RouteStep(ManeuverType.TURN, ManeuverModifier.LEFT, "Karve Road", null, lengthM, lengthM / 10.0, 0),
                RouteStep(ManeuverType.ARRIVE, ManeuverModifier.NONE, null, null, 0.0, 0.0, 1),
            ),
            totalDistanceM = lengthM,
            totalDurationS = lengthM / 10.0,
        )
    }

    private fun onRouteState(
        distanceToNextStepM: Double,
        remainingM: Double,
        remainingS: Double,
        nextStepIndex: Int = 1,
    ): GuidanceState.OnRoute = GuidanceState.OnRoute(
        snappedLatitudeDeg = 18.52,
        snappedLongitudeDeg = 73.85,
        segmentIndex = 0,
        distanceAlongM = 0.0,
        remainingM = remainingM,
        remainingS = remainingS,
        nextStepIndex = nextStepIndex,
        distanceToNextStepM = distanceToNextStepM,
        crossTrackM = 0.0,
    )
}
