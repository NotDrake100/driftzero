package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class DisplacementPseudoTest {
    @Test
    fun displacementUpdateMovesPositionInMeasuredDirection() {
        val filter = DeadReckoningFilter()
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3.ZERO,
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
        )
        val before = filter.horizontalVariance()
        val beforePos = filter.positionEnu()
        filter.ingestDisplacementPseudo(
            DisplacementPseudoMeasurement(
                dxM = 8.0,
                dyM = 0.0,
                dzM = 0.0,
                logSigmaX = ln(0.4),
                logSigmaY = ln(0.4),
                logSigmaZ = ln(0.4),
                windowStart = Nanoseconds(0L),
            ),
            Nanoseconds(20_000_000L),
        )
        val after = filter.positionEnu()
        val afterVar = filter.horizontalVariance()
        assertTrue("east should increase, e=${after.x}", after.x > beforePos.x + 4.0)
        assertTrue("north should stay small, n=${after.y}", kotlin.math.abs(after.y) < 1.0)
        assertTrue("accepted update must not grow P, before=$before after=$afterVar", afterVar < before)
        val pose = filter.poseAt(Nanoseconds(20_000_000L))
        assertNotNull(pose)
        assertTrue(pose!!.health.modelOk)
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_DISPLACEMENT_PSEUDO))
        assertFalse(pose.health.flags.contains(DeadReckoningFilter.FLAG_DISPLACEMENT_GATED))
    }

    @Test
    fun gatedDisplacementInflatesPAndDoesNotJump() {
        val filter = DeadReckoningFilter()
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3.ZERO,
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
        )
        val before = filter.horizontalVariance()
        filter.ingestDisplacementPseudo(
            DisplacementPseudoMeasurement(
                dxM = 400.0,
                dyM = 0.0,
                dzM = 0.0,
                logSigmaX = ln(0.05),
                logSigmaY = ln(0.05),
                logSigmaZ = ln(0.05),
                windowStart = Nanoseconds(0L),
            ),
            Nanoseconds(20_000_000L),
        )
        val after = filter.positionEnu()
        val afterVar = filter.horizontalVariance()
        assertTrue("gated Δp must not yank east, e=${after.x}", kotlin.math.abs(after.x) < 1.0)
        assertTrue("P grows only when gated, before=$before after=$afterVar", afterVar > before)
        val pose = filter.poseAt(Nanoseconds(20_000_000L))
        assertTrue(pose!!.health.flags.contains(DeadReckoningFilter.FLAG_DISPLACEMENT_GATED))
        assertTrue(pose.health.flags.contains(DeadReckoningFilter.FLAG_DISPLACEMENT_PSEUDO))
    }

    @Test
    fun twoDimensionalUpdateMovesInPlaneOnly() {
        val filter = DeadReckoningFilter()
        filter.seedForTest(
            timestamp = Nanoseconds(0L),
            latitudeDeg = 0.0,
            longitudeDeg = 0.0,
            velocityEnu = Vec3.ZERO,
            quat = Quat.IDENTITY,
            posStdM = 5.0,
            frame = VectorFrame.VEHICLE_FLU,
        )
        filter.ingestDisplacementPseudo(
            DisplacementPseudoMeasurement(
                dxM = 0.0,
                dyM = 6.0,
                dzM = 80.0,
                logSigmaX = ln(0.3),
                logSigmaY = ln(0.3),
                logSigmaZ = ln(0.3),
                windowStart = Nanoseconds(0L),
                twoDimensional = true,
            ),
            Nanoseconds(20_000_000L),
        )
        val p = filter.positionEnu()
        assertTrue("north HACF y, n=${p.y}", p.y > 3.0)
        assertTrue("vertical ignored, u=${p.z}", kotlin.math.abs(p.z) < 1.0)
    }
}
