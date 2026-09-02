package `in`.driftzero.app.ui

import android.content.Context

class LastFixStore(
    private val load: () -> Pair<Double, Double>?,
    private val save: (Double, Double) -> Unit,
) {
    fun read(): TravelLatLng? {
        val pair = load() ?: return null
        return CameraStartResolver.validOrNull(pair.first, pair.second)
    }

    fun write(point: TravelLatLng) {
        val valid = CameraStartResolver.validOrNull(point) ?: return
        save(valid.latitudeDeg, valid.longitudeDeg)
    }

    companion object {
        fun prefs(context: Context): LastFixStore {
            val prefs = context.applicationContext.getSharedPreferences(
                StreetMapConfig.LAST_FIX_PREFS,
                Context.MODE_PRIVATE,
            )
            return LastFixStore(
                load = {
                    if (!prefs.contains(StreetMapConfig.LAST_FIX_LAT) ||
                        !prefs.contains(StreetMapConfig.LAST_FIX_LON)
                    ) {
                        null
                    } else {
                        prefs.getFloat(StreetMapConfig.LAST_FIX_LAT, Float.NaN).toDouble() to
                            prefs.getFloat(StreetMapConfig.LAST_FIX_LON, Float.NaN).toDouble()
                    }
                },
                save = { lat, lon ->
                    prefs.edit()
                        .putFloat(StreetMapConfig.LAST_FIX_LAT, lat.toFloat())
                        .putFloat(StreetMapConfig.LAST_FIX_LON, lon.toFloat())
                        .apply()
                },
            )
        }
    }
}
