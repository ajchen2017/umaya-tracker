package tw.umaya.tracker.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    var activeShareToken: String?
        get() = prefs.getString("active_share_token", null)
        set(value) = prefs.edit().putString("active_share_token", value).apply()

    /** Minutes between GPS fixes. Configurable in-app; smaller = better tracking, worse battery. */
    var intervalMinutes: Int
        get() = prefs.getInt("interval_minutes", 3)
        set(value) = prefs.edit().putInt("interval_minutes", value).apply()

    val isLoggedIn: Boolean get() = authToken != null
    val hasActiveHike: Boolean get() = activeHikeId != -1L

    fun clearActiveHike() {
        prefs.edit().remove("active_hike_id").remove("active_share_token").apply()
    }
}
