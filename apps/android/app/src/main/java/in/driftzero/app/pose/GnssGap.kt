package `in`.driftzero.app.pose

import `in`.driftzero.core.DeadReckoningFilter
import kotlin.math.max
import kotlin.math.min

/**
 * After a GNSS outage the next emulator or phone fix can land tens of
 * metres from the coasted pose. The filter's innovation gate uses the
 * reported accuracy. Inflate that accuracy so a tunnel-exit jump can
 * reacquire instead of being rejected forever. Does not rewrite the
 * estimator; it only widens R.
 *
 * Two triggers: trusted GNSS older than [STALE_AFTER_S], or a jump from
 * the last ingested fix of at least [GAP_JUMP_M]. The second covers
 * Android and the emulator holding the last Location through a gap, then
 * teleporting to the resume point with a fresh timestamp.
 */
internal const val GAP_REACQUIRE_ACCURACY_M: Double = 50.0
internal const val GAP_JUMP_M: Double = 80.0
/** Must stay at or under [InsConfig.maxGnssAccuracyM] or the filter drops the fix. */
internal const val GAP_ACCURACY_CAP_M: Double = 80.0

internal fun inflateGnssAccuracyAfterGap(
    accuracyM: Double,
    trustedAgeS: Double,
    jumpM: Double = 0.0,
    staleAfterS: Double = DeadReckoningFilter.STALE_AFTER_S,
): Double {
    require(accuracyM.isFinite() && accuracyM >= 0.0) { "accuracy_m must be finite and >= 0" }
    require(trustedAgeS.isFinite() && trustedAgeS >= 0.0) { "trusted_age_s must be finite and >= 0" }
    require(jumpM.isFinite() && jumpM >= 0.0) { "jump_m must be finite and >= 0" }
    require(staleAfterS.isFinite() && staleAfterS > 0.0) { "stale_after_s must be finite and > 0" }
    val afterOutage = trustedAgeS > staleAfterS
    val teleport = jumpM >= GAP_JUMP_M
    if (!afterOutage && !teleport) {
        return accuracyM
    }
    val floor = max(accuracyM, max(GAP_REACQUIRE_ACCURACY_M, jumpM / 6.0))
    return min(floor, GAP_ACCURACY_CAP_M)
}
