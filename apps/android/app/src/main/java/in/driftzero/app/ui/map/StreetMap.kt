package `in`.driftzero.app.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PointF
import android.location.Location
import android.os.SystemClock
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import `in`.driftzero.app.R
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circlePitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import java.util.concurrent.atomic.AtomicReference

private const val PUCK_SOURCE_ID = "driftzero-puck-source"
private const val PUCK_ACCURACY_LAYER_ID = "driftzero-puck-accuracy"
private const val PUCK_HALO_LAYER_ID = "driftzero-puck-halo"
private const val PUCK_FILL_LAYER_ID = "driftzero-puck-fill"

private data class PuckSnapshot(
    val latLng: LatLng,
    val bearingDeg: Float,
    val accuracyMeters: Float,
)

/**
 * MapLibre Native street map (OpenFreeMap liberty) with a high-contrast location mark.
 *
 * Prototype tile source (online liberty style until local PMTiles). Production-path puck:
 * LocationComponent is forced to the camera target before GNSS, circle layers sit above
 * every style layer, and a Compose overlay (owned by [NavigationScreen]) cannot be covered
 * by labels or HUD.
 */
@Composable
fun StreetMap(
    puckLatLng: LatLng,
    puckBearingDeg: Float,
    accuracyMeters: Float,
    onScreenPosition: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapView(context).apply {
            contentDescription = context.getString(R.string.map_content_description)
        }
    }
    val puckRef = remember {
        AtomicReference(PuckSnapshot(puckLatLng, puckBearingDeg, accuracyMeters))
    }
    puckRef.set(PuckSnapshot(puckLatLng, puckBearingDeg, accuracyMeters))

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }

    DisposableEffect(lifecycleOwner, mapView) {
        mapView.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            mapView.onStart()
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val state = lifecycleOwner.lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) {
                mapView.onPause()
            }
            if (state.isAtLeast(Lifecycle.State.STARTED)) {
                mapView.onStop()
            }
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { viewContext ->
            mapView.getMapAsync { map ->
                mapRef = map
                map.uiSettings.isAttributionEnabled = true
                map.uiSettings.isLogoEnabled = true
                map.uiSettings.isCompassEnabled = false
                val seed = puckRef.get()
                map.cameraPosition = CameraPosition.Builder()
                    .target(seed.latLng)
                    .zoom(MapDefaults.DEFAULT_ZOOM)
                    .tilt(MapDefaults.DEFAULT_TILT_DEG)
                    .build()
                map.setStyle(MapDefaults.OPEN_FREE_MAP_LIBERTY) { style ->
                    styleRef = style
                    installPuckLayers(style, seed)
                    enableLocationLayer(viewContext, map, style, toLocation(seed))
                    publishProjection(map, seed.latLng, onScreenPosition)
                }
                map.addOnCameraMoveListener {
                    publishProjection(map, puckRef.get().latLng, onScreenPosition)
                }
                map.addOnCameraIdleListener {
                    publishProjection(map, puckRef.get().latLng, onScreenPosition)
                }
            }
            mapView
        },
        modifier = modifier,
    )

    LaunchedEffect(puckLatLng, puckBearingDeg, accuracyMeters, mapRef, styleRef) {
        val map = mapRef ?: return@LaunchedEffect
        val style = styleRef ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        val seed = puckRef.get()
        updatePuckLayers(style, seed)
        forcePuck(map, toLocation(seed))
        publishProjection(map, seed.latLng, onScreenPosition)
    }
}

private fun installPuckLayers(style: Style, seed: PuckSnapshot) {
    if (style.getSource(PUCK_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(PUCK_SOURCE_ID, featureFor(seed)))
    }
    if (style.getLayer(PUCK_ACCURACY_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(PUCK_ACCURACY_LAYER_ID, PUCK_SOURCE_ID).withProperties(
                circleRadius(36f),
                circleColor("#1E6BFF"),
                circleOpacity(0.16f),
                circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_VIEWPORT),
            ),
        )
    }
    if (style.getLayer(PUCK_HALO_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(PUCK_HALO_LAYER_ID, PUCK_SOURCE_ID).withProperties(
                circleRadius(26f),
                circleColor("#FFFFFF"),
                circleStrokeColor("#0A2A6B"),
                circleStrokeWidth(3.5f),
                circleOpacity(1f),
                circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_VIEWPORT),
            ),
        )
    }
    if (style.getLayer(PUCK_FILL_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(PUCK_FILL_LAYER_ID, PUCK_SOURCE_ID).withProperties(
                circleRadius(18f),
                circleColor("#1E6BFF"),
                circleOpacity(1f),
                circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_VIEWPORT),
            ),
        )
    }
}

private fun updatePuckLayers(style: Style, seed: PuckSnapshot) {
    val source = style.getSourceAs<GeoJsonSource>(PUCK_SOURCE_ID)
    source?.setGeoJson(featureFor(seed))
}

private fun featureFor(seed: PuckSnapshot): Feature {
    return Feature.fromGeometry(
        Point.fromLngLat(seed.latLng.longitude, seed.latLng.latitude),
    )
}

@SuppressLint("MissingPermission")
private fun enableLocationLayer(
    context: Context,
    map: MapLibreMap,
    style: Style,
    seed: Location,
) {
    val topLayerId = style.layers.lastOrNull()?.id
    val optionsBuilder = LocationComponentOptions.builder(context)
        .foregroundDrawable(R.drawable.location_puck_foreground)
        .foregroundDrawableStale(R.drawable.location_puck_foreground)
        .backgroundDrawable(R.drawable.location_puck_background)
        .backgroundDrawableStale(R.drawable.location_puck_background)
        .bearingDrawable(R.drawable.location_puck_bearing)
        .gpsDrawable(R.drawable.location_puck_gps)
        .accuracyAlpha(LocationPuckStyle.ACCURACY_ALPHA)
        .accuracyColor(LocationPuckStyle.FILL_ARGB.toInt())
        .pulseEnabled(true)
        .pulseFadeEnabled(true)
        .pulseColor(LocationPuckStyle.FILL_ARGB.toInt())
        .pulseAlpha(LocationPuckStyle.PULSE_ALPHA)
        .pulseMaxRadius(LocationPuckStyle.PULSE_MAX_RADIUS_PX)
        .pulseSingleDuration(2_400f)
        .pulseInterpolator(AccelerateDecelerateInterpolator())
        .elevation(0f)
        .enableStaleState(false)
        .minZoomIconScale(LocationPuckStyle.ICON_SCALE)
        .maxZoomIconScale(LocationPuckStyle.ICON_SCALE)
    if (topLayerId != null) {
        optionsBuilder.layerAbove(topLayerId)
    }
    val activation = LocationComponentActivationOptions.builder(context, style)
        .locationComponentOptions(optionsBuilder.build())
        .useDefaultLocationEngine(hasLocationPermission(context))
        .useSpecializedLocationLayer(false)
        .locationEngineRequest(
            LocationEngineRequest.Builder(750)
                .setFastestInterval(750)
                .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                .build(),
        )
        .build()
    val component = map.locationComponent
    component.activateLocationComponent(activation)
    component.isLocationComponentEnabled = true
    component.renderMode = RenderMode.GPS
    component.cameraMode = CameraMode.NONE
    component.forceLocationUpdate(seed)
}

@SuppressLint("MissingPermission")
private fun forcePuck(map: MapLibreMap, seed: Location) {
    val component = map.locationComponent
    if (!component.isLocationComponentActivated) return
    if (!component.isLocationComponentEnabled) {
        component.isLocationComponentEnabled = true
    }
    component.forceLocationUpdate(seed)
}

private fun publishProjection(
    map: MapLibreMap,
    target: LatLng,
    onScreenPosition: (Offset) -> Unit,
) {
    val point: PointF = map.projection.toScreenLocation(target)
    onScreenPosition(Offset(point.x, point.y))
}

private fun toLocation(seed: PuckSnapshot): Location {
    return Location("camera-target").apply {
        latitude = seed.latLng.latitude
        longitude = seed.latLng.longitude
        accuracy = seed.accuracyMeters.coerceAtLeast(8f)
        bearing = seed.bearingDeg
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}
