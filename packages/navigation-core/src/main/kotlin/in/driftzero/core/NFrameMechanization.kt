package `in`.driftzero.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Local-tangent n-frame strapdown. Groves 2nd ed. §5.4 writes NED. This code
 * uses ENU: (E, N, U) = (NED_E, NED_N, −NED_D). Titterton & Weston 2nd ed.
 * §3.5.3 is the same local-geographic mechanization.
 *
 * Earth rate and transport rate are omitted. Phone-scale bound is in
 * [Wgs84.coriolisAccelEnu] and docs/refs/INS_ESKF.md.
 */
internal data class NFrameState(
    val attitude: Quat,
    val velocityEnu: Vec3,
    val positionEnu: Vec3,
)

internal data class NFrameAdvance(
    val state: NFrameState,
    val specificForceNav: Vec3,
    val navAccel: Vec3,
)

internal object NFrameMechanization {
    /**
     * Gravity resolved in ENU. Down magnitude is Groves (2.134)+(2.139).
     * North leak is Groves (2.140). East is zero on the ellipsoid of revolution.
     */
    fun gravityEnu(latitudeDeg: Double, altitudeM: Double): Vec3 {
        val down = Wgs84.gravityMps2(latitudeDeg, altitudeM)
        val lat = latitudeDeg * PI / 180.0
        val north = -Wgs84.NORTH_GRAVITY_PER_M * altitudeM * sin(2.0 * lat)
        return Vec3(0.0, north, -down)
    }

    /**
     * a^n = f^n + g^n. Groves discrete velocity (5.54) with ω_ie = ω_en = 0.
     * Solà (233)/(237b): a = R(a_m − a_b) + g in a local tangent.
     */
    fun navAccelFromSpecificForce(
        specificForceNav: Vec3,
        latitudeDeg: Double,
        altitudeM: Double,
    ): Vec3 = specificForceNav + gravityEnu(latitudeDeg, altitudeM)

    /**
     * Body-rate quaternion step. Solà (144) q̇ = ½ q ⊗ ω, discrete (157)/(260c).
     * Titterton §3.6.4 and §11.2.5. Rodrigues closed form when |ω|Δt is finite.
     */
    fun integrateAttitude(q: Quat, omegaBodyRadps: Vec3, dtS: Double): Quat {
        val omegaDt = omegaBodyRadps * dtS
        val theta = omegaDt.norm()
        val dq = if (theta < 1e-9) {
            Quat(1.0, 0.5 * omegaDt.x, 0.5 * omegaDt.y, 0.5 * omegaDt.z)
        } else {
            val half = 0.5 * theta
            val scale = sin(half) / theta
            Quat(cos(half), omegaDt.x * scale, omegaDt.y * scale, omegaDt.z * scale)
        }
        return q.times(dq).normalized()
    }

    /**
     * Groves (5.84) average C_b^n over the interval, Earth-rate term omitted.
     * Titterton §11.3.1 is the same first-order specific-force transform.
     */
    fun averageBodyToNav(qOld: Quat, omegaBodyRadps: Vec3, dtS: Double): Mat3 {
        val oldC = qOld.toRotation()
        val alpha = omegaBodyRadps * dtS
        val mag = alpha.norm()
        if (mag < 1e-8) {
            return oldC
        }
        val skew = Mat3.skew(alpha)
        val c1 = (1.0 - cos(mag)) / (mag * mag)
        val c2 = (1.0 - sin(mag) / mag) / (mag * mag)
        return oldC * (Mat3.IDENTITY + skew * c1 + (skew * skew) * c2)
    }

    /** Linearized Groves (2.139): ∂g_U/∂h ≈ +2γ / a. Used in Φ_{v_U, p_U}. */
    fun gravityGradientUpPerMetre(latitudeDeg: Double, altitudeM: Double): Double {
        return 2.0 * Wgs84.gravityMps2(latitudeDeg, altitudeM) / Wgs84.A_M
    }

    /**
     * One IMU interval. Specific force uses Groves (5.84). Velocity is (5.54)
     * without Earth-rate. Position is the trapezoidal (5.56) tangent-plane case.
     */
    fun advance(
        state: NFrameState,
        gyroBodyRadps: Vec3,
        accelBodyMps2: Vec3,
        dtS: Double,
        latitudeDeg: Double,
        altitudeM: Double,
    ): NFrameAdvance {
        val aveC = averageBodyToNav(state.attitude, gyroBodyRadps, dtS)
        val fNav = aveC * accelBodyMps2
        val aNav = navAccelFromSpecificForce(fNav, latitudeDeg, altitudeM)
        val vNew = state.velocityEnu + aNav * dtS
        val pNew = state.positionEnu + (state.velocityEnu + vNew) * (0.5 * dtS)
        val q = integrateAttitude(state.attitude, gyroBodyRadps, dtS)
        return NFrameAdvance(NFrameState(q, vNew, pNew), fNav, aNav)
    }

    fun step(
        state: NFrameState,
        gyroBodyRadps: Vec3,
        accelBodyMps2: Vec3,
        dtS: Double,
        latitudeDeg: Double,
        altitudeM: Double,
    ): NFrameState = advance(
        state,
        gyroBodyRadps,
        accelBodyMps2,
        dtS,
        latitudeDeg,
        altitudeM,
    ).state
}
