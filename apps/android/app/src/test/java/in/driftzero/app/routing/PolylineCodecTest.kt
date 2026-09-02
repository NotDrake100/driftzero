package `in`.driftzero.app.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class PolylineCodecTest {
    @Test
    fun decodesGoogleDocumentationExample() {
        val points = PolylineCodec.decode("_p~iF~ps|U")
        assertEquals(1, points.size)
        assertEquals(38.5, points[0].latitudeDeg, 0.01)
        assertEquals(-120.2, points[0].longitudeDeg, 0.01)
    }

    @Test
    fun emptyPolylineIsEmpty() {
        assertEquals(0, PolylineCodec.decode("").size)
    }
}
