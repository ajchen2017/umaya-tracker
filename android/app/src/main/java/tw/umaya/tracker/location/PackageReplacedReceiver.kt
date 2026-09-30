package tw.umaya.tracker.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import tw.umaya.tracker.data.Prefs
import tw.umaya.tracker.ui.MainActivity

/**
 * Installing an update kills the process, and with it the location service — tracking then
 * silently stopped until the hiker happened to open the app. When a trip or GPX recording was
 * running, restart the service right away; if Android won't allow that from the background
 * (no 「一律允許」 location permission), post a notification that reopens the app instead.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        val tripRunning = prefs.activeHikeId != -1L && !prefs.isPaused
        if (!tripRunning && !prefs.isGpxRecording) return

        val canTrackInBackground = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (canTrackInBackground) {
            val started = runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LocationForegroundService::class.java).setAction(LocationForegroundService.ACTION_START),
                )
            }.isSuccess
            if (started) return
        }
        notifyReopen(context)
    }

    private fun notifyReopen(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "App 更新提醒", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("App 已更新，行程記錄暫停中")
                .setContentText("點這裡打開 App，繼續記錄位置與軌跡")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "app_updated"
        const val NOTIFICATION_ID = 1002
    }
}
