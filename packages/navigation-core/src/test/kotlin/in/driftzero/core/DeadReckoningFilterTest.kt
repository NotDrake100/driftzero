package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class DeadReckoningFilterTest {
    @Test
    fun noFixYieldsNoPose() {
        val filter = DeadReckoningFilter()
        assertNull(filter.poseAt(Nanoseconds(1_000_000_000L)))
    }

    @Test
    fun eastSpecificForceIntegratesToEastVelocity() {
        val filter = DeadReckoningFilter()
        val g = Wgs84.gravityMps2(0.0)
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3.ZERO,
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
            headingRad = PI / 2.0,
        )
        val dtNs = 10_000_000L
        val steps = 100
        for (i in 1..steps) {
            val t = Nanoseconds(i * dtNs)
            stepImu(filter, t, ax = 1.0, ay = 0.0, az = g)
        }
        val v = filter.velocityEnu()
        assertEquals("east velocity", 1.0, v.x, 0.08)
        assertEquals("north velocity", 0.0, v.y, 0.08)
        val p = filter.positionEnu()
        assertEquals("east displacement", 0.5, p.x, 0.08)
        val pose = filter.poseAt(Nanoseconds(steps * dtNs))
        assertNotNull(pose)
        assertTrue(pose!!.position.longitude.value > 0.0)
        assertEquals(0.0, pose.position.latitude.value, 1e-5)
    }

    @Test
    fun eastAccelOneMps2ForTwoSecondsFromRest() {
        val filter = DeadReckoningFilter(InsConfig(nhcMinSpeedMps = 100.0))
        val g = Wgs84.gravityMps2(0.0)
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3.ZERO,
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
            headingRad = PI / 2.0,
        )
        val dtNs = 10_000_000L
        val steps = 200
        for (i in 1..steps) {
            stepImu(filter, Nanoseconds(i * dtNs), ax = 1.0, ay = 0.0, az = g)
        }
        val v = filter.velocityEnu()
        val p = filter.positionEnu()
        assertEquals("east velocity", 2.0, v.x, 0.05)
        assertEquals("north velocity", 0.0, v.y, 0.05)
        assertEquals("east displacement ½at²", 2.0, p.x, 0.05)
    }

    @Test
    fun gravityOnlyDoesNotInventHorizontalVelocity() {
        val filter = DeadReckoningFilter()
        val g = Wgs84.gravityMps2(0.0)
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3.ZERO,
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
        )
        val dtNs = 10_000_000L
        for (i in 1..100) {
            stepImu(filter, Nanoseconds(i * dtNs), ax = 0.0, ay = 0.0, az = g)
        }
        val v = filter.velocityEnu()
        assertEquals(0.0, v.x, 0.05)
        assertEquals(0.0, v.y, 0.05)
        assertEquals(0.0, v.z, 0.08)
    }

    @Test
    fun gnssPositionUpdateReducesHorizontalCovariance() {
        val filter = DeadReckoningFilter()
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 51.5,
            longitudeDeg = -0.12,
            velocityEnu = Vec3.ZERO,
            quat = Quat.IDENTITY,
            posStdM = 40.0,
            frame = VectorFrame.VEHICLE_FLU,
        )
        val before = filter.horizontalVariance()
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(100_000_000L),
                latitudeDeg = 51.5,
                longitudeDeg = -0.12,
                speedMps = 0.0,
                headingRad = 0.0,
                horizontalAccuracyM = 3.0,
            ),
        )
        val after = filter.horizontalVariance()
        assertTrue("P before $before after $after", after < before * 0.25)
        val pose = filter.poseAt(Nanoseconds(100_000_000L))
        assertEquals(NavigationMode.GNSS_FUSED, pose!!.mode)
        assertTrue(pose.health.filterOk)
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_ESKF))
        assertFalse(pose.health.flags.any { it.contains("cv_stub") })
    }

    @Test
    fun gnssOlderThanTwoSecondsIsDeadReckoning() {
        val filter = DeadReckoningFilter()
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = -33.86,
                longitudeDeg = 151.21,
                speedMps = 8.0,
                headingRad = 0.0,
                horizontalAccuracyM = 4.0,
            ),
        )
        val pose = filter.poseAt(Nanoseconds(3_000_000_000L))
        assertNotNull(pose)
        assertEquals(NavigationMode.DEAD_RECKONING, pose!!.mode)
        assertEquals(3.0, pose.gnssHealth.lastTrustedFixAgeS, 1e-6)
        assertTrue(pose.position.latitude.value > -33.86)
    }

    @Test
    fun heldGnssIgnoresNewFix() {
        val filter = DeadReckoningFilter()
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                speedMps = 10.0,
                headingRad = 0.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        filter.setGnssHeld(true)
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(500_000_000L),
                latitudeDeg = 1.0,
                longitudeDeg = 1.0,
                speedMps = 1.0,
                headingRad = 1.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        val pose = filter.poseAt(Nanoseconds(1_000_000_000L))
        assertEquals(NavigationMode.DEAD_RECKONING, pose!!.mode)
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_GPS_HELD))
        assertTrue(abs(pose.position.latitude.value) < 0.01)
        assertTrue(abs(pose.position.longitude.value) < 0.01)
    }

    @Test
    fun zuptFromStopProbabilityKillsVelocity() {
        val filter = DeadReckoningFilter()
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 40.0,
            longitudeDeg = -74.0,
            velocityEnu = Vec3(3.0, 4.0, 0.2),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
        )
        filter.ingestMotionPseudo(
            MotionPseudoMeasurement(
                forwardSpeed = MetresPerSecond(0.0),
                yawRateRadps = 0.0,
                stopProbability = 0.95,
                logSpeedVariance = kotlin.math.ln(0.03 * 0.03),
            ),
            Nanoseconds(20_000_000L),
        )
        val v = filter.velocityEnu()
        assertEquals(0.0, v.x, 0.15)
        assertEquals(0.0, v.y, 0.15)
        assertEquals(0.0, v.z, 0.15)
    }

    @Test
    fun nhcDropsLateralVelocityAndHighLateralAccelSkipsIt() {
        val tight = DeadReckoningFilter(
            InsConfig(nhcMinSpeedMps = 0.5, nhcVelStdMps = 0.05, nhcDropLateralMps2 = 2.0),
        )
        tight.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(5.0, 4.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
            headingRad = PI / 2.0,
        )
        val g = Wgs84.gravityMps2(0.0)
        for (i in 1..8) {
            stepImu(tight, Nanoseconds(i * 20_000_000L), ax = 0.0, ay = 0.0, az = g)
        }
        val after = tight.velocityEnu()
        assertTrue("lateral north should drop, vn=${after.y}", abs(after.y) < 1.5)
        assertTrue("forward east should remain, ve=${after.x}", after.x > 3.0)

        val dropped = DeadReckoningFilter(
            InsConfig(nhcMinSpeedMps = 0.5, nhcDropLateralMps2 = 2.0),
        )
        dropped.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(5.0, 4.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
            headingRad = PI / 2.0,
        )
        stepImu(dropped, Nanoseconds(20_000_000L), ax = 0.0, ay = 5.0, az = g)
        val skipped = dropped.velocityEnu()
        assertTrue("NHC skipped so north leak remains, vn=${skipped.y}", skipped.y > 3.5)
    }

    @Test
    fun outputSequenceIncrementsAndConfigHashIsStable() {
        val filter = DeadReckoningFilter()
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 35.68,
                longitudeDeg = 139.76,
                speedMps = 1.0,
                headingRad = 0.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        val first = filter.poseAt(Nanoseconds(100_000_000L))
        val second = filter.poseAt(Nanoseconds(200_000_000L))
        assertEquals(0L, first!!.sequence)
        assertEquals(1L, second!!.sequence)
        assertEquals(64, DeadReckoningFilter.CONFIG_HASH.length)
        assertEquals(sha256Hex(DeadReckoningFilter.CONFIG_ID), DeadReckoningFilter.CONFIG_HASH)
    }

    @Test
    fun consumeSensorFramesMatchesDirectIngest() {
        val filter = DeadReckoningFilter()
        filter.consume(
            SensorFrame(
                sourceId = "test-gnss",
                sequence = 0L,
                timestamp = Nanoseconds(0L),
                clockDomain = ClockDomain.ANDROID_ELAPSED_REALTIME,
                kind = SensorKind.GNSS_FIX,
                quality = Quality(available = true, accuracyCode = 3),
                payload = FixPayload(
                    GnssFixPayload(
                        latitude = LatitudeDeg(0.0),
                        longitude = LongitudeDeg(0.0),
                        horizontalAccuracyM = Metres(4.0),
                        providerTimeMs = 0L,
                        speedMps = MetresPerSecond(0.0),
                        bearingRad = HeadingRadians(0.0),
                    ),
                ),
            ),
        )
        val pose = filter.poseAt(Nanoseconds(0L))
        assertEquals(NavigationMode.GNSS_FUSED, pose!!.mode)
    }

    @Test
    fun poorAccuracyFixReportsGnssDegradedNotFused() {
        val filter = DeadReckoningFilter()
        filter.ingestGnss(fix(0L, accuracyM = 4.0))
        assertEquals(NavigationMode.GNSS_FUSED, filter.poseAt(Nanoseconds(100_000_000L))!!.mode)
        filter.ingestGnss(fix(1_000_000_000L, accuracyM = 45.0))
        val pose = filter.poseAt(Nanoseconds(1_100_000_000L))!!
        assertEquals(NavigationMode.GNSS_DEGRADED, pose.mode)
        assertTrue(pose.gnssHealth.riskFlags.contains(DeadReckoningFilter.RISK_POOR_ACCURACY))
        filter.ingestGnss(fix(2_000_000_000L, accuracyM = 5.0))
        assertEquals(NavigationMode.GNSS_FUSED, filter.poseAt(Nanoseconds(2_100_000_000L))!!.mode)
    }

    @Test
    fun coastThenFixesWalkThroughReacquiringBeforeFused() {
        val filter = DeadReckoningFilter()
        filter.ingestGnss(fix(0L))
        assertEquals(NavigationMode.DEAD_RECKONING, filter.poseAt(Nanoseconds(3_000_000_000L))!!.mode)
        filter.ingestGnss(fix(3_000_000_000L))
        val first = filter.poseAt(Nanoseconds(3_100_000_000L))!!
        assertEquals(NavigationMode.REACQUIRING, first.mode)
        assertTrue(first.gnssHealth.riskFlags.contains(DeadReckoningFilter.RISK_REACQUIRING))
        assertFalse(first.gnssHealth.riskFlags.contains(DeadReckoningFilter.RISK_STALE_GNSS))
        filter.ingestGnss(fix(4_000_000_000L))
        assertEquals(NavigationMode.REACQUIRING, filter.poseAt(Nanoseconds(4_100_000_000L))!!.mode)
        filter.ingestGnss(fix(5_000_000_000L))
        assertEquals(NavigationMode.GNSS_FUSED, filter.poseAt(Nanoseconds(5_100_000_000L))!!.mode)
    }

    @Test
    fun heldGnssReleaseGoesThroughReacquiring() {
        val filter = DeadReckoningFilter()
        filter.ingestGnss(fix(0L))
        filter.setGnssHeld(true)
        assertEquals(NavigationMode.DEAD_RECKONING, filter.poseAt(Nanoseconds(500_000_000L))!!.mode)
        filter.setGnssHeld(false)
        filter.ingestGnss(fix(600_000_000L))
        assertEquals(NavigationMode.REACQUIRING, filter.poseAt(Nanoseconds(700_000_000L))!!.mode)
    }

    @Test
    fun gatedFixKeepsDegradedForFiveSecondsThenClears() {
        val filter = DeadReckoningFilter()
        filter.ingestGnss(fix(0L))
        // 0.01 deg of latitude is about 1.1 km, far outside the 6 sigma gate.
        filter.ingestGnss(fix(500_000_000L, latitudeDeg = 0.01))
        val gated = filter.poseAt(Nanoseconds(600_000_000L))!!
        assertEquals(NavigationMode.GNSS_DEGRADED, gated.mode)
        assertTrue(gated.gnssHealth.riskFlags.contains(DeadReckoningFilter.RISK_GATED_FIX))
        assertTrue(abs(gated.position.latitude.value) < 0.001)
        filter.ingestGnss(fix(1_500_000_000L))
        assertEquals(NavigationMode.GNSS_DEGRADED, filter.poseAt(Nanoseconds(1_600_000_000L))!!.mode)
        filter.ingestGnss(fix(3_000_000_000L))
        filter.ingestGnss(fix(4_500_000_000L))
        filter.ingestGnss(fix(6_000_000_000L))
        assertEquals(NavigationMode.GNSS_FUSED, filter.poseAt(Nanoseconds(6_000_000_000L))!!.mode)
    }

    @Test
    fun unspecifiedFrameDoesNotApplyNhc() {
        val filter = DeadReckoningFilter(
            InsConfig(nhcMinSpeedMps = 0.5, nhcVelStdMps = 0.05, nhcDropLateralMps2 = 2.0),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(5.0, 4.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = PI / 2.0,
        )
        val g = Wgs84.gravityMps2(0.0)
        for (i in 1..8) {
            filter.ingestGyro(Nanoseconds(i * 20_000_000L), 0.0, 0.0, 0.0, VectorFrame.UNSPECIFIED)
            filter.ingestAccel(Nanoseconds(i * 20_000_000L), 0.0, 0.0, g, VectorFrame.UNSPECIFIED)
        }
        val after = filter.velocityEnu()
        assertTrue("NHC must not run on unspecified, vn=${after.y}", after.y > 3.5)
        val pose = filter.poseAt(Nanoseconds(8 * 20_000_000L))
        assertFalse(pose!!.health.flags.contains(DeadReckoningFilter.FLAG_NHC))
    }

    @Test
    fun heldHighSpeedSkipsStillZupt() {
        val filter = DeadReckoningFilter()
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(8.0, 0.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
            headingRad = PI / 2.0,
        )
        filter.setGnssHeld(true)
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.4, 0.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
            headingRad = PI / 2.0,
        )
        val g = Wgs84.gravityMps2(0.0)
        for (i in 1..8) {
            stepImu(filter, Nanoseconds(i * 20_000_000L), ax = 0.0, ay = 0.0, az = g)
        }
        val held = filter.velocityEnu()
        assertTrue("held still-ZUPT skip should keep speed, ve=${held.x}", held.x > 0.2)
        val heldPose = filter.poseAt(Nanoseconds(8 * 20_000_000L))
        assertFalse(heldPose!!.health.flags.contains(DeadReckoningFilter.FLAG_ZUPT))

        val free = DeadReckoningFilter()
        free.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.4, 0.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
            headingRad = PI / 2.0,
        )
        for (i in 1..8) {
            stepImu(free, Nanoseconds(i * 20_000_000L), ax = 0.0, ay = 0.0, az = g)
        }
        val stopped = free.velocityEnu()
        assertTrue("unheld still-ZUPT should kill speed, ve=${stopped.x}", abs(stopped.x) < 0.15)
    }

    @Test
    fun gapBetweenFixesWithoutPoseQueryStillCountsAsCoast() {
        val filter = DeadReckoningFilter()
        filter.ingestGnss(fix(0L))
        filter.ingestGnss(fix(4_000_000_000L))
        assertEquals(NavigationMode.REACQUIRING, filter.poseAt(Nanoseconds(4_000_000_000L))!!.mode)
    }

    @Test
    fun yawSpeedHoldNorthCoastHoldsSpeedAndDisplacement() {
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        val g = Wgs84.gravityMps2(0.0)
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 15.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        filter.setGnssHeld(true)
        val dtNs = 100_000_000L
        val steps = 200
        for (i in 1..steps) {
            stepImu(filter, Nanoseconds(i * dtNs), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
        }
        val v = filter.velocityEnu()
        val speed = hypot(v.x, v.y)
        assertEquals("held north speed", 15.0, speed, 0.5)
        assertEquals("vertical held at 0", 0.0, v.z, 0.05)
        val p = filter.positionEnu()
        assertEquals("300 m north", 300.0, p.y, 5.0)
        assertEquals("no east leak", 0.0, p.x, 5.0)
    }

    @Test
    fun strapdownTiltLeaksHorizontalSpeed() {
        val filter = DeadReckoningFilter(
            InsConfig(nhcMinSpeedMps = 100.0, lowConfidenceRadiusM = 10_000.0),
        )
        val g = Wgs84.gravityMps2(0.0)
        val half = 0.5 * 5.0 * PI / 180.0
        val tilted = Quat(cos(half), sin(half), 0.0, 0.0).normalized()
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 15.0, 0.0),
            quat = tilted,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        val dtNs = 100_000_000L
        for (i in 1..100) {
            stepImu(filter, Nanoseconds(i * dtNs), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
        }
        val v = filter.velocityEnu()
        val speedError = abs(hypot(v.x, v.y) - 15.0)
        assertTrue("strapdown tilt leak should grow speed error past 2 m/s, got $speedError", speedError > 2.0)
    }

    @Test
    fun yawSpeedHoldVerticalGyroCurvesPathAndHoldsSpeed() {
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        val g = Wgs84.gravityMps2(0.0)
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 15.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        filter.setGnssHeld(true)
        val dtNs = 100_000_000L
        val steps = 100
        for (i in 1..steps) {
            stepImu(
                filter,
                Nanoseconds(i * dtNs),
                0.0,
                0.0,
                g,
                gx = 0.0,
                gy = 0.0,
                gz = 0.1,
                frame = VectorFrame.UNSPECIFIED,
            )
        }
        val pose = filter.poseAt(Nanoseconds(steps * dtNs))!!
        val heading = pose.motion.heading.value
        val headingErr = minHeadingDelta(heading, 1.0)
        assertTrue("heading should change by ~1 rad, got $heading", headingErr < 0.15)
        assertEquals("speed held", 15.0, pose.motion.speed.value, 0.5)
        val p = filter.positionEnu()
        assertTrue("path should curve east, east=${p.x}", abs(p.x) > 20.0)
        assertTrue("north should be less than a straight 150 m, north=${p.y}", p.y < 145.0)
    }

    @Test
    fun ageOutCoastSkipsStillZuptAndIdleStudentStillZeroes() {
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        val g = Wgs84.gravityMps2(0.0)
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 8.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        val dtNs = 100_000_000L
        for (i in 1..30) {
            stepImu(filter, Nanoseconds(i * dtNs), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
        }
        val coasted = filter.velocityEnu()
        assertTrue("age-out must not collapse velocity, vn=${coasted.y}", hypot(coasted.x, coasted.y) > 7.0)
        val pose = filter.poseAt(Nanoseconds(3_000_000_000L))!!
        assertEquals(NavigationMode.DEAD_RECKONING, pose.mode)
        assertFalse(pose.health.flags.contains(DeadReckoningFilter.FLAG_GPS_HELD))

        val planted = DeadReckoningFilter(InsConfig(nhcMinSpeedMps = 100.0))
        planted.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(8.0, 0.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
            headingRad = PI / 2.0,
        )
        for (i in 1..25) {
            stepImu(planted, Nanoseconds(i * dtNs), 0.0, 0.0, g)
        }
        planted.plantEnuForTest(velocity = Vec3(0.4, 0.0, 0.0))
        for (i in 26..35) {
            stepImu(planted, Nanoseconds(i * dtNs), 0.0, 0.0, g)
        }
        val skipped = planted.velocityEnu()
        assertTrue("age-out still-ZUPT skip should keep speed, ve=${skipped.x}", skipped.x > 0.2)
        val skippedPose = planted.poseAt(Nanoseconds(35 * dtNs))
        assertFalse(skippedPose!!.health.flags.contains(DeadReckoningFilter.FLAG_ZUPT))

        filter.ingestMotionPseudo(
            MotionPseudoMeasurement(
                forwardSpeed = MetresPerSecond(0.0),
                yawRateRadps = 0.0,
                stopProbability = 0.95,
                logSpeedVariance = kotlin.math.ln(0.03 * 0.03),
            ),
            Nanoseconds(3_020_000_000L),
        )
        val stopped = filter.velocityEnu()
        assertEquals(0.0, stopped.x, 0.15)
        assertEquals(0.0, stopped.y, 0.15)
        assertEquals(0.0, stopped.z, 0.15)
    }

    @Test
    fun gnssGateSelfLockInflatesOrFlagsInsteadOfSilentFuse() {
        val filter = DeadReckoningFilter()
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3.ZERO,
            quat = Quat.IDENTITY,
            posStdM = kotlin.math.sqrt(12.5),
            frame = VectorFrame.VEHICLE_FLU,
        )
        assertEquals(25.0, filter.horizontalVariance(), 1e-6)
        filter.plantEnuForTest(position = Vec3(500.0, 0.0, 0.0))
        for (i in 1..5) {
            filter.ingestGnss(fix(i * 100_000_000L, latitudeDeg = 0.0, accuracyM = 5.0))
        }
        val pose = filter.poseAt(Nanoseconds(500_000_000L))!!
        val p = filter.positionEnu()
        val error = hypot(p.x, p.y)
        val flagged = pose.health.flags.contains(DeadReckoningFilter.FLAG_GNSS_GATE_INFLATE) ||
            pose.gnssHealth.riskFlags.contains(DeadReckoningFilter.RISK_GNSS_GATE_INFLATE) ||
            pose.gnssHealth.riskFlags.contains(DeadReckoningFilter.RISK_GATED_FIX)
        val accepted = error < 80.0
        assertTrue(
            "gate self-lock must accept after inflate or surface a gated flag, error=$error mode=${pose.mode} flags=${pose.health.flags}",
            accepted || flagged,
        )
        val silent = pose.mode == NavigationMode.GNSS_FUSED &&
            error > 250.0 &&
            !pose.health.flags.contains(DeadReckoningFilter.FLAG_GNSS_GATE_INFLATE) &&
            !pose.gnssHealth.riskFlags.contains(DeadReckoningFilter.RISK_GATED_FIX)
        assertFalse("must not stay silently fused at 500 m, error=$error", silent)
    }

    @Test
    fun yawSpeedHoldIsDeterministic() {
        val g = Wgs84.gravityMps2(0.0)
        fun runOnce(): Pair<Vec3, Vec3> {
            val filter = DeadReckoningFilter(
                InsConfig(coastMode = CoastMode.YAW_SPEED_HOLD, nhcMinSpeedMps = 100.0),
            )
            filter.seedForTest(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                velocityEnu = Vec3(0.0, 15.0, 0.0),
                quat = Quat.IDENTITY,
                posStdM = 5.0,
                frame = VectorFrame.UNSPECIFIED,
                headingRad = 0.0,
            )
            filter.setGnssHeld(true)
            for (i in 1..50) {
                stepImu(
                    filter,
                    Nanoseconds(i * 100_000_000L),
                    0.0,
                    0.0,
                    g,
                    gz = 0.02,
                    frame = VectorFrame.UNSPECIFIED,
                )
            }
            return filter.positionEnu() to filter.velocityEnu()
        }
        val first = runOnce()
        val second = runOnce()
        assertEquals(first.first.x, second.first.x, 0.0)
        assertEquals(first.first.y, second.first.y, 0.0)
        assertEquals(first.second.x, second.second.x, 0.0)
        assertEquals(first.second.y, second.second.y, 0.0)
    }

    @Test
    fun coastVibrationStopBeatsPersistHold() {
        val g = Wgs84.gravityMps2(0.0)
        val dtNs = 100_000_000L
        val drive1 = 200
        val stop = 150
        val drive2 = 200
        fun run(detect: Boolean, restart: CoastRestart): Vec3 {
            val filter = DeadReckoningFilter(
                InsConfig(
                    coastMode = CoastMode.YAW_SPEED_HOLD,
                    coastStopDetect = detect,
                    coastRestart = restart,
                    nhcMinSpeedMps = 100.0,
                    lowConfidenceRadiusM = 10_000.0,
                ),
            )
            filter.seedForTest(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                velocityEnu = Vec3(0.0, 15.0, 0.0),
                quat = Quat.IDENTITY,
                posStdM = 5.0,
                frame = VectorFrame.UNSPECIFIED,
                headingRad = 0.0,
            )
            filter.setGnssHeld(true)
            val total = drive1 + stop + drive2
            for (i in 1..total) {
                val t = Nanoseconds(i * dtNs)
                if (i in (drive1 + 1)..(drive1 + stop)) {
                    stepImu(filter, t, 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
                    if (detect && i == drive1 + 80) {
                        val speed = hypot(filter.velocityEnu().x, filter.velocityEnu().y)
                        assertTrue("mid-stop ZUPT speed=$speed", speed < 1.0 && filter.coastStoppedForTest())
                    }
                } else {
                    val wobble = 1.8 * sin(2.0 * PI * i / 6.0)
                    stepImu(
                        filter,
                        t,
                        wobble,
                        0.6 * wobble,
                        g + wobble,
                        frame = VectorFrame.UNSPECIFIED,
                    )
                }
            }
            return filter.positionEnu()
        }
        val persist = run(detect = false, restart = CoastRestart.HELD_SPEED)
        val v4 = run(detect = true, restart = CoastRestart.HELD_SPEED)
        val truthNorth = 15.0 * (drive1 + drive2) * (dtNs / 1_000_000_000.0)
        val persistErr = abs(persist.y - truthNorth)
        val v4Err = abs(v4.y - truthNorth)
        assertTrue("persist should keep rolling through the stop, err=$persistErr", persistErr > 150.0)
        assertTrue(
            "v4 stop detector end error $v4Err should be far below persist $persistErr north=${v4.y}",
            v4Err < 80.0 && v4Err < persistErr * 0.4,
        )
    }

    @Test
    fun coastAccelBurstRestartGainsSpeedFromForwardAccel() {
        val g = Wgs84.gravityMps2(0.0)
        val dtNs = 100_000_000L
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                coastStopDetect = true,
                coastRestart = CoastRestart.ACCEL_BURST,
                coastRestartBurstS = 4.0,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 15.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        filter.setGnssHeld(true)
        for (i in 1..50) {
            val wobble = 1.8 * sin(2.0 * PI * i / 6.0)
            stepImu(filter, Nanoseconds(i * dtNs), wobble, 0.6 * wobble, g + wobble, frame = VectorFrame.UNSPECIFIED)
        }
        for (i in 51..80) {
            stepImu(filter, Nanoseconds(i * dtNs), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
        }
        val stopped = hypot(filter.velocityEnu().x, filter.velocityEnu().y)
        assertTrue("should be stopped, speed=$stopped", stopped < 1.0)
        for (i in 81..150) {
            val wobble = 1.8 * sin(2.0 * PI * i / 6.0)
            stepImu(
                filter,
                Nanoseconds(i * dtNs),
                wobble,
                3.0 + 0.5 * wobble,
                g + wobble,
                frame = VectorFrame.UNSPECIFIED,
            )
        }
        val speed = hypot(filter.velocityEnu().x, filter.velocityEnu().y)
        assertTrue(
            "accel burst should rebuild speed, got $speed stopped=${filter.coastStoppedForTest()} held=${filter.heldSpeedForTest()}",
            speed > 1.5 && !filter.coastStoppedForTest(),
        )
    }

    @Test
    fun applyRoadHeadingCorrectsSmallResidualAndRejectsLarge() {
        val g = Wgs84.gravityMps2(0.0)
        fun seeded(): DeadReckoningFilter {
            val filter = DeadReckoningFilter(
                InsConfig(coastMode = CoastMode.YAW_SPEED_HOLD, nhcMinSpeedMps = 100.0),
            )
            filter.seedForTest(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                velocityEnu = Vec3(0.0, 10.0, 0.0),
                quat = Quat.IDENTITY,
                posStdM = 5.0,
                frame = VectorFrame.UNSPECIFIED,
                headingRad = 0.0,
            )
            filter.setGnssHeld(true)
            stepImu(filter, Nanoseconds(100_000_000L), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
            return filter
        }
        val small = seeded()
        val start = small.positionEnu()
        val startHeading = small.poseAt(Nanoseconds(100_000_000L))!!.motion.heading.value
        val bearing = 30.0 * PI / 180.0
        val std = 3.0 * PI / 180.0
        var last: RoadHeadingResult? = null
        repeat(5) {
            last = small.applyRoadHeading(bearing, std, null)
        }
        assertTrue("30 deg residual should accept, ${last!!.reason} chi2=${last.chi2}", last.accepted)
        val after = small.poseAt(Nanoseconds(100_000_000L))!!.motion.heading.value
        val closer = minHeadingDelta(after, bearing) < minHeadingDelta(startHeading, bearing)
        assertTrue("heading should move toward bearing, start=$startHeading after=$after", closer)
        val pos = small.positionEnu()
        assertEquals("road heading must not set position", start.x, pos.x, 1e-9)
        assertEquals(start.y, pos.y, 1e-9)

        val large = seeded()
        val before = large.poseAt(Nanoseconds(100_000_000L))!!.motion.heading.value
        val rejected = large.applyRoadHeading(PI / 2.0, std, null)
        assertTrue("90 deg residual should reject, ${rejected.reason}", !rejected.accepted)
        assertEquals(RoadHeadingReason.CHI2_REJECT, rejected.reason)
        val still = large.poseAt(Nanoseconds(100_000_000L))!!.motion.heading.value
        assertEquals("rejected update must not change heading", before, still, 1e-9)
        assertEquals(start.x, large.positionEnu().x, 1e-9)
    }

    @Test
    fun androidDeviceFrameDoesNotApplyNhc() {
        val filter = DeadReckoningFilter(
            InsConfig(nhcMinSpeedMps = 0.5, nhcVelStdMps = 0.05, nhcDropLateralMps2 = 2.0),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(5.0, 4.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.ANDROID_DEVICE,
            headingRad = PI / 2.0,
        )
        val g = Wgs84.gravityMps2(0.0)
        for (i in 1..8) {
            filter.ingestGyro(Nanoseconds(i * 20_000_000L), 0.0, 0.0, 0.0, VectorFrame.ANDROID_DEVICE)
            filter.ingestAccel(Nanoseconds(i * 20_000_000L), 0.0, 0.0, g, VectorFrame.ANDROID_DEVICE)
        }
        val after = filter.velocityEnu()
        assertTrue("NHC must not run on ANDROID_DEVICE, vn=${after.y}", after.y > 3.5)
        val pose = filter.poseAt(Nanoseconds(8 * 20_000_000L))
        assertFalse(pose!!.health.flags.contains(DeadReckoningFilter.FLAG_NHC))
    }

    @Test
    fun resetRemountClearsHeldLikeUser() {
        val filter = DeadReckoningFilter(InsConfig(coastMode = CoastMode.YAW_SPEED_HOLD))
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 8.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        filter.setGnssHeld(true)
        assertTrue(filter.isGnssHeld())
        filter.reset(ResetReason.NUMERICAL)
        assertTrue("NUMERICAL must keep gnssHeld", filter.isGnssHeld())
        filter.reset(ResetReason.REMOUNT)
        assertFalse("REMOUNT must clear gnssHeld", filter.isGnssHeld())
    }

    @Test
    fun weakHeadingHoldCourseZerosYawRateAndGrowsHalo() {
        val g = Wgs84.gravityMps2(0.0)
        fun run(policy: WeakHeadingPolicy, weak: Boolean): Pair<Double, Double> {
            val filter = DeadReckoningFilter(
                InsConfig(
                    coastMode = CoastMode.YAW_SPEED_HOLD,
                    weakHeadingPolicy = policy,
                    weakHeadingGrowRadps = 0.2,
                    nhcMinSpeedMps = 100.0,
                    lowConfidenceRadiusM = 10_000.0,
                ),
            )
            filter.seedForTest(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                velocityEnu = Vec3(0.0, 15.0, 0.0),
                quat = Quat.IDENTITY,
                posStdM = 5.0,
                frame = VectorFrame.UNSPECIFIED,
                headingRad = 0.0,
            )
            filter.setHeadingPickWeak(weak, forced = true)
            filter.setGnssHeld(true)
            val steps = 100
            val dtNs = 100_000_000L
            for (i in 1..steps) {
                stepImu(
                    filter,
                    Nanoseconds(i * dtNs),
                    0.0,
                    0.0,
                    g,
                    gz = 0.1,
                    frame = VectorFrame.UNSPECIFIED,
                )
            }
            val pose = filter.poseAt(Nanoseconds(steps * dtNs))!!
            return pose.motion.heading.value to pose.uncertainty.heading95Rad
        }
        val integrate = run(WeakHeadingPolicy.INTEGRATE, weak = true)
        val hold = run(WeakHeadingPolicy.HOLD_COURSE, weak = true)
        val strong = run(WeakHeadingPolicy.HOLD_COURSE, weak = false)
        assertTrue("INTEGRATE should yaw ~1 rad, got ${integrate.first}", minHeadingDelta(integrate.first, 1.0) < 0.15)
        assertTrue("HOLD_COURSE weak must not yaw, heading=${hold.first}", minHeadingDelta(hold.first, 0.0) < 0.05)
        assertTrue("HOLD_COURSE with a strong pick still yaws, heading=${strong.first}", minHeadingDelta(strong.first, 1.0) < 0.15)
        assertTrue(
            "HOLD_COURSE should grow heading95 vs INTEGRATE, hold=${hold.second} integrate=${integrate.second}",
            hold.second > integrate.second + 0.05,
        )
    }

    @Test
    fun coastLatchUsesRecentReportedGnssSpeedNotFilterOvershoot() {
        val g = Wgs84.gravityMps2(0.0)
        val latched = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                coastLatchGnssSpeed = true,
                coastLatchGnssMaxS = 2.0,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        latched.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 15.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        latched.plantReportedGnssSpeedForTest(0.0, 0L)
        latched.setGnssHeld(true)
        stepImu(latched, Nanoseconds(100_000_000L), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
        assertEquals("recent GNSS 0 must win over filter 15", 0.0, latched.heldSpeedForTest(), 0.05)

        val stale = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                coastLatchGnssSpeed = true,
                coastLatchGnssMaxS = 2.0,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        stale.seedForTest(
            timestamp = Nanoseconds(3_000_000_000L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 15.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        stale.plantReportedGnssSpeedForTest(0.0, 0L)
        stale.setGnssHeld(true)
        stepImu(stale, Nanoseconds(3_100_000_000L), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
        assertEquals("stale GNSS 0 must fall back to filter 15", 15.0, stale.heldSpeedForTest(), 0.5)
    }

    @Test
    fun coastLatchRecapturesAtHoldAfterAgeOutSnapshot() {
        val g = Wgs84.gravityMps2(0.0)
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                coastLatchGnssSpeed = true,
                coastLatchGnssMaxS = 2.0,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 15.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        for (i in 1..30) {
            stepImu(filter, Nanoseconds(i * 100_000_000L), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
        }
        assertEquals("age-out should have latched 15", 15.0, filter.heldSpeedForTest(), 0.5)
        filter.plantReportedGnssSpeedForTest(0.0, 3_000_000_000L)
        filter.setGnssHeld(true)
        stepImu(filter, Nanoseconds(3_100_000_000L), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
        assertEquals("mask hold must recapture recent GNSS 0", 0.0, filter.heldSpeedForTest(), 0.05)
    }

    @Test
    fun coastStopRequireStoppedPrefixDisarmsWithoutStoppedBaseline() {
        val g = Wgs84.gravityMps2(0.0)
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                coastStopDetect = true,
                coastStopRequireStoppedPrefix = true,
                coastStopHoldS = 1.0,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 15.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        filter.setGnssHeld(true)
        for (i in 1..80) {
            stepImu(filter, Nanoseconds(i * 100_000_000L), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
        }
        val speed = hypot(filter.velocityEnu().x, filter.velocityEnu().y)
        assertTrue("no stopped prefix must keep rolling, speed=$speed", speed > 10.0)
        assertFalse(filter.coastStopArmedForTest())
        val pose = filter.poseAt(Nanoseconds(80 * 100_000_000L))!!
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_COAST_STOP_DISARMED))
        assertFalse(filter.coastStoppedForTest())
    }

    @Test
    fun gyroFlagsMarkWeakHeadingPickUnlessForcedAccepted() {
        val filter = DeadReckoningFilter(InsConfig(coastMode = CoastMode.YAW_SPEED_HOLD))
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 8.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        filter.consume(
            SensorFrame(
                sourceId = "test",
                sequence = 1L,
                timestamp = Nanoseconds(20_000_000L),
                clockDomain = ClockDomain.DATASET_DECLARED,
                kind = SensorKind.GYROSCOPE,
                quality = Quality(
                    available = true,
                    accuracyCode = 3,
                    flags = setOf(DeadReckoningFilter.FLAG_HEADING_PICK_WEAK),
                ),
                payload = VectorPayload(
                    Vector3Payload(0.0, 0.0, 0.0, "rad/s", VectorFrame.UNSPECIFIED),
                ),
            ),
        )
        assertTrue(filter.headingPickWeakForTest())
        filter.setHeadingPickWeak(false, forced = true)
        filter.noteQualityFlags(setOf(DeadReckoningFilter.FLAG_HEADING_PICK_WEAK))
        assertFalse("forced accepted must ignore gyro flags", filter.headingPickWeakForTest())
    }

    @Test
    fun longGapGnssReseedSnapsToFixAndReturnsFused() {
        val g = Wgs84.gravityMps2(0.0)
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                gnssReseedAfterS = 3.0,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 20.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        val dtNs = 100_000_000L
        for (i in 1..90) {
            stepImu(filter, Nanoseconds(i * dtNs), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
        }
        val (lat, lon) = Wgs84.offsetMetres(0.0, 0.0, 0.0, 150.0)
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(9_000_000_000L),
                latitudeDeg = lat,
                longitudeDeg = lon,
                speedMps = 8.0,
                headingRad = PI / 2.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        assertEquals(DeadReckoningFilter.GNSS_RESEED_AFTER_GAP, filter.lastGnssAdmitForTest())
        val pose = filter.poseAt(Nanoseconds(9_000_000_000L))!!
        assertEquals(NavigationMode.GNSS_FUSED, pose.mode)
        val dist = Wgs84.distanceMetres(
            pose.position.latitude.value,
            pose.position.longitude.value,
            lat,
            lon,
        )
        assertTrue("reseed pose must sit on the fix, dist=$dist", dist < 5.0)
        assertEquals(8.0, pose.motion.speed.value, 0.2)
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_GNSS_RESEED))
    }

    @Test
    fun longGapZeroColumnSpeedUsesUniqueHopSpeed() {
        val g = Wgs84.gravityMps2(0.0)
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                gnssReseedAfterS = 3.0,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 20.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        val dtNs = 100_000_000L
        for (i in 1..90) {
            stepImu(filter, Nanoseconds(i * dtNs), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
        }
        val (lat, lon) = Wgs84.offsetMetres(0.0, 0.0, 0.0, 150.0)
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(9_000_000_000L),
                latitudeDeg = lat,
                longitudeDeg = lon,
                speedMps = 0.0,
                headingRad = PI / 2.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        assertEquals(DeadReckoningFilter.GNSS_RESEED_AFTER_GAP, filter.lastGnssAdmitForTest())
        val pose = filter.poseAt(Nanoseconds(9_000_000_000L))!!
        assertEquals(150.0 / 9.0, pose.motion.speed.value, 0.3)
        assertTrue("hop heading should be east", minHeadingDelta(pose.motion.heading.value, PI / 2.0) < 0.1)
        val dist = Wgs84.distanceMetres(
            pose.position.latitude.value,
            pose.position.longitude.value,
            lat,
            lon,
        )
        assertTrue("reseed pose must sit on the unique hop, dist=$dist", dist < 5.0)
    }

    @Test
    fun oneHzUniqueStreamDoesNotReseedAtTSparse() {
        val g = Wgs84.gravityMps2(0.0)
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                gnssReseedAfterS = 6.0,
                coastHonestP = true,
                gnssGateInflate = false,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 10.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        val dtNs = 100_000_000L
        for (sec in 1..8) {
            for (i in 1..10) {
                val t = (sec - 1) * 1_000_000_000L + i * dtNs
                stepImu(filter, Nanoseconds(t), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
            }
            val (lat, lon) = Wgs84.offsetMetres(0.0, 0.0, sec * 10.0, 0.0)
            filter.ingestGnss(
                CoastFix(
                    timestamp = Nanoseconds(sec * 1_000_000_000L),
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    speedMps = 10.0,
                    headingRad = 0.0,
                    horizontalAccuracyM = 4.0,
                ),
            )
            assertEquals(
                "1 Hz unique must stay on the Joseph path, sec=$sec admit=${filter.lastGnssAdmitForTest()}",
                DeadReckoningFilter.GNSS_GATE_ADMIT,
                filter.lastGnssAdmitForTest(),
            )
        }
    }

    @Test
    fun fusedUniqueHopDoesNotReseedUnlessAllowed() {
        val g = Wgs84.gravityMps2(0.0)
        fun afterHop(whileFused: Boolean): DeadReckoningFilter {
            val filter = DeadReckoningFilter(
                InsConfig(
                    coastMode = CoastMode.YAW_SPEED_HOLD,
                    gnssReseedAfterS = 6.0,
                    gnssReseedWhileFused = whileFused,
                    gnssGateInflate = false,
                    nhcMinSpeedMps = 100.0,
                    lowConfidenceRadiusM = 10_000.0,
                ),
            )
            filter.seedForTest(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                velocityEnu = Vec3.ZERO,
                quat = Quat.IDENTITY,
                posStdM = 5.0,
                frame = VectorFrame.UNSPECIFIED,
                headingRad = 0.0,
            )
            val dtNs = 100_000_000L
            for (sec in 1..6) {
                for (i in 1..10) {
                    val t = (sec - 1) * 1_000_000_000L + i * dtNs
                    stepImu(filter, Nanoseconds(t), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
                }
                filter.ingestGnss(
                    CoastFix(
                        timestamp = Nanoseconds(sec * 1_000_000_000L),
                        latitudeDeg = 0.0,
                        longitudeDeg = 0.0,
                        speedMps = 0.0,
                        headingRad = 0.0,
                        horizontalAccuracyM = 4.0,
                    ),
                )
            }
            val (lat, lon) = Wgs84.offsetMetres(0.0, 0.0, 0.0, 150.0)
            filter.ingestGnss(
                CoastFix(
                    timestamp = Nanoseconds(7_000_000_000L),
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    speedMps = 8.0,
                    headingRad = PI / 2.0,
                    horizontalAccuracyM = 5.0,
                ),
            )
            return filter
        }
        val blocked = afterHop(whileFused = false)
        assertEquals(
            "fused 1 Hz must not reseed a long hop",
            DeadReckoningFilter.GNSS_GATE_REJECT,
            blocked.lastGnssAdmitForTest(),
        )
        val allowed = afterHop(whileFused = true)
        assertEquals(DeadReckoningFilter.GNSS_RESEED_AFTER_GAP, allowed.lastGnssAdmitForTest())
    }

    @Test
    fun oneHzHistoricalNineSecondHopDoesNotReseedWhenSparseRequired() {
        val g = Wgs84.gravityMps2(0.0)
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                gnssReseedAfterS = 8.0,
                gnssReseedRequireSparseSpacing = true,
                gnssReseedMinSparseHops = 3,
                gnssGateInflate = false,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 10.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        val dtNs = 100_000_000L
        for (sec in 1..5) {
            for (i in 1..10) {
                val t = (sec - 1) * 1_000_000_000L + i * dtNs
                stepImu(filter, Nanoseconds(t), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
            }
            val (lat, lon) = Wgs84.offsetMetres(0.0, 0.0, sec * 10.0, 0.0)
            filter.ingestGnss(
                CoastFix(
                    timestamp = Nanoseconds(sec * 1_000_000_000L),
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    speedMps = 10.0,
                    headingRad = 0.0,
                    horizontalAccuracyM = 4.0,
                ),
            )
            assertEquals(DeadReckoningFilter.GNSS_GATE_ADMIT, filter.lastGnssAdmitForTest())
        }
        for (i in 1..90) {
            stepImu(
                filter,
                Nanoseconds(5_000_000_000L + i * dtNs),
                0.0,
                0.0,
                g,
                frame = VectorFrame.UNSPECIFIED,
            )
        }
        val (lat, lon) = Wgs84.offsetMetres(0.0, 0.0, 140.0, 0.0)
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(14_000_000_000L),
                latitudeDeg = lat,
                longitudeDeg = lon,
                speedMps = 10.0,
                headingRad = 0.0,
                horizontalAccuracyM = 4.0,
            ),
        )
        assertTrue(
            "1 Hz plus one 9 s hop must not reseed, admit=${filter.lastGnssAdmitForTest()}",
            filter.lastGnssAdmitForTest() != DeadReckoningFilter.GNSS_RESEED_AFTER_GAP,
        )
        assertTrue(filter.lastReseedBlockedSparseForTest())
        assertTrue(filter.uniqueGapCountForTest() >= 3)
    }

    @Test
    fun sparseNineSecondStreamReseedsAfterProvenSpacing() {
        val g = Wgs84.gravityMps2(0.0)
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                gnssReseedAfterS = 8.0,
                gnssReseedRequireSparseSpacing = true,
                gnssReseedMinSparseHops = 3,
                gnssGateInflate = false,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(10.0, 0.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = PI / 2.0,
        )
        val dtNs = 100_000_000L
        val admits = mutableListOf<String>()
        for (hop in 1..3) {
            val tEnd = hop * 9_000_000_000L
            val tStart = (hop - 1) * 9_000_000_000L
            for (i in 1..90) {
                stepImu(filter, Nanoseconds(tStart + i * dtNs), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
            }
            val (lat, lon) = Wgs84.offsetMetres(0.0, 0.0, 0.0, hop * 90.0)
            filter.ingestGnss(
                CoastFix(
                    timestamp = Nanoseconds(tEnd),
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    speedMps = 10.0,
                    headingRad = PI / 2.0,
                    horizontalAccuracyM = 5.0,
                ),
            )
            admits.add(filter.lastGnssAdmitForTest())
        }
        assertEquals(
            "first two 9 s hops fail closed; third proves sparse",
            listOf(
                DeadReckoningFilter.GNSS_GATE_ADMIT,
                DeadReckoningFilter.GNSS_GATE_ADMIT,
                DeadReckoningFilter.GNSS_RESEED_AFTER_GAP,
            ),
            admits,
        )
        assertFalse(filter.lastReseedBlockedSparseForTest())
    }

    @Test
    fun sparseRequireOffStillReseedsAnyNineSecondHop() {
        val g = Wgs84.gravityMps2(0.0)
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                gnssReseedAfterS = 8.0,
                gnssReseedRequireSparseSpacing = false,
                gnssGateInflate = false,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, 10.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = 0.0,
        )
        val dtNs = 100_000_000L
        for (sec in 1..3) {
            for (i in 1..10) {
                val t = (sec - 1) * 1_000_000_000L + i * dtNs
                stepImu(filter, Nanoseconds(t), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
            }
            val (lat, lon) = Wgs84.offsetMetres(0.0, 0.0, sec * 10.0, 0.0)
            filter.ingestGnss(
                CoastFix(
                    timestamp = Nanoseconds(sec * 1_000_000_000L),
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    speedMps = 10.0,
                    headingRad = 0.0,
                    horizontalAccuracyM = 4.0,
                ),
            )
        }
        for (i in 1..90) {
            stepImu(filter, Nanoseconds(3_000_000_000L + i * dtNs), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
        }
        val (lat, lon) = Wgs84.offsetMetres(0.0, 0.0, 120.0, 0.0)
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(12_000_000_000L),
                latitudeDeg = lat,
                longitudeDeg = lon,
                speedMps = 10.0,
                headingRad = 0.0,
                horizontalAccuracyM = 4.0,
            ),
        )
        assertEquals(DeadReckoningFilter.GNSS_RESEED_AFTER_GAP, filter.lastGnssAdmitForTest())
        assertFalse(filter.lastReseedBlockedSparseForTest())
    }

    @Test
    fun liveDefaultsDoNotReseedTwoSecondLateFix() {
        val live = InsConfig(coastMode = CoastMode.YAW_SPEED_HOLD)
        assertEquals(0.0, live.gnssReseedAfterS, 0.0)
        assertFalse(live.studentForwardSpeed)
        assertFalse(live.coastStopDetect)
        assertFalse(live.coastSpeedDecay)
        assertFalse(live.gnssReseedWhileFused)
        assertFalse(live.gnssReseedRequireSparseSpacing)

        val g = Wgs84.gravityMps2(0.0)
        val dtNs = 100_000_000L
        val lateNs = 22_000_000_000L
        fun afterLateFix(config: InsConfig): Pair<DeadReckoningFilter, Pair<Double, Double>> {
            val filter = DeadReckoningFilter(
                config.copy(
                    nhcMinSpeedMps = 100.0,
                    lowConfidenceRadiusM = 10_000.0,
                    gnssGateInflate = false,
                ),
            )
            filter.seedForTest(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                velocityEnu = Vec3(0.0, 10.0, 0.0),
                quat = Quat.IDENTITY,
                posStdM = 5.0,
                frame = VectorFrame.UNSPECIFIED,
                headingRad = 0.0,
            )
            var lastLat = 0.0
            var lastLon = 0.0
            for (sec in 1..20) {
                for (i in 1..10) {
                    val t = (sec - 1) * 1_000_000_000L + i * dtNs
                    stepImu(filter, Nanoseconds(t), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
                }
                val (lat, lon) = Wgs84.offsetMetres(0.0, 0.0, sec * 10.0, 0.0)
                lastLat = lat
                lastLon = lon
                filter.ingestGnss(
                    CoastFix(
                        timestamp = Nanoseconds(sec * 1_000_000_000L),
                        latitudeDeg = lat,
                        longitudeDeg = lon,
                        speedMps = 10.0,
                        headingRad = 0.0,
                        horizontalAccuracyM = 4.0,
                    ),
                )
            }
            for (i in 1..20) {
                stepImu(
                    filter,
                    Nanoseconds(20_000_000_000L + i * dtNs),
                    0.0,
                    0.0,
                    g,
                    frame = VectorFrame.UNSPECIFIED,
                )
            }
            val late = Wgs84.offsetMetres(lastLat, lastLon, 0.0, 20.0)
            filter.ingestGnss(
                CoastFix(
                    timestamp = Nanoseconds(lateNs),
                    latitudeDeg = late.first,
                    longitudeDeg = late.second,
                    speedMps = 10.0,
                    headingRad = 0.0,
                    horizontalAccuracyM = 4.0,
                ),
            )
            return filter to late
        }

        val (liveFilter, latePos) = afterLateFix(live)
        assertTrue(
            "live defaults must not reseed a 2 s late 20 m hop, admit=${liveFilter.lastGnssAdmitForTest()}",
            liveFilter.lastGnssAdmitForTest() != DeadReckoningFilter.GNSS_RESEED_AFTER_GAP,
        )
        val livePose = liveFilter.poseAt(Nanoseconds(lateNs))!!
        assertFalse(livePose.health.flags.contains(DeadReckoningFilter.FLAG_GNSS_RESEED))
        val liveDist = Wgs84.distanceMetres(
            livePose.position.latitude.value,
            livePose.position.longitude.value,
            latePos.first,
            latePos.second,
        )
        assertTrue("must not teleport onto the late fix, dist=$liveDist", liveDist > 5.0)

        val (reseedFilter, reseedLate) = afterLateFix(
            live.copy(gnssReseedAfterS = 2.0, gnssReseedWhileFused = true),
        )
        assertEquals(
            DeadReckoningFilter.GNSS_RESEED_AFTER_GAP,
            reseedFilter.lastGnssAdmitForTest(),
        )
        val reseedPose = reseedFilter.poseAt(Nanoseconds(lateNs))!!
        val reseedDist = Wgs84.distanceMetres(
            reseedPose.position.latitude.value,
            reseedPose.position.longitude.value,
            reseedLate.first,
            reseedLate.second,
        )
        assertTrue("reseed-on must snap onto the late fix, dist=$reseedDist", reseedDist < 5.0)
    }

    @Test
    fun honestCoastPAdmitsDistantFixWithoutReseed() {
        val g = Wgs84.gravityMps2(0.0)
        fun coastOnly(honest: Boolean): DeadReckoningFilter {
            val filter = DeadReckoningFilter(
                InsConfig(
                    coastMode = CoastMode.YAW_SPEED_HOLD,
                    gnssReseedAfterS = 0.0,
                    coastHonestP = honest,
                    gnssGateInflate = false,
                    nhcMinSpeedMps = 100.0,
                    lowConfidenceRadiusM = 10_000.0,
                ),
            )
            filter.seedForTest(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                velocityEnu = Vec3(0.0, 15.0, 0.0),
                quat = Quat.IDENTITY,
                posStdM = 5.0,
                frame = VectorFrame.UNSPECIFIED,
                headingRad = 0.0,
            )
            val dtNs = 100_000_000L
            for (i in 1..90) {
                stepImu(filter, Nanoseconds(i * dtNs), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
            }
            return filter
        }
        fun ingestEast(filter: DeadReckoningFilter, eastM: Double) {
            val (lat, lon) = Wgs84.offsetMetres(0.0, 0.0, 0.0, eastM)
            filter.ingestGnss(
                CoastFix(
                    timestamp = Nanoseconds(9_000_000_000L),
                    latitudeDeg = lat,
                    longitudeDeg = lon,
                    speedMps = 8.0,
                    headingRad = PI / 2.0,
                    horizontalAccuracyM = 5.0,
                ),
            )
        }
        val tightP = sqrt(coastOnly(honest = false).horizontalVariance())
        val honestCoast = coastOnly(honest = true)
        val honestPH = sqrt(honestCoast.horizontalVariance())
        val gate = 6.0 * (5.0 + honestPH)
        assertTrue(
            "honest P must outgrow the v5 halo, honest=$honestPH tight=$tightP",
            honestPH > tightP + 5.0,
        )
        assertTrue("honest P gate must cover a few hundred metres, gate=$gate pH=$honestPH", gate > 200.0)
        ingestEast(honestCoast, 150.0)
        assertEquals(DeadReckoningFilter.GNSS_GATE_ADMIT, honestCoast.lastGnssAdmitForTest())
        assertTrue(honestCoast.lastWouldAdmitWithoutReseedForTest())
    }

    @Test
    fun studentForwardSpeedChi2RejectsWildAid() {
        val g = Wgs84.gravityMps2(0.0)
        fun held(): DeadReckoningFilter {
            val filter = DeadReckoningFilter(
                InsConfig(
                    coastMode = CoastMode.YAW_SPEED_HOLD,
                    studentForwardSpeed = true,
                    nhcMinSpeedMps = 100.0,
                    lowConfidenceRadiusM = 10_000.0,
                ),
            )
            filter.seedForTest(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                velocityEnu = Vec3(10.0, 0.0, 0.0),
                quat = Quat.IDENTITY,
                posStdM = 5.0,
                frame = VectorFrame.VEHICLE_FLU,
                headingRad = PI / 2.0,
            )
            filter.setGnssHeld(true)
            stepImu(filter, Nanoseconds(100_000_000L), 0.0, 0.0, g)
            return filter
        }
        val wild = held()
        wild.ingestMotionPseudo(
            MotionPseudoMeasurement(
                forwardSpeed = MetresPerSecond(30.0),
                yawRateRadps = 0.0,
                stopProbability = 0.0,
                logSpeedVariance = kotlin.math.ln(4.0),
            ),
            Nanoseconds(200_000_000L),
        )
        assertEquals("30 m/s student must reject", 10.0, wild.heldSpeedForTest(), 0.5)
        val mild = held()
        mild.ingestMotionPseudo(
            MotionPseudoMeasurement(
                forwardSpeed = MetresPerSecond(11.0),
                yawRateRadps = 0.0,
                stopProbability = 0.0,
                logSpeedVariance = kotlin.math.ln(4.0),
            ),
            Nanoseconds(200_000_000L),
        )
        assertTrue("11 m/s student should accept, speed=${mild.heldSpeedForTest()}", mild.heldSpeedForTest() > 10.2)
        val off = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                studentForwardSpeed = false,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        off.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(10.0, 0.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
            headingRad = PI / 2.0,
        )
        off.setGnssHeld(true)
        stepImu(off, Nanoseconds(100_000_000L), 0.0, 0.0, g)
        off.ingestMotionPseudo(
            MotionPseudoMeasurement(
                forwardSpeed = MetresPerSecond(30.0),
                yawRateRadps = 0.0,
                stopProbability = 0.0,
                logSpeedVariance = kotlin.math.ln(4.0),
            ),
            Nanoseconds(200_000_000L),
        )
        assertEquals("student default off must ignore 30 m/s", 10.0, off.heldSpeedForTest(), 0.5)
    }

    @Test
    fun coastSpeedDecayMovesTowardPremaskMean() {
        val g = Wgs84.gravityMps2(0.0)
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                coastSpeedDecay = true,
                coastSpeedDecayTauS = 1.0,
                coastSpeedDecayTargetMps = 0.0,
                nhcMinSpeedMps = 100.0,
                lowConfidenceRadiusM = 10_000.0,
            ),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(10.0, 0.0, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.UNSPECIFIED,
            headingRad = PI / 2.0,
        )
        filter.setGnssHeld(true)
        val dtNs = 100_000_000L
        for (i in 1..20) {
            stepImu(filter, Nanoseconds(i * dtNs), 0.0, 0.0, g, frame = VectorFrame.UNSPECIFIED)
        }
        val speed = filter.heldSpeedForTest()
        assertTrue("tau=1 s over 2 s should drop ~10 toward 0, speed=$speed", speed < 2.0 && speed > 0.5)
    }

    private fun minHeadingDelta(a: Double, b: Double): Double {
        val d = wrapHeadingRad(a) - wrapHeadingRad(b)
        return abs(if (d > PI) d - 2.0 * PI else if (d < -PI) d + 2.0 * PI else d)
    }

    private fun fix(
        timestampNs: Long,
        latitudeDeg: Double = 0.0,
        accuracyM: Double = 4.0,
    ): CoastFix = CoastFix(
        timestamp = Nanoseconds(timestampNs),
        latitudeDeg = latitudeDeg,
        longitudeDeg = 0.0,
        speedMps = 0.0,
        headingRad = 0.0,
        horizontalAccuracyM = accuracyM,
    )

    private fun stepImu(
        filter: DeadReckoningFilter,
        t: Nanoseconds,
        ax: Double,
        ay: Double,
        az: Double,
        gx: Double = 0.0,
        gy: Double = 0.0,
        gz: Double = 0.0,
        frame: VectorFrame = VectorFrame.VEHICLE_FLU,
    ) {
        filter.ingestGyro(t, gx, gy, gz, frame)
        filter.ingestAccel(t, ax, ay, az, frame)
    }
}
