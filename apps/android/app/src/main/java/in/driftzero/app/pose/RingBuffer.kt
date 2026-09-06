package `in`.driftzero.app.pose

/**
 * Fixed-capacity ring. Oldest samples drop. Used for trails, the mode strip,
 * and output-gap timing. Not a filter input.
 */
class RingBuffer<T>(val capacity: Int) {
    private val items = ArrayDeque<T>(capacity)

    val size: Int get() = items.size

    /** Returns true when the oldest sample was dropped to make room. */
    fun add(item: T): Boolean {
        val dropped = items.size == capacity
        if (dropped) {
            items.removeFirst()
        }
        items.addLast(item)
        return dropped
    }

    fun toList(): List<T> = items.toList()

    fun drain(): List<T> {
        val out = items.toList()
        items.clear()
        return out
    }

    fun clear() {
        items.clear()
    }
}
