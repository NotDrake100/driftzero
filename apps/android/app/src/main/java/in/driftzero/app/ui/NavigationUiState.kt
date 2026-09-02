package `in`.driftzero.app.ui

import `in`.driftzero.app.geo.GeoPoint
import `in`.driftzero.app.location.LiveFix
import `in`.driftzero.app.location.OriginSource
import `in`.driftzero.app.routing.RouteFailure
import `in`.driftzero.app.routing.RoutePlan
import `in`.driftzero.app.search.Place
import `in`.driftzero.app.search.SearchFailure

enum class SearchPanel {
    Hidden,
    Typing,
    Loading,
    Results,
    Empty,
    Error,
}

enum class RoutePanel {
    Idle,
    Loading,
    Ready,
    Error,
}

/**
 * First-screen product state. Destination search is collected here;
 * geocoding and routing are owned by this module.
 */
data class NavigationUiState(
    val destinationQuery: String = "",
    val liveFix: LiveFix? = null,
    val locationPermissionGranted: Boolean = false,
    val startRequested: Boolean = false,
    val searchPanel: SearchPanel = SearchPanel.Hidden,
    val searchResults: List<Place> = emptyList(),
    val searchFailure: SearchFailure? = null,
    val destination: Place? = null,
    val origin: GeoPoint? = null,
    val originSource: OriginSource = OriginSource.Fallback,
    val waitingForFix: Boolean = true,
    val gpsLost: Boolean = false,
    val speedMetersPerSecond: Double? = null,
    val route: RoutePlan? = null,
    val routePanel: RoutePanel = RoutePanel.Idle,
    val routeFailure: RouteFailure? = null,
    val cameraEpoch: Int = 0,
) {
    val canStart: Boolean
        get() = destinationQuery.trim().isNotEmpty() && destination == null
}
