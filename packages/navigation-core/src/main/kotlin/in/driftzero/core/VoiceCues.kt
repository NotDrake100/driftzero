package `in`.driftzero.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

enum class CueStage { FAR, NEAR, NOW, ARRIVED, REROUTING }

data class VoiceCue(val stage: CueStage, val stepIndex: Int, val text: String)

data class VoiceCueConfig(
    val farM: Double = 500.0,
    val nearM: Double = 150.0,
    val nowM: Double = 30.0,
    val minGapS: Double = 4.0,
) {
    init {
        require(farM.isFinite() && farM > 0.0)
        require(nearM.isFinite() && nearM > 0.0)
        require(nowM.isFinite() && nowM > 0.0)
        require(minGapS.isFinite() && minGapS >= 0.0)
        require(nowM <= nearM && nearM <= farM) { "cue distances must be now <= near <= far" }
    }
}

/**
 * Emits a spoken cue at most once per (stepIndex, stage). FAR is 500 m or
 * 30 s, NEAR 150 m, NOW 30 m, each grown with speed when speed is known.
 */
class VoiceCueScheduler(private val config: VoiceCueConfig = VoiceCueConfig()) {
    private val emitted = HashSet<Long>()
    private var virtualS: Double = 0.0
    private var lastCueVirtualS: Double? = null
    private var lastDistanceToStepM: Double? = null
    private var lastRemainingS: Double? = null
    private var lastOffSinceS: Double? = null

    fun onGuidance(state: GuidanceState, route: GuidanceRoute, speedMps: Double?): VoiceCue? {
        speedMps?.let {
            require(it.isFinite() && it >= 0.0) { "speed_mps must be finite and non-negative when present" }
        }
        advanceClock(state, speedMps)
        return when (state) {
            is GuidanceState.Arrived -> arrivedCue(route)
            is GuidanceState.OffRoute -> null
            is GuidanceState.OnRoute -> onRouteCue(state, route, speedMps)
        }
    }

    fun onReroute(): VoiceCue =
        VoiceCue(CueStage.REROUTING, stepIndex = 0, text = "Rerouting")

    private fun onRouteCue(
        state: GuidanceState.OnRoute,
        route: GuidanceRoute,
        speedMps: Double?,
    ): VoiceCue? {
        val stepIndex = state.nextStepIndex
        val step = route.steps[stepIndex]
        val dist = state.distanceToNextStepM
        val farT = thresholdM(config.farM, FAR_TIME_S, speedMps)
        val nearT = thresholdM(config.nearM, nearTimeS(), speedMps)
        val nowT = thresholdM(config.nowM, nowTimeS(), speedMps)
        val stage = when {
            dist <= nowT && !already(stepIndex, CueStage.NOW) -> CueStage.NOW
            dist <= nearT && !already(stepIndex, CueStage.NEAR) -> CueStage.NEAR
            dist <= farT && !already(stepIndex, CueStage.FAR) -> CueStage.FAR
            else -> return null
        }
        if (!gapOk()) {
            return null
        }
        mark(stepIndex, stage)
        lastCueVirtualS = virtualS
        val spokenDistance = if (stage == CueStage.NOW) null else dist
        return VoiceCue(stage, stepIndex, ManeuverText.instruction(step, spokenDistance, stage))
    }

    private fun arrivedCue(route: GuidanceRoute): VoiceCue? {
        val stepIndex = route.steps.lastIndex
        if (already(stepIndex, CueStage.ARRIVED)) {
            return null
        }
        mark(stepIndex, CueStage.ARRIVED)
        lastCueVirtualS = virtualS
        val step = route.steps[stepIndex]
        return VoiceCue(CueStage.ARRIVED, stepIndex, ManeuverText.instruction(step, null, CueStage.ARRIVED))
    }

    private fun advanceClock(state: GuidanceState, speedMps: Double?) {
        when (state) {
            is GuidanceState.OnRoute -> {
                val prevD = lastDistanceToStepM
                val prevR = lastRemainingS
                when {
                    prevD != null && speedMps != null && speedMps > SPEED_EPS_MPS -> {
                        virtualS += abs(prevD - state.distanceToNextStepM) / speedMps
                    }
                    prevR != null -> {
                        virtualS += abs(prevR - state.remainingS)
                    }
                }
                lastDistanceToStepM = state.distanceToNextStepM
                lastRemainingS = state.remainingS
                lastOffSinceS = null
            }
            is GuidanceState.OffRoute -> {
                val prev = lastOffSinceS
                if (prev != null && state.sinceS >= prev) {
                    virtualS += state.sinceS - prev
                }
                lastOffSinceS = state.sinceS
            }
            is GuidanceState.Arrived -> {
                lastDistanceToStepM = 0.0
                lastRemainingS = 0.0
            }
        }
    }

    private fun gapOk(): Boolean {
        val last = lastCueVirtualS ?: return true
        return virtualS - last >= config.minGapS
    }

    private fun already(stepIndex: Int, stage: CueStage): Boolean =
        emitted.contains(pack(stepIndex, stage))

    private fun mark(stepIndex: Int, stage: CueStage) {
        emitted.add(pack(stepIndex, stage))
    }

    private fun nearTimeS(): Double = FAR_TIME_S * (config.nearM / config.farM)

    private fun nowTimeS(): Double = FAR_TIME_S * (config.nowM / config.farM)

    private companion object {
        const val FAR_TIME_S: Double = 30.0
        const val SPEED_EPS_MPS: Double = 1e-6

        fun pack(stepIndex: Int, stage: CueStage): Long =
            (stepIndex.toLong() shl 8) or stage.ordinal.toLong()

        fun thresholdM(baseM: Double, timeS: Double, speedMps: Double?): Double {
            if (speedMps == null) {
                return baseM
            }
            return max(baseM, speedMps * timeS)
        }
    }
}

object ManeuverText {
    /**
     * English nouns and numbers. Distances round under 50 m to 10 m, under
     * 1000 m to 50 m, otherwise to 0.1 km.
     */
    fun instruction(step: RouteStep, distanceM: Double?, stage: CueStage): String {
        distanceM?.let { require(it.isFinite() && it >= 0.0) }
        if (stage == CueStage.ARRIVED || (stage == CueStage.NOW && step.type == ManeuverType.ARRIVE)) {
            return "You have arrived"
        }
        if (stage == CueStage.REROUTING) {
            return "Rerouting"
        }
        if (stage == CueStage.NOW) {
            return nowText(step)
        }
        val action = farAction(step)
        if (distanceM == null) {
            return capitalize(action)
        }
        return "In ${spokenDistance(distanceM)}, $action"
    }

    private fun farAction(step: RouteStep): String {
        val road = onto(step.roadName)
        val dir = modifierPhrase(step.modifier)
        return when (step.type) {
            ManeuverType.DEPART -> {
                if (dir != null && step.modifier != ManeuverModifier.UTURN) {
                    "head $dir$road"
                } else {
                    "depart$road"
                }
            }
            ManeuverType.TURN -> turnAction(step, dir, road)
            ManeuverType.NEW_NAME -> {
                if (step.roadName != null) "continue onto ${step.roadName}" else "continue"
            }
            ManeuverType.CONTINUE -> {
                when (dir) {
                    null, "straight" -> "continue straight$road"
                    else -> "continue $dir$road"
                }
            }
            ManeuverType.MERGE -> if (dir != null) "merge $dir$road" else "merge$road"
            ManeuverType.ON_RAMP -> if (dir != null) "take the ramp $dir$road" else "take the ramp$road"
            ManeuverType.OFF_RAMP -> if (dir != null) "take the exit $dir$road" else "take the exit$road"
            ManeuverType.FORK -> if (dir != null) "keep $dir at the fork$road" else "keep ahead at the fork$road"
            ManeuverType.END_OF_ROAD -> {
                if (dir != null) "at the end of the road, turn $dir$road" else "at the end of the road$road"
            }
            ManeuverType.ROUNDABOUT -> roundaboutAction("roundabout", step, road)
            ManeuverType.ROTARY -> roundaboutAction("rotary", step, road)
            ManeuverType.EXIT_ROUNDABOUT -> "exit the roundabout$road"
            ManeuverType.UTURN -> "make a U-turn$road"
            ManeuverType.ARRIVE -> "you will arrive$road"
            ManeuverType.UNKNOWN -> if (dir != null) "continue $dir$road" else "continue$road"
        }
    }

    private fun nowText(step: RouteStep): String {
        val dir = modifierPhrase(step.modifier)
        return when (step.type) {
            ManeuverType.DEPART -> "Depart now"
            ManeuverType.TURN -> {
                when {
                    step.modifier == ManeuverModifier.UTURN -> "Make a U-turn now"
                    dir != null -> "Turn $dir now"
                    else -> "Turn now"
                }
            }
            ManeuverType.NEW_NAME -> {
                if (step.roadName != null) "Continue onto ${step.roadName} now" else "Continue now"
            }
            ManeuverType.CONTINUE -> {
                when (dir) {
                    null, "straight" -> "Continue straight now"
                    else -> "Continue $dir now"
                }
            }
            ManeuverType.MERGE -> if (dir != null) "Merge $dir now" else "Merge now"
            ManeuverType.ON_RAMP -> "Take the ramp now"
            ManeuverType.OFF_RAMP -> "Take the exit now"
            ManeuverType.FORK -> if (dir != null) "Keep $dir now" else "Keep ahead now"
            ManeuverType.END_OF_ROAD -> if (dir != null) "Turn $dir now" else "Turn now"
            ManeuverType.ROUNDABOUT, ManeuverType.ROTARY -> {
                val n = step.exitNumber
                if (n != null) "Take the ${ordinal(n)} exit now" else "Take the exit now"
            }
            ManeuverType.EXIT_ROUNDABOUT -> "Exit the roundabout now"
            ManeuverType.UTURN -> "Make a U-turn now"
            ManeuverType.ARRIVE -> "You have arrived"
            ManeuverType.UNKNOWN -> "Continue now"
        }
    }

    private fun turnAction(step: RouteStep, dir: String?, road: String): String =
        when {
            step.modifier == ManeuverModifier.UTURN -> "make a U-turn$road"
            dir != null -> "turn $dir$road"
            else -> "turn$road"
        }

    private fun roundaboutAction(place: String, step: RouteStep, road: String): String {
        val n = step.exitNumber
        return if (n != null) {
            "at the $place, take the ${ordinal(n)} exit$road"
        } else {
            "at the $place$road"
        }
    }

    private fun onto(roadName: String?): String =
        if (roadName == null) "" else " onto $roadName"

    private fun modifierPhrase(modifier: ManeuverModifier): String? = when (modifier) {
        ManeuverModifier.UTURN -> "U-turn"
        ManeuverModifier.SHARP_RIGHT -> "sharp right"
        ManeuverModifier.RIGHT -> "right"
        ManeuverModifier.SLIGHT_RIGHT -> "slight right"
        ManeuverModifier.STRAIGHT -> "straight"
        ManeuverModifier.SLIGHT_LEFT -> "slight left"
        ManeuverModifier.LEFT -> "left"
        ManeuverModifier.SHARP_LEFT -> "sharp left"
        ManeuverModifier.NONE -> null
    }

    private fun ordinal(n: Int): String = when (n) {
        1 -> "first"
        2 -> "second"
        3 -> "third"
        4 -> "fourth"
        5 -> "fifth"
        6 -> "sixth"
        7 -> "seventh"
        8 -> "eighth"
        9 -> "ninth"
        10 -> "tenth"
        else -> n.toString()
    }

    internal fun spokenDistance(distanceM: Double): String {
        if (distanceM < 50.0) {
            val metres = roundToStep(distanceM, 10.0).coerceAtLeast(10.0)
            return metresPhrase(metres)
        }
        if (distanceM < 1000.0) {
            val metres = roundToStep(distanceM, 50.0)
            if (metres >= 1000.0) {
                return kmPhrase(1.0)
            }
            return metresPhrase(metres)
        }
        val km = roundToStep(distanceM, 100.0) / 1000.0
        return kmPhrase(km)
    }

    private fun roundToStep(value: Double, step: Double): Double =
        kotlin.math.floor(value / step + 0.5) * step

    private fun metresPhrase(metres: Double): String {
        val n = metres.toInt()
        return if (n == 1) "1 metre" else "$n metres"
    }

    private fun kmPhrase(km: Double): String {
        val tenths = round(km * 10.0) / 10.0
        val text = if (abs(tenths - tenths.toLong().toDouble()) < 1e-9) {
            "${tenths.toLong()}.0"
        } else {
            tenths.toString()
        }
        return "$text kilometres"
    }

    private fun capitalize(text: String): String {
        if (text.isEmpty()) {
            return text
        }
        return text[0].uppercaseChar() + text.substring(1)
    }
}
