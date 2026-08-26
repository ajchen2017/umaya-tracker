package tw.umaya.tracker

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import tw.umaya.tracker.sync.SyncWorker
import java.util.concurrent.TimeUnit

class TrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Backstop in case a location-triggered sync was missed (app killed, etc).
        // WorkManager's minimum periodic interval is 15 minutes.
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("sync_backstop", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
