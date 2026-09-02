package `in`.driftzero.core

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.InflaterInputStream

/**
 * OSM XML or PBF to a directed [RoadGraph]. The bbox is any WGS84 box.
 * A city name is not an input.
 */
object OsmGraphLoader {
    fun load(
        path: Path,
        packageId: String = path.fileName.toString(),
        bbox: Wgs84Bbox? = null,
    ): RoadGraph {
        val bytes = Files.readAllBytes(path)
        val name = path.fileName.toString().lowercase()
        val format = when {
            name.endsWith(".pbf") || name.endsWith(".osm.pbf") -> OsmFormat.PBF
            looksLikePbf(bytes) -> OsmFormat.PBF
            else -> OsmFormat.XML
        }
        return load(bytes, packageId, format, bbox)
    }

    fun load(
        bytes: ByteArray,
        packageId: String,
        format: OsmFormat,
        bbox: Wgs84Bbox? = null,
    ): RoadGraph = when (format) {
        OsmFormat.XML -> loadXml(bytes.decodeToString(), packageId, bbox)
        OsmFormat.PBF -> loadPbf(bytes, packageId, bbox)
    }

    fun loadXml(xml: String, packageId: String, bbox: Wgs84Bbox? = null): RoadGraph {
        val nodes = LinkedHashMap<Long, GraphNode>()
        val ways = ArrayList<RawWay>()
        val tagRe = Regex("<(/?)(node|way|nd|tag|bounds|osm|relation|member)\\b([^>]*)(/?)>", RegexOption.IGNORE_CASE)
        var currentWay: RawWay? = null
        for (match in tagRe.findAll(xml)) {
            val closing = match.groupValues[1] == "/"
            val name = match.groupValues[2].lowercase()
            val attrs = parseAttrs(match.groupValues[3])
            val selfClose = match.groupValues[4] == "/"
            when (name) {
                "node" -> if (!closing) {
                    val id = attrs["id"]?.toLongOrNull() ?: continue
                    val lat = attrs["lat"]?.toDoubleOrNull() ?: continue
                    val lon = attrs["lon"]?.toDoubleOrNull() ?: continue
                    if (!lat.isFinite() || !lon.isFinite()) continue
                    if (bbox != null && !bbox.contains(lat, lon)) continue
                    nodes[id] = GraphNode(id, LatitudeDeg(lat), LongitudeDeg(lon))
                }
                "way" -> if (!closing) {
                    val id = attrs["id"]?.toLongOrNull() ?: continue
                    currentWay = RawWay(id)
                    if (selfClose) {
                        ways.add(currentWay)
                        currentWay = null
                    }
                } else {
                    currentWay?.let { ways.add(it) }
                    currentWay = null
                }
                "nd" -> if (!closing) {
                    val ref = attrs["ref"]?.toLongOrNull() ?: continue
                    currentWay?.refs?.add(ref)
                }
                "tag" -> if (!closing) {
                    val key = attrs["k"] ?: continue
                    val value = attrs["v"] ?: continue
                    currentWay?.tags?.put(key, value)
                }
            }
        }
        currentWay?.let { ways.add(it) }
        return assemble(packageId, nodes, ways, bbox)
    }

    fun loadPbf(bytes: ByteArray, packageId: String, bbox: Wgs84Bbox? = null): RoadGraph {
        val nodes = LinkedHashMap<Long, GraphNode>()
        val ways = ArrayList<RawWay>()
        var offset = 0
        while (offset + 4 <= bytes.size) {
            val headerLen = readInt32Be(bytes, offset)
            offset += 4
            if (headerLen <= 0 || offset + headerLen > bytes.size) {
                break
            }
            val header = parseBlobHeader(bytes, offset, headerLen)
            offset += headerLen
            if (header.datasize <= 0 || offset + header.datasize > bytes.size) {
                break
            }
            val payload = parseBlob(bytes, offset, header.datasize)
            offset += header.datasize
            if (header.type != "OSMData" || payload == null) {
                continue
            }
            parsePrimitiveBlock(payload, nodes, ways, bbox)
        }
        return assemble(packageId, nodes, ways, bbox)
    }

    internal val VEHICLE_HIGHWAYS: Set<String> = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary",
        "unclassified", "residential", "living_street", "service", "road",
        "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
    )

    private fun assemble(
        packageId: String,
        nodes: Map<Long, GraphNode>,
        ways: List<RawWay>,
        bbox: Wgs84Bbox?,
    ): RoadGraph {
        val usable = ways.filter { way ->
            val highway = way.tags["highway"] ?: return@filter false
            highway in VEHICLE_HIGHWAYS && way.tags["area"] != "yes" && way.refs.size >= 2
        }
        val degree = HashMap<Long, Int>()
        for (way in usable) {
            for (ref in way.refs) {
                degree[ref] = (degree[ref] ?: 0) + 1
            }
        }
        val edges = ArrayList<GraphEdge>()
        for (way in usable) {
            val splitAt = BooleanArray(way.refs.size)
            splitAt[0] = true
            splitAt[way.refs.lastIndex] = true
            for (i in 1 until way.refs.lastIndex) {
                val ref = way.refs[i]
                if ((degree[ref] ?: 0) != 2) {
                    splitAt[i] = true
                }
            }
            val cuts = way.refs.indices.filter { splitAt[it] }
            var seg = 0
            for (c in 0 until cuts.size - 1) {
                val from = cuts[c]
                val to = cuts[c + 1]
                if (to - from < 1) continue
                val refs = way.refs.subList(from, to + 1)
                val points = refs.mapNotNull { nodes[it] }.map {
                    GeoPoint(it.latitude, it.longitude)
                }
                if (points.size < 2) continue
                val (length, headings) = try {
                    polylineLengthAndHeadings(points)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                val oneway = parseOneway(way.tags)
                val baseId = "way/${way.id}:$seg"
                fun add(id: String, pts: List<GeoPoint>, hdgs: DoubleArray, fromId: Long, toId: Long) {
                    val edge = GraphEdge(
                        id = id,
                        fromNodeId = fromId,
                        toNodeId = toId,
                        points = pts,
                        lengthM = length,
                        segmentHeadingsRad = hdgs,
                        highway = way.tags.getValue("highway"),
                        osmWayId = way.id,
                    )
                    if (bbox == null || edgeTouchesBbox(edge, bbox)) {
                        edges.add(edge)
                    }
                }
                when (oneway) {
                    Oneway.FORWARD -> add(
                        "$baseId:fwd",
                        points,
                        headings,
                        refs.first(),
                        refs.last(),
                    )
                    Oneway.REVERSE -> add(
                        "$baseId:rev",
                        points.asReversed(),
                        reverseHeadings(headings),
                        refs.last(),
                        refs.first(),
                    )
                    Oneway.BOTH -> {
                        add("$baseId:fwd", points, headings, refs.first(), refs.last())
                        add(
                            "$baseId:rev",
                            points.asReversed(),
                            reverseHeadings(headings),
                            refs.last(),
                            refs.first(),
                        )
                    }
                }
                seg += 1
            }
        }
        val used = HashSet<Long>()
        for (edge in edges) {
            used.add(edge.fromNodeId)
            used.add(edge.toNodeId)
        }
        val keptNodes = nodes.filterKeys { it in used }
        return RoadGraph(packageId, keptNodes, edges, bbox)
    }

    private fun reverseHeadings(headings: DoubleArray): DoubleArray {
        val out = DoubleArray(headings.size)
        for (i in headings.indices) {
            out[i] = wrapHeadingRad(headings[headings.lastIndex - i] + Math.PI)
        }
        return out
    }

    private fun parseOneway(tags: Map<String, String>): Oneway {
        if (tags["junction"] == "roundabout" || tags["junction"] == "circular") {
            return Oneway.FORWARD
        }
        return when (tags["oneway"]?.lowercase()) {
            "yes", "true", "1" -> Oneway.FORWARD
            "-1", "reverse" -> Oneway.REVERSE
            else -> Oneway.BOTH
        }
    }

    private fun parseAttrs(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val re = Regex("""([A-Za-z:_][A-Za-z0-9:._-]*)\s*=\s*("([^"]*)"|'([^']*)')""")
        for (match in re.findAll(raw)) {
            val value = match.groupValues[3].ifEmpty { match.groupValues[4] }
            out[match.groupValues[1]] = value
        }
        return out
    }

    private fun looksLikePbf(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val headerLen = readInt32Be(bytes, 0)
        return headerLen in 1..64 && bytes.size > 4 + headerLen
    }

    private fun parseBlobHeader(bytes: ByteArray, start: Int, length: Int): BlobHeader {
        val reader = ProtoReader(bytes, start, start + length)
        var type = ""
        var datasize = 0
        while (reader.hasRemaining()) {
            when (val field = reader.readKey()) {
                1 to 2 -> type = reader.readString()
                3 to 0 -> datasize = reader.readVarint().toInt()
                else -> reader.skip(field.second)
            }
        }
        return BlobHeader(type, datasize)
    }

    private fun parseBlob(bytes: ByteArray, start: Int, length: Int): ByteArray? {
        val reader = ProtoReader(bytes, start, start + length)
        var raw: ByteArray? = null
        var zlib: ByteArray? = null
        var rawSize = 0
        while (reader.hasRemaining()) {
            when (val field = reader.readKey()) {
                1 to 2 -> raw = reader.readBytes()
                2 to 0 -> rawSize = reader.readVarint().toInt()
                3 to 2 -> zlib = reader.readBytes()
                else -> reader.skip(field.second)
            }
        }
        if (raw != null) {
            return raw
        }
        val compressed = zlib ?: return null
        return inflate(compressed, if (rawSize > 0) rawSize else compressed.size * 4)
    }

    private fun inflate(compressed: ByteArray, hint: Int): ByteArray {
        ByteArrayInputStream(compressed).use { input ->
            InflaterInputStream(input).use { stream ->
                val bytes = stream.readBytes()
                return if (bytes.isNotEmpty()) bytes else ByteArray(hint.coerceAtLeast(0))
            }
        }
    }

    private fun parsePrimitiveBlock(
        payload: ByteArray,
        nodes: MutableMap<Long, GraphNode>,
        ways: MutableList<RawWay>,
        bbox: Wgs84Bbox?,
    ) {
        val reader = ProtoReader(payload, 0, payload.size)
        var strings = emptyList<String>()
        val groups = ArrayList<ByteArray>()
        var granularity = 100
        var latOffset = 0L
        var lonOffset = 0L
        while (reader.hasRemaining()) {
            when (val field = reader.readKey()) {
                1 to 2 -> strings = parseStringTable(reader.readBytes())
                2 to 2 -> groups.add(reader.readBytes())
                17 to 0 -> granularity = reader.readVarint().toInt()
                19 to 0 -> latOffset = reader.readVarint()
                20 to 0 -> lonOffset = reader.readVarint()
                else -> reader.skip(field.second)
            }
        }
        for (group in groups) {
            parseGroup(group, strings, granularity, latOffset, lonOffset, nodes, ways, bbox)
        }
    }

    private fun parseStringTable(bytes: ByteArray): List<String> {
        val reader = ProtoReader(bytes, 0, bytes.size)
        val out = ArrayList<String>()
        while (reader.hasRemaining()) {
            when (val field = reader.readKey()) {
                1 to 2 -> out.add(reader.readString())
                else -> reader.skip(field.second)
            }
        }
        return out
    }

    private fun parseGroup(
        bytes: ByteArray,
        strings: List<String>,
        granularity: Int,
        latOffset: Long,
        lonOffset: Long,
        nodes: MutableMap<Long, GraphNode>,
        ways: MutableList<RawWay>,
        bbox: Wgs84Bbox?,
    ) {
        val reader = ProtoReader(bytes, 0, bytes.size)
        while (reader.hasRemaining()) {
            when (val field = reader.readKey()) {
                1 to 2 -> parsePlainNode(reader.readBytes(), granularity, latOffset, lonOffset, nodes, bbox)
                2 to 2 -> parseDenseNodes(reader.readBytes(), granularity, latOffset, lonOffset, nodes, bbox)
                3 to 2 -> parseWay(reader.readBytes(), strings, ways)
                else -> reader.skip(field.second)
            }
        }
    }

    private fun parsePlainNode(
        bytes: ByteArray,
        granularity: Int,
        latOffset: Long,
        lonOffset: Long,
        nodes: MutableMap<Long, GraphNode>,
        bbox: Wgs84Bbox?,
    ) {
        val reader = ProtoReader(bytes, 0, bytes.size)
        var id = 0L
        var lat = 0L
        var lon = 0L
        while (reader.hasRemaining()) {
            when (val field = reader.readKey()) {
                1 to 0 -> id = unzigzag(reader.readVarint())
                8 to 0 -> lat = unzigzag(reader.readVarint())
                9 to 0 -> lon = unzigzag(reader.readVarint())
                else -> reader.skip(field.second)
            }
        }
        addNode(id, lat, lon, granularity, latOffset, lonOffset, nodes, bbox)
    }

    private fun parseDenseNodes(
        bytes: ByteArray,
        granularity: Int,
        latOffset: Long,
        lonOffset: Long,
        nodes: MutableMap<Long, GraphNode>,
        bbox: Wgs84Bbox?,
    ) {
        val reader = ProtoReader(bytes, 0, bytes.size)
        var ids = LongArray(0)
        var lats = LongArray(0)
        var lons = LongArray(0)
        while (reader.hasRemaining()) {
            when (val field = reader.readKey()) {
                1 to 0, 1 to 2 -> ids = reader.readPackedSint64(field.second)
                8 to 0, 8 to 2 -> lats = reader.readPackedSint64(field.second)
                9 to 0, 9 to 2 -> lons = reader.readPackedSint64(field.second)
                else -> reader.skip(field.second)
            }
        }
        val n = minOf(ids.size, lats.size, lons.size)
        var id = 0L
        var lat = 0L
        var lon = 0L
        for (i in 0 until n) {
            id += ids[i]
            lat += lats[i]
            lon += lons[i]
            addNode(id, lat, lon, granularity, latOffset, lonOffset, nodes, bbox)
        }
    }

    private fun parseWay(bytes: ByteArray, strings: List<String>, ways: MutableList<RawWay>) {
        val reader = ProtoReader(bytes, 0, bytes.size)
        var id = 0L
        var keys = IntArray(0)
        var vals = IntArray(0)
        var refs = LongArray(0)
        while (reader.hasRemaining()) {
            when (val field = reader.readKey()) {
                1 to 0 -> id = reader.readVarint()
                2 to 0, 2 to 2 -> keys = reader.readPackedInt32(field.second)
                3 to 0, 3 to 2 -> vals = reader.readPackedInt32(field.second)
                8 to 0, 8 to 2 -> refs = reader.readPackedSint64(field.second)
                else -> reader.skip(field.second)
            }
        }
        val way = RawWay(id)
        var acc = 0L
        for (delta in refs) {
            acc += delta
            way.refs.add(acc)
        }
        val n = minOf(keys.size, vals.size)
        for (i in 0 until n) {
            val key = strings.getOrNull(keys[i]) ?: continue
            val value = strings.getOrNull(vals[i]) ?: continue
            way.tags[key] = value
        }
        ways.add(way)
    }

    private fun addNode(
        id: Long,
        latRaw: Long,
        lonRaw: Long,
        granularity: Int,
        latOffset: Long,
        lonOffset: Long,
        nodes: MutableMap<Long, GraphNode>,
        bbox: Wgs84Bbox?,
    ) {
        val lat = 1e-9 * (latOffset + granularity.toLong() * latRaw)
        val lon = 1e-9 * (lonOffset + granularity.toLong() * lonRaw)
        if (!lat.isFinite() || !lon.isFinite()) return
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        if (bbox != null && !bbox.contains(lat, lon)) return
        nodes[id] = GraphNode(id, LatitudeDeg(lat), LongitudeDeg(lon))
    }

    private fun readInt32Be(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private data class BlobHeader(val type: String, val datasize: Int)

    internal data class RawWay(
        val id: Long,
        val refs: MutableList<Long> = ArrayList(),
        val tags: MutableMap<String, String> = LinkedHashMap(),
    )

    private enum class Oneway { FORWARD, REVERSE, BOTH }
}

enum class OsmFormat { XML, PBF }

private class ProtoReader(
    private val bytes: ByteArray,
    private var pos: Int,
    private val end: Int,
) {
    fun hasRemaining(): Boolean = pos < end

    fun readKey(): Pair<Int, Int> {
        val key = readVarint().toInt()
        return (key ushr 3) to (key and 7)
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (pos < end) {
            val b = bytes[pos].toInt() and 0xff
            pos += 1
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) {
                return result
            }
            shift += 7
            if (shift > 63) {
                error("varint overflow")
            }
        }
        error("truncated varint")
    }

    fun readBytes(): ByteArray {
        val n = readVarint().toInt()
        require(n >= 0 && pos + n <= end) { "truncated bytes" }
        val out = bytes.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun readString(): String = readBytes().toString(Charsets.UTF_8)

    fun readPackedSint64(wire: Int): LongArray {
        if (wire == 0) {
            return longArrayOf(unzigzag(readVarint()))
        }
        val blob = readBytes()
        val inner = ProtoReader(blob, 0, blob.size)
        val out = ArrayList<Long>()
        while (inner.hasRemaining()) {
            out.add(unzigzag(inner.readVarint()))
        }
        return out.toLongArray()
    }

    fun readPackedInt32(wire: Int): IntArray {
        if (wire == 0) {
            return intArrayOf(readVarint().toInt())
        }
        val blob = readBytes()
        val inner = ProtoReader(blob, 0, blob.size)
        val out = ArrayList<Int>()
        while (inner.hasRemaining()) {
            out.add(inner.readVarint().toInt())
        }
        return out.toIntArray()
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> readVarint()
            1 -> pos += 8
            2 -> {
                val n = readVarint().toInt()
                pos += n
            }
            5 -> pos += 4
            else -> error("unknown wire $wire")
        }
    }
}

private fun unzigzag(n: Long): Long = (n ushr 1) xor -(n and 1L)
