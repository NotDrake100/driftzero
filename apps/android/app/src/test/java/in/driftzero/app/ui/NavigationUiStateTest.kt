package `in`.driftzero.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationUiStateTest {
    @Test
    fun startHiddenUntilDestinationExists() {
        assertFalse(NavigationUiState(destinationQuery = "").canStart)
        assertFalse(NavigationUiState(destinationQuery = "   ").canStart)
        assertTrue(NavigationUiState(destinationQuery = "Koregaon Park").canStart)
    }
}
