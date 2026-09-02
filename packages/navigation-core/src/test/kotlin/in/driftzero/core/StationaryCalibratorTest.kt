package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryCalibratorTest {
    @Test
    fun stillThenDoneWhenQuiet() {
        val cal = StationaryCalibrator()
        repeat(50) { i ->
            cal.ingestAccel(i * 100_000_000L, 0.0, 0.0, 9.81)
        }
        assertEquals(CalibrationStatus.Still, cal.evaluate(2_000_000_000L, hasGyro = true))
        val done = cal.evaluate(5_000_000_000L, hasGyro = true)
        assertTrue(done is CalibrationStatus.Done)
        assertTrue((done as CalibrationStatus.Done).tiltDeg < 1.0)
    }

    @Test
    fun movingWhenAccelJumps() {
        val cal = StationaryCalibrator()
        repeat(20) { i ->
            val z = if (i % 2 == 0) 12.0 else 6.0
            cal.ingestAccel(i * 100_000_000L, 0.0, 0.0, z)
        }
        assertEquals(CalibrationStatus.Moving, cal.evaluate(1_000_000_000L, hasGyro = true))
    }

    @Test
    fun noGyroFails() {
        val cal = StationaryCalibrator()
        cal.ingestAccel(0L, 0.0, 0.0, 9.81)
        assertEquals(CalibrationStatus.FailedNoGyro, cal.evaluate(5_000_000_000L, hasGyro = false))
    }

    @Test
    fun profileAngleAndMount() {
        val a = CalibrationProfile(0.0, 0.0, 9.81)
        val b = CalibrationProfile(0.0, 0.0, 9.81)
        assertTrue(a.angleDeg(b) < 0.5)
        val monitor = MountMonitor(a)
        monitor.ingestAccel(0L, 0.0, 0.0, 9.81)
        assertTrue(monitor.mountOk(1_000_000_000L))
        repeat(30) { i ->
            monitor.ingestAccel(i * 100_000_000L, 9.81, 0.0, 0.0)
        }
        assertFalse(monitor.mountOk(3_000_000_000L))
    }
}
