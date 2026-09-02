package `in`.driftzero.app.copy

import `in`.driftzero.app.format.DistanceEta
import `in`.driftzero.app.location.OriginSource
import `in`.driftzero.app.product.DemoPlaces
import `in`.driftzero.app.routing.RouteFailure
import `in`.driftzero.app.routing.RoutePlan
import `in`.driftzero.app.search.SearchFailure
import kotlin.math.roundToInt

/**
 * Driver-facing copy. Keep this free of contest IDs, firmware labels,
 * and estimator enum names such as LOW_CONFIDENCE.
 */
object DriverCopy {
    const val WORDMARK = "DriftZero"
    const val SEARCH_PLACEHOLDER = "Where to?"
    const val START = "Search"
    const val GPS_ON = "GPS on"
    const val GPS_ESTIMATING = "No GPS yet"
    const val TRY_DEMO = DemoPlaces.DEMO_DESTINATION_QUERY
    const val ATTRIBUTION = "Places and roads: OpenStreetMap"

    /**
     * @param hasTrustedFix true only when a recent, usable GNSS fix exists
     */
    fun gpsHealthLabel(hasTrustedFix: Boolean): String =
        if (hasTrustedFix) GPS_ON else GPS_ESTIMATING

    fun locationLine(
        source: OriginSource,
        hasPermission: Boolean,
        waitingForFix: Boolean,
        gpsLost: Boolean,
    ): String? = when {
        gpsLost ->
            "GPS is weak. Holding the last known place. Motion without GPS is not ready yet."
        !hasPermission ->
            "Location is off. Starting from ${DemoPlaces.FALLBACK_ORIGIN_LABEL}."
        waitingForFix && source == OriginSource.Fallback ->
            "Looking for GPS. Starting from ${DemoPlaces.FALLBACK_ORIGIN_LABEL} for now."
        source == OriginSource.Gps -> "Following your location"
        else -> "Starting from ${DemoPlaces.FALLBACK_ORIGIN_LABEL} (no GPS yet)"
    }

    fun idleTitle(): String = "Where do you want to go?"

    fun idleBody(): String = "Type a destination, then tap a result. Try $TRY_DEMO"

    fun searching(): String = "Searching places"

    fun noResults(query: String): String =
        "No places match \"$query\". Try a landmark or a street name."

    fun searchError(reason: SearchFailure): String = when (reason) {
        SearchFailure.Network -> "Search needs a connection right now."
        SearchFailure.RateLimited -> "Too many searches. Wait a moment, then try again."
        SearchFailure.Parse, SearchFailure.Unavailable ->
            "Could not look up places. Try again in a moment."
    }

    fun routing(): String = "Finding a driving route"

    fun routeError(reason: RouteFailure): String = when (reason) {
        RouteFailure.Network -> "Routing needs a connection right now."
        RouteFailure.RateLimited -> "The route service is busy. Try again in a moment."
        RouteFailure.NoRoute -> "No driving route between those points. Try another place."
        RouteFailure.Parse -> "Could not read the route. Try again."
    }

    fun routeTitle(destinationName: String): String = "To $destinationName"

    fun routeSummary(plan: RoutePlan): String =
        "${DistanceEta.formatDistance(plan.distanceMeters)}  ·  ${DistanceEta.formatDuration(plan.durationSeconds)}"

    /**
     * @param speedMps speed in metres/second, or null when unknown (never treat missing as 0)
     * @return kilometres/hour label, or null when speed is unknown/invalid
     */
    fun speedLabelOrNull(speedMps: Double?): String? {
        if (speedMps == null) return null
        if (!speedMps.isFinite() || speedMps < 0.0) return null
        val kilometresPerHour = speedMps * 3.6
        return "${kilometresPerHour.roundToInt()} km/h"
    }
}
