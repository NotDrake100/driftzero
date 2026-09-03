package `in`.driftzero.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import `in`.driftzero.app.maps.AreaPack
import `in`.driftzero.app.maps.AreaPackState
import `in`.driftzero.app.maps.AreaPackStore
import `in`.driftzero.app.maps.installAreaPackFromUri
import `in`.driftzero.app.pose.newestLastKnownLocation
import `in`.driftzero.app.pose.BlackoutOverlay
import `in`.driftzero.app.ui.LastFixStore
import `in`.driftzero.core.LocalRouter
import `in`.driftzero.core.OsmGraphLoader
import `in`.driftzero.core.Wgs84Bbox
import kotlin.math.max
import kotlin.math.min
import `in`.driftzero.app.pose.MotionStudentAssets
import `in`.driftzero.app.pose.rememberPoseStore
import `in`.driftzero.app.settings.MotionMode
import `in`.driftzero.app.settings.ThemeMode
import `in`.driftzero.app.settings.rememberSettingsStore
import `in`.driftzero.app.trips.TripReplay
import `in`.driftzero.app.trips.TripStore
import `in`.driftzero.app.trips.TripSummary
import `in`.driftzero.app.ui.AboutScreen
import `in`.driftzero.app.ui.AppScreen
import `in`.driftzero.app.ui.DriftZeroTheme
import `in`.driftzero.app.ui.FirstRunScreen
import `in`.driftzero.app.ui.OfflineAreasScreen
import `in`.driftzero.app.ui.SettingsScreen
import `in`.driftzero.app.ui.StreetMap
import `in`.driftzero.app.ui.StreetMapController
import `in`.driftzero.app.ui.TravelLatLng
import `in`.driftzero.app.ui.StatusCopy
import `in`.driftzero.app.ui.TravelMapScreen
import `in`.driftzero.app.ui.TripsScreen
import `in`.driftzero.app.ui.areaPackSideloadHint
import `in`.driftzero.app.ui.rememberSystemReduceMotion
import `in`.driftzero.core.ReplayLoadResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    private var pendingDest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        pendingDest = intent.getStringExtra("dest")
        MapLibre.getInstance(this)
        setContent {
            val settingsStore = rememberSettingsStore()
            val settings by settingsStore.settings.collectAsState()
            val systemNight = isSystemInDarkTheme()
            val night = when (settings.theme) {
                ThemeMode.SYSTEM -> systemNight
                ThemeMode.DAY -> false
                ThemeMode.NIGHT -> true
            }
            val reduceMotion = rememberSystemReduceMotion() || settings.motion == MotionMode.REDUCED
            LaunchedEffect(night) {
                applySystemBars(night)
            }
            val map = remember { StreetMapController() }
            val poses = rememberPoseStore()
            val pose by poses.state.collectAsState()
            val held by poses.simulateGpsOff.collectAsState()
            val lastGnss by poses.lastGnssSeenNs.collectAsState()
            val lastTrusted by poses.lastTrustedFix.collectAsState()
            val coastedM by poses.coastedDistanceM.collectAsState()
            val correction by poses.correction.collectAsState()
            val mountQuality by poses.mountQuality.collectAsState()
            val mountReason by poses.mountReason.collectAsState()
            val mountYawConfidence by poses.mountYawConfidence.collectAsState()
            val roadDecision by poses.roadDecision.collectAsState()
            val navic by poses.navic.visibility.collectAsState()
            val context = LocalContext.current
            val studentLoaded = remember { MotionStudentAssets.load(context.applicationContext) != null }
            val packs = remember { AreaPackStore(File(context.applicationContext.filesDir, "area-packs")) }
            val tripStore = remember { TripStore(File(context.applicationContext.filesDir, "trips")) }
            var trips by remember { mutableStateOf(tripStore.list()) }
            var replayTrip by remember { mutableStateOf<TripSummary?>(null) }
            var packTick by remember { mutableIntStateOf(0) }
            var importNote by remember { mutableStateOf<String?>(null) }
            var installedPacks by remember { mutableStateOf<List<AreaPack>>(emptyList()) }
            var queuedPacks by remember { mutableStateOf<List<AreaPack>>(emptyList()) }
            var activePack by remember { mutableStateOf<AreaPack?>(null) }
            var packBytes by remember { mutableStateOf<Long?>(null) }
            var packStyleJson by remember { mutableStateOf<String?>(null) }
            var localRouter by remember { mutableStateOf<LocalRouter?>(null) }
            LaunchedEffect(packTick, night) {
                val covering = withContext(Dispatchers.IO) {
                    val lastFix = LastFixStore.prefs(context).read()
                    val live = newestLastKnownLocation(context)
                    val lat = live?.latitude ?: lastFix?.latitudeDeg ?: pose?.position?.latitude?.value
                    val lon = live?.longitude ?: lastFix?.longitudeDeg ?: pose?.position?.longitude?.value
                    packs.coveringStyleJson(lat, lon, night)
                }
                packStyleJson = covering
                val snapshot = withContext(Dispatchers.IO) {
                    val installed = packs.installed()
                    val queued = packs.queued()
                    val active = installed.firstOrNull { it.state == AreaPackState.Ready }
                    val graphFile = packs.graphBinOnDisk()
                    val lastFix = LastFixStore.prefs(context).read()
                    val live = newestLastKnownLocation(context)
                    val originLat = live?.latitude ?: lastFix?.latitudeDeg
                    val originLon = live?.longitude ?: lastFix?.longitudeDeg
                    val destParts = pendingDest?.split(",")
                    val destLat = destParts?.getOrNull(0)?.trim()?.toDoubleOrNull()
                    val destLon = destParts?.getOrNull(1)?.trim()?.toDoubleOrNull()
                    val window = routeWindow(originLat, originLon, destLat, destLon)
                    val router = graphFile?.let { file ->
                        if (window == null) {
                            Log.w(LOCAL_ROUTER_TAG, "graph.bin skipped: no origin window")
                            return@let null
                        }
                        try {
                            val graph = OsmGraphLoader.load(
                                file.toPath(),
                                active?.manifest?.id?.value ?: file.parentFile?.name ?: "pack",
                                window,
                            )
                            if (graph.isEmpty()) {
                                Log.i(LOCAL_ROUTER_TAG, "graph.bin empty ${file.absolutePath}")
                                null
                            } else {
                                Log.i(
                                    LOCAL_ROUTER_TAG,
                                    "loaded ${file.name} edges=${graph.edges.size} " +
                                        "nodes=${graph.nodes.size} " +
                                        "window=${window.southLatDeg},${window.westLonDeg}," +
                                        "${window.northLatDeg},${window.eastLonDeg}",
                                )
                                Log.i(
                                    LOCAL_ROUTER_TAG,
                                    "matcher loaded ${file.name} edges=${graph.edges.size} " +
                                        "nodes=${graph.nodes.size}",
                                )
                                withContext(Dispatchers.Main.immediate) {
                                    poses.setRoadGraph(graph)
                                }
                                LocalRouter(graph)
                            }
                        } catch (error: Throwable) {
                            Log.w(LOCAL_ROUTER_TAG, "graph.bin load failed: ${error.message}")
                            null
                        }
                    }
                    PackUiSnapshot(
                        installed = installed,
                        queued = queued,
                        active = active,
                        bytes = packs.bytesOnDisk(active),
                        localRouter = router,
                    )
                }
                installedPacks = snapshot.installed
                queuedPacks = snapshot.queued
                activePack = snapshot.active
                packBytes = snapshot.bytes
                localRouter = snapshot.localRouter
            }
            LaunchedEffect(settings.recordTrips) {
                if (!settings.recordTrips) {
                    poses.attachRecorder(null)
                    tripStore.stop()
                    trips = tripStore.list()
                    return@LaunchedEffect
                }
                poses.attachRecorder(tripStore.start())
                try {
                    awaitCancellation()
                } finally {
                    poses.attachRecorder(null)
                    tripStore.stop()
                    trips = tripStore.list()
                }
            }
            LaunchedEffect(replayTrip) {
                val trip = replayTrip ?: return@LaunchedEffect
                val loaded = withContext(Dispatchers.IO) { TripReplay.loadReady(trip) }
                if (loaded is ReplayLoadResult.Ready) {
                    withContext(Dispatchers.Default) {
                        TripReplay.playInto(poses, loaded.source, trip.holds)
                    }
                }
                replayTrip = null
            }
            val matchedRoad = remember(pose?.mapMatch?.roadSegmentId, pose?.mapMatch?.status) {
                poses.matchedRoad(pose)?.map { TravelLatLng(it.latitude.value, it.longitude.value) }
            }
            var screen by remember {
                mutableStateOf(if (settings.firstRunDone) AppScreen.MAP else AppScreen.FIRST_RUN)
            }
            var destExtra by remember { mutableStateOf(pendingDest) }
            LaunchedEffect(pendingDest) {
                destExtra = pendingDest
            }
            LaunchedEffect(destExtra, localRouter, screen) {
                val raw = destExtra ?: return@LaunchedEffect
                if (screen != AppScreen.MAP || localRouter == null) {
                    return@LaunchedEffect
                }
                val parts = raw.split(",")
                if (parts.size < 2) {
                    return@LaunchedEffect
                }
                val lat = parts[0].trim().toDoubleOrNull() ?: return@LaunchedEffect
                val lon = parts[1].trim().toDoubleOrNull() ?: return@LaunchedEffect
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    return@LaunchedEffect
                }
                Log.i(LOCAL_ROUTER_TAG, "dest extra $lat,$lon")
                repeat(40) {
                    delay(250)
                    if (map.originOrNull() != null && map.onMapClick != null) {
                        map.onMapClick?.invoke(TravelLatLng(lat, lon))
                        pendingDest = null
                        destExtra = null
                        return@LaunchedEffect
                    }
                }
                Log.w(LOCAL_ROUTER_TAG, "dest extra unused: no origin or map click")
            }
            var judgeOpen by remember { mutableStateOf(false) }
            val demoScope = rememberCoroutineScope()
            var demoJob by remember { mutableStateOf<Job?>(null) }
            DriftZeroTheme(night = night, reduceMotion = reduceMotion) {
                when (screen) {
                    AppScreen.MAP -> TravelMapScreen(
                        controller = map,
                        localRouter = localRouter,
                        pose = pose,
                        gnssHeld = held,
                        onToggleHold = { poses.toggleSimulateGpsOff() },
                        matchedRoad = matchedRoad,
                        speedUnit = settings.units,
                        lastGnssSeenNs = lastGnss,
                        nowNs = pose?.timestamp?.value ?: poses.clockNowNs(),
                        studentLoaded = studentLoaded,
                        navic = navic,
                        areaPack = activePack,
                        packBytes = packBytes,
                        p95GapMs = remember(pose) { poses.p95GapMs() },
                        recording = settings.recordTrips,
                        judgeOpen = judgeOpen && settings.labUnlocked,
                        rawTrail = remember(judgeOpen, pose, settings.labUnlocked) {
                            if (!judgeOpen || !settings.labUnlocked) {
                                emptyList()
                            } else {
                                poses.rawTrail().map { TravelLatLng(it.latitudeDeg, it.longitudeDeg) }
                            }
                        },
                        fusedTrail = remember(judgeOpen, pose, settings.labUnlocked) {
                            if (!judgeOpen || !settings.labUnlocked) {
                                emptyList()
                            } else {
                                poses.fusedTrail().map { TravelLatLng(it.latitudeDeg, it.longitudeDeg) }
                            }
                        },
                        modeStrip = remember(judgeOpen, pose, settings.labUnlocked) {
                            if (!judgeOpen || !settings.labUnlocked) emptyList() else poses.modeStrip()
                        },
                        holdElapsedS = remember(pose, held) { poses.holdElapsedS() },
                        holdDistanceM = remember(pose, held) { poses.holdDistanceM() },
                        lastTrustedFix = lastTrusted,
                        coastedDistanceM = coastedM,
                        correction = correction,
                        mountQuality = mountQuality,
                        mountYawConfidence = mountYawConfidence,
                        mountReason = mountReason,
                        roadAid = StatusCopy.roadAid(roadDecision),
                        onOpenJudge = { if (settings.labUnlocked) judgeOpen = true },
                        onCloseJudge = { judgeOpen = false },
                        onOpenTrips = { screen = AppScreen.TRIPS },
                        onOpenOffline = { screen = AppScreen.OFFLINE },
                        onOpenSettings = { screen = AppScreen.SETTINGS },
                        onOpenAbout = { screen = AppScreen.ABOUT },
                        labUnlocked = settings.labUnlocked,
                        onDemoSignalLoss = {
                            demoJob?.cancel()
                            poses.setSimulateGpsOff(true)
                            demoJob = demoScope.launch {
                                delay((BlackoutOverlay.DEMO_SIGNAL_LOSS_HOLD_S * 1000.0).toLong())
                                poses.setSimulateGpsOff(false)
                            }
                        },
                        mapContent = {
                            StreetMap(
                                controller = map,
                                packStyleJson = packStyleJson,
                            )
                        },
                    )
                    AppScreen.FIRST_RUN -> FirstRunScreen(
                        speedMps = pose?.motion?.speed?.value,
                        onFinished = {
                            settingsStore.update { it.copy(firstRunDone = true) }
                            screen = AppScreen.MAP
                        },
                    )
                    AppScreen.TRIPS -> TripsScreen(
                        trips = trips,
                        onBack = { screen = AppScreen.MAP },
                        onReplay = { trip ->
                            replayTrip = trip
                            screen = AppScreen.MAP
                        },
                        onDelete = { trip ->
                            tripStore.delete(trip.id)
                            trips = tripStore.list()
                        },
                        onExport = { trip -> shareTrip(context, tripStore, trip) },
                    )
                    AppScreen.OFFLINE -> OfflineAreasScreen(
                        installed = installedPacks,
                        queued = queuedPacks,
                        sideloadPath = areaPackSideloadHint(
                            File(context.applicationContext.filesDir, "area-packs").path,
                        ),
                        bytesOf = { packs.bytesOnDisk(it) },
                        onBack = { screen = AppScreen.MAP },
                        importNote = importNote,
                        onImportZip = { uri ->
                            demoScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    installAreaPackFromUri(
                                        context.applicationContext,
                                        packs,
                                        uri,
                                        tree = false,
                                    )
                                }
                                packTick++
                                importNote = packImportNote(result?.state, result?.manifest?.id?.value)
                            }
                        },
                        onImportFolder = { uri ->
                            demoScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    installAreaPackFromUri(
                                        context.applicationContext,
                                        packs,
                                        uri,
                                        tree = true,
                                    )
                                }
                                packTick++
                                importNote = packImportNote(result?.state, result?.manifest?.id?.value)
                            }
                        },
                    )
                    AppScreen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onChange = { next -> settingsStore.update { next } },
                        onBack = { screen = AppScreen.MAP },
                        onDeleteTrips = {
                            tripStore.deleteAll()
                            trips = tripStore.list()
                        },
                    )
                    AppScreen.ABOUT -> AboutScreen(
                        appVersion = "0.1.0",
                        onBack = { screen = AppScreen.MAP },
                        labUnlocked = settings.labUnlocked,
                        onLabUnlock = { settingsStore.update { it.copy(labUnlocked = true) } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDest = intent.getStringExtra("dest")
    }

    private fun shareTrip(context: Context, store: TripStore, trip: TripSummary) {
        val zip = store.exportZip(
            trip,
            File(context.cacheDir, "trip-export/${trip.id}.zip"),
        )
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.trips", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.action_export)))
    }

    private fun applySystemBars(night: Boolean) {
        val style = if (night) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

private const val LOCAL_ROUTER_TAG = "LocalRouter"

/** Inclusive WGS84 window around origin, expanded to dest if present. Not a city. */
internal fun routeWindow(
    originLatDeg: Double?,
    originLonDeg: Double?,
    destLatDeg: Double?,
    destLonDeg: Double?,
    padDeg: Double = 0.03,
): Wgs84Bbox? {
    val lat = originLatDeg ?: destLatDeg ?: return null
    val lon = originLonDeg ?: destLonDeg ?: return null
    if (!lat.isFinite() || !lon.isFinite() || padDeg <= 0.0) {
        return null
    }
    var south = lat - padDeg
    var north = lat + padDeg
    var west = lon - padDeg
    var east = lon + padDeg
    if (destLatDeg != null && destLonDeg != null && destLatDeg.isFinite() && destLonDeg.isFinite()) {
        south = min(south, destLatDeg - 0.01)
        north = max(north, destLatDeg + 0.01)
        west = min(west, destLonDeg - 0.01)
        east = max(east, destLonDeg + 0.01)
    }
    south = south.coerceIn(-90.0, 90.0)
    north = north.coerceIn(-90.0, 90.0)
    west = west.coerceIn(-180.0, 180.0)
    east = east.coerceIn(-180.0, 180.0)
    if (south >= north || west >= east) {
        return null
    }
    return Wgs84Bbox(south, west, north, east)
}

internal fun packImportNote(state: AreaPackState?, id: String?): String = when (state) {
    AreaPackState.Ready -> "Ready ${id ?: ""}".trim()
    AreaPackState.Corrupt -> "Corrupt"
    else -> "Not installed"
}

private data class PackUiSnapshot(
    val installed: List<AreaPack>,
    val queued: List<AreaPack>,
    val active: AreaPack?,
    val bytes: Long?,
    val localRouter: LocalRouter?,
)
