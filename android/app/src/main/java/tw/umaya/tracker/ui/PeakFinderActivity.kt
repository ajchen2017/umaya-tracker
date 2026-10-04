package tw.umaya.tracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.umaya.tracker.peak.Peak
import tw.umaya.tracker.peak.PeakData
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * 🏔️ 山峰辨識 (PeakFinder-style AR): the camera view with the computed white skyline and peak names
 * (中文 + English + height) on top. Data comes from [PeakData] around the GPS position (downloaded and
 * cached on first use there). Drag to fine-tune heading/pitch — phone compasses are often a few
 * degrees off. Zoom chips cover the phone's wide…tele range; 📷 saves photo + overlay to the gallery.
 */
class PeakFinderActivity : ComponentActivity(), SensorEventListener {

    private val data by lazy { PeakData(this) }
    private lateinit var sensors: SensorManager

    // device → world rotation (east, magnetic north, up), low-pass filtered
    private val rot = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    private var hasRot = false
    private var frame by mutableIntStateOf(0)

    private var status by mutableStateOf("等待 GPS 定位…")
    private var peaks: List<Peak> = emptyList()
    private var skyline: FloatArray? = null
    private var eye: Triple<Double, Double, Double>? = null // lat, lon, eye elevation (m)
    private var declination = 0f
    private var preparedAt: Pair<Double, Double>? = null
    private var skylineAt: Pair<Double, Double>? = null
    private var busy = false

    private var headingCalib by mutableFloatStateOf(0f)
    private var pitchCalib by mutableFloatStateOf(0f)
    private var zoom by mutableFloatStateOf(1f)
    private var zoomRange by mutableStateOf(1f..1f)
    private var camGranted by mutableStateOf(false)

    private var tanLong = tan(Math.toRadians(32.0)).toFloat()  // half-FOV tangents of the main back camera
    private var tanShort = tan(Math.toRadians(25.0)).toFloat()
    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> camGranted = ok }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { onLocation(it.latitude, it.longitude, it.altitude) } }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        readCameraFov()
        camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!camGranted) askCamera.launch(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val fused = LocationServices.getFusedLocationProviderClient(this)
            fused.lastLocation.addOnSuccessListener { it?.let { l -> onLocation(l.latitude, l.longitude, l.altitude) } }
            fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000).build(), locationCallback, mainLooper)
        } else status = "沒有定位權限"

        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() { sensors.unregisterListener(this); super.onPause() }

    override fun onDestroy() {
        LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val r = FloatArray(9); SensorManager.getRotationMatrixFromVector(r, e.values)
        val a = if (hasRot) 0.25f else 1f
        for (i in 0..8) rot[i] += (r[i] - rot[i]) * a
        hasRot = true
        frame++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun readCameraFov() = runCatching {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val ch = cm.getCameraCharacteristics(id)
        val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
        val s = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
        tanLong = max(s.width, s.height) / (2 * f); tanShort = minOf(s.width, s.height) / (2 * f)
    }

    // ---- data ----

    private fun onLocation(lat: Double, lon: Double, gpsAlt: Double) {
        declination = GeomagneticField(lat.toFloat(), lon.toFloat(), gpsAlt.toFloat(), System.currentTimeMillis()).declination
        if (busy) return
        val prepared = preparedAt
        val needData = prepared == null || PeakData.distance(prepared.first, prepared.second, lat, lon) > 5_000
        val sky = skylineAt
        val needSky = needData || sky == null || PeakData.distance(sky.first, sky.second, lat, lon) > 150
        if (!needSky) return
        busy = true
        lifecycleScope.launch {
            try {
                if (needData) {
                    peaks = withContext(Dispatchers.IO) { data.prepare(lat, lon) { msg -> runOnUiThread { status = msg } } }
                    preparedAt = lat to lon
                }
                status = "計算稜線…"
                val ground = withContext(Dispatchers.Default) { data.elevation(lat, lon) }
                val eyeEle = (ground ?: gpsAlt) + 1.7
                val sl = withContext(Dispatchers.Default) { data.skyline(lat, lon, eyeEle) }
                eye = Triple(lat, lon, eyeEle); skyline = sl; skylineAt = lat to lon
                status = if (ground == null) "這一帶沒有地形資料（需要網路下載）" else "${peaks.size} 座山峰・海拔 ${eyeEle.toInt()} m"
                frame++
            } catch (e: Exception) {
                status = "資料準備失敗：${e.message}"
            } finally { busy = false }
        }
    }

    // ---- projection ----

    /** Scale (px per unit tangent) so the overlay matches what's on screen / in the photo. */
    private fun scaleFor(w: Float, h: Float, fill: Boolean): Float =
        (if (fill) max(w / (2 * tanShort), h / (2 * tanLong)) else h / (2 * tanLong)) * zoom

    /** Screen position of true azimuth/elevation (deg), or null if behind the camera. */
    private fun project(r: FloatArray, azTrue: Double, el: Double, w: Float, h: Float, k: Float): Pair<Float, Float>? {
        val az = Math.toRadians(azTrue - declination + headingCalib); val e = Math.toRadians(el + pitchCalib)
        val dx = sin(az) * cos(e); val dy = cos(az) * cos(e); val dz = sin(e)
        val xc = dx * r[0] + dy * r[3] + dz * r[6]          // · device right
        val yc = dx * r[1] + dy * r[4] + dz * r[7]          // · device up
        val zc = -(dx * r[2] + dy * r[5] + dz * r[8])       // · camera forward (−device z)
        if (zc < 0.05) return null
        return (w / 2 + (xc / zc * k).toFloat()) to (h / 2 - (yc / zc * k).toFloat())
    }

    /** White skyline + peak labels, drawn the same way on screen and onto a captured photo. */
    private fun drawOverlay(c: android.graphics.Canvas, w: Float, h: Float, k: Float, r: FloatArray, unit: Float) {
        val sl = skyline ?: return
        val (lat, lon, eyeEle) = eye ?: return
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; strokeWidth = 2.2f * unit; style = Paint.Style.STROKE
            setShadowLayer(2.5f * unit, 0f, 0f, android.graphics.Color.argb(200, 0, 0, 0))
        }
        val path = android.graphics.Path(); var pen = false; var lastX = 0f
        for (i in 0..sl.size) {
            val a = i % sl.size
            val p = project(r, a * PeakData.AZ_STEP, sl[a].toDouble(), w, h, k)
            if (p == null || (pen && abs(p.first - lastX) > w)) { pen = false; continue }
            if (pen) path.lineTo(p.first, p.second) else path.moveTo(p.first, p.second)
            pen = true; lastX = p.first
        }
        c.drawPath(path, line)

        // peaks: visible (not hidden behind the skyline), nearest/highest first, decluttered by x
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; textSize = 13f * unit; isFakeBoldText = true
            setShadowLayer(3f * unit, 0f, 0f, android.graphics.Color.BLACK)
        }
        val tick = Paint(line).apply { strokeWidth = 1.2f * unit }
        val visible = peaks.mapNotNull { pk ->
            val (ang, d) = PeakData.angleTo(lat, lon, eyeEle, pk.lat, pk.lon, pk.ele)
            if (d > PeakData.RADIUS_KM * 1000 || d < 200) return@mapNotNull null
            val brg = PeakData.bearing(lat, lon, pk.lat, pk.lon)
            val sky = sl[((brg / PeakData.AZ_STEP).toInt()) % sl.size]
            if (ang < sky - 0.35) return@mapNotNull null
            val p = project(r, brg, ang, w, h, k) ?: return@mapNotNull null
            if (p.first < -20 || p.first > w + 20) return@mapNotNull null
            Triple(pk, p, d)
        }.sortedByDescending { (pk, _, d) -> pk.ele - d / 50 }
        // Labels stay level with the real horizon however the phone is turned (portrait, tilted,
        // landscape): `up` is the world's up direction as it appears on screen, `along` the horizon.
        val ux = r[6].toDouble(); val uy = r[7].toDouble(); val un = kotlin.math.hypot(ux, uy).coerceAtLeast(1e-6)
        val upX = (ux / un).toFloat(); val upY = (-uy / un).toFloat()      // screen vector pointing "up"
        val alX = -upY; val alY = upX                                       // screen vector along the horizon
        val angle = Math.toDegrees(kotlin.math.atan2(ux, uy)).toFloat()     // canvas rotation for level text
        val small = Paint(text).apply { textSize = 10.5f * unit; isFakeBoldText = false }
        val rowH = 30 * unit; val gap = 6 * unit
        val rows = ArrayList<MutableList<Pair<Float, Float>>>()             // per row: occupied spans along the horizon
        var shown = 0
        for ((pk, p, d) in visible) {
            val line1 = "${pk.zh ?: pk.en} ${pk.ele.toInt()} m"
            val line2 = listOfNotNull(pk.en.takeIf { pk.zh != null }, "%.1f km".format(d / 1000)).joinToString("・")
            val half = maxOf(text.measureText(line1), small.measureText(line2)) / 2 + gap
            val u = p.first * alX + p.second * alY                         // position along the horizon
            // lowest row where this label's span is free
            val row = (0 until 6).firstOrNull { k -> rows.getOrNull(k)?.none { (a0, a1) -> u + half > a0 && u - half < a1 } ?: true } ?: continue
            while (rows.size <= row) rows += mutableListOf<Pair<Float, Float>>()
            rows[row] += (u - half) to (u + half)
            val lift = 22 * unit + row * rowH
            val tx = p.first + upX * lift; val ty = p.second + upY * lift  // label anchor (bottom centre)
            c.drawLine(p.first + upX * 3 * unit, p.second + upY * 3 * unit, tx, ty, tick)
            c.drawCircle(p.first, p.second, 2.5f * unit, tick)
            c.save(); c.rotate(angle, tx, ty)
            text.textAlign = Paint.Align.CENTER; small.textAlign = Paint.Align.CENTER
            c.drawText(line1, tx, ty - 13 * unit, text)
            c.drawText(line2, tx, ty - 2 * unit, small)
            c.restore()
            if (++shown >= 40) break
        }
    }

    // ---- camera ----

    private fun bindCamera(view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            imageCapture = capture
            camera?.cameraInfo?.zoomState?.observe(this) { z -> zoomRange = z.minZoomRatio..z.maxZoomRatio; zoom = z.zoomRatio }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyZoom(z: Float) { camera?.cameraControl?.setZoomRatio(z.coerceIn(zoomRange.start, zoomRange.endInclusive)) }

    private fun takePhoto() {
        val cap = imageCapture ?: run { Toast.makeText(this, "相機尚未就緒", Toast.LENGTH_SHORT).show(); return }
        val r = rot.copyOf()
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val raw = image.toBitmap(); image.close()
                lifecycleScope.launch {
                    val saved = withContext(Dispatchers.Default) {
                        val bmp = if (rotation != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                            .also { raw.recycle() } else raw
                        val out = bmp.copy(Bitmap.Config.ARGB_8888, true); bmp.recycle()
                        val w = out.width.toFloat(); val h = out.height.toFloat()
                        drawOverlay(android.graphics.Canvas(out), w, h, scaleFor(w, h, fill = false), r, unit = w / 400f)
                        save(out).also { out.recycle() }
                    }
                    Toast.makeText(this@PeakFinderActivity, if (saved) "已存到相簿 Pictures/PeakFinder" else "儲存失敗", Toast.LENGTH_LONG).show()
                }
            }
            override fun onError(e: ImageCaptureException) {
                Toast.makeText(this@PeakFinderActivity, "拍照失敗：${e.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun save(bmp: Bitmap): Boolean = runCatching {
        val name = "PeakFinder-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PeakFinder")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } else {
            val dir = java.io.File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "PeakFinder").apply { mkdirs() }
            java.io.File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }
        true
    }.getOrDefault(false)

    // ---- UI ----

    @androidx.compose.runtime.Composable
    private fun Screen() {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (camGranted) AndroidView(factory = { ctx -> PreviewView(ctx).also { bindCamera(it) } }, modifier = Modifier.fillMaxSize())
            Canvas(
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGestures { _, drag ->
                        val k = scaleFor(size.width.toFloat(), size.height.toFloat(), fill = true)
                        headingCalib -= Math.toDegrees((drag.x / k).toDouble()).toFloat()
                        pitchCalib -= Math.toDegrees((drag.y / k).toDouble()).toFloat()
                    }
                },
            ) {
                @Suppress("UNUSED_EXPRESSION") frame // redraw on every sensor tick
                val k = scaleFor(size.width, size.height, fill = true)
                drawContext.canvas.nativeCanvas.let { drawOverlay(it, size.width, size.height, k, rot.copyOf(), unit = density) }
            }

            // top bar
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 8.dp).align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✕", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable { finish() }.padding(end = 12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("🏔️ 山峰辨識", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    @Suppress("UNUSED_EXPRESSION") frame
                    val headTrue = currentHeading()
                    Text("$status・方位 ${headTrue.toInt()}°", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                }
                if (headingCalib != 0f || pitchCalib != 0f) Text(
                    "⟲ 校正 ${"%+.1f".format(headingCalib)}°", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.background(Color(0x44FFFFFF), RoundedCornerShape(8.dp)).clickable { headingCalib = 0f; pitchCalib = 0f }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                "拖動畫面可微調方位與仰角，讓白色稜線對齊真實山稜",
                color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp).background(Color(0x66000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            )

            // bottom: zoom chips + shutter
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val stops = (listOf(zoomRange.start) + listOf(1f, 2f, 3f, 5f, 10f)).filter { it in zoomRange }.distinct()
                    stops.forEach { z ->
                        val on = abs(zoom - z) < 0.05f
                        Text(
                            (if (z < 1f) "廣角 " else if (z > 1f) "望遠 " else "") + (if (z % 1f == 0f) "${z.toInt()}x" else "%.1fx".format(z)),
                            color = if (on) Color.Black else Color.White, fontSize = 12.sp,
                            modifier = Modifier.background(if (on) Color(0xFFFFD54F) else Color(0x66000000), RoundedCornerShape(14.dp))
                                .clickable { applyZoom(z) }.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier.padding(top = 14.dp).size(68.dp).border(4.dp, Color.White, CircleShape).padding(6.dp)
                        .background(Color.White, CircleShape).clickable { takePhoto() },
                )
            }
        }
    }

    /** True heading of the camera, after the hiker's calibration. */
    private fun currentHeading(): Double {
        val fx = -rot[2]; val fy = -rot[5]
        return (Math.toDegrees(kotlin.math.atan2(fx.toDouble(), fy.toDouble())) + declination - headingCalib + 360) % 360
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, PeakFinderActivity::class.java))
    }
}
