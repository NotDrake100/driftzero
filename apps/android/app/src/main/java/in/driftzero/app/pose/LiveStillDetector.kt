package `in`.driftzero.app.pose

import kotlin.math.hypot

/**
 * Live-phone still gate from raw accel (m/s^2) and gyro (rad/s).
 * A 1 s window of |a| variance and mean gyro norm. Independent of GNSS
 * speed and of nav-frame attitude, so a phone on a table wins over a
 * jumping indoor fix.
 */
internal class LiveStillDetector(
    private val windowNs: Long = WINDOW_NS,
    private val accelVarMax: Double = ACCEL_VAR_MAX,
    private val gyroMeanMax: Double = GYRO_MEAN_MAX,
    private val minSamples: Int = MIN_SAMPLES,
) {
    private data class Sample(val tNs: Long, val accelMag: Double, val gyroNorm: Double)

    private val samples = ArrayDeque<Sample>()
    private var lastAccelX: Double = Double.NaN
    private var lastAccelY: Double = Double.NaN
    private var lastAccelZ: Double = Double.NaN
    private var lastGyroX: Double = Double.NaN
    private var lastGyroY: Double = Double.NaN
    private var lastGyroZ: Double = Double.NaN
    private var hasAccel: Boolean = false
    private var hasGyro: Boolean = false

    @Synchronized
    fun reset() {
        samples.clear()
        hasAccel = false
        hasGyro = false
        lastAccelX = Double.NaN
        lastAccelY = Double.NaN
        lastAccelZ = Double.NaN
        lastGyroX = Double.NaN
        lastGyroY = Double.NaN
        lastGyroZ = Double.NaN
    }

    @Synchronized
    fun onAccel(tNs: Long, x: Double, y: Double, z: Double) {
        lastAccelX = x
        lastAccelY = y
        lastAccelZ = z
        hasAccel = true
        push(tNs)
    }

    @Synchronized
    fun onGyro(tNs: Long, x: Double, y: Double, z: Double) {
        lastGyroX = x
        lastGyroY = y
        lastGyroZ = z
        hasGyro = true
        push(tNs)
    }

    @Synchronized
    fun isStill(nowNs: Long): Boolean {
        prune(nowNs)
        if (samples.size < minSamples) {
            return false
        }
        val span = samples.last().tNs - samples.first().tNs
        if (span < (windowNs * 85L) / 100L) {
            return false
        }
        val stats = stats() ?: return false
        return stats.first < accelVarMax && stats.second <= gyroMeanMax
    }

    private fun push(tNs: Long) {
        if (!hasAccel || !hasGyro) {
            return
        }
        val accelMag = hypot(hypot(lastAccelX, lastAccelY), lastAccelZ)
        val gyroNorm = hypot(hypot(lastGyroX, lastGyroY), lastGyroZ)
        val last = samples.lastOrNull()
        if (last != null && tNs <= last.tNs) {
            samples.removeLast()
        }
        samples.addLast(Sample(tNs, accelMag, gyroNorm))
        prune(tNs)
    }

    private fun prune(nowNs: Long) {
        val cut = nowNs - windowNs
        while (samples.isNotEmpty() && samples.first().tNs < cut) {
            samples.removeFirst()
        }
    }

    private fun stats(): Pair<Double, Double>? {
        val snap = samples.toList()
        if (snap.size < minSamples) {
            return null
        }
        var magSum = 0.0
        var gyroSum = 0.0
        for (sample in snap) {
            magSum += sample.accelMag
            gyroSum += sample.gyroNorm
        }
        val n = snap.size.toDouble()
        val magMean = magSum / n
        var varSum = 0.0
        for (sample in snap) {
            val d = sample.accelMag - magMean
            varSum += d * d
        }
        return (varSum / n) to (gyroSum / n)
    }

    companion object {
        const val WINDOW_NS: Long = 1_000_000_000L
        const val ACCEL_VAR_MAX: Double = 0.04
        const val GYRO_MEAN_MAX: Double = 0.08
        const val MIN_SAMPLES: Int = 8
        const val GNSS_SPEED_REJECT_MPS: Double = 1.0
        const val GNSS_JUMP_REJECT_M: Double = 8.0

        fun impliedSpeedMps(reported: Double?, jumpM: Double, dtS: Double): Double {
            val column = if (reported != null && reported.isFinite() && reported >= 0.0) {
                reported
            } else {
                0.0
            }
            val hop = if (dtS > 1e-3 && jumpM.isFinite() && jumpM >= 0.0) jumpM / dtS else 0.0
            return maxOf(column, hop)
        }

        fun rejectGnssWhileStill(speedMps: Double?, jumpM: Double, dtS: Double): Boolean {
            if (jumpM > GNSS_JUMP_REJECT_M) {
                return true
            }
            return impliedSpeedMps(speedMps, jumpM, dtS) > GNSS_SPEED_REJECT_MPS
        }
    }
}
