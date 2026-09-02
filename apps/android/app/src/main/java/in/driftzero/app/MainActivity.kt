package `in`.driftzero.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import `in`.driftzero.app.pose.rememberPoseStore
import `in`.driftzero.app.ui.DriftZeroTheme
import `in`.driftzero.app.ui.StreetMap
import `in`.driftzero.app.ui.StreetMapController
import `in`.driftzero.app.ui.TravelMapScreen
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            val map = remember { StreetMapController() }
            val poses = rememberPoseStore()
            val pose by poses.state.collectAsState()
            DriftZeroTheme {
                TravelMapScreen(
                    controller = map,
                    pose = pose,
                    mapContent = { StreetMap(controller = map, pose = pose) },
                )
            }
        }
    }
}
