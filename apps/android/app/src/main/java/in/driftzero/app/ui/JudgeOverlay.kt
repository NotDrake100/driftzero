package `in`.driftzero.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.driftzero.app.R
import `in`.driftzero.core.NavigationMode

@Composable
internal fun JudgeOverlay(
    held: Boolean,
    holdElapsedS: Double?,
    holdDistanceM: Double?,
    p95GapMs: Double?,
    modes: List<NavigationMode>,
    onToggleHold: () -> Unit,
    onClose: () -> Unit,
    onDemoSignalLoss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = InstrumentTheme.colors
    val type = InstrumentTheme.type
    var detailsOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(16.dp)
            .shadow(2.dp, RoundedCornerShape(8.dp))
            .background(colors.panel, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(text = stringResource(R.string.judge_title), style = type.title)
            SecondaryButton(label = stringResource(R.string.action_close), onClick = onClose)
        }
        BasicText(text = stringResource(R.string.judge_score_only), style = type.body)
        if (held && holdElapsedS != null && holdDistanceM != null) {
            BasicText(
                text = stringResource(
                    R.string.judge_held_status,
                    InstrumentFormat.formatSeconds(holdElapsedS),
                    InstrumentFormat.formatDistance(holdDistanceM),
                ),
                style = type.readout,
            )
        }
        if (detailsOpen) {
            BasicText(text = stringResource(R.string.judge_raw_trail), style = type.caption)
            BasicText(text = stringResource(R.string.judge_fused_trail), style = type.caption)
            BasicText(text = stringResource(R.string.judge_mode_strip), style = type.caption)
            ModeStrip(modes = modes, modifier = Modifier.fillMaxWidth().height(24.dp))
            StatusCopy.outputRate(p95GapMs)?.let { BasicText(text = it, style = type.readout) }
        }
        PrimaryButton(
            label = stringResource(if (held) R.string.action_resume_gnss else R.string.action_hold_gnss),
            onClick = onToggleHold,
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryButton(
            label = stringResource(if (detailsOpen) R.string.judge_less else R.string.judge_more),
            onClick = { detailsOpen = !detailsOpen },
            modifier = Modifier.fillMaxWidth(),
        )
        if (onDemoSignalLoss != null) {
            SecondaryButton(
                label = stringResource(R.string.demo_signal_loss),
                onClick = onDemoSignalLoss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ModeStrip(
    modes: List<NavigationMode>,
    modifier: Modifier = Modifier,
) {
    val colors = InstrumentTheme.colors
    val ok = colors.lampOk
    val caution = colors.lampCaution
    val alert = colors.lampAlert
    val well = colors.well
    Canvas(modifier = modifier.background(well)) {
        if (modes.isEmpty()) {
            return@Canvas
        }
        val col = 2.dp.toPx()
        val visible = ((size.width / col).toInt()).coerceAtLeast(1)
        val start = (modes.size - visible).coerceAtLeast(0)
        modes.subList(start, modes.size).forEachIndexed { index, mode ->
            drawRect(
                color = stripColor(mode, ok, caution, alert),
                topLeft = Offset(index * col, 0f),
                size = Size(col, size.height),
            )
        }
    }
}

private fun stripColor(mode: NavigationMode, ok: Color, caution: Color, alert: Color): Color = when (mode) {
    NavigationMode.GNSS_FUSED -> ok
    NavigationMode.GNSS_DEGRADED,
    NavigationMode.DEAD_RECKONING,
    NavigationMode.REACQUIRING,
    -> caution
    NavigationMode.LOW_CONFIDENCE -> alert
}
