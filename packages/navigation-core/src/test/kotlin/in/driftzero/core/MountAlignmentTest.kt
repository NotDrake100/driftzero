package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MountAlignmentTest {
    @Test
    fun gravityOnPlusYPhoneAxisMatchesAndroidYForwardFamily() {
        val gravity = Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0)
        val attitude = rollPitchFromGravity(gravity)
        val ok = attitude as GravityAttitude.Ok
        assertEquals(PI / 2.0, ok.rollPitch.rollRad, 1.0e-12)
        assertEquals(0.0, ok.rollPitch.pitchRad, 1.0e-12)
        assertMat3Close(ANDROID_Y_FORWARD, ok.rotationPhoneToVehicle, 1.0e-12)
        assertTrue(inAndroidYForwardFamily(ok.rotationPhoneToVehicle, gravity))
        val mapped = ok.rotationPhoneToVehicle * gravity
        assertVec3Close(Vec3(0.0, 0.0, STANDARD_GRAVITY_MPS2), mapped, 1.0e-12)
    }

    @Test
    fun gravityTilted20DegreesRecoversRollAndPitch() {
        val tiltRad = Math.toRadians(20.0)
        val gravity = Mat3.rotationX(tiltRad) * Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0)
        val expectedRoll = atan2YOverZ(gravity)
        val attitude = rollPitchFromGravity(gravity) as GravityAttitude.Ok
        assertEquals(expectedRoll, attitude.rollPitch.rollRad, 1.0e-12)
        assertEquals(0.0, attitude.rollPitch.pitchRad, 1.0e-12)
        assertEquals(PI / 2.0 - tiltRad, attitude.rollPitch.rollRad, 1.0e-12)

        val synthetic = bodyToVehicle(attitude.rollPitch.rollRad, attitude.rollPitch.pitchRad, ANDROID_Y_FORWARD_YAW_RAD)
        val recovered =
            (rotationPhoneToVehicleFromGravity(gravity, yawAboutVehicleZRad = 0.0) as RotationFromGravity.Ok)
                .rotationPhoneToVehicle
        val mapped = recovered * gravity
        assertVec3Close(Vec3(0.0, 0.0, gravity.norm()), mapped, 1.0e-9)
        assertMat3Close(synthetic, recovered, 1.0e-9)
        assertEquals(tiltRad, angleBetweenUnits(Vec3(0.0, 1.0, 0.0), gravity), 1.0e-12)
    }

    @Test
    fun yawFromThreeAccelerationEventsWithSpeedDeltasResolvesSign() {
        val yaw = YawFromMotion(Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0))
        val accelForward = Vec3(0.0, STANDARD_GRAVITY_MPS2, -1.2)
        val accelBrake = Vec3(0.0, STANDARD_GRAVITY_MPS2, 1.1)
        assertTrue(yaw.add(1_000_000_000L, accelForward, Vec3.ZERO, 2.4) is SampleAccept.Accepted)
        assertTrue(yaw.add(2_000_000_000L, accelBrake, Vec3.ZERO, -1.8) is SampleAccept.Accepted)
        assertTrue(yaw.add(3_000_000_000L, accelForward, Vec3.ZERO, 1.6) is SampleAccept.Accepted)
        val estimate = yaw.estimate() as YawResult.YawEstimate
        assertEquals(3, estimate.eventCount)
        assertEquals(0.0, estimate.yawRad, 1.0e-9)
        assertTrue(estimate.confidence >= 0.99)
        val composed =
            composeMountProfile(
                stillOk(Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0)),
                estimate,
                createdNs = 4_000_000_000L,
            ) as ProfileCompose.Ok
        assertEquals(ProfileQuality.ALIGNED_HIGH, composed.profile.quality)
        assertMat3Close(ANDROID_Y_FORWARD, composed.profile.rotationPhoneToVehicle, 1.0e-9)
    }

    @Test
    fun yawWithoutSpeedDeltasStaysPending() {
        val yaw = YawFromMotion(Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0))
        val accel = Vec3(0.0, STANDARD_GRAVITY_MPS2, -1.5)
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
                rotationPhoneToVehicle = ANDROID_Y_FORWARD,
                gravityPhone = Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0),
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
            val update = monitor.update(t, tiltedGravity(Math.toRadians(15.0)))
            if (update is MisalignmentUpdate.Remount) {
                remount = update
                break
            }
            t += dtNs
        }
        val fired = remount
        assertTrue(fired != null)
        assertTrue(fired!!.detectedAtNs - startNs >= 3_000_000_000L)
        assertTrue(fired.angleRad > Math.toRadians(8.0))
    }

    @Test
    fun monitorIgnoresShortSpike() {
        val monitor = MisalignmentMonitor()
        monitor.setProfile(alignedProfile())
        var t = 0L
        val dtNs = 20_000_000L
        repeat(10) {
            assertTrue(monitor.update(t, Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0)) is MisalignmentUpdate.Observing)
            t += dtNs
        }
        val spikeEnd = t + 300_000_000L
        while (t <= spikeEnd) {
            val update = monitor.update(t, tiltedGravity(Math.toRadians(20.0)))
            assertTrue(update is MisalignmentUpdate.Observing)
            t += dtNs
        }
        repeat(10) {
            assertTrue(monitor.update(t, Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0)) is MisalignmentUpdate.Observing)
            t += dtNs
        }
        assertNull(firstRemountOrNull(monitor, t, t + 200_000_000L, Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0)))
    }

    @Test
    fun insufficientAndMovingResultsIncludeReasons() {
        val short = StationaryCapture()
        short.add(0L, Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0), Vec3.ZERO)
        short.add(1_000_000L, Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0), Vec3.ZERO)
        val insufficient = short.result() as StationaryResult.Insufficient
        assertTrue(insufficient.reason.contains("sampleCount"))

        val moving = StationaryCapture(StationaryConfig(minDurationNs = 1_000_000_000L, minSampleCount = 20))
        var t = 0L
        repeat(40) { index ->
            val jitter = if (index % 2 == 0) 1.8 else -1.8
            moving.add(
                t,
                Vec3(jitter, STANDARD_GRAVITY_MPS2, 0.0),
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
        assertTrue(capture.add(5_000_000L, Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0), Vec3.ZERO) is SampleAccept.Accepted)
        val rejected =
            capture.add(4_000_000L, Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0), Vec3.ZERO) as SampleAccept.Rejected
        assertTrue(rejected.reason.contains("non-monotonic"))
        val result = capture.result() as StationaryResult.Insufficient
        assertTrue(result.reason.contains("non-monotonic"))

        val yaw = YawFromMotion(Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0))
        val accel = Vec3(0.0, STANDARD_GRAVITY_MPS2, -1.0)
        assertTrue(yaw.add(10L, accel, Vec3.ZERO, 1.0) is SampleAccept.Accepted)
        val yawRejected = yaw.add(10L, accel, Vec3.ZERO, 1.0) as SampleAccept.Rejected
        assertTrue(yawRejected.reason.contains("non-monotonic"))

        val monitor = MisalignmentMonitor()
        monitor.setProfile(alignedProfile())
        assertTrue(monitor.update(100L, Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0)) is MisalignmentUpdate.Observing)
        val monitorRejected = monitor.update(50L, Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0)) as MisalignmentUpdate.Rejected
        assertTrue(monitorRejected.reason.contains("non-monotonic"))
    }

    @Test
    fun stillCaptureProducesOkGravityAndBias() {
        val capture = StationaryCapture()
        val gravity = Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0)
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
                rotationPhoneToVehicle = ANDROID_Y_FORWARD,
                gravityPhone = Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0),
                gyroBias = Vec3(0.1, 0.0, 0.0),
                yawConfidence = 0.0,
                createdNs = 0L,
                quality = ProfileQuality.STATIONARY_ONLY,
            )
        assertVec3Close(Vec3(0.0, 0.0, STANDARD_GRAVITY_MPS2), profile.toVehicleAccel(profile.gravityPhone), 1.0e-12)
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
            rotationPhoneToVehicle = ANDROID_Y_FORWARD,
            gravityPhone = Vec3(0.0, STANDARD_GRAVITY_MPS2, 0.0),
            gyroBias = Vec3.ZERO,
            yawConfidence = 0.0,
            createdNs = 0L,
            quality = ProfileQuality.STATIONARY_ONLY,
        )

    private fun tiltedGravity(tiltRad: Double): Vec3 =
        Vec3(0.0, STANDARD_GRAVITY_MPS2 * cos(tiltRad), STANDARD_GRAVITY_MPS2 * sin(tiltRad))

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
        if (mapped.minus(up).norm() > 1.0e-8) {
            return false
        }
        val relative = ANDROID_Y_FORWARD.transpose() * rotation
        val euler = relative.toBodyToVehicleEuler()
        return abs(euler.rollRad) < 1.0e-8 && abs(euler.pitchRad) < 1.0e-8
    }

    private fun atan2YOverZ(v: Vec3): Double = kotlin.math.atan2(v.y, v.z)

    private fun angleBetweenUnits(a: Vec3, b: Vec3): Double =
        (angleBetween(a, b) as AngleBetween.Ok).radians

    private fun assertVec3Close(expected: Vec3, actual: Vec3, absTol: Double) {
        assertEquals("x", expected.x, actual.x, absTol)
        assertEquals("y", expected.y, actual.y, absTol)
        assertEquals("z", expected.z, actual.z, absTol)
    }

    private fun assertMat3Close(expected: Mat3, actual: Mat3, absTol: Double) {
        val e = expected.toRowMajor()
        val a = actual.toRowMajor()
        for (i in e.indices) {
            assertEquals("m[$i]", e[i], a[i], absTol)
        }
    }
}
