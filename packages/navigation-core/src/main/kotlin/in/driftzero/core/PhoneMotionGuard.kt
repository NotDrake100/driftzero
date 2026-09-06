package `in`.driftzero.core

import kotlin.math.sqrt

/**
 * Causal phone-frame gravity projection. Inputs use elapsed realtime ns,
 * gravity including +g in m/s² and right-handed gyro in rad/s.
 * Tilt/pickup is observable; a slow phone spin about gravity is not uniquely
 * distinguishable from a vehicle turn. This is a conservative fallback, not
 * an arbitrary handheld navigation guarantee.
 */
class PhoneMotionGuard {
    private var gravity: Vec3? = null
    private var gravityNs = -1L
    private var gyroNs = -1L
    private var disturbedUntilNs = -1L

    fun reset() {
        gravity = null
        gravityNs = -1L
        gyroNs = -1L
        disturbedUntilNs = -1L
    }

    fun onGravity(timestampNs: Long, x: Double, y: Double, z: Double) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val value = Vec3(x, y, z)
        val norm = value.norm()
        if (timestampNs <= gravityNs || !norm.isFinite() || norm !in 7.0..12.0) return
        val next = value * (1.0 / norm)
        val previous = gravity
        if (previous != null && previous.dot(next) < 0.985) disturb(timestampNs)
        gravity = next
        gravityNs = timestampNs
    }

    fun disturb(timestampNs: Long) {
        disturbedUntilNs = maxOf(disturbedUntilNs, timestampNs + 2_000_000_000L)
    }

    data class Observation(val yawUpRadps: Double?, val handling: Boolean)

    fun onGyro(timestampNs: Long, x: Double, y: Double, z: Double): Observation {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return Observation(null, true)
        val rate = Vec3(x, y, z)
        if (timestampNs <= gyroNs) return Observation(null, timestampNs <= disturbedUntilNs)
        gyroNs = timestampNs
        val up = gravity
        if (up == null || timestampNs < gravityNs || timestampNs - gravityNs > 500_000_000L) {
            return Observation(null, timestampNs <= disturbedUntilNs)
        }
        val yaw = rate.dot(up)
        val transverse = sqrt((rate.dot(rate) - yaw * yaw).coerceAtLeast(0.0))
        if (!yaw.isFinite() || transverse > 0.6) disturb(timestampNs)
        return Observation(yaw.takeIf { it.isFinite() }, timestampNs <= disturbedUntilNs)
    }
}
