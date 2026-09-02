package `in`.driftzero.app.pose

import org.junit.Assert.assertEquals
import org.junit.Test

class GnssProvidersTest {
    @Test
    fun api31PrefersFusedThenGpsThenNetwork() {
        val providers = gnssFixProviders(31, listOf("passive", "gps", "network", "fused"))
        assertEquals(listOf("fused", "gps", "network"), providers)
    }

    @Test
    fun api29SkipsFused() {
        val providers = gnssFixProviders(29, listOf("gps", "network", "fused"))
        assertEquals(listOf("gps", "network"), providers)
    }

    @Test
    fun missingProvidersAreOmitted() {
        val providers = gnssFixProviders(33, listOf("gps"))
        assertEquals(listOf("gps"), providers)
    }
}
