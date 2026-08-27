package tw.umaya.tracker.guardian

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Thin WebView wrapper around the existing family/guardian page (tracker.umaya.tw/t/:token) —
 * the page itself already has everything (魯地圖/線上地圖 toggle, elevation chart, SOS alert,
 * settings); this app just gives it an icon and a launcher entry so a guardian doesn't have to
 * keep a browser tab/bookmark around.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var inputContainer: android.view.View
    private lateinit var webContainer: FrameLayout
    private lateinit var linkInput: EditText
    private lateinit var webView: WebView

    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val requestLocationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
        pendingGeoCallback = null
        pendingGeoOrigin = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("guardian", MODE_PRIVATE)

        inputContainer = findViewById(R.id.inputContainer)
        webContainer = findViewById(R.id.webContainer)
        linkInput = findViewById(R.id.linkInput)
        webView = findViewById(R.id.webView)
        val btnGo = findViewById<Button>(R.id.btnGo)
        val btnChangeLink = findViewById<ImageButton>(R.id.btnChangeLink)

        setupWebView()

        btnGo.setOnClickListener { tryLoadLink(linkInput.text.toString()) }
        btnChangeLink.setOnClickListener {
            webContainer.visibility = android.view.View.GONE
            inputContainer.visibility = android.view.View.VISIBLE
        }

        val saved = prefs.getString(KEY_LINK, null)
        if (saved != null) {
            linkInput.setText(saved)
            loadLink(saved)
        }
    }

    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true // localStorage — trackColor/travelMode/signalPointsEnabled etc.
        webView.settings.setGeolocationEnabled(true)
        webView.webViewClient = WebViewClient() // keep navigation inside the app, no external browser hop
        webView.webChromeClient = object : WebChromeClient() {
            // The page falls back to the guardian's own location before any hike point has
            // arrived — grant it only if we already hold the permission, otherwise ask once.
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    callback?.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }
    }

    /** Accepts either a full share URL or a bare token, normalizes to the full page URL. */
    private fun tryLoadLink(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(this, "請輸入分享連結", Toast.LENGTH_SHORT).show()
            return
        }
        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("tracker.umaya.tw") -> "https://$trimmed"
            else -> "https://tracker.umaya.tw/t/$trimmed"
        }
        prefs.edit().putString(KEY_LINK, url).apply()
        loadLink(url)
    }

    private fun loadLink(url: String) {
        webView.loadUrl(url)
        inputContainer.visibility = android.view.View.GONE
        webContainer.visibility = android.view.View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webContainer.visibility == android.view.View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val KEY_LINK = "share_link"
    }
}
