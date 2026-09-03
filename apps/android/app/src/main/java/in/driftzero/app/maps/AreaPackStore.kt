package `in`.driftzero.app.maps

import `in`.driftzero.app.ui.StreetMapConfig
import `in`.driftzero.app.ui.parseJson
import java.io.File
import java.security.MessageDigest

/**
 * Queues and activates area packs by bbox. Ready packs own MapLibre
 * rendering through a rewritten style JSON. Until tiles, a graph, and a
 * matching checksum exist on disk, the hosted style stays.
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
     * Sideload a built pack. Directory must contain manifest.json plus
     * tiles.pmtiles and a graph file. Missing files stay queued. Hash
     * mismatch is Corrupt, not Ready.
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
        copyIfPresent(sourceDir, dest, STYLE)
        copyIfPresent(sourceDir, dest, STYLE_DAY)
        copyIfPresent(sourceDir, dest, STYLE_NIGHT)
        copyDirIfPresent(sourceDir, dest, GLYPHS_DIR)
        copyDirIfPresent(sourceDir, dest, SPRITES_DIR)
        val installedPmtile = File(dest, TILES)
        val installedGraph = findGraph(dest)
        val state = if (
            checksumMatches(installedPmtile, parsed.pmtilesSha256) &&
            installedGraph != null &&
            checksumMatches(installedGraph, parsed.graphSha256)
        ) {
            AreaPackState.Ready
        } else {
            AreaPackState.Corrupt
        }
        File(dest, MANIFEST).writeText(manifestJson(parsed, state))
        return AreaPack(parsed, state, TILES, graph.name)
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

    /**
     * Style JSON for a pack whose bbox covers [latitudeDeg], [longitudeDeg]
     * and whose tiles exist. Does not hash. First paint uses this so a covering
     * Ready pack never flashes the hosted sheet.
     */
    fun coveringStyleJson(
        latitudeDeg: Double?,
        longitudeDeg: Double?,
        night: Boolean = false,
    ): String? {
        if (latitudeDeg == null || longitudeDeg == null) {
            return null
        }
        if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite()) {
            return null
        }
        val dirs = installedRoot().listFiles() ?: return null
        for (dir in dirs) {
            if (!dir.isDirectory) {
                continue
            }
            val manifest = readManifest(File(dir, MANIFEST)) ?: continue
            if (!manifest.bbox.contains(latitudeDeg, longitudeDeg)) {
                continue
            }
            val json = styleJsonFromDir(dir, night)
            if (json != null) {
                return json
            }
        }
        return null
    }

    fun coversPosition(latitudeDeg: Double?, longitudeDeg: Double?): Boolean {
        if (latitudeDeg == null || longitudeDeg == null) {
            return false
        }
        val dirs = installedRoot().listFiles() ?: return false
        return dirs.any { dir ->
            dir.isDirectory &&
                File(dir, TILES).isFile &&
                (readManifest(File(dir, MANIFEST))?.bbox?.contains(latitudeDeg, longitudeDeg) == true)
        }
    }

    fun graphBinOnDisk(): File? {
        val dirs = installedRoot().listFiles() ?: return null
        for (dir in dirs) {
            val bin = File(dir, GRAPH)
            if (bin.isFile) {
                return bin
            }
        }
        return null
    }

    /**
     * Day or night pack style with absolute `pmtiles://file://` tiles and
     * local glyph/sprite URLs. Null means keep the hosted OpenFreeMap sheet.
     */
    fun resolvedStyleJson(pack: AreaPack?, night: Boolean = false): String? {
        if (pack == null || pack.state != AreaPackState.Ready) {
            return null
        }
        return styleJsonFromDir(File(installedRoot(), pack.manifest.id.value), night)
    }

    private fun styleJsonFromDir(dir: File, night: Boolean): String? {
        val styleFile = packStyleFile(dir, night) ?: return null
        val tiles = File(dir, TILES)
        if (!tiles.isFile) {
            return null
        }
        val glyphsDir = File(dir, GLYPHS_DIR)
        val glyphsUri = if (glyphsDir.isDirectory) {
            StreetMapConfig.glyphsFileUri(glyphsDir)
        } else {
            StreetMapConfig.ASSET_GLYPHS_URI
        }
        val spriteJson = File(dir, "$SPRITES_DIR/ofm.json")
        val spriteUri = if (spriteJson.isFile) {
            StreetMapConfig.spriteFileUri(File(dir, "$SPRITES_DIR/ofm"))
        } else {
            null
        }
        return StreetMapConfig.rewritePackStyle(
            styleFile.readText(Charsets.UTF_8),
            tiles,
            glyphsUri,
            spriteUri,
        )
    }

    /** Hosted day or night sheet when no Ready pack style exists. */
    fun styleUri(pack: AreaPack?, night: Boolean = false): String {
        if (pack != null && pack.state == AreaPackState.Ready) {
            val style = packStyleFile(File(installedRoot(), pack.manifest.id.value), night)
            if (style != null) {
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
            val filesPresent = expected == AreaPackState.Ready && pmtiles.isFile && graph != null
            val hashesOk = filesPresent &&
                checksumMatches(pmtiles, manifest.pmtilesSha256) &&
                checksumMatches(graph!!, manifest.graphSha256)
            val state = when {
                expected == AreaPackState.Queued -> AreaPackState.Queued
                hashesOk -> AreaPackState.Ready
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

    private fun packStyleFile(dir: File, night: Boolean): File? {
        val preferred = File(dir, if (night) STYLE_NIGHT else STYLE_DAY)
        if (preferred.isFile) {
            return preferred
        }
        val fallback = File(dir, STYLE)
        return fallback.takeIf { it.isFile }
    }

    private fun copyIfPresent(sourceDir: File, dest: File, name: String) {
        val src = File(sourceDir, name)
        if (src.isFile) {
            src.copyTo(File(dest, name), overwrite = true)
        }
    }

    private fun copyDirIfPresent(sourceDir: File, dest: File, name: String) {
        val src = File(sourceDir, name)
        if (src.isDirectory) {
            src.copyRecursively(File(dest, name), overwrite = true)
        }
    }

    companion object {
        const val MANIFEST = "manifest.json"
        const val TILES = "tiles.pmtiles"
        const val GRAPH = "graph.bin"
        const val STYLE = "style.json"
        const val STYLE_DAY = "style-day.json"
        const val STYLE_NIGHT = "style-night.json"
        const val GLYPHS_DIR = "glyphs"
        const val SPRITES_DIR = "sprites"
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

internal fun checksumMatches(file: File, expected: String?): Boolean {
    if (expected.isNullOrBlank()) {
        return true
    }
    if (!file.isFile) {
        return false
    }
    val token = "${file.absolutePath}|${file.length()}|${file.lastModified()}|${expected.trim().lowercase()}"
    synchronized(checksumMemo) {
        checksumMemo[token]?.let { return it }
    }
    val sidecar = File(file.parentFile, "${file.name}.sha256-ok")
    if (sidecar.isFile && sidecar.readText().trim() == token) {
        synchronized(checksumMemo) {
            checksumMemo[token] = true
        }
        return true
    }
    val ok = sha256Hex(file).equals(expected.trim(), ignoreCase = true)
    synchronized(checksumMemo) {
        checksumMemo[token] = ok
    }
    if (ok) {
        sidecar.writeText(token)
    }
    return ok
}

private val checksumMemo = HashMap<String, Boolean>()

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) {
                break
            }
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

internal fun manifestJson(manifest: AreaPackManifest, state: AreaPackState): String {
    val parts = ArrayList<String>(16)
    parts += "\"id\":${jsonString(manifest.id.value)}"
    parts += "\"south\":${manifest.bbox.southLatDeg}"
    parts += "\"west\":${manifest.bbox.westLonDeg}"
    parts += "\"north\":${manifest.bbox.northLatDeg}"
    parts += "\"east\":${manifest.bbox.eastLonDeg}"
    parts += "\"schemaVersion\":${manifest.schemaVersion}"
    parts += "\"state\":${jsonString(state.name.lowercase())}"
    manifest.label?.let { parts += "\"label\":${jsonString(it)}" }
    manifest.osmSource?.let { parts += "\"osmSource\":${jsonString(it)}" }
    manifest.osmSnapshot?.let { parts += "\"osmSnapshot\":${jsonString(it)}" }
    manifest.pmtilesSha256?.let { parts += "\"pmtilesSha256\":${jsonString(it)}" }
    manifest.graphSha256?.let { parts += "\"graphSha256\":${jsonString(it)}" }
    manifest.bytes?.let { parts += "\"bytes\":$it" }
    return "{${parts.joinToString(",")}}"
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
        osmSnapshot = root.at("osmSnapshot")?.str(),
        pmtilesSha256 = root.at("pmtilesSha256")?.str(),
        graphSha256 = root.at("graphSha256")?.str(),
        bytes = root.at("bytes")?.num()?.toLong(),
    )
}

private fun jsonString(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}
