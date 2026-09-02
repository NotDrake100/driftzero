package `in`.driftzero.core

import kotlin.math.abs
import kotlin.math.max

/** 15-state error: δp, δv, δθ, ba, bg. Row-major matrices. */
internal object EskfDim {
    const val N: Int = 15
    const val IP: Int = 0
    const val IV: Int = 3
    const val ITH: Int = 6
    const val IBA: Int = 9
    const val IBG: Int = 12
}

internal class Square(val n: Int, val a: DoubleArray = DoubleArray(n * n)) {
    operator fun get(i: Int, j: Int): Double = a[i * n + j]

    operator fun set(i: Int, j: Int, value: Double) {
        a[i * n + j] = value
    }

    fun zero() {
        a.fill(0.0)
    }

    fun setIdentity() {
        zero()
        for (i in 0 until n) {
            this[i, i] = 1.0
        }
    }

    fun copyFrom(other: Square) {
        require(n == other.n)
        other.a.copyInto(a)
    }

    fun allFinite(): Boolean {
        for (v in a) {
            if (!v.isFinite()) {
                return false
            }
        }
        return true
    }
}

internal fun matMul(out: Square, left: Square, right: Square) {
    require(out.n == left.n && left.n == right.n)
    val n = out.n
    for (i in 0 until n) {
        for (j in 0 until n) {
            var s = 0.0
            for (k in 0 until n) {
                s += left[i, k] * right[k, j]
            }
            out[i, j] = s
        }
    }
}

internal fun matMulABt(out: Square, a: Square, b: Square) {
    val n = out.n
    for (i in 0 until n) {
        for (j in 0 until n) {
            var s = 0.0
            for (k in 0 until n) {
                s += a[i, k] * b[j, k]
            }
            out[i, j] = s
        }
    }
}

internal fun matAddInPlace(target: Square, other: Square, scale: Double = 1.0) {
    for (i in target.a.indices) {
        target.a[i] += scale * other.a[i]
    }
}

internal fun symmetrize(p: Square) {
    val n = p.n
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val v = 0.5 * (p[i, j] + p[j, i])
            p[i, j] = v
            p[j, i] = v
        }
        p[i, i] = max(p[i, i], 0.0)
    }
}

/**
 * Discrete Φ for the 15-state n-frame ESKF. Solà (238a)/(247) plus Groves
 * linearized (2.139) vertical gravity gradient. Earth-rate blocks omitted.
 * Sibling F_{vθ} sign is kept: +[f^n×].
 */
internal fun fillStrapdownPhi(
    phi: Square,
    dt: Double,
    fNav: Vec3,
    bodyToNav: Mat3,
    gravityGradientUp: Double,
) {
    phi.setIdentity()
    for (i in 0..2) {
        phi[EskfDim.IP + i, EskfDim.IV + i] = dt
    }
    phi[EskfDim.IV + 2, EskfDim.IP + 2] = gravityGradientUp * dt
    val skewF = Mat3.skew(fNav)
    for (i in 0..2) {
        for (j in 0..2) {
            phi[EskfDim.IV + i, EskfDim.ITH + j] = skewF[i, j] * dt
            phi[EskfDim.IV + i, EskfDim.IBA + j] = -bodyToNav[i, j] * dt
            phi[EskfDim.ITH + i, EskfDim.IBG + j] = -bodyToNav[i, j] * dt
        }
    }
}

/**
 * IMU process noise from integrating white accel/gyro (Solà 5.4.2 / (260)).
 * Q_v = σ_a² Δt, Q_p = σ_a² Δt³/3, Q_pv = σ_a² Δt²/2. Gyro and bias RW stay
 * diagonal. Earth-rate mapping of gyro noise is omitted.
 */
internal fun addImuProcessNoise(
    p: Square,
    dt: Double,
    accelNoise: Double,
    gyroNoise: Double,
    accelBiasRw: Double,
    gyroBiasRw: Double,
) {
    val sa = accelNoise * accelNoise
    val sg = gyroNoise * gyroNoise * dt
    val sba = accelBiasRw * accelBiasRw * dt
    val sbg = gyroBiasRw * gyroBiasRw * dt
    val qv = sa * dt
    val qp = sa * dt * dt * dt / 3.0
    val qpv = 0.5 * sa * dt * dt
    for (i in 0..2) {
        p[EskfDim.IP + i, EskfDim.IP + i] += qp
        p[EskfDim.IP + i, EskfDim.IV + i] += qpv
        p[EskfDim.IV + i, EskfDim.IP + i] += qpv
        p[EskfDim.IV + i, EskfDim.IV + i] += qv
        p[EskfDim.ITH + i, EskfDim.ITH + i] += sg
        p[EskfDim.IBA + i, EskfDim.IBA + i] += sba
        p[EskfDim.IBG + i, EskfDim.IBG + i] += sbg
    }
}

internal fun pIsHealthy(p: Square): Boolean {
    if (!p.allFinite()) {
        return false
    }
    for (i in 0 until p.n) {
        if (p[i, i] < 0.0 || p[i, i] > 1.0e12) {
            return false
        }
    }
    return true
}

/**
 * Innovation covariance S = H P Hᵀ + R into [JosephScratch.s] and P Hᵀ
 * into [JosephScratch.pht]. Returns false if S is non-finite.
 */
internal fun formInnovationS(
    p: Square,
    h: DoubleArray,
    r: DoubleArray,
    m: Int,
    scratch: JosephScratch,
): Boolean {
    require(p.n == EskfDim.N)
    require(h.size >= m * EskfDim.N)
    require(r.size >= m * m)
    val n = EskfDim.N
    val pht = scratch.pht
    for (i in 0 until n) {
        for (j in 0 until m) {
            var s = 0.0
            for (k in 0 until n) {
                s += p[i, k] * h[j * n + k]
            }
            pht[i * m + j] = s
        }
    }
    val sMat = scratch.s
    for (i in 0 until m) {
        for (j in 0 until m) {
            var acc = r[i * m + j]
            for (k in 0 until n) {
                acc += h[i * n + k] * pht[k * m + j]
            }
            if (!acc.isFinite()) {
                return false
            }
            sMat[i * m + j] = acc
        }
    }
    return true
}

internal fun invertInnovationS(m: Int, sMat: DoubleArray): DoubleArray? {
    return when (m) {
        1 -> {
            val d = sMat[0]
            if (!d.isFinite() || abs(d) < 1e-18) {
                null
            } else {
                doubleArrayOf(1.0 / d)
            }
        }
        2 -> invert2(sMat.copyOf(4))
        3 -> invert3(sMat.copyOf(9))
        else -> error("joseph m=$m")
    }
}

/** Mahalanobis νᵀ S⁻¹ ν. Null if S is singular. TLIO gates 3-dof at 11.345. */
internal fun innovationChiSquared(
    p: Square,
    h: DoubleArray,
    residual: DoubleArray,
    r: DoubleArray,
    m: Int,
    scratch: JosephScratch,
): Double? {
    require(residual.size >= m)
    if (!formInnovationS(p, h, r, m, scratch)) {
        return null
    }
    val sInv = invertInnovationS(m, scratch.s) ?: return null
    var acc = 0.0
    for (i in 0 until m) {
        for (j in 0 until m) {
            acc += residual[i] * sInv[i * m + j] * residual[j]
        }
    }
    return if (acc.isFinite()) acc else null
}

/**
 * Joseph update on a zero-mean error state. [residual] is z − h(nominal).
 * [h] is m×15 row-major. [r] is m×m row-major, SPD.
 * Returns false if S is singular or a value is non-finite.
 *
 * Solà (280) Joseph form P ← (I−KH)P(I−KH)ᵀ + KRKᵀ. Groves 2nd ed. Ch. 14.
 */
internal fun josephUpdate(
    p: Square,
    h: DoubleArray,
    residual: DoubleArray,
    r: DoubleArray,
    m: Int,
    dx: DoubleArray,
    scratch: JosephScratch,
): Boolean {
    require(dx.size == EskfDim.N)
    require(residual.size >= m)
    if (!formInnovationS(p, h, r, m, scratch)) {
        return false
    }
    val sInv = invertInnovationS(m, scratch.s) ?: return false
    val n = EskfDim.N
    val pht = scratch.pht
    val k = scratch.k
    for (i in 0 until n) {
        for (j in 0 until m) {
            var acc = 0.0
            for (pCol in 0 until m) {
                acc += pht[i * m + pCol] * sInv[pCol * m + j]
            }
            k[i * m + j] = acc
        }
    }
    for (i in 0 until n) {
        var acc = 0.0
        for (j in 0 until m) {
            acc += k[i * m + j] * residual[j]
        }
        if (!acc.isFinite()) {
            return false
        }
        dx[i] = acc
    }
    val ikh = scratch.ikh
    ikh.setIdentity()
    for (i in 0 until n) {
        for (j in 0 until n) {
            var acc = 0.0
            for (t in 0 until m) {
                acc += k[i * m + t] * h[t * n + j]
            }
            ikh[i, j] = ikh[i, j] - acc
        }
    }
    matMul(scratch.tmp, ikh, p)
    matMulABt(scratch.tmp2, scratch.tmp, ikh)
    val krkt = scratch.tmp
    krkt.zero()
    for (i in 0 until n) {
        for (j in 0 until n) {
            var acc = 0.0
            for (a in 0 until m) {
                for (b in 0 until m) {
                    acc += k[i * m + a] * r[a * m + b] * k[j * m + b]
                }
            }
            krkt[i, j] = acc
        }
    }
    p.copyFrom(scratch.tmp2)
    matAddInPlace(p, krkt)
    symmetrize(p)
    return pIsHealthy(p)
}

internal class JosephScratch {
    val pht: DoubleArray = DoubleArray(EskfDim.N * 3)
    val s: DoubleArray = DoubleArray(9)
    val k: DoubleArray = DoubleArray(EskfDim.N * 3)
    val ikh: Square = Square(EskfDim.N)
    val tmp: Square = Square(EskfDim.N)
    val tmp2: Square = Square(EskfDim.N)
}
