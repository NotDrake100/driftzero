package `in`.driftzero.app.product

import `in`.driftzero.app.copy.DriverCopy
import `in`.driftzero.app.location.OriginSource
import `in`.driftzero.app.routing.RouteFailure
import `in`.driftzero.app.routing.RoutePlan
import `in`.driftzero.app.search.SearchFailure

/** Product copy aliases used by session tests. Driver UI reads [DriverCopy]. */
object UserCopy {
    const val SEARCH_HINT = "Where do you want to go?"
    const val SEARCH_PLACEHOLDER = DriverCopy.SEARCH_PLACEHOLDER
    const val TRY_DEMO = DriverCopy.TRY_DEMO
    const val ATTRIBUTION = DriverCopy.ATTRIBUTION

    fun idleTitle(): String = DriverCopy.idleTitle()
    fun idleBody(): String = DriverCopy.idleBody()
    fun searching(): String = DriverCopy.searching()
    fun noResults(query: String): String = DriverCopy.noResults(query)
    fun searchError(reason: SearchFailure): String = DriverCopy.searchError(reason)
    fun routing(): String = DriverCopy.routing()
    fun routeError(reason: RouteFailure): String = DriverCopy.routeError(reason)
    fun routeTitle(destinationName: String): String = DriverCopy.routeTitle(destinationName)
    fun routeSummary(plan: RoutePlan): String = DriverCopy.routeSummary(plan)
    fun locationLine(
        source: OriginSource,
        hasPermission: Boolean,
        waitingForFix: Boolean,
        gpsLost: Boolean,
    ): String? = DriverCopy.locationLine(source, hasPermission, waitingForFix, gpsLost)
    fun speedChip(speedMetersPerSecond: Double?): String? =
        DriverCopy.speedLabelOrNull(speedMetersPerSecond)
}
