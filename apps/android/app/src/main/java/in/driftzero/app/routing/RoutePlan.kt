package `in`.driftzero.app.routing

import `in`.driftzero.app.geo.GeoPoint

data class RoutePlan(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val points: List<GeoPoint>,
)

sealed class RouteOutcome {
    data class Ok(val plan: RoutePlan) : RouteOutcome()
    data class Failed(val reason: RouteFailure) : RouteOutcome()
}

enum class RouteFailure {
    Network,
    RateLimited,
    NoRoute,
    Parse,
}
