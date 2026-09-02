package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class LinearDisplacementStudentTest {
    @Test
    fun levelPhoneHacfIsIdentity() {
        val rotation = hacfRotation(Vec3(0.0, 0.0, ImuMotionConstants.GRAVITY_MPS2))
        val mapped = rotateIntoHacf(Vec3(0.0, 0.0, ImuMotionConstants.GRAVITY_MPS2), rotation)
        assertEquals(0.0, mapped.x, 1e-9)
        assertEquals(0.0, mapped.y, 1e-9)
        assertEquals(ImuMotionConstants.GRAVITY_MPS2, mapped.z, 1e-9)
        assertEquals(1.0, rotation.r00, 1e-9)
        assertEquals(1.0, rotation.r11, 1e-9)
        assertEquals(1.0, rotation.r22, 1e-9)
    }

    @Test
    fun parseAndInferUsesInterceptWeights() {
        val student = LinearDisplacementStudent.parse(interceptJson(dx = 3.5, dy = -0.25, dz = 0.1, logSigma = -1.2))
        val window = idleWindow(40, 800_000_000L)
        val meas = student.infer(window)
        assertNotNull(meas)
        assertEquals(3.5, meas!!.dxM, 1e-9)
        assertEquals(-0.25, meas.dyM, 1e-9)
        assertEquals(0.1, meas.dzM, 1e-9)
        assertEquals(-1.2, meas.logSigmaX, 1e-9)
        assertEquals(window.samples.first().timestamp.value, meas.windowStart.value)
    }

    @Test
    fun gnssFeatureNamesAreRejected() {
        val row = "[0.0,0.0]"
        val weights = (0 until LinearDpConstants.HEAD_DIM).joinToString(",") { row }
        val json = """{"schema":"driftzero.linear_dp.v1","feature_names":["latitude_deg"],"weights":[$weights]}"""
        try {
            LinearDisplacementStudent.parse(json)
            throw AssertionError("GNSS feature must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("GNSS"))
        }
    }

    @Test
    fun decodeMatchesPythonSoftplusAndClamp() {
        val heads = decodeRawHeads(doubleArrayOf(1.0, 0.2, 0.0, -9.0, 7.0, -1.0, 0.0, 0.5, 0.0, 0.1))
        assertEquals(1.0, heads.dxM, 1e-12)
        assertEquals(LinearDpConstants.LOG_SIGMA_MIN, heads.logSigmaX, 1e-12)
        assertEquals(LinearDpConstants.LOG_SIGMA_MAX, heads.logSigmaY, 1e-12)
        assertEquals(ln(2.0), heads.forwardSpeedMps, 1e-9)
    }

    @Test
    fun runtimeFeedsDisplacementWhenStudentIsPresent() {
        val student = LinearDisplacementStudent.parse(interceptJson(dx = 2.0, dy = 0.0, dz = 0.0, logSigma = -1.0))
        val runtime = MotionPseudoRuntime(displacement = student)
        val g = ImuMotionConstants.GRAVITY_MPS2
        val dt = 20_000_000L
        for (i in 0 until 40) {
            val t = Nanoseconds(i * dt)
            runtime.ingestAccel(t, 0.0, 0.0, g)
            runtime.ingestGyro(t, 0.0, 0.0, 0.0)
        }
        val now = Nanoseconds(39 * dt)
        val meas = runtime.inferDisplacementAt(now)
        assertNotNull(meas)
        assertEquals(2.0, meas!!.dxM, 1e-9)
    }

    @Test
    fun chi2OfUnitResidualOnIdentitySIsOne() {
        val p = Square(EskfDim.N)
        p.setIdentity()
        val h = DoubleArray(EskfDim.N)
        h[0] = 1.0
        val chi2 = innovationChiSquared(p, h, doubleArrayOf(1.0), doubleArrayOf(0.0), 1, JosephScratch())
        assertNotNull(chi2)
        assertEquals(1.0, chi2!!, 1e-9)
        assertTrue(LinearDpConstants.CHI2_99_3DOF > 11.3)
        assertTrue(LinearDpConstants.CHI2_99_3DOF < 11.4)
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

    private fun interceptJson(dx: Double, dy: Double, dz: Double, logSigma: Double): String {
        val dim = LinearDpConstants.FEATURE_NAMES.size + 1
        val intercepts = DoubleArray(LinearDpConstants.HEAD_DIM)
        intercepts[0] = dx
        intercepts[1] = dy
        intercepts[2] = dz
        intercepts[3] = logSigma
        intercepts[4] = logSigma
        intercepts[5] = logSigma
        val names = LinearDpConstants.FEATURE_NAMES.joinToString(",") { "\"$it\"" }
        val rows = intercepts.joinToString(",") { value ->
            val cells = MutableList(dim) { 0.0 }
            cells[0] = value
            cells.joinToString(prefix = "[", postfix = "]")
        }
        return """{"schema":"${LinearDpConstants.SCHEMA}","feature_names":[$names],"heads":[],"weights":[$rows]}"""
    }
}
