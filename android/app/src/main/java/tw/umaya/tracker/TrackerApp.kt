package tw.umaya.tracker

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import tw.umaya.tracker.sync.SyncWorker
import java.util.concurrent.TimeUnit

fun crashFile(context: android.content.Context) = java.io.File(context.filesDir, "last_crash.txt")

class TrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Keep the last crash's stack trace so HomeActivity can offer to share it on next launch —
        // the phone is usually out in the field, nowhere near a debugger.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile(this).writeText(
                    "版本 ${packageManager.getPackageInfo(packageName, 0).versionName}・" +
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(System.currentTimeMillis()) +
                        "\n" + android.util.Log.getStackTraceString(error)
                )
            }
            previous?.uncaughtException(thread, error)
        }
        // Backstop in case a location-triggered sync was missed (app killed, etc).
        // WorkManager's minimum periodic interval is 15 minutes.
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("sync_backstop", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
