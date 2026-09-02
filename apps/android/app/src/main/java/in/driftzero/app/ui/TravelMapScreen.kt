package `in`.driftzero.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.driftzero.app.R
import `in`.driftzero.core.NavigationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Travel map. The map fills the phone. Chrome is a Where to? pill, a locate
 * control, and a small route sheet after a destination is chosen.
 */
@Composable
fun TravelMapScreen(
    controller: StreetMapController = remember { StreetMapController() },
    search: TravelSearchClient = remember { TravelSearchClient() },
    pose: NavigationState? = null,
    mapContent: @Composable BoxScope.() -> Unit = { StreetMap(controller = controller, pose = pose) },
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(listOf<TravelPlace>()) }
    var searchNote by remember { mutableStateOf<String?>(null) }
    var route by remember { mutableStateOf<TravelRoute?>(null) }
    var destination by remember { mutableStateOf<TravelPlace?>(null) }
    var fix by remember { mutableStateOf<TravelFix?>(null) }
    var cameraBearingDeg by remember { mutableFloatStateOf(0f) }
    var suppressSearch by remember { mutableStateOf(false) }
    var speedShown by remember { mutableStateOf(false) }
    var routeJob by remember { mutableStateOf<Job?>(null) }

    val emptyNote = stringResource(R.string.search_empty)
    val networkNote = stringResource(R.string.search_network)
    val routeFailNote = stringResource(R.string.route_fail)

    DisposableEffect(controller) {
        controller.onFix = { incoming -> fix = incoming }
        controller.onBearing = { cameraBearingDeg = it }
        onDispose {
            if (controller.onFix != null) {
                controller.onFix = null
                controller.onBearing = null
            }
        }
    }

    val routeActive = route != null
    val hudFix = pose?.toTravelFix(providerEnabled = fix?.gpsProviderOn != false) ?: fix
    LaunchedEffect(hudFix?.speedMps, routeActive) {
        speedShown = TravelHud.shouldShowSpeed(hudFix?.speedMps, routeActive, speedShown)
    }
    val speedText = if (routeActive && speedShown) {
        hudFix?.speedMps?.let(TravelHud::formatSpeedKmh)
    } else {
        null
    }

    fun searchNear(): Pair<Double, Double>? {
        val origin = controller.originOrNull()
        val camera = controller.cameraOrNull()
        return searchBiasLatLon(
            hudFix?.latitudeDeg ?: origin?.latitudeDeg,
            hudFix?.longitudeDeg ?: origin?.longitudeDeg,
            camera?.latitudeDeg,
            camera?.longitudeDeg,
            controller.cameraZoomOrNull(),
        )
    }

    val nearKey = searchBiasKey(searchNear())
    LaunchedEffect(query, suppressSearch, nearKey) {
        if (suppressSearch) {
            suppressSearch = false
            suggestions = emptyList()
            return@LaunchedEffect
        }
        if (query.trim().length < 2) {
            suggestions = emptyList()
            searchNote = null
            return@LaunchedEffect
        }
        delay(300)
        val near = searchNear()
        val result = withContext(Dispatchers.IO) {
            search.search(query, near?.first, near?.second)
        }
        when (result) {
            is PlaceQuery.Hits -> {
                suggestions = result.places
                searchNote = null
            }
            PlaceQuery.Empty -> {
                suggestions = emptyList()
                searchNote = emptyNote
            }
            PlaceQuery.Network -> {
                suggestions = emptyList()
                searchNote = networkNote
            }
            PlaceQuery.Parse -> {
                suggestions = emptyList()
                searchNote = emptyNote
            }
        }
    }

    fun dismissKeyboard() {
        keyboard?.hide()
        focus.clearFocus()
    }

    fun applyRoute(place: TravelPlace, built: TravelRoute) {
        route = built
        destination = place
        searchNote = null
        controller.setDestination(TravelLatLng(place.latitudeDeg, place.longitudeDeg))
        controller.setRoute(built.points)
        controller.fitRoute(built.points)
        controller.followOwnVehicle()
    }

    fun flyToPlace(place: TravelPlace) {
        destination = place
        controller.setDestination(TravelLatLng(place.latitudeDeg, place.longitudeDeg))
        controller.clearRoute()
        controller.flyTo(TravelLatLng(place.latitudeDeg, place.longitudeDeg))
    }

    fun pickPlace(place: TravelPlace) {
        suppressSearch = true
        query = place.name
        suggestions = emptyList()
        dismissKeyboard()
        routeJob?.cancel()
        routeJob = scope.launch {
            val origin = routeOrigin(
                hudFix?.latitudeDeg,
                hudFix?.longitudeDeg,
                controller.originOrNull(),
            )
            if (origin == null) {
                route = null
                flyToPlace(place)
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                search.route(origin, TravelLatLng(place.latitudeDeg, place.longitudeDeg))
            }
            when (result) {
                is RouteQuery.Ok -> applyRoute(place, result.route)
                RouteQuery.Failed, RouteQuery.Network -> {
                    route = null
                    flyToPlace(place)
                    searchNote = routeFailNote
                }
            }
        }
    }

    fun submitSearch() {
        val first = suggestions.firstOrNull()
        if (first != null) {
            pickPlace(first)
            return
        }
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            return
        }
        dismissKeyboard()
        routeJob?.cancel()
        routeJob = scope.launch {
            val near = searchNear()
            val result = withContext(Dispatchers.IO) {
                search.search(trimmed, near?.first, near?.second)
            }
            when (result) {
                is PlaceQuery.Hits -> {
                    pickPlace(result.places.first())
                }
                PlaceQuery.Empty, PlaceQuery.Parse -> {
                    suggestions = emptyList()
                    searchNote = emptyNote
                }
                PlaceQuery.Network -> {
                    suggestions = emptyList()
                    searchNote = networkNote
                }
            }
        }
    }

    fun stopRoute() {
        route = null
        destination = null
        controller.clearRoute()
        controller.setDestination(null)
        controller.stopFollow()
        searchNote = null
    }

    val dockVisible = route != null && destination != null
    LaunchedEffect(dockVisible, speedShown) {
        val top = with(density) { 88.dp.roundToPx() }
        val bottom = with(density) {
            if (dockVisible) 128.dp.roundToPx() else 72.dp.roundToPx()
        }
        val side = with(density) { 16.dp.roundToPx() }
        controller.setChromePadding(side, top, side, bottom)
    }

    val bearing = ((cameraBearingDeg % 360f) + 360f) % 360f
    val showCompass = abs(bearing) > 8f && abs(bearing - 360f) > 8f

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), content = mapContent)
        TravelTopChrome(
            query = query,
            onQueryChange = { incoming ->
                query = incoming
                if (searchNote != null) {
                    searchNote = null
                }
            },
            onSubmit = { submitSearch() },
            onClear = {
                query = ""
                suggestions = emptyList()
                searchNote = null
            },
            places = suggestions,
            onPick = { pickPlace(it) },
            searchNote = searchNote,
            reduceMotion = reduceMotion,
            modifier = Modifier.align(Alignment.TopStart),
        )
        MapControls(
            showCompass = showCompass,
            compassBearingDeg = cameraBearingDeg,
            onCompassClick = { controller.resetNorth() },
            onLocateClick = { controller.locateOwnVehicle() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .then(
                    if (dockVisible) {
                        Modifier.padding(end = 16.dp, bottom = 144.dp)
                    } else {
                        Modifier
                            .navigationBarsPadding()
                            .padding(16.dp)
                    },
                ),
        )
        RouteDock(
            destinationName = destination?.name.orEmpty(),
            distanceText = route?.let { TravelHud.formatDistance(it.distanceM) }.orEmpty(),
            etaText = route?.let { TravelHud.formatEta(it.durationS) }.orEmpty(),
            speedText = if (dockVisible) speedText else null,
            onStop = { stopRoute() },
            visible = dockVisible,
            reduceMotion = reduceMotion,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Preview(name = "idle", widthDp = 412, heightDp = 915, showBackground = true, backgroundColor = 0xFFF2EFE9)
@Composable
private fun TravelMapIdlePreview() {
    DriftZeroTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            TravelTopChrome(
                query = "",
                onQueryChange = {},
                onSubmit = {},
                onClear = {},
                places = emptyList(),
                onPick = {},
                searchNote = null,
                reduceMotion = true,
            )
            MapControls(
                showCompass = false,
                compassBearingDeg = 0f,
                onCompassClick = {},
                onLocateClick = {},
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        }
    }
}

@Preview(name = "navigating", widthDp = 412, heightDp = 915, showBackground = true, backgroundColor = 0xFFF2EFE9)
@Composable
private fun TravelMapNavigatingPreview() {
    DriftZeroTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            TravelTopChrome(
                query = "Station",
                onQueryChange = {},
                onSubmit = {},
                onClear = {},
                places = emptyList(),
                onPick = {},
                searchNote = null,
                reduceMotion = true,
            )
            MapControls(
                showCompass = true,
                compassBearingDeg = 47f,
                onCompassClick = {},
                onLocateClick = {},
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 144.dp),
            )
            RouteDock(
                destinationName = "Station",
                distanceText = "12.4 km",
                etaText = "18 min",
                speedText = "34 km/h",
                onStop = {},
                visible = true,
                reduceMotion = true,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
