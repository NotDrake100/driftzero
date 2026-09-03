package `in`.driftzero.app.maps

import `in`.driftzero.app.ui.StreetMapConfig
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
            assertEquals(
                "https://tiles.openfreemap.org/styles/dark",
                store.styleUri(store.active(), night = true),
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

    @Test
    fun manifestParseKeepsBboxOsmDateAndChecksum() {
        val root = createTempDirectory("area-pack").toFile()
        try {
            val file = File(root, "manifest.json")
            file.writeText(
                """{"id":"pune-core","south":18.46,"west":73.76,"north":18.62,"east":73.96,""" +
                    """"schemaVersion":1,"label":"Pune core","osmSnapshot":"2026-09-01T20:20:50Z",""" +
                    """"pmtilesSha256":"33aa090afa65b6a4441364e758e6fe48194be9769874a27cfefcceb72e0b10a3",""" +
                    """"graphSha256":"4a5074ccc2dcee75483a0dbf6669ffb4d51d6e0e601de2b02f6b2f3a576043b4",""" +
                    """"bytes":12778000}""",
            )
            val parsed = readManifest(file)!!
            assertEquals("pune-core", parsed.id.value)
            assertEquals(18.46, parsed.bbox.southLatDeg, 0.0)
            assertEquals(73.76, parsed.bbox.westLonDeg, 0.0)
            assertEquals("2026-09-01T20:20:50Z", parsed.osmSnapshot)
            assertEquals("33aa090afa65b6a4441364e758e6fe48194be9769874a27cfefcceb72e0b10a3", parsed.pmtilesSha256)
            assertEquals(12_778_000L, parsed.bytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun puneManifestOnDiskParsesWhenPresent() {
        val candidates = listOf(
            File("data/area-packs/pune-core/manifest.json"),
            File("../data/area-packs/pune-core/manifest.json"),
            File("../../../../data/area-packs/pune-core/manifest.json"),
        )
        val file = candidates.firstOrNull { it.isFile } ?: return
        val parsed = readManifest(file)!!
        assertEquals("pune-core", parsed.id.value)
        assertEquals("2026-09-01T20:20:50Z", parsed.osmSnapshot)
        assertEquals("33aa090afa65b6a4441364e758e6fe48194be9769874a27cfefcceb72e0b10a3", parsed.pmtilesSha256)
    }

    @Test
    fun checksumMismatchIsCorruptNotReady() {
        val root = createTempDirectory("area-pack").toFile()
        try {
            val store = AreaPackStore(root)
            val bbox = GeoBbox.of(18.46, 73.76, 18.62, 73.96)!!
            val source = File(root, "incoming").apply { mkdirs() }
            val manifest = AreaPackManifest(
                id = AreaPackId("pune-core"),
                bbox = bbox,
                osmSnapshot = "2026-09-01T20:20:50Z",
                pmtilesSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                graphSha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            )
            File(source, AreaPackStore.MANIFEST).writeText(manifestJson(manifest, AreaPackState.Queued))
            File(source, AreaPackStore.TILES).writeText("pmtiles-bytes")
            File(source, "graph.osm.pbf").writeText("graph-bytes")
            val result = store.installSideload(source)!!
            assertEquals(AreaPackState.Corrupt, result.state)
            assertEquals(AreaPackState.Corrupt, store.installed().single().state)
            assertNull(store.active())
            assertNull(store.resolvedStyleJson(store.installed().single()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun coveringStyleUsesTilesWithoutWaitingOnChecksum() {
        val root = createTempDirectory("area-pack").toFile()
        try {
            val store = AreaPackStore(root)
            val bbox = GeoBbox.of(18.46, 73.76, 18.62, 73.96)!!
            val dest = File(root, "installed/pune-core").apply { mkdirs() }
            val manifest = AreaPackManifest(
                id = AreaPackId("pune-core"),
                bbox = bbox,
                pmtilesSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            )
            File(dest, AreaPackStore.MANIFEST).writeText(manifestJson(manifest, AreaPackState.Ready))
            File(dest, AreaPackStore.TILES).writeText("pmtiles-bytes")
            File(dest, AreaPackStore.GRAPH).writeText("graph-bin")
            File(dest, AreaPackStore.STYLE_DAY).writeText(
                """{"sources":{"openmaptiles":{"type":"vector","url":"pmtiles://tiles.pmtiles"}},""" +
                    """"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"}""",
            )
            assertTrue(store.coversPosition(18.51, 73.85))
            assertFalse(store.coversPosition(0.0, 0.0))
            val json = store.coveringStyleJson(18.51, 73.85, night = false)!!
            assertTrue(json.contains(StreetMapConfig.pmtilesFileUri(File(dest, AreaPackStore.TILES))))
            assertFalse(json.contains("pmtiles://tiles.pmtiles"))
            assertEquals(AreaPackState.Corrupt, store.installed().single().state)
            assertNull(store.resolvedStyleJson(store.installed().single()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolvedStyleUsesAbsolutePmtilesAndLocalGlyphs() {
        val root = createTempDirectory("area-pack").toFile()
        try {
            val store = AreaPackStore(root)
            val bbox = GeoBbox.of(18.46, 73.76, 18.62, 73.96)!!
            val source = File(root, "incoming").apply { mkdirs() }
            val manifest = AreaPackManifest(id = AreaPackId("pune-core"), bbox = bbox)
            File(source, AreaPackStore.MANIFEST).writeText(manifestJson(manifest, AreaPackState.Queued))
            File(source, AreaPackStore.TILES).writeText("pmtiles-bytes")
            File(source, "graph.osm.pbf").writeText("graph-bytes")
            File(source, AreaPackStore.STYLE_DAY).writeText(
                """{"sources":{"openmaptiles":{"type":"vector","url":"pmtiles://tiles.pmtiles"}},""" +
                    """"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"}""",
            )
            File(source, AreaPackStore.STYLE_NIGHT).writeText(
                """{"sources":{"openmaptiles":{"type":"vector","url":"pmtiles://tiles.pmtiles"}},""" +
                    """"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"}""",
            )
            File(source, "glyphs/Noto Sans Regular").mkdirs()
            File(source, "glyphs/Noto Sans Regular/0-255.pbf").writeText("glyph")
            val result = store.installSideload(source)!!
            assertEquals(AreaPackState.Ready, result.state)
            val json = store.resolvedStyleJson(store.active(), night = false)!!
            val tiles = File(root, "installed/pune-core/tiles.pmtiles")
            assertTrue(json.contains(StreetMapConfig.pmtilesFileUri(tiles)))
            assertTrue(json.contains("/glyphs/{fontstack}/{range}.pbf"))
            assertFalse(json.contains("pmtiles://tiles.pmtiles"))
            val night = store.resolvedStyleJson(store.active(), night = true)!!
            assertTrue(night.contains(StreetMapConfig.pmtilesFileUri(tiles)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zipSideloadBecomesReady() {
        val root = createTempDirectory("area-pack").toFile()
        try {
            val store = AreaPackStore(root)
            val bbox = GeoBbox.of(1.2, 103.6, 1.5, 104.1)!!
            val source = File(root, "incoming/singapore-sample").apply { mkdirs() }
            val manifest = AreaPackManifest(id = AreaPackId("singapore-sample"), bbox = bbox)
            File(source, AreaPackStore.MANIFEST).writeText(manifestJson(manifest, AreaPackState.Queued))
            File(source, AreaPackStore.TILES).writeText("pmtiles-bytes")
            File(source, "graph.osm.xml").writeText("<osm version=\"0.6\"></osm>")
            val zip = File(root, "singapore-sample.zip")
            zipPack(source, zip)
            val result = AreaPackImport.installFromZip(store, zip)!!
            assertEquals(AreaPackState.Ready, result.state)
            assertEquals("singapore-sample", store.active()?.manifest?.id?.value)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sha256HexIsUnsigned() {
        val root = createTempDirectory("area-pack").toFile()
        try {
            val file = File(root, "hello.txt")
            file.writeText("hello")
            assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                sha256Hex(file),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun checksumResultIsKeyedBySizeAndMtime() {
        val root = createTempDirectory("area-pack").toFile()
        try {
            val file = File(root, "tiles.pmtiles")
            file.writeText("pmtiles-bytes")
            val expected = sha256Hex(file)
            assertTrue(checksumMatches(file, expected))
            val sidecar = File(root, "tiles.pmtiles.sha256-ok")
            assertTrue(sidecar.isFile)
            val token = sidecar.readText()
            assertTrue(token.contains("|${file.length()}|"))
            assertTrue(token.contains("|${file.lastModified()}|"))
            assertTrue(checksumMatches(file, expected))
            assertFalse(checksumMatches(file, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zipSlipIsRejected() {
        val root = createTempDirectory("area-pack").toFile()
        try {
            val zip = File(root, "evil.zip")
            java.util.zip.ZipOutputStream(zip.outputStream()).use { stream ->
                stream.putNextEntry(java.util.zip.ZipEntry("../evil.txt"))
                stream.write("no".toByteArray())
                stream.closeEntry()
            }
            try {
                AreaPackImport.unzip(zip, File(root, "out").apply { mkdirs() })
                org.junit.Assert.fail("zip slip")
            } catch (_: IllegalArgumentException) {
            }
            assertFalse(File(root, "evil.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}

private fun zipPack(source: File, zip: File) {
    java.util.zip.ZipOutputStream(zip.outputStream()).use { stream ->
        source.walkTopDown().filter { it.isFile }.forEach { file ->
        val parent = source.parentFile ?: source
        val rel = file.relativeTo(parent).invariantSeparatorsPath
            stream.putNextEntry(java.util.zip.ZipEntry(rel))
            file.inputStream().use { it.copyTo(stream) }
            stream.closeEntry()
        }
    }
}
