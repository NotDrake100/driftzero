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

@Composable
internal fun BottomInstrument(
    lamp: LampDisplay,
    speedText: String?,
    radiusText: String?,
    reason: ModeReason?,
    rows: List<Pair<String, String>>,
    routeName: String?,
    routeSummary: String?,
    recording: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenJudge: () -> Unit,
    onStopRoute: (() -> Unit)?,
    onOpenTrips: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val handle = stringResource(if (expanded) R.string.sheet_status_expanded else R.string.sheet_status_collapsed)
    InstrumentSheet(
        expanded = expanded,
        onToggle = onToggle,
        onLongPress = onOpenJudge,
        handleDescription = handle,
        modifier = modifier.navigationBarsPadding(),
    ) {
        CollapsedStatusLine(
            lamp = lamp,
            speedText = speedText,
            radiusText = radiusText,
            recording = recording,
            onClick = onToggle,
            onLongPress = onOpenJudge,
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
                if (reason != null) {
                    ListRow(label = stringResource(R.string.row_reason), value = reasonText(reason))
                }
                rows.forEach { (label, value) ->
                    ListRow(label = label, value = value)
                }
                LinkRow(
                    onOpenTrips = onOpenTrips,
                    onOpenOffline = onOpenOffline,
                    onOpenSettings = onOpenSettings,
                    onOpenAbout = onOpenAbout,
                )
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
    onLongPress: () -> Unit,
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
            .padding(horizontal = 16.dp),
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
        Box(modifier = Modifier.weight(1f))
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
private fun LinkRow(
    onOpenTrips: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SecondaryButton(label = stringResource(R.string.link_trips), onClick = onOpenTrips)
        SecondaryButton(label = stringResource(R.string.link_offline), onClick = onOpenOffline)
        SecondaryButton(label = stringResource(R.string.link_settings), onClick = onOpenSettings)
        SecondaryButton(label = stringResource(R.string.link_about), onClick = onOpenAbout)
    }
}
