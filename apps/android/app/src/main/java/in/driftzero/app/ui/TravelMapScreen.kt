package `in`.driftzero.app.ui

import android.util.Log
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.driftzero.app.R
import `in`.driftzero.app.maps.AreaPack
import `in`.driftzero.app.pose.BlackoutOverlay
import `in`.driftzero.app.pose.CorrectionStroke
import `in`.driftzero.app.pose.NavicSnapshot
import `in`.driftzero.app.pose.PosePoint
import `in`.driftzero.app.pose.readLocationGrant
import `in`.driftzero.app.settings.SpeedUnit
import `in`.driftzero.core.LocalRouter
import `in`.driftzero.core.GuidanceRoute
import `in`.driftzero.core.GuidanceState
import `in`.driftzero.core.MountQuality
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.NavigationState
import `in`.driftzero.core.RouteGuidance
import `in`.driftzero.core.VoiceCueScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** PRD 13: Hold GNSS is a stopped-vehicle control outside Judge mode. */
internal const val HOLD_MAX_SPEED_MPS = 8.0

@Composable
internal fun rememberTravelSearchClient(
    localRouter: LocalRouter? = null,
): TravelSearchClient {
    val context = LocalContext.current.applicationContext
    return remember(context, localRouter) {
        TravelSearchClient(
            online = { networkReachable(context) },
            localRoute = localRouter?.let { router ->
                { from, to ->
                    val started = System.nanoTime()
                    val built = router.route(
                        from.latitudeDeg,
                        from.longitudeDeg,
                        to.latitudeDeg,
                        to.longitudeDeg,
                    )
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                    Log.i(
                        "LocalRouter",
                        if (built == null) {
                            "no route elapsedMs=$elapsedMs"
                        } else {
                            "route ${built.totalDistanceM}m ${built.totalDurationS}s " +
                                "points=${built.points.size} elapsedMs=$elapsedMs"
                        },
                    )
                    built
                }
            },
        )
    }
}

/**
 * Travel map. The map fills the phone. Chrome is a Where to? pill, the mode
 * lamp, a locate control, and a route dock after a destination is chosen.
 * The own-vehicle mark is fed once per frame from [PuckInterpolator].
 */
@Composable
fun TravelMapScreen(
    controller: StreetMapController = remember { StreetMapController() },
    localRouter: LocalRouter? = null,
    search: TravelSearchClient = rememberTravelSearchClient(localRouter),
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
    rawTrail: List<TravelLatLng> = emptyList(),
    fusedTrail: List<TravelLatLng> = emptyList(),
    modeStrip: List<NavigationMode> = emptyList(),
    holdElapsedS: Double? = null,
    holdDistanceM: Double? = null,
    lastTrustedFix: PosePoint? = null,
    coastedDistanceM: Double = 0.0,
    correction: CorrectionStroke? = null,
    mountQuality: MountQuality? = null,
    mountYawConfidence: Double? = null,
    mountReason: String? = null,
    roadAid: StatusCopy.RoadAidState? = null,
    onOpenJudge: () -> Unit = {},
    onCloseJudge: () -> Unit = {},
    onOpenTrips: () -> Unit = {},
    onOpenOffline: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onDemoSignalLoss: (() -> Unit)? = null,
    labUnlocked: Boolean = false,
    mapContent: @Composable BoxScope.() -> Unit = { StreetMap(controller = controller) },
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val reduceMotion = InstrumentTheme.reduceMotion
    val density = LocalDensity.current

    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(listOf<TravelPlace>()) }
    var searchNote by remember { mutableStateOf<String?>(null) }
    var route by remember { mutableStateOf<TravelRoute?>(null) }
    var destination by remember { mutableStateOf<TravelPlace?>(null) }
    var locationGrant by remember { mutableStateOf(readLocationGrant(context)) }
    var cameraBearingDeg by remember { mutableFloatStateOf(0f) }
    var suppressSearch by remember { mutableStateOf(false) }
    var speedShown by remember { mutableStateOf(false) }
    var sheetExpanded by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var sheetHeightPx by remember { mutableIntStateOf(0) }
    var routeJob by remember { mutableStateOf<Job?>(null) }
    var searchExpanded by remember { mutableStateOf(true) }
    var tracker by remember { mutableStateOf<RouteGuidance?>(null) }
    var cues by remember { mutableStateOf<VoiceCueScheduler?>(null) }
    var guidanceRoute by remember { mutableStateOf<GuidanceRoute?>(null) }
    var guidanceState by remember { mutableStateOf<GuidanceState?>(null) }
    var lastRerouteNs by remember { mutableStateOf<Long?>(null) }
    var topChromePx by remember { mutableIntStateOf(0) }
    val voice = remember(context) { GuidanceVoice(context.applicationContext) }
    DisposableEffect(voice) {
        onDispose { voice.close() }
    }

    val emptyNote = stringResource(R.string.search_empty)
    val networkNote = stringResource(R.string.search_network)
    val tapDestNote = stringResource(R.string.search_tap_dest)
    val routeFailNote = stringResource(R.string.route_fail)
    val routeNetworkNote = stringResource(R.string.route_network)
    val arrivedNote = stringResource(R.string.guidance_arrived)
    val reroutingNote = stringResource(R.string.guidance_rerouting)
    val offRouteNote = stringResource(R.string.guidance_off_route)

    DisposableEffect(controller) {
        controller.onLocationGrant = { locationGrant = it }
        controller.onBearing = { cameraBearingDeg = it }
        onDispose {
            controller.onLocationGrant = null
            controller.onBearing = null
        }
    }

    val lamp = lampFor(pose, locationGrant)
    val lampNow by rememberUpdatedState(lamp)
    val interpolator = remember { PuckInterpolator() }
    interpolator.reduceMotion = reduceMotion
    var routeFromLocal by remember { mutableStateOf<Boolean?>(null) }
    fun bindRoute(place: TravelPlace, built: TravelRoute, fromLocal: Boolean) {
        val guided = built.toGuidance()
        route = built
        destination = place
        tracker = RouteGuidance(guided)
        cues = VoiceCueScheduler()
        guidanceRoute = guided
        guidanceState = null
        lastRerouteNs = pose?.timestamp?.value
        searchNote = null
        routeFromLocal = fromLocal
        controller.setDestination(TravelLatLng(place.latitudeDeg, place.longitudeDeg))
        controller.setRoute(built.points)
        controller.fitRoute(built.points)
        controller.setNavigating(true)
        controller.followOwnVehicle()
        searchExpanded = false
    }

    val trackerNow by rememberUpdatedState(tracker)
    val cuesNow by rememberUpdatedState(cues)
    val guidanceRouteNow by rememberUpdatedState(guidanceRoute)
    val destNow by rememberUpdatedState(destination)
    val poseNow by rememberUpdatedState(pose)
    val guidanceForPuck by rememberUpdatedState(guidanceState)
    var lastRemaining by remember { mutableStateOf<List<TravelLatLng>>(emptyList()) }
    LaunchedEffect(pose?.sequence, pose?.mode) {
        val current = pose ?: return@LaunchedEffect
        val active = trackerNow
        val built = guidanceRouteNow
        if (active != null && built != null) {
            val next = active.update(
                latitudeDeg = current.position.latitude.value,
                longitudeDeg = current.position.longitude.value,
                headingRad = current.motion.heading.value,
                speedMps = current.motion.speed.value,
                timestampNs = current.timestamp.value,
            )
            guidanceState = next
            val remaining = active.remainingPoints().map { TravelLatLng(it.latitudeDeg, it.longitudeDeg) }
            if (remaining != lastRemaining) {
                lastRemaining = remaining
                controller.setRoute(remaining)
            }
            val spoken = cuesNow?.onGuidance(next, built, current.motion.speed.value)
            if (spoken != null) {
                voice.speak(spoken.text)
            }
            val dest = destNow
            if (dest != null && GuidanceNav.shouldReroute(next, current.mode, lastRerouteNs, current.timestamp.value)) {
                lastRerouteNs = current.timestamp.value
                voice.speak(cuesNow?.onReroute()?.text ?: reroutingNote)
                routeJob?.cancel()
                routeJob = scope.launch {
                    val origin = TravelLatLng(
                        current.position.latitude.value,
                        current.position.longitude.value,
                    )
                    val result = withContext(Dispatchers.IO) {
                        search.route(origin, TravelLatLng(dest.latitudeDeg, dest.longitudeDeg))
                    }
                    when (result) {
                        is RouteQuery.Ok -> bindRoute(dest, result.route, result.fromLocal)
                        RouteQuery.Network -> searchNote = routeNetworkNote
                        RouteQuery.Failed -> searchNote = routeFailNote
                    }
                }
            }
        } else {
            guidanceState = null
        }
    }
    LaunchedEffect(controller) {
        var lastSeq = Long.MIN_VALUE
        var lastUiNs = 0L
        while (true) {
            withFrameNanos { frameNs ->
                val current = poseNow
                if (current != null && current.sequence != lastSeq) {
                    lastSeq = current.sequence
                    interpolator.target(current, frameNs, guidanceForPuck)
                }
                if (!PuckInterpolator.shouldApplyDisplayPose(lastUiNs, frameNs)) {
                    return@withFrameNanos
                }
                lastUiNs = frameNs
                val puck = interpolator.sample(frameNs) ?: return@withFrameNanos
                controller.applyDisplayPose(puck, lampNow, frameNs)
            }
        }
    }
    LaunchedEffect(matchedRoad) {
        controller.setMatchedRoad(matchedRoad)
    }
    LaunchedEffect(judgeOpen, rawTrail, fusedTrail) {
        if (judgeOpen) {
            controller.setRawTrail(rawTrail)
            controller.setFusedTrail(fusedTrail)
        } else {
            controller.setRawTrail(emptyList())
            controller.setFusedTrail(emptyList())
        }
    }
    val outage = pose?.mode?.let { BlackoutOverlay.isOutageMode(it) } == true
    LaunchedEffect(pose?.mode, lastTrustedFix, correction, nowNs) {
        val mode = pose?.mode
        val ghost = if (mode != null && BlackoutOverlay.ghostVisible(mode, lastTrustedFix)) {
            lastTrustedFix?.let { TravelLatLng(it.latitudeDeg, it.longitudeDeg) }
        } else {
            null
        }
        val line = correction?.takeIf { BlackoutOverlay.correctionVisible(it.startedNs, nowNs) }?.let {
            listOf(
                TravelLatLng(it.from.latitudeDeg, it.from.longitudeDeg),
                TravelLatLng(it.to.latitudeDeg, it.to.longitudeDeg),
            )
        }
        controller.setBlackoutMarks(ghost, line)
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
    val holdAllowed = pose != null && ((speedMps ?: 0.0) < HOLD_MAX_SPEED_MPS || gnssHeld)

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

    val routerNow by rememberUpdatedState(localRouter)
    LaunchedEffect(query, suppressSearch) {
        if (suppressSearch) {
            suppressSearch = false
            suggestions = emptyList()
            return@LaunchedEffect
        }
        if (query.trim().length < 2) {
            suggestions = emptyList()
            searchNote = if (routerNow != null && destNow == null) tapDestNote else null
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
                searchNote = if (routerNow != null) tapDestNote else networkNote
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

    fun applyRoute(place: TravelPlace, built: TravelRoute, fromLocal: Boolean) {
        bindRoute(place, built, fromLocal)
    }

    fun flyToPlace(place: TravelPlace) {
        destination = place
        tracker = null
        cues = null
        guidanceRoute = null
        guidanceState = null
        lastRerouteNs = null
        routeFromLocal = null
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
            if (origin == null) {
                route = null
                flyToPlace(place)
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                search.route(origin, TravelLatLng(place.latitudeDeg, place.longitudeDeg))
            }
            when (result) {
                is RouteQuery.Ok -> applyRoute(place, result.route, result.fromLocal)
                RouteQuery.Network -> {
                    route = null
                    flyToPlace(place)
                    searchNote = routeNetworkNote
                }
                RouteQuery.Failed -> {
                    route = null
                    flyToPlace(place)
                    searchNote = routeFailNote
                }
            }
        }
    }

    fun pickMapPin(point: TravelLatLng) {
        val placeholder = TravelPlace(
            name = SearchNotes.destTitle(),
            detail = "",
            latitudeDeg = point.latitudeDeg,
            longitudeDeg = point.longitudeDeg,
        )
        pickPlace(placeholder)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                search.reverse(point.latitudeDeg, point.longitudeDeg)
            }
            val named = (result as? PlaceQuery.Hits)?.places?.firstOrNull() ?: return@launch
            val current = destination
            if (current != null &&
                abs(current.latitudeDeg - point.latitudeDeg) < 1e-7 &&
                abs(current.longitudeDeg - point.longitudeDeg) < 1e-7
            ) {
                destination = current.copy(
                    name = SearchNotes.destTitle(reverseName = named.name),
                    detail = named.detail,
                )
            }
        }
    }

    DisposableEffect(controller, localRouter) {
        controller.onMapClick = { point -> pickMapPin(point) }
        onDispose {
            controller.onMapClick = null
        }
    }

    fun submitSearch() {
        val first = suggestions.firstOrNull()
        if (first != null) {
            pickPlace(first.copy(name = SearchNotes.destTitle(searchName = first.name)))
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
                    pickPlace(
                        result.places.first().let { hit ->
                            hit.copy(name = SearchNotes.destTitle(searchName = hit.name))
                        },
                    )
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
        tracker = null
        cues = null
        guidanceRoute = null
        guidanceState = null
        lastRerouteNs = null
        controller.setNavigating(false)
        controller.clearRoute()
        controller.setDestination(null)
        controller.stopFollow()
        searchNote = null
        searchExpanded = true
        routeFromLocal = null
    }

    val dockVisible = route != null && destination != null
    val banner = remember(guidanceState, guidanceRoute) {
        val state = guidanceState
        val built = guidanceRoute
        if (state != null && built != null) GuidanceNav.banner(state, built) else null
    }
    val remainingSummary = when (val state = guidanceState) {
        is GuidanceState.OnRoute ->
            "${InstrumentFormat.formatDistance(state.remainingM)}, ${InstrumentFormat.formatEta(state.remainingS)}"
        is GuidanceState.Arrived -> arrivedNote
        is GuidanceState.OffRoute -> offRouteNote
        null -> if (dockVisible && route != null) {
            "${InstrumentFormat.formatDistance(route!!.distanceM)}, ${InstrumentFormat.formatEta(route!!.durationS)}"
        } else {
            null
        }
    }
    LaunchedEffect(sheetHeightPx, topChromePx) {
        val fallbackTop = with(density) { 88.dp.roundToPx() }
        val top = if (topChromePx > 0) topChromePx + with(density) { 8.dp.roundToPx() } else fallbackTop
        val side = with(density) { 16.dp.roundToPx() }
        val bottom = sheetHeightPx + with(density) { 16.dp.roundToPx() }
        controller.setChromePadding(side, top, side, bottom)
    }
    LaunchedEffect(judgeOpen) {
        if (judgeOpen) {
            sheetExpanded = false
        }
    }
    val radiusText = pose?.let { StatusCopy.radius95(it.uncertainty.horizontal95.value) }
    val stripSpeed = if (outage) null else speedText
    val reason = pose?.let { modeReason(it) }
    val locationReason = StatusCopy.noLocationReason(locationGrant, pose != null)
    val lampReason = if (lamp.word == LampWord.PRECISE_OFF) {
        null
    } else {
        locationReason ?: pose?.let { StatusCopy.reasonLine(reason, mountReason, it.health.flags, lab = labUnlocked) }
    }
    val rowRouting = stringResource(R.string.row_routing)
    val networkUp = networkReachable(context)
    val sheetRows = remember(
        pose, lastGnssSeenNs, nowNs, studentLoaded, navic, areaPack, packBytes, p95GapMs,
        mountQuality, mountYawConfidence, mountReason, roadAid, rowRouting, networkUp, localRouter,
        locationGrant, locationReason, labUnlocked, stripSpeed, routeFromLocal,
    ) {
        if (pose == null) {
            if (locationReason != null) listOf("Reason" to locationReason) else emptyList()
        } else {
            val rows = StatusCopy.of(
                state = pose,
                lastGnssSeenNs = lastGnssSeenNs,
                nowNs = nowNs,
                studentLoaded = studentLoaded,
                navic = navic,
                pack = areaPack,
                packBytes = packBytes,
                p95Ms = p95GapMs,
                mountQuality = mountQuality,
                mountYawConfidence = mountYawConfidence,
                mountReason = mountReason,
                roadAid = roadAid,
                lab = labUnlocked,
                speedText = stripSpeed,
            ).toMutableList()
            StatusCopy.routingStatus(
                network = networkUp,
                localRouter = localRouter != null,
                packReady = areaPack != null,
            )?.let { rows += rowRouting to it }
            StatusCopy.routeSource(routeFromLocal, localRouter != null)?.let { rows += rowRouting to it }
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
            onPick = { pickPlace(it.copy(name = SearchNotes.destTitle(searchName = it.name))) },
            searchNote = searchNote,
            reduceMotion = reduceMotion,
            searchCollapsed = routeActive && !searchExpanded,
            onExpandSearch = {
                searchExpanded = true
                query = ""
                suggestions = emptyList()
            },
            lamp = lamp,
            onLampClick = { sheetExpanded = !sheetExpanded },
            onLampLongPress = if (holdAllowed) onToggleHold else null,
            lampReason = lampReason,
            banner = banner,
            gnssHeld = gnssHeld,
            onOpenTrips = onOpenTrips,
            onOpenOffline = onOpenOffline,
            onOpenSettings = onOpenSettings,
            onOpenAbout = onOpenAbout,
            modifier = Modifier
                .align(Alignment.TopStart)
                .onSizeChanged { topChromePx = it.height },
        )
        MapControls(
            showCompass = showCompass,
            compassBearingDeg = cameraBearingDeg,
            onCompassClick = { controller.resetNorth() },
            onLocateClick = { controller.locateOwnVehicle() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = (sheetHeightPx / density.density + 16f).dp),
        )
        if (judgeOpen && labUnlocked) {
            JudgeOverlay(
                held = gnssHeld,
                holdElapsedS = holdElapsedS,
                holdDistanceM = holdDistanceM,
                p95GapMs = p95GapMs,
                modes = modeStrip,
                onToggleHold = onToggleHold,
                onClose = onCloseJudge,
                onDemoSignalLoss = onDemoSignalLoss,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (confirmStop) {
            ConfirmPanel(
                title = stringResource(R.string.stop_route_title),
                confirmLabel = stringResource(R.string.action_stop),
                cancelLabel = stringResource(R.string.action_cancel),
                onConfirm = {
                    confirmStop = false
                    stopRoute()
                },
                onCancel = { confirmStop = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        } else if (!judgeOpen || !labUnlocked) {
            BottomInstrument(
                lamp = lamp,
                speedText = stripSpeed,
                radiusText = radiusText,
                reason = null,
                rows = sheetRows,
                routeName = if (dockVisible) destination?.name else null,
                routeSummary = if (dockVisible) remainingSummary else null,
                recording = recording,
                expanded = sheetExpanded,
                onToggle = { sheetExpanded = !sheetExpanded },
                onOpenJudge = if (labUnlocked) onOpenJudge else null,
                onStopRoute = if (dockVisible) ({ confirmStop = true }) else null,
                onDemoSignalLoss = onDemoSignalLoss,
                showLabTools = labUnlocked,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { sheetHeightPx = it.height },
            )
        }
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
            BottomInstrument(
                lamp = LampDisplay(LampTone.CAUTION, LampWord.DEAD_RECKONING, 14.0, dashed = true, coasting = true),
                speedText = "34 km/h",
                radiusText = "12 m 95%",
                reason = ModeReason.NoFix(14.0),
                rows = emptyList(),
                routeName = "Station",
                routeSummary = "12.4 km, 18 min",
                recording = false,
                expanded = false,
                onToggle = {},
                onOpenJudge = {},
                onStopRoute = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
