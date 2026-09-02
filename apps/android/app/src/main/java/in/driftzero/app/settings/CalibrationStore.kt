package `in`.driftzero.app.settings

import android.content.Context
import android.content.SharedPreferences
import `in`.driftzero.core.CalibrationProfile

class CalibrationStore(private val prefs: SharedPreferences) {
    fun read(): CalibrationProfile? {
        if (!prefs.contains(KEY_X) || !prefs.contains(KEY_Y) || !prefs.contains(KEY_Z)) {
            return null
        }
        return try {
            CalibrationProfile(
                prefs.getFloat(KEY_X, Float.NaN).toDouble(),
                prefs.getFloat(KEY_Y, Float.NaN).toDouble(),
                prefs.getFloat(KEY_Z, Float.NaN).toDouble(),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun write(profile: CalibrationProfile) {
        prefs.edit()
            .putFloat(KEY_X, profile.gravityX.toFloat())
            .putFloat(KEY_Y, profile.gravityY.toFloat())
            .putFloat(KEY_Z, profile.gravityZ.toFloat())
            .apply()
    }

    companion object {
        const val PREFS: String = "driftzero_calibration"
        private const val KEY_X = "gravity_x"
        private const val KEY_Y = "gravity_y"
        private const val KEY_Z = "gravity_z"

        fun open(context: Context): CalibrationStore =
            CalibrationStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
