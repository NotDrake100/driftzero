package `in`.driftzero.app.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class GeoBboxTest {
    @Test
    fun validBoxRoundTrips() {
        val box = GeoBbox.of(40.70, -74.05, 40.85, -73.90)!!
        assertTrue(box.contains(40.76, -73.98))
        assertFalse(box.contains(51.5, -0.12))
        assertEquals(0.15, box.spanLatDeg(), 0.00001)
    }

    @Test
    fun invertedOrWorldWrapRejected() {
        assertNull(GeoBbox.of(40.0, -73.0, 39.0, -74.0))
        assertNull(GeoBbox.of(10.0, 170.0, 20.0, -170.0))
        assertNull(GeoBbox.of(Double.NaN, 0.0, 1.0, 1.0))
    }

    @Test
    fun aroundCenterIsGeneric() {
        val box = GeoBbox.around(35.68, 139.76, 0.2)!!
        assertTrue(box.contains(35.68, 139.76))
        assertFalse(box.contains(18.53, 73.85))
    }
}

class AreaPackStoreTest {
    @Test
    fun queueIsAddressedByBboxNotACityName() {
        val root = createTempDirectory("area-pack").toFile()
        try {
            val store = AreaPackStore(root)
            val nyc = GeoBbox.of(40.70, -74.05, 40.85, -73.90)!!
            val queued = store.queue(nyc)
            assertEquals(AreaPackState.Queued, queued.state)
            assertTrue(queued.manifest.id.value.startsWith("bbox-"))
            assertFalse(queued.manifest.id.value.contains("pune"))
            assertEquals(1, store.queued().size)
            assertTrue(store.installed().isEmpty())
            assertNull(store.active())
            assertEquals(
                "https://tiles.openfreemap.org/styles/liberty",
                store.styleUri(store.active()),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun indiaExtractIsCatalogExampleNotTheApi() {
        assertEquals("example-india", AreaPackCatalog.EXAMPLE_INDIA.id.value)
        assertTrue(AreaPackCatalog.EXAMPLE_INDIA_BBOX.contains(28.61, 77.21))
        assertTrue(AreaPackCatalog.EXAMPLE_INDIA_BBOX.contains(18.52, 73.85))
        val london = GeoBbox.of(51.4, -0.3, 51.6, 0.1)!!
        assertNotEquals(AreaPackCatalog.EXAMPLE_INDIA.id, AreaPackId.fromBbox(london))
    }

    @Test
    fun sideloadWithoutTilesStaysQueued() {
        val root = createTempDirectory("area-pack").toFile()
        try {
            val store = AreaPackStore(root)
            val bbox = GeoBbox.of(12.8, 77.4, 13.2, 77.8)!!
            val source = File(root, "incoming").apply { mkdirs() }
            val manifest = AreaPackManifest(id = AreaPackId("bengaluru-sample"), bbox = bbox)
            File(source, AreaPackStore.MANIFEST).writeText(manifestJson(manifest, AreaPackState.Queued))
            val result = store.installSideload(source)!!
            assertEquals(AreaPackState.Queued, result.state)
            assertEquals("bengaluru-sample", result.manifest.id.value)
            assertTrue(store.queued().any { it.manifest.id.value == "bengaluru-sample" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sideloadWithTilesAndGraphBecomesReady() {
        val root = createTempDirectory("area-pack").toFile()
        try {
            val store = AreaPackStore(root)
            val bbox = GeoBbox.of(1.2, 103.6, 1.5, 104.1)!!
            val source = File(root, "incoming").apply { mkdirs() }
            val manifest = AreaPackManifest(id = AreaPackId("singapore-sample"), bbox = bbox)
            File(source, AreaPackStore.MANIFEST).writeText(manifestJson(manifest, AreaPackState.Queued))
            File(source, AreaPackStore.TILES).writeText("pmtiles-bytes")
            File(source, "graph.osm.xml").writeText("<osm version=\"0.6\"></osm>")
            File(source, AreaPackStore.STYLE).writeText("""{"version":8,"sources":{},"layers":[]}""")
            val result = store.installSideload(source)!!
            assertEquals(AreaPackState.Ready, result.state)
            assertEquals(result.manifest.id, store.active()?.manifest?.id)
            assertEquals("graph.osm.xml", result.graphFileName)
            assertTrue(store.graphFile(store.active())!!.name == "graph.osm.xml")
            val uri = store.styleUri(store.active())
            assertTrue(uri.startsWith("file://"))
            assertTrue(uri.endsWith("style.json"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingSideloadManifestIsNull() {
        val root = createTempDirectory("area-pack").toFile()
        try {
            val store = AreaPackStore(root)
            assertNull(store.installSideload(File(root, "empty").apply { mkdirs() }))
        } finally {
            root.deleteRecursively()
        }
    }
}
