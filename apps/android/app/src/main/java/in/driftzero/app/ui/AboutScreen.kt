package `in`.driftzero.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.driftzero.app.R
import `in`.driftzero.core.CORE_VERSION

@Composable
internal fun AboutScreen(
    appVersion: String,
    onBack: () -> Unit,
) {
    var showNotices by remember { mutableStateOf(false) }
    ScreenScaffold(title = stringResource(R.string.about_title), onBack = onBack) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
        BasicText(text = stringResource(R.string.about_india), style = InstrumentTheme.type.body)
        BasicText(text = stringResource(R.string.about_limits_title), style = InstrumentTheme.type.label)
        BasicText(text = stringResource(R.string.about_limit_1), style = InstrumentTheme.type.caption)
        BasicText(text = stringResource(R.string.about_limit_2), style = InstrumentTheme.type.caption)
        BasicText(text = stringResource(R.string.about_limit_3), style = InstrumentTheme.type.caption)
        BasicText(text = stringResource(R.string.about_limit_4), style = InstrumentTheme.type.caption)
        SecondaryButton(
            label = stringResource(R.string.about_notices),
            onClick = { showNotices = !showNotices },
            modifier = Modifier.fillMaxWidth(),
        )
        if (showNotices) {
            BasicText(text = stringResource(R.string.about_notices_body), style = InstrumentTheme.type.caption)
        }
    }
}
