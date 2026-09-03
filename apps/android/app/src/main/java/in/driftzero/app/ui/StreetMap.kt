package `in`.driftzero.app.ui

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.util.Log
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import `in`.driftzero.app.R
import `in`.driftzero.app.maps.AreaPack
import `in`.driftzero.app.maps.AreaPackStore
import `in`.driftzero.app.maps.GeoBbox
import `in`.driftzero.app.pose.LocationGrant
import `in`.driftzero.app.pose.newestLastKnownLocation
import `in`.driftzero.app.pose.readLocationGrant
import java.io.File
import kotlin.math.abs
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * MapLibre Native street map in an [AndroidView].
 *
 * The own-vehicle mark, its confidence halo, and its heading cone are GeoJSON
 * layers fed once per frame by [StreetMapController.applyDisplayPose], so all
 * three share one clock and the halo is true in metres at every zoom. The
 * MapLibre LocationComponent is not used: it animates on its own schedule and
 * draws a pixel accuracy disc.
 *
 * MapView defaults to a SurfaceView. Compose cannot punch a hole through an
 * opaque parent, so that surface paints behind the window and the map looks
 * gone. [StreetMapConfig.TEXTURE_MODE] puts the map on a TextureView so it
 * composites in the view tree.
 *
 * MapLibre 13 `android-sdk` is Vulkan. On the emulator that guest VkInstance
 * (engine maplibre-native) then kills host qemu with SIGSEGV 139, host GPU or
 * SwiftShader. The app depends on `android-sdk-opengl` so the guest stays on
 * OpenGL ES.
 */
class StreetMapController {
    internal var session: StreetMapSession? = null
    var onBearing: ((Float) -> Unit)? = null
    var onLocationGrant: ((LocationGrant) -> Unit)? = null
    var onFollowing: ((Boolean) -> Unit)? = null
    var onMapClick: ((TravelLatLng) -> Unit)? = null

    fun locateOwnVehicle() {
        session?.recenterOnPuck()
    }

    fun followOwnVehicle() {
        session?.setFollowing(true)
    }

    fun stopFollow() {
        session?.setFollowing(false)
    }

    fun setNavigating(on: Boolean) {
        session?.setNavigating(on)
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
        session?.setRoute(emptyList())
    }

    fun setDestination(point: TravelLatLng?) {
        session?.setDestination(point)
    }

    fun setMatchedRoad(points: List<TravelLatLng>?) {
        session?.setMatchedRoad(points)
    }

    fun applyDisplayPose(puck: DisplayPuck, lamp: LampDisplay, frameNs: Long) {
        session?.applyDisplayPose(puck, lamp, frameNs)
    }

    fun setRawTrail(points: List<TravelLatLng>) {
        session?.setRawTrail(points)
    }

    fun setFusedTrail(points: List<TravelLatLng>) {
        session?.setFusedTrail(points)
    }

    fun setBlackoutMarks(ghost: TravelLatLng?, correction: List<TravelLatLng>?) {
        session?.setBlackoutMarks(ghost, correction)
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

/** Packed ARGB ints for MapLibre paint properties, taken from the active palette. */
internal data class MapPalette(
    val marker: Int,
    val paper: Int,
    val routeFill: Int,
    val routeCasing: Int,
    val haloFillAlpha: Float,
    val lampOk: Int,
    val lampCaution: Int,
    val lampAlert: Int,
    val inkDim: Int,
) {
    fun lamp(tone: LampTone): Int = when (tone) {
        LampTone.OK -> lampOk
        LampTone.CAUTION -> lampCaution
        LampTone.ALERT -> lampAlert
    }

    companion object {
        fun from(colors: InstrumentColors): MapPalette = MapPalette(
            marker = colors.marker.toArgb(),
            paper = colors.paper.toArgb(),
            routeFill = colors.routeFill.toArgb(),
            routeCasing = colors.routeCasing.toArgb(),
            haloFillAlpha = colors.haloFillAlpha,
            lampOk = colors.lampOk.toArgb(),
            lampCaution = colors.lampCaution.toArgb(),
            lampAlert = colors.lampAlert.toArgb(),
            inkDim = colors.inkDim.toArgb(),
        )
    }
}

@Composable
fun StreetMap(
    modifier: Modifier = Modifier,
    controller: StreetMapController = remember { StreetMapController() },
    packStyleJson: String? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val locationGranted = rememberLocationAccess()
    val session = remember { StreetMapSession() }
    val night = InstrumentTheme.night
    val palette = MapPalette.from(InstrumentTheme.colors)
    val loadColorArgb = InstrumentTheme.colors.chassis.toArgb()
    session.night = night
    session.packStyleJson = packStyleJson

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
            createStreetMapView(ctx, loadColorArgb).apply {
                session.mapView = this
                onCreate(Bundle())
            }
        },
        onRelease = { view ->
            session.unbind()
            session.mapView = null
            view.onPause()
            view.onStop()
            view.onDestroy()
        },
    )

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            val view = session.mapView ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> {
                    view.onResume()
                    session.attachIfNeeded(view, context)
                }
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
                session.attachIfNeeded(view, context)
            }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(night, packStyleJson) {
        session.packStyleJson = packStyleJson
        session.applySheet(night)
    }

    LaunchedEffect(palette) {
        session.applyPalette(palette)
    }

    LaunchedEffect(locationGranted) {
        controller.onLocationGrant?.invoke(locationGranted)
    }
}

@Composable
private fun rememberLocationAccess(): LocationGrant {
    val context = LocalContext.current
    var grant by remember { mutableStateOf(readLocationGrant(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        grant = readLocationGrant(context).let { now ->
            if (now != LocationGrant.NONE) {
                now
            } else if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                LocationGrant.FINE
            } else if (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                LocationGrant.COARSE
            } else {
                LocationGrant.NONE
            }
        }
    }
    LaunchedEffect(Unit) {
        if (grant != LocationGrant.FINE) {
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }
    return grant
}

internal fun createStreetMapView(context: Context, loadColorArgb: Int): MapView {
    MapLibre.getInstance(context.applicationContext)
    val options = MapLibreMapOptions.createFromAttributes(context)
        .textureMode(StreetMapConfig.TEXTURE_MODE)
        .foregroundLoadColor(loadColorArgb)
        .compassEnabled(false)
        .logoEnabled(false)
        .attributionEnabled(true)
    return MapView(context, options).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }
}

internal class StreetMapSession {
    var mapView: MapView? = null
    var controller: StreetMapController? = null
    var night: Boolean = false
    var packStyleJson: String? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var appContext: Context? = null
    private var fallbackUsed: Boolean = false
    private var loadedKey: String? = null
    private var palette: MapPalette? = null
    private var didInitialRecenter: Boolean = false
    private var liveJumpDone: Boolean = false
    private var sessionStartFix: TravelLatLng? = null
    private var sessionStartFixCaptured: Boolean = false
    private var following: Boolean = false
    private var navigating: Boolean = false
    private var northUpLocked: Boolean = false
    private var cameraBusyUntilNs: Long = 0L
    private var zoomBand: Double? = null
    private var paddingLeft: Int = 0
    private var paddingTop: Int = 0
    private var paddingRight: Int = 0
    private var paddingBottom: Int = 0
    private var lastFixStore: LastFixStore? = null
    private var packStore: AreaPackStore? = null
    private var lastPuck: DisplayPuck? = null
    private var lastDrawnZoom: Double = Double.NaN
    private var lastLampTone: LampTone? = null
    private var lastLampDashed: Boolean? = null
    private var lastBearingSent: Float = Float.NaN
    private var lastFixWritten: TravelLatLng? = null
    private var lastFixWriteNs: Long = 0L
    private var routePoints: List<TravelLatLng> = emptyList()
    private var destination: TravelLatLng? = null
    private var matchedRoad: List<TravelLatLng>? = null
    private var rawTrail: List<TravelLatLng> = emptyList()
    private var fusedTrail: List<TravelLatLng> = emptyList()
    private var ghostFix: TravelLatLng? = null
    private var correctionLine: List<TravelLatLng>? = null

    fun bind(map: MapLibreMap, mapView: MapView, context: Context) {
        this.map = map
        this.mapView = mapView
        this.appContext = context.applicationContext
        lastFixStore = LastFixStore.prefs(context)
        if (!sessionStartFixCaptured) {
            sessionStartFix = lastFixStore?.read()
            sessionStartFixCaptured = true
        }
        packStore = AreaPackStore(File(context.applicationContext.filesDir, "area-packs"))
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isAttributionEnabled = true
        map.uiSettings.isLogoEnabled = false
        map.addOnCameraMoveListener {
            val bearing = map.cameraPosition.bearing.toFloat()
            if (lastBearingSent.isNaN() || abs(bearing - lastBearingSent) > 1f) {
                lastBearingSent = bearing
                controller?.onBearing?.invoke(bearing)
            }
        }
        map.addOnMapClickListener { latLng ->
            val handler = controller?.onMapClick
            if (handler != null) {
                handler(TravelLatLng(latLng.latitude, latLng.longitude))
                true
            } else {
                false
            }
        }
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                setFollowing(false)
                northUpLocked = true
            }
        }
        mapView.addOnDidFailLoadingMapListener {
            if (!fallbackUsed && this.style == null) {
                val packJson = coveringPackJson(context)
                if (packJson != null) {
                    loadSheet(map, context)
                    return@addOnDidFailLoadingMapListener
                }
                fallbackUsed = true
                map.setStyle(StreetMapConfig.STYLE_BRIGHT) { loaded -> onStyle(map, loaded, context) }
            }
        }
        loadSheet(map, context)
    }

    /** Style load waits until MapView is resumed so the TextureView surface exists. */
    fun attachIfNeeded(view: MapView, context: Context) {
        if (map != null) {
            return
        }
        view.getMapAsync { mapLibre ->
            if (this.map == null) {
                bind(mapLibre, view, context)
            }
        }
    }

    fun unbind() {
        style = null
        map = null
    }

    private fun sheetKey(): String =
        "${if (night) "n" else "d"}:${packStyleJson?.hashCode() ?: 0}"

    private fun coveringPackJson(context: Context): String? {
        packStyleJson?.let { return it }
        val live = newestLastKnownLocation(context)
        val stored = lastFixStore?.read()
        val lat = live?.latitude ?: stored?.latitudeDeg
        val lon = live?.longitude ?: stored?.longitudeDeg
        val json = packStore?.coveringStyleJson(lat, lon, night) ?: return null
        packStyleJson = json
        return json
    }

    private fun loadSheet(map: MapLibreMap, context: Context) {
        coveringPackJson(context)
        loadedKey = sheetKey()
        val json = packStyleJson
        if (json != null) {
            val cache = File(context.cacheDir, if (night) "pack-style-night.json" else "pack-style-day.json")
            cache.writeText(json, Charsets.UTF_8)
            Log.i(
                STREET_MAP_SESSION_TAG,
                "pack style ${cache.absolutePath} chars=${json.length} " +
                    "pmtiles=${json.contains("pmtiles://file://")}",
            )
            map.setStyle("file://${cache.absolutePath}") { loaded -> onStyle(map, loaded, context) }
        } else {
            val hosted = StreetMapConfig.hostedStyleIfNoPack(packStyleJson, night) ?: StreetMapConfig.hostedStyle(night)
            Log.i(STREET_MAP_SESSION_TAG, "hosted style night=$night")
            map.setStyle(hosted) { loaded -> onStyle(map, loaded, context) }
        }
    }

    /** Swap day and night sheets, or reload when the pack style JSON changes. */
    fun applySheet(night: Boolean) {
        this.night = night
        val map = map ?: return
        val context = appContext ?: return
        if (loadedKey == sheetKey() && style != null) {
            return
        }
        style = null
        loadSheet(map, context)
    }

    fun applyPalette(palette: MapPalette) {
        this.palette = palette
        style?.let { paintLayers(it, palette) }
    }

    private fun onStyle(map: MapLibreMap, style: Style, context: Context) {
        this.style = style
        val live = newestLastKnownLocation(context)?.let { TravelLatLng(it.latitude, it.longitude) }
        val stored = lastFixStore?.read()
        if (!sessionStartFixCaptured) {
            sessionStartFix = stored
            sessionStartFixCaptured = true
        }
        Log.i(LAST_KNOWN_TAG, formatPoint("lastKnown", live))
        if (!didInitialRecenter) {
            val start = CameraStartResolver.resolve(liveGps = live, lastFix = stored)
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(start.latitudeDeg, start.longitudeDeg))
                .zoom(start.zoom)
                .build()
            didInitialRecenter = start.isStreetLevel
            Log.i(
                CAMERA_START_TAG,
                "camera ${start.latitudeDeg},${start.longitudeDeg} zoom=${start.zoom} " +
                    "${formatPoint("live", live)} ${formatPoint("lastFix", stored)}",
            )
            if (CameraStartResolver.shouldRecenterOnLive(
                    liveGps = live,
                    lastFix = sessionStartFix,
                    alreadyRecentred = false,
                )
            ) {
                liveJumpDone = true
            }
        }
        live?.let { lastFixStore?.write(it) }
        ensureLayers(style)
        palette?.let { paintLayers(style, it) }
        applyNavPadding(map)
        setRoute(routePoints)
        setDestination(destination)
        setMatchedRoad(matchedRoad)
        setRawTrail(rawTrail)
        setFusedTrail(fusedTrail)
        setBlackoutMarks(ghostFix, correctionLine)
        lastPuck?.let { puck ->
            setGeoJson(StreetMapConfig.PUCK_SOURCE_ID, pointGeoJson(TravelLatLng(puck.latitudeDeg, puck.longitudeDeg)))
        }
    }

    private fun ensureLayers(style: Style) {
        listOf(
            StreetMapConfig.TRAIL_RAW_SOURCE_ID,
            StreetMapConfig.TRAIL_FUSED_SOURCE_ID,
            StreetMapConfig.MATCHED_SOURCE_ID,
            StreetMapConfig.ROUTE_SOURCE_ID,
            StreetMapConfig.HALO_SOURCE_ID,
            StreetMapConfig.CONE_SOURCE_ID,
            StreetMapConfig.PUCK_SOURCE_ID,
            StreetMapConfig.DEST_SOURCE_ID,
            StreetMapConfig.GHOST_SOURCE_ID,
            StreetMapConfig.CORRECTION_SOURCE_ID,
        ).forEach { id ->
            if (style.getSource(id) == null) {
                style.addSource(GeoJsonSource(id, EMPTY_FEATURE_COLLECTION))
            }
        }
        val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }?.id
        fun belowLabels(layer: Layer) {
            if (style.getLayer(layer.id) != null) return
            if (firstSymbol != null) style.addLayerBelow(layer, firstSymbol) else style.addLayer(layer)
        }
        fun onTop(layer: Layer) {
            if (style.getLayer(layer.id) == null) style.addLayer(layer)
        }
        belowLabels(
            LineLayer(StreetMapConfig.TRAIL_RAW_LAYER_ID, StreetMapConfig.TRAIL_RAW_SOURCE_ID).withProperties(
                PropertyFactory.lineWidth(StreetMapConfig.TRAIL_RAW_WIDTH),
                PropertyFactory.lineDasharray(arrayOf(1f, 1.5f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        belowLabels(
            LineLayer(StreetMapConfig.TRAIL_FUSED_LAYER_ID, StreetMapConfig.TRAIL_FUSED_SOURCE_ID).withProperties(
                PropertyFactory.lineWidth(StreetMapConfig.TRAIL_FUSED_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        belowLabels(
            LineLayer(StreetMapConfig.MATCHED_LAYER_ID, StreetMapConfig.MATCHED_SOURCE_ID).withProperties(
                PropertyFactory.lineWidth(StreetMapConfig.MATCHED_LINE_WIDTH),
                PropertyFactory.lineOpacity(StreetMapConfig.MATCHED_ALPHA),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        belowLabels(
            LineLayer(StreetMapConfig.ROUTE_CASING_LAYER_ID, StreetMapConfig.ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineWidth(StreetMapConfig.ROUTE_CASING_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        belowLabels(
            LineLayer(StreetMapConfig.ROUTE_LAYER_ID, StreetMapConfig.ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineWidth(StreetMapConfig.ROUTE_LINE_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        belowLabels(FillLayer(StreetMapConfig.HALO_FILL_LAYER_ID, StreetMapConfig.HALO_SOURCE_ID))
        belowLabels(
            LineLayer(StreetMapConfig.HALO_LINE_LAYER_ID, StreetMapConfig.HALO_SOURCE_ID).withProperties(
                PropertyFactory.lineWidth(StreetMapConfig.HALO_LINE_WIDTH),
            ),
        )
        belowLabels(
            FillLayer(StreetMapConfig.CONE_LAYER_ID, StreetMapConfig.CONE_SOURCE_ID).withProperties(
                PropertyFactory.fillOpacity(StreetMapConfig.CONE_ALPHA),
            ),
        )
        val ghostLabel = appContext?.getString(R.string.map_last_fix) ?: "Last fix"
        onTop(
            LineLayer(StreetMapConfig.CORRECTION_LAYER_ID, StreetMapConfig.CORRECTION_SOURCE_ID).withProperties(
                PropertyFactory.lineWidth(StreetMapConfig.CORRECTION_LINE_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        onTop(
            CircleLayer(StreetMapConfig.GHOST_LAYER_ID, StreetMapConfig.GHOST_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(StreetMapConfig.GHOST_RADIUS_DP),
                PropertyFactory.circleOpacity(0f),
                PropertyFactory.circleStrokeWidth(StreetMapConfig.GHOST_STROKE_DP),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
        onTop(
            SymbolLayer(StreetMapConfig.GHOST_LABEL_LAYER_ID, StreetMapConfig.GHOST_SOURCE_ID).withProperties(
                PropertyFactory.textField(ghostLabel),
                PropertyFactory.textSize(11f),
                PropertyFactory.textOffset(arrayOf(0f, 1.2f)),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            ),
        )
        onTop(
            CircleLayer(StreetMapConfig.PUCK_RING_LAYER_ID, StreetMapConfig.PUCK_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(StreetMapConfig.PUCK_DISK_DP / 2f + StreetMapConfig.PUCK_RING_DP),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
        onTop(
            CircleLayer(StreetMapConfig.PUCK_LAYER_ID, StreetMapConfig.PUCK_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(StreetMapConfig.PUCK_DISK_DP / 2f),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
        onTop(
            CircleLayer(StreetMapConfig.DEST_LAYER_ID, StreetMapConfig.DEST_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(StreetMapConfig.DEST_RADIUS_DP),
                PropertyFactory.circleStrokeWidth(StreetMapConfig.DEST_STROKE_DP),
            ),
        )
    }

    private fun paintLayers(style: Style, palette: MapPalette) {
        style.getLayer(StreetMapConfig.TRAIL_RAW_LAYER_ID)?.setProperties(PropertyFactory.lineColor(palette.inkDim))
        style.getLayer(StreetMapConfig.TRAIL_FUSED_LAYER_ID)?.setProperties(PropertyFactory.lineColor(palette.marker))
        style.getLayer(StreetMapConfig.MATCHED_LAYER_ID)?.setProperties(PropertyFactory.lineColor(palette.routeFill))
        style.getLayer(StreetMapConfig.ROUTE_CASING_LAYER_ID)?.setProperties(PropertyFactory.lineColor(palette.routeCasing))
        style.getLayer(StreetMapConfig.ROUTE_LAYER_ID)?.setProperties(PropertyFactory.lineColor(palette.routeFill))
        style.getLayer(StreetMapConfig.HALO_FILL_LAYER_ID)?.setProperties(
            PropertyFactory.fillColor(palette.marker),
            PropertyFactory.fillOpacity(palette.haloFillAlpha),
        )
        style.getLayer(StreetMapConfig.CONE_LAYER_ID)?.setProperties(PropertyFactory.fillColor(palette.marker))
        style.getLayer(StreetMapConfig.PUCK_RING_LAYER_ID)?.setProperties(PropertyFactory.circleColor(palette.paper))
        style.getLayer(StreetMapConfig.PUCK_LAYER_ID)?.setProperties(PropertyFactory.circleColor(palette.marker))
        style.getLayer(StreetMapConfig.DEST_LAYER_ID)?.setProperties(
            PropertyFactory.circleColor(palette.marker),
            PropertyFactory.circleStrokeColor(palette.paper),
        )
        style.getLayer(StreetMapConfig.CORRECTION_LAYER_ID)?.setProperties(
            PropertyFactory.lineColor(palette.lampCaution),
        )
        style.getLayer(StreetMapConfig.GHOST_LAYER_ID)?.setProperties(
            PropertyFactory.circleStrokeColor(palette.inkDim),
        )
        style.getLayer(StreetMapConfig.GHOST_LABEL_LAYER_ID)?.setProperties(
            PropertyFactory.textColor(palette.inkDim),
            PropertyFactory.textHaloColor(palette.paper),
            PropertyFactory.textHaloWidth(1.2f),
        )
        lastLampTone = null
        lastLampDashed = null
    }

    private fun setGeoJson(sourceId: String, json: String) {
        style?.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(json)
    }

    /**
     * One frame of the own-vehicle mark. Halo radius is [DisplayPuck.radiusM]
     * on the ground; cone length is 36 dp at the current zoom; the stroke
     * takes the lamp colour and dashes on coasting modes.
     */
    fun applyDisplayPose(puck: DisplayPuck, lamp: LampDisplay, frameNs: Long) {
        val map = map ?: run { lastPuck = puck; return }
        val style = style ?: run { lastPuck = puck; return }
        val zoom = map.cameraPosition.zoom
        val unchanged = puck == lastPuck && zoom == lastDrawnZoom &&
            lamp.tone == lastLampTone && lamp.dashed == lastLampDashed
        if (unchanged) {
            if (following &&
                frameNs >= cameraBusyUntilNs &&
                puck.speedMps >= StreetMapConfig.FOLLOW_MIN_SPEED_MPS
            ) {
                followCamera(map, puck, zoom)
            }
            return
        }
        lastPuck = puck
        lastDrawnZoom = zoom
        val centre = TravelLatLng(puck.latitudeDeg, puck.longitudeDeg)
        setGeoJson(StreetMapConfig.PUCK_SOURCE_ID, pointGeoJson(centre))
        setGeoJson(
            StreetMapConfig.HALO_SOURCE_ID,
            polygonGeoJson(MapGeometry.circle(puck.latitudeDeg, puck.longitudeDeg, puck.radiusM)),
        )
        if (MapGeometry.coneVisible(puck.speedMps, puck.heading95Rad)) {
            val lengthM = MapGeometry.CONE_LENGTH_DP * MapGeometry.metresPerDp(puck.latitudeDeg, zoom)
            setGeoJson(
                StreetMapConfig.CONE_SOURCE_ID,
                polygonGeoJson(
                    MapGeometry.wedge(puck.latitudeDeg, puck.longitudeDeg, puck.headingRad, puck.heading95Rad, lengthM),
                ),
            )
        } else {
            setGeoJson(StreetMapConfig.CONE_SOURCE_ID, EMPTY_FEATURE_COLLECTION)
        }
        val palette = palette
        if (palette != null && (lamp.tone != lastLampTone || lamp.dashed != lastLampDashed)) {
            lastLampTone = lamp.tone
            lastLampDashed = lamp.dashed
            val dash = if (lamp.dashed) arrayOf(4f, 2.67f) else arrayOf(1f, 0f)
            style.getLayer(StreetMapConfig.HALO_LINE_LAYER_ID)?.setProperties(
                PropertyFactory.lineColor(palette.lamp(lamp.tone)),
                PropertyFactory.lineDasharray(dash),
            )
        }
        if (CameraStartResolver.shouldRecenterOnLive(
                liveGps = centre,
                lastFix = sessionStartFix,
                alreadyRecentred = liveJumpDone,
            )
        ) {
            liveJumpDone = true
            didInitialRecenter = true
            Log.i(
                STREET_MAP_SESSION_TAG,
                "recenter live ${centre.latitudeDeg},${centre.longitudeDeg} " +
                    "${formatPoint("lastFix", sessionStartFix)}",
            )
            persistFix(centre, frameNs)
            recenterOnPuck()
            return
        }
        persistFix(centre, frameNs)
        if (!didInitialRecenter) {
            didInitialRecenter = true
            recenterOnPuck()
            return
        }
        if (following &&
            frameNs >= cameraBusyUntilNs &&
            puck.speedMps >= StreetMapConfig.FOLLOW_MIN_SPEED_MPS
        ) {
            followCamera(map, puck, zoom)
        }
    }

    private fun persistFix(centre: TravelLatLng, frameNs: Long) {
        val prev = lastFixWritten
        val moved = prev == null ||
            abs(centre.latitudeDeg - prev.latitudeDeg) > StreetMapConfig.LAST_FIX_WRITE_MIN_DEG ||
            abs(centre.longitudeDeg - prev.longitudeDeg) > StreetMapConfig.LAST_FIX_WRITE_MIN_DEG
        val aged = frameNs - lastFixWriteNs >= StreetMapConfig.LAST_FIX_WRITE_MIN_NS
        if (!moved && !aged) {
            return
        }
        lastFixStore?.write(centre)
        lastFixWritten = centre
        lastFixWriteNs = frameNs
    }

    private fun followCamera(map: MapLibreMap, puck: DisplayPuck, zoom: Double) {
        val band = MapGeometry.zoomForSpeed(puck.speedMps, zoomBand)
        val target = LatLng(puck.latitudeDeg, puck.longitudeDeg)
        val bearing = MapGeometry.followBearingDeg(puck.speedMps, puck.headingRad, northUp = northUpLocked || !navigating)
        applyNavPadding(map)
        val camera = CameraPosition.Builder()
            .target(target)
            .zoom(if (band != zoomBand) band else zoom)
            .bearing(bearing)
            .tilt(0.0)
            .build()
        if (band != zoomBand) {
            zoomBand = band
            cameraBusyUntilNs = System.nanoTime() + StreetMapConfig.FOLLOW_ZOOM_EASE_MS * 1_000_000L
            map.easeCamera(CameraUpdateFactory.newCameraPosition(camera), StreetMapConfig.FOLLOW_ZOOM_EASE_MS.toInt())
            return
        }
        map.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
    }

    fun setNavigating(on: Boolean) {
        navigating = on
        if (on) {
            northUpLocked = false
            setFollowing(true)
        }
        map?.let { applyNavPadding(it) }
    }

    private fun applyNavPadding(map: MapLibreMap) {
        val height = mapView?.height ?: 0
        val extraTop = if (navigating && !northUpLocked && height > 0) {
            (height * 0.42).toInt().coerceAtLeast(paddingTop)
        } else {
            paddingTop
        }
        map.setPadding(paddingLeft, extraTop, paddingRight, paddingBottom)
    }

    fun setFollowing(on: Boolean) {
        if (following == on) {
            return
        }
        following = on
        if (!on) {
            zoomBand = null
        }
        controller?.onFollowing?.invoke(on)
    }

    fun recenterOnPuck() {
        val map = map ?: return
        val target = lastPuck?.let { TravelLatLng(it.latitudeDeg, it.longitudeDeg) } ?: originOrNull() ?: return
        northUpLocked = false
        setFollowing(true)
        zoomBand = MapGeometry.zoomForSpeed(lastPuck?.speedMps ?: 0.0, null)
        cameraBusyUntilNs = System.nanoTime() + StreetMapConfig.CAMERA_EASE_MS * 1_000_000L
        applyNavPadding(map)
        val puck = lastPuck
        val bearing = if (puck != null) {
            MapGeometry.followBearingDeg(puck.speedMps, puck.headingRad, northUp = !navigating)
        } else {
            0.0
        }
        map.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(target.latitudeDeg, target.longitudeDeg))
                    .zoom(zoomBand ?: StreetMapConfig.CAMERA_ZOOM)
                    .bearing(bearing)
                    .tilt(0.0)
                    .build(),
            ),
            StreetMapConfig.CAMERA_EASE_MS,
        )
    }

    fun resetNorth() {
        val map = map ?: return
        northUpLocked = true
        applyNavPadding(map)
        val current = map.cameraPosition
        cameraBusyUntilNs = System.nanoTime() + StreetMapConfig.CAMERA_EASE_MS * 1_000_000L
        map.easeCamera(
            CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(current).bearing(0.0).tilt(0.0).build()),
            StreetMapConfig.CAMERA_EASE_MS,
        )
    }

    fun originOrNull(): TravelLatLng? {
        lastPuck?.let { return TravelLatLng(it.latitudeDeg, it.longitudeDeg) }
        val live = appContext?.let { newestLastKnownLocation(it) }?.let {
            CameraStartResolver.validOrNull(it.latitude, it.longitude)
        }
        return CameraStartResolver.preferredFix(liveGps = live, lastFix = lastFixStore?.read())
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
        routePoints = points
        setGeoJson(
            StreetMapConfig.ROUTE_SOURCE_ID,
            if (points.size < 2) EMPTY_FEATURE_COLLECTION else lineStringGeoJson(points),
        )
    }

    fun setDestination(point: TravelLatLng?) {
        destination = point
        setGeoJson(StreetMapConfig.DEST_SOURCE_ID, if (point == null) EMPTY_FEATURE_COLLECTION else pointGeoJson(point))
    }

    fun setMatchedRoad(points: List<TravelLatLng>?) {
        matchedRoad = points
        setGeoJson(
            StreetMapConfig.MATCHED_SOURCE_ID,
            if (points == null || points.size < 2) EMPTY_FEATURE_COLLECTION else lineStringGeoJson(points),
        )
    }

    fun setRawTrail(points: List<TravelLatLng>) {
        rawTrail = points
        setGeoJson(
            StreetMapConfig.TRAIL_RAW_SOURCE_ID,
            if (points.size < 2) EMPTY_FEATURE_COLLECTION else lineStringGeoJson(points),
        )
    }

    fun setFusedTrail(points: List<TravelLatLng>) {
        fusedTrail = points
        setGeoJson(
            StreetMapConfig.TRAIL_FUSED_SOURCE_ID,
            if (points.size < 2) EMPTY_FEATURE_COLLECTION else lineStringGeoJson(points),
        )
    }

    fun setBlackoutMarks(ghost: TravelLatLng?, correction: List<TravelLatLng>?) {
        ghostFix = ghost
        correctionLine = correction
        setGeoJson(
            StreetMapConfig.GHOST_SOURCE_ID,
            if (ghost == null) EMPTY_FEATURE_COLLECTION else pointGeoJson(ghost),
        )
        setGeoJson(
            StreetMapConfig.CORRECTION_SOURCE_ID,
            if (correction == null || correction.size < 2) {
                EMPTY_FEATURE_COLLECTION
            } else {
                lineStringGeoJson(correction)
            },
        )
    }

    fun flyTo(point: TravelLatLng) {
        val map = map ?: return
        setFollowing(false)
        map.easeCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(point.latitudeDeg, point.longitudeDeg), StreetMapConfig.CAMERA_ZOOM),
            StreetMapConfig.CAMERA_EASE_MS,
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
        setFollowing(false)
        val bounds = LatLngBounds.Builder()
        points.forEach { bounds.include(LatLng(it.latitudeDeg, it.longitudeDeg)) }
        map.easeCamera(
            CameraUpdateFactory.newLatLngBounds(bounds.build(), paddingLeft, paddingTop, paddingRight, paddingBottom),
            StreetMapConfig.CAMERA_EASE_MS,
        )
    }

    fun setChromePadding(left: Int, top: Int, right: Int, bottom: Int) {
        paddingLeft = left
        paddingTop = top
        paddingRight = right
        paddingBottom = bottom
        val map = map
        if (map != null) {
            applyNavPadding(map)
        }
    }
}

internal const val CAMERA_START_TAG = "CameraStart"
internal const val STREET_MAP_SESSION_TAG = "StreetMapSession"
internal const val LAST_KNOWN_TAG = "lastKnown"

internal fun formatPoint(label: String, point: TravelLatLng?): String {
    return if (point == null) {
        "$label=none"
    } else {
        "$label=${point.latitudeDeg},${point.longitudeDeg}"
    }
}
