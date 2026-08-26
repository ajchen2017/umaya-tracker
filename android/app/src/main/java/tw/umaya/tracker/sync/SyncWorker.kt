package tw.umaya.tracker.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import tw.umaya.tracker.data.ApiClient
import tw.umaya.tracker.data.AppDatabase
import tw.umaya.tracker.data.MARKER_LABELS
import tw.umaya.tracker.data.Prefs
import tw.umaya.tracker.data.UploadPointDto
import tw.umaya.tracker.data.UploadPointsRequest

/**
 * Uploads any track points recorded while offline. Runs only when a network
 * is available (WorkManager constraint); retries with backoff on failure.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val hikeId = prefs.activeHikeId
        val token = prefs.authToken
        if (hikeId == -1L || token == null) return Result.success()

        val dao = AppDatabase.get(applicationContext).trackPointDao()
        val pending = dao.getPending(hikeId)
        if (pending.isEmpty()) return Result.success()

        val body = UploadPointsRequest(
            points = pending.map {
                UploadPointDto(
                    clientId = it.clientId,
                    lat = it.lat,
                    lng = it.lng,
                    altitude = it.altitude,
                    accuracy = it.accuracy,
                    markerType = it.markerType,
                    batteryPct = it.batteryPct,
                    recordedAt = it.recordedAtIso,
                )
            }
        )

        return try {
            val response = ApiClient.service.uploadPoints("Bearer $token", hikeId, body)
            if (response.isSuccessful) {
                dao.markSynced(pending.map { it.clientId })
                // Confirms actual server delivery, distinct from the "queued locally" toast
                // shown at record time — the hiker needs to know an SOS really went out.
                pending.mapNotNull { MARKER_LABELS[it.markerType] }.distinct().forEach { label ->
                    val text = "✅ $label 已送達"
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
                    }
                }
                // More may have queued up while this batch was in flight.
                if (dao.pendingCount(hikeId) > 0) enqueue(applicationContext)
                Result.success()
            } else if (response.code() in 400..499) {
                Result.failure() // bad request/auth — retrying won't help
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "sync_track_points"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
