package tw.umaya.tracker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.util.Base64
import android.util.Xml
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 3D 飛行回放 (Relive-style): plays one or more GPX/KML trails as a 3D terrain flyover in a WebView
 * (assets/relive.html, MapLibre + online terrain/imagery), and can record it to a video file.
 * The page pulls the track, its 航點 photos and hands recorded video back through [Bridge].
 */
class ReliveActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private val photos = mutableListOf<File>()
    private var videoTemp: File? = null
    private var videoMime = "video/mp4"

    private val saveMp4 = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { saveVideo(it) }
    private val saveWebm = registerForActivityResult(ActivityResultContracts.CreateDocument("video/webm")) { saveVideo(it) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // playback/recording must not sleep
        val files = intent.getStringArrayListExtra(EXTRA_FILES).orEmpty().map(::File).filter { it.exists() }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: files.firstOrNull()?.nameWithoutExtension ?: "軌跡"
        val trackJson = buildTrackJson(files, title)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(trackJson), "Android")
        }
        setContentView(webView)
        val html = assets.open("relive.html").bufferedReader().use { it.readText() }
        // An https base (not file://) so MapLibre's workers and tile fetches behave like a normal site.
        webView.loadDataWithBaseURL("https://relive.umaya.local/", html, "text/html", "utf-8", null)
    }

    override fun onDestroy() {
        webView.destroy()
        videoTemp?.delete()
        super.onDestroy()
    }

    private inner class Bridge(private val trackJson: String) {
        @JavascriptInterface fun getTrackJson(): String = trackJson

        /** A photo as a JPEG data URL, downscaled to at most 1280 px and turned upright per EXIF. */
        @JavascriptInterface fun getPhoto(index: Int): String {
            val file = photos.getOrNull(index) ?: return ""
            return runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
                var bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (degrees != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }.getOrDefault("")
        }

        @JavascriptInterface fun videoBegin(mime: String) {
            videoMime = if (mime.contains("mp4")) "video/mp4" else "video/webm"
            videoTemp?.delete()
            videoTemp = File(cacheDir, "relive-recording.tmp").apply { delete() }
        }

        @JavascriptInterface fun videoChunk(base64: String) {
            val f = videoTemp ?: return
            FileOutputStream(f, true).use { it.write(Base64.decode(base64, Base64.DEFAULT)) }
        }

        @JavascriptInterface fun videoEnd() {
            runOnUiThread {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(System.currentTimeMillis())
                if (videoMime == "video/mp4") saveMp4.launch("飛行回放_$stamp.mp4") else saveWebm.launch("飛行回放_$stamp.webm")
            }
        }
    }

    private fun saveVideo(uri: android.net.Uri?) {
        val tmp = videoTemp ?: return
        if (uri == null) {
            Toast.makeText(this, "已取消儲存影片", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        }.onSuccess {
            Toast.makeText(this, "影片已儲存（${tmp.length() / 1_000_000} MB）", Toast.LENGTH_LONG).show()
            tmp.delete()
        }.onFailure {
            Toast.makeText(this, "儲存失敗：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---- track → JSON for the page ----

    private fun buildTrackJson(files: List<File>, title: String): String {
        val segments = JSONArray()
        val waypoints = JSONArray()
        for (f in files.sortedBy { it.name }) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (text.contains("<kml", ignoreCase = true)) addKml(text, segments, waypoints)
            else addGpx(text, f.parentFile, segments, waypoints)
        }
        return JSONObject().put("title", title).put("segments", segments).put("waypoints", waypoints).toString()
    }

    private val isoParsers = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX")
        .map { SimpleDateFormat(it, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }

    private fun parseTime(s: String?): Long? {
        val v = s?.trim() ?: return null
        for (p in isoParsers) runCatching { return p.parse(v)!!.time }
        return null
    }

    private fun addGpx(text: String, dir: File?, segments: JSONArray, waypoints: JSONArray) {
        val p = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(text.trimStart('﻿', ' ', '\n', '\r', '\t')))
        }
        var seg: JSONArray? = null
        var point: JSONObject? = null // current trkpt or wpt
        var inWpt = false
        var photo: String? = null
        fun readText(): String {
            val sb = StringBuilder()
            while (p.next() == XmlPullParser.TEXT) sb.append(p.text)
            return sb.toString().trim()
        }
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                    "trkseg" -> seg = JSONArray()
                    "trkpt", "wpt" -> {
                        val lat = p.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = p.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        point = if (lat != null && lon != null) JSONObject().put("lat", lat).put("lon", lon) else null
                        inWpt = p.name.substringAfter(':') == "wpt"
                        photo = null
                    }
                    "ele" -> readText().toDoubleOrNull()?.let { point?.put("ele", it) }
                    "time" -> parseTime(readText())?.let { point?.put("t", it) }
                    "name" -> if (inWpt) point?.put("name", readText())
                    "link" -> if (inWpt) photo = p.getAttributeValue(null, "href")
                }
                XmlPullParser.END_TAG -> when (p.name.substringAfter(':')) {
                    "trkpt" -> { point?.let { seg?.put(it) }; point = null }
                    "trkseg" -> { seg?.takeIf { it.length() >= 2 }?.let(segments::put); seg = null }
                    "wpt" -> {
                        point?.let { w ->
                            val photoFile = photo?.takeIf { it.matches(Regex("(?i).*\\.(jpe?g|png|webp)$")) }
                                ?.let { if (it.startsWith("/")) File(it) else File(dir, it) }?.takeIf { it.exists() }
                            w.put("photo", if (photoFile != null) { photos += photoFile; photos.size - 1 } else -1)
                            if (!w.has("name")) w.put("name", "航點")
                            waypoints.put(w)
                        }
                        point = null; inWpt = false
                    }
                }
            }
        }
    }

    private fun addKml(text: String, segments: JSONArray, waypoints: JSONArray) {
        val parsed = parseRoute(text)
        for (s in parsed.segments) {
            segments.put(JSONArray().apply { s.forEach { put(JSONObject().put("lat", it.latitude).put("lon", it.longitude)) } })
        }
        for (l in parsed.labels) {
            waypoints.put(JSONObject().put("lat", l.point.latitude).put("lon", l.point.longitude).put("name", l.text).put("photo", -1))
        }
    }

    companion object {
        const val EXTRA_FILES = "files"
        const val EXTRA_TITLE = "title"

        fun start(context: Context, files: List<File>, title: String) {
            context.startActivity(
                Intent(context, ReliveActivity::class.java)
                    .putStringArrayListExtra(EXTRA_FILES, ArrayList(files.map { it.absolutePath }))
                    .putExtra(EXTRA_TITLE, title)
            )
        }
    }
}
