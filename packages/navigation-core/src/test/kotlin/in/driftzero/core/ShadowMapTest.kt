package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShadowMapTest {
    @Test
    fun cellKeyIsStableForTheSamePoint() {
        val map = ShadowMap()
        val a = map.cellKey(18.5204, 73.8567)
        val b = map.cellKey(18.5204, 73.8567)
        assertEquals(a, b)
        assertEquals(ShadowMap.CELL_M, map.cellSizeM, 1e-12)
    }

    @Test
    fun occupancyIsLossesOverVisitsAndOmitsMissingCn0() {
        val map = ShadowMap()
        val lat = 18.52
        val lon = 73.85
        map.observe(lat, lon, lost = false, accuracyM = 8.0, cn0DbHz = 32.0)
        map.observe(lat, lon, lost = true, accuracyM = 40.0, cn0DbHz = null)
        map.observe(lat, lon, lost = true, accuracyM = null, cn0DbHz = 18.0)
        val cell = map.cell(lat, lon)!!
        assertEquals(3, cell.visits)
        assertEquals(2, cell.losses)
        assertEquals(2.0 / 3.0, cell.occupancy, 1e-12)
        assertEquals(2.0 / 3.0, map.occupancy(lat, lon)!!, 1e-12)
        assertEquals(24.0, cell.meanAccuracyM!!, 1e-12)
        assertEquals(25.0, cell.meanCn0DbHz!!, 1e-12)
        assertNull(map.occupancy(19.0, 74.0))
    }

    @Test
    fun nearbyPointCanShareCellAndFarPointDoesNot() {
        val map = ShadowMap()
        val seed = map.cellKey(18.52, 73.85)
        val (lat, lon) = map.cellCenter(seed)
        val here = map.cellKey(lat, lon)
        assertEquals(seed, here)
        val (nearLat, nearLon) = Wgs84.offsetMetres(lat, lon, 10.0, 10.0)
        val (farLat, farLon) = Wgs84.offsetMetres(lat, lon, 200.0, 200.0)
        assertEquals(here, map.cellKey(nearLat, nearLon))
        assertNotEquals(here, map.cellKey(farLat, farLon))
    }

    @Test
    fun occupancyAheadFindsLossCellAlongHeading() {
        val map = ShadowMap()
        val lat = 18.52
        val lon = 73.85
        val (aheadLat, aheadLon) = Wgs84.offsetMetres(lat, lon, 100.0, 0.0)
        val (sideLat, sideLon) = Wgs84.offsetMetres(lat, lon, 0.0, 100.0)
        map.observe(aheadLat, aheadLon, lost = true, accuracyM = 40.0)
        map.observe(aheadLat, aheadLon, lost = true, accuracyM = 36.0)
        map.observe(sideLat, sideLon, lost = true, accuracyM = 30.0)
        val ahead = map.occupancyAhead(lat, lon, headingRad = 0.0, lookM = 200.0)
        assertEquals(1.0, ahead!!, 1e-12)
        val east = map.occupancyAhead(lat, lon, headingRad = Math.PI / 2.0, lookM = 200.0)
        assertEquals(1.0, east!!, 1e-12)
        val south = map.occupancyAhead(lat, lon, headingRad = Math.PI, lookM = 200.0)
        assertNull(south)
        assertNull(map.occupancyAhead(lat, lon, headingRad = Double.NaN))
    }

    @Test
    fun storeRoundTripsJson() {
        val dir = File.createTempFile("shadow", "dir").apply {
            delete()
            mkdirs()
        }
        val file = File(dir, ShadowMapStore.FILE_NAME)
        val store = ShadowMapStore(file)
        val map = store.current()
        map.observe(18.5, 73.8, lost = true, accuracyM = 20.0, cn0DbHz = 22.0)
        store.save(map)
        val loaded = ShadowMapStore(file).load()
        assertEquals(1.0, loaded.occupancy(18.5, 73.8)!!, 1e-12)
        assertEquals(22.0, loaded.cell(18.5, 73.8)!!.meanCn0DbHz!!, 1e-12)
    }
}
