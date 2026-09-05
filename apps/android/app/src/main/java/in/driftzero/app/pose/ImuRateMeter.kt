package `in`.driftzero.app.pose

import kotlin.math.roundToInt

/**
 * Median IMU rate from consecutive timestamp deltas. Never assumes a Hertz
 * value. [medianHz] is null until two samples have a positive dt.
 */
class ImuRateMeter(window: Int = WINDOW) {
    private val cap = window.coerceAtLeast(2)
    private val dts = LongArray(cap)
    private var lastNs: Long? = null
    private var sampleCount: Int = 0
    private var dtCount: Int = 0
    private var dtWrite: Int = 0
    private var positiveDts: Int = 0
    private var sumDtNs: Long = 0L
    private var minDtNs: Long = Long.MAX_VALUE
    private var maxDtNs: Long = 0L

    fun accept(timestampNs: Long) {
        if (timestampNs < 0L) {
            return
        }
        val prev = lastNs
        lastNs = timestampNs
        sampleCount += 1
        if (prev == null) {
            return
        }
        val dt = timestampNs - prev
        if (dt <= 0L) {
            return
        }
        if (dtCount < cap) {
            dts[dtCount] = dt
            dtCount += 1
        } else {
            dts[dtWrite] = dt
            dtWrite = (dtWrite + 1) % cap
        }
        positiveDts += 1
        sumDtNs += dt
        if (dt < minDtNs) {
            minDtNs = dt
        }
        if (dt > maxDtNs) {
            maxDtNs = dt
        }
    }

    fun snapshot(): ImuRateSnapshot {
        val medianDt = medianDtNs()
        val meanHz = if (positiveDts > 0 && sumDtNs > 0L) {
            (positiveDts.toDouble() * NS_PER_S) / sumDtNs.toDouble()
        } else {
            null
        }
        return ImuRateSnapshot(
            sampleCount = sampleCount,
            positiveDtCount = positiveDts,
            medianHz = medianDt?.let { NS_PER_S / it.toDouble() },
            meanHz = meanHz,
            medianDtNs = medianDt,
            minDtNs = if (positiveDts > 0) minDtNs else null,
            maxDtNs = if (positiveDts > 0) maxDtNs else null,
        )
    }

    private fun medianDtNs(): Long? {
        if (dtCount <= 0) {
            return null
        }
        val copy = dts.copyOf(dtCount)
        copy.sort()
        val mid = copy.size / 2
        return if (copy.size % 2 == 1) {
            copy[mid]
        } else {
            (copy[mid - 1] + copy[mid]) / 2L
        }
    }

    companion object {
        const val WINDOW: Int = 256
        const val NS_PER_S: Double = 1_000_000_000.0
    }
}

data class ImuRateSnapshot(
    val sampleCount: Int,
    val positiveDtCount: Int,
    val medianHz: Double?,
    val meanHz: Double?,
    val medianDtNs: Long?,
    val minDtNs: Long?,
    val maxDtNs: Long?,
) {
    fun medianHzRounded(): Double? = medianHz?.let { (it * 1000.0).roundToInt() / 1000.0 }
}
