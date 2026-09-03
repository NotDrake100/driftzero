package `in`.driftzero.core

import kotlin.math.PI
import kotlin.math.max

/**
 * Heading-only map aid for the ESKF. Never returns a position.
 *
 * The matcher overlay ([MapMatchResult.displayPose]) is not a filter state.
 * When [decide] returns a [RoadHeadingPrior], the filter owner applies it as a
 * 1-dof yaw measurement (`applyRoadHeading(prior)`): Joseph form on the
 * heading error state, with a 1-dof chi-square gate. Lat/lon from the graph
 * must not be written into the ESKF.
 *
 * Std: `max(MIN_STD_RAD, STD_SCALE_RAD * (1 - p) / p)` with
 * [MIN_STD_RAD] = 3 deg. At posterior 1 the formula hits the floor. At
 * posterior 0.55 it is about 0.29 rad.
 */
object RoadHeadingAid {
    /** Floor on heading measurement std. 3 deg. */
    const val MIN_STD_RAD: Double = 3.0 * PI / 180.0

    /**
     * Scale in `std = max(floor, scale * (1-p)/p)`. Chosen so a just-matched
     * posterior (0.55) is a loose heading prior, not a snap.
     */
    const val STD_SCALE_RAD: Double = 0.35

    /** Same numeric gate as [HmmMatchConfig.headingMinSpeedMps]. */
    const val MIN_SPEED_MPS: Double = 1.0

    fun decide(
        result: MapMatchResult,
        filterHeadingRad: Double,
        speedMps: Double,
        config: HmmMatchConfig = HmmMatchConfig(),
    ): RoadHeadingDecision {
        require(filterHeadingRad.isFinite()) { "filter heading must be finite" }
        require(speedMps.isFinite() && speedMps >= 0.0) { "speed_mps must be finite and non-negative" }
        if (result.match.status != MapMatchStatus.MATCHED) {
            return RoadHeadingDecision(prior = null, skipReason = RoadHeadingSkipReason.NOT_MATCHED)
        }
        if (result.bestPosterior < config.matchedMinPosterior) {
            return RoadHeadingDecision(prior = null, skipReason = RoadHeadingSkipReason.POSTERIOR_BELOW_MIN)
        }
        if (result.secondPosterior > config.ambiguousSecondRatio * result.bestPosterior) {
            return RoadHeadingDecision(prior = null, skipReason = RoadHeadingSkipReason.SECOND_BEST_AMBIGUOUS)
        }
        if (result.nearJunction) {
            return RoadHeadingDecision(prior = null, skipReason = RoadHeadingSkipReason.NEAR_JUNCTION)
        }
        if (speedMps < MIN_SPEED_MPS) {
            return RoadHeadingDecision(prior = null, skipReason = RoadHeadingSkipReason.SPEED_BELOW_MIN)
        }
        val bearing = result.displayPose?.heading?.value
        if (bearing == null || !bearing.isFinite()) {
            return RoadHeadingDecision(prior = null, skipReason = RoadHeadingSkipReason.MISSING_EDGE_BEARING)
        }
        return RoadHeadingDecision(
            prior = RoadHeadingPrior(
                edgeBearingRad = wrapHeadingRad(bearing),
                stdRad = stdRad(result.bestPosterior),
                alongTrackSpeedHintMps = null,
            ),
            skipReason = null,
        )
    }

    /**
     * Measurement std, radians. Shrinks as posterior rises, never below
     * [MIN_STD_RAD] (3 deg).
     */
    fun stdRad(posterior: Double): Double {
        val p = posterior.coerceIn(1e-6, 1.0)
        return max(MIN_STD_RAD, STD_SCALE_RAD * (1.0 - p) / p)
    }

    /**
     * Signed innovation `z - h(x)` in (-π, π]: edge bearing minus filter
     * heading. Filter owner uses this as the 1-dof residual.
     */
    fun innovationRad(filterHeadingRad: Double, edgeBearingRad: Double): Double =
        signedHeadingDeltaRad(filterHeadingRad, edgeBearingRad)
}

/**
 * Soft heading measurement. No latitude, no longitude. [edgeBearingRad] is
 * ENU course, radians clockwise from north, in [0, 2π). [stdRad] is the
 * measurement std in radians. [alongTrackSpeedHintMps] is optional and is
 * null unless a documented speed prior exists on the edge. This object does
 * not carry a snap position.
 */
data class RoadHeadingPrior(
    val edgeBearingRad: Double,
    val stdRad: Double,
    val alongTrackSpeedHintMps: Double? = null,
) {
    init {
        require(edgeBearingRad.isFinite())
        require(edgeBearingRad >= 0.0 && edgeBearingRad < TWO_PI)
        require(stdRad.isFinite() && stdRad >= RoadHeadingAid.MIN_STD_RAD)
        alongTrackSpeedHintMps?.let {
            require(it.isFinite() && it >= 0.0) { "along-track speed hint must be finite and non-negative" }
        }
    }
}

data class RoadHeadingDecision(
    val prior: RoadHeadingPrior?,
    val skipReason: RoadHeadingSkipReason?,
) {
    init {
        require((prior == null) xor (skipReason == null)) {
            "prior and skipReason are mutually exclusive"
        }
    }
}

enum class RoadHeadingSkipReason {
    NOT_MATCHED,
    POSTERIOR_BELOW_MIN,
    SECOND_BEST_AMBIGUOUS,
    NEAR_JUNCTION,
    SPEED_BELOW_MIN,
    MISSING_EDGE_BEARING,
}
