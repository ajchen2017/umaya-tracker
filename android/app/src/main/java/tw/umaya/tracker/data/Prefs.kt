package tw.umaya.tracker.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Fixed set of selectable GPS-fix intervals: seconds value paired with its display label. */
/** GPX 記錄間隔 choices in seconds (0 = 持續記錄) and 最短紀錄長度 choices in meters. */
val GPX_INTERVAL_OPTIONS = listOf(0, 5, 8, 10, 20, 60)
val GPX_DISTANCE_OPTIONS = listOf(5, 10, 20)
val OFF_ROUTE_DISTANCE_OPTIONS = listOf(10, 20, 50, 100)
val MAP_TEXT_SIZE_OPTIONS = listOf(8, 9, 10, 11, 12)

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

    /** Trip clock for the stats panel: start time (0 = no trip), total paused ms, and when the
     *  current pause began (0 = not paused). */
    var tripStartedAt: Long
        get() = prefs.getLong("trip_started_at", 0L)
        set(value) = prefs.edit().putLong("trip_started_at", value).apply()
    var tripPausedTotalMs: Long
        get() = prefs.getLong("trip_paused_total_ms", 0L)
        set(value) = prefs.edit().putLong("trip_paused_total_ms", value).apply()
    var tripPausedSince: Long
        get() = prefs.getLong("trip_paused_since", 0L)
        set(value) = prefs.edit().putLong("trip_paused_since", value).apply()

    /** Step counter (TYPE_STEP_COUNTER counts since boot): value at trip start (-1 = take the next
     *  reading), steps carried over a reboot, and the last reading seen. */
    var stepBaseline: Float
        get() = prefs.getFloat("step_baseline", -1f)
        set(value) = prefs.edit().putFloat("step_baseline", value).apply()
    var stepCarry: Float
        get() = prefs.getFloat("step_carry", 0f)
        set(value) = prefs.edit().putFloat("step_carry", value).apply()
    var stepLast: Float
        get() = prefs.getFloat("step_last", 0f)
        set(value) = prefs.edit().putFloat("step_last", value).apply()

    /** Loaded route files the hiker hid from the map (kept, just not drawn / not used for 偏離航道). */
    var hiddenRouteFiles: Set<String>
        get() = prefs.getStringSet("hidden_route_files", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("hidden_route_files", value).apply()

    /** 偏離航道提醒: on/off and the distance from the nearest visible route that counts as off-route. */
    var offRouteAlertEnabled: Boolean
        get() = prefs.getBoolean("off_route_alert_enabled", true)
        set(value) = prefs.edit().putBoolean("off_route_alert_enabled", value).apply()
    var offRouteDistanceM: Int
        get() = prefs.getInt("off_route_distance_m", 20)
        set(value) = prefs.edit().putInt("off_route_distance_m", value).apply()

    /** 地圖文字大小 (px, one of [MAP_TEXT_SIZE_OPTIONS]) for the offline map's own labels. */
    var mapTextSizePx: Int
        get() = prefs.getInt("map_text_size_px", 10)
        set(value) = prefs.edit().putInt("map_text_size_px", value).apply()

    /** GPS 永遠置中 — the map scrolls so the hiker's position stays in the middle (default on). */
    var keepGpsCentered: Boolean
        get() = prefs.getBoolean("keep_gps_centered", true)
        set(value) = prefs.edit().putBoolean("keep_gps_centered", value).apply()

    /** Old automatic recording names renamed to 行程名稱-yyyyMMdd-HHmmss (done once). */
    var trackNamesMigrated: Boolean
        get() = prefs.getBoolean("track_names_migrated", false)
        set(value) = prefs.edit().putBoolean("track_names_migrated", value).apply()

    /** 身分鎖定: "hiker" / "guardian" once chosen on this phone; null = ask on launch. */
    var appRole: String?
        get() = prefs.getString("app_role", null)
        set(value) = prefs.edit().putString("app_role", value).apply()

    /** SHA-256 of the PIN that must be entered to switch 身分. */
    var rolePinHash: String?
        get() = prefs.getString("role_pin_hash", null)
        set(value) = prefs.edit().putString("role_pin_hash", value).apply()

    /** 螢幕恆亮 on the map screen (default off — it costs battery). */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    var statsPanelExpanded: Boolean
        get() = prefs.getBoolean("stats_panel_expanded", true)
        set(value) = prefs.edit().putBoolean("stats_panel_expanded", value).apply()

    /** The trail saved when the last trip ended — 接續舊行程 keeps writing to it. */
    var lastFinishedGpxPath: String?
        get() = prefs.getString("last_finished_gpx_path", null)
        set(value) = prefs.edit().putString("last_finished_gpx_path", value).apply()

    /** GPX 記錄間隔 — minimum seconds between logged points, one of [GPX_INTERVAL_OPTIONS]; 0 =
     *  continuous. Independent of the guardian 定位頻率 ([intervalSeconds]). */
    var gpxMinIntervalSec: Int
        get() = prefs.getInt("gpx_record_interval_sec", 10)
        set(value) = prefs.edit().putInt("gpx_record_interval_sec", value).apply()

    /** GPX 最短紀錄長度 — minimum meters moved before the next point is logged, one of
     *  [GPX_DISTANCE_OPTIONS]; must be met together with [gpxMinIntervalSec]. */
    var gpxMinDistanceM: Int
        get() = prefs.getInt("gpx_min_record_distance_m", 20)
        set(value) = prefs.edit().putInt("gpx_min_record_distance_m", value).apply()

    /** 地圖比例尺 開/關 — default on (bottom-left, above the status bar). */
    var showScaleBar: Boolean
        get() = prefs.getBoolean("show_scale_bar", true)
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

    /** 行程名稱 of the active hike — kept so the status bar shows it after the app restarts. */
    var activeHikeName: String?
        get() = prefs.getString("active_hike_name", null)
        set(value) = prefs.edit().putString("active_hike_name", value).apply()

    fun clearActiveHike() {
        prefs.edit().remove("active_hike_id").remove("is_paused").remove("active_hike_name").apply()
    }
}
