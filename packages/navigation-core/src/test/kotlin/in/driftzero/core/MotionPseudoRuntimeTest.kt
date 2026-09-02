package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPseudoRuntimeTest {
    @Test
    fun idleWindowBecomesZuptUpdateOnTheFilterHook() {
        val filter = DeadReckoningFilter()
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 18.5,
            longitudeDeg = 73.8,
            velocityEnu = Vec3(3.0, 4.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
        )
        val runtime = MotionPseudoRuntime()
        val g = ImuMotionConstants.GRAVITY_MPS2
        val dt = 20_000_000L
        for (i in 0 until 40) {
            val t = Nanoseconds(i * dt)
            runtime.ingestAccel(t, 0.0, 0.0, g)
            runtime.ingestGyro(t, 0.0, 0.0, 0.0)
        }
        val now = Nanoseconds(39 * dt)
        val meas = runtime.inferAt(now)
        assertNotNull(meas)
        assertTrue(meas!!.idle)
        assertEquals(0.0, meas.forwardSpeed.value, 1e-12)
        filter.ingestMotionPseudo(meas, now)
        val v = filter.velocityEnu()
        assertEquals(0.0, v.x, 0.15)
        assertEquals(0.0, v.y, 0.15)
        val pose = filter.poseAt(now)
        assertNotNull(pose)
        assertTrue(pose!!.health.modelOk)
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_MOTION_PSEUDO))
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_ZUPT))
    }

    @Test
    fun shortWindowYieldsNoMeasurement() {
        val runtime = MotionPseudoRuntime()
        runtime.ingestAccel(Nanoseconds(0L), 0.0, 0.0, 9.81)
        assertEquals(null, runtime.inferAt(Nanoseconds(0L)))
    }
}
