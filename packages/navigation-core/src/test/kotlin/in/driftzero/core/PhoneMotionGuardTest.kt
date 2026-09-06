package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMotionGuardTest {
    @Test
    fun gravityYawDoesNotRequirePhoneForwardAxisOrMount() {
        for (up in listOf(Vec3.EX, Vec3.EY, Vec3.EZ, Vec3(1.0, 2.0, 3.0).normalized()!!)) {
            val guard = PhoneMotionGuard()
            guard.onGravity(0, up.x * 9.81, up.y * 9.81, up.z * 9.81)
            val observation = guard.onGyro(1, up.x * 0.2, up.y * 0.2, up.z * 0.2)
            assertEquals(0.2, observation.yawUpRadps!!, 1e-12)
            assertFalse(observation.handling)
            assertNull(guard.onGyro(600_000_000, 0.0, 0.0, 0.2).yawUpRadps)
        }
    }

    @Test
    fun pickupHasSettlingPeriodAndResetDropsPriorTripGravity() {
        val guard = PhoneMotionGuard()
        guard.onGravity(0, 0.0, 0.0, 9.81)
        assertTrue(guard.onGyro(1, 1.0, 0.0, 0.0).handling)
        guard.onGravity(1_000_000_000, 0.0, 0.0, 9.81)
        assertTrue(guard.onGyro(1_000_000_001, 0.0, 0.0, 0.0).handling)
        guard.onGravity(3_000_000_000, 0.0, 0.0, 9.81)
        assertFalse(guard.onGyro(3_000_000_001, 0.0, 0.0, 0.0).handling)
        guard.reset()
        assertNull(guard.onGyro(1, 0.0, 0.0, 0.2).yawUpRadps)
    }

    @Test
    fun handlingPreservesMovingBlackoutTrajectoryAndExpandsUncertainty() {
        val filter = DeadReckoningFilter(InsConfig(coastMode = CoastMode.YAW_SPEED_HOLD))
        filter.seedForTest(Nanoseconds(0), 0.0, 0.0, Vec3(0.0, 10.0, 0.0),
            yawOnlyAttitude(0.0, VectorFrame.ANDROID_DEVICE), posStdM = 5.0,
            frame = VectorFrame.ANDROID_DEVICE, headingRad = 0.0)
        filter.setGnssHeld(true)
        val before = filter.poseAt(Nanoseconds(0))!!
        filter.setPhoneMotion(true, null, Nanoseconds(0))
        for (i in 1..100) {
            val t = Nanoseconds(i * 10_000_000L)
            filter.ingestAccel(t, 8.0, -4.0, 3.0)
            filter.ingestGyro(t, 1.0, 2.0, -3.0)
        }
        val after = filter.poseAt(Nanoseconds(1_000_000_000))!!
        assertEquals(0.0, filter.positionEnu().x, 1e-6)
        assertEquals(10.0, filter.positionEnu().y, 1e-6)
        assertEquals(10.0, after.motion.speed.value, 1e-6)
        assertEquals(NavigationMode.LOW_CONFIDENCE, after.mode)
        assertTrue(after.health.flags.contains("phone_handling"))
        assertTrue(after.uncertainty.horizontal95.value > before.uncertainty.horizontal95.value)
    }
}
