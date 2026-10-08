package tw.umaya.tracker.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.io.File

/**
 * GPX 編輯器 (assets/gpxedit.html, Leaflet): move / erase / hand-draw points, delete or keep a
 * selected stretch, split and merge segments, reverse (time runs backwards too), fix times and
 * heights (DEM), simplify, and add / edit / delete 航點. Saves over the file (the original kept once
 * as .orig) or as a new 「（編輯）」 file next to it; the saved path comes back as the result.
 */
class GpxEditorActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var file: File

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return })
        val title = intent.getStringExtra(EXTRA_TITLE) ?: file.nameWithoutExtension
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(title), "Android")
        }
        setContentView(webView)
        val html = assets.open("gpxedit.html").bufferedReader().use { it.readText() }
        webView.loadDataWithBaseURL("https://gpxedit.umaya.local/", html, "text/html", "utf-8", null)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.onAndroidBack ? (onAndroidBack(), 'ok') : 'none'") { if (it != "\"ok\"") finish() }
            }
        })
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private inner class Bridge(private val title: String) {
        @JavascriptInterface fun getGpx(): String = runCatching { file.readText() }.getOrDefault("")
        @JavascriptInterface fun getTitle(): String = title

        /** Writes the edited GPX; returns the saved path, or "" on failure. */
        @JavascriptInterface fun save(xml: String, asCopy: Boolean): String = runCatching {
            val target = if (asCopy) copyName(file) else file.also {
                val orig = File(it.path + ".orig")
                if (!orig.exists()) it.copyTo(orig) // the untouched recording, kept once
            }
            target.writeText(xml)
            runOnUiThread { setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SAVED, target.absolutePath)) }
            target.absolutePath
        }.getOrDefault("")

        @JavascriptInterface fun close() = runOnUiThread { finish() }
    }

    companion object {
        const val EXTRA_FILE = "file"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SAVED = "saved"

        fun intent(context: Context, file: File, title: String) =
            Intent(context, GpxEditorActivity::class.java).putExtra(EXTRA_FILE, file.absolutePath).putExtra(EXTRA_TITLE, title)

        /** 行程名稱（編輯）-yyyyMMdd-HHmmss.gpx — the stamp stays last so 記錄管理 still reads the date. */
        private fun copyName(f: File): File {
            val m = TrackRecord.stampName.find(f.nameWithoutExtension)
            val base = if (m != null) "${m.groupValues[1]}（編輯）-${m.groupValues[2]}-${m.groupValues[3]}" else "${f.nameWithoutExtension}（編輯）"
            var t = File(f.parentFile, "$base.gpx"); var n = 2
            while (t.exists()) t = File(f.parentFile, "$base-${n++}.gpx")
            return t
        }
    }
}
