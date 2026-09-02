package `in`.driftzero.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavicMonitorTest {
    @Test
    fun androidConstellationIntsMatchContractNames() {
        assertEquals("GPS", NavicMonitor.constellationName(NavicMonitor.CONSTELLATION_GPS))
        assertEquals("GALILEO", NavicMonitor.constellationName(NavicMonitor.CONSTELLATION_GALILEO))
        assertEquals("IRNSS", NavicMonitor.constellationName(NavicMonitor.CONSTELLATION_IRNSS))
        assertEquals("BEIDOU", NavicMonitor.constellationName(NavicMonitor.CONSTELLATION_BEIDOU))
        assertEquals("UNKNOWN", NavicMonitor.constellationName(NavicMonitor.CONSTELLATION_UNKNOWN))
        assertEquals("UNKNOWN", NavicMonitor.constellationName(99))
    }

    @Test
    fun gpsOnlyHasNoChipAndNoLog() {
        val snapshot = NavicMonitor.tally(
            listOf(
                GnssSatRow("GPS", usedInFix = true),
                GnssSatRow("GPS", usedInFix = true),
                GnssSatRow("GPS", usedInFix = false),
            ),
        )
        assertEquals(2, snapshot.gpsUsed)
        assertEquals(0, snapshot.galileoUsed)
        assertEquals(0, snapshot.navicUsed)
        assertEquals(0, snapshot.navicVisible)
        assertEquals(3, snapshot.visible)
        assertEquals(2, snapshot.used)
        assertNull(snapshot.chipLabel)
        assertNull(NavicMonitor.logMessage(NavicSnapshot.NONE, snapshot))
    }

    @Test
    fun countsUsedNavicGpsGalileoSeparately() {
        val snapshot = NavicMonitor.tally(
            listOf(
                GnssSatRow("GPS", usedInFix = true),
                GnssSatRow("GPS", usedInFix = true),
                GnssSatRow("GPS", usedInFix = false),
                GnssSatRow("GALILEO", usedInFix = true),
                GnssSatRow("GALILEO", usedInFix = true),
                GnssSatRow("GALILEO", usedInFix = true),
                GnssSatRow("IRNSS", usedInFix = true),
                GnssSatRow("IRNSS", usedInFix = true),
                GnssSatRow("IRNSS", usedInFix = true),
                GnssSatRow("IRNSS", usedInFix = false),
                GnssSatRow("BEIDOU", usedInFix = true),
            ),
        )
        assertEquals(2, snapshot.gpsUsed)
        assertEquals(3, snapshot.galileoUsed)
        assertEquals(3, snapshot.navicUsed)
        assertEquals(4, snapshot.navicVisible)
        assertEquals(11, snapshot.visible)
        assertEquals(9, snapshot.used)
        assertEquals("NavIC 3", snapshot.chipLabel)
        assertTrue(!snapshot.chipLabel!!.contains("SIH"))
        assertEquals(
            "IRNSS visible=4 used=3 GPS used=2 Galileo used=3",
            NavicMonitor.logMessage(NavicSnapshot.NONE, snapshot),
        )
        assertTrue(snapshot.constellations.containsAll(listOf("GPS", "GALILEO", "IRNSS", "BEIDOU")))
    }

    @Test
    fun visibleUnusedIrnssLogsButHasNoChip() {
        val snapshot = NavicMonitor.tally(
            listOf(
                GnssSatRow("GPS", usedInFix = true),
                GnssSatRow("IRNSS", usedInFix = false),
                GnssSatRow("IRNSS", usedInFix = false),
            ),
        )
        assertEquals(0, snapshot.navicUsed)
        assertEquals(2, snapshot.navicVisible)
        assertNull(snapshot.chipLabel)
        assertEquals(
            "IRNSS visible=2 used=0 GPS used=1 Galileo used=0",
            NavicMonitor.logMessage(NavicSnapshot.NONE, snapshot),
        )
    }

    @Test
    fun ingestLogsOnChangeThenStaysQuiet() {
        val monitor = NavicMonitor()
        val withNavic = listOf(
            GnssSatRow("GPS", usedInFix = true),
            GnssSatRow("IRNSS", usedInFix = true),
        )
        assertEquals(
            "IRNSS visible=1 used=1 GPS used=1 Galileo used=0",
            monitor.ingest(withNavic),
        )
        assertEquals("NavIC 1", monitor.visibility.value.chipLabel)
        assertNull(monitor.ingest(withNavic))
        assertEquals(
            "IRNSS visible=0 used=0 GPS used=1 Galileo used=0",
            monitor.ingest(listOf(GnssSatRow("GPS", usedInFix = true))),
        )
        assertNull(monitor.visibility.value.chipLabel)
        assertNull(monitor.ingest(listOf(GnssSatRow("GPS", usedInFix = true))))
    }

    @Test
    fun clearDropsChip() {
        val monitor = NavicMonitor()
        monitor.ingest(listOf(GnssSatRow("IRNSS", usedInFix = true)))
        assertEquals("NavIC 1", monitor.visibility.value.chipLabel)
        monitor.clear()
        assertEquals(NavicSnapshot.NONE, monitor.visibility.value)
        assertNull(monitor.visibility.value.chipLabel)
    }
}
