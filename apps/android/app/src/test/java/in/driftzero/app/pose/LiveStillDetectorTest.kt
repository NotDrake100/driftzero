package `in`.driftzero.app.pose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStillDetectorTest {
    @Test
    fun quietWindowIsStillAfterOneSecond() {
        val detector = LiveStillDetector()
        val g = 9.81
        val dt = 20_000_000L
        for (i in 0..60) {
            val t = i * dt
            detector.onAccel(t, 0.0, 0.0, g)
            detector.onGyro(t, 0.0, 0.0, 0.0)
        }
        assertTrue(detector.isStill(60 * dt))
    }

    @Test
    fun drivingImuIsNotStill() {
        val detector = LiveStillDetector()
        val g = 9.81
        val dt = 20_000_000L
        for (i in 0..60) {
            val t = i * dt
            val wobble = if (i % 2 == 0) 2.0 else -2.0
            detector.onAccel(t, wobble, 0.5, g + wobble)
            detector.onGyro(t, 0.2, 0.1, 0.4)
        }
        assertFalse(detector.isStill(60 * dt))
    }

    @Test
    fun jumpingGnssIsRejectedWhileStill() {
        assertTrue(LiveStillDetector.rejectGnssWhileStill(6.4, 2.0, 1.0))
        assertTrue(LiveStillDetector.rejectGnssWhileStill(0.0, 12.0, 1.0))
        assertFalse(LiveStillDetector.rejectGnssWhileStill(0.2, 1.0, 1.0))
        assertFalse(LiveStillDetector.rejectGnssWhileStill(null, 0.0, 1.0))
    }
}
