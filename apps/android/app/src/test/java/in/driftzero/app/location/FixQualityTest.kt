package `in`.driftzero.app.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixQualityTest {
    @Test
    fun missingOrInvalidAccuracyIsNotTrusted() {
        assertFalse(FixQuality.hasTrustedFix(null, 1_000L, 2_000L))
        assertFalse(FixQuality.hasTrustedFix(Float.NaN, 1_000L, 2_000L))
        assertFalse(FixQuality.hasTrustedFix(-1f, 1_000L, 2_000L))
    }

    @Test
    fun coarseOrStaleFixIsNotTrusted() {
        val now = 20_000_000_000L
        assertFalse(FixQuality.hasTrustedFix(80f, now, now))
        assertFalse(FixQuality.hasTrustedFix(10f, 0L, now))
        assertFalse(FixQuality.hasTrustedFix(10f, now + 1_000L, now))
    }

    @Test
    fun recentAccurateFixIsTrusted() {
        val now = 5_000_000_000L
        assertTrue(FixQuality.hasTrustedFix(12f, now - 1_000_000_000L, now))
    }
}
