package `in`.driftzero.core

import kotlin.math.acos
import kotlin.math.sqrt

data class CalibrationProfile(
    val gravityX: Double,
    val gravityY: Double,
    val gravityZ: Double,
) {
    init {
        require(gravityX.isFinite() && gravityY.isFinite() && gravityZ.isFinite())
        require(norm() > 0.0) { "gravity vector" }
    }

    fun norm(): Double = sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ)

    fun unit(): Triple<Double, Double, Double> {
        val n = norm()
        return Triple(gravityX / n, gravityY / n, gravityZ / n)
    }

    fun angleDeg(other: CalibrationProfile): Double {
        val a = unit()
        val b = other.unit()
        val dot = (a.first * b.first + a.second * b.second + a.third * b.third).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }
}

sealed class CalibrationStatus {
    data object Still : CalibrationStatus()
    data object Moving : CalibrationStatus()
    data class Done(val tiltDeg: Double, val profile: CalibrationProfile) : CalibrationStatus()
    data object FailedShort : CalibrationStatus()
    data object FailedMoving : CalibrationStatus()
    data object FailedNoGyro : CalibrationStatus()
}

/**
 * Averages device accel for [WINDOW_NS] while the vehicle is still.
 * Variance of |a| above [MOVE_STD] is motion. Not a filter input.
 */
class StationaryCalibrator {
    private val times = ArrayList<Long>()
    private val ax = ArrayList<Double>()
    private val ay = ArrayList<Double>()
    private val az = ArrayList<Double>()

    fun reset() {
        times.clear()
        ax.clear()
        ay.clear()
        az.clear()
    }

    fun ingestAccel(timestampNs: Long, x: Double, y: Double, z: Double) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || timestampNs < 0L) {
            return
        }
        times.add(timestampNs)
        ax.add(x)
        ay.add(y)
        az.add(z)
    }

    fun evaluate(nowNs: Long, hasGyro: Boolean): CalibrationStatus {
        if (!hasGyro) {
            return CalibrationStatus.FailedNoGyro
        }
        if (times.isEmpty()) {
            return CalibrationStatus.Still
        }
        val first = times.first()
        val elapsed = nowNs - first
        val std = magnitudeStd()
        val moving = std != null && std > MOVE_STD
        if (elapsed < WINDOW_NS) {
            return if (moving) CalibrationStatus.Moving else CalibrationStatus.Still
        }
        if (times.size < MIN_SAMPLES) {
            return CalibrationStatus.FailedShort
        }
        if (moving || std == null) {
            return CalibrationStatus.FailedMoving
        }
        val profile = meanProfile() ?: return CalibrationStatus.FailedShort
        val tilt = profile.angleDeg(CalibrationProfile(0.0, 0.0, profile.gravityZ.let { if (it >= 0) 1.0 else -1.0 }))
        return CalibrationStatus.Done(tilt, profile)
    }

    private fun magnitudeStd(): Double? {
        if (ax.size < MIN_SAMPLES) {
            return null
        }
        val mags = DoubleArray(ax.size) { i ->
            sqrt(ax[i] * ax[i] + ay[i] * ay[i] + az[i] * az[i])
        }
        val mean = mags.average()
        val varSum = mags.sumOf { (it - mean) * (it - mean) }
        return sqrt(varSum / mags.size)
    }

    private fun meanProfile(): CalibrationProfile? {
        if (ax.isEmpty()) {
            return null
        }
        return CalibrationProfile(ax.average(), ay.average(), az.average())
    }

    companion object {
        const val WINDOW_NS: Long = 5_000_000_000L
        const val MIN_SAMPLES: Int = 10
        const val MOVE_STD: Double = 0.4
        const val SKIP_ALIGN_DEG: Double = 10.0
    }
}

/**
 * Low-pass gravity versus a stored profile. More than [LIMIT_DEG] for
 * [HOLD_NS] means the phone moved on the mount.
 */
class MountMonitor(
    private val profile: CalibrationProfile,
) {
    private var filtered: CalibrationProfile? = null
    private var overSinceNs: Long? = null

    fun ingestAccel(timestampNs: Long, x: Double, y: Double, z: Double) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            return
        }
        val sample = CalibrationProfile(x, y, z)
        val prev = filtered
        filtered = if (prev == null) {
            sample
        } else {
            CalibrationProfile(
                prev.gravityX * (1 - ALPHA) + sample.gravityX * ALPHA,
                prev.gravityY * (1 - ALPHA) + sample.gravityY * ALPHA,
                prev.gravityZ * (1 - ALPHA) + sample.gravityZ * ALPHA,
            )
        }
        val angle = filtered!!.angleDeg(profile)
        if (angle > LIMIT_DEG) {
            if (overSinceNs == null) {
                overSinceNs = timestampNs
            }
        } else {
            overSinceNs = null
        }
    }

    fun mountOk(nowNs: Long): Boolean {
        val since = overSinceNs ?: return true
        return nowNs - since < HOLD_NS
    }

    companion object {
        const val LIMIT_DEG: Double = 25.0
        const val HOLD_NS: Long = 2_000_000_000L
        private const val ALPHA: Double = 0.1
    }
}
