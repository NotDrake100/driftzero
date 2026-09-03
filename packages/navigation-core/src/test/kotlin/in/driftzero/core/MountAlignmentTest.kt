package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MountAlignmentTest {
    @Test
    fun gravityOnPlusZPhoneAxisMatchesAndroidYForwardFamily() {
        val gravity = Vec3(0.0, 0.0, G)
        val attitude = rollPitchFromGravity(gravity)
        val ok = attitude as GravityAttitude.Ok
        assertEquals(0.0, ok.rollPitch.rollRad, 1.0e-12)
        assertEquals(0.0, ok.rollPitch.pitchRad, 1.0e-12)
        assertMat3Close(Mat3.ANDROID_Y_FORWARD, ok.rotationPhoneToVehicle, 1.0e-12)
        assertTrue(inAndroidYForwardFamily(ok.rotationPhoneToVehicle, gravity))
        val mapped = ok.rotationPhoneToVehicle * gravity
        assertVec3Close(Vec3(0.0, 0.0, G), mapped, 1.0e-12)
    }

    @Test
    fun gravityTilted20DegreesRecoversRollAndPitch() {
        val tiltRad = Math.toRadians(20.0)
        val gravity = rotateX(tiltRad, Vec3(0.0, 0.0, G))
        val expectedRoll = atan2(gravity.y, gravity.z)
        val attitude = rollPitchFromGravity(gravity) as GravityAttitude.Ok
        assertEquals(expectedRoll, attitude.rollPitch.rollRad, 1.0e-12)
        assertEquals(0.0, attitude.rollPitch.pitchRad, 1.0e-12)
        assertEquals(-tiltRad, attitude.rollPitch.rollRad, 1.0e-12)

        val recovered =
            (rotationPhoneToVehicleFromGravity(gravity, yawAboutVehicleZRad = 0.0) as RotationFromGravity.Ok)
                .rotationPhoneToVehicle
        val mapped = recovered * gravity
        assertVec3Close(Vec3(0.0, 0.0, gravity.norm()), mapped, 1.0e-9)
        assertEquals(tiltRad, angleBetweenUnits(Vec3.EZ, gravity), 1.0e-12)
    }

    @Test
    fun yawFromThreeAccelerationEventsWithSpeedDeltasResolvesSign() {
        val yaw = YawFromMotion(Vec3(0.0, 0.0, G))
        val accelForward = Vec3(0.0, 1.2, G)
        val accelBrake = Vec3(0.0, -1.1, G)
        assertTrue(yaw.add(1_000_000_000L, accelForward, Vec3.ZERO, 2.4) is SampleAccept.Accepted)
        assertTrue(yaw.add(2_000_000_000L, accelBrake, Vec3.ZERO, -1.8) is SampleAccept.Accepted)
        assertTrue(yaw.add(3_000_000_000L, accelForward, Vec3.ZERO, 1.6) is SampleAccept.Accepted)
        val estimate = yaw.estimate() as YawResult.YawEstimate
        assertEquals(3, estimate.eventCount)
        assertEquals(0.0, estimate.yawRad, 1.0e-9)
        assertTrue(estimate.confidence >= 0.99)
        val composed =
            composeMountProfile(
                stillOk(Vec3(0.0, 0.0, G)),
                estimate,
                createdNs = 4_000_000_000L,
            ) as ProfileCompose.Ok
        assertEquals(ProfileQuality.ALIGNED_HIGH, composed.profile.quality)
        assertMat3Close(Mat3.ANDROID_Y_FORWARD, composed.profile.rotationPhoneToVehicle, 1.0e-9)
    }

    @Test
    fun yawWithPositiveSpeedDeltaOnPhoneMinusYResolvesPi() {
        val yaw = YawFromMotion(Vec3(0.0, 0.0, G))
        val accelPhoneMinusY = Vec3(0.0, -1.2, G)
        assertTrue(yaw.add(1_000_000_000L, accelPhoneMinusY, Vec3.ZERO, 2.4) is SampleAccept.Accepted)
        assertTrue(yaw.add(2_000_000_000L, accelPhoneMinusY, Vec3.ZERO, 1.8) is SampleAccept.Accepted)
        assertTrue(yaw.add(3_000_000_000L, accelPhoneMinusY, Vec3.ZERO, 1.6) is SampleAccept.Accepted)
        val estimate = yaw.estimate() as YawResult.YawEstimate
        assertEquals(3, estimate.eventCount)
        assertEquals(Math.PI, abs(estimate.yawRad), 1.0e-9)
        val composed =
            composeMountProfile(
                stillOk(Vec3(0.0, 0.0, G)),
                estimate,
                createdNs = 4_000_000_000L,
            ) as ProfileCompose.Ok
        assertEquals(ProfileQuality.ALIGNED_HIGH, composed.profile.quality)
        val vehicle = composed.profile.toVehicleAccel(accelPhoneMinusY)
        assertEquals(1.2, vehicle.x, 1.0e-9)
        assertEquals(0.0, vehicle.y, 1.0e-9)
        assertEquals(G, vehicle.z, 1.0e-9)
    }

    @Test
    fun mountSessionStillThenSpeedDeltaAlignsAndRemountReturnsPending() {
        val session = MountSession()
        var t = 0L
        val dt = 40_000_000L
        repeat(80) {
            session.onGyro(t, 0.0, 0.0, 0.0)
            val emit = session.onAccel(t, 0.0, 0.0, G, gnssSpeedDeltaMps = null, gnssAccepted = false)
            assertEquals(VectorFrame.ANDROID_DEVICE, emit.frame)
            t += dt
        }
        assertEquals(MountQuality.STATIONARY_ONLY, session.quality())
        val accelForward = 1.2
        repeat(3) {
            session.onGyro(t, 0.0, 0.0, 0.0)
            val emit =
                session.onAccel(t, 0.0, accelForward, G, gnssSpeedDeltaMps = 2.4, gnssAccepted = true)
            t += dt
            if (it == 2) {
                assertEquals(MountQuality.ALIGNED_HIGH, emit.quality)
                assertEquals(VectorFrame.VEHICLE_FLU, emit.frame)
                assertEquals(accelForward, emit.x, 1.0e-6)
            }
        }
        assertTrue(session.quality().emitsVehicleFrame())
        repeat(20) {
            session.onGyro(t, 0.0, 0.0, 0.0)
            session.onAccel(t, 0.0, 0.0, G, null, false)
            t += dt
        }
        val tilt = tiltedGravity(Math.toRadians(30.0))
        var remount = false
        val end = t + 3_500_000_000L
        while (t <= end) {
            session.onGyro(t, 0.0, 0.0, 0.0)
            val emit = session.onAccel(t, tilt.x, tilt.y, tilt.z, null, false, phoneStill = false)
            if (emit.remount) {
                remount = true
                assertEquals(MountQuality.PENDING, emit.quality)
                assertEquals(VectorFrame.ANDROID_DEVICE, emit.frame)
                break
            }
            t += dt
        }
        assertTrue(remount)
        assertEquals(MountQuality.PENDING, session.quality())
    }

    @Test
    fun remountDoesNotFireWhilePhoneStill() {
        val session = MountSession()
        var t = 0L
        val dt = 40_000_000L
        repeat(80) {
            session.onGyro(t, 0.0, 0.0, 0.0)
            session.onAccel(t, 0.0, 0.0, G, gnssSpeedDeltaMps = null, gnssAccepted = false)
            t += dt
        }
        assertEquals(MountQuality.STATIONARY_ONLY, session.quality())
        val tilt = tiltedGravity(Math.toRadians(30.0))
        val end = t + 4_000_000_000L
        var remount = false
        while (t <= end) {
            session.onGyro(t, 0.0, 0.0, 0.0)
            val emit = session.onAccel(t, tilt.x, tilt.y, tilt.z, null, false, phoneStill = true)
            if (emit.remount) {
                remount = true
                break
            }
            t += dt
        }
        assertTrue(!remount)
        assertEquals(MountQuality.STATIONARY_ONLY, session.quality())
    }

    @Test
    fun yawWithoutSpeedDeltasStaysPending() {
        val yaw = YawFromMotion(Vec3(0.0, 0.0, G))
        val accel = Vec3(0.0, 1.5, G)
        assertTrue(yaw.add(10L, accel, Vec3.ZERO, null) is SampleAccept.Accepted)
        assertTrue(yaw.add(20L, accel, Vec3.ZERO, null) is SampleAccept.Accepted)
        assertTrue(yaw.add(30L, accel, Vec3.ZERO, 0.0) is SampleAccept.Accepted)
        val pending = yaw.estimate() as YawResult.Pending
        assertEquals(0, pending.eventCount)
        assertEquals(3, pending.needed)
    }

    @Test
    fun profileJsonRoundTrip() {
        val profile =
            MountProfile(
                rotationPhoneToVehicle = Mat3.ANDROID_Y_FORWARD,
                gravityPhone = Vec3(0.0, 0.0, G),
                gyroBias = Vec3(0.001, -0.002, 0.0004),
                yawConfidence = 0.91,
                createdNs = 12_345_678_901L,
                quality = ProfileQuality.ALIGNED_HIGH,
            )
        val json = profile.toJson()
        val parsed = MountProfile.fromJson(json) as ProfileParse.Ok
        assertMat3Close(profile.rotationPhoneToVehicle, parsed.profile.rotationPhoneToVehicle, 1.0e-12)
        assertVec3Close(profile.gravityPhone, parsed.profile.gravityPhone, 1.0e-12)
        assertVec3Close(profile.gyroBias, parsed.profile.gyroBias, 1.0e-12)
        assertEquals(profile.yawConfidence, parsed.profile.yawConfidence, 1.0e-12)
        assertEquals(profile.createdNs, parsed.profile.createdNs)
        assertEquals(profile.quality, parsed.profile.quality)

        val missing = MountProfile.fromJson("""{"schema_version":"mount_profile_1.0.0"}""")
        assertTrue(missing is ProfileParse.Invalid)
        assertTrue((missing as ProfileParse.Invalid).reason.contains("rotation_phone_to_vehicle"))
    }

    @Test
    fun monitorTriggersAfterThreeSecondsOverThreshold() {
        val monitor = MisalignmentMonitor()
        monitor.setProfile(alignedProfile())
        val startNs = 10_000_000_000L
        val dtNs = 20_000_000L
        var remount: MisalignmentUpdate.Remount? = null
        var t = startNs
        val endNs = startNs + 3_000_000_000L
        while (t <= endNs) {
            val update = monitor.update(t, tiltedGravity(Math.toRadians(30.0)))
            if (update is MisalignmentUpdate.Remount) {
                remount = update
                break
            }
            t += dtNs
        }
        val fired = remount
        assertTrue(fired != null)
        assertTrue(fired!!.detectedAtNs - startNs >= 3_000_000_000L)
        assertTrue(fired.angleRad > Math.toRadians(25.0))
    }

    @Test
    fun monitorDoesNotTriggerForFifteenDegreeTilt() {
        val monitor = MisalignmentMonitor()
        monitor.setProfile(alignedProfile())
        val startNs = 10_000_000_000L
        val dtNs = 20_000_000L
        var t = startNs
        val endNs = startNs + 4_000_000_000L
        while (t <= endNs) {
            val update = monitor.update(t, tiltedGravity(Math.toRadians(15.0)))
            assertTrue(update is MisalignmentUpdate.Observing)
            t += dtNs
        }
    }

    @Test
    fun monitorIgnoresShortSpike() {
        val monitor = MisalignmentMonitor()
        monitor.setProfile(alignedProfile())
        var t = 0L
        val dtNs = 20_000_000L
        repeat(10) {
            assertTrue(monitor.update(t, Vec3(0.0, 0.0, G)) is MisalignmentUpdate.Observing)
            t += dtNs
        }
        val spikeEnd = t + 300_000_000L
        while (t <= spikeEnd) {
            val update = monitor.update(t, tiltedGravity(Math.toRadians(20.0)))
            assertTrue(update is MisalignmentUpdate.Observing)
            t += dtNs
        }
        repeat(10) {
            assertTrue(monitor.update(t, Vec3(0.0, 0.0, G)) is MisalignmentUpdate.Observing)
            t += dtNs
        }
        assertNull(firstRemountOrNull(monitor, t, t + 200_000_000L, Vec3(0.0, 0.0, G)))
    }

    @Test
    fun insufficientAndMovingResultsIncludeReasons() {
        val short = StationaryCapture()
        short.add(0L, Vec3(0.0, 0.0, G), Vec3.ZERO)
        short.add(1_000_000L, Vec3(0.0, 0.0, G), Vec3.ZERO)
        val insufficient = short.result() as StationaryResult.Insufficient
        assertTrue(insufficient.reason.contains("sampleCount"))

        val moving = StationaryCapture(StationaryConfig(minDurationNs = 1_000_000_000L, minSampleCount = 20))
        var t = 0L
        repeat(40) { index ->
            val jitter = if (index % 2 == 0) 1.8 else -1.8
            moving.add(
                t,
                Vec3(jitter, 0.0, G),
                Vec3(0.4, -0.3, 0.5),
            )
            t += 50_000_000L
        }
        val movingResult = moving.result() as StationaryResult.Moving
        assertTrue(movingResult.reason.contains("Std") || movingResult.reason.contains("specific-force"))
    }

    @Test
    fun nonMonotonicTimestampIsRejected() {
        val capture = StationaryCapture()
        assertTrue(capture.add(5_000_000L, Vec3(0.0, 0.0, G), Vec3.ZERO) is SampleAccept.Accepted)
        val rejected =
            capture.add(4_000_000L, Vec3(0.0, 0.0, G), Vec3.ZERO) as SampleAccept.Rejected
        assertTrue(rejected.reason.contains("non-monotonic"))
        val result = capture.result() as StationaryResult.Insufficient
        assertTrue(result.reason.contains("non-monotonic"))

        val yaw = YawFromMotion(Vec3(0.0, 0.0, G))
        val accel = Vec3(0.0, 1.0, G)
        assertTrue(yaw.add(10L, accel, Vec3.ZERO, 1.0) is SampleAccept.Accepted)
        val yawRejected = yaw.add(10L, accel, Vec3.ZERO, 1.0) as SampleAccept.Rejected
        assertTrue(yawRejected.reason.contains("non-monotonic"))

        val monitor = MisalignmentMonitor()
        monitor.setProfile(alignedProfile())
        assertTrue(monitor.update(100L, Vec3(0.0, 0.0, G)) is MisalignmentUpdate.Observing)
        val monitorRejected = monitor.update(50L, Vec3(0.0, 0.0, G)) as MisalignmentUpdate.Rejected
        assertTrue(monitorRejected.reason.contains("non-monotonic"))
    }

    @Test
    fun stillCaptureProducesOkGravityAndBias() {
        val capture = StationaryCapture()
        val gravity = Vec3(0.0, 0.0, G)
        val bias = Vec3(0.01, -0.02, 0.005)
        var t = 0L
        repeat(80) {
            capture.add(t, gravity, bias)
            t += 40_000_000L
        }
        val ok = capture.result() as StationaryResult.Ok
        assertVec3Close(gravity, ok.gravityPhone, 1.0e-12)
        assertVec3Close(bias, ok.gyroBias, 1.0e-12)
        assertEquals(80, ok.sampleCount)
        assertTrue(ok.durationNs >= 2_000_000_000L)
        assertTrue(ok.accelStd < 1.0e-12)
        assertTrue(ok.gyroStd < 1.0e-12)
    }

    @Test
    fun filterHookRotatesPhoneAccelAndDebiasesGyro() {
        val profile =
            MountProfile(
                rotationPhoneToVehicle = Mat3.ANDROID_Y_FORWARD,
                gravityPhone = Vec3(0.0, 0.0, G),
                gyroBias = Vec3(0.1, 0.0, 0.0),
                yawConfidence = 0.0,
                createdNs = 0L,
                quality = ProfileQuality.STATIONARY_ONLY,
            )
        assertVec3Close(Vec3(0.0, 0.0, G), profile.toVehicleAccel(profile.gravityPhone), 1.0e-12)
        assertVec3Close(Vec3.ZERO, profile.toVehicleGyro(Vec3(0.1, 0.0, 0.0)), 1.0e-12)
    }

    private fun stillOk(gravity: Vec3): StationaryResult.Ok =
        StationaryResult.Ok(
            gravityPhone = gravity,
            gyroBias = Vec3.ZERO,
            accelStd = 0.01,
            gyroStd = 0.001,
            sampleCount = 80,
            durationNs = 3_000_000_000L,
        )

    private fun alignedProfile(): MountProfile =
        MountProfile(
            rotationPhoneToVehicle = Mat3.ANDROID_Y_FORWARD,
            gravityPhone = Vec3(0.0, 0.0, G),
            gyroBias = Vec3.ZERO,
            yawConfidence = 0.0,
            createdNs = 0L,
            quality = ProfileQuality.STATIONARY_ONLY,
        )

    private fun tiltedGravity(tiltRad: Double): Vec3 =
        Vec3(0.0, G * sin(tiltRad), G * cos(tiltRad))

    private fun firstRemountOrNull(
        monitor: MisalignmentMonitor,
        startNs: Long,
        endNs: Long,
        accel: Vec3,
    ): MisalignmentUpdate.Remount? {
        var t = startNs
        while (t <= endNs) {
            val update = monitor.update(t, accel)
            if (update is MisalignmentUpdate.Remount) {
                return update
            }
            t += 20_000_000L
        }
        return null
    }

    private fun inAndroidYForwardFamily(rotation: Mat3, gravityPhone: Vec3): Boolean {
        val mapped = rotation * gravityPhone
        val up = Vec3(0.0, 0.0, gravityPhone.norm())
        if ((mapped - up).norm() > 1.0e-8) {
            return false
        }
        val relative = Mat3.ANDROID_Y_FORWARD.transpose() * rotation
        return abs(relative.r02) < 1.0e-8 &&
            abs(relative.r12) < 1.0e-8 &&
            abs(relative.r20) < 1.0e-8 &&
            abs(relative.r21) < 1.0e-8 &&
            abs(relative.r22 - 1.0) < 1.0e-8
    }

    private fun rotateX(angleRad: Double, v: Vec3): Vec3 {
        val c = cos(angleRad)
        val s = sin(angleRad)
        return Vec3(v.x, c * v.y - s * v.z, s * v.y + c * v.z)
    }

    private fun angleBetweenUnits(a: Vec3, b: Vec3): Double {
        val ua = a.normalized()!!
        val ub = b.normalized()!!
        return acos(ua.dot(ub).coerceIn(-1.0, 1.0))
    }

    private fun assertVec3Close(expected: Vec3, actual: Vec3, absTol: Double) {
        assertEquals("x", expected.x, actual.x, absTol)
        assertEquals("y", expected.y, actual.y, absTol)
        assertEquals("z", expected.z, actual.z, absTol)
    }

    private fun assertMat3Close(expected: Mat3, actual: Mat3, absTol: Double) {
        for (i in 0..2) {
            for (j in 0..2) {
                assertEquals("m[$i,$j]", expected[i, j], actual[i, j], absTol)
            }
        }
    }

    companion object {
        private val G: Double = Wgs84.STANDARD_G
    }
}
