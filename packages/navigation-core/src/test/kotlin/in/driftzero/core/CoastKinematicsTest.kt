package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Analytical vehicle trajectories, independent of the filter's propagation formula. */
class CoastKinematicsTest {
    @Test
    fun rightHandedLeftAndRightTurnsAgreeWithAttitudeAndCircularGeometry() {
        for (frame in listOf(VectorFrame.VEHICLE_FLU, VectorFrame.ANDROID_DEVICE)) {
            for (yaw in listOf(-PI / 20.0, 0.0, PI / 20.0)) {
                for (hz in listOf(10, 100, 200)) {
                    val speed = 10.0
                    val filter = DeadReckoningFilter(InsConfig(
                        coastMode = CoastMode.YAW_SPEED_HOLD,
                        nhcMinSpeedMps = 100.0,
                        lowConfidenceRadiusM = 100_000.0,
                    ))
                    filter.seedForTest(
                        Nanoseconds(0), 0.0, 0.0,
                        Vec3(0.0, speed, 0.0), yawOnlyAttitude(0.0, frame),
                        posStdM = 5.0,
                        frame = frame, headingRad = 0.0,
                    )
                    filter.setGnssHeld(true)
                    // The phone is rigidly mounted. +Z angular rate means a left turn.
                    filter.ingestAccel(Nanoseconds(0), 0.0, 0.0, Wgs84.gravityMps2(0.0), frame)
                    filter.ingestGyro(Nanoseconds(0), 0.0, 0.0, yaw, frame)
                    for (i in 1..10 * hz) {
                        val t = Nanoseconds(i * (1_000_000_000L / hz))
                        filter.ingestAccel(t, 0.0, 0.0, Wgs84.gravityMps2(0.0), frame)
                        filter.ingestGyro(t, 0.0, 0.0, yaw, frame)
                    }
                    val position = filter.positionEnu()
                    val expectedEast = if (yaw == 0.0) 0.0 else speed * (cos(yaw * 10.0) - 1.0) / yaw
                    val expectedNorth = if (yaw == 0.0) 100.0 else speed * sin(yaw * 10.0) / yaw
                    assertEquals("east, frame=$frame hz=$hz yaw=$yaw", expectedEast, position.x, 0.02)
                    assertEquals("north, frame=$frame hz=$hz yaw=$yaw", expectedNorth, position.y, 0.02)
                    val forward = filter.attitude().rotate(bodyForward(frame))
                    val attitudeHeading = atan2(forward.x, forward.y)
                    val pose = filter.poseAt(Nanoseconds(10_000_000_000L))!!
                    val difference = pose.motion.heading.value - attitudeHeading
                    assertEquals("attitude and velocity headings agree", 0.0, atan2(sin(difference), cos(difference)), 0.002)
                    assertEquals(speed, pose.motion.speed.value, 1e-6)
                }
            }
        }
    }

    @Test
    fun gnssReseedRotatesAttitudeToClockwiseBearing() {
        val filter = DeadReckoningFilter(InsConfig(
            coastMode = CoastMode.YAW_SPEED_HOLD,
            gnssReseedAfterS = 3.0,
            nhcMinSpeedMps = 100.0,
        ))
        filter.seedForTest(
            Nanoseconds(0), 0.0, 0.0, Vec3(0.0, 10.0, 0.0),
            yawOnlyAttitude(0.0, VectorFrame.VEHICLE_FLU),
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU, headingRad = 0.0,
        )
        val target = PI / 3.0
        val point = Wgs84.offsetMetres(0.0, 0.0, 50.0, 80.0)
        filter.ingestGnss(CoastFix(
            timestamp = Nanoseconds(9_000_000_000L),
            latitudeDeg = point.first, longitudeDeg = point.second,
            speedMps = 10.0, headingRad = target, horizontalAccuracyM = 5.0,
        ))
        assertEquals(DeadReckoningFilter.GNSS_RESEED_AFTER_GAP, filter.lastGnssAdmitForTest())
        val forward = filter.attitude().rotate(Vec3.EX)
        assertEquals(target, atan2(forward.x, forward.y), 1e-6)
        assertTrue(filter.velocityEnu().dot(forward) > 9.9)
    }
}
