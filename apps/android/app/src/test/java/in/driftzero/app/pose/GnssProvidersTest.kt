package `in`.driftzero.app.pose

import org.junit.Assert.assertEquals
import org.junit.Test

class GnssProvidersTest {
    @Test
    fun gpsNetworkAndFusedInThatOrder() {
        val providers = gnssFixProviders(31, listOf("passive", "gps", "network", "fused"))
        assertEquals(listOf("gps", "network", "fused"), providers)
    }

    @Test
    fun fusedIsRequestedOnApi31EvenIfMissingFromList() {
        val providers = gnssFixProviders(31, listOf("gps", "network"))
        assertEquals(listOf("gps", "network", "fused"), providers)
    }

    @Test
    fun api29AddsFusedOnlyWhenListed() {
        assertEquals(listOf("gps", "network"), gnssFixProviders(29, listOf("gps", "network")))
        assertEquals(
            listOf("gps", "network", "fused"),
            gnssFixProviders(29, listOf("gps", "network", "fused")),
        )
    }

    @Test
    fun missingProvidersAreOmitted() {
        val providers = gnssFixProviders(33, listOf("gps"))
        assertEquals(listOf("gps", "fused"), providers)
    }

    @Test
    fun lastKnownUsesFusedWhenLiveAndGpsAreEmpty() {
        val fused = LastKnownFix("fused", elapsedRealtimeNanos = 20L, latitude = 18.52, longitude = 73.85)
        val picked = pickLastKnownFix(listOf(fused))
        assertEquals(fused, picked)
    }

    @Test
    fun lastKnownPrefersGpsWhenLiveIsEmpty() {
        val gps = LastKnownFix("gps", elapsedRealtimeNanos = 5L, latitude = 18.51, longitude = 73.84)
        val fused = LastKnownFix("fused", elapsedRealtimeNanos = 50L, latitude = 0.0, longitude = 0.0)
        val picked = pickLastKnownFix(listOf(fused, gps))
        assertEquals(gps, picked)
    }

    @Test
    fun coarseGrantIsNotPrecise() {
        assertEquals(LocationGrant.NONE, readLocationGrant(fineGranted = false, coarseGranted = false))
        assertEquals(LocationGrant.COARSE, readLocationGrant(fineGranted = false, coarseGranted = true))
        assertEquals(LocationGrant.FINE, readLocationGrant(fineGranted = true, coarseGranted = true))
        assertEquals(LocationGrant.FINE, readLocationGrant(fineGranted = true, coarseGranted = false))
    }
}
