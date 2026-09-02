package `in`.driftzero.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import `in`.driftzero.app.R

@Composable
internal fun lampWordText(word: LampWord): String = stringResource(
    when (word) {
        LampWord.GNSS -> R.string.mode_gnss
        LampWord.ASSISTED -> R.string.mode_assisted
        LampWord.DEAD_RECKONING -> R.string.mode_dead_reckoning
        LampWord.REACQUIRING -> R.string.mode_reacquiring
        LampWord.LOW_CONFIDENCE -> R.string.mode_low_confidence
        LampWord.NO_PERMISSION -> R.string.mode_no_permission
        LampWord.WAITING_FIX -> R.string.mode_waiting_fix
    },
)

@Composable
internal fun lampReadoutText(lamp: LampDisplay): String? {
    val age = lamp.ageS ?: return null
    val seconds = InstrumentFormat.formatSeconds(age)
    return if (lamp.coasting) stringResource(R.string.readout_no_fix, seconds) else seconds
}

@Composable
internal fun lampColor(tone: LampTone): Color {
    val colors = InstrumentTheme.colors
    return when (tone) {
        LampTone.OK -> colors.lampOk
        LampTone.CAUTION -> colors.lampCaution
        LampTone.ALERT -> colors.lampAlert
    }
}

@Composable
internal fun reasonText(reason: ModeReason): String = when (reason) {
    is ModeReason.NoFix -> stringResource(R.string.reason_no_fix, InstrumentFormat.formatSeconds(reason.ageS))
    ModeReason.Held -> stringResource(R.string.reason_held)
    is ModeReason.RadiusLimit -> stringResource(
        R.string.reason_radius_limit,
        InstrumentFormat.formatRadius(reason.radiusM),
        InstrumentFormat.formatRadius(reason.limitM),
    )
    is ModeReason.FixAccuracyOver -> stringResource(R.string.reason_fix_accuracy, InstrumentFormat.formatRadius(reason.limitM))
    ModeReason.GatedFix -> stringResource(R.string.reason_gated_fix)
    ModeReason.Reacquiring -> stringResource(R.string.reason_reacquiring)
    ModeReason.ImuGap -> stringResource(R.string.reason_imu_gap)
}
