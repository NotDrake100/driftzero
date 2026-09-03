package `in`.driftzero.core

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Compact OSM PBF writer for [graph.bin]. [OsmGraphLoader.load] detects PBF
 * from the blob header, so the live matcher can read this file. Each directed
 * [GraphEdge] becomes one oneway way with layer, bridge, tunnel, highway,
 * and name tags.
 */
object OsmPbfWriter {
    fun write(
        nodes: List<Triple<Long, Double, Double>>,
        ways: List<PbfWay>,
    ): ByteArray {
        val strings = LinkedHashSet<String>().apply {
            add("")
            ways.forEach { way ->
                way.tags.forEach { (k, v) ->
                    add(k)
                    add(v)
                }
            }
        }.toList()
        val index = strings.withIndex().associate { it.value to it.index }
        val group = ByteArrayOutputStream()
        for ((id, lat, lon) in nodes) {
            val node = ByteArrayOutputStream()
            writeSint(node, 1, id)
            writeSint(node, 8, (lat * 1e7).toLong())
            writeSint(node, 9, (lon * 1e7).toLong())
            writeLen(group, 1, node.toByteArray())
        }
        for (way in ways) {
            val msg = ByteArrayOutputStream()
            writeVarintField(msg, 1, way.id)
            writePackedU32(msg, 2, way.tags.keys.map { index.getValue(it) })
            writePackedU32(msg, 3, way.tags.values.map { index.getValue(it) })
            var prev = 0L
            val deltas = way.refs.map { ref ->
                val d = ref - prev
                prev = ref
                d
            }
            writePackedSint(msg, 8, deltas)
            writeLen(group, 3, msg.toByteArray())
        }
        val table = ByteArrayOutputStream()
        for (s in strings) {
            writeLen(table, 1, s.toByteArray(Charsets.UTF_8))
        }
        val block = ByteArrayOutputStream()
        writeLen(block, 1, table.toByteArray())
        writeLen(block, 2, group.toByteArray())
        val raw = block.toByteArray()
        val compressed = zlib(raw)
        val blob = ByteArrayOutputStream()
        writeVarintField(blob, 2, raw.size.toLong())
        writeLen(blob, 3, compressed)
        val blobBytes = blob.toByteArray()
        val header = ByteArrayOutputStream()
        writeLen(header, 1, "OSMData".toByteArray(Charsets.UTF_8))
        writeVarintField(header, 3, blobBytes.size.toLong())
        val headerBytes = header.toByteArray()
        val out = ByteArrayOutputStream()
        writeInt32Be(out, headerBytes.size)
        out.write(headerBytes)
        out.write(blobBytes)
        return out.toByteArray()
    }

    /**
     * Directed edges as oneway OSM ways. Intermediate polyline vertices
     * get fresh node ids. [namesByOsmWay] is keyed by the original OSM way.
     */
    fun fromGraph(graph: RoadGraph, namesByOsmWay: Map<Long, String> = emptyMap()): ByteArray {
        val nodeCoords = LinkedHashMap<Long, Pair<Double, Double>>()
        for (node in graph.nodes.values) {
            nodeCoords[node.id] = node.latitude.value to node.longitude.value
        }
        var nextId = (nodeCoords.keys.maxOrNull() ?: 0L) + 1L
        val ways = ArrayList<PbfWay>(graph.edges.size)
        graph.edges.forEachIndexed { index, edge ->
            val refs = ArrayList<Long>(edge.points.size)
            edge.points.forEachIndexed { pi, point ->
                val lat = point.latitude.value
                val lon = point.longitude.value
                val reuse = when (pi) {
                    0 -> edge.fromNodeId
                    edge.points.lastIndex -> edge.toNodeId
                    else -> null
                }
                if (reuse != null) {
                    nodeCoords.putIfAbsent(reuse, lat to lon)
                    refs.add(reuse)
                } else {
                    val id = nextId
                    nextId += 1
                    nodeCoords[id] = lat to lon
                    refs.add(id)
                }
            }
            val tags = LinkedHashMap<String, String>()
            tags["highway"] = edge.highway
            tags["oneway"] = "yes"
            if (edge.layer != 0) {
                tags["layer"] = edge.layer.toString()
            }
            if (edge.bridge) {
                tags["bridge"] = "yes"
            }
            if (edge.tunnel) {
                tags["tunnel"] = "yes"
            }
            val name = namesByOsmWay[edge.osmWayId]
            if (!name.isNullOrEmpty()) {
                tags["name"] = name
            }
            ways += PbfWay(id = index + 1L, refs = refs, tags = tags)
        }
        val nodes = nodeCoords.map { (id, ll) -> Triple(id, ll.first, ll.second) }
        return write(nodes, ways)
    }

    /** OSM way id to `name` tag. Skips nodes so a 2 MB extract stays cheap. */
    fun readWayNames(bytes: ByteArray): Map<Long, String> {
        val names = HashMap<Long, String>()
        var offset = 0
        while (offset + 4 <= bytes.size) {
            val headerLen = readInt32Be(bytes, offset)
            offset += 4
            if (headerLen <= 0 || offset + headerLen > bytes.size) {
                break
            }
            val (blobType, datasize) = parseBlobHeader(bytes, offset, headerLen)
            offset += headerLen
            if (datasize <= 0 || offset + datasize > bytes.size) {
                break
            }
            val payload = if (blobType == "OSMData") {
                parseBlob(bytes, offset, datasize)
            } else {
                null
            }
            offset += datasize
            if (payload == null) {
                continue
            }
            collectNames(payload, names)
        }
        return names
    }

    private fun collectNames(payload: ByteArray, names: MutableMap<Long, String>) {
        val reader = BinProtoReader(payload, 0, payload.size)
        var strings = emptyList<String>()
        val groups = ArrayList<ByteArray>()
        while (reader.hasRemaining()) {
            when (val field = reader.readKey()) {
                1 to 2 -> strings = parseStringTable(reader.readBytes())
                2 to 2 -> groups.add(reader.readBytes())
                else -> reader.skip(field.second)
            }
        }
        for (group in groups) {
            val inner = BinProtoReader(group, 0, group.size)
            while (inner.hasRemaining()) {
                when (val field = inner.readKey()) {
                    1 to 2 -> inner.readBytes()
                    2 to 2 -> inner.readBytes()
                    3 to 2 -> parseWayName(inner.readBytes(), strings, names)
                    else -> inner.skip(field.second)
                }
            }
        }
    }

    private fun parseStringTable(bytes: ByteArray): List<String> {
        val reader = BinProtoReader(bytes, 0, bytes.size)
        val out = ArrayList<String>()
        while (reader.hasRemaining()) {
            when (val field = reader.readKey()) {
                1 to 2 -> out.add(reader.readString())
                else -> reader.skip(field.second)
            }
        }
        return out
    }

    private fun parseWayName(bytes: ByteArray, strings: List<String>, names: MutableMap<Long, String>) {
        val reader = BinProtoReader(bytes, 0, bytes.size)
        var id = 0L
        var keys = IntArray(0)
        var vals = IntArray(0)
        while (reader.hasRemaining()) {
            when (val field = reader.readKey()) {
                1 to 0 -> id = reader.readVarint()
                2 to 0, 2 to 2 -> keys = reader.readPackedInt32(field.second)
                3 to 0, 3 to 2 -> vals = reader.readPackedInt32(field.second)
                8 to 0, 8 to 2 -> reader.readPackedInt32(field.second)
                else -> reader.skip(field.second)
            }
        }
        val n = minOf(keys.size, vals.size)
        for (i in 0 until n) {
            if (strings.getOrNull(keys[i]) != "name") {
                continue
            }
            val value = strings.getOrNull(vals[i]) ?: continue
            if (value.isNotEmpty()) {
                names[id] = value
            }
        }
    }

    private fun parseBlobHeader(bytes: ByteArray, start: Int, length: Int): Pair<String, Int> {
        val reader = BinProtoReader(bytes, start, start + length)
        var type = ""
        var datasize = 0
        while (reader.hasRemaining()) {
            when (val field = reader.readKey()) {
                1 to 2 -> type = reader.readString()
                3 to 0 -> datasize = reader.readVarint().toInt()
                else -> reader.skip(field.second)
            }
        }
        return type to datasize
    }

    private fun parseBlob(bytes: ByteArray, start: Int, length: Int): ByteArray? {
        val reader = BinProtoReader(bytes, start, start + length)
        var raw: ByteArray? = null
        var zlib: ByteArray? = null
        while (reader.hasRemaining()) {
            when (val field = reader.readKey()) {
                1 to 2 -> raw = reader.readBytes()
                2 to 0 -> reader.readVarint()
                3 to 2 -> zlib = reader.readBytes()
                else -> reader.skip(field.second)
            }
        }
        if (raw != null) {
            return raw
        }
        val compressed = zlib ?: return null
        return java.util.zip.InflaterInputStream(compressed.inputStream()).use { it.readBytes() }
    }

    private fun zlib(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { stream ->
            stream.write(raw)
        }
        return out.toByteArray()
    }

    private fun writeInt32Be(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    private fun readInt32Be(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private fun writeKey(out: ByteArrayOutputStream, field: Int, wire: Int) {
        writeVarint(out, ((field shl 3) or wire).toLong())
    }

    private fun writeVarint(out: ByteArrayOutputStream, raw: Long) {
        var value = raw
        while (true) {
            if (value and 0x7fL.inv() == 0L) {
                out.write(value.toInt())
                return
            }
            out.write(((value and 0x7fL).toInt()) or 0x80)
            value = value ushr 7
        }
    }

    private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
        writeKey(out, field, 0)
        writeVarint(out, value)
    }

    private fun writeSint(out: ByteArrayOutputStream, field: Int, value: Long) {
        writeVarintField(out, field, (value shl 1) xor (value shr 63))
    }

    private fun writeLen(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) {
        writeKey(out, field, 2)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writePackedU32(out: ByteArrayOutputStream, field: Int, values: List<Int>) {
        val inner = ByteArrayOutputStream()
        values.forEach { writeVarint(inner, it.toLong()) }
        writeLen(out, field, inner.toByteArray())
    }

    private fun writePackedSint(out: ByteArrayOutputStream, field: Int, values: List<Long>) {
        val inner = ByteArrayOutputStream()
        values.forEach { writeVarint(inner, (it shl 1) xor (it shr 63)) }
        writeLen(out, field, inner.toByteArray())
    }
}

data class PbfWay(
    val id: Long,
    val refs: List<Long>,
    val tags: Map<String, String>,
)

private class BinProtoReader(
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

    fun readPackedInt32(wire: Int): IntArray {
        if (wire == 0) {
            return intArrayOf(readVarint().toInt())
        }
        val blob = readBytes()
        val inner = BinProtoReader(blob, 0, blob.size)
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
                if (n < 0 || pos + n > end) {
                    pos = end
                    return
                }
                pos += n
            }
            3 -> {
                while (hasRemaining()) {
                    val inner = readKey().second
                    if (inner == 4) {
                        return
                    }
                    skip(inner)
                }
            }
            4 -> Unit
            5 -> pos += 4
            else -> pos = end
        }
    }
}
