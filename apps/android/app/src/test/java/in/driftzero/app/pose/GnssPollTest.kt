package `in`.driftzero.app.pose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssPollTest {
    @Test
    fun newerElapsedIngestsOnce() {
        assertTrue(shouldIngestPolled(null, 1L))
        assertTrue(shouldIngestPolled(1L, 2L))
        assertFalse(shouldIngestPolled(2L, 2L))
        assertFalse(shouldIngestPolled(2L, 1L))
        assertFalse(shouldIngestPolled(null, 0L))
    }

    @Test
    fun sameElapsedIngestsWhenPositionMoves() {
        assertTrue(shouldIngestPolled(2L, 2L, 18.5, 73.8, 18.51, 73.8))
        assertFalse(shouldIngestPolled(2L, 2L, 18.5, 73.8, 18.5, 73.8))
        assertTrue(shouldIngestPolled(2L, 3L, 18.5, 73.8, 18.5, 73.8))
    }
}
