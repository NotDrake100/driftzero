package `in`.driftzero.core

import kotlin.math.PI

/**
 * Conservative map constraint while GNSS is held or stale. Heading may be
 * pulled to a MATCHED edge. Road DNA may inject a 1-dof along-track heal.
 * Ambiguous or unmatched hypotheses do not snap. They raise uncertainty.
 *
 * Lat/lon from the matcher never enter the ESKF. This is not an IO-VNBD
 * claim. Official screening has no team OSM pack.
 */
object MapCoastConstraint {
    const val UNCONSTRAINED_POS_SIGMA_M: Double = 4.0
    const val UNCONSTRAINED_HEADING_SIGMA_RAD: Double = 8.0 * PI / 180.0
    const val MIN_INTERVAL_NS: Long = 2_000_000_000L
    const val CANDIDATE_RADIUS_M: Double = 40.0
    const val MAX_DNA_CANDIDATES: Int = 8

    private val inflatingSkips: Set<RoadHeadingSkipReason> = setOf(
        RoadHeadingSkipReason.NOT_MATCHED,
        RoadHeadingSkipReason.POSTERIOR_BELOW_MIN,
        RoadHeadingSkipReason.SECOND_BEST_AMBIGUOUS,
        RoadHeadingSkipReason.NEAR_JUNCTION,
        RoadHeadingSkipReason.MISSING_EDGE_BEARING,
    )

    fun decide(
        match: MapMatchResult,
        headingRad: Double,
        speedMps: Double,
        observed: RoadDnaSignature?,
        candidates: List<RoadDnaSignature>,
        config: HmmMatchConfig = HmmMatchConfig(),
    ): MapCoastAction {
        require(headingRad.isFinite()) { "filter heading must be finite" }
        require(speedMps.isFinite() && speedMps >= 0.0) { "speed_mps must be finite and non-negative" }
        val heading = RoadHeadingAid.decide(match, headingRad, speedMps, config)
        if (match.match.status == MapMatchStatus.NO_MAP) {
            return MapCoastAction(heading = heading, heal = null, unconstrained = false)
        }
        if (heading.skipReason != null && heading.skipReason in inflatingSkips) {
            return MapCoastAction(heading = heading, heal = null, unconstrained = true)
        }
        val heal = if (heading.prior != null && observed != null && candidates.isNotEmpty()) {
            RoadDna.decideHeal(observed, candidates)
        } else {
            null
        }
        return MapCoastAction(heading = heading, heal = heal, unconstrained = false)
    }

    fun candidateSignatures(
        graph: RoadGraph?,
        match: MapMatchResult,
        latitudeDeg: Double,
        longitudeDeg: Double,
    ): List<RoadDnaSignature> {
        if (graph == null || graph.isEmpty()) {
            return emptyList()
        }
        if (match.match.status != MapMatchStatus.MATCHED) {
            return emptyList()
        }
        val picked = LinkedHashSet<String>()
        match.match.roadSegmentId?.let { picked.add(it) }
        match.secondRoadSegmentId?.let { picked.add(it) }
        val edges = ArrayList<GraphEdge>(MAX_DNA_CANDIDATES)
        for (id in picked) {
            val edge = graph.edgesById[id] ?: continue
            edges.add(edge)
            if (edges.size >= MAX_DNA_CANDIDATES) {
                return edges.map { RoadDna.fromEdge(it) }
            }
        }
        for (edge in graph.edgesNear(latitudeDeg, longitudeDeg, CANDIDATE_RADIUS_M)) {
            if (!picked.add(edge.id)) {
                continue
            }
            edges.add(edge)
            if (edges.size >= MAX_DNA_CANDIDATES) {
                break
            }
        }
        return edges.map { RoadDna.fromEdge(it) }
    }
}

data class MapCoastAction(
    val heading: RoadHeadingDecision,
    val heal: RoadDnaHeal?,
    val unconstrained: Boolean,
)

data class MapCoastReport(
    val action: MapCoastAction,
    val headingAccepted: Boolean,
    val healAccepted: Boolean,
    val inflated: Boolean,
)

/**
 * Live and in-app replay owner of Road DNA memory plus rate limits.
 * Official [Replay] does not construct this unless a graph is attached to
 * [DeadReckoningEngine].
 */
class MapCoastSession {
    private val dnaTracker = RoadDnaTracker()
    private var graph: RoadGraph? = null
    private var lastHealNs: Long = Long.MIN_VALUE
    private var lastInflateNs: Long = Long.MIN_VALUE
    private var lastMatchNs: Long = Long.MIN_VALUE
    private var lastMatcher: RoadMatcher? = null
    private var lastMatchGraph: RoadGraph? = null
    private var lastMatch: MapMatchResult? = null
    private var lastApplyNs: Long = Long.MIN_VALUE
    private var lastReport: MapCoastReport? = null

    /** One HMM observation per sensor epoch, shared by live, engine, and replay. */
    fun match(pose: NavigationState, matcher: RoadMatcher?, graph: RoadGraph?): MapMatchResult? {
        if (matcher == null || graph == null || graph.isEmpty()) return null
        if (matcher === lastMatcher && graph === lastMatchGraph && pose.timestamp.value <= lastMatchNs) {
            return lastMatch
        }
        val result = matcher.update(FilterSnapshot(pose), graph)
        lastMatcher = matcher
        lastMatchGraph = graph
        lastMatchNs = pose.timestamp.value
        lastMatch = result
        return result
    }

    fun setGraph(next: RoadGraph?) {
        graph = if (next == null || next.isEmpty()) null else next
        resetMemory()
    }

    fun reset() {
        resetMemory()
    }

    fun observe(
        latitudeDeg: Double,
        longitudeDeg: Double,
        headingRad: Double,
        yawRateRadps: Double = 0.0,
        bump: Boolean = false,
        stopped: Boolean = false,
    ) {
        if (graph == null) {
            return
        }
        dnaTracker.add(
            latitudeDeg = latitudeDeg,
            longitudeDeg = longitudeDeg,
            headingRad = headingRad,
            yawRateRadps = yawRateRadps,
            bump = bump,
            stopped = stopped,
        )
    }

    /**
     * Apply heading, along-track heal, or unconstrained inflate. Call only
     * while coasting. The matcher result must already be computed from the
     * unconstrained ESKF pose for this epoch.
     */
    fun apply(
        filter: DeadReckoningFilter,
        match: MapMatchResult,
        pose: NavigationState,
        nowNs: Long,
    ): MapCoastReport {
        val previous = lastReport
        if (previous != null && nowNs <= lastApplyNs) {
            return previous.copy(headingAccepted = false, healAccepted = false, inflated = false)
        }
        val headingRad = pose.motion.heading.value
        val speedMps = pose.motion.speed.value
        if (!headingRad.isFinite() || !speedMps.isFinite() || speedMps < 0.0) {
            return MapCoastReport(
                action = MapCoastAction(
                    heading = RoadHeadingDecision(null, RoadHeadingSkipReason.NOT_MATCHED),
                    heal = null,
                    unconstrained = false,
                ),
                headingAccepted = false,
                healAccepted = false,
                inflated = false,
            )
        }
        val active = graph
        val action = MapCoastConstraint.decide(
            match = match,
            headingRad = headingRad,
            speedMps = speedMps,
            observed = dnaTracker.signature(),
            candidates = MapCoastConstraint.candidateSignatures(
                graph = active,
                match = match,
                latitudeDeg = pose.position.latitude.value,
                longitudeDeg = pose.position.longitude.value,
            ),
        )
        val report = if (action.unconstrained) {
            val due = lastInflateNs == Long.MIN_VALUE ||
                nowNs - lastInflateNs >= MapCoastConstraint.MIN_INTERVAL_NS
            val inflated = if (due) {
                filter.noteMapUnconstrained()
            } else {
                false
            }
            if (inflated) {
                lastInflateNs = nowNs
            }
            MapCoastReport(
                action = action,
                headingAccepted = false,
                healAccepted = false,
                inflated = inflated,
            )
        } else {
            val headingAccepted = action.heading.prior?.let { prior ->
                filter.applyRoadHeading(prior.edgeBearingRad, prior.stdRad, prior.alongTrackSpeedHintMps)
            }?.accepted == true
            val heal = action.heal
            val healDue = heal != null &&
                (lastHealNs == Long.MIN_VALUE || nowNs - lastHealNs >= MapCoastConstraint.MIN_INTERVAL_NS)
            val healAccepted = if (healDue && heal != null) {
                filter.applyAlongTrack(heal.offsetM, heal.stdM).accepted
            } else {
                false
            }
            if (healAccepted) {
                lastHealNs = nowNs
            }
            MapCoastReport(
                action = action,
                headingAccepted = headingAccepted,
                healAccepted = healAccepted,
                inflated = false,
            )
        }
        if (active != null) {
            observe(
                latitudeDeg = pose.position.latitude.value,
                longitudeDeg = pose.position.longitude.value,
                headingRad = headingRad,
                yawRateRadps = pose.motion.yawRateRadps ?: 0.0,
                stopped = pose.health.flags.contains(DeadReckoningFilter.FLAG_ZUPT),
            )
        }
        lastApplyNs = nowNs
        lastReport = report
        return report
    }

    private fun resetMemory() {
        dnaTracker.reset()
        lastHealNs = Long.MIN_VALUE
        lastInflateNs = Long.MIN_VALUE
        lastMatchNs = Long.MIN_VALUE
        lastMatcher = null
        lastMatchGraph = null
        lastMatch = null
        lastApplyNs = Long.MIN_VALUE
        lastReport = null
    }
}
