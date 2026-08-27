package tw.umaya.tracker.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Fixed set of selectable GPS-fix intervals: seconds value paired with its display label. */
val INTERVAL_PRESETS = listOf(
    5 to "5 秒",
    10 to "10 秒",
    20 to "20 秒",
    60 to "1 分鐘",
    180 to "3 分鐘",
    600 to "10 分鐘",
    3600 to "1 小時",
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
        get() = prefs.getInt("interval_seconds", 180)
        set(value) = prefs.edit().putInt("interval_seconds", value).apply()

    /** Remembers the last-entered per-hike nickname as the default for the next hike. */
    var lastNickname: String
        get() = prefs.getString("last_nickname", "") ?: ""
        set(value) = prefs.edit().putString("last_nickname", value).apply()

    /** True while the hiker has paused GPS recording mid-hike (hike itself stays active). */
    var isPaused: Boolean
        get() = prefs.getBoolean("is_paused", false)
        set(value) = prefs.edit().putBoolean("is_paused", value).apply()

    /** Default on: whether starting a hike should prompt to exempt the app from battery
     *  optimization, so Android is less likely to kill background GPS recording. */
    var backgroundExecutionEnabled: Boolean
        get() = prefs.getBoolean("background_execution_enabled", true)
        set(value) = prefs.edit().putBoolean("background_execution_enabled", value).apply()

    val isLoggedIn: Boolean get() = authToken != null
    val hasActiveHike: Boolean get() = activeHikeId != -1L

    fun clearActiveHike() {
        prefs.edit().remove("active_hike_id").remove("is_paused").apply()
    }
}
