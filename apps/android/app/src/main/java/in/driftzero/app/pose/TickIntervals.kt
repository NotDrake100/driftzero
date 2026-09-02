package `in`.driftzero.app.pose

/**
 * Records emit intervals and reports p95 in milliseconds.
 * Needs [MIN_SAMPLES] samples. Null until then. Never invents a gap.
 */
class TickIntervals(
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val buffer = RingBuffer<Long>(capacity)
    private var lastNs: Long? = null

    fun record(nowNs: Long) {
        val previous = lastNs
        lastNs = nowNs
        if (previous != null && nowNs >= previous) {
            buffer.add(nowNs - previous)
        }
    }

    fun p95Ms(): Double? {
        if (buffer.size < MIN_SAMPLES) {
            return null
        }
        val sorted = buffer.toList().sorted()
        val index = ((sorted.size - 1) * 0.95).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index] / 1_000_000.0
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 600
        const val MIN_SAMPLES: Int = 20
    }
}
