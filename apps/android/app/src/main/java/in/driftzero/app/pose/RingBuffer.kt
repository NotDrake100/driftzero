package `in`.driftzero.app.pose

/**
 * Fixed-capacity ring. Oldest samples drop. Used for trails, the mode strip,
 * and output-gap timing. Not a filter input.
 */
class RingBuffer<T>(val capacity: Int) {
    private val items = ArrayDeque<T>(capacity)

    val size: Int get() = items.size

    fun add(item: T) {
        if (items.size == capacity) {
            items.removeFirst()
        }
        items.addLast(item)
    }

    fun toList(): List<T> = items.toList()

    fun clear() {
        items.clear()
    }
}
