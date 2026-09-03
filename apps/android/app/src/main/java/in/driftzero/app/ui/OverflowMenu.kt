package `in`.driftzero.app.ui

/** Overflow (More) on the travel chrome. Search hits own the top of the map. */
internal object OverflowMenu {
    fun shouldDismiss(suggestionCount: Int): Boolean = suggestionCount > 0
}
