package `in`.driftzero.app.trips

import `in`.driftzero.core.ContractWrite
import `in`.driftzero.core.GnssMaskInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class TripStoreTest {
    @Test
    fun listMapsManifestsAndDeleteRemovesOne() {
        val root = createTempDirectory("trip-store").toFile()
        try {
            val store = TripStore(root)
            writeManifest(
                File(root, "trip-100"),
                id = "trip-100",
                wallMs = 100L,
                startNs = 0L,
                endNs = 5_000_000_000L,
                distanceM = 1200.0,
                stateCount = 50,
                drCount = 7,
            )
            writeManifest(
                File(root, "trip-200"),
                id = "trip-200",
                wallMs = 200L,
                startNs = 0L,
                endNs = 2_000_000_000L,
                distanceM = 400.0,
                stateCount = 20,
                drCount = 10,
                holds = listOf(GnssMaskInterval(1_000_000_000L, 2_000_000_000L)),
            )
            val listed = store.list()
            assertEquals(listOf("trip-200", "trip-100"), listed.map { it.id })
            assertEquals(50, listed[0].drPercent)
            assertEquals(1, listed[0].holds.size)
            assertEquals(1_000_000_000L, listed[0].holds[0].startNs)
            assertTrue(store.delete("trip-100"))
            assertEquals(listOf("trip-200"), store.list().map { it.id })
            assertFalse(File(root, "trip-100").exists())
            assertEquals(1, store.deleteAll())
            assertTrue(store.list().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exportZipContainsContractFiles() {
        val root = createTempDirectory("trip-zip").toFile()
        try {
            val dir = File(root, "trip-9")
            dir.mkdirs()
            File(dir, TripRecorder.SENSORS).writeText("{\"declared_rate_hz\":50}\n")
            File(dir, TripRecorder.STATES).writeText("{}\n")
            File(dir, TripRecorder.MANIFEST).writeText("{\"id\":\"trip-9\"}\n")
            val trip = TripSummary(
                id = "trip-9",
                dir = dir,
                startWallMs = 9L,
                startNs = 0L,
                endNs = 1L,
                distanceM = 0.0,
                stateCount = 0,
                drCount = 0,
                holds = emptyList(),
            )
            val zip = TripStore(root).exportZip(trip, File(root, "out/trip-9.zip"))
            assertTrue(zip.isFile && zip.length() > 0L)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeManifest(
        dir: File,
        id: String,
        wallMs: Long,
        startNs: Long,
        endNs: Long,
        distanceM: Double,
        stateCount: Int,
        drCount: Int,
        holds: List<GnssMaskInterval> = emptyList(),
    ) {
        dir.mkdirs()
        File(dir, TripRecorder.SENSORS).writeText("{\"declared_rate_hz\":50}\n")
        val summary = TripSummary(
            id = id,
            dir = dir,
            startWallMs = wallMs,
            startNs = startNs,
            endNs = endNs,
            distanceM = distanceM,
            stateCount = stateCount,
            drCount = drCount,
            holds = holds,
        )
        File(dir, TripRecorder.MANIFEST).writeText(ContractWrite.stringify(summary.toManifest()))
    }
}
