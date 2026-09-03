package `in`.driftzero.app.maps

import `in`.driftzero.core.OsmPbfWriter
import `in`.driftzero.core.PbfWay
import `in`.driftzero.core.RoadGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LocalGraphPolicyTest {
    @Test
    fun compactBinKeepsWayNames() {
        val bytes = OsmPbfWriter.write(
            listOf(
                Triple(1L, 18.50, 73.80),
                Triple(2L, 18.51, 73.80),
            ),
            listOf(
                PbfWay(
                    10L,
                    listOf(1L, 2L),
                    mapOf("highway" to "residential", "name" to "High Street"),
                ),
            ),
        )
        val dir = createTempDirectory("local-graph").toFile()
        try {
            val file = File(dir, "graph.bin")
            file.writeBytes(bytes)
            assertTrue(LocalGraphPolicy.isCompactBin(file.name))
            val (graph, names) = LocalGraphPolicy.loadGraphAndNames(file, "named-road")
            val router = LocalGraphPolicy.routerOf(graph, names)
            assertNotNull(router)
            assertEquals("High Street", router!!.nameOf(graph.edges.first()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun xmlSideloadLoadsTopologyWithoutInventingNames() {
        val xml =
            """
            <?xml version="1.0"?>
            <osm version="0.6">
              <node id="1" lat="18.50" lon="73.80"/>
              <node id="2" lat="18.51" lon="73.80"/>
              <way id="10">
                <nd ref="1"/><nd ref="2"/>
                <tag k="highway" v="residential"/>
                <tag k="name" v="High Street"/>
              </way>
            </osm>
            """.trimIndent()
        val dir = createTempDirectory("local-graph-xml").toFile()
        try {
            val file = File(dir, "graph.osm.xml")
            file.writeText(xml)
            assertFalse(LocalGraphPolicy.isCompactBin(file.name))
            val (graph, names) = LocalGraphPolicy.loadGraphAndNames(file, "xml-road")
            assertTrue(graph.edges.isNotEmpty())
            assertTrue(names.isEmpty())
            val router = LocalGraphPolicy.routerOf(graph, names)
            assertNotNull(router)
            assertNull(router!!.nameOf(graph.edges.first()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyGraphHasNoRouter() {
        assertNull(LocalGraphPolicy.routerOf(RoadGraph.empty("empty"), emptyMap()))
        assertTrue(LocalGraphPolicy.isCompactBin("graph.osm.pbf"))
        assertFalse(LocalGraphPolicy.isCompactBin("graph.osm.xml"))
    }
}
