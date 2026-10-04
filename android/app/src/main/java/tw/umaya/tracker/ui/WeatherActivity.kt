package tw.umaya.tracker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity

/**
 * ⛅ 天氣預報: assets/weather.html in a WebView — Windy-style cloud layers, 14-day forecast from
 * three models (Open-Meteo: ECMWF / GFS / ICON) and per-elevation rain/snow, for the hiker's
 * position or any point tapped on its map. Needs a network connection, like the forecast itself.
 */
class WeatherActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // remembers the chosen model
            webChromeClient = WebChromeClient()
            addJavascriptInterface(object {
                @JavascriptInterface fun close() = runOnUiThread { finish() }
            }, "Android")
        }
        setContentView(webView)
        val html = assets.open("weather.html").bufferedReader().use { it.readText() }
        // An https base (not file://) so fetch() to the forecast API and the map tiles behave like a
        // normal site; the start position rides along as the page's query string.
        val query = if (lat.isNaN() || lon.isNaN()) "" else "?lat=$lat&lon=$lon"
        webView.loadDataWithBaseURL("https://weather.umaya.local/$query", html, "text/html", "utf-8", null)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"

        fun start(context: Context, lat: Double?, lon: Double?) {
            context.startActivity(
                Intent(context, WeatherActivity::class.java).apply {
                    if (lat != null && lon != null) putExtra(EXTRA_LAT, lat).putExtra(EXTRA_LON, lon)
                }
            )
        }
    }
}
