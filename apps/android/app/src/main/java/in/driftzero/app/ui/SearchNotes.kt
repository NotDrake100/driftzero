package `in`.driftzero.app.ui

/**
 * Where to? notes. Search always needs a network. Local graph routing does
 * not. Keep the empty-field airplane line visible before the user types.
 */
internal object SearchNotes {
    const val MAP_PIN: String = "Map pin"

    fun needsNetworkFallback(
        query: String,
        searchNote: String?,
        network: Boolean,
        searchOpen: Boolean,
    ): Boolean {
        if (!searchOpen) {
            return false
        }
        if (!searchNote.isNullOrEmpty()) {
            return false
        }
        return !network && query.trim().length < 2
    }

    /**
     * Destination sheet title. Search hits keep their name. A map tap uses
     * a reverse-geocode name when the network returned one, otherwise Map pin.
     */
    fun destTitle(searchName: String? = null, reverseName: String? = null): String {
        val searched = searchName?.trim().orEmpty()
        if (searched.isNotEmpty()) {
            return searched
        }
        val reverse = reverseName?.trim().orEmpty()
        if (reverse.isNotEmpty()) {
            return reverse
        }
        return MAP_PIN
    }

    fun holdHintVisible(
        longPressArmed: Boolean,
        word: LampWord,
        gnssHeld: Boolean = false,
        bannerVisible: Boolean = false,
    ): Boolean {
        if (!longPressArmed) {
            return false
        }
        if (bannerVisible && !gnssHeld) {
            return false
        }
        return when (word) {
            LampWord.GNSS, LampWord.ASSISTED -> true
            LampWord.DEAD_RECKONING, LampWord.REACQUIRING -> gnssHeld
            else -> false
        }
    }
}
