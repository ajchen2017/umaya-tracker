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
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
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
import tw.umaya.tracker.widget.TrackerWidgetProvider
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
        const val ACTION_ADD_WAYPOINT = "tw.umaya.tracker.action.ADD_WAYPOINT"
        const val EXTRA_WAYPOINT_NAME = "waypoint_name"
        /** Photo path relative to the gpx folder (e.g. "photos/IMG_….jpg"), linked from the 航點. */
        const val EXTRA_WAYPOINT_PHOTO = "waypoint_photo"
        const val ACTION_UPDATE_INTERVAL = "tw.umaya.tracker.action.UPDATE_INTERVAL"
        const val ACTION_PAUSE = "tw.umaya.tracker.action.PAUSE"
        const val ACTION_RESUME = "tw.umaya.tracker.action.RESUME"
        // Local GPX trail recording — independent of the hike-reporting actions above.
        const val ACTION_GPX_START = "tw.umaya.tracker.action.GPX_START"
        const val ACTION_GPX_PAUSE = "tw.umaya.tracker.action.GPX_PAUSE"
        const val ACTION_GPX_RESUME = "tw.umaya.tracker.action.GPX_RESUME"
        const val ACTION_GPX_STOP = "tw.umaya.tracker.action.GPX_STOP"
        // GPX_START: true = 繼續 an on-disk unfinished file (prefs.gpxFilePath), false/absent = 重新開始
        const val EXTRA_GPX_RESUME_EXISTING = "gpx_resume_existing"
        // GPX_STOP: EXTRA_NAME/EXTRA_FORMAT ("gpx"/"kml"); EXTRA_OUTPUT_URI = where the hiker chose to save it
        const val EXTRA_GPX_NAME = "gpx_name"
        const val EXTRA_GPX_FORMAT = "gpx_format"
        const val EXTRA_GPX_OUTPUT_URI = "gpx_output_uri"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001

        // A weak-signal or multipath-reflected fix (common near buildings/dense campus
        // structures) looks the same on the map either way: the track snaps out to a
        // wrong point and back, over and over. Reject both rather than plotting them.
        private const val MAX_ACCEPTABLE_ACCURACY_M = 50f
        private const val MAX_PLAUSIBLE_SPEED_MPS = 15f // ~54 km/h — generous for a hiker, rejects GPS teleports
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var prefs: Prefs
    private lateinit var db: AppDatabase
    private lateinit var gpxRecorder: GpxRecorder
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastLocation: Location? = null
    private var lastAcceptedLocation: Location? = null
    private var lastReportedFixTime = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // While the phone throttles the app (screen off, 飛航模式/no signal, power saving) the
            // GPS keeps fixing and the OS hands the buffered fixes over in one batch afterwards —
            // taking only lastLocation dropped all of them and joined the gap with a straight line.
            for (location in result.locations.sortedBy { it.time }) {
                lastLocation = location // kept unfiltered — SOS/markers favor recency over precision
                if (!isPlausibleFix(location)) continue
                lastAcceptedLocation = location
                // Fixes can arrive faster than the guardian 定位頻率 while a GPX is recording at
                // a finer interval — report at most once per 定位頻率 (80% tolerance for jitter).
                if (location.time - lastReportedFixTime >= prefs.intervalSeconds * 800L) {
                    lastReportedFixTime = location.time
                    recordPoint(location, "normal", location.time) // a batched fix keeps its own time
                }
                if (prefs.isGpxRecording && !prefs.isGpxPaused) {
                    gpxRecorder.appendPoint(location, prefs.gpxMinIntervalSec, prefs.gpxMinDistanceM)
                }
            }
        }
    }

    private fun isPlausibleFix(location: Location): Boolean {
        if (location.hasAccuracy() && location.accuracy > MAX_ACCEPTABLE_ACCURACY_M) return false
        val prev = lastAcceptedLocation ?: return true
        val elapsedSec = (location.time - prev.time) / 1000.0
        // A same-or-earlier fix time than the last accepted fix means this is a duplicate or
        // out-of-order delivery (e.g. a burst of buffered network/passive fixes released at
        // once after the OS throttled callbacks) — no speed can be computed, so reject instead
        // of letting it through unchecked.
        if (elapsedSec <= 0) return false
        val impliedSpeedMps = prev.distanceTo(location) / elapsedSec
        return impliedSpeedMps <= MAX_PLAUSIBLE_SPEED_MPS
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        prefs = Prefs(this)
        db = AppDatabase.get(this)
        gpxRecorder = GpxRecorder(this)
        createNotificationChannel()
        // speak() right after construction would silently no-op: the engine takes a moment to
        // connect, and calls made before onInit fires are dropped rather than queued. Stash a
        // pending request and flush it once the engine reports ready.
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.US // the alert phrase is English regardless of device locale
                if (pendingSpeech) speakSosNow()
            }
            pendingSpeech = false
        }
    }

    private fun speakSosNow() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
        tts?.speak("Help, Help", TextToSpeech.QUEUE_FLUSH, params, "sos_alert")
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun copyToUri(source: java.io.File, target: android.net.Uri): Boolean = try {
        contentResolver.openOutputStream(target, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } } != null
    } catch (e: Exception) {
        android.util.Log.e("LocationService", "複製軌跡到 $target 失敗", e)
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A killed/updated process loses the recorder's open file even though prefs still say
        // 記錄中 — reattach to the same on-disk file so later fixes keep landing in it.
        if (prefs.isGpxRecording && !gpxRecorder.isActive && intent?.action != ACTION_GPX_START) {
            prefs.gpxFilePath = gpxRecorder.start(prefs.gpxFilePath)
        }
        when (intent?.action) {
            ACTION_STOP -> {
                refreshLocationUpdates()
                if (!prefs.isGpxRecording) stopSelf() // GPX recording alone still needs this service running
                return START_NOT_STICKY
            }
            ACTION_UPDATE_INTERVAL -> refreshLocationUpdates()
            ACTION_PAUSE -> {
                prefs.isPaused = true
                refreshLocationUpdates()
                updateNotification()
                TrackerWidgetProvider.updateAllWidgets(applicationContext)
                if (prefs.activeHikeId != -1L) {
                    HikeActionWorker.enqueue(applicationContext, prefs.activeHikeId, HikeActionWorker.ACTION_PAUSE)
                }
            }
            ACTION_RESUME -> {
                prefs.isPaused = false
                refreshLocationUpdates()
                updateNotification()
                TrackerWidgetProvider.updateAllWidgets(applicationContext)
                if (prefs.activeHikeId != -1L) {
                    HikeActionWorker.enqueue(applicationContext, prefs.activeHikeId, HikeActionWorker.ACTION_RESUME)
                }
            }
            ACTION_MARK_SOS -> {
                if (ttsReady) speakSosNow() else pendingSpeech = true
                markPoint("sos")
            }
            ACTION_MARK_SAFE -> markPoint("safe")
            ACTION_MARK_CAMPING -> markPoint("camping")
            ACTION_ADD_WAYPOINT -> addWaypoint(
                intent?.getStringExtra(EXTRA_WAYPOINT_NAME)?.ifBlank { null } ?: "航點",
                intent?.getStringExtra(EXTRA_WAYPOINT_PHOTO),
            )
            ACTION_GPX_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                val resumeExisting = intent?.getBooleanExtra(EXTRA_GPX_RESUME_EXISTING, false) ?: false
                // 接續舊行程 keeps writing the trail it recorded last time (unfinished, or the one
                // saved when it ended); a new trip starts a new file. Nothing is ever deleted — an
                // older file stays listed under 軌跡記錄設定 → 匯出.
                val resumePath = if (resumeExisting) prefs.gpxFilePath ?: prefs.lastFinishedGpxPath else null
                val path = gpxRecorder.start(resumePath)
                prefs.gpxFilePath = path
                prefs.isGpxRecording = true
                prefs.isGpxPaused = false
                refreshLocationUpdates()
                updateNotification()
            }
            ACTION_GPX_PAUSE -> {
                prefs.isGpxPaused = true
                refreshLocationUpdates()
                updateNotification()
            }
            ACTION_GPX_RESUME -> {
                gpxRecorder.newSegment() // don't draw a line across the pause
                prefs.isGpxPaused = false
                refreshLocationUpdates()
                updateNotification()
            }
            ACTION_GPX_STOP -> {
                val name = intent?.getStringExtra(EXTRA_GPX_NAME) ?: ""
                val format = intent?.getStringExtra(EXTRA_GPX_FORMAT) ?: "gpx"
                val outputUri = intent?.getStringExtra(EXTRA_GPX_OUTPUT_URI)?.let(android.net.Uri::parse)
                val saved = gpxRecorder.finalize(name, format)
                if (saved != null) prefs.lastFinishedGpxPath = saved.first
                toast(
                    when {
                        saved == null -> "沒有正在記錄的軌跡"
                        outputUri == null -> "軌跡已保留在 App 內（${saved.second} 個點），可到「軌跡記錄設定」匯出"
                        copyToUri(java.io.File(saved.first), outputUri) -> "已儲存到選擇的位置（${saved.second} 個點）"
                        else -> "無法寫入選擇的位置，已改存在：${saved.first}"
                    }
                )
                prefs.isGpxRecording = false
                prefs.isGpxPaused = false
                prefs.gpxFilePath = null
                refreshLocationUpdates()
                updateNotification()
                if (prefs.activeHikeId == -1L) stopSelf()
            }
            else -> {
                // Also how the screen re-asserts the service is alive on reopen (ACTION_START
                // falls through to here, since it doesn't match a case above) — a killed
                // process would otherwise leave "行程進行中" showing with tracking silently
                // stopped and nothing to restart it. Must not override an existing pause.
                startForeground(NOTIFICATION_ID, buildNotification())
                refreshLocationUpdates()
            }
        }
        return START_STICKY
    }

    /** Single source of truth for whether the fused-location callback should be running —
     *  hike reporting and local GPX recording are independent, either one alone is enough
     *  to need live fixes, so this can't just be "is there an active hike". */
    private fun refreshLocationUpdates() {
        val shouldRun = hasLocationPermission() &&
            ((prefs.activeHikeId != -1L && !prefs.isPaused) || (prefs.isGpxRecording && !prefs.isGpxPaused))
        if (shouldRun) startLocationUpdates() else stopLocationUpdates()
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
        // The faster of the guardian 定位頻率 and the GPX 記錄間隔 (0 = continuous → every second).
        val reportMs = prefs.intervalSeconds * 1_000L
        val gpxMs = if (prefs.isGpxRecording && !prefs.isGpxPaused) maxOf(1_000L, prefs.gpxMinIntervalSec * 1_000L) else Long.MAX_VALUE
        val intervalMs = minOf(reportMs, gpxMs)
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
    private fun markPoint(markerType: String) = withCurrentFix("標記") { recordPoint(it, markerType) }

    /**
     * Marker/waypoint buttons must never silently do nothing: [lastLocation] is only populated once
     * the first periodic fix lands, which can be minutes away, so fall back to an on-demand fix and
     * always end in either [onFix] or a toast explaining why not.
     */
    private fun withCurrentFix(what: String, onFix: (Location) -> Unit) {
        val cached = lastLocation
        if (cached != null) {
            onFix(cached)
            return
        }
        if (!hasLocationPermission()) {
            toast("沒有定位權限，無法$what")
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
                onFix(location)
            }
        }.addOnFailureListener {
            toast("定位失敗：${it.message}")
        }
    }

    /** 航點: into the GPX being recorded, and to the guardian page (retried until delivered). */
    private fun addWaypoint(name: String, photo: String?) = withCurrentFix("新增航點") { location ->
        if (prefs.isGpxRecording) gpxRecorder.addWaypoint(name, location, photo)
        val hikeId = prefs.activeHikeId
        if (hikeId != -1L) {
            val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(location.time)
            HikeActionWorker.enqueue(
                applicationContext, hikeId, HikeActionWorker.ACTION_WAYPOINT,
                mapOf(
                    HikeActionWorker.KEY_WP_CLIENT_ID to java.util.UUID.randomUUID().toString(),
                    HikeActionWorker.KEY_WP_NAME to name,
                    HikeActionWorker.KEY_WP_LAT to location.latitude,
                    HikeActionWorker.KEY_WP_LNG to location.longitude,
                    HikeActionWorker.KEY_WP_ALT to if (location.hasAltitude()) location.altitude else Double.NaN,
                    HikeActionWorker.KEY_WP_TIME to iso,
                ),
            )
        }
        toast((if (photo != null) "📷" else "🚩") + " 已新增航點「$name」")
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
    }

    private fun recordPoint(location: Location, markerType: String, atMillis: Long = System.currentTimeMillis()) {
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
                    recordedAtIso = isoAt(atMillis),
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

    private fun isoAt(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(millis)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LocationForegroundService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hasHike = prefs.activeHikeId != -1L
        val parts = mutableListOf<String>()
        if (hasHike) parts += if (prefs.isPaused) "行程已暫停" else "行程記錄中（每 ${intervalLabel(prefs.intervalSeconds)} 回報一次）"
        if (prefs.isGpxRecording) parts += if (prefs.isGpxPaused) "GPX 已暫停" else "GPX 錄製中"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (parts.isEmpty()) "定位服務" else parts.joinToString(" · "))
            .setContentText("正在背景記錄你的位置")
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
