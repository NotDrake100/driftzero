package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GnssTrustTest {
    @Test
    fun headingZeroIsNorthAndEastIsCross() {
        val proj = GnssTrust.projectResidual(eastM = 180.0, northM = 0.0, ve = 0.0, vn = 11.67)
        assertEquals(0.0, proj.headingRad, 1e-9)
        assertEquals(0.0, proj.alongTrackM, 1e-6)
        assertEquals(180.0, proj.crossTrackM, 1e-6)
    }

    @Test
    fun sidewaysOneEightyAtFortyTwoKmhQuarantines() {
        val speed = 42.0 / 3.6
        val engine = GnssTrustEngine(reacquireFixes = 3)
        val decision = engine.evaluate(
            residualEastM = 180.0,
            residualNorthM = 0.0,
            ve = 0.0,
            vn = speed,
            dtS = 1.0,
            pHorizM = 40.0,
            sigmaM = 5.0,
        )
        assertEquals(GnssTrustAction.QUARANTINE, decision.action)
        assertTrue(engine.isQuarantined())
        assertEquals(GnssTrust.GNSS_ANOMALY_COPY, GnssTrust.GNSS_ANOMALY_COPY)
    }

    @Test
    fun threeConsistentFixesRelease() {
        val speed = 42.0 / 3.6
        val engine = GnssTrustEngine(3)
        engine.evaluate(180.0, 0.0, 0.0, speed, 1.0, 40.0, 5.0)
        assertTrue(engine.isQuarantined())
        val first = engine.evaluate(2.0, 1.0, 0.0, speed, 1.0, 10.0, 4.0)
        assertEquals(GnssTrustAction.HOLD, first.action)
        val second = engine.evaluate(1.0, 0.5, 0.0, speed, 1.0, 10.0, 4.0)
        assertEquals(GnssTrustAction.HOLD, second.action)
        val third = engine.evaluate(1.0, 0.4, 0.0, speed, 1.0, 10.0, 4.0)
        assertEquals(GnssTrustAction.APPLY, third.action)
        assertFalse(engine.isQuarantined())
        assertTrue(engine.justReleased())
        assertEquals(GnssTrust.GNSS_RESTORED_COPY, GnssTrust.GNSS_RESTORED_COPY)
    }

    @Test
    fun slowVehicleSkipsCrossTrackQuarantine() {
        val engine = GnssTrustEngine()
        val decision = engine.evaluate(80.0, 0.0, 0.0, 0.4, 1.0, 5.0, 4.0)
        assertEquals(GnssTrustAction.APPLY, decision.action)
    }
}

class GnssQuarantineFilterTest {
    @Test
    fun sidewaysJumpIsRejectedThenRestored() {
        val speed = 42.0 / 3.6
        val filter = DeadReckoningFilter(InsConfig(gnssQuarantine = true))
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, speed, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 40.0,
            headingRad = 0.0,
        )
        val before = filter.positionEnu()
        val jumped = Wgs84.offsetMetres(0.0, 0.0, 0.0, 180.0)
        // Same epoch as the seed so predictTo does not walk along-track.
        // The 180 m east residual is the thing under test.
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = jumped.first,
                longitudeDeg = jumped.second,
                speedMps = speed,
                headingRad = 0.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        val mid = filter.poseAt(Nanoseconds(0L))!!
        assertTrue(mid.health.flags.contains(DeadReckoningFilter.FLAG_GNSS_QUARANTINE))
        assertTrue(mid.gnssHealth.riskFlags.contains(DeadReckoningFilter.RISK_GNSS_QUARANTINE))
        val afterJump = filter.positionEnu()
        assertEquals(before.x, afterJump.x, 1.0)
        assertEquals(before.y, afterJump.y, 1.0)
        repeat(3) { i ->
            filter.ingestGnss(
                CoastFix(
                    timestamp = Nanoseconds((2L + i) * 1_000_000_000L),
                    latitudeDeg = 0.0,
                    longitudeDeg = 0.0,
                    speedMps = speed,
                    headingRad = 0.0,
                    horizontalAccuracyM = 4.0,
                ),
            )
        }
        val restored = filter.poseAt(Nanoseconds(4_000_000_000L))!!
        assertFalse(restored.health.flags.contains(DeadReckoningFilter.FLAG_GNSS_QUARANTINE))
        assertTrue(filter.gnssTrustJustReleased() || !filter.gnssQuarantined())
        assertTrue(abs(restored.position.latitude.value) < 0.002)
        assertTrue(abs(restored.position.longitude.value) < 0.002)
    }

    @Test
    fun liveShapedConfigRejectsOneEightyMetreSidewaysJump() {
        val speed = 42.0 / 3.6
        val filter = DeadReckoningFilter(
            InsConfig(
                coastMode = CoastMode.YAW_SPEED_HOLD,
                staleAfterS = 8.0,
                coastHonestP = true,
                studentForwardSpeed = true,
                coastLatchGnssSpeed = true,
                gnssQuarantine = true,
            ),
        )
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, speed, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 40.0,
            headingRad = 0.0,
        )
        val before = filter.positionEnu()
        val jumped = Wgs84.offsetMetres(0.0, 0.0, 0.0, 180.0)
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = jumped.first,
                longitudeDeg = jumped.second,
                speedMps = speed,
                headingRad = 0.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        val mid = filter.poseAt(Nanoseconds(0L))!!
        assertTrue(mid.health.flags.contains(DeadReckoningFilter.FLAG_GNSS_QUARANTINE))
        val afterJump = filter.positionEnu()
        assertEquals(before.x, afterJump.x, 1.0)
        assertEquals(before.y, afterJump.y, 1.0)
    }

    @Test
    fun replayDefaultDoesNotQuarantineSidewaysJump() {
        val speed = 42.0 / 3.6
        val filter = DeadReckoningFilter()
        assertFalse(InsConfig().gnssQuarantine)
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3(0.0, speed, 0.0),
            quat = Quat.IDENTITY,
            posStdM = 40.0,
            headingRad = 0.0,
        )
        val jumped = Wgs84.offsetMetres(0.0, 0.0, 0.0, 180.0)
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = jumped.first,
                longitudeDeg = jumped.second,
                speedMps = speed,
                headingRad = 0.0,
                horizontalAccuracyM = 5.0,
            ),
        )
        val mid = filter.poseAt(Nanoseconds(0L))!!
        assertFalse(mid.health.flags.contains(DeadReckoningFilter.FLAG_GNSS_QUARANTINE))
        assertFalse(filter.gnssQuarantined())
    }

    @Test
    fun geometricGateStillRejectsKilometreJump() {
        val filter = DeadReckoningFilter()
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(0L),
                latitudeDeg = 0.0,
                longitudeDeg = 0.0,
                speedMps = 0.0,
                headingRad = 0.0,
                horizontalAccuracyM = 4.0,
            ),
        )
        filter.ingestGnss(
            CoastFix(
                timestamp = Nanoseconds(500_000_000L),
                latitudeDeg = 0.01,
                longitudeDeg = 0.0,
                speedMps = 0.0,
                headingRad = 0.0,
                horizontalAccuracyM = 4.0,
            ),
        )
        val gated = filter.poseAt(Nanoseconds(600_000_000L))!!
        assertTrue(gated.gnssHealth.riskFlags.contains(DeadReckoningFilter.RISK_GATED_FIX))
        assertFalse(gated.health.flags.contains(DeadReckoningFilter.FLAG_GNSS_QUARANTINE))
    }
}
