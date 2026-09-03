package `in`.driftzero.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchNotesTest {
    @Test
    fun emptyWhereToShowsNetworkWhenOffline() {
        assertTrue(
            SearchNotes.needsNetworkFallback(
                query = "",
                searchNote = null,
                network = false,
                searchOpen = true,
            ),
        )
        assertTrue(
            SearchNotes.needsNetworkFallback(
                query = " ",
                searchNote = null,
                network = false,
                searchOpen = true,
            ),
        )
    }

    @Test
    fun parentNoteAndOpenSearchWin() {
        assertFalse(
            SearchNotes.needsNetworkFallback(
                query = "",
                searchNote = "Tap the map to set a destination. Search needs network.",
                network = false,
                searchOpen = true,
            ),
        )
        assertFalse(
            SearchNotes.needsNetworkFallback(
                query = "",
                searchNote = null,
                network = false,
                searchOpen = false,
            ),
        )
        assertFalse(
            SearchNotes.needsNetworkFallback(
                query = "",
                searchNote = null,
                network = true,
                searchOpen = true,
            ),
        )
        assertFalse(
            SearchNotes.needsNetworkFallback(
                query = "st",
                searchNote = null,
                network = false,
                searchOpen = true,
            ),
        )
    }

    @Test
    fun holdHintTracksLongPressArming() {
        assertTrue(SearchNotes.holdHintVisible(longPressArmed = true, word = LampWord.GNSS))
        assertTrue(SearchNotes.holdHintVisible(longPressArmed = true, word = LampWord.ASSISTED))
        assertFalse(SearchNotes.holdHintVisible(longPressArmed = true, word = LampWord.DEAD_RECKONING))
        assertFalse(SearchNotes.holdHintVisible(longPressArmed = false, word = LampWord.GNSS))
        assertTrue(
            SearchNotes.holdHintVisible(
                longPressArmed = true,
                word = LampWord.DEAD_RECKONING,
                gnssHeld = true,
            ),
        )
        assertFalse(
            SearchNotes.holdHintVisible(
                longPressArmed = true,
                word = LampWord.GNSS,
                bannerVisible = true,
            ),
        )
        assertTrue(
            SearchNotes.holdHintVisible(
                longPressArmed = true,
                word = LampWord.DEAD_RECKONING,
                gnssHeld = true,
                bannerVisible = true,
            ),
        )
    }

    @Test
    fun destTitlePrefersSearchThenReverseThenMapPin() {
        assertEquals("KEM Hospital", SearchNotes.destTitle(searchName = "KEM Hospital"))
        assertEquals("Somwar Peth", SearchNotes.destTitle(reverseName = "Somwar Peth"))
        assertEquals(
            "KEM Hospital",
            SearchNotes.destTitle(searchName = "KEM Hospital", reverseName = "Somwar Peth"),
        )
        assertEquals(SearchNotes.MAP_PIN, SearchNotes.destTitle())
        assertEquals(SearchNotes.MAP_PIN, SearchNotes.destTitle(searchName = "  ", reverseName = ""))
        assertEquals("Map pin", SearchNotes.MAP_PIN)
    }

    @Test
    fun holdHintHiddenWhileWaitingForFix() {
        assertFalse(SearchNotes.holdHintVisible(longPressArmed = true, word = LampWord.WAITING_FIX))
        assertFalse(SearchNotes.holdHintVisible(longPressArmed = true, word = LampWord.NO_PERMISSION))
        assertFalse(SearchNotes.holdHintVisible(longPressArmed = true, word = LampWord.PRECISE_OFF))
        assertFalse(SearchNotes.holdHintVisible(longPressArmed = true, word = LampWord.REACQUIRING))
    }
}
