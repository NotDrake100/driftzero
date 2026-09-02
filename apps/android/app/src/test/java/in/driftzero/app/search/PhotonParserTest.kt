package `in`.driftzero.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonParserTest {
    @Test
    fun parsesPuneCollegeAndKeepsCoordinates() {
        val json = readFixture("fixtures/photon_pune.json")
        val places = PhotonParser.parse(json)
        assertEquals(2, places.size)
        val college = places.first()
        assertEquals("Modern College of Engineering", college.name)
        assertEquals(18.5254689, college.point.latitudeDeg, 1e-6)
        assertEquals(73.8460317, college.point.longitudeDeg, 1e-6)
        assertTrue(college.subtitle.contains("Pune"))
    }

    @Test
    fun ranksIndiaPuneFirst() {
        val places = PhotonParser.parse(readFixture("fixtures/photon_pune.json"))
        val ranked = PlaceRanker.preferIndiaPune(places)
        assertEquals("Modern College of Engineering", ranked.first().name)
        assertEquals("PK", ranked.last().countryCode)
    }
}
