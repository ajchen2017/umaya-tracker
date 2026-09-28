package tw.umaya.tracker.ui

import android.content.Context
import android.graphics.Bitmap as AndroidBitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.osmdroid.util.GeoPoint
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView as MapsforgeView
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.hills.DemFolderFS
import org.mapsforge.map.layer.hills.HillsRenderConfig
import org.mapsforge.map.layer.hills.MemoryCachingHgtReaderTileSource
import org.mapsforge.map.layer.hills.SimpleShadingAlgorithm
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.model.common.Observer
import org.mapsforge.map.reader.MapFile
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.roundToInt
import org.mapsforge.map.rendertheme.ExternalRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleMenu
import org.mapsforge.map.scalebar.DefaultMapScaleBar
import tw.umaya.tracker.R
import tw.umaya.tracker.data.MapsforgeDownloader
import java.io.File

/** 一開始（無 GPS fix 時居中於全台範圍）與「回到目前位置」都固定用這個縮放層級。 */
private const val DEFAULT_ZOOM_LEVEL: Byte = 19
private const val MIN_ZOOM_LEVEL: Byte = 8
// Mapsforge has no sane ceiling of its own — an unbounded pinch gesture (or the +/- buttons held
// down) can push the zoom level into the double digits, at which point its own tile-coordinate
// math integer-overflows and crashes the LayerManager background thread (uncaught → whole app
// dies). 22 is comfortably past what MOI_OSM's own theme renders anything useful at.
private const val MAX_ZOOM_LEVEL: Byte = 22

/** One of MOI_OSM.xml's <stylemenu>「登山」(elmt-hiking) style's own 32 optional <layer>
 *  overlays — [id] must match the theme file's <layer id="..."> exactly. Transcribed from the
 *  downloaded theme rather than parsed at runtime (simpler, and this list only needs to change
 *  if rudymap.tw restructures the theme's overlay set, which is rare). [categories] are what
 *  actually get matched against each rule's own cat="..." attribute when this layer is enabled. */
data class ThemeLayer(val id: String, val label: String, val categories: List<String>, val defaultEnabled: Boolean)

/** elmt-hiking's 32 overlays, in the same order the theme file lists them. */
val HIKING_THEME_LAYERS = listOf(
    ThemeLayer("elmt-landcover", "地表圖案", listOf("landcover"), true),
    ThemeLayer("elmt-h_routes", "登山步道", listOf("h_routes"), true),
    ThemeLayer("elmt-h_s_routes", "建議路線", listOf("h_s_routes"), true),
    ThemeLayer("elmt-hiking-no_access", "封閉路線", listOf("hike-no_access"), false),
    ThemeLayer("elmt-hiking-tough", "艱難路線", listOf("hike-tough"), true),
    ThemeLayer("elmt-waymarks", "路標", listOf("waymarks"), true),
    ThemeLayer("elmt-settlements-h", "鄉鎮城市名稱", listOf("settlements"), true),
    ThemeLayer("elmt-landscapefeat-h", "地理景觀特徵", listOf("landscapefeat", "landscapename"), true),
    ThemeLayer("elmt-trail-label", "山徑名稱", listOf("trail-label"), true),
    ThemeLayer("elmt-surveypoint-h", "標石（基點/三角點）", listOf("surveypoint"), false),
    ThemeLayer("elmt-contour-c", "等高線", listOf("contour"), true),
    ThemeLayer("elmt-hiking_borders-h", "國家公園與林業保育區邊界", listOf("hiking_borders"), true),
    ThemeLayer("elmt-borders-h", "行政邊界和特殊區域", listOf("borders"), false),
    ThemeLayer("elmt-compartments-h", "林業保育署林班地", listOf("compartments"), false),
    ThemeLayer("elmt-amenities-h", "公共設施", listOf("amenities"), true),
    ThemeLayer("elmt-barriers-h", "障礙物", listOf("barriers"), true),
    ThemeLayer("elmt-outdoorsports-h", "戶外與運動", listOf("outdoor", "sports", "sled", "aerway", "huts", "viewpoint"), true),
    ThemeLayer("elmt-emergency-h", "緊急設施", listOf("emergency"), true),
    ThemeLayer("elmt-accommodation-h", "住宿旅館", listOf("accommodation", "huts"), false),
    ThemeLayer("elmt-restaurants-h", "餐廳和酒吧", listOf("restaurants"), false),
    ThemeLayer("elmt-shops-h", "商店與服務", listOf("shops", "fuel"), false),
    ThemeLayer("elmt-tourism-h", "旅遊和人文", listOf("tourism", "viewpoint"), true),
    ThemeLayer("elmt-pubtrans-h", "大眾運輸", listOf("pubtrans", "aerway"), true),
    ThemeLayer("elmt-car-h", "汽車", listOf("car", "fuel", "toll"), false),
    ThemeLayer("elmt-buildings-h", "特殊建築物", listOf("buildings"), true),
    ThemeLayer("elmt-mast-h", "基地台", listOf("mast"), false),
    ThemeLayer("elmt-track", "航跡", listOf("track"), true),
    // 主題預設是 false，但登山者要求 GPX/KML 航跡名稱要顯示，所以預設值在這裡覆寫成 true。
    ThemeLayer("elmt-track-label", "航跡名稱", listOf("track-label"), true),
    ThemeLayer("elmt-waypoint", "航點", listOf("waypoint"), true),
    ThemeLayer("elmt-waypoint-label", "航點名稱", listOf("waypoint-label"), true),
    ThemeLayer("elmt-ads", "防空避難處所", listOf("ads"), false),
    ThemeLayer("elmt-giant_tree", "台灣巨木", listOf("giant_tree"), false),
)

/** Resolves MOI_OSM.xml's <stylemenu> to the "elmt-hiking" base style (matching the VPS's own
 *  hiking.properties) plus whichever of its 32 optional overlays are in [enabledLayerIds] —
 *  without a menu callback at all, Mapsforge has no basis to exclude ANY layer's categories,
 *  rendering all 32 regardless of their own enabled="false" defaults (dense 三角點 clutter and
 *  the render cost that comes with it). */
private class HikingStyleMenuCallback(private val enabledLayerIds: Set<String>) : XmlRenderThemeMenuCallback {
    override fun getCategories(menu: XmlRenderThemeStyleMenu): MutableSet<String> {
        val categories = mutableSetOf<String>()
        val baseLayer = menu.getLayer(menu.defaultValue)
        if (baseLayer != null) categories.addAll(baseLayer.categories)
        for (layer in HIKING_THEME_LAYERS) {
            if (layer.id in enabledLayerIds) categories.addAll(layer.categories)
        }
        return categories
    }
}

private var graphicFactoryInitialized = false

/** AndroidGraphicFactory.createInstance must run once, before the first Mapsforge MapView is
 *  built — same one-time-setup shape as osmdroid's own Configuration step in HikeMapView.kt. */
private fun ensureGraphicFactory(context: Context) {
    if (graphicFactoryInitialized) return
    graphicFactoryInitialized = true
    AndroidGraphicFactory.createInstance(context.applicationContext)
}

/** Renders [R.drawable.ic_direction_arrow] rotated by [degrees] into a fresh Mapsforge bitmap —
 *  Marker has no native rotation (unlike osmdroid's), so the only way to turn the arrow is to
 *  redraw and swap the bitmap each time the heading changes meaningfully. */
private fun rotatedArrowBitmap(context: Context, degrees: Float) =
    AndroidGraphicFactory.convertToBitmap(
        BitmapDrawable(
            context.resources,
            AndroidBitmap.createBitmap(ARROW_SIZE_PX, ARROW_SIZE_PX, AndroidBitmap.Config.ARGB_8888).also { bmp ->
                val canvas = AndroidCanvas(bmp)
                val half = ARROW_SIZE_PX / 2f
                canvas.rotate(degrees, half, half)
                ContextCompat.getDrawable(context, R.drawable.ic_direction_arrow)?.apply {
                    setBounds(0, 0, ARROW_SIZE_PX, ARROW_SIZE_PX)
                    draw(canvas)
                }
            },
        )
    )

private const val ARROW_SIZE_PX = 96
private const val LORA_MARKER_SIZE_PX = 64

private fun loraNodeBitmap(context: Context, point: LoraDevicePoint) =
    AndroidGraphicFactory.convertToBitmap(
        BitmapDrawable(
            context.resources,
            AndroidBitmap.createBitmap(LORA_MARKER_SIZE_PX, LORA_MARKER_SIZE_PX, AndroidBitmap.Config.ARGB_8888).also { bmp ->
                val canvas = AndroidCanvas(bmp)
                val center = LORA_MARKER_SIZE_PX / 2f
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                val fill = if (point.isSelf) Color.rgb(24, 119, 242) else Color.rgb(28, 154, 94)
                paint.color = Color.WHITE
                canvas.drawCircle(center, center, center - 2f, paint)
                paint.color = fill
                canvas.drawCircle(center, center, center - 8f, paint)
                paint.color = Color.WHITE
                paint.strokeWidth = 5f
                paint.style = android.graphics.Paint.Style.STROKE
                canvas.drawCircle(center, center, center - 19f, paint)
            },
        )
    )

/** How the offline map renders within one zoom band; a change of band swaps the whole renderer. */
internal data class RenderBand(val textScale: Float, val hillshading: Boolean)

internal class RendererBundle(val band: RenderBand, val layer: TileRendererLayer, val cache: TileCache, private val mapFile: MapFile) {
    fun release() {
        layer.onDestroy()
        cache.destroy()
        mapFile.close()
    }
}

/** Thin handle mirroring [HikeMapController]'s shape so HikeScreen's zoom/recenter/GPS buttons
 *  don't need to know which rendering engine is currently active. Mapsforge itself has no
 *  built-in location provider (unlike osmdroid's MyLocationNewOverlay) — GPS fixes are pushed in
 *  from outside via [updateGpsFix]. The map itself always stays north-up: an earlier attempt to
 *  fake TRACK_UP/COMPASS_UP by rotating the oversized Android View caused an unresolved rendering
 *  bug (most of the map going blank) on top of the crash risk from an unbounded pinch-zoom, so
 *  it was reverted — stability over the rotation feature for now. */
class OfflineMapController internal constructor(
    internal val mapView: MapsforgeView,
    private val context: Context,
) {
    private val layers get() = mapView.layerManager.layers

    private var gpsMarker: Marker? = null
    private var gpsAccuracyCircle: Circle? = null
    private var lastFix: LatLong? = null
    private var lastHeadingDeg = 0f
    private val loadedRoutePolylines = mutableMapOf<String, List<Layer>>()
    private val loraDeviceMarkers = mutableMapOf<String, Marker>()
    private var scaleBar: DefaultMapScaleBar? = null
    /** Builds a fresh renderer (own TileCache + MapFile) at a given text scale. */
    internal var rendererFactory: ((RenderBand) -> RendererBundle)? = null
    private var renderer: RendererBundle? = null
    internal var baseFontScale = 1f
    /** The pack's own coverage — auto-centering on the phone only makes sense inside it. */
    internal var coverage: BoundingBox? = null
    fun covers(lat: Double, lon: Double): Boolean = coverage?.contains(lat, lon) ?: true
    fun showWholePack() {
        val box = coverage ?: return
        val pos = mapView.model.mapViewPosition
        pos.setCenter(box.centerPoint)
        pos.setZoomLevel(10)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val pendingReleases = mutableListOf<Runnable>()
    private val applyTextScaleRunnable = Runnable { applyTextScaleForZoom() }

    fun zoomIn() {
        val pos = mapView.model.mapViewPosition
        pos.setZoomLevel((pos.zoomLevel + 1).coerceAtMost(MAX_ZOOM_LEVEL.toInt()).toByte())
    }
    fun zoomOut() {
        val pos = mapView.model.mapViewPosition
        pos.setZoomLevel((pos.zoomLevel - 1).coerceAtLeast(MIN_ZOOM_LEVEL.toInt()).toByte())
    }
    fun animateTo(lat: Double, lon: Double) {
        mapView.model.mapViewPosition.animateTo(LatLong(lat, lon))
    }
    fun zoomLevel(): Double = mapView.model.mapViewPosition.zoomLevel.toDouble()

    /** 文字大小隨縮放層級分段變化：[baseFontScale] 是 zoom 18 以上的大小，越往外縮越小。
     *  文字是畫進圖磚點陣圖裡的，改 textScale 不會重畫已快取的圖磚；而 TileCache.purge() 會跟
     *  Mapsforge 背景執行緒搶資料導致 crash（2026-08-29 log）。所以每一段各用一個獨立的快取目錄，
     *  跨段時直接換掉整個渲染圖層，舊圖層延後幾秒、等它的背景工作結束後才釋放。 */
    private fun bandFor(zoom: Int): RenderBand = RenderBand(
        textScale = baseFontScale * when {
            zoom <= 11 -> 0.5f
            zoom <= 13 -> 0.6f
            zoom <= 15 -> 0.72f
            zoom <= 17 -> 0.86f
            else -> 1f
        },
        // At zoom ≤9 the DEM's 1°×1° tiles shade the sea too, showing as tinted rectangles.
        hillshading = zoom >= 10,
    )

    /** Debounced so a pinch passing through several zoom levels only swaps renderers once. */
    internal fun onPositionChanged() {
        handler.removeCallbacks(applyTextScaleRunnable)
        handler.postDelayed(applyTextScaleRunnable, 600)
        handler.post { applyRouteLabelMode() }
    }

    /** A route label marker with both looks: dot + text (zoomed in) and the bare dot (zoomed out). */
    private class RouteLabelMarker(val marker: Marker, val full: RouteLabelBitmap, val dot: RouteLabelBitmap)
    private val routeLabelMarkers = mutableMapOf<String, List<RouteLabelMarker>>()
    private var routeLabelsShowText: Boolean? = null

    private fun Marker.show(look: RouteLabelBitmap) {
        bitmap = AndroidGraphicFactory.convertToBitmap(BitmapDrawable(context.resources, look.bitmap))
        // Mapsforge centres the bitmap on the point; shift it so the dot, not the centre, sits there.
        horizontalOffset = (look.bitmap.width / 2f - look.dotX).toInt()
        verticalOffset = (look.bitmap.height / 2f - look.dotY).toInt()
    }

    private fun applyRouteLabelMode(force: Boolean = false) {
        val showText = mapView.model.mapViewPosition.zoomLevel >= ROUTE_LABEL_TEXT_MIN_ZOOM
        if (!force && showText == routeLabelsShowText) return
        routeLabelsShowText = showText
        routeLabelMarkers.values.flatten().forEach { it.marker.show(if (showText) it.full else it.dot) }
        mapView.layerManager.redrawLayers()
    }

    internal fun applyTextScaleForZoom() {
        val factory = rendererFactory ?: return
        val band = bandFor(mapView.model.mapViewPosition.zoomLevel.toInt())
        val old = renderer
        if (old != null && old.band == band) return
        val new = factory(band)
        if (old != null) layers.remove(old.layer, false)
        layers.add(0, new.layer, true)
        renderer = new
        if (old != null) {
            val release = object : Runnable {
                override fun run() {
                    pendingReleases.remove(this)
                    old.release()
                }
            }
            pendingReleases += release
            handler.postDelayed(release, 3000)
        }
    }

    /** Frees every renderer this controller built; the view's own destroyAll() handles the rest. */
    internal fun release() {
        handler.removeCallbacksAndMessages(null)
        pendingReleases.toList().forEach { it.run() }
        renderer?.cache?.destroy()
    }

    fun currentFix(): LatLong? = lastFix

    fun recenterOnGps() {
        // Plain setCenter+setZoomLevel, not moveCenterAndZoom — that one animates, and on this
        // device the animation appeared to never actually land (model updated, screen didn't).
        lastFix?.let {
            val pos = mapView.model.mapViewPosition
            pos.setCenter(it)
            pos.setZoomLevel(DEFAULT_ZOOM_LEVEL)
        }
    }

    /** 地圖比例尺 開/關 — Mapsforge's own scale bar, not osmdroid's ScaleBarOverlay. */
    fun setScaleBarEnabled(enabled: Boolean) {
        if (enabled) {
            if (scaleBar == null) {
                scaleBar = DefaultMapScaleBar(
                    mapView.model.mapViewPosition, mapView.model.mapViewDimension,
                    AndroidGraphicFactory.INSTANCE, mapView.model.displayModel,
                ).also { mapView.setMapScaleBar(it) }
            }
        } else {
            mapView.setMapScaleBar(null)
            scaleBar = null
        }
    }

    /** Fed from a FusedLocationProviderClient subscription in HikeScreen (see MainActivity.kt) —
     *  Mapsforge itself has no location layer like osmdroid's MyLocationNewOverlay. */
    fun updateGpsFix(lat: Double, lon: Double, accuracyMeters: Float) {
        val point = LatLong(lat, lon)
        lastFix = point
        val circle = gpsAccuracyCircle
        if (circle == null) {
            val fillPaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                setColor(Color.argb(60, 45, 125, 210))
                setStyle(Style.FILL)
            }
            val strokePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                setColor(Color.argb(160, 45, 125, 210))
                setStyle(Style.STROKE)
                setStrokeWidth(2f)
            }
            gpsAccuracyCircle = Circle(point, accuracyMeters, fillPaint, strokePaint).also { layers.add(it) }
        } else {
            circle.setLatLong(point)
            circle.setRadius(accuracyMeters)
        }
        redrawArrow(point)
    }

    /** Device compass heading in degrees — the map never rotates, so this is just the raw azimuth. */
    fun setHeading(degrees: Float) {
        if (kotlin.math.abs(degrees - lastHeadingDeg) < 1.5f) return
        lastHeadingDeg = degrees
        lastFix?.let { redrawArrow(it) }
    }

    private fun redrawArrow(point: LatLong) {
        val bitmap = rotatedArrowBitmap(context, lastHeadingDeg)
        val marker = gpsMarker
        if (marker == null) {
            gpsMarker = Marker(point, bitmap, 0, 0).also { layers.add(it) }
        } else {
            marker.setBitmap(bitmap)
            marker.setLatLong(point)
        }
        // Marker.setBitmap doesn't reliably trigger a repaint on its own — force one so a
        // compass-only heading change (no position/zoom change) actually reaches the screen.
        mapView.layerManager.redrawLayers()
    }

    /** Turning GPS off tears the marker/circle down entirely rather than just hiding them, since
     *  a stale position left visible while GPS is off would be misleading on a hiking app. */
    fun setGpsVisible(visible: Boolean) {
        if (visible) return
        gpsMarker?.let { layers.remove(it); it.onDestroy() }
        gpsAccuracyCircle?.let { layers.remove(it) }
        gpsMarker = null
        gpsAccuracyCircle = null
        lastFix = null
    }

    /** One Polyline per segment — segments are NOT connected to each other (see RouteParser.kt),
     *  since joining them would draw a straight line across a real gap between recordings. */
    fun addLoadedRoute(id: String, route: ParsedRoute) {
        removeLoadedRoute(id)
        val polylines: List<Layer> = route.segments.map { points ->
            val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                setColor(LOADED_ROUTE_COLOR)
                setStyle(Style.STROKE)
                setStrokeWidth(6f)
            }
            Polyline(paint, AndroidGraphicFactory.INSTANCE).apply {
                addPoints(points.map { LatLong(it.latitude, it.longitude) })
            }
        }
        val dot = routeDotBitmap(context)
        val labels = route.labels.map { label ->
            val full = routeLabelBitmap(context, label.text)
            val marker = Marker(LatLong(label.point.latitude, label.point.longitude), null, 0, 0)
            RouteLabelMarker(marker, full, dot)
        }
        routeLabelMarkers[id] = labels
        val added = polylines + labels.map { it.marker }
        loadedRoutePolylines[id] = added
        applyRouteLabelMode(force = true)
        added.forEach { layers.add(it) }
    }

    private var recordingLine: Polyline? = null
    /** The GPX trail being recorded right now — its own layer, untouched by loaded-route redraws. */
    fun setRecordingTrack(points: List<GeoPoint>) {
        recordingLine?.let { layers.remove(it) }
        recordingLine = null
        if (points.size < 2) return
        val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(RECORDING_TRACK_COLOR)
            setStyle(Style.STROKE)
            setStrokeWidth(7f)
        }
        recordingLine = Polyline(paint, AndroidGraphicFactory.INSTANCE).apply {
            addPoints(points.map { LatLong(it.latitude, it.longitude) })
        }.also { layers.add(it) }
    }

    fun removeLoadedRoute(id: String) {
        loadedRoutePolylines.remove(id)?.forEach { layers.remove(it) }
        routeLabelMarkers.remove(id)
    }

    fun clearAllLoadedRoutes() {
        loadedRoutePolylines.values.forEach { polylines -> polylines.forEach { layers.remove(it) } }
        loadedRoutePolylines.clear()
        routeLabelMarkers.clear()
    }

    fun setLoraDevicePoints(points: List<LoraDevicePoint>) {
        val activeIds = points.map { it.id }.toSet()
        val removed = loraDeviceMarkers.keys - activeIds
        removed.forEach { id ->
            loraDeviceMarkers.remove(id)?.let {
                layers.remove(it)
                it.onDestroy()
            }
        }
        points.forEach { point ->
            val latLong = LatLong(point.latitude, point.longitude)
            val marker = loraDeviceMarkers[point.id]
            if (marker == null) {
                loraDeviceMarkers[point.id] = Marker(latLong, loraNodeBitmap(context, point), 0, 0)
                    .also { layers.add(it) }
            } else {
                marker.setLatLong(latLong)
                marker.setBitmap(loraNodeBitmap(context, point))
            }
        }
        mapView.layerManager.redrawLayers()
    }
}

/**
 * Renders the downloaded Mapsforge .map file locally — real offline vector rendering (magic
 * offline-and-fast, no network, no per-tile gaps), replacing osmdroid's raster-tile approach for
 * 魯地圖 once [MapsforgeDownloader.hasCoreMapData] is true. Theme (element scale/font/hillshade)
 * comes from the downloaded theme/DEM files. Always north-up — see [OfflineMapController]'s
 * kdoc for why the rotation attempt was reverted.
 */
@Composable
fun OfflineMapView(
    modifier: Modifier = Modifier,
    downloader: MapsforgeDownloader,
    pack: MapsforgeDownloader.OfflinePack,
    elementScale: Float = 0.8f,
    fontScale: Float = 2.8f, // hiking.properties' own text-scale=1.4, doubled per hiker request
    hillshadingEnabled: Boolean = true,
    enabledLayerIds: Set<String> = HIKING_THEME_LAYERS.filter { it.defaultEnabled }.map { it.id }.toSet(),
    onReady: (OfflineMapController) -> Unit,
) {
    val context = LocalContext.current
    ensureGraphicFactory(context)

    var controllerRef by remember { mutableStateOf<OfflineMapController?>(null) }

    // Device compass heading — only ever turns the arrow; the map itself stays north-up.
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientationOut = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationOut)
                val azimuthDeg = ((Math.toDegrees(orientationOut[0].toDouble()).toFloat()) + 360) % 360
                controllerRef?.setHeading(azimuthDeg)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            MapsforgeView(ctx).apply {
                setBuiltInZoomControls(false) // custom zoom buttons in 右側導航區, matching the osmdroid map
                model.displayModel.setUserScaleFactor(elementScale)

                val demDir = downloader.demDir(pack)

                val hillsConfig = if (hillshadingEnabled && demDir != null && demDir.exists() &&
                    demDir.listFiles()?.isNotEmpty() == true
                ) {
                    HillsRenderConfig(
                        MemoryCachingHgtReaderTileSource(
                            DemFolderFS(demDir),
                            SimpleShadingAlgorithm(0.1, 0.666), // matches mapsforgesrv's own config (theme/ + server.properties)
                            AndroidGraphicFactory.INSTANCE,
                        )
                    ).also { it.indexOnThread() }
                } else null

                // Theme is expected alongside the map file (downloaded together) — no built-in
                // fallback style, since falling back silently would render with a completely
                // different look than what the hiker asked to download.
                //
                // Without a menu callback, Mapsforge has no way to know which of the theme's 84
                // <layer> entries to actually include — MOI_OSM.xml defines a <stylemenu> whose
                // default style ("elmt-hiking", matching the VPS's own hiking.properties) has 31
                // optional overlay sub-layers OFF by default (survey points/三角點, accommodation,
                // restaurants, shops, car POIs, ads, etc.). Leaving the callback unset appears to
                // render everything regardless of each layer's own enabled="false" — exactly the
                // dense unwanted point clutter (and the extra render cost that comes with it).
                val theme = ExternalRenderTheme(downloader.themeFile, HikingStyleMenuCallback(enabledLayerIds))

                fun createRenderer(band: RenderBand): RendererBundle {
                    val mapFile = MapFile(downloader.mapFile(pack))
                    val hills = hillsConfig?.takeIf { band.hillshading }
                    // Cache id keyed by everything baked into the tile bitmap (text scale, whether it
                    // was shaded — the cache persists across restarts) and by pack (a blank tile from
                    // one pack's .map must never be reused where another pack actually has data).
                    val tileCache: TileCache = AndroidUtil.createTileCache(
                        ctx,
                        "offline-map-tiles-${pack.mapFileName.removeSuffix(".map")}-fs${(band.textScale * 10).roundToInt()}-h${if (hills != null) 1 else 0}",
                        model.displayModel.tileSize, 1f,
                        4.0, // overzoom factor — standard Mapsforge sample default
                        true,
                    )
                    val layer = if (hills != null) {
                        AndroidUtil.createTileRendererLayer(
                            tileCache, model.mapViewPosition, mapFile, theme,
                            false, true, false, hills,
                        )
                    } else {
                        AndroidUtil.createTileRendererLayer(
                            tileCache, model.mapViewPosition, mapFile, theme,
                            false, true, false,
                        )
                    }
                    layer.textScale = band.textScale
                    return RendererBundle(band, layer, tileCache, mapFile)
                }

                val bounds = MapFile(downloader.mapFile(pack)).let { f -> f.boundingBox().also { f.close() } }
                model.mapViewPosition.zoomLevelMin = MIN_ZOOM_LEVEL
                model.mapViewPosition.zoomLevelMax = MAX_ZOOM_LEVEL
                model.mapViewPosition.setCenter(bounds.centerPoint)
                model.mapViewPosition.setZoomLevel(DEFAULT_ZOOM_LEVEL)
                // Deferred: setting the zoom/center before the View has been measured (dimension
                // still 0x0 at this point in factory{}) appeared to get silently overridden once
                // layout actually happened — re-applying post-layout is what actually stuck.
                post {
                    // NOT setMapLimit(bounds) — it kept re-snapping the view back to fit the
                    // whole limit region (undoing recenterOnGps a few seconds after it ran),
                    // so panning outside Taiwan's extent is unrestricted for now.
                    model.mapViewPosition.setCenter(bounds.centerPoint)
                    model.mapViewPosition.setZoomLevel(DEFAULT_ZOOM_LEVEL)
                    // MOI_OSM.xml declares map-background-outside="#FFFFFF" for area beyond the
                    // .map file's own data — but that's the theme's fallback for a *rendered*
                    // empty tile; DisplayModel's own canvas-clear color is separate and defaults
                    // to a light blue that showed everywhere outside the landmass at zoom 8.
                    // Match it to the theme's own choice so the whole map uses one background.
                    model.displayModel.backgroundColor = android.graphics.Color.WHITE
                    invalidate()
                }

                val controller = OfflineMapController(this, context)
                controller.baseFontScale = fontScale
                controller.coverage = bounds
                controller.rendererFactory = ::createRenderer
                controller.applyTextScaleForZoom()
                model.mapViewPosition.addObserver(Observer { controller.onPositionChanged() })
                controllerRef = controller
                onReady(controller)
            }
        },
        onRelease = {
            controllerRef?.release()
            it.destroyAll()
        },
    )
}
