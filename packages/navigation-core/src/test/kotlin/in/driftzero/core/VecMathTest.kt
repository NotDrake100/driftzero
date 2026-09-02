package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class VecMathTest {
    @Test
    fun quaternionRotatesBodyXToNavWhenIdentity() {
        val v = Quat.IDENTITY.rotate(Vec3.EX)
        assertEquals(1.0, v.x, 1e-12)
        assertEquals(0.0, v.y, 1e-12)
        assertEquals(0.0, v.z, 1e-12)
    }

    @Test
    fun triadEastHeadingMapsVehicleForwardToEast() {
        val g = Wgs84.gravityMps2(0.0)
        val q = attitudeFromGravityAndHeading(
            specificForceBody = Vec3(0.0, 0.0, g),
            headingRad = PI / 2.0,
            frame = VectorFrame.VEHICLE_FLU,
        )
        assertNotNull(q)
        val fwd = q!!.rotate(Vec3.EX)
        assertEquals(1.0, fwd.x, 1e-6)
        assertEquals(0.0, fwd.y, 1e-6)
        assertTrue(abs(fwd.z) < 1e-6)
    }

    @Test
    fun invert3RecoversIdentity() {
        val inv = invert3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        assertNotNull(inv)
        assertEquals(1.0, inv!![0], 1e-12)
        assertEquals(1.0, inv[4], 1e-12)
        assertEquals(1.0, inv[8], 1e-12)
    }

    @Test
    fun josephUpdateReducesPositionVariance() {
        val p = Square(EskfDim.N)
        p.setIdentity()
        for (i in 0..2) {
            p[i, i] = 100.0
        }
        val h = DoubleArray(EskfDim.N)
        h[0] = 1.0
        val residual = doubleArrayOf(0.0)
        val r = doubleArrayOf(4.0)
        val dx = DoubleArray(EskfDim.N)
        val ok = josephUpdate(p, h, residual, r, 1, dx, JosephScratch())
        assertEquals(true, ok)
        assertTrue(p[0, 0] < 5.0)
        assertEquals(1.0, p[4, 4], 1e-12)
    }

    @Test
    fun strapdownPhiHasGrovesGravityGradient() {
        val phi = Square(EskfDim.N)
        val dt = 0.1
        val grad = NFrameMechanization.gravityGradientUpPerMetre(0.0, 0.0)
        fillStrapdownPhi(phi, dt, Vec3(1.0, 0.0, 9.8), Mat3.IDENTITY, grad)
        assertEquals(dt, phi[EskfDim.IP, EskfDim.IV], 0.0)
        assertEquals(grad * dt, phi[EskfDim.IV + 2, EskfDim.IP + 2], 1e-18)
        assertEquals(0.0, phi[EskfDim.IV, EskfDim.IP], 0.0)
        assertEquals(dt, phi[EskfDim.IV + 2, EskfDim.ITH + 1], 1e-12)
        assertEquals(-dt, phi[EskfDim.IV + 1, EskfDim.ITH + 2], 1e-12)
    }

    @Test
    fun imuProcessNoiseAddsPositionVelocityCross() {
        val p = Square(EskfDim.N)
        addImuProcessNoise(p, 0.1, 0.2, 0.01, 0.001, 1e-5)
        val sa = 0.2 * 0.2
        assertEquals(sa * 0.1, p[EskfDim.IV, EskfDim.IV], 1e-12)
        assertEquals(sa * 0.1 * 0.1 * 0.1 / 3.0, p[EskfDim.IP, EskfDim.IP], 1e-12)
        assertEquals(0.5 * sa * 0.1 * 0.1, p[EskfDim.IP, EskfDim.IV], 1e-12)
        assertEquals(p[EskfDim.IP, EskfDim.IV], p[EskfDim.IV, EskfDim.IP], 0.0)
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
