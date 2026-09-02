package `in`.driftzero.app.ui

import `in`.driftzero.app.settings.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentFormatTest {
    @Test
    fun speedUsesMotionHysteresis() {
        assertTrue(InstrumentFormat.shouldShowSpeed(5.0, currentlyShown = false))
        assertFalse(InstrumentFormat.shouldShowSpeed(0.2, currentlyShown = false))
        assertTrue(InstrumentFormat.shouldShowSpeed(1.0, currentlyShown = true))
        assertFalse(InstrumentFormat.shouldShowSpeed(1.0, currentlyShown = false))
        assertFalse(InstrumentFormat.shouldShowSpeed(null, currentlyShown = true))
        assertFalse(InstrumentFormat.shouldShowSpeed(Double.NaN, currentlyShown = true))
    }

    @Test
    fun speedInBothUnits() {
        assertEquals("34 km/h", InstrumentFormat.formatSpeed(9.5, SpeedUnit.KMH))
        assertEquals("21 mph", InstrumentFormat.formatSpeed(9.5, SpeedUnit.MPH))
        assertEquals("7.2 km/h", InstrumentFormat.formatSpeed(2.0, SpeedUnit.KMH))
        assertEquals("0 km/h", InstrumentFormat.formatSpeed(0.1, SpeedUnit.KMH))
    }

    @Test
    fun distanceAndEta() {
        assertEquals("850 m", InstrumentFormat.formatDistance(850.0))
        assertEquals("12.4 km", InstrumentFormat.formatDistance(12_400.0))
        assertEquals("18 min", InstrumentFormat.formatEta(1_080.0))
        assertEquals("1 hr", InstrumentFormat.formatEta(3_600.0))
        assertEquals("2 hr 5 min", InstrumentFormat.formatEta(7_500.0))
        assertEquals("1 min", InstrumentFormat.formatEta(3.0))
    }

    @Test
    fun secondsChangePrecisionByMagnitude() {
        assertEquals("0.4 s", InstrumentFormat.formatSeconds(0.42))
        assertEquals("14 s", InstrumentFormat.formatSeconds(14.3))
        assertEquals("3 min 20 s", InstrumentFormat.formatSeconds(200.0))
        assertEquals("0.0 s", InstrumentFormat.formatSeconds(-1.0))
    }

    @Test
    fun radiusNeverReadsZero() {
        assertEquals("1 m", InstrumentFormat.formatRadius(0.2))
        assertEquals("12 m", InstrumentFormat.formatRadius(12.4))
        assertEquals("1.3 km", InstrumentFormat.formatRadius(1_260.0))
    }

    @Test
    fun headingWrapsToCompassDegrees() {
        assertEquals("0 deg", InstrumentFormat.formatHeadingDeg(0.0))
        assertEquals("90 deg", InstrumentFormat.formatHeadingDeg(Math.PI / 2))
        assertEquals("270 deg", InstrumentFormat.formatHeadingDeg(-Math.PI / 2))
        assertEquals("0 deg", InstrumentFormat.formatHeadingDeg(2 * Math.PI))
        assertEquals("6 deg", InstrumentFormat.formatAngleDeg(0.1))
    }

    @Test
    fun bytesAndConfidence() {
        assertEquals("412 MB", InstrumentFormat.formatBytes(412_000_000L))
        assertEquals("48 kB", InstrumentFormat.formatBytes(48_000L))
        assertEquals("1.2 GB", InstrumentFormat.formatBytes(1_200_000_000L))
        assertEquals("0.91", InstrumentFormat.formatConfidence(0.912))
    }
}
