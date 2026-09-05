package `in`.driftzero.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class PersistCoastSeedTest {
    @Test
    fun maskStartZeroSpeedUniqueIsNotTheSeedWhenEarlierHasSpeedAndCourse() {
        val originLat = 0.0
        val originLon = 0.0
        val (lat12, lon12) = Wgs84.offsetMetres(originLat, originLon, 12.0, 0.0)
        val (latZero, lonZero) = Wgs84.offsetMetres(lat12, lon12, 0.0, 20.0)
        val trail = listOf(
            UniqueFixSample(originLat, originLon, 0L, 15.5, 0.0),
            UniqueFixSample(lat12, lon12, 800_000_000L, 15.5, 0.0),
            UniqueFixSample(latZero, lonZero, 1_000_000_000L, 0.0, PI / 2.0),
        )
        val last = PersistCoastSeed.pick(listOf(trail.last()))
        assertNull("a lone 0 m/s unique must not seed", last)
        val seeded = PersistCoastSeed.pick(trail)
        assertNotNull(seeded)
        assertEquals(800_000_000L, seeded!!.timestampNs)
        assertEquals(15.5, seeded.speedMps, 1e-9)
        assertTrue("10 m course must be north, heading=${seeded.headingRad}", abs(seeded.headingRad) < 0.15)
    }

    @Test
    fun newestMovingUniqueWithTenMetreCourseWins() {
        val originLat = 0.0
        val originLon = 0.0
        val (lat12, lon12) = Wgs84.offsetMetres(originLat, originLon, 12.0, 0.0)
        val (lat24, lon24) = Wgs84.offsetMetres(originLat, originLon, 24.0, 0.0)
        val trail = listOf(
            UniqueFixSample(originLat, originLon, 0L, 10.0, 0.0),
            UniqueFixSample(lat12, lon12, 1_000_000_000L, 10.0, 0.0),
            UniqueFixSample(lat24, lon24, 2_000_000_000L, 11.0, 0.0),
        )
        val seeded = PersistCoastSeed.pick(trail)
        assertNotNull(seeded)
        assertEquals(2_000_000_000L, seeded!!.timestampNs)
        assertEquals(11.0, seeded.speedMps, 1e-9)
    }

    @Test
    fun beforeNsDropsTheMaskStartUniqueLikePersistHistory() {
        val originLat = 0.0
        val originLon = 0.0
        val (lat12, lon12) = Wgs84.offsetMetres(originLat, originLon, 12.0, 0.0)
        val startNs = 1_000_000_000L
        val trail = listOf(
            UniqueFixSample(originLat, originLon, 0L, 15.5, 0.0),
            UniqueFixSample(lat12, lon12, 800_000_000L, 15.5, 0.0),
            UniqueFixSample(lat12, lon12 + 0.0002, startNs, 0.0, PI / 2.0),
        )
        val persist = PersistCoastSeed.pick(trail, beforeNs = startNs)
        assertNotNull(persist)
        assertEquals(800_000_000L, persist!!.timestampNs)
        val inclusive = PersistCoastSeed.pick(trail, beforeNs = startNs + 1L)
        assertEquals(800_000_000L, inclusive!!.timestampNs)
    }
}
