package `in`.driftzero.app.pose

import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackoutOverlayTest {
    @Test
    fun coastAddsPathStepsAndResetsOnFix() {
        val origin = PosePoint(0.0, 0.0)
        val first = offset(origin, northM = 100.0, eastM = 0.0)
        val second = offset(first, northM = 40.0, eastM = 0.0)
        val afterFirst = BlackoutOverlay.accumulateCoastM(
            0.0,
            origin,
            first,
            NavigationMode.DEAD_RECKONING,
        )
        assertEquals(100.0, afterFirst, 0.5)
        val afterSecond = BlackoutOverlay.accumulateCoastM(
            afterFirst,
            first,
            second,
            NavigationMode.DEAD_RECKONING,
        )
        assertEquals(140.0, afterSecond, 0.5)
        assertEquals(
            0.0,
            BlackoutOverlay.accumulateCoastM(
                afterSecond,
                second,
                second,
                NavigationMode.GNSS_FUSED,
            ),
            0.0,
        )
    }

    @Test
    fun coastKeepsSumOnReacquireAndLowConfidence() {
        val a = PosePoint(12.0, 77.0)
        val b = offset(a, northM = 20.0, eastM = 0.0)
        val mid = BlackoutOverlay.accumulateCoastM(10.0, a, b, NavigationMode.REACQUIRING)
        assertEquals(30.0, mid, 0.5)
        val low = BlackoutOverlay.accumulateCoastM(mid, b, offset(b, 10.0, 0.0), NavigationMode.LOW_CONFIDENCE)
        assertEquals(40.0, low, 0.5)
    }

    @Test
    fun coastIgnoresMissingOrInvalidFrom() {
        val to = PosePoint(1.0, 2.0)
        assertEquals(12.0, BlackoutOverlay.accumulateCoastM(12.0, null, to, NavigationMode.DEAD_RECKONING), 0.0)
        val bad = PosePoint(Double.NaN, 2.0)
        assertEquals(12.0, BlackoutOverlay.accumulateCoastM(12.0, bad, to, NavigationMode.DEAD_RECKONING), 0.0)
    }

    @Test
    fun ghostLivesOnlyDuringOutageWithAFix() {
        val last = PosePoint(18.52, 73.85)
        assertTrue(BlackoutOverlay.ghostVisible(NavigationMode.DEAD_RECKONING, last))
        assertTrue(BlackoutOverlay.ghostVisible(NavigationMode.REACQUIRING, last))
        assertTrue(BlackoutOverlay.ghostVisible(NavigationMode.LOW_CONFIDENCE, last))
        assertFalse(BlackoutOverlay.ghostVisible(NavigationMode.GNSS_FUSED, last))
        assertFalse(BlackoutOverlay.ghostVisible(NavigationMode.DEAD_RECKONING, null))
        assertFalse(BlackoutOverlay.ghostVisible(NavigationMode.DEAD_RECKONING, PosePoint(Double.NaN, 0.0)))
    }

    @Test
    fun correctionLastsThreeSecondsThenDrops() {
        val start = 10_000_000_000L
        assertFalse(BlackoutOverlay.correctionVisible(null, start))
        assertTrue(BlackoutOverlay.correctionVisible(start, start))
        assertTrue(BlackoutOverlay.correctionVisible(start, start + 2_999_999_999L))
        assertFalse(BlackoutOverlay.correctionVisible(start, start + BlackoutOverlay.CORRECTION_LIFETIME_NS))
        assertFalse(BlackoutOverlay.correctionVisible(start, start - 1L))
        assertEquals(
            1_000_000_000L,
            BlackoutOverlay.correctionRemainingNs(start, start + 2_000_000_000L),
        )
    }

    private fun offset(from: PosePoint, northM: Double, eastM: Double): PosePoint {
        val next = Wgs84.offsetMetres(from.latitudeDeg, from.longitudeDeg, northM, eastM)
        return PosePoint(next.first, next.second)
    }
}
