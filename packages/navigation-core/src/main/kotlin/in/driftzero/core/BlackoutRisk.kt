package `in`.driftzero.core

import kotlin.math.max
import kotlin.math.min

/**
 * Causal GNSS-blackout risk from only the inputs that exist. A missing C/N0
 * or map is [OptionalScalar.Unavailable], never 0.
 *
 * Piecewise factors, each in [0, 1] when present:
 * - used sats: 1 if < 4, 0 if >= 8, linear between
 * - mean used C/N0 dB-Hz: 1 if < 20, 0 if > 35 (Unavailable if no C/N0)
 * - horizontal accuracy: 1 if > 30 m, 0 if < 8 m
 * - accuracy trend d(acc)/dt when two samples exist
 * - tunnel ahead: 1 if within 50 m, 0.7 at 280 m, 0 beyond 400 m
 * - shadow occupancy in [0, 1]
 *
 * Combined risk is the mean of available factors. No available factor
 * yields Unavailable.
 *
 * [BlackoutAssessment.preconditioning] is true when the mean risk is at
 * least [PRECONDITION_RISK], or when tunnel / shadow look-ahead is already
 * strong. Healthy GNSS must not dilute a 40 m tunnel or a high-occupancy
 * shadow cell. Live PoseStore arms [DeadReckoningFilter.armCoastPrecondition]
 * from that flag so yaw-speed-hold latches speed before the fix dies.
 */
data class BlackoutInputs(
    val usedSats: Int? = null,
    val meanUsedCn0DbHz: Double? = null,
    val horizontalAccuracyM: Double? = null,
    val accuracyTrendMps: Double? = null,
    val tunnelAheadM: Double? = null,
    val shadowOccupancy: Double? = null,
    val mapPresent: Boolean = false,
)

data class BlackoutAssessment(
    val risk: OptionalScalar,
    val usedSatsFactor: OptionalScalar,
    val cn0Factor: OptionalScalar,
    val accuracyFactor: OptionalScalar,
    val accuracyTrendFactor: OptionalScalar,
    val tunnelFactor: OptionalScalar,
    val shadowFactor: OptionalScalar,
    val tunnelAheadM: OptionalScalar,
    val preconditioning: Boolean,
    val usedFactorCount: Int,
)

object BlackoutRisk {
    const val TUNNEL_NEAR_M: Double = 50.0
    const val TUNNEL_FAR_M: Double = 280.0
    const val TUNNEL_FADE_M: Double = 400.0
    const val TUNNEL_FAR_FACTOR: Double = 0.70
    /** Live PoseStore arms [DeadReckoningFilter.armCoastPrecondition] at this risk. */
    const val PRECONDITION_RISK: Double = 0.55
    /**
     * Tunnel factor at or above this (280 m or closer) arms even when GNSS
     * factors are still healthy. Same numeric bar as [TUNNEL_FAR_FACTOR].
     */
    const val PRECONDITION_TUNNEL_FACTOR: Double = TUNNEL_FAR_FACTOR
    /**
     * Shadow occupancy at or above this arms even when GNSS factors are
     * still healthy. Same numeric bar as [PRECONDITION_RISK].
     */
    const val PRECONDITION_SHADOW: Double = PRECONDITION_RISK
    const val MAX_LOOK_M: Double = 400.0
    const val MAX_HOPS: Int = 32
    const val HEADING_ALIGN_RAD: Double = 60.0 * Math.PI / 180.0

    const val BLACKOUT_COPY_PREFIX: String = "GNSS blackout "
    const val PRECONDITION_COPY: String = "Dead-reckoning preconditioning active"

    fun evaluate(inputs: BlackoutInputs): BlackoutAssessment {
        val used = usedSatsFactor(inputs.usedSats)
        val cn0 = cn0Factor(inputs.meanUsedCn0DbHz)
        val acc = accuracyFactor(inputs.horizontalAccuracyM)
        val trend = accuracyTrendFactor(inputs.accuracyTrendMps)
        val tunnel = tunnelFactor(inputs.tunnelAheadM, inputs.mapPresent)
        val shadow = shadowFactor(inputs.shadowOccupancy)
        val parts = listOf(used, cn0, acc, trend, tunnel, shadow)
        val available = parts.filterIsInstance<OptionalScalar.Available>()
        val risk = if (available.isEmpty()) {
            OptionalScalar.Unavailable("no blackout factors available")
        } else {
            var sum = 0.0
            for (item in available) {
                sum += item.value
            }
            OptionalScalar.Available((sum / available.size).coerceIn(0.0, 1.0))
        }
        val preconditioning = shouldArmCoast(risk, tunnel, shadow)
        val tunnelAhead = when {
            !inputs.mapPresent -> OptionalScalar.Unavailable("no map")
            inputs.tunnelAheadM == null -> OptionalScalar.Unavailable("no tunnel in look-ahead")
            !inputs.tunnelAheadM.isFinite() || inputs.tunnelAheadM < 0.0 ->
                OptionalScalar.Unavailable("tunnel distance invalid")
            else -> OptionalScalar.Available(inputs.tunnelAheadM)
        }
        return BlackoutAssessment(
            risk = risk,
            usedSatsFactor = used,
            cn0Factor = cn0,
            accuracyFactor = acc,
            accuracyTrendFactor = trend,
            tunnelFactor = tunnel,
            shadowFactor = shadow,
            tunnelAheadM = tunnelAhead,
            preconditioning = preconditioning,
            usedFactorCount = available.size,
        )
    }

    /**
     * Arm when mean risk is high, or when tunnel / shadow look-ahead is
     * already strong. A healthy satellite count must not hide a 40 m tunnel.
     */
    fun shouldArmCoast(
        risk: OptionalScalar,
        tunnelFactor: OptionalScalar,
        shadowFactor: OptionalScalar,
    ): Boolean {
        val fromRisk = risk is OptionalScalar.Available && risk.value >= PRECONDITION_RISK
        val fromTunnel =
            tunnelFactor is OptionalScalar.Available && tunnelFactor.value >= PRECONDITION_TUNNEL_FACTOR
        val fromShadow =
            shadowFactor is OptionalScalar.Available && shadowFactor.value >= PRECONDITION_SHADOW
        return fromRisk || fromTunnel || fromShadow
    }

    fun riskLine(assessment: BlackoutAssessment): String? {
        val risk = assessment.risk as? OptionalScalar.Available ?: return null
        return "GNSS blackout ${DriftBudgetMath.percentWhole(risk.value)}"
    }

    fun tunnelLine(assessment: BlackoutAssessment): String? {
        val d = assessment.tunnelAheadM as? OptionalScalar.Available ?: return null
        return "Tunnel ${DriftBudgetMath.formatDistance(d.value)} ahead"
    }

    fun usedSatsFactor(used: Int?): OptionalScalar {
        if (used == null) {
            return OptionalScalar.Unavailable("used satellite count not reported")
        }
        if (used < 0) {
            return OptionalScalar.Unavailable("used satellite count is negative")
        }
        val v = when {
            used < 4 -> 1.0
            used >= 8 -> 0.0
            else -> (8 - used) / 4.0
        }
        return OptionalScalar.Available(v)
    }

    fun cn0Factor(meanUsedCn0DbHz: Double?): OptionalScalar {
        if (meanUsedCn0DbHz == null) {
            return OptionalScalar.Unavailable("no used C/N0")
        }
        if (!meanUsedCn0DbHz.isFinite()) {
            return OptionalScalar.Unavailable("C/N0 is not finite")
        }
        val v = when {
            meanUsedCn0DbHz < 20.0 -> 1.0
            meanUsedCn0DbHz > 35.0 -> 0.0
            else -> (35.0 - meanUsedCn0DbHz) / 15.0
        }
        return OptionalScalar.Available(v)
    }

    fun accuracyFactor(horizontalAccuracyM: Double?): OptionalScalar {
        if (horizontalAccuracyM == null) {
            return OptionalScalar.Unavailable("horizontal accuracy not reported")
        }
        if (!horizontalAccuracyM.isFinite() || horizontalAccuracyM < 0.0) {
            return OptionalScalar.Unavailable("horizontal accuracy is invalid")
        }
        val v = when {
            horizontalAccuracyM > 30.0 -> 1.0
            horizontalAccuracyM < 8.0 -> 0.0
            else -> (horizontalAccuracyM - 8.0) / 22.0
        }
        return OptionalScalar.Available(v)
    }

    fun accuracyTrendFactor(trendMps: Double?): OptionalScalar {
        if (trendMps == null) {
            return OptionalScalar.Unavailable("accuracy trend needs two samples")
        }
        if (!trendMps.isFinite()) {
            return OptionalScalar.Unavailable("accuracy trend is not finite")
        }
        val v = when {
            trendMps >= 2.0 -> 1.0
            trendMps <= 0.0 -> 0.0
            else -> trendMps / 2.0
        }
        return OptionalScalar.Available(v)
    }

    fun tunnelFactor(tunnelAheadM: Double?, mapPresent: Boolean): OptionalScalar {
        if (!mapPresent) {
            return OptionalScalar.Unavailable("no map")
        }
        if (tunnelAheadM == null) {
            return OptionalScalar.Available(0.0)
        }
        if (!tunnelAheadM.isFinite() || tunnelAheadM < 0.0) {
            return OptionalScalar.Unavailable("tunnel distance invalid")
        }
        val d = tunnelAheadM
        val v = when {
            d <= TUNNEL_NEAR_M -> 1.0
            d <= TUNNEL_FAR_M -> {
                val t = (d - TUNNEL_NEAR_M) / (TUNNEL_FAR_M - TUNNEL_NEAR_M)
                1.0 + t * (TUNNEL_FAR_FACTOR - 1.0)
            }
            d <= TUNNEL_FADE_M -> {
                val t = (d - TUNNEL_FAR_M) / (TUNNEL_FADE_M - TUNNEL_FAR_M)
                TUNNEL_FAR_FACTOR * (1.0 - t)
            }
            else -> 0.0
        }
        return OptionalScalar.Available(v.coerceIn(0.0, 1.0))
    }

    fun shadowFactor(occupancy: Double?): OptionalScalar {
        if (occupancy == null) {
            return OptionalScalar.Unavailable("no shadow visits")
        }
        if (!occupancy.isFinite() || occupancy < 0.0 || occupancy > 1.0) {
            return OptionalScalar.Unavailable("shadow occupancy is not in [0, 1]")
        }
        return OptionalScalar.Available(occupancy)
    }

    /**
     * Metres along the matched heading to the first [GraphEdge.tunnel].
     * Unavailable when there is no graph or no current edge.
     * Already on a tunnel returns 0.
     */
    fun tunnelAheadM(
        graph: RoadGraph?,
        edgeId: String?,
        alongM: Double?,
        headingRad: Double,
        maxLookM: Double = MAX_LOOK_M,
    ): OptionalScalar {
        if (graph == null || graph.isEmpty()) {
            return OptionalScalar.Unavailable("no map")
        }
        if (edgeId.isNullOrEmpty()) {
            return OptionalScalar.Unavailable("no current edge")
        }
        if (!headingRad.isFinite()) {
            return OptionalScalar.Unavailable("heading is not finite")
        }
        val start = graph.edges.indexOfFirst { it.id == edgeId }
        if (start < 0) {
            return OptionalScalar.Unavailable("edge not in graph")
        }
        val first = graph.edges[start]
        val along = (alongM ?: 0.0).coerceIn(0.0, first.lengthM)
        if (first.tunnel) {
            return OptionalScalar.Available(0.0)
        }
        var walked = first.lengthM - along
        if (walked > maxLookM) {
            return OptionalScalar.Unavailable("no tunnel in look-ahead")
        }
        var nodeId = first.toNodeId
        var lastHeading = first.segmentHeadingsRad.last()
        var hops = 0
        val seen = HashSet<Int>()
        seen.add(start)
        while (walked <= maxLookM && hops < MAX_HOPS) {
            hops += 1
            val outgoing = graph.outgoing[nodeId] ?: break
            val nextIdx = pickOutgoing(graph, outgoing, lastHeading, headingRad, seen) ?: break
            seen.add(nextIdx)
            val edge = graph.edges[nextIdx]
            if (edge.tunnel) {
                return OptionalScalar.Available(max(0.0, walked))
            }
            walked += edge.lengthM
            nodeId = edge.toNodeId
            lastHeading = edge.segmentHeadingsRad.last()
        }
        return OptionalScalar.Unavailable("no tunnel in look-ahead")
    }

    private fun pickOutgoing(
        graph: RoadGraph,
        outgoing: IntArray,
        lastHeadingRad: Double,
        filterHeadingRad: Double,
        seen: Set<Int>,
    ): Int? {
        var best: Int? = null
        var bestErr = Double.POSITIVE_INFINITY
        for (idx in outgoing) {
            if (idx in seen) {
                continue
            }
            val edge = graph.edges[idx]
            val h = edge.segmentHeadingsRad.first()
            val vsLast = headingDeltaRad(lastHeadingRad, h)
            val vsFilter = headingDeltaRad(filterHeadingRad, h)
            val err = min(vsLast, vsFilter)
            if (err > HEADING_ALIGN_RAD) {
                continue
            }
            if (err < bestErr) {
                bestErr = err
                best = idx
            }
        }
        return best
    }
}
