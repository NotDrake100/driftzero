package `in`.driftzero.app.maps

/**
 * Offline visual tiles plus a compact road graph for one bbox. Identity is
 * [id] plus [bbox]. A city name is an optional label, never the API.
 */
data class AreaPackId(val value: String) {
    init {
        require(value.matches(ID_PATTERN)) { "area pack id" }
    }

    companion object {
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

        fun fromBbox(bbox: GeoBbox): AreaPackId {
            return AreaPackId("bbox-${fmt(bbox.southLatDeg)}-${fmt(bbox.westLonDeg)}-${fmt(bbox.northLatDeg)}-${fmt(bbox.eastLonDeg)}")
        }

        private fun fmt(value: Double): String {
            val raw = "%.4f".format(java.util.Locale.US, value)
            return raw.replace('-', 'm').replace('.', 'p')
        }
    }
}

enum class AreaPackState {
    Absent,
    Queued,
    Installing,
    Ready,
    Corrupt,
}

data class AreaPackManifest(
    val id: AreaPackId,
    val bbox: GeoBbox,
    val schemaVersion: Int = 1,
    val label: String? = null,
    val osmSource: String? = null,
    val pmtilesSha256: String? = null,
    val graphSha256: String? = null,
    val bytes: Long? = null,
)

data class AreaPack(
    val manifest: AreaPackManifest,
    val state: AreaPackState,
    val pmtilesFileName: String? = null,
    val graphFileName: String? = null,
)

/**
 * Sample extract only. [AreaPackStore.queue] accepts any valid bbox.
 * Geofabrik India bounds are approximate; confirm against the extract in use.
 */
object AreaPackCatalog {
    val EXAMPLE_INDIA_BBOX: GeoBbox = requireNotNull(
        GeoBbox.of(
            southLatDeg = 6.5546,
            westLonDeg = 68.1114,
            northLatDeg = 35.6745,
            eastLonDeg = 97.3956,
        ),
    ) { "example india bbox" }

    val EXAMPLE_INDIA: AreaPackManifest = AreaPackManifest(
        id = AreaPackId("example-india"),
        bbox = EXAMPLE_INDIA_BBOX,
        label = "India example extract",
        osmSource = "https://download.geofabrik.de/asia/india.html",
    )
}
