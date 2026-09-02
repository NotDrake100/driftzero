package `in`.driftzero.core

import kotlinx.coroutines.flow.Flow

data class SensorSourceDescriptor(
    val sourceId: String,
    val declaredRateHz: Double,
    val clockDomain: ClockDomain,
) {
    init {
        require(sourceId.isNotEmpty())
        require(declaredRateHz.isFinite() && declaredRateHz > 0.0)
    }
}

interface SensorSource {
    val descriptor: SensorSourceDescriptor
    fun frames(): Flow<SensorFrame>
}

/**
 * Motion student output. The ESKF consumes this as a gated measurement:
 * forward speed with R = exp(logSpeedVariance), and a ZUPT when
 * [idle] or [stopProbability] is high. It must not overwrite the state.
 *
 * Units: speed m/s, yaw rate rad/s (device frame until alignment exists),
 * logSpeedVariance is ln(sigma^2) of speed in (m/s)^2, vibrationEnergy is
 * mean squared specific force residual (m/s^2)^2.
 */
data class MotionPseudoMeasurement(
    val forwardSpeed: MetresPerSecond,
    val yawRateRadps: Double,
    val stopProbability: Double,
    val logSpeedVariance: Double = 0.0,
    val vibrationEnergy: Double = 0.0,
    val idle: Boolean = false,
    val bump: Boolean = false,
) {
    init {
        require(yawRateRadps.isFinite())
        require(stopProbability in 0.0..1.0)
        require(logSpeedVariance.isFinite())
        require(vibrationEnergy.isFinite() && vibrationEnergy >= 0.0)
    }
}

/**
 * RoNIN/TLIO displacement over a causal IMU window. Δp is metres in the
 * start-of-window HACF (Z along gravity). logSigma* is ln(σ) in metres,
 * so R_ii = exp(2 logSigma_i). [windowStart] is the first sample time,
 * used to clone the INS pose. [twoDimensional] drops the vertical row
 * (RoNIN plane). The ESKF applies this through [DeadReckoningFilter.ingestDisplacementPseudo]
 * with a χ² gate of 11.345 (TLIO, 99th percentile, 3 dof).
 */
data class DisplacementPseudoMeasurement(
    val dxM: Double,
    val dyM: Double,
    val dzM: Double,
    val logSigmaX: Double,
    val logSigmaY: Double,
    val logSigmaZ: Double,
    val windowStart: Nanoseconds,
    val twoDimensional: Boolean = false,
) {
    init {
        require(dxM.isFinite() && dyM.isFinite() && dzM.isFinite())
        require(logSigmaX.isFinite() && logSigmaY.isFinite() && logSigmaZ.isFinite())
    }
}

interface MotionModel {
    fun infer(window: CausalImuWindow): MotionPseudoMeasurement
}

fun interface DisplacementModel {
    fun infer(window: CausalImuWindow): DisplacementPseudoMeasurement?
}

data class FilterSnapshot(
    val state: NavigationState,
)

interface RoadMatcher {
    fun update(state: FilterSnapshot, graph: RoadGraph): MapMatchResult

    fun reset()
}

enum class ResetReason {
    USER,
    REMOUNT,
    NUMERICAL,
}

interface NavigationEngine {
    suspend fun consume(frame: SensorFrame)
    fun states(): Flow<NavigationState>
    fun reset(reason: ResetReason)
}
