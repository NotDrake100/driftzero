package `in`.driftzero.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared linear algebra for the platform-neutral navigation core.
 *
 * Frames:
 * - Phone / Android device: x right, y toward the top of the screen, z out of the screen.
 * - Vehicle: x forward, y left, z up (right-handed FLU).
 *
 * Units: metres, seconds, radians. Timestamps stay in integer nanoseconds at API boundaries.
 *
 * This file exists so mount alignment (and later the dead-reckoning filter) share one
 * [Mat3] / [Vec3] type. Production path. No Android types.
 */
const val STANDARD_GRAVITY_MPS2: Double = 9.80665

/** Near-zero guard for norms. Not a silent replacement value for missing data. */
internal const val VEC_DEGENERATE_NORM: Double = 1.0e-12

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun times(scale: Double): Vec3 = Vec3(x * scale, y * scale, z * scale)

    operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)

    fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 =
        Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )

    fun norm(): Double = sqrt(x * x + y * y + z * z)

    fun normSq(): Double = x * x + y * y + z * z

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    /**
     * Unit vector, or [UnitVec.Degenerate] when the norm is not finite or is near zero.
     * Never returns a zero vector as a stand-in for failure.
     */
    fun unit(): UnitVec {
        if (!isFinite()) {
            return UnitVec.Degenerate("non-finite vector")
        }
        val n = norm()
        if (n < VEC_DEGENERATE_NORM) {
            return UnitVec.Degenerate("near-zero vector")
        }
        return UnitVec.Ok(this * (1.0 / n))
    }

    companion object {
        val ZERO: Vec3 = Vec3(0.0, 0.0, 0.0)
        val PLUS_X: Vec3 = Vec3(1.0, 0.0, 0.0)
        val PLUS_Y: Vec3 = Vec3(0.0, 1.0, 0.0)
        val PLUS_Z: Vec3 = Vec3(0.0, 0.0, 1.0)
    }
}

sealed class UnitVec {
    data class Ok(val value: Vec3) : UnitVec()

    data class Degenerate(val reason: String) : UnitVec()
}

/**
 * 3x3 matrix stored row-major. Used as a rotation from one right-handed frame to another.
 *
 * `v_out = R * v_in`.
 */
data class Mat3(
    val r00: Double,
    val r01: Double,
    val r02: Double,
    val r10: Double,
    val r11: Double,
    val r12: Double,
    val r20: Double,
    val r21: Double,
    val r22: Double,
) {
    fun row0(): Vec3 = Vec3(r00, r01, r02)

    fun row1(): Vec3 = Vec3(r10, r11, r12)

    fun row2(): Vec3 = Vec3(r20, r21, r22)

    fun col0(): Vec3 = Vec3(r00, r10, r20)

    fun col1(): Vec3 = Vec3(r01, r11, r21)

    fun col2(): Vec3 = Vec3(r02, r12, r22)

    fun isFinite(): Boolean =
        r00.isFinite() &&
            r01.isFinite() &&
            r02.isFinite() &&
            r10.isFinite() &&
            r11.isFinite() &&
            r12.isFinite() &&
            r20.isFinite() &&
            r21.isFinite() &&
            r22.isFinite()

    fun transpose(): Mat3 =
        Mat3(
            r00, r10, r20,
            r01, r11, r21,
            r02, r12, r22,
        )

    operator fun times(v: Vec3): Vec3 =
        Vec3(
            r00 * v.x + r01 * v.y + r02 * v.z,
            r10 * v.x + r11 * v.y + r12 * v.z,
            r20 * v.x + r21 * v.y + r22 * v.z,
        )

    operator fun times(other: Mat3): Mat3 {
        val a = this
        val b = other
        return Mat3(
            a.r00 * b.r00 + a.r01 * b.r10 + a.r02 * b.r20,
            a.r00 * b.r01 + a.r01 * b.r11 + a.r02 * b.r21,
            a.r00 * b.r02 + a.r01 * b.r12 + a.r02 * b.r22,
            a.r10 * b.r00 + a.r11 * b.r10 + a.r12 * b.r20,
            a.r10 * b.r01 + a.r11 * b.r11 + a.r12 * b.r21,
            a.r10 * b.r02 + a.r11 * b.r12 + a.r12 * b.r22,
            a.r20 * b.r00 + a.r21 * b.r10 + a.r22 * b.r20,
            a.r20 * b.r01 + a.r21 * b.r11 + a.r22 * b.r21,
            a.r20 * b.r02 + a.r21 * b.r12 + a.r22 * b.r22,
        )
    }

    fun toRowMajor(): DoubleArray =
        doubleArrayOf(r00, r01, r02, r10, r11, r12, r20, r21, r22)

    /**
     * ZYX Euler angles matching [bodyToVehicle]: R = Rz(yaw) Ry(pitch) Rx(roll).
     * Units: radians.
     */
    fun toBodyToVehicleEuler(): EulerZyx {
        val pitch = atan2(-r20, hypot(r21, r22))
        val roll = atan2(r21, r22)
        val yaw = atan2(r10, r00)
        return EulerZyx(rollRad = roll, pitchRad = pitch, yawRad = yaw)
    }

    companion object {
        val IDENTITY: Mat3 =
            Mat3(
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0,
            )

        fun fromRows(row0: Vec3, row1: Vec3, row2: Vec3): Mat3 =
            Mat3(
                row0.x, row0.y, row0.z,
                row1.x, row1.y, row1.z,
                row2.x, row2.y, row2.z,
            )

        fun fromRowMajor(values: DoubleArray): Mat3? {
            if (values.size != 9) {
                return null
            }
            val m =
                Mat3(
                    values[0], values[1], values[2],
                    values[3], values[4], values[5],
                    values[6], values[7], values[8],
                )
            return if (m.isFinite()) m else null
        }

        /** Right-handed active rotation about +x, [angleRad] in radians. */
        fun rotationX(angleRad: Double): Mat3 {
            val c = cos(angleRad)
            val s = sin(angleRad)
            return Mat3(
                1.0, 0.0, 0.0,
                0.0, c, -s,
                0.0, s, c,
            )
        }

        /** Right-handed active rotation about +y, [angleRad] in radians. */
        fun rotationY(angleRad: Double): Mat3 {
            val c = cos(angleRad)
            val s = sin(angleRad)
            return Mat3(
                c, 0.0, s,
                0.0, 1.0, 0.0,
                -s, 0.0, c,
            )
        }

        /** Right-handed active rotation about +z, [angleRad] in radians. */
        fun rotationZ(angleRad: Double): Mat3 {
            val c = cos(angleRad)
            val s = sin(angleRad)
            return Mat3(
                c, -s, 0.0,
                s, c, 0.0,
                0.0, 0.0, 1.0,
            )
        }
    }
}

data class EulerZyx(val rollRad: Double, val pitchRad: Double, val yawRad: Double)

/**
 * Rotation from phone/body coordinates to the vehicle frame.
 *
 * `v_vehicle = Rz(yaw) * Ry(pitch) * Rx(roll) * v_body`.
 *
 * Angles are radians, right-handed. Vehicle axes are x forward, y left, z up.
 */
fun bodyToVehicle(rollRad: Double, pitchRad: Double, yawRad: Double): Mat3 =
    Mat3.rotationZ(yawRad) * Mat3.rotationY(pitchRad) * Mat3.rotationX(rollRad)

/**
 * Body-to-vehicle yaw of [ANDROID_Y_FORWARD] in the [bodyToVehicle] convention.
 * Radians.
 */
const val ANDROID_Y_FORWARD_YAW_RAD: Double = -PI / 2.0

/**
 * Default phone-to-vehicle rotation for an upright portrait mount:
 * Android +Y along vehicle +Z (gravity / up), Android -Z along vehicle +X (forward),
 * Android +X along vehicle -Y (right).
 *
 * This is the ANDROID_Y_FORWARD family member with yaw [ANDROID_Y_FORWARD_YAW_RAD].
 * Equals [bodyToVehicle](roll = π/2, pitch = 0, yaw = -π/2).
 */
val ANDROID_Y_FORWARD: Mat3 =
    bodyToVehicle(rollRad = PI / 2.0, pitchRad = 0.0, yawRad = ANDROID_Y_FORWARD_YAW_RAD)

/** Unsigned angle between two vectors, radians. Degenerate if either vector is unusable. */
fun angleBetween(a: Vec3, b: Vec3): AngleBetween {
    val ua = a.unit()
    val ub = b.unit()
    if (ua is UnitVec.Degenerate) {
        return AngleBetween.Degenerate(ua.reason)
    }
    if (ub is UnitVec.Degenerate) {
        return AngleBetween.Degenerate(ub.reason)
    }
    val uaOk = ua as UnitVec.Ok
    val ubOk = ub as UnitVec.Ok
    val c = uaOk.value.dot(ubOk.value).coerceIn(-1.0, 1.0)
    return AngleBetween.Ok(kotlin.math.acos(c))
}

sealed class AngleBetween {
    data class Ok(val radians: Double) : AngleBetween()

    data class Degenerate(val reason: String) : AngleBetween()
}

