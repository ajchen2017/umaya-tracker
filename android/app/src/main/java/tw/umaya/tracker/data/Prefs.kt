package tw.umaya.tracker.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Fixed set of selectable GPS-fix intervals: seconds value paired with its display label. */
val INTERVAL_PRESETS = listOf(
    10 to "10 秒",
    20 to "20 秒",
    30 to "0.5 分鐘",
    60 to "1 分鐘",
    120 to "2 分鐘",
    300 to "5 分鐘",
    600 to "10 分鐘",
    900 to "15 分鐘",
    1800 to "30 分鐘",
    3600 to "60 分鐘",
)

fun intervalLabel(seconds: Int): String =
    INTERVAL_PRESETS.firstOrNull { it.first == seconds }?.second ?: "$seconds 秒"

/** Display labels for the non-routine track_points.marker_type values. */
val MARKER_LABELS = mapOf("sos" to "SOS", "safe" to "我很好", "camping" to "停駐中")

/** Local device state: auth token, the hike currently being recorded, and user settings. */
class Prefs(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context, "tracker_prefs", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var authToken: String?
        get() = prefs.getString("auth_token", null)
        set(value) = prefs.edit().putString("auth_token", value).apply()

    var activeHikeId: Long
        get() = prefs.getLong("active_hike_id", -1L)
        set(value) = prefs.edit().putLong("active_hike_id", value).apply()

    /** Set once at login/register — persistent per account, not per hike, so the
     *  family's link keeps working across every trip instead of breaking each time
     *  a new hike starts. */
    var shareToken: String?
        get() = prefs.getString("share_token", null)
        set(value) = prefs.edit().putString("share_token", value).apply()

    /**
     * Seconds between GPS fixes. Configurable in-app from a fixed preset list (see
     * [tw.umaya.tracker.ui.INTERVAL_PRESETS]); smaller = better tracking, worse battery.
     */
    var intervalSeconds: Int
        get() = prefs.getInt("interval_seconds", 20)
        set(value) = prefs.edit().putInt("interval_seconds", value).apply()

    /** Remembers the last-entered per-hike nickname as the default for the next hike. */
    var lastNickname: String
        get() = prefs.getString("last_nickname", "") ?: ""
        set(value) = prefs.edit().putString("last_nickname", value).apply()

    /** Local GPX trail recording — independent of hasActiveHike/isPaused, which are about
     *  reporting to the guardian server. A hiker can record their own GPX with no hike started. */
    var isGpxRecording: Boolean
        get() = prefs.getBoolean("is_gpx_recording", false)
        set(value) = prefs.edit().putBoolean("is_gpx_recording", value).apply()

    var isGpxPaused: Boolean
        get() = prefs.getBoolean("is_gpx_paused", false)
        set(value) = prefs.edit().putBoolean("is_gpx_paused", value).apply()

    /** Absolute path of the GPX file currently being appended to, if any — lets 開始追蹤's
     *  「繼續」 resume the same on-disk file after the app/service process died mid-recording,
     *  instead of silently abandoning it and starting a new one. Cleared once the recording is
     *  finalized (儲存) or discarded (放棄). */
    var gpxFilePath: String?
        get() = prefs.getString("gpx_file_path", null)
        set(value) = prefs.edit().putString("gpx_file_path", value).apply()

    /** Minimum seconds between logged GPX points — a hard floor independent of how often the
     *  underlying GPS callback actually fires. Range 1–20; default 5. */
    var gpxMinIntervalSec: Int
        get() = prefs.getInt("gpx_min_interval_sec", 5)
        set(value) = prefs.edit().putInt("gpx_min_interval_sec", value).apply()

    /** Minimum meters moved between logged GPX points — independent OR-condition alongside
     *  [gpxMinIntervalSec] (whichever threshold is hit first triggers a log). Range 5–20; default 5. */
    var gpxMinDistanceM: Int
        get() = prefs.getInt("gpx_min_distance_m", 5)
        set(value) = prefs.edit().putInt("gpx_min_distance_m", value).apply()

    /** 地圖比例尺 開/關 — default off. */
    var showScaleBar: Boolean
        get() = prefs.getBoolean("show_scale_bar", false)
        set(value) = prefs.edit().putBoolean("show_scale_bar", value).apply()

    /** 向量魯地圖顯示山坡陰影(DEM) 開/關 — default on. Each tile needs the DEM-based shading
     *  composited on top of the vector rendering, so this is a real performance/battery cost,
     *  not just visual. */
    var showHillshading: Boolean
        get() = prefs.getBoolean("show_hillshading", true)
        set(value) = prefs.edit().putBoolean("show_hillshading", value).apply()

    /** 向量魯地圖圖層設定（各 elmt-hiking overlay 的開關）—— null 表示還沒改過，用主題各自的
     *  預設值；一旦使用者調整過任何一個，就存完整的「目前開啟中」清單，逗號分隔。 */
    var enabledMapLayerIds: Set<String>?
        get() = prefs.getString("enabled_map_layer_ids", null)?.split(",")?.filter { it.isNotBlank() }?.toSet()
        set(value) = prefs.edit().putString("enabled_map_layer_ids", value?.joinToString(",")).apply()

    /** 地圖方向 — "north" (default), "track", or "compass". Stored as a string rather than the
     *  enum directly so this file doesn't need to depend on the ui package. */
    var mapOrientationMode: String
        get() = prefs.getString("map_orientation_mode", "north") ?: "north"
        set(value) = prefs.edit().putString("map_orientation_mode", value).apply()

    /** Whether to draw Meshtastic/LoRa node positions on top of the selected map. */
    var showLoraDevicePoints: Boolean
        get() = prefs.getBoolean("show_lora_device_points", true)
        set(value) = prefs.edit().putBoolean("show_lora_device_points", value).apply()

    /** True while the hiker has paused GPS recording mid-hike (hike itself stays active). */
    var isPaused: Boolean
        get() = prefs.getBoolean("is_paused", false)
        set(value) = prefs.edit().putBoolean("is_paused", value).apply()

    /** Default on: whether starting a hike should prompt to exempt the app from battery
     *  optimization, so Android is less likely to kill background GPS recording. */
    var backgroundExecutionEnabled: Boolean
        get() = prefs.getBoolean("background_execution_enabled", true)
        set(value) = prefs.edit().putBoolean("background_execution_enabled", value).apply()

    /** Remembered credentials from the last successful login, prefilled on the login
     *  screen. Stored in EncryptedSharedPreferences (Keystore-backed), same as authToken. */
    var lastEmail: String?
        get() = prefs.getString("last_email", null)
        set(value) = prefs.edit().putString("last_email", value).apply()

    var lastPassword: String?
        get() = prefs.getString("last_password", null)
        set(value) = prefs.edit().putString("last_password", value).apply()

    /** Parent-folder URI of the last GPX/KML file the hiker picked (via SAF's
     *  EXTRA_INITIAL_URI) — so 載入GPX/KML's file picker reopens where they left off instead of
     *  the device's default root every time. */
    var lastGpxFolderUri: String?
        get() = prefs.getString("last_gpx_folder_uri", null)
        set(value) = prefs.edit().putString("last_gpx_folder_uri", value).apply()

    /** App-private copies of loaded GPX/KML reference routes (功能選單「載入GPX/KML」), redrawn on
     *  every launch until the hiker removes them. Newline-joined absolute paths. */
    var loadedRouteFiles: List<String>
        get() = prefs.getString("loaded_route_files", null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = prefs.edit().putString("loaded_route_files", value.joinToString("\n")).apply()

    /** Server-side hike_routes id per loaded route file, for routes shared with the guardian page
     *  during the current hike — needed to unshare one when it's removed. "id\tpath" lines. */
    var routeServerIds: Map<String, Long>
        get() = prefs.getString("route_server_ids", null)?.lines()?.mapNotNull { line ->
            val (id, path) = line.split("\t", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            id.toLongOrNull()?.let { path to it }
        }?.toMap() ?: emptyMap()
        set(value) = prefs.edit().putString("route_server_ids", value.entries.joinToString("\n") { "${it.value}\t${it.key}" }).apply()

    val isLoggedIn: Boolean get() = authToken != null
    val hasActiveHike: Boolean get() = activeHikeId != -1L

    fun clearActiveHike() {
        prefs.edit().remove("active_hike_id").remove("is_paused").apply()
    }
}
