package `in`.driftzero.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 3-vector. Metres, m/s, rad/s, or m/s² depending on the call site. */
internal data class Vec3(val x: Double, val y: Double, val z: Double) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "vector must be finite" }
    }

    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun times(scale: Double): Vec3 = Vec3(x * scale, y * scale, z * scale)

    operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)

    fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun norm(): Double = sqrt(dot(this))

    fun normalized(): Vec3? {
        val n = norm()
        if (n < 1e-12) {
            return null
        }
        return this * (1.0 / n)
    }

    fun toArray(): DoubleArray = doubleArrayOf(x, y, z)

    companion object {
        val ZERO: Vec3 = Vec3(0.0, 0.0, 0.0)
        val EX: Vec3 = Vec3(1.0, 0.0, 0.0)
        val EY: Vec3 = Vec3(0.0, 1.0, 0.0)
        val EZ: Vec3 = Vec3(0.0, 0.0, 1.0)
    }
}

/** Row-major 3×3. */
internal data class Mat3(
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
    operator fun get(i: Int, j: Int): Double = when (i * 3 + j) {
        0 -> r00
        1 -> r01
        2 -> r02
        3 -> r10
        4 -> r11
        5 -> r12
        6 -> r20
        7 -> r21
        8 -> r22
        else -> error("index $i,$j")
    }

    operator fun times(v: Vec3): Vec3 = Vec3(
        r00 * v.x + r01 * v.y + r02 * v.z,
        r10 * v.x + r11 * v.y + r12 * v.z,
        r20 * v.x + r21 * v.y + r22 * v.z,
    )

    operator fun times(other: Mat3): Mat3 = Mat3(
        r00 * other.r00 + r01 * other.r10 + r02 * other.r20,
        r00 * other.r01 + r01 * other.r11 + r02 * other.r21,
        r00 * other.r02 + r01 * other.r12 + r02 * other.r22,
        r10 * other.r00 + r11 * other.r10 + r12 * other.r20,
        r10 * other.r01 + r11 * other.r11 + r12 * other.r21,
        r10 * other.r02 + r11 * other.r12 + r12 * other.r22,
        r20 * other.r00 + r21 * other.r10 + r22 * other.r20,
        r20 * other.r01 + r21 * other.r11 + r22 * other.r21,
        r20 * other.r02 + r21 * other.r12 + r22 * other.r22,
    )

    operator fun plus(other: Mat3): Mat3 = Mat3(
        r00 + other.r00, r01 + other.r01, r02 + other.r02,
        r10 + other.r10, r11 + other.r11, r12 + other.r12,
        r20 + other.r20, r21 + other.r21, r22 + other.r22,
    )

    operator fun times(scale: Double): Mat3 = Mat3(
        r00 * scale, r01 * scale, r02 * scale,
        r10 * scale, r11 * scale, r12 * scale,
        r20 * scale, r21 * scale, r22 * scale,
    )

    fun transpose(): Mat3 = Mat3(r00, r10, r20, r01, r11, r21, r02, r12, r22)

    fun timesT(v: Vec3): Vec3 = transpose() * v

    fun row(i: Int): Vec3 = when (i) {
        0 -> Vec3(r00, r01, r02)
        1 -> Vec3(r10, r11, r12)
        2 -> Vec3(r20, r21, r22)
        else -> error("row $i")
    }

    fun isFinite(): Boolean =
        r00.isFinite() && r01.isFinite() && r02.isFinite() &&
            r10.isFinite() && r11.isFinite() && r12.isFinite() &&
            r20.isFinite() && r21.isFinite() && r22.isFinite()

    companion object {
        val IDENTITY: Mat3 = Mat3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val ZERO: Mat3 = Mat3(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        fun skew(v: Vec3): Mat3 = Mat3(
            0.0, -v.z, v.y,
            v.z, 0.0, -v.x,
            -v.y, v.x, 0.0,
        )

        /** Default dash mount: Android Y forward, −X left, Z up. */
        val ANDROID_Y_FORWARD: Mat3 = Mat3(
            0.0, 1.0, 0.0,
            -1.0, 0.0, 0.0,
            0.0, 0.0, 1.0,
        )
    }
}

/**
 * Hamilton quaternion [w, x, y, z]. Rotates body vectors into the nav frame:
 * `v_n = q ⊗ v_b ⊗ q*` (Solà (86)).
 */
internal data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    fun times(other: Quat): Quat = Quat(
        w * other.w - x * other.x - y * other.y - z * other.z,
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w,
    )

    fun conjugate(): Quat = Quat(w, -x, -y, -z)

    fun rotate(v: Vec3): Vec3 {
        val qv = Quat(0.0, v.x, v.y, v.z)
        val r = times(qv).times(conjugate())
        return Vec3(r.x, r.y, r.z)
    }

    fun toRotation(): Mat3 {
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        return Mat3(
            1.0 - 2.0 * (yy + zz),
            2.0 * (xy - wz),
            2.0 * (xz + wy),
            2.0 * (xy + wz),
            1.0 - 2.0 * (xx + zz),
            2.0 * (yz - wx),
            2.0 * (xz - wy),
            2.0 * (yz + wx),
            1.0 - 2.0 * (xx + yy),
        )
    }

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        require(n > 1e-18 && n.isFinite()) { "quaternion norm" }
        return Quat(w / n, x / n, y / n, z / n)
    }

    fun isFinite(): Boolean = w.isFinite() && x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val IDENTITY: Quat = Quat(1.0, 0.0, 0.0, 0.0)

        fun fromRotation(m: Mat3): Quat {
            val trace = m.r00 + m.r11 + m.r22
            val q = if (trace > 0.0) {
                val s = sqrt(trace + 1.0) * 2.0
                Quat(0.25 * s, (m.r21 - m.r12) / s, (m.r02 - m.r20) / s, (m.r10 - m.r01) / s)
            } else if (m.r00 > m.r11 && m.r00 > m.r22) {
                val s = sqrt(1.0 + m.r00 - m.r11 - m.r22) * 2.0
                Quat((m.r21 - m.r12) / s, 0.25 * s, (m.r01 + m.r10) / s, (m.r02 + m.r20) / s)
            } else if (m.r11 > m.r22) {
                val s = sqrt(1.0 + m.r11 - m.r00 - m.r22) * 2.0
                Quat((m.r02 - m.r20) / s, (m.r01 + m.r10) / s, 0.25 * s, (m.r12 + m.r21) / s)
            } else {
                val s = sqrt(1.0 + m.r22 - m.r00 - m.r11) * 2.0
                Quat((m.r10 - m.r01) / s, (m.r02 + m.r20) / s, (m.r12 + m.r21) / s, 0.25 * s)
            }
            return q.normalized()
        }
    }
}

internal fun bodyToVehicle(frame: VectorFrame): Mat3 = when (frame) {
    VectorFrame.VEHICLE_FLU, VectorFrame.UNSPECIFIED -> Mat3.IDENTITY
    VectorFrame.ANDROID_DEVICE -> Mat3.ANDROID_Y_FORWARD
}

internal fun bodyForward(frame: VectorFrame): Vec3 = when (frame) {
    VectorFrame.VEHICLE_FLU, VectorFrame.UNSPECIFIED -> Vec3.EX
    VectorFrame.ANDROID_DEVICE -> Vec3.EY
}

/**
 * TRIAD: specific force (gravity-up when still) plus vehicle course.
 * Heading is clockwise from true north. Body-forward is the vehicle +X in the sensor frame.
 */
internal fun attitudeFromGravityAndHeading(
    specificForceBody: Vec3,
    headingRad: Double,
    frame: VectorFrame,
): Quat? {
    val upBody = specificForceBody.normalized() ?: return null
    val fwdBodyRaw = bodyForward(frame)
    val fwdBody = (fwdBodyRaw - upBody * fwdBodyRaw.dot(upBody)).normalized() ?: return null
    val leftBody = upBody.cross(fwdBody).normalized() ?: return null
    val fwdNav = Vec3(sin(headingRad), cos(headingRad), 0.0)
    val upNav = Vec3.EZ
    val leftNav = upNav.cross(fwdNav).normalized() ?: return null
    val body = Mat3(
        fwdBody.x, leftBody.x, upBody.x,
        fwdBody.y, leftBody.y, upBody.y,
        fwdBody.z, leftBody.z, upBody.z,
    )
    val nav = Mat3(
        fwdNav.x, leftNav.x, upNav.x,
        fwdNav.y, leftNav.y, upNav.y,
        fwdNav.z, leftNav.z, upNav.z,
    )
    val c = nav * body.transpose()
    if (!c.isFinite()) {
        return null
    }
    return Quat.fromRotation(c)
}

internal fun yawOnlyAttitude(headingRad: Double, frame: VectorFrame): Quat {
    val c = cos(headingRad)
    val s = sin(headingRad)
    val vehicleToEnu = Mat3(
        s, -c, 0.0,
        c, s, 0.0,
        0.0, 0.0, 1.0,
    )
    return Quat.fromRotation(vehicleToEnu * bodyToVehicle(frame))
}

internal fun invert3(m: DoubleArray): DoubleArray? {
    require(m.size == 9)
    val a = m[0]
    val b = m[1]
    val c = m[2]
    val d = m[3]
    val e = m[4]
    val f = m[5]
    val g = m[6]
    val h = m[7]
    val i = m[8]
    val a00 = e * i - f * h
    val a01 = c * h - b * i
    val a02 = b * f - c * e
    val a10 = f * g - d * i
    val a11 = a * i - c * g
    val a12 = c * d - a * f
    val a20 = d * h - e * g
    val a21 = b * g - a * h
    val a22 = a * e - b * d
    val det = a * a00 + b * a10 + c * a20
    if (!det.isFinite() || abs(det) < 1e-18) {
        return null
    }
    val inv = 1.0 / det
    return doubleArrayOf(
        a00 * inv, a01 * inv, a02 * inv,
        a10 * inv, a11 * inv, a12 * inv,
        a20 * inv, a21 * inv, a22 * inv,
    )
}

internal fun invert2(m: DoubleArray): DoubleArray? {
    require(m.size == 4)
    val det = m[0] * m[3] - m[1] * m[2]
    if (!det.isFinite() || abs(det) < 1e-18) {
        return null
    }
    val inv = 1.0 / det
    return doubleArrayOf(m[3] * inv, -m[1] * inv, -m[2] * inv, m[0] * inv)
}
