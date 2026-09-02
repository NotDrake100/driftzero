package `in`.driftzero.app.product

import `in`.driftzero.app.location.OriginSource
import `in`.driftzero.app.routing.RouteFailure
import `in`.driftzero.app.routing.RoutePlan
import `in`.driftzero.app.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UserCopyTest {
    @Test
    fun idleCopyDoesNotLookBroken() {
        assertEquals("Where do you want to go?", UserCopy.idleTitle())
        assertFalse(UserCopy.idleBody().contains("NO ROUTE"))
        assertFalse(UserCopy.idleTitle().contains("--"))
        assertNull(UserCopy.speedChip(null))
    }

    @Test
    fun routeSummaryIsPlainLanguage() {
        val plan = RoutePlan(
            distanceMeters = 5258.7,
            durationSeconds = 386.7,
            points = listOf(
                GeoPoint(18.5392674, 73.8866937),
                GeoPoint(18.5254689, 73.8460317),
            ),
        )
        assertEquals("5.3 km  ·  6 min", UserCopy.routeSummary(plan))
        assertEquals("To Modern College of Engineering", UserCopy.routeTitle("Modern College of Engineering"))
    }

    @Test
    fun gpsLostIsHonestAboutEstimator() {
        val line = UserCopy.locationLine(
            source = OriginSource.Gps,
            hasPermission = true,
            waitingForFix = false,
            gpsLost = true,
        )
        requireNotNull(line)
        assertFalse(line.contains("dead reckoning", ignoreCase = true))
        assert(line.contains("not ready"))
    }

    @Test
    fun routeErrorHasNoPlaceholderDash() {
        val text = UserCopy.routeError(RouteFailure.NoRoute)
        assertFalse(text.contains("--"))
        assertFalse(text.contains("NO ROUTE"))
    }
}
