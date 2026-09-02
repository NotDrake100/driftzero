package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class ZuptAccelMotionModelTest {
    @Test
    fun idleWindowIsZuptWithZeroSpeed() {
        val window = idleWindow(samples = 50, endNs = 1_000_000_000L)
        val meas = ZuptAccelMotionModel().infer(window)
        assertTrue(meas.idle)
        assertEquals(0.0, meas.forwardSpeed.value, 1e-12)
        assertTrue(meas.stopProbability >= ImuMotionConstants.STOP_ZUPT)
        assertTrue(meas.logSpeedVariance.isFinite())
        assertTrue(exp(meas.logSpeedVariance) < 0.01)
        assertFalse(meas.bump)
    }

    @Test
    fun futureSampleIsRejected() {
        val samples = listOf(
            ImuSample(Nanoseconds(0L), 0.0, 0.0, 9.81),
            ImuSample(Nanoseconds(2_000_000_000L), 0.0, 0.0, 9.81),
        )
        try {
            CausalImuWindow(Nanoseconds(1_000_000_000L), samples)
            throw AssertionError("future sample must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("future"))
        }
    }

    @Test
    fun bufferDropsSamplesAfterEndTime() {
        val buffer = CausalImuBuffer()
        buffer.pushAccel(Nanoseconds(0L), 0.0, 0.0, 9.81)
        buffer.pushAccel(Nanoseconds(2_000_000_000L), 1.0, 0.0, 9.81)
        val window = buffer.windowEndingAt(Nanoseconds(1_000_000_000L))
        assertEquals(1, window.samples.size)
        assertEquals(0L, window.samples[0].timestamp.value)
    }

    @Test
    fun bumpFlagFromVerticalSpike() {
        val samples = ArrayList<ImuSample>()
        val dt = 20_000_000L
        for (i in 0 until 40) {
            val z = if (i == 35) 14.5 else ImuMotionConstants.GRAVITY_MPS2
            samples.add(
                ImuSample(
                    timestamp = Nanoseconds(i * dt),
                    accelX = 0.0,
                    accelY = 0.0,
                    accelZ = z,
                    gyroX = 0.0,
                    gyroY = 0.0,
                    gyroZ = 0.0,
                ),
            )
        }
        val meas = ZuptAccelMotionModel().infer(
            CausalImuWindow(Nanoseconds(39 * dt), samples),
        )
        assertTrue(meas.bump)
    }

    @Test
    fun vibrationYieldsPositiveSpeedWhenNotIdle() {
        val samples = ArrayList<ImuSample>()
        val dt = 20_000_000L
        for (i in 0 until 50) {
            val vib = if (i % 2 == 0) 2.4 else -2.4
            samples.add(
                ImuSample(
                    timestamp = Nanoseconds(i * dt),
                    accelX = vib,
                    accelY = 0.3,
                    accelZ = ImuMotionConstants.GRAVITY_MPS2 + 0.5,
                    gyroX = 0.2,
                    gyroY = -0.15,
                    gyroZ = 0.12,
                ),
            )
        }
        val meas = ZuptAccelMotionModel().infer(
            CausalImuWindow(Nanoseconds(49 * dt), samples),
        )
        assertFalse(meas.idle)
        assertTrue(meas.forwardSpeed.value > 1.0)
        assertTrue(meas.vibrationEnergy > ImuMotionConstants.VIB_IDLE_ENERGY)
        assertEquals(
            ImuMotionConstants.SPEED_VIB_GAIN * sqrt(meas.vibrationEnergy),
            meas.forwardSpeed.value,
            1e-9,
        )
    }

    @Test
    fun hookZerosCoastSpeedOnIdleAndKeepsPriorOtherwise() {
        val idle = ZuptAccelMotionModel().infer(idleWindow(40, 800_000_000L))
        assertEquals(0.0, MotionPseudoHook.coastSpeedMps(12.0, idle), 1e-12)
        val moving = idle.copy(
            idle = false,
            stopProbability = 0.1,
            forwardSpeed = MetresPerSecond(4.0),
        )
        assertEquals(12.0, MotionPseudoHook.coastSpeedMps(12.0, moving), 1e-12)
    }

    @Test
    fun featureVectorHasNoGnssSlots() {
        assertEquals(12, ImuMotionConstants.FEATURE_DIM)
        val names = CausalImuFeatures.FEATURE_NAMES
        assertEquals(ImuMotionConstants.FEATURE_DIM, names.size)
        val banned = setOf(
            "latitude_deg",
            "longitude_deg",
            "gnss_speed_mps",
            "satellites_used",
            "horizontal_accuracy_m",
        )
        assertTrue(names.none { it in banned })
    }

    @Test
    fun speedVarianceIsExpOfLogVariance() {
        val meas = ZuptAccelMotionModel().infer(idleWindow(20, 400_000_000L))
        val r = MotionPseudoHook.speedVarianceMps2(meas)
        assertTrue(abs(r - exp(meas.logSpeedVariance)) < 1e-12)
    }

    private fun idleWindow(samples: Int, endNs: Long): CausalImuWindow {
        val dt = endNs / samples
        val rows = (0 until samples).map { i ->
            ImuSample(
                timestamp = Nanoseconds(i * dt),
                accelX = 0.0,
                accelY = 0.0,
                accelZ = ImuMotionConstants.GRAVITY_MPS2,
                gyroX = 0.0,
                gyroY = 0.0,
                gyroZ = 0.0,
            )
        }
        return CausalImuWindow(Nanoseconds(endNs), rows)
    }
}
