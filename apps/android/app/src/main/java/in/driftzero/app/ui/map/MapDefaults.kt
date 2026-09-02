package `in`.driftzero.app.ui.map

import org.maplibre.android.geometry.LatLng

/**
 * Default camera / puck target: Koregaon Park, Pune.
 *
 * Shown even before a GNSS fix so the map always has a location mark.
 */
object MapDefaults {
    const val KOREGAON_PARK_LATITUDE_DEG = 18.5362
    const val KOREGAON_PARK_LONGITUDE_DEG = 73.8938
    const val DEFAULT_ZOOM = 16.0
    const val DEFAULT_TILT_DEG = 0.0
    const val DEFAULT_BEARING_DEG = 28.0f
    const val DEFAULT_ACCURACY_M = 28.0f

    /** OpenFreeMap liberty — light street style, no API key. Prototype online source. */
    const val OPEN_FREE_MAP_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"

    val koregaonPark: LatLng
        get() = LatLng(KOREGAON_PARK_LATITUDE_DEG, KOREGAON_PARK_LONGITUDE_DEG)
}
