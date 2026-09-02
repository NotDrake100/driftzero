package `in`.driftzero.app.search

import `in`.driftzero.app.net.HttpException
import `in`.driftzero.app.net.TextGetter
import `in`.driftzero.app.product.DemoPlaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchTest {
    @Test
    fun demoQueryReturnsCollegeFromPhoton() {
        val search = PlaceSearch(
            http = TextGetter { url ->
                if (url.contains("photon.komoot.io")) readFixture("fixtures/photon_pune.json")
                else error("unexpected $url")
            },
        )
        val outcome = search.search(DemoPlaces.DEMO_DESTINATION_QUERY, DemoPlaces.PUNE_BIAS)
        val found = outcome as SearchOutcome.Found
        assertEquals("Modern College of Engineering", found.places.first().name)
        assertEquals("photon", found.places.first().source)
    }

    @Test
    fun fallsBackToNominatimWhenPhotonIsEmpty() {
        val search = PlaceSearch(
            http = TextGetter { url ->
                if (url.contains("photon.komoot.io")) """{"type":"FeatureCollection","features":[]}"""
                else readFixture("fixtures/nominatim_pune.json")
            },
        )
        val outcome = search.search("Modern College of Engineering Pune", DemoPlaces.PUNE_BIAS)
        val found = outcome as SearchOutcome.Found
        assertEquals("nominatim", found.places.first().source)
    }

    @Test
    fun shortQueryDoesNotHitNetwork() {
        var calls = 0
        val search = PlaceSearch(http = TextGetter { calls += 1; "" })
        val outcome = search.search("PE", DemoPlaces.PUNE_BIAS)
        assertEquals(SearchOutcome.Empty, outcome)
        assertEquals(0, calls)
    }

    @Test
    fun photonRateLimitSurfacesWhenNominatimAlsoFails() {
        val search = PlaceSearch(
            http = TextGetter { url ->
                if (url.contains("photon")) throw HttpException(429, "rate")
                else throw HttpException(500, "down")
            },
        )
        val outcome = search.search("Starbucks Koregaon Park", DemoPlaces.PUNE_BIAS)
        assertTrue(outcome is SearchOutcome.Failed)
        assertEquals(SearchFailure.RateLimited, (outcome as SearchOutcome.Failed).reason)
    }
}
