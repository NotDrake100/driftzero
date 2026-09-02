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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.driftzero.app.R
import `in`.driftzero.app.maps.AreaPack
import `in`.driftzero.app.pose.NavicSnapshot
import `in`.driftzero.app.settings.SpeedUnit
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.NavigationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** PRD 13: Hold GNSS is a stopped-vehicle control outside Judge mode. */
internal const val HOLD_MAX_SPEED_MPS = 8.0

/**
 * Travel map. The map fills the phone. Chrome is a Where to? pill, the mode
 * lamp, a locate control, and a route dock after a destination is chosen.
 * The own-vehicle mark is fed once per frame from [PuckInterpolator].
 */
@Composable
fun TravelMapScreen(
    controller: StreetMapController = remember { StreetMapController() },
    search: TravelSearchClient = remember { TravelSearchClient() },
    pose: NavigationState? = null,
    gnssHeld: Boolean = false,
    onToggleHold: () -> Unit = {},
    matchedRoad: List<TravelLatLng>? = null,
    speedUnit: SpeedUnit = SpeedUnit.KMH,
    lastGnssSeenNs: Long? = null,
    nowNs: Long = System.nanoTime(),
    studentLoaded: Boolean = false,
    navic: NavicSnapshot = NavicSnapshot.NONE,
    areaPack: AreaPack? = null,
    packBytes: Long? = null,
    p95GapMs: Double? = null,
    recording: Boolean = false,
    judgeOpen: Boolean = false,
    onOpenJudge: () -> Unit = {},
    onOpenTrips: () -> Unit = {},
    onOpenOffline: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    mapContent: @Composable BoxScope.() -> Unit = { StreetMap(controller = controller) },
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val reduceMotion = InstrumentTheme.reduceMotion
    val density = LocalDensity.current

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(listOf<TravelPlace>()) }
    var searchNote by remember { mutableStateOf<String?>(null) }
    var route by remember { mutableStateOf<TravelRoute?>(null) }
    var destination by remember { mutableStateOf<TravelPlace?>(null) }
    var locationPermission by remember { mutableStateOf(true) }
    var cameraBearingDeg by remember { mutableFloatStateOf(0f) }
    var suppressSearch by remember { mutableStateOf(false) }
    var speedShown by remember { mutableStateOf(false) }
    var sheetExpanded by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var sheetHeightPx by remember { mutableIntStateOf(0) }
    var routeJob by remember { mutableStateOf<Job?>(null) }

    val emptyNote = stringResource(R.string.search_empty)
    val networkNote = stringResource(R.string.search_network)
    val routeFailNote = stringResource(R.string.route_fail)

    DisposableEffect(controller) {
        controller.onPermission = { locationPermission = it }
        controller.onBearing = { cameraBearingDeg = it }
        onDispose {
            controller.onPermission = null
            controller.onBearing = null
        }
    }

    val lamp = lampFor(pose, locationPermission)
    val lampNow by rememberUpdatedState(lamp)
    val interpolator = remember { PuckInterpolator() }
    interpolator.reduceMotion = reduceMotion
    LaunchedEffect(pose) {
        pose?.let { interpolator.target(it, System.nanoTime()) }
    }
    LaunchedEffect(controller) {
        while (true) {
            withFrameNanos { frameNs ->
                val puck = interpolator.sample(frameNs) ?: return@withFrameNanos
                controller.applyDisplayPose(puck, lampNow, frameNs)
            }
        }
    }
    LaunchedEffect(matchedRoad) {
        controller.setMatchedRoad(matchedRoad)
    }

    val routeActive = route != null
    val speedMps = pose?.motion?.speed?.value
    LaunchedEffect(speedMps) {
        speedShown = InstrumentFormat.shouldShowSpeed(speedMps, speedShown)
    }
    val speedText = if (speedShown && speedMps != null) {
        InstrumentFormat.formatSpeed(speedMps, speedUnit)
    } else {
        null
    }
    val holdAllowed = (speedMps ?: 0.0) < HOLD_MAX_SPEED_MPS || gnssHeld

    fun searchNear(): Pair<Double, Double>? {
        val origin = controller.originOrNull()
        val camera = controller.cameraOrNull()
        return searchBiasLatLon(
            origin?.latitudeDeg,
            origin?.longitudeDeg,
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
            val origin = controller.originOrNull()
            val coasting = pose?.mode == NavigationMode.DEAD_RECKONING ||
                pose?.mode == NavigationMode.LOW_CONFIDENCE
            if (origin == null || coasting) {
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
    LaunchedEffect(sheetHeightPx) {
        val top = with(density) { 88.dp.roundToPx() }
        val side = with(density) { 16.dp.roundToPx() }
        val bottom = sheetHeightPx + with(density) { 16.dp.roundToPx() }
        controller.setChromePadding(side, top, side, bottom)
    }
    if (judgeOpen && sheetExpanded) {
        sheetExpanded = false
    }
    val radiusText = pose?.let { StatusCopy.radius95(it.uncertainty.horizontal95.value) }
    val reason = pose?.let { modeReason(it) }
    val rowGnss = stringResource(R.string.row_gnss_age)
    val rowTrusted = stringResource(R.string.row_last_trusted)
    val rowRadius = stringResource(R.string.row_radius)
    val rowHeading = stringResource(R.string.row_heading)
    val rowMatch = stringResource(R.string.row_map_match)
    val rowSensors = stringResource(R.string.row_sensors)
    val rowModel = stringResource(R.string.row_model)
    val rowNavic = stringResource(R.string.row_navic)
    val rowPack = stringResource(R.string.row_area_pack)
    val rowRate = stringResource(R.string.row_output_rate)
    val sheetRows = remember(
        pose, lastGnssSeenNs, nowNs, studentLoaded, navic, areaPack, packBytes, p95GapMs,
        rowGnss, rowTrusted, rowRadius, rowHeading, rowMatch, rowSensors, rowModel, rowNavic, rowPack, rowRate,
    ) {
        if (pose == null) {
            emptyList()
        } else {
            val rows = ArrayList<Pair<String, String>>(10)
            StatusCopy.gnssAgeS(lastGnssSeenNs, nowNs)?.let {
                rows += rowGnss to InstrumentFormat.formatSeconds(it)
            }
            rows += rowTrusted to StatusCopy.lastTrustedAgo(pose.gnssHealth.lastTrustedFixAgeS)
            rows += rowRadius to StatusCopy.radius95(pose.uncertainty.horizontal95.value)
            rows += rowHeading to StatusCopy.heading(pose.motion.heading.value, pose.uncertainty.heading95Rad)
            rows += rowMatch to StatusCopy.mapMatch(pose.mapMatch.status, pose.mapMatch.confidence)
            rows += rowSensors to StatusCopy.sensors(pose.health.flags)
            rows += rowModel to StatusCopy.model(pose.health.flags, studentLoaded)
            rows += rowNavic to StatusCopy.navic(navic)
            rows += rowPack to StatusCopy.areaPack(areaPack, packBytes)
            StatusCopy.outputRate(p95GapMs)?.let { rows += rowRate to it }
            rows
        }
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
            lamp = lamp,
            onLampClick = {},
            onLampLongPress = if (holdAllowed) onToggleHold else null,
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
            distanceText = route?.let { InstrumentFormat.formatDistance(it.distanceM) }.orEmpty(),
            etaText = route?.let { InstrumentFormat.formatEta(it.durationS) }.orEmpty(),
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
    DriftZeroTheme(night = false, reduceMotion = true) {
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
                lamp = LampDisplay(LampTone.OK, LampWord.GNSS, 0.4, dashed = false, coasting = false),
                onLampClick = {},
                onLampLongPress = null,
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

@Preview(name = "night", widthDp = 412, heightDp = 915, showBackground = true, backgroundColor = 0xFF0B0D0A)
@Composable
private fun TravelMapNavigatingPreview() {
    DriftZeroTheme(night = true, reduceMotion = true) {
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
                lamp = LampDisplay(LampTone.CAUTION, LampWord.DEAD_RECKONING, 14.0, dashed = true, coasting = true),
                onLampClick = {},
                onLampLongPress = null,
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
