package `in`.driftzero.app.ui

import `in`.driftzero.app.geo.GeoPoint
import `in`.driftzero.app.location.DeviceFix
import `in`.driftzero.app.location.OriginSource
import `in`.driftzero.app.net.TextGetter
import `in`.driftzero.app.product.DemoPlaces
import `in`.driftzero.app.product.UserCopy
import `in`.driftzero.app.routing.OsrmRouteService
import `in`.driftzero.app.search.PlaceSearch
import `in`.driftzero.app.search.readFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorSessionTest {
    private var now = 10_000L

    private fun session(): NavigatorSession {
        val http = TextGetter { url ->
            when {
                url.contains("photon.komoot.io") -> readFixture("fixtures/photon_pune.json")
                url.contains("nominatim") -> readFixture("fixtures/nominatim_pune.json")
                url.contains("router.project-osrm.org") -> readFixture("fixtures/osrm_pune.json")
                else -> error("unexpected $url")
            }
        }
        return NavigatorSession(
            search = PlaceSearch(http),
            router = OsrmRouteService(http),
            nowElapsedMs = { now },
        )
    }

    @Test
    fun demoPathComesFromSearchNotHardcodedPair() {
        val session = session()
        session.onPermission(false)
        session.useDemoDestinationQuery()
        assertEquals(SearchPanel.Results, session.state.searchPanel)
        val place = session.state.searchResults.first()
        assertEquals("Modern College of Engineering", place.name)
        session.selectPlace(place)
        assertEquals(RoutePanel.Ready, session.state.routePanel)
        val route = requireNotNull(session.state.route)
        assertTrue(route.points.size >= 2)
        assertEquals(DemoPlaces.FALLBACK_ORIGIN, session.state.origin)
        assertEquals(OriginSource.Fallback, session.state.originSource)
        assertEquals("5.3 km  ·  6 min", UserCopy.routeSummary(route))
        assertFalse(session.state.destinationQuery.contains("hardcoded", ignoreCase = true))
    }

    @Test
    fun idleStateHidesBrokenTelemetry() {
        val session = session()
        assertEquals(RoutePanel.Idle, session.state.routePanel)
        assertEquals(null, session.state.speedMetersPerSecond)
        assertEquals("Where do you want to go?", UserCopy.idleTitle())
        assertEquals(null, UserCopy.speedChip(session.state.speedMetersPerSecond))
    }

    @Test
    fun gpsFixReplacesFallbackOrigin() {
        val session = session()
        session.onPermission(true)
        session.onFix(
            DeviceFix(
                point = GeoPoint(18.531, 73.85),
                elapsedRealtimeMs = now,
                speedMetersPerSecond = 5.0,
            ),
        )
        assertEquals(OriginSource.Gps, session.state.originSource)
        assertEquals(5.0, session.state.speedMetersPerSecond)
        assertNotNull(UserCopy.speedChip(session.state.speedMetersPerSecond))
    }
}
