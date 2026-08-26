package tw.umaya.tracker.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tw.umaya.tracker.data.AppDatabase
import tw.umaya.tracker.data.Prefs
import tw.umaya.tracker.data.TrackPoint
import tw.umaya.tracker.sync.SyncWorker
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LocationForegroundService : Service() {

    companion object {
        const val ACTION_START = "tw.umaya.tracker.action.START"
        const val ACTION_STOP = "tw.umaya.tracker.action.STOP"
        const val ACTION_MARK_SOS = "tw.umaya.tracker.action.MARK_SOS"
        const val ACTION_MARK_SAFE = "tw.umaya.tracker.action.MARK_SAFE"
        const val ACTION_MARK_CAMPING = "tw.umaya.tracker.action.MARK_CAMPING"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var prefs: Prefs
    private lateinit var db: AppDatabase
    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastLocation: Location? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lastLocation = location
            recordPoint(location, "normal")
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        prefs = Prefs(this)
        db = AppDatabase.get(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLocationUpdates()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MARK_SOS -> lastLocation?.let { recordPoint(it, "sos") }
            ACTION_MARK_SAFE -> lastLocation?.let { recordPoint(it, "safe") }
            ACTION_MARK_CAMPING -> lastLocation?.let { recordPoint(it, "camping") }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startLocationUpdates()
            }
        }
        return START_STICKY
    }

    private fun startLocationUpdates() {
        val intervalMs = prefs.intervalMinutes * 60_000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    private fun stopLocationUpdates() {
        fusedClient.removeLocationUpdates(locationCallback)
    }

    private fun recordPoint(location: Location, markerType: String) {
        val hikeId = prefs.activeHikeId
        if (hikeId == -1L) return

        scope.launch {
            db.trackPointDao().insert(
                TrackPoint(
                    hikeId = hikeId,
                    lat = location.latitude,
                    lng = location.longitude,
                    altitude = if (location.hasAltitude()) location.altitude else null,
                    accuracy = if (location.hasAccuracy()) location.accuracy else null,
                    markerType = markerType,
                    batteryPct = currentBatteryPct(),
                    recordedAtIso = isoNow(),
                )
            )
            SyncWorker.enqueue(applicationContext)
        }
    }

    private fun currentBatteryPct(): Int? {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) pct else null
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(System.currentTimeMillis())
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LocationForegroundService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("行程記錄中")
            .setContentText("正在背景記錄你的位置，每 ${prefs.intervalMinutes} 分鐘一筆")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(0, "結束行程", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "定位追蹤", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
