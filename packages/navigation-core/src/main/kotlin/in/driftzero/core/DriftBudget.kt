package `in`.driftzero.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Causal integrity budget from ESKF quantities already on [NavigationState].
 *
 * confidence = clamp(1 - horizontal95 / integrityLimitM, 0, 1).
 * Default [integrityLimitM] is [InsConfig.lowConfidenceRadiusM] (120 m).
 * Growth is dr95/dt from consecutive samples. If the radius is not growing,
 * remaining time and range are [OptionalScalar.Unavailable], never +inf.
 *
 * [sihRemainM] = max(0, 0.10 * travelledM - r95) is a lab remainder, not a
 * claim that the official SIH 10 percent gate is met.
 */
enum class DriftLamp {
    HIGH,
    CAUTION,
    EXHAUSTED,
}

sealed class OptionalScalar {
    data class Available(val value: Double) : OptionalScalar() {
        init {
            require(value.isFinite()) { "optional scalar must be finite" }
        }
    }

    data class Unavailable(val reason: String) : OptionalScalar() {
        init {
            require(reason.isNotEmpty()) { "unavailable reason must be non-empty" }
        }
    }
}

data class DriftBudget(
    val confidence: Double,
    val remainingRadiusM: Double,
    val growthRateMps: OptionalScalar,
    val timeRemainS: OptionalScalar,
    val rangeRemainM: OptionalScalar,
    val lamp: DriftLamp,
    val sihRemainM: Double,
    val integrityLimitM: Double,
    val horizontal95M: Double,
    val travelledM: Double,
) {
    init {
        require(confidence in 0.0..1.0)
        require(remainingRadiusM.isFinite() && remainingRadiusM >= 0.0)
        require(sihRemainM.isFinite() && sihRemainM >= 0.0)
        require(integrityLimitM.isFinite() && integrityLimitM > 0.0)
        require(horizontal95M.isFinite() && horizontal95M >= 0.0)
        require(travelledM.isFinite() && travelledM >= 0.0)
    }
}

object DriftBudgetMath {
    const val DEFAULT_LIMIT_M: Double = 120.0
    const val HIGH_CONFIDENCE: Double = 0.80
    const val CAUTION_RANGE_M: Double = 650.0
    const val SIH_DRIFT_FRACTION: Double = 0.10
    const val MIN_DT_S: Double = 0.05

    fun evaluate(
        horizontal95M: Double,
        integrityLimitM: Double = DEFAULT_LIMIT_M,
        previousHorizontal95M: Double? = null,
        previousTimestampNs: Long? = null,
        timestampNs: Long,
        speedMps: Double,
        travelledM: Double,
    ): DriftBudget {
        require(horizontal95M.isFinite() && horizontal95M >= 0.0) { "horizontal95 must be finite and >= 0" }
        require(integrityLimitM.isFinite() && integrityLimitM > 0.0) { "integrityLimitM must be finite and > 0" }
        require(speedMps.isFinite() && speedMps >= 0.0) { "speed must be finite and >= 0" }
        require(travelledM.isFinite() && travelledM >= 0.0) { "travelledM must be finite and >= 0" }
        val r95 = horizontal95M
        val limit = integrityLimitM
        val confidence = (1.0 - r95 / limit).coerceIn(0.0, 1.0)
        val remaining = max(0.0, limit - r95)
        val growth = growthRateMps(previousHorizontal95M, previousTimestampNs, r95, timestampNs)
        val timeRemain: OptionalScalar
        val rangeRemain: OptionalScalar
        when (growth) {
            is OptionalScalar.Unavailable -> {
                timeRemain = growth
                rangeRemain = OptionalScalar.Unavailable(growth.reason)
            }
            is OptionalScalar.Available -> {
                if (growth.value > 0.0 && remaining > 0.0) {
                    val t = remaining / growth.value
                    timeRemain = OptionalScalar.Available(t)
                    rangeRemain = OptionalScalar.Available(speedMps * t)
                } else {
                    val reason = if (remaining <= 0.0) {
                        "radius already at integrity limit"
                    } else {
                        "horizontal95 is not growing"
                    }
                    timeRemain = OptionalScalar.Unavailable(reason)
                    rangeRemain = OptionalScalar.Unavailable(reason)
                }
            }
        }
        val lamp = when {
            r95 >= limit -> DriftLamp.EXHAUSTED
            rangeRemain is OptionalScalar.Available && rangeRemain.value < CAUTION_RANGE_M -> DriftLamp.CAUTION
            confidence < HIGH_CONFIDENCE -> DriftLamp.CAUTION
            else -> DriftLamp.HIGH
        }
        val sihRemain = max(0.0, SIH_DRIFT_FRACTION * travelledM - r95)
        return DriftBudget(
            confidence = confidence,
            remainingRadiusM = remaining,
            growthRateMps = growth,
            timeRemainS = timeRemain,
            rangeRemainM = rangeRemain,
            lamp = lamp,
            sihRemainM = sihRemain,
            integrityLimitM = limit,
            horizontal95M = r95,
            travelledM = travelledM,
        )
    }

    fun confidencePercent(budget: DriftBudget): String = percentWhole(budget.confidence)

    fun confidenceLine(budget: DriftBudget): String =
        "Navigation confidence ${confidencePercent(budget)}"

    fun rangeValue(budget: DriftBudget): String? {
        val range = budget.rangeRemainM
        val time = budget.timeRemainS
        if (range !is OptionalScalar.Available || time !is OptionalScalar.Available) {
            return null
        }
        return "${formatDistance(range.value)}, ${formatClockSpan(time.value)}"
    }

    fun rangeLine(budget: DriftBudget): String? = rangeValue(budget)?.let { "Safe range $it" }

    fun lampLine(budget: DriftBudget): String = when (budget.lamp) {
        DriftLamp.HIGH -> "High confidence"
        DriftLamp.CAUTION -> {
            val range = budget.rangeRemainM
            if (range is OptionalScalar.Available && range.value < CAUTION_RANGE_M) {
                "${formatDistance(range.value)} remaining"
            } else {
                "${formatDistance(budget.remainingRadiusM)} remaining"
            }
        }
        DriftLamp.EXHAUSTED -> "Position integrity cannot be guaranteed"
    }

    fun sihRemainLine(budget: DriftBudget): String =
        "SIH remain ${formatDistance(budget.sihRemainM)}"

    private fun growthRateMps(
        previousHorizontal95M: Double?,
        previousTimestampNs: Long?,
        r95: Double,
        timestampNs: Long,
    ): OptionalScalar {
        if (previousHorizontal95M == null || previousTimestampNs == null) {
            return OptionalScalar.Unavailable("need a previous horizontal95 sample")
        }
        if (!previousHorizontal95M.isFinite() || previousHorizontal95M < 0.0) {
            return OptionalScalar.Unavailable("previous horizontal95 is invalid")
        }
        val dt = (timestampNs - previousTimestampNs) / NS_PER_S
        if (dt < MIN_DT_S) {
            return OptionalScalar.Unavailable("dt below ${MIN_DT_S} s")
        }
        val dr = r95 - previousHorizontal95M
        return OptionalScalar.Available(dr / dt)
    }

    internal fun percentWhole(fraction: Double): String =
        "${(fraction.coerceIn(0.0, 1.0) * 100.0).roundToInt()}%"

    internal fun formatDistance(metres: Double): String {
        val m = max(0.0, metres)
        return if (m < 1000.0) {
            "${m.roundToInt()} m"
        } else {
            val km = (m / 100.0).roundToInt() / 10.0
            "${trimOneDecimal(km)} km"
        }
    }

    /** `2 min 40 s` under an hour. Whole seconds under 60 s. */
    internal fun formatClockSpan(seconds: Double): String {
        val s = max(0.0, seconds)
        if (s < 60.0) {
            return "${s.roundToInt()} s"
        }
        val whole = s.roundToInt()
        val min = whole / 60
        val rem = whole % 60
        return "$min min $rem s"
    }

    private fun trimOneDecimal(value: Double): String {
        val scaled = (value * 10.0).roundToInt()
        val whole = scaled / 10
        val frac = abs(scaled % 10)
        return if (frac == 0) whole.toString() else "$whole.$frac"
    }

    private const val NS_PER_S: Double = 1_000_000_000.0
}

/** Holds the last r95 sample so [DriftBudgetMath.evaluate] can form dr/dt. */
class DriftBudgetTracker(
    private val integrityLimitM: Double = DriftBudgetMath.DEFAULT_LIMIT_M,
) {
    private var lastR95: Double? = null
    private var lastNs: Long? = null
    private var last: DriftBudget? = null

    fun last(): DriftBudget? = last

    fun reset() {
        lastR95 = null
        lastNs = null
        last = null
    }

    fun update(
        timestampNs: Long,
        horizontal95M: Double,
        speedMps: Double,
        travelledM: Double,
        integrityLimitM: Double = this.integrityLimitM,
    ): DriftBudget {
        val budget = DriftBudgetMath.evaluate(
            horizontal95M = horizontal95M,
            integrityLimitM = integrityLimitM,
            previousHorizontal95M = lastR95,
            previousTimestampNs = lastNs,
            timestampNs = timestampNs,
            speedMps = speedMps,
            travelledM = travelledM,
        )
        lastR95 = horizontal95M
        lastNs = timestampNs
        last = budget
        return budget
    }
}
