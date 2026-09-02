package `in`.driftzero.app.ui

import androidx.lifecycle.ViewModel
import `in`.driftzero.app.location.LiveFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class NavigationUiViewModel : ViewModel() {
    private val _state = MutableStateFlow(NavigationUiState())
    val state: StateFlow<NavigationUiState> = _state.asStateFlow()

    fun setDestinationQuery(query: String) {
        _state.update { it.copy(destinationQuery = query, startRequested = false) }
    }

    fun setLocationPermissionGranted(granted: Boolean) {
        _state.update { current ->
            current.copy(
                locationPermissionGranted = granted,
                liveFix = if (granted) current.liveFix else null,
            )
        }
    }

    fun onLiveFix(fix: LiveFix?) {
        _state.update { it.copy(liveFix = fix) }
    }

    fun requestStart() {
        _state.update { current ->
            if (current.destinationQuery.trim().isEmpty()) {
                current
            } else {
                current.copy(startRequested = true)
            }
        }
    }
}
