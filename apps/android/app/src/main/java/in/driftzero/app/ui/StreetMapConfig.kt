package `in`.driftzero.app.ui

/**
 * Hosted OpenFreeMap styles until an installed PMTiles area package owns rendering.
 * Liberty is the primary light street sheet. Bright is the fallback if liberty fails.
 * OpenFreeMap, Photon, Nominatim, and public OSRM are worldwide. No city is the
 * default world. Photon is the primary geocoder. Nominatim is the fallback.
 */
object StreetMapConfig {
    const val STYLE_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
    const val STYLE_BRIGHT = "https://tiles.openfreemap.org/styles/bright"
    const val PHOTON_API = "https://photon.komoot.io/api/"
    const val NOMINATIM_SEARCH = "https://nominatim.openstreetmap.org/search"
    const val OSRM_ROUTE = "https://router.project-osrm.org/route/v1/driving/"
    const val USER_AGENT = "DriftZero/0.1 (Android travel map)"
    const val WORLD_LAT_DEG = 20.0
    const val WORLD_LON_DEG = 0.0
    const val WORLD_ZOOM = 2.0
    const val STREET_ZOOM = 16.0
    const val CAMERA_ZOOM = STREET_ZOOM
    const val SEARCH_BIAS_SPAN_DEG = 0.3
    const val SEARCH_BIAS_ZOOM = 14
    const val SEARCH_BIAS_SCALE = 0.1
    const val SEARCH_CAMERA_MIN_ZOOM = 8.0
    const val LAST_FIX_PREFS = "driftzero_map"
    const val LAST_FIX_LAT = "last_fix_lat_deg"
    const val LAST_FIX_LON = "last_fix_lon_deg"
    const val PUCK_DISK_DP = 22
    const val PUCK_RING_DP = 3
    const val PUCK_COLOR_ARGB = InstrumentPalette.MARKER_BLUE
    const val TEXTURE_MODE = true
    // MapLibre 13 default AAR is Vulkan. Emulator qemu SIGSEGVs after that
    // VkInstance. The Gradle catalog pins android-sdk-opengl (OpenGL ES).
    const val MAP_LOAD_COLOR_ARGB = InstrumentPalette.CHASSIS
    const val ROUTE_SOURCE_ID = "driftzero-route"
    const val ROUTE_LAYER_ID = "driftzero-route-line"
    const val DEST_SOURCE_ID = "driftzero-dest"
    const val DEST_LAYER_ID = "driftzero-dest-point"
    const val ROUTE_LINE_WIDTH = 5f
}
