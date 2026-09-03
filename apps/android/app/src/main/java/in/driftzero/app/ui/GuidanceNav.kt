package `in`.driftzero.app.ui

import `in`.driftzero.core.CueStage
import `in`.driftzero.core.GuidanceRoute
import `in`.driftzero.core.GuidanceState
import `in`.driftzero.core.ManeuverText
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.NavigationState
import kotlin.math.abs

/**
 * Display-only turn-by-turn policy. The estimator pose is never rewritten.
 * The puck may follow the route snap only while GNSS is live and the
 * cross-track is under [SNAP_CROSS_TRACK_M].
 */
internal object GuidanceNav {
    const val SNAP_CROSS_TRACK_M: Double = 15.0
    const val REROUTE_COOLDOWN_NS: Long = 10_000_000_000L

    fun isLiveGnss(mode: NavigationMode): Boolean = when (mode) {
        NavigationMode.GNSS_FUSED, NavigationMode.GNSS_DEGRADED -> true
        NavigationMode.DEAD_RECKONING, NavigationMode.REACQUIRING, NavigationMode.LOW_CONFIDENCE -> false
    }

    fun shouldSnapPuck(mode: NavigationMode, state: GuidanceState): Boolean {
        val on = state as? GuidanceState.OnRoute ?: return false
        return isLiveGnss(mode) && abs(on.crossTrackM) < SNAP_CROSS_TRACK_M
    }

    fun displayLatitudeDeg(pose: NavigationState, state: GuidanceState?): Double {
        val on = state as? GuidanceState.OnRoute
        return if (on != null && shouldSnapPuck(pose.mode, on)) {
            on.snappedLatitudeDeg
        } else {
            pose.position.latitude.value
        }
    }

    fun displayLongitudeDeg(pose: NavigationState, state: GuidanceState?): Double {
        val on = state as? GuidanceState.OnRoute
        return if (on != null && shouldSnapPuck(pose.mode, on)) {
            on.snappedLongitudeDeg
        } else {
            pose.position.longitude.value
        }
    }

    fun shouldReroute(
        state: GuidanceState,
        mode: NavigationMode,
        lastRerouteNs: Long?,
        nowNs: Long,
    ): Boolean {
        if (state !is GuidanceState.OffRoute || !isLiveGnss(mode)) {
            return false
        }
        val last = lastRerouteNs ?: return true
        return nowNs - last >= REROUTE_COOLDOWN_NS
    }

    fun banner(state: GuidanceState, route: GuidanceRoute): GuidanceBanner = when (state) {
        is GuidanceState.Arrived -> GuidanceBanner(
            distanceText = null,
            instruction = ManeuverText.instruction(route.steps.last(), null, CueStage.ARRIVED),
            arrived = true,
            offRoute = false,
        )
        is GuidanceState.OffRoute -> GuidanceBanner(
            distanceText = null,
            instruction = "Off route",
            arrived = false,
            offRoute = true,
        )
        is GuidanceState.OnRoute -> {
            val step = route.steps[state.nextStepIndex]
            val stage = bannerStage(state.distanceToNextStepM)
            GuidanceBanner(
                distanceText = InstrumentFormat.formatDistance(state.distanceToNextStepM),
                instruction = ManeuverText.instruction(step, null, if (stage == CueStage.NOW) CueStage.NOW else CueStage.FAR),
                arrived = false,
                offRoute = false,
            )
        }
    }

    private fun bannerStage(distanceM: Double): CueStage = when {
        distanceM <= 30.0 -> CueStage.NOW
        distanceM <= 150.0 -> CueStage.NEAR
        else -> CueStage.FAR
    }
}

internal data class GuidanceBanner(
    val distanceText: String?,
    val instruction: String,
    val arrived: Boolean,
    val offRoute: Boolean,
)
