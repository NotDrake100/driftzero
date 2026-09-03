package `in`.driftzero.app.ui

import java.io.File

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

    /**
     * First paint. Pack JSON when a covering pack is already rewritten.
     * Otherwise the hosted sheet. Never wait on a checksum.
     */
    fun hostedStyleIfNoPack(packStyleJson: String?, night: Boolean): String? =
        if (packStyleJson == null) hostedStyle(night) else null
    const val PHOTON_API = "https://photon.komoot.io/api/"
    const val PHOTON_REVERSE = "https://photon.komoot.io/reverse"
    const val NOMINATIM_SEARCH = "https://nominatim.openstreetmap.org/search"
    const val NOMINATIM_REVERSE = "https://nominatim.openstreetmap.org/reverse"
    const val OSRM_ROUTE = "https://router.project-osrm.org/route/v1/driving/"
    const val USER_AGENT = "DriftZero/0.1 (Android travel map)"
    const val WORLD_LAT_DEG = 20.0
    const val WORLD_LON_DEG = 0.0
    const val WORLD_ZOOM = 2.0
    const val STREET_ZOOM = 16.0
    const val CAMERA_ZOOM = STREET_ZOOM
    /** Jump the camera when the first live GNSS of a session is this far from last-fix. */
    const val LIVE_RECENTER_M = 300.0
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
    const val TRAIL_RAW_SOURCE_ID = "driftzero-trail-raw"
    const val TRAIL_RAW_LAYER_ID = "driftzero-trail-raw-line"
    const val TRAIL_FUSED_SOURCE_ID = "driftzero-trail-fused"
    const val TRAIL_FUSED_LAYER_ID = "driftzero-trail-fused-line"
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
    const val GHOST_SOURCE_ID = "driftzero-ghost"
    const val GHOST_LAYER_ID = "driftzero-ghost-ring"
    const val GHOST_LABEL_LAYER_ID = "driftzero-ghost-label"
    const val CORRECTION_SOURCE_ID = "driftzero-correction"
    const val CORRECTION_LAYER_ID = "driftzero-correction-line"

    const val TRAIL_RAW_WIDTH = 2f
    const val TRAIL_FUSED_WIDTH = 3f
    const val MATCHED_LINE_WIDTH = 7f
    const val MATCHED_ALPHA = 0.35f
    const val ROUTE_CASING_WIDTH = 9f
    const val ROUTE_LINE_WIDTH = 5f
    const val HALO_LINE_WIDTH = 1.5f
    const val CONE_ALPHA = 0.35f
    const val DEST_RADIUS_DP = 7f
    const val DEST_STROKE_DP = 3f
    const val GHOST_RADIUS_DP = 7f
    const val GHOST_STROKE_DP = 2f
    const val CORRECTION_LINE_WIDTH = 1.5f
    const val FOLLOW_ZOOM_EASE_MS = 500L
    const val CAMERA_EASE_MS = 250
    const val FOLLOW_MIN_SPEED_MPS = 0.8
    const val LAST_FIX_WRITE_MIN_NS = 5_000_000_000L
    const val LAST_FIX_WRITE_MIN_DEG = 0.00015

    const val ASSET_GLYPHS_URI = "asset://glyphs/{fontstack}/{range}.pbf"

    /**
     * MapLibre Native needs a fully specified archive URL. Relative
     * `pmtiles://tiles.pmtiles` does not resolve against the style file.
     */
    fun pmtilesFileUri(tilesFile: File): String = "pmtiles://file://${tilesFile.absolutePath}"

    fun glyphsFileUri(glyphsDir: File): String {
        val path = glyphsDir.absolutePath.trimEnd('/')
        return "file://$path/{fontstack}/{range}.pbf"
    }

    fun spriteFileUri(spriteBase: File): String = "file://${spriteBase.absolutePath}"

    fun rewritePackStyle(
        styleJson: String,
        tilesFile: File,
        glyphsUri: String,
        spriteUri: String? = null,
    ): String {
        val tilesUri = jsonEscape(pmtilesFileUri(tilesFile))
        val glyphs = jsonEscape(glyphsUri)
        var json = PMTILES_URL.replace(styleJson) { "\"url\": \"$tilesUri\"" }
        json = GLYPHS_URL.replace(json) { "\"glyphs\": \"$glyphs\"" }
        if (spriteUri != null) {
            val sprite = jsonEscape(spriteUri)
            json = SPRITE_URL.replace(json) { "\"sprite\": \"$sprite\"" }
        }
        return json
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private val PMTILES_URL = Regex("\"url\"\\s*:\\s*\"pmtiles://[^\"]*\"")
    private val GLYPHS_URL = Regex("\"glyphs\"\\s*:\\s*\"[^\"]*\"")
    private val SPRITE_URL = Regex("\"sprite\"\\s*:\\s*\"[^\"]*\"")
}
