package `in`.driftzero.app.ui

import `in`.driftzero.core.CalibrationStatus
import `in`.driftzero.core.StationaryCalibrator
import kotlin.math.max

internal enum class MountPrimaryAction {
    NONE,
    START,
    RETRY,
    FINISH,
}

/** Sensor-thread timestamps for the still window. Not Compose state. */
internal class StillSampleClock {
    @Volatile
    var firstNs: Long? = null
        private set

    @Volatile
    var lastNs: Long? = null
        private set

    fun reset() {
        firstNs = null
        lastNs = null
    }

    fun onSample(timestampNs: Long) {
        if (timestampNs < 0L) {
            return
        }
        if (firstNs == null) {
            firstNs = timestampNs
        }
        lastNs = timestampNs
    }
}

/**
 * First-run still window. The 5 s copy is [StationaryCalibrator.WINDOW_NS].
 * Straight-drive yaw is live [in.driftzero.core.MountSession], not this screen.
 */
internal object FirstRunMount {
    const val MAX_START_SPEED_MPS: Double = 1.0

    fun tooFast(speedMps: Double?): Boolean = (speedMps ?: 0.0) > MAX_START_SPEED_MPS

    fun progressPct(elapsedNs: Long, windowNs: Long = StationaryCalibrator.WINDOW_NS): Int {
        if (windowNs <= 0L) {
            return 0
        }
        return ((elapsedNs.coerceAtLeast(0L) * 100.0) / windowNs)
            .toInt()
            .coerceIn(0, 100)
    }

    /**
     * Percent shown on the mount step. Zero until the window starts so a
     * leftover boot timestamp cannot paint 100% before Start.
     */
    fun displayProgress(
        running: Boolean,
        startedWallNs: Long,
        wallNs: Long,
        firstSampleNs: Long?,
        lastSampleNs: Long?,
        status: CalibrationStatus,
        windowNs: Long = StationaryCalibrator.WINDOW_NS,
    ): Int {
        if (status is CalibrationStatus.Done) {
            return 100
        }
        if (!running && startedWallNs <= 0L) {
            return 0
        }
        return progressPct(elapsedNs(firstSampleNs, lastSampleNs, startedWallNs, wallNs), windowNs)
    }

    /**
     * Clock for [StationaryCalibrator.evaluate]. Sample timestamps and
     * elapsedRealtime can disagree across devices. Map the longer of the
     * sample span and the wall span onto the first sample so a 5 s still
     * cannot finish from a clock mismatch, and cannot run forever if the
     * sensor clock is stalled.
     */
    fun evaluateNowNs(
        firstSampleNs: Long?,
        lastSampleNs: Long?,
        startedWallNs: Long,
        wallNs: Long,
    ): Long {
        if (firstSampleNs != null && lastSampleNs != null) {
            return firstSampleNs + elapsedNs(firstSampleNs, lastSampleNs, startedWallNs, wallNs)
        }
        return wallNs
    }

    fun timedOutWithoutSamples(
        lastSampleNs: Long?,
        startedWallNs: Long,
        wallNs: Long,
        windowNs: Long = StationaryCalibrator.WINDOW_NS,
    ): Boolean {
        if (lastSampleNs != null || startedWallNs <= 0L) {
            return false
        }
        return wallNs - startedWallNs >= windowNs
    }

    fun primaryAction(
        status: CalibrationStatus,
        missingSensors: Boolean,
        running: Boolean,
    ): MountPrimaryAction {
        if (running) {
            return MountPrimaryAction.NONE
        }
        if (missingSensors) {
            return MountPrimaryAction.FINISH
        }
        return when (status) {
            is CalibrationStatus.Done -> MountPrimaryAction.FINISH
            CalibrationStatus.FailedNoGyro -> MountPrimaryAction.FINISH
            CalibrationStatus.FailedShort, CalibrationStatus.FailedMoving -> MountPrimaryAction.RETRY
            CalibrationStatus.Still, CalibrationStatus.Moving -> MountPrimaryAction.START
        }
    }

    fun shouldWriteProfile(status: CalibrationStatus): Boolean = status is CalibrationStatus.Done

    fun abortBecauseMoving(tooFast: Boolean, running: Boolean): Boolean = tooFast && running

    private fun elapsedNs(
        firstSampleNs: Long?,
        lastSampleNs: Long?,
        startedWallNs: Long,
        wallNs: Long,
    ): Long {
        val wallSpan = if (startedWallNs > 0L) (wallNs - startedWallNs).coerceAtLeast(0L) else 0L
        val sampleSpan =
            if (firstSampleNs != null && lastSampleNs != null) {
                (lastSampleNs - firstSampleNs).coerceAtLeast(0L)
            } else {
                0L
            }
        return max(wallSpan, sampleSpan)
    }
}
