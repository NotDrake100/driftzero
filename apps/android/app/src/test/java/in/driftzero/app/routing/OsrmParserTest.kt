package `in`.driftzero.app.routing

import `in`.driftzero.app.search.readFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OsrmParserTest {
    @Test
    fun parsesPuneDrivingRoute() {
        val outcome = OsrmParser.parse(readFixture("fixtures/osrm_pune.json"))
        val plan = (outcome as RouteOutcome.Ok).plan
        assertEquals(5258.7, plan.distanceMeters, 0.01)
        assertEquals(386.7, plan.durationSeconds, 0.01)
        assertTrue(plan.points.size >= 2)
        assertEquals(18.539, plan.points.first().latitudeDeg, 0.02)
        assertEquals(73.887, plan.points.first().longitudeDeg, 0.02)
    }

    @Test
    fun missingRouteIsFailure() {
        val outcome = OsrmParser.parse("""{"code":"NoRoute","routes":[]}""")
        assertEquals(RouteFailure.NoRoute, (outcome as RouteOutcome.Failed).reason)
    }
}
