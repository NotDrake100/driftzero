package `in`.driftzero.app.routing

import org.json.JSONObject

object OsrmParser {
    fun parse(json: String): RouteOutcome {
        val root = JSONObject(json)
        val code = root.optString("code")
        if (code != "Ok") {
            return RouteOutcome.Failed(RouteFailure.NoRoute)
        }
        val routes = root.optJSONArray("routes")
        if (routes == null || routes.length() == 0) {
            return RouteOutcome.Failed(RouteFailure.NoRoute)
        }
        val route = routes.optJSONObject(0) ?: return RouteOutcome.Failed(RouteFailure.Parse)
        val distance = route.optDouble("distance", Double.NaN)
        val duration = route.optDouble("duration", Double.NaN)
        if (!distance.isFinite() || !duration.isFinite() || distance < 0.0 || duration < 0.0) {
            return RouteOutcome.Failed(RouteFailure.Parse)
        }
        val geometry = route.optString("geometry")
        val points = PolylineCodec.decode(geometry)
        if (points.size < 2) {
            return RouteOutcome.Failed(RouteFailure.NoRoute)
        }
        return RouteOutcome.Ok(
            RoutePlan(
                distanceMeters = distance,
                durationSeconds = duration,
                points = points,
            ),
        )
    }
}
