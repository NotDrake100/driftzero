package `in`.driftzero.app.copy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriverCopyTest {
    @Test
    fun gpsLabelIsPlainEnglish() {
        assertEquals("GPS on", DriverCopy.gpsHealthLabel(true))
        assertEquals("No GPS yet", DriverCopy.gpsHealthLabel(false))
    }

    @Test
    fun speedHiddenWhenUnknownOrInvalid() {
        assertNull(DriverCopy.speedLabelOrNull(null))
        assertNull(DriverCopy.speedLabelOrNull(Double.NaN))
        assertNull(DriverCopy.speedLabelOrNull(Double.NEGATIVE_INFINITY))
        assertNull(DriverCopy.speedLabelOrNull(-1.0))
    }

    @Test
    fun knownSpeedIsKilometresPerHour() {
        assertEquals("0 km/h", DriverCopy.speedLabelOrNull(0.0))
        assertEquals("36 km/h", DriverCopy.speedLabelOrNull(10.0))
    }

    @Test
    fun idleAndRouteCopyStayPlain() {
        assertEquals("Where do you want to go?", DriverCopy.idleTitle())
        assertEquals(false, DriverCopy.idleBody().contains("NO ROUTE"))
        assertEquals("Search", DriverCopy.START)
    }

    @Test
    fun copyDoesNotUseFirmwareLabels() {
        val visible = listOf(
            DriverCopy.WORDMARK,
            DriverCopy.SEARCH_PLACEHOLDER,
            DriverCopy.START,
            DriverCopy.GPS_ON,
            DriverCopy.GPS_ESTIMATING,
        ).joinToString(" ")
        assertEquals(false, visible.contains("SIH26168"))
        assertEquals(false, visible.contains("CORE"))
        assertEquals(false, visible.contains("LOW CONFIDENCE"))
        assertEquals(false, visible.contains("GNSS"))
        assertEquals(false, visible.contains("HDG"))
        assertEquals(false, visible.contains("UNC"))
        assertEquals(false, visible.contains("estimating"))
        assertEquals(false, visible.contains("NO ROUTE"))
        assertEquals(false, visible.contains("--.-"))
    }
}
