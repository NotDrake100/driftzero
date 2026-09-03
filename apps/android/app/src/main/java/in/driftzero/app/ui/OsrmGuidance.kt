package `in`.driftzero.app.ui

import `in`.driftzero.core.GuidanceRoute
import `in`.driftzero.core.ManeuverModifier
import `in`.driftzero.core.ManeuverType
import `in`.driftzero.core.RoutePoint
import `in`.driftzero.core.RouteStep
import `in`.driftzero.core.Wgs84

internal fun TravelRoute.toGuidance(): GuidanceRoute {
    val routePoints = points.map { RoutePoint(it.latitudeDeg, it.longitudeDeg) }
    val resolved = if (steps.isNotEmpty()) {
        steps
    } else {
        fallbackSteps(routePoints, distanceM, durationS)
    }
    return GuidanceRoute(
        points = routePoints,
        steps = resolved,
        totalDistanceM = distanceM,
        totalDurationS = durationS,
    )
}

internal fun GuidanceRoute.toTravelRoute(): TravelRoute = TravelRoute(
    points = points.map { TravelLatLng(it.latitudeDeg, it.longitudeDeg) },
    distanceM = totalDistanceM,
    durationS = totalDurationS,
    steps = steps,
)

internal fun parseOsrmSteps(route: JsonVal, points: List<TravelLatLng>): List<RouteStep> {
    if (points.isEmpty()) {
        return emptyList()
    }
    val last = points.lastIndex
    val found = ArrayList<RouteStep>()
    val legs = route.at("legs")?.arr().orEmpty()
    for (leg in legs) {
        val steps = leg.at("steps")?.arr() ?: continue
        for (step in steps) {
            val parsed = parseOneStep(step, points) ?: continue
            found += parsed
        }
    }
    if (found.isEmpty()) {
        return fallbackSteps(points.map { RoutePoint(it.latitudeDeg, it.longitudeDeg) }, 0.0, 0.0)
    }
    if (found.last().type != ManeuverType.ARRIVE) {
        found += RouteStep(ManeuverType.ARRIVE, ManeuverModifier.NONE, null, null, 0.0, 0.0, last)
    }
    return found
}

private fun parseOneStep(step: JsonVal, points: List<TravelLatLng>): RouteStep? {
    val maneuver = step.at("maneuver") ?: return null
    val type = mapOsrmType(maneuver.at("type")?.str())
    val modifier = mapOsrmModifier(maneuver.at("modifier")?.str())
    val name = step.at("name")?.str()?.takeIf { it.isNotEmpty() }
    val exitRaw = maneuver.at("exit")?.num()?.toInt()
    val exit = exitRaw?.takeIf { it > 0 }
    val distance = step.at("distance")?.num()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val duration = step.at("duration")?.num()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val loc = maneuver.at("location")?.arr()
    val lon = loc?.getOrNull(0)?.num()
    val lat = loc?.getOrNull(1)?.num()
    val startIndex = if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
        nearestIndex(points, lat, lon)
    } else {
        0
    }
    return RouteStep(type, modifier, name, exit, distance, duration, startIndex)
}

internal fun mapOsrmType(raw: String?): ManeuverType = when (raw?.lowercase()) {
    "depart" -> ManeuverType.DEPART
    "turn" -> ManeuverType.TURN
    "new name" -> ManeuverType.NEW_NAME
    "continue" -> ManeuverType.CONTINUE
    "merge" -> ManeuverType.MERGE
    "on ramp" -> ManeuverType.ON_RAMP
    "off ramp" -> ManeuverType.OFF_RAMP
    "fork" -> ManeuverType.FORK
    "end of road" -> ManeuverType.END_OF_ROAD
    "roundabout", "roundabout turn" -> ManeuverType.ROUNDABOUT
    "exit roundabout" -> ManeuverType.EXIT_ROUNDABOUT
    "rotary" -> ManeuverType.ROTARY
    "exit rotary" -> ManeuverType.EXIT_ROUNDABOUT
    "uturn" -> ManeuverType.UTURN
    "arrive" -> ManeuverType.ARRIVE
    else -> ManeuverType.UNKNOWN
}

internal fun mapOsrmModifier(raw: String?): ManeuverModifier = when (raw?.lowercase()) {
    "uturn" -> ManeuverModifier.UTURN
    "sharp right" -> ManeuverModifier.SHARP_RIGHT
    "right" -> ManeuverModifier.RIGHT
    "slight right" -> ManeuverModifier.SLIGHT_RIGHT
    "straight" -> ManeuverModifier.STRAIGHT
    "slight left" -> ManeuverModifier.SLIGHT_LEFT
    "left" -> ManeuverModifier.LEFT
    "sharp left" -> ManeuverModifier.SHARP_LEFT
    else -> ManeuverModifier.NONE
}

private fun nearestIndex(points: List<TravelLatLng>, lat: Double, lon: Double): Int {
    var best = 0
    var bestD = Double.POSITIVE_INFINITY
    for (i in points.indices) {
        val d = Wgs84.distanceMetres(lat, lon, points[i].latitudeDeg, points[i].longitudeDeg)
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

private fun fallbackSteps(points: List<RoutePoint>, distanceM: Double, durationS: Double): List<RouteStep> {
    val last = points.lastIndex.coerceAtLeast(0)
    return listOf(
        RouteStep(ManeuverType.DEPART, ManeuverModifier.NONE, null, null, distanceM, durationS, 0),
        RouteStep(ManeuverType.ARRIVE, ManeuverModifier.NONE, null, null, 0.0, 0.0, last),
    )
}
