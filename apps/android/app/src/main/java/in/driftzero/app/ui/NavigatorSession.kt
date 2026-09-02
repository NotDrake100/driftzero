package `in`.driftzero.app.ui

import `in`.driftzero.app.geo.GeoPoint
import `in`.driftzero.app.location.DeviceFix
import `in`.driftzero.app.location.OriginResolver
import `in`.driftzero.app.product.DemoPlaces
import `in`.driftzero.app.routing.OsrmRouteService
import `in`.driftzero.app.routing.RouteOutcome
import `in`.driftzero.app.search.Place
import `in`.driftzero.app.search.PlaceSearch
import `in`.driftzero.app.search.SearchOutcome

/**
 * Product state machine. JVM-testable; no Android types.
 */
class NavigatorSession(
    private val search: PlaceSearch,
    private val router: OsrmRouteService,
    private val nowElapsedMs: () -> Long,
) {
    var state: NavigationUiState = NavigationUiState()
        private set

    private var lastFix: DeviceFix? = null
    private var sessionStartMs: Long = nowElapsedMs()
    private var lastRoutedOrigin: GeoPoint? = null
    private var lastRoutedDestinationId: String? = null

    fun onPermission(granted: Boolean) {
        state = state.copy(locationPermissionGranted = granted)
        refreshOrigin()
    }

    fun onFix(fix: DeviceFix?) {
        lastFix = fix ?: lastFix
        refreshOrigin()
        maybeReroute()
    }

    fun onQueryChange(query: String) {
        val trimmed = query
        state = state.copy(
            destinationQuery = trimmed,
            searchPanel = when {
                trimmed.isBlank() -> SearchPanel.Hidden
                trimmed.trim().length < 3 -> SearchPanel.Typing
                else -> SearchPanel.Typing
            },
            searchFailure = null,
        )
        if (trimmed.isBlank()) {
            state = state.copy(searchResults = emptyList())
        }
    }

    fun searchNow() {
        val query = state.destinationQuery.trim()
        if (query.length < 3) {
            state = state.copy(searchPanel = if (query.isEmpty()) SearchPanel.Hidden else SearchPanel.Typing)
            return
        }
        state = state.copy(searchPanel = SearchPanel.Loading, searchFailure = null)
        when (val outcome = search.search(query, bias())) {
            is SearchOutcome.Found -> state = state.copy(
                searchPanel = SearchPanel.Results,
                searchResults = outcome.places,
                searchFailure = null,
            )
            SearchOutcome.Empty -> state = state.copy(
                searchPanel = SearchPanel.Empty,
                searchResults = emptyList(),
                searchFailure = null,
            )
            is SearchOutcome.Failed -> state = state.copy(
                searchPanel = SearchPanel.Error,
                searchResults = emptyList(),
                searchFailure = outcome.reason,
            )
        }
    }

    fun useDemoDestinationQuery() {
        onQueryChange(DemoPlaces.DEMO_DESTINATION_QUERY)
        searchNow()
    }

    fun selectPlace(place: Place) {
        state = state.copy(
            destination = place,
            destinationQuery = place.name,
            searchPanel = SearchPanel.Hidden,
            searchResults = emptyList(),
            route = null,
            routePanel = RoutePanel.Loading,
            routeFailure = null,
            cameraEpoch = state.cameraEpoch + 1,
        )
        refreshOrigin()
        requestRoute()
    }

    fun clearDestination() {
        lastRoutedDestinationId = null
        lastRoutedOrigin = null
        state = state.copy(
            destination = null,
            route = null,
            routePanel = RoutePanel.Idle,
            routeFailure = null,
            destinationQuery = "",
            searchPanel = SearchPanel.Hidden,
            searchResults = emptyList(),
            cameraEpoch = state.cameraEpoch + 1,
        )
    }

    private fun refreshOrigin() {
        val waited = nowElapsedMs() - sessionStartMs
        val decision = OriginResolver.decide(
            fix = lastFix,
            nowElapsedMs = nowElapsedMs(),
            waitedMs = waited,
            hasPermission = state.locationPermissionGranted,
        )
        state = state.copy(
            origin = decision.point,
            originSource = decision.source,
            waitingForFix = decision.waitingForFix,
            gpsLost = decision.gpsLost,
            speedMetersPerSecond = decision.speedMetersPerSecond,
        )
    }

    private fun maybeReroute() {
        val destination = state.destination ?: return
        val origin = state.origin ?: return
        if (state.routePanel == RoutePanel.Loading) return
        val previous = lastRoutedOrigin ?: return
        if (lastRoutedDestinationId != destination.id) return
        if (roughlySame(previous, origin)) return
        state = state.copy(routePanel = RoutePanel.Loading, routeFailure = null)
        requestRoute()
    }

    private fun requestRoute() {
        val origin = state.origin ?: return
        val destination = state.destination ?: return
        when (val outcome = router.drivingRoute(origin, destination.point)) {
            is RouteOutcome.Ok -> {
                lastRoutedOrigin = origin
                lastRoutedDestinationId = destination.id
                state = state.copy(
                    route = outcome.plan,
                    routePanel = RoutePanel.Ready,
                    routeFailure = null,
                    cameraEpoch = state.cameraEpoch + 1,
                )
            }
            is RouteOutcome.Failed -> {
                state = state.copy(
                    route = null,
                    routePanel = RoutePanel.Error,
                    routeFailure = outcome.reason,
                )
            }
        }
    }

    private fun bias(): GeoPoint = state.origin ?: DemoPlaces.PUNE_BIAS

    private fun roughlySame(a: GeoPoint, b: GeoPoint): Boolean {
        val dLat = a.latitudeDeg - b.latitudeDeg
        val dLon = a.longitudeDeg - b.longitudeDeg
        return dLat * dLat + dLon * dLon < 1.0e-8
    }
}
