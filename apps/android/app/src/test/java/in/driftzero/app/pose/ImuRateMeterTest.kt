package `in`.driftzero.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImuRateMeterTest {
    @Test
    fun medianHzFromTenMillisecondDeltasIsOneHundred() {
        val meter = ImuRateMeter()
        assertNull(meter.snapshot().medianHz)
        var t = 0L
        repeat(11) {
            meter.accept(t)
            t += 10_000_000L
        }
        val snap = meter.snapshot()
        assertEquals(11, snap.sampleCount)
        assertEquals(100.0, snap.medianHz!!, 1e-6)
        assertEquals(10_000_000L, snap.medianDtNs)
    }

    @Test
    fun nonPositiveDeltasDoNotInventARate() {
        val meter = ImuRateMeter()
        meter.accept(1_000L)
        meter.accept(1_000L)
        meter.accept(500L)
        assertNull(meter.snapshot().medianHz)
        assertEquals(3, meter.snapshot().sampleCount)
    }
}
