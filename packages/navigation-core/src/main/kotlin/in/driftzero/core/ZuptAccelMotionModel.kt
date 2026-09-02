package `in`.driftzero.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Phone-path motion model. When [student] is present (APK assets
 * `motion_student_v1/linear.json`), that linear speed student is the
 * measurement. Otherwise ZUPT plus vibration-scaled speed.
 *
 * Outputs a [MotionPseudoMeasurement] for the ESKF speed/ZUPT update.
 * This is not strapdown integration. TimesFM is not a field.
 */
class ZuptAccelMotionModel(
    private val student: LinearMotionStudent? = null,
) : MotionModel {
    override fun infer(window: CausalImuWindow): MotionPseudoMeasurement {
        require(window.samples.isNotEmpty()) { "IMU window must not be empty" }
        if (student != null) {
            return student.infer(window)
        }
        val features = CausalImuFeatures.extract(window)
        val idle = features.idleScore >= ImuMotionConstants.IDLE_SCORE_GATE
        val stopProbability = features.idleScore.coerceIn(0.0, 1.0)
        val speed = if (idle) {
            0.0
        } else {
            (ImuMotionConstants.SPEED_VIB_GAIN * sqrt(features.vibrationEnergy))
                .coerceIn(0.0, ImuMotionConstants.MAX_SPEED_MPS)
        }
        val sigma = if (idle) {
            0.05
        } else {
            (1.5 + 3.0 * sqrt(features.vibrationEnergy)).coerceIn(0.4, 8.0)
        }
        return MotionPseudoMeasurement(
            forwardSpeed = MetresPerSecond(speed),
            yawRateRadps = features.gyroZMean,
            stopProbability = stopProbability,
            logSpeedVariance = ln(sigma * sigma),
            vibrationEnergy = features.vibrationEnergy,
            idle = idle,
            bump = features.bump,
        )
    }
}

/**
 * How the constant-velocity stub consumes a pseudo-measurement.
 *
 * The ESKF sibling must not copy this overwrite. It should treat
 * [MotionPseudoMeasurement] as a gated speed and ZUPT update with
 * R = exp(logSpeedVariance).
 */
object MotionPseudoHook {
    fun coastSpeedMps(priorSpeedMps: Double, measurement: MotionPseudoMeasurement): Double {
        require(priorSpeedMps.isFinite() && priorSpeedMps >= 0.0)
        if (measurement.idle || measurement.stopProbability >= ImuMotionConstants.STOP_ZUPT) {
            return 0.0
        }
        return priorSpeedMps
    }

    fun speedVarianceMps2(measurement: MotionPseudoMeasurement): Double = exp(measurement.logSpeedVariance)
}

internal data class CausalImuFeatures(
    val idleScore: Double,
    val vibrationEnergy: Double,
    val bump: Boolean,
    val gyroZMean: Double,
    val vector: DoubleArray,
) {
    companion object {
        val FEATURE_NAMES: List<String> = listOf(
            "accel_mag_mean",
            "accel_mag_std",
            "accel_energy",
            "gyro_energy",
            "vibration_energy",
            "specific_force_rms",
            "jerk_rms",
            "idle_flag",
            "bump_flag",
            "gyro_z_mean",
            "dt_mean_s",
            "n_norm",
        )

        fun extract(window: CausalImuWindow): CausalImuFeatures {
            val samples = window.samples
            val mags = samples.map { it.accelMag() }
            val accelMean = mean(mags)
            val accelStd = populationStd(mags)
            val accelEnergy = mean(mags.map { it * it })
            val gravity = causalGravityVectors(samples)
            val residuals = samples.mapIndexed { index, sample ->
                val g = gravity[index]
                val dx = sample.accelX - g.x
                val dy = sample.accelY - g.y
                val dz = sample.accelZ - g.z
                dx * dx + dy * dy + dz * dz
            }
            val vibrationEnergy = mean(residuals)
            val specificRms = sqrt(vibrationEnergy)
            val gyroEnergy = gyroMeanEnergy(samples)
            val gyroZ = samples.mapNotNull { it.gyroZ }
            val gyroZMean = mean(gyroZ)
            val bump = residuals.any { sqrt(it) >= ImuMotionConstants.BUMP_MPS2 }
            val jerkRms = jerkRms(samples, mags)
            val dtMean = meanDtS(samples)
            val nNorm = samples.size.toDouble() / ImuMotionConstants.MAX_SAMPLES
            var idleScore = 0.0
            if (gyroEnergy < ImuMotionConstants.GYRO_IDLE_ENERGY) {
                idleScore += 0.4
            }
            if (accelStd < ImuMotionConstants.ACCEL_IDLE_STD) {
                idleScore += 0.3
            }
            if (vibrationEnergy < ImuMotionConstants.VIB_IDLE_ENERGY) {
                idleScore += 0.3
            }
            val vector = doubleArrayOf(
                accelMean,
                accelStd,
                accelEnergy,
                gyroEnergy,
                vibrationEnergy,
                specificRms,
                jerkRms,
                if (idleScore >= ImuMotionConstants.IDLE_SCORE_GATE) 1.0 else 0.0,
                if (bump) 1.0 else 0.0,
                gyroZMean,
                dtMean,
                nNorm,
            )
            require(vector.size == ImuMotionConstants.FEATURE_DIM)
            return CausalImuFeatures(idleScore, vibrationEnergy, bump, gyroZMean, vector)
        }

        private fun gyroMeanEnergy(samples: List<ImuSample>): Double {
            val energies = samples.mapNotNull { sample ->
                val x = sample.gyroX ?: return@mapNotNull null
                val y = sample.gyroY ?: return@mapNotNull null
                val z = sample.gyroZ ?: return@mapNotNull null
                x * x + y * y + z * z
            }
            return mean(energies)
        }

        private fun jerkRms(samples: List<ImuSample>, mags: List<Double>): Double {
            if (samples.size < 2) {
                return 0.0
            }
            val jerks = ArrayList<Double>()
            for (index in 1 until samples.size) {
                val dt = (samples[index].timestamp.value - samples[index - 1].timestamp.value) / NS_PER_S
                if (dt <= 1e-4) {
                    continue
                }
                val j = (mags[index] - mags[index - 1]) / dt
                jerks.add(j * j)
            }
            return if (jerks.isEmpty()) 0.0 else sqrt(mean(jerks))
        }

        private fun meanDtS(samples: List<ImuSample>): Double {
            if (samples.size < 2) {
                return 0.0
            }
            val dts = ArrayList<Double>(samples.size - 1)
            for (index in 1 until samples.size) {
                dts.add((samples[index].timestamp.value - samples[index - 1].timestamp.value) / NS_PER_S)
            }
            return mean(dts)
        }

        private const val NS_PER_S: Double = 1_000_000_000.0
    }
}
