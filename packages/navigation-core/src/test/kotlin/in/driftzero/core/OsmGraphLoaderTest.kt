package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class OsmGraphLoaderTest {
    @Test
    fun londonXmlIsNotPuneLocked() {
        val xml = RoadFixtures.parallelOsmXml()
        val graph = OsmGraphLoader.loadXml(xml, "london-parallel")
        assertEquals("london-parallel", graph.packageId)
        assertEquals(2, graph.edges.size)
        assertTrue(graph.edges.all { it.highway == "residential" })
        val ids = graph.edges.map { it.id }.toSet()
        assertTrue(ids.any { it.startsWith("way/10") })
        assertTrue(ids.any { it.startsWith("way/11") })
        val lat = graph.nodes.values.first().latitude.value
        assertTrue(lat in 51.4..51.6)
    }

    @Test
    fun tokyoBboxDropsLondonWays() {
        val xml = RoadFixtures.parallelOsmXml()
        val tokyo = Wgs84Bbox(35.6, 139.6, 35.8, 139.9)
        val graph = OsmGraphLoader.loadXml(xml, "tokyo-clip", tokyo)
        assertTrue(graph.isEmpty())
    }

    @Test
    fun equatorExtractLoads() {
        val xml = RoadFixtures.parallelOsmXml(originLat = 0.0, originLon = 10.0)
        val box = Wgs84Bbox(-0.01, 9.99, 0.02, 10.02)
        val graph = OsmGraphLoader.loadXml(xml, "equator-sample", box)
        assertEquals(2, graph.edges.size)
    }

    @Test
    fun bidirectionalWayEmitsTwoDirectedEdges() {
        val xml = RoadFixtures.parallelOsmXml(oneway = false)
        val graph = OsmGraphLoader.loadXml(xml, "two-way")
        assertEquals(4, graph.edges.size)
        assertTrue(graph.edges.any { it.id.endsWith(":fwd") })
        assertTrue(graph.edges.any { it.id.endsWith(":rev") })
    }

    @Test
    fun footwayIsIgnored() {
        val xml = """
            <?xml version="1.0"?>
            <osm version="0.6">
              <node id="1" lat="40.7" lon="-74.0"/>
              <node id="2" lat="40.701" lon="-74.0"/>
              <way id="9">
                <nd ref="1"/><nd ref="2"/>
                <tag k="highway" v="footway"/>
              </way>
            </osm>
        """.trimIndent()
        val graph = OsmGraphLoader.loadXml(xml, "foot")
        assertTrue(graph.isEmpty())
    }

    @Test
    fun pbfRoundTripMatchesXmlEdgeCount() {
        val nodes = listOf(
            Triple(1L, 51.5, -0.12),
            Triple(2L, 51.5018, -0.12),
            Triple(3L, 51.5, -0.1197),
            Triple(4L, 51.5018, -0.1197),
        )
        val ways = listOf(
            PbfWay(10L, listOf(1L, 2L), mapOf("highway" to "residential", "oneway" to "yes")),
            PbfWay(11L, listOf(3L, 4L), mapOf("highway" to "residential", "oneway" to "yes")),
        )
        val bytes = OsmPbfWriter.write(nodes, ways)
        val graph = OsmGraphLoader.load(bytes, "pbf-london", OsmFormat.PBF)
        assertEquals(2, graph.edges.size)
        assertTrue(graph.nodes.isNotEmpty())
    }

    @Test
    fun matcherRunsOnLoadedXml() {
        val graph = OsmGraphLoader.loadXml(RoadFixtures.parallelOsmXml(), "osm-match")
        val matcher = HmmRoadMatcher()
        val states = (1..6).map { i ->
            RoadFixtures.atOffset(i * 20.0, 2.0, timestampNs = i * 1_000_000_000L)
        }
        val path = matcher.matchSequence(states, graph)
        assertTrue(path.all { it.match.status == MapMatchStatus.MATCHED })
        assertTrue(path.map { it.match.roadSegmentId }.distinct().size == 1)
    }
}

private data class PbfWay(
    val id: Long,
    val refs: List<Long>,
    val tags: Map<String, String>,
)

private object OsmPbfWriter {
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
        val blob = ByteArrayOutputStream()
        writeLen(blob, 1, block.toByteArray())
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

    private fun writeInt32Be(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
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
