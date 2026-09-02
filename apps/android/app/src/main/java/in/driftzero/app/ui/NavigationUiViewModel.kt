package `in`.driftzero.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.driftzero.app.location.DeviceFix
import `in`.driftzero.app.location.LiveFix
import `in`.driftzero.app.location.OriginResolver
import `in`.driftzero.app.net.OkHttpTextGetter
import `in`.driftzero.app.routing.OsrmRouteService
import `in`.driftzero.app.search.Place
import `in`.driftzero.app.search.PlaceSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NavigationUiViewModel(application: Application) : AndroidViewModel(application) {
    private val http = OkHttpTextGetter()
    private val session = NavigatorSession(
        search = PlaceSearch(http),
        router = OsrmRouteService(http),
        nowElapsedMs = { android.os.SystemClock.elapsedRealtime() },
    )
    private val _state = MutableStateFlow(session.state)
    val state: StateFlow<NavigationUiState> = _state.asStateFlow()
    private var searchJob: Job? = null
    private var lastLiveFix: LiveFix? = null

    init {
        publish()
        viewModelScope.launch {
            delay(OriginResolver.FALLBACK_WAIT_MS + 100)
            withContext(Dispatchers.Default) { session.onFix(null) }
            publish()
        }
    }

    fun setDestinationQuery(query: String) {
        session.onQueryChange(query)
        publish()
        searchJob?.cancel()
        if (query.trim().length < 3) return
        searchJob = viewModelScope.launch {
            delay(400)
            withContext(Dispatchers.IO) { session.searchNow() }
            publish()
        }
    }

    fun setLocationPermissionGranted(granted: Boolean) {
        if (!granted) lastLiveFix = null
        session.onPermission(granted)
        publish()
    }

    fun onLiveFix(fix: LiveFix?) {
        if (fix != null) lastLiveFix = fix
        session.onFix(fix?.toDeviceFix())
        publish()
    }

    fun requestStart() {
        searchJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { session.searchNow() }
            publish()
        }
    }

    fun onDemoSuggestion() {
        searchJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { session.useDemoDestinationQuery() }
            publish()
        }
    }

    fun onPlaceSelected(place: Place) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { session.selectPlace(place) }
            publish()
        }
    }

    fun onClearDestination() {
        session.clearDestination()
        publish()
    }

    private fun publish() {
        _state.value = session.state.copy(liveFix = lastLiveFix)
    }
}

private fun LiveFix.toDeviceFix(): DeviceFix = DeviceFix(
    point = `in`.driftzero.app.geo.GeoPoint(latitudeDeg, longitudeDeg),
    elapsedRealtimeMs = elapsedRealtimeNs / 1_000_000L,
    speedMetersPerSecond = speedMps,
)
