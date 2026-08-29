package tw.umaya.tracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import tw.umaya.tracker.R
import java.io.File

/** (3) 地圖方向 — NORTH_UP is the default; TRACK_UP rotates the map to match GPS course (only
 *  meaningful while actually moving); COMPASS_UP rotates it to match the phone's own heading
 *  (works standing still). The arrow marker's on-screen rotation is always azimuth-minus-map-
 *  orientation, so it stays correct (and usually points straight up) in every mode. */
enum class MapOrientationMode { NORTH_UP, TRACK_UP, COMPASS_UP }

/** Same two tile endpoints the guardian web page (Leaflet) already uses — no separate map
 *  backend for the native app, just a different rendering client hitting the same tiles. */
private val OSM_SOURCE = XYTileSource("OSM", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/"))
private val RUDY_SOURCE = XYTileSource("Rudy", 8, 21, 256, ".png?task=hiking", arrayOf("https://tracker.umaya.tw/tiles/"))

enum class MapLayer { ONLINE, RUDY }

private var configured = false

/** osmdroid needs this set once, before the first MapView is created, or tile caching falls
 *  back to a location that can trip strict-mode/storage-permission issues on some OEM ROMs. */
private fun ensureOsmdroidConfigured(context: Context) {
    if (configured) return
    configured = true
    val base = File(context.getExternalFilesDir(null), "osmdroid")
    Configuration.getInstance().apply {
        userAgentValue = context.packageName
        osmdroidBasePath = base
        osmdroidTileCache = File(base, "tiles")
    }
}

/** Thin handle so the caller (HikeScreen) can drive the map from its own icon buttons —
 *  recenter, zoom, layer switch, GPS on/off — without exposing the underlying osmdroid
 *  MapView/View type. */
class HikeMapController internal constructor(
    internal val mapView: MapView,
    private val locationOverlay: MyLocationNewOverlay,
) {
    fun zoomIn() = mapView.controller.zoomIn()
    fun zoomOut() = mapView.controller.zoomOut()
    /** Null while GPS is still acquiring a fix (or turned off) — drives the top bar's
     *  "connecting" pulse animation. */
    fun currentFix() = locationOverlay.myLocation
    fun recenterOnGps() {
        val loc = locationOverlay.myLocation
        if (loc != null) mapView.controller.animateTo(loc)
    }
    /** (1) 載入 GPX/KML's "跳到路線起點" choice. */
    fun animateTo(point: GeoPoint, zoom: Double? = null) {
        if (zoom != null) mapView.controller.setZoom(zoom)
        mapView.controller.animateTo(point)
    }
    fun setLayer(layer: MapLayer) {
        mapView.setTileSource(if (layer == MapLayer.RUDY) RUDY_SOURCE else OSM_SOURCE)
        mapView.invalidate()
    }
    /** (4) 打開/關閉 GPS — actually stops/starts the location overlay, not just its icon; while
     *  off the blue "you are here" dot disappears and [recenterOnGps] has nothing to jump to. */
    fun setGpsEnabled(enabled: Boolean) {
        if (enabled) locationOverlay.enableMyLocation() else locationOverlay.disableMyLocation()
    }

    /** Read by HikeMap's compass/GPS-bearing effects to decide what to rotate the map to;
     *  set via [setOrientationMode] rather than a Composable parameter so switching modes
     *  doesn't need to restart the sensor listener. */
    internal var orientationMode = MapOrientationMode.NORTH_UP
        private set

    fun setOrientationMode(mode: MapOrientationMode) {
        orientationMode = mode
        if (mode == MapOrientationMode.NORTH_UP) mapView.mapOrientation = 0f
        mapView.invalidate()
    }

    // id -> its Polyline overlays (one per segment — see RouteParser.kt for why a route is a
    // list of segments, not one flat point list) — several loaded routes can be shown at once
    // (e.g. comparing two candidate trails), each removable independently by its id.
    private val loadedRoutes = mutableMapOf<String, List<Polyline>>()

    /** (1) 載入 GPX/KML — draws an imported route as one overlay per segment, keyed by [id] (the
     *  caller's choice — e.g. the file name) so multiple routes can be loaded side by side
     *  without one replacing another. Segments are drawn as separate polylines, NOT connected to
     *  each other — joining them would draw a straight line across a real gap (a paused/resumed
     *  hike, or two unrelated trips in one file). Doesn't move the map itself; the caller asks
     *  the hiker whether to jump to the route's start or stay where they are (see [animateTo]).
     *  Loading again under the same [id] replaces just that one route. */
    fun addLoadedRoute(id: String, segments: List<List<GeoPoint>>) {
        loadedRoutes.remove(id)?.forEach { mapView.overlays.remove(it) }
        if (segments.isEmpty()) { mapView.invalidate(); return }
        val polylines = segments.map { points ->
            Polyline(mapView).apply {
                setPoints(points)
                outlinePaint.color = android.graphics.Color.parseColor("#00008B") // dark blue, per request
                outlinePaint.strokeWidth = 8f
            }
        }
        // Direction arrow marker must stay drawn above every loaded route, not under them.
        val arrowIndex = mapView.overlays.indexOfFirst { it is Marker }
        if (arrowIndex >= 0) mapView.overlays.addAll(arrowIndex, polylines) else mapView.overlays.addAll(polylines)
        loadedRoutes[id] = polylines
        mapView.invalidate()
    }

    fun removeLoadedRoute(id: String) {
        loadedRoutes.remove(id)?.forEach { mapView.overlays.remove(it) }
        mapView.invalidate()
    }

    fun clearAllLoadedRoutes() {
        loadedRoutes.values.forEach { polylines -> polylines.forEach { mapView.overlays.remove(it) } }
        loadedRoutes.clear()
        mapView.invalidate()
    }

    private var scaleBar: ScaleBarOverlay? = null

    /** 地圖比例尺 開/關 */
    fun setScaleBarEnabled(enabled: Boolean) {
        if (enabled) {
            if (scaleBar == null) {
                scaleBar = ScaleBarOverlay(mapView).apply { setAlignBottom(true) }
                mapView.overlays.add(0, scaleBar) // under everything else
            }
        } else {
            scaleBar?.let { mapView.overlays.remove(it) }
            scaleBar = null
        }
        mapView.invalidate()
    }

}

/**
 * Full-screen osmdroid map. The hiker's own position is a rotating arrow (not osmdroid's default
 * "person" dot) — its rotation follows the phone's own compass heading (magnetometer), not GPS
 * course-over-ground, so it turns the moment you turn the phone even standing still. GPS course
 * bearing is unreliable/absent exactly when stationary, which is when a hiker checking "which
 * way am I facing" needs it most. [onReady] hands back a [HikeMapController] once the underlying
 * MapView exists.
 */
@Composable
fun HikeMap(modifier: Modifier = Modifier, initialLayer: MapLayer = MapLayer.RUDY, onReady: (HikeMapController) -> Unit) {
    val context = LocalContext.current
    ensureOsmdroidConfigured(context)

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var directionMarker by remember { mutableStateOf<Marker?>(null) }
    var locationOverlayRef by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    var controllerRef by remember { mutableStateOf<HikeMapController?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(if (initialLayer == MapLayer.RUDY) RUDY_SOURCE else OSM_SOURCE)
                setMultiTouchControls(true)
                setBuiltInZoomControls(false) // custom zoom buttons in the 右側導航區, not osmdroid's stock +/-
                controller.setZoom(15.0)
                controller.setCenter(GeoPoint(23.6, 121.0)) // Taiwan-wide fallback until a GPS fix lands

                val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
                locationOverlay.enableMyLocation()
                // The overlay still draws the accuracy circle (useful), but not its own default
                // person/direction icon — a 1x1 transparent bitmap for both hides it, since the
                // real marker is the direction arrow this composable manages separately below.
                val blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                locationOverlay.setPersonIcon(blank)
                locationOverlay.setDirectionIcon(blank)
                locationOverlay.runOnFirstFix {
                    post { controller.animateTo(locationOverlay.myLocation) }
                }
                overlays.add(locationOverlay)

                val marker = Marker(this).apply {
                    icon = ContextCompat.getDrawable(ctx, R.drawable.ic_direction_arrow)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setFlat(true) // rotation is screen-relative (compass heading), not tilted with the map
                    setInfoWindow(null)
                }
                overlays.add(marker)

                mapViewRef = this
                directionMarker = marker
                locationOverlayRef = locationOverlay

                val controller = HikeMapController(this, locationOverlay)
                controllerRef = controller
                onReady(controller)
            }
        },
        // Releases osmdroid's tile-fetch threads and GPS listener when this leaves composition —
        // without it they keep running in the background after the screen is gone.
        onRelease = { it.onDetach() },
    )

    // Keeps the arrow's position synced to the latest GPS fix — MyLocationNewOverlay updates
    // its own internal position on a fix, but our separate marker needs its own poll to follow.
    LaunchedEffect(locationOverlayRef) {
        val overlay = locationOverlayRef ?: return@LaunchedEffect
        while (true) {
            overlay.myLocation?.let { loc ->
                directionMarker?.position = loc
                mapViewRef?.invalidate()
            }
            delay(1000)
        }
    }

    // Device compass heading — independent of GPS, so the arrow turns the instant you turn the
    // phone rather than only when actually walking (GPS course-over-ground needs real movement).
    // In COMPASS_UP mode this also rotates the whole map; the arrow's own rotation is always
    // azimuth-minus-map-orientation so it reads correctly no matter which mode is active.
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientationOut = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationOut)
                val azimuthDeg = Math.toDegrees(orientationOut[0].toDouble()).toFloat()
                val mapView = mapViewRef ?: return
                if (controllerRef?.orientationMode == MapOrientationMode.COMPASS_UP) {
                    mapView.mapOrientation = azimuthDeg
                }
                directionMarker?.rotation = ((azimuthDeg - mapView.mapOrientation) + 360) % 360
                mapView.invalidate()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    // TRACK_UP: rotates the map to match GPS course-over-ground — only meaningful while actually
    // moving, unlike COMPASS_UP above which works standing still.
    LaunchedEffect(locationOverlayRef) {
        val overlay = locationOverlayRef ?: return@LaunchedEffect
        while (true) {
            if (controllerRef?.orientationMode == MapOrientationMode.TRACK_UP) {
                overlay.lastFix?.let { fix ->
                    if (fix.hasBearing()) {
                        mapViewRef?.mapOrientation = fix.bearing
                        mapViewRef?.invalidate()
                    }
                }
            }
            delay(1000)
        }
    }
}
