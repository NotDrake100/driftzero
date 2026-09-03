package `in`.driftzero.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class RoutePoint(val latitudeDeg: Double, val longitudeDeg: Double) {
    init {
        require(latitudeDeg.isFinite() && latitudeDeg in -90.0..90.0)
        require(longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0)
    }
}

enum class ManeuverType {
    DEPART,
    TURN,
    NEW_NAME,
    CONTINUE,
    MERGE,
    ON_RAMP,
    OFF_RAMP,
    FORK,
    END_OF_ROAD,
    ROUNDABOUT,
    EXIT_ROUNDABOUT,
    ROTARY,
    UTURN,
    ARRIVE,
    UNKNOWN,
}

enum class ManeuverModifier {
    UTURN,
    SHARP_RIGHT,
    RIGHT,
    SLIGHT_RIGHT,
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    NONE,
}

data class RouteStep(
    val type: ManeuverType,
    val modifier: ManeuverModifier,
    val roadName: String?,
    val exitNumber: Int?,
    val distanceM: Double,
    val durationS: Double,
    val startIndex: Int,
) {
    init {
        require(roadName == null || roadName.isNotEmpty()) { "roadName must be null, never empty" }
        require(distanceM.isFinite() && distanceM >= 0.0)
        require(durationS.isFinite() && durationS >= 0.0)
        require(startIndex >= 0)
        exitNumber?.let { require(it > 0) { "exitNumber must be a positive ordinal" } }
    }
}

data class GuidanceRoute(
    val points: List<RoutePoint>,
    val steps: List<RouteStep>,
    val totalDistanceM: Double,
    val totalDurationS: Double,
) {
    init {
        require(points.size >= 2) { "route needs at least two points" }
        require(steps.isNotEmpty()) { "route needs at least one step" }
        require(totalDistanceM.isFinite() && totalDistanceM >= 0.0)
        require(totalDurationS.isFinite() && totalDurationS >= 0.0)
        val last = points.lastIndex
        for (step in steps) {
            require(step.startIndex <= last) { "step startIndex ${step.startIndex} out of polyline" }
        }
    }
}

sealed class GuidanceState {
    data class OnRoute(
        val snappedLatitudeDeg: Double,
        val snappedLongitudeDeg: Double,
        val segmentIndex: Int,
        val distanceAlongM: Double,
        val remainingM: Double,
        val remainingS: Double,
        val nextStepIndex: Int,
        val distanceToNextStepM: Double,
        val crossTrackM: Double,
    ) : GuidanceState()

    data class OffRoute(val crossTrackM: Double, val sinceS: Double) : GuidanceState()

    data class Arrived(val distanceToDestinationM: Double) : GuidanceState()
}

data class GuidanceConfig(
    val offRouteDistanceM: Double = 30.0,
    val offRouteSeconds: Double = 5.0,
    val arriveRadiusM: Double = 25.0,
    val maxBacktrackM: Double = 50.0,
    val lookaheadM: Double = 400.0,
) {
    init {
        require(offRouteDistanceM.isFinite() && offRouteDistanceM > 0.0)
        require(offRouteSeconds.isFinite() && offRouteSeconds >= 0.0)
        require(arriveRadiusM.isFinite() && arriveRadiusM >= 0.0)
        require(maxBacktrackM.isFinite() && maxBacktrackM >= 0.0)
        require(lookaheadM.isFinite() && lookaheadM > 0.0)
    }
}

/**
 * Causal turn-by-turn tracker. Hidden state is last along-track progress
 * plus the off-route hold clock. Call [update] at 10 Hz with the fused
 * estimate. [headingRad] and [speedMps] may be null. [timestampNs] is the
 * estimator clock in integer nanoseconds.
 */
class RouteGuidance(
    private val route: GuidanceRoute,
    private val config: GuidanceConfig = GuidanceConfig(),
) {
    private val geoPoints: List<GeoPoint> = route.points.map {
        GeoPoint(LatitudeDeg(it.latitudeDeg), LongitudeDeg(it.longitudeDeg))
    }
    private val headingsRad: DoubleArray
    private val routeLengthM: Double
    private val vertexAlongM: DoubleArray
    private val stepAlongM: DoubleArray
    private val routeSpeedMps: Double?

    private var lastAlongM: Double? = null
    private var lastTimestampNs: Long? = null
    private var offRouteSinceNs: Long? = null
    private var lastSnap: Snap? = null
    private var lastState: GuidanceState? = null

    init {
        val built = polylineLengthAndHeadings(geoPoints)
        routeLengthM = built.first
        headingsRad = built.second
        vertexAlongM = DoubleArray(route.points.size)
        for (i in 0 until headingsRad.size) {
            val a = route.points[i]
            val b = route.points[i + 1]
            vertexAlongM[i + 1] = vertexAlongM[i] + Wgs84.distanceMetres(
                a.latitudeDeg, a.longitudeDeg, b.latitudeDeg, b.longitudeDeg,
            )
        }
        stepAlongM = DoubleArray(route.steps.size) { i ->
            vertexAlongM[route.steps[i].startIndex]
        }
        routeSpeedMps = averageSpeedMps(route.totalDistanceM, route.totalDurationS)
    }

    fun update(
        latitudeDeg: Double,
        longitudeDeg: Double,
        headingRad: Double?,
        speedMps: Double?,
        timestampNs: Long,
    ): GuidanceState {
        require(latitudeDeg.isFinite() && latitudeDeg in -90.0..90.0)
        require(longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0)
        headingRad?.let { require(it.isFinite()) { "heading_rad must be finite when present" } }
        speedMps?.let {
            require(it.isFinite() && it >= 0.0) { "speed_mps must be finite and non-negative when present" }
        }
        require(timestampNs >= 0L) { "timestamp_ns must be non-negative" }
        val prevTs = lastTimestampNs
        require(prevTs == null || timestampNs >= prevTs) { "timestamp_ns must be causal" }
        lastTimestampNs = timestampNs

        val dest = route.points.last()
        val distToDestM = Wgs84.distanceMetres(
            latitudeDeg, longitudeDeg, dest.latitudeDeg, dest.longitudeDeg,
        )
        val progress = lastAlongM
        val heading = headingRad?.let { wrapHeadingRad(it) }
        val window = searchWindow(progress)
        val inWindow = collectCandidates(latitudeDeg, longitudeDeg, window.lo, window.hi)
        val windowSnap = selectCandidate(inWindow, heading, progress)
        val chosen = windowSnap ?: nearestOnRoute(latitudeDeg, longitudeDeg)
        val alongM = chosen.alongM.coerceIn(0.0, routeLengthM)

        if (distToDestM <= config.arriveRadiusM && alongM >= lastStepStartM() - ALONG_EPS_M) {
            offRouteSinceNs = null
            lastAlongM = max(progress ?: 0.0, alongM)
            lastSnap = chosen
            val state = GuidanceState.Arrived(distToDestM)
            lastState = state
            return state
        }

        val absCross = abs(chosen.crossTrackM)
        val farFromRoute = windowSnap == null || absCross > config.offRouteDistanceM
        if (!farFromRoute) {
            offRouteSinceNs = null
        } else if (offRouteSinceNs == null) {
            offRouteSinceNs = timestampNs
        }
        val sinceS = offRouteHoldS(timestampNs)
        if (farFromRoute && sinceS >= config.offRouteSeconds) {
            val state = GuidanceState.OffRoute(chosen.crossTrackM, sinceS)
            lastState = state
            return state
        }

        val committed = windowSnap ?: lastSnap ?: chosen
        val commitAlong = committed.alongM.coerceIn(0.0, routeLengthM)
        val remainingM = (routeLengthM - commitAlong).coerceAtLeast(0.0)
        val nextStep = nextStepIndex(commitAlong)
        val onRoute = GuidanceState.OnRoute(
            snappedLatitudeDeg = committed.latitudeDeg,
            snappedLongitudeDeg = committed.longitudeDeg,
            segmentIndex = committed.segmentIndex,
            distanceAlongM = commitAlong,
            remainingM = remainingM,
            remainingS = remainingSeconds(commitAlong),
            nextStepIndex = nextStep,
            distanceToNextStepM = (stepAlongM[nextStep] - commitAlong).coerceAtLeast(0.0),
            crossTrackM = committed.crossTrackM,
        )
        if (windowSnap != null) {
            lastAlongM = commitAlong
            lastSnap = committed
        }
        lastState = onRoute
        return onRoute
    }

    fun current(): GuidanceState? = lastState

    fun remainingPoints(): List<RoutePoint> {
        val snap = lastSnap
        val state = lastState
        if (state is GuidanceState.Arrived) {
            return listOf(route.points.last())
        }
        if (snap == null) {
            return route.points
        }
        val rest = ArrayList<RoutePoint>(route.points.size - snap.segmentIndex)
        rest.add(RoutePoint(snap.latitudeDeg, snap.longitudeDeg))
        for (i in (snap.segmentIndex + 1) until route.points.size) {
            val p = route.points[i]
            val prev = rest.last()
            val gap = Wgs84.distanceMetres(
                prev.latitudeDeg, prev.longitudeDeg, p.latitudeDeg, p.longitudeDeg,
            )
            if (gap >= VERTEX_MERGE_M) {
                rest.add(p)
            }
        }
        return rest
    }

    private fun searchWindow(progressM: Double?): Window {
        if (progressM == null) {
            return Window(0.0, routeLengthM)
        }
        return Window(
            lo = (progressM - config.maxBacktrackM).coerceAtLeast(0.0),
            hi = (progressM + config.lookaheadM).coerceAtMost(routeLengthM),
        )
    }

    private fun collectCandidates(
        latitudeDeg: Double,
        longitudeDeg: Double,
        windowLo: Double,
        windowHi: Double,
    ): List<Snap> {
        val found = ArrayList<Snap>()
        for (i in 0 until headingsRad.size) {
            val segStart = vertexAlongM[i]
            val segEnd = vertexAlongM[i + 1]
            if (segEnd - segStart < 1e-9) {
                continue
            }
            val lo = max(segStart, windowLo)
            val hi = min(segEnd, windowHi)
            if (hi < lo - 1e-9) {
                continue
            }
            val hit = projectOntoLatLonSegment(
                latitudeDeg, longitudeDeg,
                route.points[i].latitudeDeg, route.points[i].longitudeDeg,
                route.points[i + 1].latitudeDeg, route.points[i + 1].longitudeDeg,
            )
            val alongRaw = segStart + hit.alongM
            val along = alongRaw.coerceIn(lo, hi)
            if (abs(along - alongRaw) <= ALONG_EPS_M) {
                found.add(
                    Snap(
                        segmentIndex = i,
                        alongM = alongRaw.coerceIn(0.0, routeLengthM),
                        crossTrackM = hit.crossTrackM,
                        latitudeDeg = hit.latitudeDeg,
                        longitudeDeg = hit.longitudeDeg,
                        headingRad = headingsRad[i],
                    ),
                )
                continue
            }
            val t = ((along - segStart) / (segEnd - segStart)).coerceIn(0.0, 1.0)
            val clamped = interpolate(route.points[i], route.points[i + 1], t)
            val eastNorth = Wgs84.northEastMetres(
                latitudeDeg, longitudeDeg, clamped.latitudeDeg, clamped.longitudeDeg,
            )
            val signed = signedCrossToPoint(
                latitudeDeg, longitudeDeg,
                route.points[i], route.points[i + 1],
                hypot(eastNorth.first, eastNorth.second),
            )
            found.add(
                Snap(
                    segmentIndex = i,
                    alongM = along,
                    crossTrackM = signed,
                    latitudeDeg = clamped.latitudeDeg,
                    longitudeDeg = clamped.longitudeDeg,
                    headingRad = headingsRad[i],
                ),
            )
        }
        return found
    }

    private fun nearestOnRoute(latitudeDeg: Double, longitudeDeg: Double): Snap {
        var best: Snap? = null
        var bestAbs = Double.POSITIVE_INFINITY
        for (i in 0 until headingsRad.size) {
            val segStart = vertexAlongM[i]
            if (vertexAlongM[i + 1] - segStart < 1e-9) {
                continue
            }
            val hit = projectOntoLatLonSegment(
                latitudeDeg, longitudeDeg,
                route.points[i].latitudeDeg, route.points[i].longitudeDeg,
                route.points[i + 1].latitudeDeg, route.points[i + 1].longitudeDeg,
            )
            val absCross = abs(hit.crossTrackM)
            if (absCross < bestAbs) {
                bestAbs = absCross
                best = Snap(
                    segmentIndex = i,
                    alongM = (segStart + hit.alongM).coerceIn(0.0, routeLengthM),
                    crossTrackM = hit.crossTrackM,
                    latitudeDeg = hit.latitudeDeg,
                    longitudeDeg = hit.longitudeDeg,
                    headingRad = headingsRad[i],
                )
            }
        }
        return checkNotNull(best) { "route has no measurable segment" }
    }

    private fun selectCandidate(
        candidates: List<Snap>,
        headingRad: Double?,
        lastAlong: Double?,
    ): Snap? {
        if (candidates.isEmpty()) {
            return null
        }
        val bestCross = candidates.minOf { abs(it.crossTrackM) }
        val close = candidates.filter { abs(it.crossTrackM) <= bestCross + HEADING_TIE_M }
        val pool = if (headingRad != null && close.size > 1) {
            val bestHead = close.minOf { headingDeltaRad(headingRad, it.headingRad) }
            close.filter { headingDeltaRad(headingRad, it.headingRad) <= bestHead + 1e-9 }
        } else {
            close
        }
        if (lastAlong != null) {
            val forward = pool.filter { it.alongM >= lastAlong - ALONG_EPS_M }
            val pick = if (forward.isNotEmpty()) forward else pool
            return pick.minBy { abs(it.alongM - lastAlong) }
        }
        return pool.minBy { it.alongM }
    }

    private fun nextStepIndex(alongM: Double): Int {
        for (i in stepAlongM.indices) {
            if (stepAlongM[i] > alongM + ALONG_EPS_M) {
                return i
            }
        }
        return route.steps.lastIndex
    }

    private fun lastStepStartM(): Double = stepAlongM.last()

    private fun remainingSeconds(alongM: Double): Double {
        var seconds = 0.0
        for (i in route.steps.indices) {
            val start = stepAlongM[i]
            val end = if (i + 1 < stepAlongM.size) stepAlongM[i + 1] else routeLengthM
            val lo = max(alongM, start)
            if (end <= lo + ALONG_EPS_M) {
                continue
            }
            val remainM = end - lo
            val speed = stepSpeedMps(route.steps[i]) ?: routeSpeedMps
            if (speed != null && speed > 0.0) {
                seconds += remainM / speed
            } else if (route.steps[i].durationS > 0.0 && alongM <= start + ALONG_EPS_M) {
                seconds += route.steps[i].durationS
            }
        }
        return seconds
    }

    private fun offRouteHoldS(timestampNs: Long): Double {
        val started = offRouteSinceNs ?: return 0.0
        return ((timestampNs - started).toDouble() / NS_PER_S).coerceAtLeast(0.0)
    }

    private data class Window(val lo: Double, val hi: Double)

    private data class Snap(
        val segmentIndex: Int,
        val alongM: Double,
        val crossTrackM: Double,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val headingRad: Double,
    )

    private companion object {
        const val NS_PER_S: Double = 1_000_000_000.0
        const val HEADING_TIE_M: Double = 4.0
        const val ALONG_EPS_M: Double = 1e-6
        const val VERTEX_MERGE_M: Double = 0.25
    }
}

private fun averageSpeedMps(distanceM: Double, durationS: Double): Double? {
    if (distanceM > 0.0 && durationS > 0.0) {
        return distanceM / durationS
    }
    return null
}

private fun stepSpeedMps(step: RouteStep): Double? = averageSpeedMps(step.distanceM, step.durationS)

private fun interpolate(a: RoutePoint, b: RoutePoint, t: Double): RoutePoint {
    val (north, east) = Wgs84.northEastMetres(
        a.latitudeDeg, a.longitudeDeg, b.latitudeDeg, b.longitudeDeg,
    )
    val (lat, lon) = Wgs84.offsetMetres(a.latitudeDeg, a.longitudeDeg, north * t, east * t)
    return RoutePoint(lat, lon)
}

private fun signedCrossToPoint(
    queryLat: Double,
    queryLon: Double,
    a: RoutePoint,
    b: RoutePoint,
    distanceM: Double,
): Double {
    val (an, ae) = Wgs84.northEastMetres(queryLat, queryLon, a.latitudeDeg, a.longitudeDeg)
    val (bn, be) = Wgs84.northEastMetres(queryLat, queryLon, b.latitudeDeg, b.longitudeDeg)
    val abE = be - ae
    val abN = bn - an
    val segLen = hypot(abE, abN)
    if (segLen < 1e-12) {
        return distanceM
    }
    val signed = (abE * an - abN * ae) / segLen
    return if (signed >= 0.0) distanceM else -distanceM
}
