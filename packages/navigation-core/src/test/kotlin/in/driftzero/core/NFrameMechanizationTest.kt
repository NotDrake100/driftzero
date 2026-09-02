package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Constant-acceleration special case of Groves (5.54)+(5.56) with ω_ie=0:
 * a = 1 m/s² east for 2 s from rest gives v = a t = 2 m/s and
 * p = ½ a t² = 2 m. Same kinematics Titterton §3.5.3 uses in n-frame.
 */
class NFrameMechanizationTest {
    @Test
    fun eastAccelOneMps2ForTwoSecondsFromRest() {
        val lat = 0.0
        val alt = 0.0
        val g = NFrameMechanization.gravityEnu(lat, alt)
        var state = NFrameState(Quat.IDENTITY, Vec3.ZERO, Vec3.ZERO)
        val dt = 0.01
        val steps = 200
        val fBody = Vec3(1.0, 0.0, -g.z)
        for (i in 0 until steps) {
            state = NFrameMechanization.step(state, Vec3.ZERO, fBody, dt, lat, alt)
        }
        assertEquals("east velocity Groves (5.54) v=at", 2.0, state.velocityEnu.x, 0.02)
        assertEquals("north velocity", 0.0, state.velocityEnu.y, 0.02)
        assertEquals("up velocity", 0.0, state.velocityEnu.z, 0.02)
        assertEquals("east position Groves (5.56) p=½at²", 2.0, state.positionEnu.x, 0.02)
        assertEquals("north position", 0.0, state.positionEnu.y, 0.02)
        assertEquals("up position", 0.0, state.positionEnu.z, 0.02)
    }

    @Test
    fun gravityOnlySpecificForceGivesZeroNavAccel() {
        val lat = 51.5
        val alt = 80.0
        val g = NFrameMechanization.gravityEnu(lat, alt)
        val a = NFrameMechanization.navAccelFromSpecificForce(-g, lat, alt)
        assertEquals(0.0, a.x, 1e-12)
        assertEquals(0.0, a.y, 1e-12)
        assertEquals(0.0, a.z, 1e-12)
    }

    @Test
    fun earthRateCoriolisIsBelowPhoneAccelNoise() {
        val v = Vec3(20.0, 0.0, 0.0)
        val a = Wgs84.coriolisAccelEnu(45.0, v).norm()
        assertTrue("2ω×v at 20 m/s, 45 deg: $a", a < 0.005)
        assertTrue(a < InsConfig().accelNoise)
        val w = Wgs84.earthRateEnuRadps(0.0)
        assertEquals(0.0, w.x, 0.0)
        assertEquals(Wgs84.OMEGA_IE_RADPS, w.y, 1e-12)
        assertEquals(0.0, w.z, 1e-12)
    }

    @Test
    fun groves584AverageCMatchesQuarterTurnClosedForm() {
        val omega = Vec3(0.0, 0.0, PI / 2.0)
        val fBody = Vec3(2.0, 0.0, 0.0)
        val adv = NFrameMechanization.advance(
            NFrameState(Quat.IDENTITY, Vec3.ZERO, Vec3.ZERO),
            omega,
            fBody,
            1.0,
            0.0,
            0.0,
        )
        val mean = 4.0 / PI
        assertEquals("Groves (5.84) mean east", mean, adv.specificForceNav.x, 0.02)
        assertEquals("Groves (5.84) mean north", mean, adv.specificForceNav.y, 0.02)
        assertEquals(0.0, adv.specificForceNav.z, 0.02)
    }

    @Test
    fun gravityGradientIsTwoGOverA() {
        val g = Wgs84.gravityMps2(0.0, 0.0)
        val grad = NFrameMechanization.gravityGradientUpPerMetre(0.0, 0.0)
        assertEquals(2.0 * g / Wgs84.A_M, grad, 1e-18)
    }
}
