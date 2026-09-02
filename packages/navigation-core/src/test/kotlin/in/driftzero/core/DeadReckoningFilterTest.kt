package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

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
    fun gapBetweenFixesWithoutPoseQueryStillCountsAsCoast() {
        val filter = DeadReckoningFilter()
        filter.ingestGnss(fix(0L))
        filter.ingestGnss(fix(4_000_000_000L))
        assertEquals(NavigationMode.REACQUIRING, filter.poseAt(Nanoseconds(4_000_000_000L))!!.mode)
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
    ) {
        filter.ingestGyro(t, 0.0, 0.0, 0.0, VectorFrame.VEHICLE_FLU)
        filter.ingestAccel(t, ax, ay, az, VectorFrame.VEHICLE_FLU)
    }
}
