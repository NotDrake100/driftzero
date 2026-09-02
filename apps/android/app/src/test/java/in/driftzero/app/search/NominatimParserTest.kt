package `in`.driftzero.app.search

import org.junit.Assert.assertEquals
import org.junit.Test

class NominatimParserTest {
    @Test
    fun parsesCollegeFromNominatim() {
        val places = NominatimParser.parse(readFixture("fixtures/nominatim_pune.json"))
        assertEquals(1, places.size)
        assertEquals("Modern College of Engineering", places[0].name)
        assertEquals(18.5254689, places[0].point.latitudeDeg, 1e-6)
        assertEquals("IN", places[0].countryCode)
    }

    @Test
    fun emptyArrayIsEmpty() {
        assertEquals(0, NominatimParser.parse("[]").size)
    }
}
