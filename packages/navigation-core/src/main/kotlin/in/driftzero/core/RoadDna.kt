package `in`.driftzero.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Causal road signature from IMU heading and map polylines. Ambiguous
 * alignment does not snap. A unique match may request a gated along-track
 * correction. This is not an IO-VNBD claim.
 */
enum class RoadDnaKind {
    TURN,
    STRAIGHT,
    GRADE,
    CURVE,
    BUMP,
    STOP,
}

data class RoadDnaEvent(
    val kind: RoadDnaKind,
    val magnitude: Double,
    val lengthM: Double,
) {
    init {
        require(magnitude.isFinite() && magnitude >= 0.0) { "magnitude must be finite and >= 0" }
        require(lengthM.isFinite() && lengthM >= 0.0) { "lengthM must be finite and >= 0" }
    }
}

data class RoadDnaSignature(
    val events: List<RoadDnaEvent>,
    val travelledM: Double,
) {
    init {
        require(travelledM.isFinite() && travelledM >= 0.0)
    }
}

data class RoadDnaAlignment(
    val cost: Double,
    val alongTrackOffsetM: Double,
    val unique: Boolean,
)

data class RoadDnaHeal(
    val offsetM: Double,
    val stdM: Double,
    val cost: Double,
)

object RoadDna {
    const val TURN_RAD: Double = 35.0 * PI / 180.0
    const val CURVE_RATE_RADPS: Double = 0.05
    const val STRAIGHT_RAD: Double = 8.0 * PI / 180.0
    const val AMBIGUOUS_RATIO: Double = 0.85
    const val MAX_OFFSET_M: Double = 80.0
    const val HEAL_STD_M: Double = 8.0
    const val KIND_MISMATCH: Double = 4.0

    fun extract(
        headingsRad: DoubleArray,
        distancesM: DoubleArray,
        yawRatesRadps: DoubleArray? = null,
        pitchRad: DoubleArray? = null,
        bump: BooleanArray? = null,
        stopped: BooleanArray? = null,
    ): RoadDnaSignature {
        require(headingsRad.size == distancesM.size) { "heading and distance length" }
        require(headingsRad.isNotEmpty()) { "need at least one sample" }
        val events = ArrayList<RoadDnaEvent>()
        var i = 0
        var travelled = 0.0
        var straightLen = 0.0
        var straightYaw = 0.0
        fun flushStraight() {
            if (straightLen > 0.0) {
                events.add(RoadDnaEvent(RoadDnaKind.STRAIGHT, abs(straightYaw), straightLen))
                travelled += straightLen
                straightLen = 0.0
                straightYaw = 0.0
            }
        }
        while (i < headingsRad.size) {
            if (stopped != null && i < stopped.size && stopped[i]) {
                flushStraight()
                var len = 0.0
                while (i < headingsRad.size && stopped[i]) {
                    len += distancesM[i]
                    i += 1
                }
                events.add(RoadDnaEvent(RoadDnaKind.STOP, 0.0, len))
                travelled += len
                continue
            }
            if (bump != null && i < bump.size && bump[i]) {
                flushStraight()
                events.add(RoadDnaEvent(RoadDnaKind.BUMP, 1.0, distancesM[i]))
                travelled += distancesM[i]
                i += 1
                continue
            }
            if (pitchRad != null && i < pitchRad.size && abs(pitchRad[i]) > 8.0 * PI / 180.0) {
                flushStraight()
                events.add(RoadDnaEvent(RoadDnaKind.GRADE, abs(pitchRad[i]), distancesM[i]))
                travelled += distancesM[i]
                i += 1
                continue
            }
            if (i < headingsRad.size - 1) {
                val d = signedHeadingDeltaRad(headingsRad[i], headingsRad[i + 1])
                val rate = yawRatesRadps?.getOrNull(i)
                if (abs(d) >= TURN_RAD) {
                    straightLen += distancesM[i]
                    flushStraight()
                    val turnLen = max(distancesM[i + 1], 1.0)
                    events.add(RoadDnaEvent(RoadDnaKind.TURN, abs(d), turnLen))
                    travelled += turnLen
                    i += 2
                    continue
                }
                if (abs(d) >= STRAIGHT_RAD || (rate != null && abs(rate) >= CURVE_RATE_RADPS)) {
                    straightLen += distancesM[i]
                    flushStraight()
                    var yaw = d
                    var len = distancesM[i + 1]
                    var j = i + 1
                    while (j < headingsRad.size - 1) {
                        val step = signedHeadingDeltaRad(headingsRad[j], headingsRad[j + 1])
                        if (abs(step) < STRAIGHT_RAD * 0.4) {
                            break
                        }
                        if (abs(step) >= TURN_RAD) {
                            break
                        }
                        yaw += step
                        len += distancesM[j + 1]
                        j += 1
                    }
                    events.add(RoadDnaEvent(RoadDnaKind.CURVE, abs(yaw), len))
                    travelled += len
                    i = j + 1
                    continue
                }
                straightYaw += d
            }
            straightLen += distancesM[i]
            i += 1
        }
        flushStraight()
        return RoadDnaSignature(events, travelled)
    }

    fun fromEdge(edge: GraphEdge): RoadDnaSignature {
        val headings = edge.segmentHeadingsRad
        val events = ArrayList<RoadDnaEvent>()
        var travelled = 0.0
        for (i in 0 until edge.points.size - 1) {
            val a = edge.points[i]
            val b = edge.points[i + 1]
            val (n, e) = Wgs84.northEastMetres(
                a.latitude.value, a.longitude.value,
                b.latitude.value, b.longitude.value,
            )
            val seg = hypot(n, e)
            if (i > 0) {
                val yaw = abs(signedHeadingDeltaRad(headings[i - 1], headings[i]))
                if (yaw >= TURN_RAD) {
                    events.add(RoadDnaEvent(RoadDnaKind.TURN, yaw, min(seg, 25.0)))
                } else if (yaw >= STRAIGHT_RAD) {
                    events.add(RoadDnaEvent(RoadDnaKind.CURVE, yaw, seg))
                }
            }
            events.add(RoadDnaEvent(RoadDnaKind.STRAIGHT, 0.0, seg))
            travelled += seg
        }
        return RoadDnaSignature(events, travelled)
    }

    /**
     * Align [observed] to [candidate]. [alongTrackOffsetM] is observed
     * travelled minus the candidate length at the aligned turn, so a late
     * DR odometer is positive.
     */
    fun align(observed: RoadDnaSignature, candidate: RoadDnaSignature): RoadDnaAlignment? {
        if (observed.events.isEmpty() || candidate.events.isEmpty()) {
            return null
        }
        val cost = dtw(observed.events, candidate.events)
        val obsTurn = firstTurnDistance(observed)
        val candTurn = firstTurnDistance(candidate)
        val offset = if (obsTurn != null && candTurn != null) {
            obsTurn - candTurn
        } else {
            observed.travelledM - candidate.travelledM
        }
        return RoadDnaAlignment(cost = cost, alongTrackOffsetM = offset, unique = true)
    }

    fun decideHeal(
        observed: RoadDnaSignature,
        candidates: List<RoadDnaSignature>,
        maxOffsetM: Double = MAX_OFFSET_M,
    ): RoadDnaHeal? {
        if (candidates.isEmpty()) {
            return null
        }
        val distinctive = observed.events.any {
            it.kind == RoadDnaKind.TURN || it.kind == RoadDnaKind.CURVE
        }
        if (!distinctive) {
            return null
        }
        val scored = ArrayList<RoadDnaAlignment>(candidates.size)
        for (cand in candidates) {
            val a = align(observed, cand) ?: continue
            scored.add(a)
        }
        if (scored.isEmpty()) {
            return null
        }
        scored.sortBy { it.cost }
        val best = scored[0]
        if (scored.size >= 2 && scored[1].cost <= best.cost * (1.0 / AMBIGUOUS_RATIO)) {
            return null
        }
        if (abs(best.alongTrackOffsetM) > maxOffsetM) {
            return null
        }
        return RoadDnaHeal(
            offsetM = -best.alongTrackOffsetM,
            stdM = HEAL_STD_M,
            cost = best.cost,
        )
    }

    private fun firstTurnDistance(sig: RoadDnaSignature): Double? {
        var walked = 0.0
        for (event in sig.events) {
            if (event.kind == RoadDnaKind.TURN || event.kind == RoadDnaKind.CURVE) {
                return walked + 0.5 * event.lengthM
            }
            walked += event.lengthM
        }
        return null
    }

    private fun dtw(a: List<RoadDnaEvent>, b: List<RoadDnaEvent>): Double {
        val n = a.size
        val m = b.size
        val inf = 1.0e9
        val dp = Array(n + 1) { DoubleArray(m + 1) { inf } }
        dp[0][0] = 0.0
        for (i in 1..n) {
            for (j in 1..m) {
                val c = eventCost(a[i - 1], b[j - 1])
                dp[i][j] = c + min(dp[i - 1][j], min(dp[i][j - 1], dp[i - 1][j - 1]))
            }
        }
        return dp[n][m] / sqrt((n * m).toDouble())
    }

    private fun eventCost(a: RoadDnaEvent, b: RoadDnaEvent): Double {
        val kind = if (a.kind == b.kind) {
            0.0
        } else if (similarKind(a.kind, b.kind)) {
            1.0
        } else {
            KIND_MISMATCH
        }
        val mag = relErr(a.magnitude, b.magnitude)
        val len = relErr(a.lengthM, b.lengthM)
        return kind + mag + 0.5 * len
    }

    private fun similarKind(a: RoadDnaKind, b: RoadDnaKind): Boolean {
        val pair = setOf(a, b)
        return pair == setOf(RoadDnaKind.TURN, RoadDnaKind.CURVE) ||
            pair == setOf(RoadDnaKind.STRAIGHT, RoadDnaKind.CURVE)
    }

    private fun relErr(a: Double, b: Double): Double {
        val den = max(1e-3, max(a, b))
        return abs(a - b) / den
    }
}

/** Accumulates heading and distance for a live [RoadDnaSignature]. */
class RoadDnaTracker {
    private val headings = ArrayList<Double>()
    private val distances = ArrayList<Double>()
    private val yawRates = ArrayList<Double>()
    private val bumps = ArrayList<Boolean>()
    private val stops = ArrayList<Boolean>()
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    fun reset() {
        headings.clear()
        distances.clear()
        yawRates.clear()
        bumps.clear()
        stops.clear()
        lastLat = null
        lastLon = null
    }

    fun add(
        latitudeDeg: Double,
        longitudeDeg: Double,
        headingRad: Double,
        yawRateRadps: Double = 0.0,
        bump: Boolean = false,
        stopped: Boolean = false,
    ) {
        if (!headingRad.isFinite()) {
            return
        }
        val prevLat = lastLat
        val prevLon = lastLon
        val step = if (prevLat == null || prevLon == null) {
            0.0
        } else {
            Wgs84.distanceMetres(prevLat, prevLon, latitudeDeg, longitudeDeg)
        }
        headings.add(headingRad)
        distances.add(step)
        yawRates.add(yawRateRadps)
        bumps.add(bump)
        stops.add(stopped)
        lastLat = latitudeDeg
        lastLon = longitudeDeg
        while (headings.size > MAX_SAMPLES) {
            headings.removeAt(0)
            distances.removeAt(0)
            yawRates.removeAt(0)
            bumps.removeAt(0)
            stops.removeAt(0)
        }
    }

    companion object {
        const val MAX_SAMPLES: Int = 256
    }

    fun signature(): RoadDnaSignature? {
        if (headings.size < 2) {
            return null
        }
        return RoadDna.extract(
            headingsRad = headings.toDoubleArray(),
            distancesM = distances.toDoubleArray(),
            yawRatesRadps = yawRates.toDoubleArray(),
            bump = bumps.toBooleanArray(),
            stopped = stops.toBooleanArray(),
        )
    }
}
