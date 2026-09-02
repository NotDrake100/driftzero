package `in`.driftzero.app.ui

/**
 * Hosted OpenFreeMap styles until an installed PMTiles area package owns rendering.
 * Liberty is the day street sheet, dark is the night sheet (same hosted tiles).
 * Bright is the fallback if the chosen sheet fails.
 * OpenFreeMap, Photon, Nominatim, and public OSRM are worldwide. No city is the
 * default world. Photon is the primary geocoder. Nominatim is the fallback.
 *
 * MapLibre 13 `android-sdk` is Vulkan. Emulator qemu SIGSEGVs after that guest
 * VkInstance. The Gradle catalog pins `android-sdk-opengl` (OpenGL ES).
 */
object StreetMapConfig {
    const val STYLE_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
    const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
    const val STYLE_BRIGHT = "https://tiles.openfreemap.org/styles/bright"

    fun hostedStyle(night: Boolean): String = if (night) STYLE_DARK else STYLE_LIBERTY
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

    // Draw order, bottom to top. Everything up to the cone sits below the first
    // symbol layer so street names stay legible; puck and destination sit on top.
    const val MATCHED_SOURCE_ID = "driftzero-matched"
    const val MATCHED_LAYER_ID = "driftzero-matched-line"
    const val ROUTE_SOURCE_ID = "driftzero-route"
    const val ROUTE_CASING_LAYER_ID = "driftzero-route-casing"
    const val ROUTE_LAYER_ID = "driftzero-route-line"
    const val HALO_SOURCE_ID = "driftzero-halo"
    const val HALO_FILL_LAYER_ID = "driftzero-halo-fill"
    const val HALO_LINE_LAYER_ID = "driftzero-halo-line"
    const val CONE_SOURCE_ID = "driftzero-cone"
    const val CONE_LAYER_ID = "driftzero-cone-fill"
    const val PUCK_SOURCE_ID = "driftzero-puck"
    const val PUCK_RING_LAYER_ID = "driftzero-puck-ring"
    const val PUCK_LAYER_ID = "driftzero-puck-disk"
    const val DEST_SOURCE_ID = "driftzero-dest"
    const val DEST_LAYER_ID = "driftzero-dest-point"

    const val MATCHED_LINE_WIDTH = 7f
    const val MATCHED_ALPHA = 0.35f
    const val ROUTE_CASING_WIDTH = 9f
    const val ROUTE_LINE_WIDTH = 5f
    const val HALO_LINE_WIDTH = 1.5f
    const val CONE_ALPHA = 0.35f
    const val DEST_RADIUS_DP = 7f
    const val DEST_STROKE_DP = 3f
    const val FOLLOW_ZOOM_EASE_MS = 500L
    const val CAMERA_EASE_MS = 250
}
