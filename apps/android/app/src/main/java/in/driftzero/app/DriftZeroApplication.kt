package `in`.driftzero.app

import android.app.Application
import org.maplibre.android.MapLibre

/**
 * Initializes MapLibre before any [org.maplibre.android.maps.MapView] is inflated.
 *
 * The OpenFreeMap liberty style used by StreetMap is a
 * prototype online tile source until a local PMTiles package is bundled (ADR 003).
 * The location puck itself is production-path UI.
 */
class DriftZeroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
