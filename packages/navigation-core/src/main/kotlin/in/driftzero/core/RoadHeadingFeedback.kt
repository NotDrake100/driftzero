package `in`.driftzero.core

/**
 * Shared MATCHED heading-only feedback for the live phone path and JVM
 * [Replay]. Never writes lat/lon from the graph into the ESKF.
 *
 * Gates are [RoadHeadingAid.decide] plus [DeadReckoningFilter.applyRoadHeading]
 * (coasting, 1-dof chi-square). [MapMatchResult.displayPose] stays overlay.
 */
object RoadHeadingFeedback {
    fun apply(
        filter: DeadReckoningFilter,
        pose: NavigationState,
        matcher: RoadMatcher?,
        graph: RoadGraph?,
        coasting: Boolean,
        now: Nanoseconds,
    ): RoadHeadingTick {
        if (matcher == null || graph == null || graph.isEmpty()) {
            return RoadHeadingTick(match = null, decision = null, pose = pose)
        }
        val match = matcher.update(FilterSnapshot(pose), graph)
        if (!coasting) {
            return RoadHeadingTick(match = match, decision = null, pose = pose.withMapMatch(match))
        }
        val heading = pose.motion.heading.value
        val speed = pose.motion.speed.value
        if (!heading.isFinite() || !speed.isFinite() || speed < 0.0) {
            filter.noteRoadHeadingSkipped()
            return RoadHeadingTick(match = match, decision = null, pose = pose.withMapMatch(match))
        }
        val decision = RoadHeadingAid.decide(match, heading, speed)
        val prior = decision.prior
        val after = if (prior != null) {
            filter.applyRoadHeading(prior.edgeBearingRad, prior.stdRad, prior.alongTrackSpeedHintMps)
            filter.poseAt(now) ?: pose
        } else {
            filter.noteRoadHeadingSkipped()
            pose
        }
        return RoadHeadingTick(match = match, decision = decision, pose = after.withMapMatch(match))
    }
}

data class RoadHeadingTick(
    val match: MapMatchResult?,
    val decision: RoadHeadingDecision?,
    val pose: NavigationState,
)
