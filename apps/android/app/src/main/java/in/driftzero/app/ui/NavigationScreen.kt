package `in`.driftzero.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.offset
import `in`.driftzero.app.ui.hud.NavigationHud
import `in`.driftzero.app.ui.map.LocationPuck
import `in`.driftzero.app.ui.map.LocationPuckStyle
import `in`.driftzero.app.ui.map.MapDefaults
import `in`.driftzero.app.ui.map.StreetMap
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

/**
 * Default navigation surface: MapLibre street map, unmissable puck, edge HUD.
 */
@Composable
fun NavigationScreen() {
    val puckLatLng = remember {
        LatLng(MapDefaults.KOREGAON_PARK_LATITUDE_DEG, MapDefaults.KOREGAON_PARK_LONGITUDE_DEG)
    }
    val bearingDeg = MapDefaults.DEFAULT_BEARING_DEG
    var projected by remember { mutableStateOf<Offset?>(null) }
    var viewport by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val boxPx = with(density) { LocationPuckStyle.boxSize.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = Offset(it.width.toFloat(), it.height.toFloat()) },
    ) {
        StreetMap(
            puckLatLng = puckLatLng,
            puckBearingDeg = bearingDeg,
            accuracyMeters = MapDefaults.DEFAULT_ACCURACY_M,
            onScreenPosition = { projected = it },
            modifier = Modifier.fillMaxSize(),
        )

        val screen = projected ?: Offset(
            x = viewport.x / 2f,
            y = viewport.y / 2f,
        )
        LocationPuck(
            bearingDeg = bearingDeg,
            modifier = Modifier
                .zIndex(1f)
                .offset {
                    IntOffset(
                        x = (screen.x - boxPx / 2f).roundToInt(),
                        y = (screen.y - boxPx / 2f).roundToInt(),
                    )
                },
        )

        NavigationHud(
            modeLabel = "GNSS",
            speedLabel = "—",
            confidenceLabel = "High",
        )
    }
}
