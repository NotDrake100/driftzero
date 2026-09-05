package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackoutRiskTest {
    @Test
    fun missingCn0AndMapAreUnavailableNotZero() {
        val assessment = BlackoutRisk.evaluate(
            BlackoutInputs(
                usedSats = 6,
                meanUsedCn0DbHz = null,
                horizontalAccuracyM = 12.0,
                mapPresent = false,
            ),
        )
        assertTrue(assessment.cn0Factor is OptionalScalar.Unavailable)
        assertTrue(assessment.tunnelFactor is OptionalScalar.Unavailable)
        assertTrue(assessment.risk is OptionalScalar.Available)
        assertEquals(2, assessment.usedFactorCount)
        assertNull(BlackoutRisk.tunnelLine(assessment))
    }

    @Test
    fun noFactorsYieldsUnavailableRisk() {
        val assessment = BlackoutRisk.evaluate(BlackoutInputs())
        assertTrue(assessment.risk is OptionalScalar.Unavailable)
        assertFalse(assessment.preconditioning)
        assertNull(BlackoutRisk.riskLine(assessment))
    }

    @Test
    fun usedSatsAndCn0Piecewise() {
        assertEquals(1.0, (BlackoutRisk.usedSatsFactor(3) as OptionalScalar.Available).value, 1e-12)
        assertEquals(0.0, (BlackoutRisk.usedSatsFactor(8) as OptionalScalar.Available).value, 1e-12)
        assertEquals(0.5, (BlackoutRisk.usedSatsFactor(6) as OptionalScalar.Available).value, 1e-12)
        assertEquals(1.0, (BlackoutRisk.cn0Factor(15.0) as OptionalScalar.Available).value, 1e-12)
        assertEquals(0.0, (BlackoutRisk.cn0Factor(40.0) as OptionalScalar.Available).value, 1e-12)
        assertEquals(0.5, (BlackoutRisk.cn0Factor(27.5) as OptionalScalar.Available).value, 1e-12)
    }

    @Test
    fun tunnelNearIsOneFarIsPointSeven() {
        val near = BlackoutRisk.tunnelFactor(40.0, mapPresent = true) as OptionalScalar.Available
        assertEquals(1.0, near.value, 1e-12)
        val far = BlackoutRisk.tunnelFactor(280.0, mapPresent = true) as OptionalScalar.Available
        assertEquals(0.70, far.value, 1e-9)
        val none = BlackoutRisk.tunnelFactor(null, mapPresent = true) as OptionalScalar.Available
        assertEquals(0.0, none.value, 1e-12)
    }

    @Test
    fun walkFindsTunnelAlongHeading() {
        val graph = RoadFixtures.roadThenTunnel(approachM = 200.0)
        val atStart = BlackoutRisk.tunnelAheadM(graph, "approach", alongM = 0.0, headingRad = 0.0)
        val d = atStart as OptionalScalar.Available
        assertEquals(200.0, d.value, 2.0)
        val mid = BlackoutRisk.tunnelAheadM(graph, "approach", alongM = 150.0, headingRad = 0.0)
        assertEquals(50.0, (mid as OptionalScalar.Available).value, 2.0)
        val onBore = BlackoutRisk.tunnelAheadM(graph, "bore", alongM = 10.0, headingRad = 0.0)
        assertEquals(0.0, (onBore as OptionalScalar.Available).value, 1e-9)
        val noMap = BlackoutRisk.tunnelAheadM(null, "approach", 0.0, 0.0)
        assertTrue(noMap is OptionalScalar.Unavailable)
    }

    @Test
    fun highRiskArmsPreconditioningAndCopy() {
        val assessment = BlackoutRisk.evaluate(
            BlackoutInputs(
                usedSats = 3,
                meanUsedCn0DbHz = 18.0,
                horizontalAccuracyM = 40.0,
                tunnelAheadM = 40.0,
                shadowOccupancy = 0.8,
                mapPresent = true,
            ),
        )
        val risk = assessment.risk as OptionalScalar.Available
        assertTrue(risk.value >= 0.55)
        assertTrue(assessment.preconditioning)
        assertEquals("GNSS blackout ${DriftBudgetMath.percentWhole(risk.value)}", BlackoutRisk.riskLine(assessment))
        assertEquals("Tunnel 40 m ahead", BlackoutRisk.tunnelLine(assessment))
        assertEquals("Dead-reckoning preconditioning active", BlackoutRisk.PRECONDITION_COPY)
    }

    @Test
    fun healthyGnssPlusNearTunnelStillArms() {
        val assessment = BlackoutRisk.evaluate(
            BlackoutInputs(
                usedSats = 8,
                meanUsedCn0DbHz = 36.0,
                horizontalAccuracyM = 6.0,
                tunnelAheadM = 40.0,
                mapPresent = true,
            ),
        )
        val risk = assessment.risk as OptionalScalar.Available
        assertTrue(risk.value < BlackoutRisk.PRECONDITION_RISK)
        assertTrue(assessment.preconditioning)
        val tunnel = assessment.tunnelFactor as OptionalScalar.Available
        assertEquals(1.0, tunnel.value, 1e-12)
    }

    @Test
    fun healthyGnssPlusHighShadowStillArms() {
        val assessment = BlackoutRisk.evaluate(
            BlackoutInputs(
                usedSats = 8,
                meanUsedCn0DbHz = 36.0,
                horizontalAccuracyM = 6.0,
                shadowOccupancy = 0.80,
                mapPresent = false,
            ),
        )
        val risk = assessment.risk as OptionalScalar.Available
        assertTrue(risk.value < BlackoutRisk.PRECONDITION_RISK)
        assertTrue(assessment.preconditioning)
    }

    @Test
    fun healthyGnssWithoutMapEvidenceDoesNotArm() {
        val assessment = BlackoutRisk.evaluate(
            BlackoutInputs(
                usedSats = 8,
                meanUsedCn0DbHz = 36.0,
                horizontalAccuracyM = 6.0,
                mapPresent = true,
            ),
        )
        val risk = assessment.risk as OptionalScalar.Available
        assertTrue(risk.value < BlackoutRisk.PRECONDITION_RISK)
        assertFalse(assessment.preconditioning)
    }

    @Test
    fun tunnelBeyondFadeDoesNotArmWhenGnssIsHealthy() {
        val assessment = BlackoutRisk.evaluate(
            BlackoutInputs(
                usedSats = 8,
                meanUsedCn0DbHz = 36.0,
                horizontalAccuracyM = 6.0,
                tunnelAheadM = 450.0,
                mapPresent = true,
            ),
        )
        assertFalse(assessment.preconditioning)
    }
}
