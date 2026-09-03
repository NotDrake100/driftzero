package `in`.driftzero.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.driftzero.app.R
import `in`.driftzero.core.MountQuality

/**
 * Bottom status sheet. [mountReason], [mountQuality], and [roadAid] default to
 * null so the map owner can wire PoseStore without further UI edits. Skip them
 * when [rows] already come from [StatusCopy.of] to avoid a second Mount or
 * Road heading line. [onOpenJudge] is Lab only. Demo stays off the default sheet.
 */
@Composable
internal fun BottomInstrument(
    lamp: LampDisplay,
    speedText: String?,
    radiusText: String?,
    reason: ModeReason?,
    mountReason: String? = null,
    mountQuality: MountQuality? = null,
    mountYawConfidence: Double? = null,
    roadAid: StatusCopy.RoadAidState? = null,
    rows: List<Pair<String, String>>,
    routeName: String?,
    routeSummary: String?,
    recording: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenJudge: (() -> Unit)? = null,
    onStopRoute: (() -> Unit)?,
    onDemoSignalLoss: (() -> Unit)? = null,
    showLabTools: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val handle = stringResource(if (expanded) R.string.sheet_status_expanded else R.string.sheet_status_collapsed)
    InstrumentSheet(
        expanded = expanded,
        onToggle = onToggle,
        onLongPress = if (showLabTools) onOpenJudge else null,
        handleDescription = handle,
        modifier = modifier.navigationBarsPadding(),
    ) {
        CollapsedStatusLine(
            lamp = lamp,
            speedText = speedText,
            radiusText = radiusText,
            recording = recording,
            onClick = onToggle,
            onLongPress = if (showLabTools) onOpenJudge else null,
        )
        if (routeName != null && routeSummary != null) {
            RouteRows(
                name = routeName,
                summary = routeSummary,
                onStop = onStopRoute,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val seen = rows.map { it.first }.toSet()
                val reasonLabel = stringResource(R.string.row_reason)
                val mountLabel = stringResource(R.string.row_mount)
                val roadLabel = stringResource(R.string.row_road_heading)
                val reasonValue = StatusCopy.reasonLine(reason, mountReason)
                if (reasonValue != null && reasonLabel !in seen) {
                    ListRow(label = reasonLabel, value = reasonValue)
                }
                StatusCopy.mountIfUseful(mountQuality, mountYawConfidence)?.let { value ->
                    if (mountLabel !in seen) {
                        ListRow(label = mountLabel, value = value)
                    }
                }
                rows.forEach { (label, value) ->
                    ListRow(label = label, value = value)
                }
                StatusCopy.roadHeading(roadAid)?.let { value ->
                    if (showLabTools && roadLabel !in seen) {
                        ListRow(label = roadLabel, value = value)
                    }
                }
                if (showLabTools && onDemoSignalLoss != null) {
                    DemoRow(onClick = onDemoSignalLoss)
                }
            }
        }
    }
}

@Composable
private fun CollapsedStatusLine(
    lamp: LampDisplay,
    speedText: String?,
    radiusText: String?,
    recording: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val colors = InstrumentTheme.colors
    val type = InstrumentTheme.type
    val word = lampWordText(lamp.word)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .travelClickable(
                onClick = onClick,
                onLongClick = onLongPress,
                idle = colors.panel,
                pressed = colors.panelPressed,
            )
            .padding(horizontal = 16.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(lampColor(lamp.tone), CircleShape),
        )
        BasicText(text = word, style = type.label)
        if (recording) {
            BasicText(text = stringResource(R.string.trips_recording), style = type.readout)
        }
        if (speedText != null) {
            BasicText(text = speedText, style = type.readoutLarge)
        }
        if (radiusText != null) {
            BasicText(text = radiusText, style = type.readout)
        }
    }
}

@Composable
private fun RouteRows(
    name: String,
    summary: String,
    onStop: (() -> Unit)?,
) {
    val type = InstrumentTheme.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(text = name, style = type.body)
            BasicText(text = summary, style = type.label)
        }
        if (onStop != null) {
            SecondaryButton(label = stringResource(R.string.action_stop), onClick = onStop)
        }
    }
}

@Composable
private fun DemoRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .travelClickable(
                onClick = onClick,
                idle = InstrumentTheme.colors.panel,
                pressed = InstrumentTheme.colors.panelPressed,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text = stringResource(R.string.demo_signal_loss), style = InstrumentTheme.type.body)
    }
}
