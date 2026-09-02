package `in`.driftzero.core

/**
 * Causal IMU window for the motion student / ZUPT heuristic.
 *
 * Samples are past-or-present only. GNSS is never part of this window.
 * Keep numeric gates in sync with `ml/src/driftzero_ml/features/causal_imu.py`.
 */
internal val GNSS_FEATURE_BAN: Set<String> = setOf(
    "latitude_deg",
    "longitude_deg",
    "gnss_speed_mps",
    "satellites_used",
    "horizontal_accuracy_m",
)

object ImuMotionConstants {
    const val WINDOW_NS: Long = 1_000_000_000L
    const val MAX_SAMPLES: Int = 128
    const val MIN_SAMPLES: Int = 8
    const val GRAVITY_MPS2: Double = 9.80665
    const val GRAVITY_TAU_S: Double = 0.8
    const val GYRO_IDLE_ENERGY: Double = 0.025
    const val ACCEL_IDLE_STD: Double = 0.35
    const val VIB_IDLE_ENERGY: Double = 0.45
    const val BUMP_MPS2: Double = 4.0
    const val STOP_ZUPT: Double = 0.65
    const val IDLE_SCORE_GATE: Double = 0.85
    const val SPEED_VIB_GAIN: Double = 3.5
    const val MAX_SPEED_MPS: Double = 50.0
    const val FEATURE_DIM: Int = 12
}

data class ImuSample(
    val timestamp: Nanoseconds,
    val accelX: Double,
    val accelY: Double,
    val accelZ: Double,
    val gyroX: Double? = null,
    val gyroY: Double? = null,
    val gyroZ: Double? = null,
) {
    init {
        require(accelX.isFinite() && accelY.isFinite() && accelZ.isFinite())
        gyroX?.let { require(it.isFinite()) }
        gyroY?.let { require(it.isFinite()) }
        gyroZ?.let { require(it.isFinite()) }
    }

    fun accelMag(): Double = kotlin.math.sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)
}

data class CausalImuWindow(
    val endTimestamp: Nanoseconds,
    val samples: List<ImuSample>,
) {
    init {
        for (index in samples.indices) {
            val sample = samples[index]
            require(sample.timestamp.value <= endTimestamp.value) {
                "IMU window must not contain future samples"
            }
            if (index > 0) {
                require(sample.timestamp.value >= samples[index - 1].timestamp.value) {
                    "IMU window timestamps must be non-decreasing"
                }
            }
        }
    }

    val length: Int get() = samples.size
}

/**
 * Ring of accelerometer and gyroscope samples. Pairing is causal: each accel
 * row takes the latest gyro with timestamp <= accel timestamp.
 */
class CausalImuBuffer(
    private val windowNs: Long = ImuMotionConstants.WINDOW_NS,
    private val maxSamples: Int = ImuMotionConstants.MAX_SAMPLES,
) {
    private data class Vec(val t: Long, val x: Double, val y: Double, val z: Double)

    private val accel = ArrayDeque<Vec>()
    private val gyro = ArrayDeque<Vec>()

    fun pushAccel(timestamp: Nanoseconds, x: Double, y: Double, z: Double) {
        require(x.isFinite() && y.isFinite() && z.isFinite())
        accel.addLast(Vec(timestamp.value, x, y, z))
        trim(accel)
    }

    fun pushGyro(timestamp: Nanoseconds, x: Double, y: Double, z: Double) {
        require(x.isFinite() && y.isFinite() && z.isFinite())
        gyro.addLast(Vec(timestamp.value, x, y, z))
        trim(gyro)
    }

    fun windowEndingAt(end: Nanoseconds): CausalImuWindow {
        val endNs = end.value
        val startNs = (endNs - windowNs).coerceAtLeast(0L)
        dropOutsideWindow(accel, startNs, endNs)
        dropOutsideWindow(gyro, startNs, endNs)
        val samples = accel.map { row ->
            val g = latestCausalGyro(row.t)
            ImuSample(
                timestamp = Nanoseconds(row.t),
                accelX = row.x,
                accelY = row.y,
                accelZ = row.z,
                gyroX = g?.x,
                gyroY = g?.y,
                gyroZ = g?.z,
            )
        }
        return CausalImuWindow(end, samples)
    }

    private fun latestCausalGyro(accelT: Long): Vec? {
        var best: Vec? = null
        for (row in gyro) {
            if (row.t > accelT) {
                break
            }
            best = row
        }
        return best
    }

    private fun trim(buffer: ArrayDeque<Vec>) {
        while (buffer.size > maxSamples) {
            buffer.removeFirst()
        }
    }

    private fun dropOutsideWindow(buffer: ArrayDeque<Vec>, startNs: Long, endNs: Long) {
        while (buffer.isNotEmpty() && buffer.first().t < startNs) {
            buffer.removeFirst()
        }
        while (buffer.isNotEmpty() && buffer.last().t > endNs) {
            buffer.removeLast()
        }
    }
}

/** Causal accel low-pass used as gravity. Same tau as the Python extractor. */
internal fun causalGravityVectors(samples: List<ImuSample>): List<Vec3> {
    require(samples.isNotEmpty())
    var gx = samples[0].accelX
    var gy = samples[0].accelY
    var gz = samples[0].accelZ
    val out = ArrayList<Vec3>(samples.size)
    out.add(Vec3(gx, gy, gz))
    for (index in 1 until samples.size) {
        val dt = (samples[index].timestamp.value - samples[index - 1].timestamp.value) / 1_000_000_000.0
        val tau = ImuMotionConstants.GRAVITY_TAU_S
        val alpha = if (dt <= 0.0) 0.0 else dt / (tau + dt)
        gx = (1.0 - alpha) * gx + alpha * samples[index].accelX
        gy = (1.0 - alpha) * gy + alpha * samples[index].accelY
        gz = (1.0 - alpha) * gz + alpha * samples[index].accelZ
        out.add(Vec3(gx, gy, gz))
    }
    return out
}

internal fun mean(values: List<Double>): Double {
    if (values.isEmpty()) {
        return 0.0
    }
    return values.sum() / values.size
}

internal fun populationStd(values: List<Double>): Double {
    if (values.size < 2) {
        return 0.0
    }
    val mu = mean(values)
    var acc = 0.0
    for (value in values) {
        val d = value - mu
        acc += d * d
    }
    return kotlin.math.sqrt(acc / values.size)
}
