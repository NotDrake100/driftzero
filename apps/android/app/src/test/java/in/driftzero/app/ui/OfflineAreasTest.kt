package `in`.driftzero.app.ui

import `in`.driftzero.app.maps.GeoBbox
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineAreasTest {
    @Test
    fun bboxAndOsmDateAreInstrumentReadouts() {
        val box = GeoBbox.of(18.46, 73.76, 18.62, 73.96)!!
        assertEquals("18.4600, 73.7600 to 18.6200, 73.9600", packBboxText(box))
        assertEquals("OSM 2026-09-01", packOsmDateText("2026-09-01T20:20:50Z"))
        assertEquals("OSM date unknown", packOsmDateText(null))
        assertEquals("OSM date unknown", packOsmDateText(""))
    }
}
