package `in`.driftzero.app.pose

import `in`.driftzero.core.DeadReckoningFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class GnssGapTest {
    @Test
    fun freshFixKeepsReportedAccuracy() {
        assertEquals(5.0, inflateGnssAccuracyAfterGap(5.0, 0.4), 1e-9)
        assertEquals(5.0, inflateGnssAccuracyAfterGap(5.0, DeadReckoningFilter.STALE_AFTER_S), 1e-9)
    }

    @Test
    fun staleFixRaisesAccuracyFloor() {
        assertEquals(
            GAP_REACQUIRE_ACCURACY_M,
            inflateGnssAccuracyAfterGap(5.0, DeadReckoningFilter.STALE_AFTER_S + 0.1),
            1e-9,
        )
        assertEquals(80.0, inflateGnssAccuracyAfterGap(80.0, 30.0), 1e-9)
    }

    @Test
    fun nearbyStepKeepsReportedAccuracyWhenFresh() {
        assertEquals(5.0, inflateGnssAccuracyAfterGap(5.0, 0.4, 8.3), 1e-9)
        assertEquals(5.0, inflateGnssAccuracyAfterGap(5.0, 0.4, GAP_JUMP_M - 0.1), 1e-9)
    }

    @Test
    fun liveStaleGapDoesNotInflateAtFourSeconds() {
        assertEquals(5.0, inflateGnssAccuracyAfterGap(5.0, 4.0, staleAfterS = 8.0), 1e-9)
        assertEquals(
            GAP_REACQUIRE_ACCURACY_M,
            inflateGnssAccuracyAfterGap(5.0, 8.1, staleAfterS = 8.0),
            1e-9,
        )
    }
}
