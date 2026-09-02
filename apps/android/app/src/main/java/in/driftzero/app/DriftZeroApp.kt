package `in`.driftzero.app

import android.app.Application
import org.maplibre.android.MapLibre

class DriftZeroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
