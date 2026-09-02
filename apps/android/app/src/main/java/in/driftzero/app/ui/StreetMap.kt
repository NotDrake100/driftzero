package `in`.driftzero.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import `in`.driftzero.app.R
import `in`.driftzero.app.maps.AreaPack
import `in`.driftzero.app.maps.AreaPackStore
import `in`.driftzero.app.maps.GeoBbox
import `in`.driftzero.app.pose.hasLocationPermission
import `in`.driftzero.app.pose.newestLastKnownLocation
import `in`.driftzero.core.NavigationMode
import `in`.driftzero.core.NavigationState
import `in`.driftzero.core.puckHeadingRad
import `in`.driftzero.core.puckLatitudeDeg
import `in`.driftzero.core.puckLongitudeDeg
import java.io.File
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * MapLibre Native street map in an [AndroidView].
 *
 * MapView defaults to a SurfaceView. Compose cannot punch a hole through an
 * opaque parent, so that surface paints behind the window and the map looks
 * gone. [StreetMapConfig.TEXTURE_MODE] puts the map on a TextureView so it
 * composites in the view tree. The view background stays transparent. A black
 * theme background on MapView hides tiles even when they load.
 *
 * MapLibre 13 `android-sdk` is Vulkan. On the emulator that guest VkInstance
 * (engine maplibre-native) then kills host qemu with SIGSEGV 139, host GPU or
 * SwiftShader. The app depends on `android-sdk-opengl` so the guest stays on
 * OpenGL ES.
 */
class StreetMapController {
    internal var session: StreetMapSession? = null
    var onFix: ((TravelFix) -> Unit)? = null
    var onBearing: ((Float) -> Unit)? = null
    var onPermission: ((Boolean) -> Unit)? = null

    fun locateOwnVehicle() {
        session?.recenterOnPuck()
    }

    fun followOwnVehicle() {
        session?.followPuck()
    }

    fun stopFollow() {
        session?.stopFollow()
    }

    fun resetNorth() {
        session?.resetNorth()
    }

    fun originOrNull(): TravelLatLng? = session?.originOrNull()

    fun cameraOrNull(): TravelLatLng? = session?.cameraOrNull()

    fun cameraZoomOrNull(): Double? = session?.cameraZoomOrNull()

    fun queueVisibleRegion() = session?.queueVisibleRegion()

    fun setRoute(points: List<TravelLatLng>) {
        session?.setRoute(points)
    }

    fun clearRoute() {
        session?.clearRoute()
    }

    fun setDestination(point: TravelLatLng?) {
        session?.setDestination(point)
    }

    fun flyTo(point: TravelLatLng) {
        session?.flyTo(point)
    }

    fun fitRoute(points: List<TravelLatLng>) {
        session?.fitRoute(points)
    }

    fun setChromePadding(left: Int, top: Int, right: Int, bottom: Int) {
        session?.setChromePadding(left, top, right, bottom)
    }
}

@Composable
fun StreetMap(
    modifier: Modifier = Modifier,
    controller: StreetMapController = remember { StreetMapController() },
    pose: NavigationState? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val locationGranted = rememberLocationAccess()
    val session = remember { StreetMapSession() }

    DisposableEffect(controller, session) {
        controller.session = session
        session.controller = controller
        onDispose {
            if (controller.session === session) {
                controller.session = null
            }
            session.controller = null
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            createStreetMapView(ctx).apply {
                session.mapView = this
                onCreate(Bundle())
                getMapAsync { map -> session.bind(map, this, ctx) }
            }
        },
        onRelease = { view ->
            session.unbind(view.context)
            session.mapView = null
            view.onPause()
            view.onStop()
            view.onDestroy()
        },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = session.mapView ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        session.mapView?.let { view ->
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                view.onStart()
            }
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                view.onResume()
            }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(locationGranted, pose) {
        controller.onPermission?.invoke(locationGranted)
        if (locationGranted) {
            session.enablePuck(context)
            session.applyPose(pose)
        }
    }
}

@Composable
private fun rememberLocationAccess(): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasLocationPermission(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result.values.any { it } || hasLocationPermission(context)
    }
    LaunchedEffect(Unit) {
        if (!granted) {
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }
    return granted
}

internal fun createStreetMapView(context: Context): MapView {
    MapLibre.getInstance(context.applicationContext)
    val options = MapLibreMapOptions.createFromAttributes(context)
        .textureMode(StreetMapConfig.TEXTURE_MODE)
        .foregroundLoadColor(StreetMapConfig.MAP_LOAD_COLOR_ARGB.toInt())
        .compassEnabled(false)
        .logoEnabled(false)
        .attributionEnabled(true)
    return MapView(context, options).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(Color.TRANSPARENT)
    }
}

internal class StreetMapSession {
    var mapView: MapView? = null
    var controller: StreetMapController? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var appContext: Context? = null
    private var fallbackUsed: Boolean = false
    private var puckArmed: Boolean = false
    private var didInitialRecenter: Boolean = false
    private var gpsReceiver: BroadcastReceiver? = null
    private var paddingLeft: Int = 0
    private var paddingTop: Int = 0
    private var paddingRight: Int = 0
    private var paddingBottom: Int = 0
    private var lastFixStore: LastFixStore? = null
    private var packStore: AreaPackStore? = null

    fun bind(map: MapLibreMap, mapView: MapView, context: Context) {
        this.map = map
        this.mapView = mapView
        this.appContext = context.applicationContext
        lastFixStore = LastFixStore.prefs(context)
        packStore = AreaPackStore(File(context.applicationContext.filesDir, "area-packs"))
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isAttributionEnabled = true
        map.uiSettings.isLogoEnabled = false
        map.addOnCameraMoveListener {
            val bearing = map.cameraPosition.bearing.toFloat()
            controller?.onBearing?.invoke(bearing)
        }
        mapView.addOnDidFailLoadingMapListener {
            if (!fallbackUsed && this.style == null) {
                fallbackUsed = true
                map.setStyle(StreetMapConfig.STYLE_BRIGHT) { loaded ->
                    onStyle(map, loaded, context)
                }
            }
        }
        val styleUri = packStore?.styleUri(packStore?.active()) ?: StreetMapConfig.STYLE_LIBERTY
        map.setStyle(styleUri) { loaded ->
            onStyle(map, loaded, context)
        }
        registerGpsReceiver(context)
    }

    fun unbind(context: Context) {
        unregisterGpsReceiver(context)
        puckArmed = false
        style = null
        map = null
    }

    private fun onStyle(map: MapLibreMap, style: Style, context: Context) {
        this.style = style
        puckArmed = false
        val live = newestLastKnownLocation(context)?.let { TravelLatLng(it.latitude, it.longitude) }
        val start = CameraStartResolver.resolve(liveGps = live, lastFix = lastFixStore?.read())
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(start.latitudeDeg, start.longitudeDeg))
            .zoom(start.zoom)
            .build()
        didInitialRecenter = start.isStreetLevel
        live?.let { lastFixStore?.write(it) }
        ensureRouteLayers(style)
        applyPadding()
        if (hasLocationPermission(context)) {
            enablePuck(context)
        }
    }

    private fun ensureRouteLayers(style: Style) {
        if (style.getSource(StreetMapConfig.ROUTE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(StreetMapConfig.ROUTE_SOURCE_ID, EMPTY_FEATURE_COLLECTION))
        }
        if (style.getSource(StreetMapConfig.DEST_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(StreetMapConfig.DEST_SOURCE_ID, EMPTY_FEATURE_COLLECTION))
        }
        if (style.getLayer(StreetMapConfig.ROUTE_LAYER_ID) == null) {
            val line = LineLayer(StreetMapConfig.ROUTE_LAYER_ID, StreetMapConfig.ROUTE_SOURCE_ID)
                .withProperties(
                    PropertyFactory.lineColor(Color.parseColor("#1E6BFF")),
                    PropertyFactory.lineWidth(StreetMapConfig.ROUTE_LINE_WIDTH),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }?.id
            if (firstSymbol != null) {
                style.addLayerBelow(line, firstSymbol)
            } else {
                style.addLayer(line)
            }
        }
        if (style.getLayer(StreetMapConfig.DEST_LAYER_ID) == null) {
            val dest = CircleLayer(StreetMapConfig.DEST_LAYER_ID, StreetMapConfig.DEST_SOURCE_ID)
                .withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(Color.parseColor("#1E6BFF")),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor(Color.WHITE),
                )
            style.addLayer(dest)
        }
    }

    @SuppressLint("MissingPermission")
    fun enablePuck(context: Context) {
        val map = map ?: return
        val style = style ?: return
        if (!hasLocationPermission(context)) {
            return
        }
        val component = map.locationComponent
        if (puckArmed) {
            component.isLocationComponentEnabled = true
            return
        }
        val puck = StreetMapConfig.PUCK_COLOR_ARGB.toInt()
        val topLayer = style.layers.lastOrNull()?.id
        val builder = LocationComponentOptions.builder(context)
            .foregroundDrawable(R.drawable.own_vehicle_puck)
            .backgroundDrawable(R.drawable.own_vehicle_puck_ring)
            .bearingDrawable(R.drawable.own_vehicle_heading)
            .gpsDrawable(R.drawable.own_vehicle_puck)
            .accuracyColor(puck)
            .accuracyAlpha(0.16f)
            .enableStaleState(false)
            .pulseEnabled(false)
            .accuracyAnimationEnabled(false)
        if (topLayer != null) {
            builder.layerAbove(topLayer)
        }
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(builder.build())
                .useDefaultLocationEngine(false)
                .build(),
        )
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.GPS
        component.cameraMode = CameraMode.NONE
        newestLastKnownLocation(context)?.let { loc ->
            component.forceLocationUpdate(loc)
            emitFix(loc)
            lastFixStore?.write(TravelLatLng(loc.latitude, loc.longitude))
        }
        puckArmed = true
    }

    @SuppressLint("MissingPermission")
    fun applyPose(state: NavigationState?) {
        val map = map ?: return
        if (!puckArmed || state == null) {
            return
        }
        val component = map.locationComponent
        val location = state.toAndroidLocation()
        component.forceLocationUpdate(location)
        emitFixFromPose(state)
        lastFixStore?.write(
            TravelLatLng(state.position.latitude.value, state.position.longitude.value),
        )
        if (!didInitialRecenter) {
            didInitialRecenter = true
            recenterOnPuck()
        }
    }

    fun recenterOnPuck() {
        val map = map ?: return
        val loc = map.locationComponent.lastKnownLocation ?: return
        map.locationComponent.cameraMode = CameraMode.TRACKING_GPS
        map.easeCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(loc.latitude, loc.longitude),
                StreetMapConfig.CAMERA_ZOOM,
            ),
            250,
        )
    }

    fun followPuck() {
        if (!puckArmed) {
            return
        }
        map?.locationComponent?.cameraMode = CameraMode.TRACKING_GPS
    }

    fun stopFollow() {
        if (!puckArmed) {
            return
        }
        map?.locationComponent?.cameraMode = CameraMode.NONE
    }

    fun resetNorth() {
        val map = map ?: return
        val current = map.cameraPosition
        map.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(current).bearing(0.0).build(),
            ),
            250,
        )
    }

    fun originOrNull(): TravelLatLng? {
        val loc = map?.locationComponent?.lastKnownLocation
        if (loc != null) {
            return TravelLatLng(loc.latitude, loc.longitude)
        }
        lastFixStore?.read()?.let { return it }
        val known = appContext?.let { newestLastKnownLocation(it) } ?: return null
        return TravelLatLng(known.latitude, known.longitude)
    }

    fun cameraOrNull(): TravelLatLng? {
        val target = map?.cameraPosition?.target ?: return null
        return CameraStartResolver.validOrNull(target.latitude, target.longitude)
    }

    fun cameraZoomOrNull(): Double? {
        val zoom = map?.cameraPosition?.zoom ?: return null
        return zoom.takeIf { it.isFinite() }
    }

    fun visibleBbox(): GeoBbox? {
        val bounds = map?.projection?.visibleRegion?.latLngBounds ?: return null
        return GeoBbox.of(
            southLatDeg = bounds.latitudeSouth,
            westLonDeg = bounds.longitudeWest,
            northLatDeg = bounds.latitudeNorth,
            eastLonDeg = bounds.longitudeEast,
        )
    }

    fun queueVisibleRegion(): AreaPack? {
        val bbox = visibleBbox() ?: return null
        return packStore?.queue(bbox)
    }

    fun setRoute(points: List<TravelLatLng>) {
        val style = style ?: return
        val source = style.getSourceAs<GeoJsonSource>(StreetMapConfig.ROUTE_SOURCE_ID) ?: return
        if (points.size < 2) {
            source.setGeoJson(EMPTY_FEATURE_COLLECTION)
            return
        }
        source.setGeoJson(lineStringGeoJson(points))
    }

    fun clearRoute() {
        val style = style ?: return
        style.getSourceAs<GeoJsonSource>(StreetMapConfig.ROUTE_SOURCE_ID)
            ?.setGeoJson(EMPTY_FEATURE_COLLECTION)
    }

    fun setDestination(point: TravelLatLng?) {
        val style = style ?: return
        val source = style.getSourceAs<GeoJsonSource>(StreetMapConfig.DEST_SOURCE_ID) ?: return
        if (point == null) {
            source.setGeoJson(EMPTY_FEATURE_COLLECTION)
        } else {
            source.setGeoJson(pointGeoJson(point))
        }
    }

    fun flyTo(point: TravelLatLng) {
        val map = map ?: return
        map.easeCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(point.latitudeDeg, point.longitudeDeg),
                StreetMapConfig.CAMERA_ZOOM,
            ),
            250,
        )
    }

    fun fitRoute(points: List<TravelLatLng>) {
        val map = map ?: return
        if (points.isEmpty()) {
            return
        }
        if (points.size == 1) {
            flyTo(points.first())
            return
        }
        val bounds = LatLngBounds.Builder()
        points.forEach { bounds.include(LatLng(it.latitudeDeg, it.longitudeDeg)) }
        map.easeCamera(
            CameraUpdateFactory.newLatLngBounds(
                bounds.build(),
                paddingLeft,
                paddingTop,
                paddingRight,
                paddingBottom,
            ),
            250,
        )
    }

    fun setChromePadding(left: Int, top: Int, right: Int, bottom: Int) {
        paddingLeft = left
        paddingTop = top
        paddingRight = right
        paddingBottom = bottom
        applyPadding()
    }

    private fun applyPadding() {
        map?.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
    }

    private fun emitFixFromPose(state: NavigationState) {
        controller?.onFix?.invoke(state.toTravelFix(providerEnabled = gpsProviderEnabled(appContext ?: return) && state.mode != NavigationMode.DEAD_RECKONING))
    }

    private fun emitFix(location: Location) {
        val context = appContext ?: return
        val speed = if (location.hasSpeed()) location.speed.toDouble() else null
        val bearing = if (location.hasBearing()) location.bearing.toDouble() else null
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        controller?.onFix?.invoke(
            TravelFix(
                latitudeDeg = location.latitude,
                longitudeDeg = location.longitude,
                speedMps = speed,
                bearingDeg = bearing,
                accuracyM = accuracy,
                gpsProviderOn = gpsProviderEnabled(context),
            ),
        )
    }

    private fun registerGpsReceiver(context: Context) {
        if (gpsReceiver != null) {
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != LocationManager.PROVIDERS_CHANGED_ACTION) {
                    return
                }
                val loc = map?.locationComponent?.lastKnownLocation
                if (loc != null) {
                    emitFix(loc)
                }
            }
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        gpsReceiver = receiver
    }

    private fun unregisterGpsReceiver(context: Context) {
        val receiver = gpsReceiver ?: return
        try {
            context.applicationContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered with the map view.
        }
        gpsReceiver = null
    }
}

private fun gpsProviderEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        return true
    }
    if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
        return true
    }
    return try {
        manager.isProviderEnabled("fused")
    } catch (_: IllegalArgumentException) {
        false
    }
}

private const val POSE_PROVIDER = "driftzero"

private fun NavigationState.toAndroidLocation(): Location {
    val location = Location(POSE_PROVIDER)
    location.latitude = puckLatitudeDeg()
    location.longitude = puckLongitudeDeg()
    location.bearing = Math.toDegrees(puckHeadingRad()).toFloat()
    location.speed = motion.speed.value.toFloat()
    location.accuracy = uncertainty.horizontal95.value.toFloat()
    location.elapsedRealtimeNanos = timestamp.value
    location.time = System.currentTimeMillis()
    position.altitudeM?.let { location.altitude = it }
    return location
}
