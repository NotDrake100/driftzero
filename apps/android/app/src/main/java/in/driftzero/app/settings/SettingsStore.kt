package `in`.driftzero.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, DAY, NIGHT }

enum class SpeedUnit { KMH, MPH }

enum class MotionMode { SYSTEM, REDUCED }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val units: SpeedUnit = SpeedUnit.KMH,
    val motion: MotionMode = MotionMode.SYSTEM,
    val hapticOnModeChange: Boolean = true,
    val audioOnLowConfidence: Boolean = false,
    val recordTrips: Boolean = false,
    val firstRunDone: Boolean = false,
)

/** SharedPreferences-backed settings. Every value has a default; nothing is inferred. */
class SettingsStore(private val prefs: SharedPreferences) {
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_THEME, next.theme.name)
            .putString(KEY_UNITS, next.units.name)
            .putString(KEY_MOTION, next.motion.name)
            .putBoolean(KEY_HAPTIC, next.hapticOnModeChange)
            .putBoolean(KEY_AUDIO, next.audioOnLowConfidence)
            .putBoolean(KEY_RECORD, next.recordTrips)
            .putBoolean(KEY_FIRST_RUN_DONE, next.firstRunDone)
            .apply()
        _settings.value = next
    }

    private fun read(): AppSettings = AppSettings(
        theme = enumOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM),
        units = enumOrDefault(prefs.getString(KEY_UNITS, null), SpeedUnit.KMH),
        motion = enumOrDefault(prefs.getString(KEY_MOTION, null), MotionMode.SYSTEM),
        hapticOnModeChange = prefs.getBoolean(KEY_HAPTIC, true),
        audioOnLowConfidence = prefs.getBoolean(KEY_AUDIO, false),
        recordTrips = prefs.getBoolean(KEY_RECORD, false),
        firstRunDone = prefs.getBoolean(KEY_FIRST_RUN_DONE, false),
    )

    companion object {
        const val PREFS = "driftzero_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_UNITS = "units"
        private const val KEY_MOTION = "motion"
        private const val KEY_HAPTIC = "haptic_mode_change"
        private const val KEY_AUDIO = "audio_low_confidence"
        private const val KEY_RECORD = "record_trips"
        private const val KEY_FIRST_RUN_DONE = "first_run_done"

        fun open(context: Context): SettingsStore =
            SettingsStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

        internal inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == raw } ?: default
    }
}

@Composable
fun rememberSettingsStore(): SettingsStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { SettingsStore.open(context) }
}
