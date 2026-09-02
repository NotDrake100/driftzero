package `in`.driftzero.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import `in`.driftzero.app.maps.AreaPackStore
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
import `in`.driftzero.app.ui.TravelMapScreen
import `in`.driftzero.app.ui.TripsScreen
import `in`.driftzero.app.ui.areaPackSideloadHint
import `in`.driftzero.app.ui.rememberSystemReduceMotion
import `in`.driftzero.core.ReplayLoadResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
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
            val navic by poses.navic.visibility.collectAsState()
            val context = LocalContext.current
            val studentLoaded = remember { MotionStudentAssets.load(context.applicationContext) != null }
            val packs = remember { AreaPackStore(File(context.applicationContext.filesDir, "area-packs")) }
            val tripStore = remember { TripStore(File(context.applicationContext.filesDir, "trips")) }
            var trips by remember { mutableStateOf(tripStore.list()) }
            var replayTrip by remember { mutableStateOf<TripSummary?>(null) }
            val activePack = remember { packs.active() }
            val packBytes = remember(activePack) { packs.bytesOnDisk(activePack) }
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
            var judgeOpen by remember { mutableStateOf(false) }
            DriftZeroTheme(night = night, reduceMotion = reduceMotion) {
                when (screen) {
                    AppScreen.MAP -> TravelMapScreen(
                        controller = map,
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
                        judgeOpen = judgeOpen,
                        rawTrail = remember(pose) {
                            poses.rawTrail().map { TravelLatLng(it.latitudeDeg, it.longitudeDeg) }
                        },
                        fusedTrail = remember(pose) {
                            poses.fusedTrail().map { TravelLatLng(it.latitudeDeg, it.longitudeDeg) }
                        },
                        modeStrip = remember(pose) { poses.modeStrip() },
                        holdElapsedS = remember(pose, held) { poses.holdElapsedS() },
                        holdDistanceM = remember(pose, held) { poses.holdDistanceM() },
                        onOpenJudge = { judgeOpen = true },
                        onCloseJudge = { judgeOpen = false },
                        onOpenTrips = { screen = AppScreen.TRIPS },
                        onOpenOffline = { screen = AppScreen.OFFLINE },
                        onOpenSettings = { screen = AppScreen.SETTINGS },
                        onOpenAbout = { screen = AppScreen.ABOUT },
                        mapContent = { StreetMap(controller = map) },
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
                        installed = packs.installed(),
                        queued = packs.queued(),
                        sideloadPath = areaPackSideloadHint(
                            File(context.applicationContext.filesDir, "area-packs").path,
                        ),
                        bytesOf = { packs.bytesOnDisk(it) },
                        onBack = { screen = AppScreen.MAP },
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
                    )
                }
            }
        }
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
