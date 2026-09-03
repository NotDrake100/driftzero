package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGraphPolicyTest {
    @Test
    fun routeWindowIsUnionOfPadsNotOriginOnly() {
        val originLat = 12.0
        val originLon = 77.0
        val dest = Wgs84.offsetMetres(originLat, originLon, 2_000.0, 0.0)
        val originPad = LocalGraphPolicy.routeWindow(originLat, originLon, null, null, 500.0)!!
        val union = LocalGraphPolicy.routeWindow(
            originLat, originLon, dest.first, dest.second, 500.0,
        )!!
        assertTrue(originPad.contains(originLat, originLon))
        assertFalse(originPad.contains(dest.first, dest.second))
        assertTrue(union.contains(originLat, originLon))
        assertTrue(union.contains(dest.first, dest.second))
        assertTrue(union.northLatDeg > originPad.northLatDeg)
    }

    @Test
    fun fourMegPackStaysWindowedOnEmulatorHeap() {
        val packBytes = 4_500_000L
        val emulatorHeap = 512L * 1024L * 1024L
        assertFalse(LocalGraphPolicy.canLoadFull(packBytes, emulatorHeap))
        val originLat = 18.51091
        val originLon = 73.8851
        val destLat = 18.51808
        val destLon = 73.8677
        val clip = LocalGraphPolicy.clipForLoad(
            packBytes, emulatorHeap, originLat, originLon, destLat, destLon,
        )
        val expected = LocalGraphPolicy.routeWindow(originLat, originLon, destLat, destLon)
        assertEquals(expected, clip)
        assertNotNull(clip)
        assertTrue(clip!!.contains(originLat, originLon))
        assertTrue(clip.contains(destLat, destLon))
    }

    @Test
    fun smallPackLoadsWholeOnPhoneHeap() {
        val packBytes = 50_000L
        val phoneHeap = 512L * 1024L * 1024L
        assertTrue(LocalGraphPolicy.canLoadFull(packBytes, phoneHeap))
        assertNull(
            LocalGraphPolicy.clipForLoad(
                packBytes, phoneHeap, 12.0, 77.0, 12.02, 77.0,
            ),
        )
    }

    @Test
    fun sessionReloadsWhenDestLeavesOriginPad() {
        val originLat = 12.0
        val originLon = 77.0
        val dest = Wgs84.offsetMetres(originLat, originLon, 2_000.0, 0.0)
        val bytes = northCorridorPbf(originLat, originLon, lengthM = 2_400.0, stepM = 200.0)
        val session = LocalGraphSession(bytes, "corridor-session", maxHeapBytes = bytes.size.toLong())
        val originRouter = session.covering(originLat, originLon, null, null)
        assertNotNull(originRouter)
        assertNull(originRouter!!.route(originLat, originLon, dest.first, dest.second))
        val destRouter = session.covering(originLat, originLon, dest.first, dest.second)
        assertNotNull(destRouter)
        val built = destRouter!!.route(originLat, originLon, dest.first, dest.second)
        assertNotNull(built)
        assertTrue(built!!.points.size >= 2)
    }

    @Test
    fun coversTreatsFullPackAsSuperset() {
        val pad = LocalGraphPolicy.routeWindow(12.0, 77.0, null, null, 500.0)
        assertTrue(LocalGraphPolicy.covers(null, pad))
        assertFalse(LocalGraphPolicy.covers(pad, null))
        assertTrue(LocalGraphPolicy.covers(pad, pad))
    }
}
