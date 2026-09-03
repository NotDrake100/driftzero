package `in`.driftzero.app.pose

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for [in.driftzero.core.MountProfile] JSON (`mount_profile_1.0.0`).
 * PoseStore does not own SharedPreferences types in tests.
 */
interface MountProfileStore {
    fun loadJson(): String?

    fun saveJson(json: String?)

    companion object {
        val None: MountProfileStore =
            object : MountProfileStore {
                override fun loadJson(): String? = null

                override fun saveJson(json: String?) = Unit
            }
    }
}

/** SharedPreferences-backed mount profile. Key is JSON, not a guessed rotation. */
class PrefsMountProfileStore(private val prefs: SharedPreferences) : MountProfileStore {
    override fun loadJson(): String? = prefs.getString(KEY, null)

    override fun saveJson(json: String?) {
        prefs.edit().apply {
            if (json.isNullOrEmpty()) {
                remove(KEY)
            } else {
                putString(KEY, json)
            }
        }.apply()
    }

    companion object {
        const val PREFS: String = "driftzero_mount"
        private const val KEY: String = "mount_profile_json"

        fun open(context: Context): PrefsMountProfileStore =
            PrefsMountProfileStore(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            )
    }
}
