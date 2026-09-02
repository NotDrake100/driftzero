package `in`.driftzero.app

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
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import `in`.driftzero.app.pose.rememberPoseStore
import `in`.driftzero.app.settings.MotionMode
import `in`.driftzero.app.settings.ThemeMode
import `in`.driftzero.app.settings.rememberSettingsStore
import `in`.driftzero.app.ui.DriftZeroTheme
import `in`.driftzero.app.ui.StreetMap
import `in`.driftzero.app.ui.StreetMapController
import `in`.driftzero.app.ui.TravelLatLng
import `in`.driftzero.app.ui.TravelMapScreen
import `in`.driftzero.app.ui.rememberSystemReduceMotion
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
            val matchedRoad = remember(pose?.mapMatch?.roadSegmentId, pose?.mapMatch?.status) {
                poses.matchedRoad(pose)?.map { TravelLatLng(it.latitude.value, it.longitude.value) }
            }
            DriftZeroTheme(night = night, reduceMotion = reduceMotion) {
                TravelMapScreen(
                    controller = map,
                    pose = pose,
                    gnssHeld = held,
                    onToggleHold = { poses.toggleSimulateGpsOff() },
                    matchedRoad = matchedRoad,
                    speedUnit = settings.units,
                    mapContent = { StreetMap(controller = map) },
                )
            }
        }
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
