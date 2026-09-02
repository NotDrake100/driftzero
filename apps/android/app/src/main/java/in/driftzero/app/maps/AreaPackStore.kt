package `in`.driftzero.app.maps

import `in`.driftzero.app.ui.StreetMapConfig
import `in`.driftzero.app.ui.parseJson
import java.io.File

/**
 * Queues and activates area packs by bbox. Ready packs would own MapLibre
 * rendering. Until tiles and a graph exist on disk, the hosted style stays.
 */
class AreaPackStore(private val root: File) {
    fun queue(bbox: GeoBbox, id: AreaPackId = AreaPackId.fromBbox(bbox)): AreaPack {
        val manifest = AreaPackManifest(id = id, bbox = bbox)
        val dir = queuedDir(id)
        if (!dir.exists() && !dir.mkdirs()) {
            return AreaPack(manifest, AreaPackState.Corrupt)
        }
        File(dir, MANIFEST).writeText(manifestJson(manifest, AreaPackState.Queued))
        return AreaPack(manifest, AreaPackState.Queued)
    }

    fun queued(): List<AreaPack> = readTree(queuedRoot(), AreaPackState.Queued)

    fun installed(): List<AreaPack> = readTree(installedRoot(), AreaPackState.Ready)

    fun active(): AreaPack? = installed().firstOrNull { it.state == AreaPackState.Ready }

    /**
     * Sideload a built pack: directory must contain manifest.json plus
     * tiles.pmtiles and graph.bin. Missing files stay queued.
     */
    fun installSideload(sourceDir: File): AreaPack? {
        val parsed = readManifest(File(sourceDir, MANIFEST)) ?: return null
        val pmtiles = File(sourceDir, TILES)
        val graph = findGraph(sourceDir)
        if (!pmtiles.isFile || graph == null) {
            queue(parsed.bbox, parsed.id)
            return AreaPack(parsed, AreaPackState.Queued)
        }
        val dest = File(installedRoot(), parsed.id.value)
        if (dest.exists()) {
            dest.deleteRecursively()
        }
        if (!dest.mkdirs()) {
            return AreaPack(parsed, AreaPackState.Corrupt)
        }
        pmtiles.copyTo(File(dest, TILES), overwrite = true)
        graph.copyTo(File(dest, graph.name), overwrite = true)
        val style = File(sourceDir, STYLE)
        if (style.isFile) {
            style.copyTo(File(dest, STYLE), overwrite = true)
        }
        File(dest, MANIFEST).writeText(manifestJson(parsed, AreaPackState.Ready))
        return AreaPack(parsed, AreaPackState.Ready, TILES, graph.name)
    }

    fun bytesOnDisk(pack: AreaPack?): Long? {
        if (pack == null) {
            return null
        }
        pack.manifest.bytes?.let { return it }
        val dir = File(installedRoot(), pack.manifest.id.value)
        if (!dir.isDirectory) {
            return null
        }
        val sum = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return sum.takeIf { it > 0L }
    }

    fun graphFile(pack: AreaPack?): File? {
        if (pack == null || pack.state != AreaPackState.Ready) {
            return null
        }
        return findGraph(File(installedRoot(), pack.manifest.id.value))
    }

    /** A Ready pack with its own style.json owns rendering; otherwise the hosted day or night sheet. */
    fun styleUri(pack: AreaPack?, night: Boolean = false): String {
        if (pack != null && pack.state == AreaPackState.Ready) {
            val style = File(File(installedRoot(), pack.manifest.id.value), STYLE)
            if (style.isFile) {
                return "file://${style.absolutePath}"
            }
        }
        return StreetMapConfig.hostedStyle(night)
    }

    private fun queuedRoot(): File = File(root, "queued")

    private fun installedRoot(): File = File(root, "installed")

    private fun queuedDir(id: AreaPackId): File = File(queuedRoot(), id.value)

    private fun readTree(parent: File, expected: AreaPackState): List<AreaPack> {
        val dirs = parent.listFiles() ?: return emptyList()
        return dirs.filter { it.isDirectory }.mapNotNull { dir ->
            val manifest = readManifest(File(dir, MANIFEST)) ?: return@mapNotNull null
            val pmtiles = File(dir, TILES)
            val graph = findGraph(dir)
            val ready = expected == AreaPackState.Ready && pmtiles.isFile && graph != null
            val state = when {
                expected == AreaPackState.Queued -> AreaPackState.Queued
                ready -> AreaPackState.Ready
                else -> AreaPackState.Corrupt
            }
            AreaPack(
                manifest = manifest,
                state = state,
                pmtilesFileName = if (pmtiles.isFile) TILES else null,
                graphFileName = graph?.name,
            )
        }
    }

    private fun findGraph(dir: File): File? =
        GRAPH_NAMES.map { File(dir, it) }.firstOrNull { it.isFile }

    companion object {
        const val MANIFEST = "manifest.json"
        const val TILES = "tiles.pmtiles"
        const val GRAPH = "graph.bin"
        const val STYLE = "style.json"
        const val SCHEMA_VERSION = 1
        val GRAPH_NAMES: List<String> = listOf(
            "graph.bin",
            "graph.osm.xml",
            "graph.osm",
            "graph.osm.pbf",
            "graph.pbf",
        )
    }
}

internal fun manifestJson(manifest: AreaPackManifest, state: AreaPackState): String {
    val label = manifest.label?.let { ",\"label\":${jsonString(it)}" } ?: ""
    val source = manifest.osmSource?.let { ",\"osmSource\":${jsonString(it)}" } ?: ""
    return """{"id":${jsonString(manifest.id.value)},"south":${manifest.bbox.southLatDeg},"west":${manifest.bbox.westLonDeg},"north":${manifest.bbox.northLatDeg},"east":${manifest.bbox.eastLonDeg},"schemaVersion":${manifest.schemaVersion},"state":${jsonString(state.name.lowercase())}$label$source}"""
}

internal fun readManifest(file: File): AreaPackManifest? {
    if (!file.isFile) {
        return null
    }
    val root = parseJson(file.readText()) ?: return null
    val idRaw = root.at("id")?.str() ?: return null
    val id = try {
        AreaPackId(idRaw)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val bbox = GeoBbox.of(
        southLatDeg = root.at("south")?.num() ?: return null,
        westLonDeg = root.at("west")?.num() ?: return null,
        northLatDeg = root.at("north")?.num() ?: return null,
        eastLonDeg = root.at("east")?.num() ?: return null,
    ) ?: return null
    val schema = root.at("schemaVersion")?.num()?.toInt() ?: AreaPackStore.SCHEMA_VERSION
    return AreaPackManifest(
        id = id,
        bbox = bbox,
        schemaVersion = schema,
        label = root.at("label")?.str(),
        osmSource = root.at("osmSource")?.str(),
        pmtilesSha256 = root.at("pmtilesSha256")?.str(),
        graphSha256 = root.at("graphSha256")?.str(),
        bytes = root.at("bytes")?.num()?.toLong(),
    )
}

private fun jsonString(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}
