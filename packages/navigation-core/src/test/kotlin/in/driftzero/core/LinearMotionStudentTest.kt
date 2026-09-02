package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class LinearMotionStudentTest {
    @Test
    fun parseAndInferUsesInterceptSpeed() {
        val student = LinearMotionStudent.parse(interceptJson(speedBias = 0.0, stopBias = -4.0, logVarBias = -1.0))
        val meas = student.infer(idleWindow(40, 800_000_000L))
        assertEquals(ln(2.0), meas.forwardSpeed.value, 1e-9)
        assertEquals(-1.0, meas.logSpeedVariance, 1e-9)
        assertFalse(meas.idle)
        assertTrue(meas.stopProbability < 0.1)
    }

    @Test
    fun gnssFeatureNamesAreRejected() {
        val zeros = (0 until 2).joinToString(",") { "0.0" }
        val json = """{"schema":"${LinearSpeedConstants.SCHEMA}","feature_names":["latitude_deg"],"weights_speed":[$zeros],"weights_stop":[$zeros],"weights_logvar":[$zeros]}"""
        try {
            LinearMotionStudent.parse(json)
            throw AssertionError("GNSS feature must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("GNSS"))
        }
    }

    @Test
    fun zuptModelUsesStudentWhenPresent() {
        val student = LinearMotionStudent.parse(interceptJson(speedBias = 0.0, stopBias = -4.0, logVarBias = 0.0))
        val heuristic = ZuptAccelMotionModel().infer(idleWindow(40, 800_000_000L))
        val learned = ZuptAccelMotionModel(student).infer(idleWindow(40, 800_000_000L))
        assertEquals(0.0, heuristic.forwardSpeed.value, 1e-12)
        assertTrue(heuristic.idle)
        assertEquals(ln(2.0), learned.forwardSpeed.value, 1e-9)
        assertFalse(learned.idle)
    }

    @Test
    fun runtimeFeedsStudentSpeed() {
        val student = LinearMotionStudent.parse(interceptJson(speedBias = 0.0, stopBias = -4.0, logVarBias = -1.0))
        val runtime = MotionPseudoRuntime(model = ZuptAccelMotionModel(student))
        val g = ImuMotionConstants.GRAVITY_MPS2
        val dt = 20_000_000L
        for (i in 0 until 40) {
            val t = Nanoseconds(i * dt)
            runtime.ingestAccel(t, 0.0, 0.0, g)
            runtime.ingestGyro(t, 0.0, 0.0, 0.0)
        }
        val meas = runtime.inferAt(Nanoseconds(39 * dt))
        assertEquals(ln(2.0), meas!!.forwardSpeed.value, 1e-9)
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

    private fun interceptJson(speedBias: Double, stopBias: Double, logVarBias: Double): String {
        val dim = CausalImuFeatures.FEATURE_NAMES.size + 1
        val names = CausalImuFeatures.FEATURE_NAMES.joinToString(",") { "\"$it\"" }
        fun row(bias: Double): String {
            val cells = MutableList(dim) { 0.0 }
            cells[0] = bias
            return cells.joinToString(prefix = "[", postfix = "]")
        }
        return """{"schema":"${LinearSpeedConstants.SCHEMA}","feature_names":[$names],"weights_speed":${row(speedBias)},"weights_stop":${row(stopBias)},"weights_logvar":${row(logVarBias)}}"""
    }
}
