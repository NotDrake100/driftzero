package `in`.driftzero.app.ui

import `in`.driftzero.app.pose.LocationGrant
import `in`.driftzero.core.DeadReckoningFilter
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.NavigationState

enum class LampTone { OK, CAUTION, ALERT }

enum class LampWord {
    GNSS,
    ASSISTED,
    DEAD_RECKONING,
    REACQUIRING,
    LOW_CONFIDENCE,
    NO_PERMISSION,
    PRECISE_OFF,
    WAITING_FIX,
}

/**
 * What the lamp on the map says. [dashed] is the non-colour cue carried by the
 * halo stroke. [ageS] is the last trusted fix age; null when there is no pose.
 */
data class LampDisplay(
    val tone: LampTone,
    val word: LampWord,
    val ageS: Double?,
    val dashed: Boolean,
    val coasting: Boolean,
)

fun lampFor(mode: NavigationMode): LampDisplay = when (mode) {
    NavigationMode.GNSS_FUSED -> LampDisplay(LampTone.OK, LampWord.GNSS, null, dashed = false, coasting = false)
    NavigationMode.GNSS_DEGRADED -> LampDisplay(LampTone.CAUTION, LampWord.ASSISTED, null, dashed = false, coasting = false)
    NavigationMode.DEAD_RECKONING -> LampDisplay(LampTone.CAUTION, LampWord.DEAD_RECKONING, null, dashed = true, coasting = true)
    NavigationMode.REACQUIRING -> LampDisplay(LampTone.CAUTION, LampWord.REACQUIRING, null, dashed = true, coasting = false)
    NavigationMode.LOW_CONFIDENCE -> LampDisplay(LampTone.ALERT, LampWord.LOW_CONFIDENCE, null, dashed = true, coasting = true)
}

fun lampFor(state: NavigationState?, locationPermission: Boolean): LampDisplay =
    lampFor(state, if (locationPermission) LocationGrant.FINE else LocationGrant.NONE)

fun lampFor(state: NavigationState?, grant: LocationGrant): LampDisplay {
    if (grant == LocationGrant.NONE) {
        return LampDisplay(LampTone.ALERT, LampWord.NO_PERMISSION, null, dashed = true, coasting = false)
    }
    if (state == null && grant == LocationGrant.COARSE) {
        return LampDisplay(LampTone.ALERT, LampWord.PRECISE_OFF, null, dashed = true, coasting = false)
    }
    if (state == null) {
        return LampDisplay(LampTone.CAUTION, LampWord.WAITING_FIX, null, dashed = true, coasting = false)
    }
    return lampFor(state.mode).copy(ageS = state.gnssHealth.lastTrustedFixAgeS)
}

/** One sentence on why the lamp is not green. Null when it is. */
sealed class ModeReason {
    data class NoFix(val ageS: Double) : ModeReason()
    data object Held : ModeReason()
    data class RadiusLimit(val radiusM: Double, val limitM: Double) : ModeReason()
    data class FixAccuracyOver(val limitM: Double) : ModeReason()
    data object GatedFix : ModeReason()
    data object Reacquiring : ModeReason()
    data object ImuGap : ModeReason()
}

fun modeReason(
    state: NavigationState,
    lowConfidenceLimitM: Double = DEFAULT_LOW_CONFIDENCE_M,
    degradedAccuracyM: Double = DEFAULT_DEGRADED_ACCURACY_M,
): ModeReason? {
    val flags = state.health.flags
    val risk = state.gnssHealth.riskFlags
    return when (state.mode) {
        NavigationMode.GNSS_FUSED -> null
        NavigationMode.LOW_CONFIDENCE ->
            ModeReason.RadiusLimit(state.uncertainty.horizontal95.value, lowConfidenceLimitM)
        NavigationMode.DEAD_RECKONING -> when {
            flags.contains(DeadReckoningFilter.FLAG_GPS_HELD) -> ModeReason.Held
            flags.contains(DeadReckoningFilter.FLAG_NO_IMU) -> ModeReason.ImuGap
            else -> ModeReason.NoFix(state.gnssHealth.lastTrustedFixAgeS)
        }
        NavigationMode.REACQUIRING -> ModeReason.Reacquiring
        NavigationMode.GNSS_DEGRADED -> when {
            risk.contains(DeadReckoningFilter.RISK_POOR_ACCURACY) -> ModeReason.FixAccuracyOver(degradedAccuracyM)
            risk.contains(DeadReckoningFilter.RISK_GATED_FIX) -> ModeReason.GatedFix
            else -> ModeReason.NoFix(state.gnssHealth.lastTrustedFixAgeS)
        }
    }
}

/** Mirrors `InsConfig` defaults. The filter owns the rule; the UI only names it. */
const val DEFAULT_LOW_CONFIDENCE_M: Double = 120.0
const val DEFAULT_DEGRADED_ACCURACY_M: Double = 30.0
