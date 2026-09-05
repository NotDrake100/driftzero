package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftBudgetTest {
    @Test
    fun highConfidenceWhenRadiusIsSmall() {
        val budget = DriftBudgetMath.evaluate(
            horizontal95M = 8.4,
            timestampNs = 1_000_000_000L,
            speedMps = 12.0,
            travelledM = 400.0,
        )
        assertEquals(0.93, budget.confidence, 0.01)
        assertEquals(DriftLamp.HIGH, budget.lamp)
        assertEquals("93%", DriftBudgetMath.confidencePercent(budget))
        assertEquals("Navigation confidence 93%", DriftBudgetMath.confidenceLine(budget))
        assertEquals("High confidence", DriftBudgetMath.lampLine(budget))
        assertTrue(budget.growthRateMps is OptionalScalar.Unavailable)
        assertTrue(budget.timeRemainS is OptionalScalar.Unavailable)
        assertNull(DriftBudgetMath.rangeLine(budget))
    }

    @Test
    fun growingRadiusGivesFiniteRemain() {
        val tracker = DriftBudgetTracker()
        tracker.update(0L, 20.0, 12.0, 200.0)
        val later = tracker.update(10_000_000_000L, 40.0, 12.0, 320.0)
        val growth = later.growthRateMps as OptionalScalar.Available
        assertEquals(2.0, growth.value, 1e-9)
        val remainingRadius = later.remainingRadiusM
        assertEquals(80.0, remainingRadius, 1e-9)
        val t = later.timeRemainS as OptionalScalar.Available
        assertEquals(remainingRadius / growth.value, t.value, 1e-6)
        val range = later.rangeRemainM as OptionalScalar.Available
        assertEquals(12.0 * remainingRadius / growth.value, range.value, 1e-6)
        assertEquals(480.0, range.value, 1e-6)
        assertTrue(range.value != DriftBudgetMath.CAUTION_RANGE_M)
        assertEquals(DriftLamp.CAUTION, later.lamp)
        assertEquals("Safe range 480 m, 40 s", DriftBudgetMath.rangeLine(later))
        assertEquals("480 m remaining", DriftBudgetMath.lampLine(later))
    }

    @Test
    fun shrinkingRadiusIsUnavailableNotInfinity() {
        val tracker = DriftBudgetTracker()
        tracker.update(0L, 40.0, 10.0, 100.0)
        val later = tracker.update(5_000_000_000L, 30.0, 10.0, 150.0)
        val remain = later.timeRemainS as OptionalScalar.Unavailable
        assertTrue(remain.reason.contains("not growing"))
        assertNull(DriftBudgetMath.rangeLine(later))
    }

    @Test
    fun exhaustedWhenRadiusAtLimit() {
        val budget = DriftBudgetMath.evaluate(
            horizontal95M = 120.0,
            timestampNs = 1L,
            speedMps = 8.0,
            travelledM = 50.0,
        )
        assertEquals(0.0, budget.confidence, 1e-12)
        assertEquals(DriftLamp.EXHAUSTED, budget.lamp)
        assertEquals("Position integrity cannot be guaranteed", DriftBudgetMath.lampLine(budget))
        assertEquals(0.0, budget.sihRemainM, 1e-12)
    }

    @Test
    fun sihRemainIsLabRemainder() {
        val budget = DriftBudgetMath.evaluate(
            horizontal95M = 8.0,
            timestampNs = 1L,
            speedMps = 10.0,
            travelledM = 200.0,
        )
        assertEquals(12.0, budget.sihRemainM, 1e-12)
        assertEquals("SIH remain 12 m", DriftBudgetMath.sihRemainLine(budget))
    }

    @Test
    fun formatClockSpanMatchesDriverCopy() {
        assertEquals("2 min 40 s", DriftBudgetMath.formatClockSpan(160.0))
        assertEquals("1.7 km", DriftBudgetMath.formatDistance(1700.0))
    }
}
