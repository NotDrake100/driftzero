package `in`.driftzero.app.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistanceEtaTest {
    @Test
    fun formatsShortWalkInMetres() {
        assertEquals("800 m", DistanceEta.formatDistance(800.0))
    }

    @Test
    fun formatsCityDriveWithOneDecimal() {
        assertEquals("5.3 km", DistanceEta.formatDistance(5258.7))
    }

    @Test
    fun formatsLongDriveAsWholeKilometres() {
        assertEquals("12 km", DistanceEta.formatDistance(12_400.0))
    }

    @Test
    fun formatsMinutesInPlainLanguage() {
        assertEquals("6 min", DistanceEta.formatDuration(386.7))
        assertEquals("28 min", DistanceEta.formatDuration(28 * 60.0))
        assertEquals("1 hr 5 min", DistanceEta.formatDuration(65 * 60.0))
    }

    @Test
    fun hidesSpeedWhenMissing() {
        assertNull(DistanceEta.formatSpeedKmh(null))
        assertNull(DistanceEta.formatSpeedKmh(Double.NaN))
        assertEquals("36 km/h", DistanceEta.formatSpeedKmh(10.0))
    }
}
