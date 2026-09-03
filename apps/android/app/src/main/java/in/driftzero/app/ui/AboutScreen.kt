package `in`.driftzero.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.driftzero.app.R
import `in`.driftzero.core.CORE_VERSION

@Composable
internal fun AboutScreen(
    appVersion: String,
    onBack: () -> Unit,
    labUnlocked: Boolean = false,
    onLabUnlock: () -> Unit = {},
) {
    var showNotices by remember { mutableStateOf(false) }
    var labOpen by remember { mutableStateOf(labUnlocked) }
    var titleTaps by remember { mutableIntStateOf(if (labUnlocked) EvidenceCopy.LAB_UNLOCK_TAPS else 0) }
    ScreenScaffold(title = stringResource(R.string.about_title), onBack = onBack) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (labOpen) {
                        return@clickable
                    }
                    titleTaps += 1
                    if (titleTaps >= EvidenceCopy.LAB_UNLOCK_TAPS) {
                        labOpen = true
                        onLabUnlock()
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
            BasicText(text = stringResource(R.string.app_name), style = InstrumentTheme.type.title)
        }
        BasicText(
            text = stringResource(R.string.about_version, appVersion, CORE_VERSION),
            style = InstrumentTheme.type.readout,
        )
        EvidenceSection(
            title = stringResource(R.string.about_what_title),
            body = stringResource(R.string.about_what_body),
        )
        EvidenceSection(
            title = stringResource(R.string.about_outage_title),
            body = stringResource(R.string.about_outage_body),
        )
        EvidenceList(
            title = stringResource(R.string.about_demo_title),
            lines = listOf(
                stringResource(R.string.about_demo_1),
                stringResource(R.string.about_demo_2),
                stringResource(R.string.about_demo_3),
                stringResource(R.string.about_demo_4),
                stringResource(R.string.about_demo_5),
            ),
        )
        BasicText(text = stringResource(R.string.about_emulator), style = InstrumentTheme.type.caption)
        BasicText(text = stringResource(R.string.sensors_mag_unused), style = InstrumentTheme.type.caption)
        BasicText(text = stringResource(R.string.about_no_obd), style = InstrumentTheme.type.caption)
        SecondaryButton(
            label = stringResource(R.string.about_notices),
            onClick = { showNotices = !showNotices },
            modifier = Modifier.fillMaxWidth(),
        )
        if (showNotices) {
            BasicText(text = stringResource(R.string.about_notices_body), style = InstrumentTheme.type.caption)
        }
        if (labOpen) {
            BasicText(text = stringResource(R.string.about_lab_title), style = InstrumentTheme.type.title)
            EvidenceSection(
                title = stringResource(R.string.about_screening_title),
                body = stringResource(R.string.about_screening_body),
            )
            EvidenceSection(
                title = stringResource(R.string.about_ai_title),
                body = stringResource(R.string.about_ai_body),
            )
            EvidenceSection(
                title = stringResource(R.string.about_not_title),
                body = stringResource(R.string.about_not_body),
            )
        }
    }
}

@Composable
private fun EvidenceSection(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(text = title, style = InstrumentTheme.type.label)
        BasicText(text = body, style = InstrumentTheme.type.body)
    }
}

@Composable
private fun EvidenceList(title: String, lines: List<String>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(text = title, style = InstrumentTheme.type.label)
        lines.forEach { line ->
            BasicText(text = line, style = InstrumentTheme.type.body)
        }
    }
}
