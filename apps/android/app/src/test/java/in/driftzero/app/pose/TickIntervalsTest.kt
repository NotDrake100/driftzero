package `in`.driftzero.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TickIntervalsTest {
    @Test
    fun p95NeedsTwentySamples() {
        val ticks = TickIntervals()
        repeat(19) { i -> ticks.record(i * 100_000_000L) }
        assertNull(ticks.p95Ms())
        ticks.record(19 * 100_000_000L)
        val p95 = ticks.p95Ms()
        assertEquals(100.0, p95!!, 0.5)
    }

    @Test
    fun ringDropsOldest() {
        val buf = RingBuffer<Int>(3)
        buf.add(1)
        buf.add(2)
        buf.add(3)
        buf.add(4)
        assertEquals(listOf(2, 3, 4), buf.toList())
        assertEquals(3, buf.size)
    }

    @Test
    fun ignoresBackwardClock() {
        val ticks = TickIntervals()
        ticks.record(1_000_000_000L)
        ticks.record(500_000_000L)
        repeat(20) { i -> ticks.record(1_000_000_000L + (i + 1) * 80_000_000L) }
        val p95 = ticks.p95Ms()
        assertTrue(p95 != null && p95 < 200.0)
    }
}
