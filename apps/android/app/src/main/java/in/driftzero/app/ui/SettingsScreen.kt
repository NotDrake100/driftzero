package `in`.driftzero.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import `in`.driftzero.app.R
import `in`.driftzero.app.settings.AppSettings
import `in`.driftzero.app.settings.MotionMode
import `in`.driftzero.app.settings.SpeedUnit
import `in`.driftzero.app.settings.ThemeMode

@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onDeleteTrips: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    ScreenScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        ChoiceRow(
            label = stringResource(R.string.settings_units),
            options = listOf(
                stringResource(R.string.settings_units_kmh) to (settings.units == SpeedUnit.KMH),
                stringResource(R.string.settings_units_mph) to (settings.units == SpeedUnit.MPH),
            ),
            onPick = { index ->
                onChange(settings.copy(units = if (index == 0) SpeedUnit.KMH else SpeedUnit.MPH))
            },
        )
        ChoiceRow(
            label = stringResource(R.string.settings_theme),
            options = listOf(
                stringResource(R.string.settings_theme_system) to (settings.theme == ThemeMode.SYSTEM),
                stringResource(R.string.settings_theme_day) to (settings.theme == ThemeMode.DAY),
                stringResource(R.string.settings_theme_night) to (settings.theme == ThemeMode.NIGHT),
            ),
            onPick = { index ->
                onChange(
                    settings.copy(
                        theme = when (index) {
                            1 -> ThemeMode.DAY
                            2 -> ThemeMode.NIGHT
                            else -> ThemeMode.SYSTEM
                        },
                    ),
                )
            },
        )
        ChoiceRow(
            label = stringResource(R.string.settings_reduce_motion),
            options = listOf(
                stringResource(R.string.settings_theme_system) to (settings.motion == MotionMode.SYSTEM),
                stringResource(R.string.settings_motion_on) to (settings.motion == MotionMode.REDUCED),
            ),
            onPick = { index ->
                onChange(settings.copy(motion = if (index == 0) MotionMode.SYSTEM else MotionMode.REDUCED))
            },
        )
        ToggleRow(
            label = stringResource(R.string.settings_haptic),
            on = settings.hapticOnModeChange,
            onToggle = { onChange(settings.copy(hapticOnModeChange = !settings.hapticOnModeChange)) },
        )
        ToggleRow(
            label = stringResource(R.string.settings_audio),
            on = settings.audioOnLowConfidence,
            onToggle = { onChange(settings.copy(audioOnLowConfidence = !settings.audioOnLowConfidence)) },
        )
        ToggleRow(
            label = stringResource(R.string.settings_record),
            on = settings.recordTrips,
            onToggle = { onChange(settings.copy(recordTrips = !settings.recordTrips)) },
        )
        SecondaryButton(
            label = stringResource(R.string.settings_delete_all),
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth(),
        )
        if (confirmDelete) {
            ConfirmPanel(
                title = stringResource(R.string.settings_delete_all),
                confirmLabel = stringResource(R.string.action_delete),
                cancelLabel = stringResource(R.string.action_cancel),
                onConfirm = {
                    onDeleteTrips()
                    confirmDelete = false
                },
                onCancel = { confirmDelete = false },
                destructive = true,
            )
        }
        BasicText(text = stringResource(R.string.settings_privacy), style = InstrumentTheme.type.caption)
    }
}
