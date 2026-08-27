package tw.umaya.tracker.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tw.umaya.tracker.data.AppDatabase
import tw.umaya.tracker.data.MARKER_LABELS
import tw.umaya.tracker.data.Prefs
import tw.umaya.tracker.data.TrackPoint
import tw.umaya.tracker.data.intervalLabel
import tw.umaya.tracker.sync.HikeActionWorker
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
        const val ACTION_UPDATE_INTERVAL = "tw.umaya.tracker.action.UPDATE_INTERVAL"
        const val ACTION_PAUSE = "tw.umaya.tracker.action.PAUSE"
        const val ACTION_RESUME = "tw.umaya.tracker.action.RESUME"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var prefs: Prefs
    private lateinit var db: AppDatabase
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
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
            ACTION_UPDATE_INTERVAL -> if (hasLocationPermission() && !prefs.isPaused) startLocationUpdates()
            ACTION_PAUSE -> {
                stopLocationUpdates()
                prefs.isPaused = true
                updateNotification()
                if (prefs.activeHikeId != -1L) {
                    HikeActionWorker.enqueue(applicationContext, prefs.activeHikeId, HikeActionWorker.ACTION_PAUSE)
                }
            }
            ACTION_RESUME -> {
                prefs.isPaused = false
                if (hasLocationPermission()) startLocationUpdates()
                updateNotification()
                if (prefs.activeHikeId != -1L) {
                    HikeActionWorker.enqueue(applicationContext, prefs.activeHikeId, HikeActionWorker.ACTION_RESUME)
                }
            }
            ACTION_MARK_SOS -> markPoint("sos")
            ACTION_MARK_SAFE -> markPoint("safe")
            ACTION_MARK_CAMPING -> markPoint("camping")
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startLocationUpdates()
            }
        }
        return START_STICKY
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun startLocationUpdates() {
        val intervalMs = prefs.intervalSeconds * 1_000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        fusedClient.removeLocationUpdates(locationCallback)
        fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    private fun stopLocationUpdates() {
        fusedClient.removeLocationUpdates(locationCallback)
    }

    /**
     * Marker buttons must never silently do nothing: [lastLocation] is only populated once the
     * first periodic fix lands, which can be minutes away, so fall back to an on-demand fix and
     * always end in either a recorded point or a toast explaining why not.
     */
    private fun markPoint(markerType: String) {
        val cached = lastLocation
        if (cached != null) {
            recordPoint(cached, markerType)
            return
        }
        if (!hasLocationPermission()) {
            toast("沒有定位權限，無法標記")
            return
        }
        val cancellationToken = CancellationTokenSource()
        fusedClient.getCurrentLocation(
            CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).build(),
            cancellationToken.token,
        ).addOnSuccessListener { location ->
            if (location == null) {
                toast("目前無法取得定位，請稍後再試")
            } else {
                lastLocation = location
                recordPoint(location, markerType)
            }
        }.addOnFailureListener {
            toast("定位失敗：${it.message}")
        }
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
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
            if (markerType in MARKER_LABELS) {
                val label = MARKER_LABELS[markerType]
                val status = if (hasNetwork()) "已標記：$label，正在同步" else "已標記：$label（目前無訊號，恢復後自動同步）"
                toast(status)
            }
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
            .setContentTitle(if (prefs.isPaused) "行程已暫停" else "行程記錄中")
            .setContentText(
                if (prefs.isPaused) "定位記錄已暫停，行程仍在進行中"
                else "正在背景記錄你的位置，每 ${intervalLabel(prefs.intervalSeconds)} 一筆"
            )
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(0, "結束行程", stopPending)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "定位追蹤", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
