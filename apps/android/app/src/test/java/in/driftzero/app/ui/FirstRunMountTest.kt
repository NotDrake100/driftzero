package `in`.driftzero.app.ui

import `in`.driftzero.core.CalibrationProfile
import `in`.driftzero.core.CalibrationStatus
import `in`.driftzero.core.StationaryCalibrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunMountTest {
    @Test
    fun tooFastUsesOneMetrePerSecond() {
        assertFalse(FirstRunMount.tooFast(null))
        assertFalse(FirstRunMount.tooFast(1.0))
        assertTrue(FirstRunMount.tooFast(1.01))
    }

    @Test
    fun progressIsZeroUntilTheWindowStarts() {
        assertEquals(
            0,
            FirstRunMount.displayProgress(
                running = false,
                startedWallNs = 0L,
                wallNs = 80_000_000_000L,
                firstSampleNs = null,
                lastSampleNs = null,
                status = CalibrationStatus.Still,
            ),
        )
        assertEquals(0, FirstRunMount.progressPct(0L))
        assertEquals(50, FirstRunMount.progressPct(StationaryCalibrator.WINDOW_NS / 2))
        assertEquals(100, FirstRunMount.progressPct(StationaryCalibrator.WINDOW_NS))
        assertEquals(100, FirstRunMount.progressPct(StationaryCalibrator.WINDOW_NS * 2))
    }

    @Test
    fun evaluateClockUsesTheLongerOfSampleSpanAndWallSpan() {
        val first = 1_000L
        val last = first + 1_000_000_000L
        val started = 10_000_000_000L
        val wall = started + StationaryCalibrator.WINDOW_NS
        assertEquals(
            first + StationaryCalibrator.WINDOW_NS,
            FirstRunMount.evaluateNowNs(first, last, started, wall),
        )
        val longSampleLast = first + StationaryCalibrator.WINDOW_NS
        assertEquals(
            longSampleLast,
            FirstRunMount.evaluateNowNs(first, longSampleLast, started, started + 1_000_000_000L),
        )
        assertEquals(42L, FirstRunMount.evaluateNowNs(null, null, started, 42L))
    }

    @Test
    fun deadSensorFailsShortAfterFiveSeconds() {
        val started = 5_000_000_000L
        assertFalse(
            FirstRunMount.timedOutWithoutSamples(lastSampleNs = 1L, startedWallNs = started, wallNs = started + StationaryCalibrator.WINDOW_NS),
        )
        assertFalse(
            FirstRunMount.timedOutWithoutSamples(lastSampleNs = null, startedWallNs = started, wallNs = started + StationaryCalibrator.WINDOW_NS - 1),
        )
        assertTrue(
            FirstRunMount.timedOutWithoutSamples(lastSampleNs = null, startedWallNs = started, wallNs = started + StationaryCalibrator.WINDOW_NS),
        )
        assertFalse(
            FirstRunMount.timedOutWithoutSamples(lastSampleNs = null, startedWallNs = 0L, wallNs = 80_000_000_000L),
        )
    }

    @Test
    fun failedStillDoesNotFinishIntoAMissingProfile() {
        assertEquals(
            MountPrimaryAction.RETRY,
            FirstRunMount.primaryAction(CalibrationStatus.FailedShort, missingSensors = false, running = false),
        )
        assertEquals(
            MountPrimaryAction.RETRY,
            FirstRunMount.primaryAction(CalibrationStatus.FailedMoving, missingSensors = false, running = false),
        )
        assertEquals(
            MountPrimaryAction.NONE,
            FirstRunMount.primaryAction(CalibrationStatus.Still, missingSensors = false, running = true),
        )
        assertEquals(
            MountPrimaryAction.START,
            FirstRunMount.primaryAction(CalibrationStatus.Still, missingSensors = false, running = false),
        )
        assertEquals(
            MountPrimaryAction.FINISH,
            FirstRunMount.primaryAction(CalibrationStatus.Still, missingSensors = true, running = false),
        )
        assertEquals(
            MountPrimaryAction.FINISH,
            FirstRunMount.primaryAction(CalibrationStatus.FailedNoGyro, missingSensors = false, running = false),
        )
        val done = CalibrationStatus.Done(2.0, CalibrationProfile(0.0, 0.0, 9.81))
        assertEquals(MountPrimaryAction.FINISH, FirstRunMount.primaryAction(done, missingSensors = false, running = false))
        assertTrue(FirstRunMount.shouldWriteProfile(done))
        assertFalse(FirstRunMount.shouldWriteProfile(CalibrationStatus.FailedMoving))
        assertTrue(FirstRunMount.abortBecauseMoving(tooFast = true, running = true))
        assertFalse(FirstRunMount.abortBecauseMoving(tooFast = true, running = false))
    }

    @Test
    fun skipLocationPromptOnlyWhenFineGrantedAndNotDenied() {
        assertTrue(FirstRunMount.skipLocationPrompt(fineGranted = true, denied = false))
        assertFalse(FirstRunMount.skipLocationPrompt(fineGranted = false, denied = false))
        assertFalse(FirstRunMount.skipLocationPrompt(fineGranted = true, denied = true))
        assertFalse(FirstRunMount.skipLocationPrompt(fineGranted = false, denied = true))
    }

    @Test
    fun sampleClockIgnoresNegativeAndKeepsFirst() {
        val clock = StillSampleClock()
        clock.onSample(-1L)
        assertEquals(null, clock.firstNs)
        clock.onSample(10L)
        clock.onSample(20L)
        assertEquals(10L, clock.firstNs)
        assertEquals(20L, clock.lastNs)
        clock.reset()
        assertEquals(null, clock.firstNs)
        assertEquals(null, clock.lastNs)
    }
}
