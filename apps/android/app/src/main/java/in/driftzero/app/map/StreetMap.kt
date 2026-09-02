package `in`.driftzero.app.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
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
import `in`.driftzero.app.geo.GeoPoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val PUCK_BLUE = 0xFF1A73E8.toInt()
private const val ROUTE_SOURCE = "driftzero-route"
private const val DEST_SOURCE = "driftzero-dest"
private const val FALLBACK_SOURCE = "driftzero-fallback"
private const val ROUTE_CASE = "driftzero-route-case"
private const val ROUTE_LINE = "driftzero-route-line"
private const val DEST_LAYER = "driftzero-dest-layer"
private const val FALLBACK_LAYER = "driftzero-fallback-layer"

/**
 * Full-bleed MapLibre streets. OpenFreeMap liberty style, Koregaon Park camera,
 * and the SDK LocationComponent blue you-are-here puck when location is granted.
 *
 * Product routing draws on this same MapView. Do not add a second map.
 * Production-path prototype: tiles come from OpenFreeMap until a local PMTiles
 * package is installed (ADR 004). Offline graph matching is not this surface.
 */
@Composable
fun StreetMap(
    locationPermissionGranted: Boolean,
    modifier: Modifier = Modifier,
    routePoints: List<GeoPoint> = emptyList(),
    destination: GeoPoint? = null,
    fallbackOrigin: GeoPoint? = null,
    showFallbackOrigin: Boolean = false,
    cameraEpoch: Int = 0,
    followUser: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapView(context)
    }
    val overlay = remember { RouteOverlayState() }

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
                    overlay.map = map
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
                        ensureRouteLayers(style)
                        overlay.style = style
                        overlay.apply(
                            routePoints = routePoints,
                            destination = destination,
                            fallbackOrigin = fallbackOrigin,
                            showFallbackOrigin = showFallbackOrigin,
                            cameraEpoch = cameraEpoch,
                            followUser = followUser,
                        )
                        if (locationPermissionGranted || hasLocationPermission(viewContext)) {
                            enableBluePuck(viewContext, map, style, followUser)
                        }
                    }
                }
            }
        },
        update = { view ->
            overlay.apply(
                routePoints = routePoints,
                destination = destination,
                fallbackOrigin = fallbackOrigin,
                showFallbackOrigin = showFallbackOrigin,
                cameraEpoch = cameraEpoch,
                followUser = followUser,
            )
            if (locationPermissionGranted) {
                view.getMapAsync { map ->
                    val style = map.style
                    if (style != null && style.isFullyLoaded) {
                        enableBluePuck(view.context, map, style, followUser)
                    }
                }
            }
        },
        modifier = modifier,
    )
}

private class RouteOverlayState {
    var map: MapLibreMap? = null
    var style: Style? = null
    private var lastEpoch = -1

    fun apply(
        routePoints: List<GeoPoint>,
        destination: GeoPoint?,
        fallbackOrigin: GeoPoint?,
        showFallbackOrigin: Boolean,
        cameraEpoch: Int,
        followUser: Boolean,
    ) {
        val loaded = style ?: return
        val mapLibreMap = map ?: return
        (loaded.getSource(ROUTE_SOURCE) as? GeoJsonSource)?.setGeoJson(routePoints.toLineCollection())
        (loaded.getSource(DEST_SOURCE) as? GeoJsonSource)?.setGeoJson(destination.toPointCollection())
        val fallbackCollection = if (showFallbackOrigin) fallbackOrigin.toPointCollection() else emptyCollection()
        (loaded.getSource(FALLBACK_SOURCE) as? GeoJsonSource)?.setGeoJson(fallbackCollection)
        if (cameraEpoch != lastEpoch) {
            lastEpoch = cameraEpoch
            moveCamera(mapLibreMap, routePoints, destination, fallbackOrigin, followUser)
        }
    }
}

private fun moveCamera(
    map: MapLibreMap,
    routePoints: List<GeoPoint>,
    destination: GeoPoint?,
    fallbackOrigin: GeoPoint?,
    followUser: Boolean,
) {
    if (followUser && routePoints.size < 2) return
    if (routePoints.size >= 2) {
        val bounds = LatLngBounds.Builder().also { builder ->
            routePoints.forEach { builder.include(it.toLatLng()) }
            destination?.let { builder.include(it.toLatLng()) }
            fallbackOrigin?.let { builder.include(it.toLatLng()) }
        }.build()
        map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 140), 700)
        return
    }
    val target = destination ?: fallbackOrigin ?: return
    map.easeCamera(
        CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(target.toLatLng()).zoom(14.2).build(),
        ),
        600,
    )
}

private fun ensureRouteLayers(style: Style) {
    if (style.getSource(ROUTE_SOURCE) != null) return
    style.addSource(GeoJsonSource(ROUTE_SOURCE, emptyCollection()))
    style.addSource(GeoJsonSource(DEST_SOURCE, emptyCollection()))
    style.addSource(GeoJsonSource(FALLBACK_SOURCE, emptyCollection()))
    style.addLayer(
        LineLayer(ROUTE_CASE, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineWidth(10f),
            PropertyFactory.lineColor(Color.parseColor("#0B3D91")),
        ),
    )
    style.addLayer(
        LineLayer(ROUTE_LINE, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineColor(Color.parseColor("#1A73E8")),
        ),
    )
    style.addLayer(
        CircleLayer(DEST_LAYER, DEST_SOURCE).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(Color.parseColor("#C62828")),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    style.addLayer(
        CircleLayer(FALLBACK_LAYER, FALLBACK_SOURCE).withProperties(
            PropertyFactory.circleRadius(6.5f),
            PropertyFactory.circleColor(Color.parseColor("#1A73E8")),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
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
    followUser: Boolean,
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
    locationComponent.cameraMode = if (followUser) CameraMode.TRACKING else CameraMode.NONE
}

private fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}

private fun emptyCollection(): FeatureCollection = FeatureCollection.fromFeatures(emptyArray())

private fun List<GeoPoint>.toLineCollection(): FeatureCollection {
    if (size < 2) return emptyCollection()
    val line = LineString.fromLngLats(map { Point.fromLngLat(it.longitudeDeg, it.latitudeDeg) })
    return FeatureCollection.fromFeature(Feature.fromGeometry(line))
}

private fun GeoPoint?.toPointCollection(): FeatureCollection {
    if (this == null) return emptyCollection()
    val point = Point.fromLngLat(longitudeDeg, latitudeDeg)
    return FeatureCollection.fromFeature(Feature.fromGeometry(point))
}

private fun GeoPoint.toLatLng(): LatLng = LatLng(latitudeDeg, longitudeDeg)
