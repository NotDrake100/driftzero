package `in`.driftzero.app.location

import `in`.driftzero.app.geo.GeoPoint
import `in`.driftzero.app.product.DemoPlaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginResolverTest {
    @Test
    fun usesFallbackWhenGpsMissing() {
        val decision = OriginResolver.decide(
            fix = null,
            nowElapsedMs = 10_000,
            waitedMs = 5_000,
            hasPermission = false,
        )
        assertEquals(OriginSource.Fallback, decision.source)
        assertEquals(DemoPlaces.FALLBACK_ORIGIN, decision.point)
        assertNull(decision.speedMetersPerSecond)
        assertFalse(decision.gpsLost)
    }

    @Test
    fun usesGpsFixWhenPresent() {
        val fix = DeviceFix(
            point = GeoPoint(18.53, 73.85),
            elapsedRealtimeMs = 9_000,
            speedMetersPerSecond = 8.0,
        )
        val decision = OriginResolver.decide(
            fix = fix,
            nowElapsedMs = 9_500,
            waitedMs = 9_500,
            hasPermission = true,
        )
        assertEquals(OriginSource.Gps, decision.source)
        assertEquals(8.0, decision.speedMetersPerSecond)
        assertFalse(decision.gpsLost)
    }

    @Test
    fun marksGpsLostWhenFixIsStale() {
        val fix = DeviceFix(
            point = GeoPoint(18.53, 73.85),
            elapsedRealtimeMs = 1_000,
            speedMetersPerSecond = 8.0,
        )
        val decision = OriginResolver.decide(
            fix = fix,
            nowElapsedMs = 1_000 + OriginResolver.GPS_STALE_MS + 1,
            waitedMs = 20_000,
            hasPermission = true,
        )
        assertTrue(decision.gpsLost)
        assertNull(decision.speedMetersPerSecond)
        assertEquals(fix.point, decision.point)
    }
}
