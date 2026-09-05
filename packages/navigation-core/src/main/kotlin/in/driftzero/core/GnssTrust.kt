package `in`.driftzero.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Quarantine firewall for GNSS position. Residual is ENU metres versus the
 * IMU-predicted pose. Heading is atan2(ve, vn), 0 = north, clockwise.
 *
 * Cross-track versus along-track: a 180 m sideways jump at 42 km/h is
 * quarantine. Geometric gate and still-reject stay in [DeadReckoningFilter].
 * This is observable inconsistency, not certified spoofing detection
 * (docs/09).
 */
enum class GnssTrustAction {
    APPLY,
    QUARANTINE,
    HOLD,
}

data class GnssTrustDecision(
    val action: GnssTrustAction,
    val residualEastM: Double,
    val residualNorthM: Double,
    val alongTrackM: Double,
    val crossTrackM: Double,
    val headingRad: Double,
    val quarantined: Boolean,
    val consistentFixes: Int,
    val reason: String,
)

data class GnssEnuResidual(
    val eastM: Double,
    val northM: Double,
    val alongTrackM: Double,
    val crossTrackM: Double,
    val headingRad: Double,
)

object GnssTrust {
    /**
     * Floor on |cross| before quarantine, metres. Below this, the geometric
     * gate owns the decision.
     */
    const val CROSS_MIN_M: Double = 40.0

    /** |cross| limit = 3 * speed * dt + 2 * (pHoriz + sigma), metres. */
    const val CROSS_SPEED_K: Double = 3.0
    const val CROSS_UNCERT_K: Double = 2.0

    /** Skip the sideways test below this speed (m/s). Still-reject covers idle. */
    const val MIN_SPEED_MPS: Double = 2.0

    const val GNSS_ANOMALY_COPY: String = "GNSS anomaly. Satellite position rejected."
    const val GNSS_RESTORED_COPY: String = "GNSS integrity restored."

    /**
     * Project ENU residual onto heading. Heading 0 is north.
     * along = e sin h + n cos h. cross = e cos h - n sin h (right of heading).
     */
    fun projectResidual(eastM: Double, northM: Double, ve: Double, vn: Double): GnssEnuResidual {
        require(eastM.isFinite() && northM.isFinite()) { "residual must be finite" }
        require(ve.isFinite() && vn.isFinite()) { "velocity must be finite" }
        val heading = atan2(ve, vn)
        val s = sin(heading)
        val c = cos(heading)
        return GnssEnuResidual(
            eastM = eastM,
            northM = northM,
            alongTrackM = eastM * s + northM * c,
            crossTrackM = eastM * c - northM * s,
            headingRad = heading,
        )
    }

    fun crossLimitM(speedMps: Double, dtS: Double, pHorizM: Double, sigmaM: Double): Double {
        require(speedMps.isFinite() && speedMps >= 0.0)
        require(dtS.isFinite() && dtS >= 0.0)
        require(pHorizM.isFinite() && pHorizM >= 0.0)
        require(sigmaM.isFinite() && sigmaM >= 0.0)
        val dt = dtS.coerceIn(0.05, 2.0)
        val fromMotion = CROSS_SPEED_K * speedMps * dt
        val fromP = CROSS_UNCERT_K * (pHorizM + sigmaM)
        return max(CROSS_MIN_M, fromMotion + fromP)
    }

    fun shouldQuarantine(
        crossTrackM: Double,
        speedMps: Double,
        dtS: Double,
        pHorizM: Double,
        sigmaM: Double,
    ): Boolean {
        if (speedMps < MIN_SPEED_MPS) {
            return false
        }
        val limit = crossLimitM(speedMps, dtS, pHorizM, sigmaM)
        return abs(crossTrackM) > limit
    }
}

/**
 * Stateful release: [reacquireFixes] consecutive consistent (non-quarantine)
 * fixes before GNSS is applied again.
 */
class GnssTrustEngine(
    private val reacquireFixes: Int = 3,
) {
    init {
        require(reacquireFixes >= 1) { "reacquireFixes must be at least 1" }
    }

    private var quarantined: Boolean = false
    private var consistent: Int = 0
    private var releasedThisFix: Boolean = false

    fun isQuarantined(): Boolean = quarantined

    fun consistentFixes(): Int = consistent

    fun justReleased(): Boolean = releasedThisFix

    fun reset() {
        quarantined = false
        consistent = 0
        releasedThisFix = false
    }

    fun evaluate(
        residualEastM: Double,
        residualNorthM: Double,
        ve: Double,
        vn: Double,
        dtS: Double,
        pHorizM: Double,
        sigmaM: Double,
        enabled: Boolean = true,
    ): GnssTrustDecision {
        releasedThisFix = false
        val speed = hypot(ve, vn)
        val proj = GnssTrust.projectResidual(residualEastM, residualNorthM, ve, vn)
        if (!enabled) {
            return decision(GnssTrustAction.APPLY, proj, "quarantine disabled")
        }
        val sideways = GnssTrust.shouldQuarantine(proj.crossTrackM, speed, dtS, pHorizM, sigmaM)
        if (sideways) {
            quarantined = true
            consistent = 0
            return decision(GnssTrustAction.QUARANTINE, proj, "cross-track ${proj.crossTrackM} m")
        }
        if (!quarantined) {
            consistent = 0
            return decision(GnssTrustAction.APPLY, proj, "consistent")
        }
        consistent += 1
        return if (consistent >= reacquireFixes) {
            quarantined = false
            consistent = 0
            releasedThisFix = true
            decision(GnssTrustAction.APPLY, proj, "integrity restored")
        } else {
            decision(GnssTrustAction.HOLD, proj, "confirming ${consistent}/$reacquireFixes")
        }
    }

    private fun decision(
        action: GnssTrustAction,
        proj: GnssEnuResidual,
        reason: String,
    ): GnssTrustDecision =
        GnssTrustDecision(
            action = action,
            residualEastM = proj.eastM,
            residualNorthM = proj.northM,
            alongTrackM = proj.alongTrackM,
            crossTrackM = proj.crossTrackM,
            headingRad = proj.headingRad,
            quarantined = quarantined,
            consistentFixes = consistent,
            reason = reason,
        )
}
