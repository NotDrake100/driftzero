package `in`.driftzero.app.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

private const val PUCK_BLUE = 0xFF1A73E8.toInt()

/**
 * Full-bleed MapLibre streets. OpenFreeMap liberty style, Koregaon Park camera,
 * and the SDK LocationComponent blue you-are-here puck when location is granted.
 *
 * Production-path prototype: tiles come from OpenFreeMap until a local PMTiles
 * package is installed (ADR 004). Offline graph matching is not this surface.
 */
@Composable
fun StreetMap(
    locationPermissionGranted: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapView(context)
    }

    DisposableEffect(lifecycleOwner, mapView) {
        mapView.onCreate(Bundle())
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
            runCatching {
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    AndroidView(
        factory = { viewContext ->
            mapView.apply {
                getMapAsync { map ->
                    configureMapChrome(map)
                    map.cameraPosition = CameraPosition.Builder()
                        .target(
                            LatLng(
                                MapDefaults.DEFAULT_LATITUDE_DEG,
                                MapDefaults.DEFAULT_LONGITUDE_DEG,
                            ),
                        )
                        .zoom(MapDefaults.DEFAULT_ZOOM)
                        .build()
                    map.setStyle(Style.Builder().fromUri(MapDefaults.LIBERTY_STYLE_URI)) { style ->
                        if (locationPermissionGranted || hasLocationPermission(viewContext)) {
                            enableBluePuck(viewContext, map, style)
                        }
                    }
                }
            }
        },
        update = { view ->
            if (locationPermissionGranted) {
                view.getMapAsync { map ->
                    val style = map.style
                    if (style != null && style.isFullyLoaded) {
                        enableBluePuck(view.context, map, style)
                    }
                }
            }
        },
        modifier = modifier,
    )
}

private fun configureMapChrome(map: MapLibreMap) {
    val settings = map.uiSettings
    settings.isCompassEnabled = false
    settings.isAttributionEnabled = true
    settings.isLogoEnabled = true
    settings.setAttributionMargins(12, 0, 0, 96)
}

@SuppressLint("MissingPermission")
private fun enableBluePuck(
    context: Context,
    map: MapLibreMap,
    style: Style,
) {
    if (!hasLocationPermission(context)) return
    val locationComponent = map.locationComponent
    if (!locationComponent.isLocationComponentActivated) {
        val options = LocationComponentOptions.builder(context)
            .pulseEnabled(true)
            .pulseColor(PUCK_BLUE)
            .foregroundTintColor(PUCK_BLUE)
            .accuracyColor(PUCK_BLUE)
            .accuracyAlpha(0.18f)
            .build()
        val activation = LocationComponentActivationOptions.builder(context, style)
            .locationComponentOptions(options)
            .useDefaultLocationEngine(true)
            .locationEngineRequest(
                LocationEngineRequest.Builder(1_000)
                    .setFastestInterval(1_000)
                    .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                    .build(),
            )
            .build()
        locationComponent.activateLocationComponent(activation)
    }
    locationComponent.isLocationComponentEnabled = true
    locationComponent.renderMode = RenderMode.COMPASS
    locationComponent.cameraMode = CameraMode.TRACKING
}

private fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}
