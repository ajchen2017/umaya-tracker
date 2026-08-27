package tw.umaya.tracker.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.WorkerParameters
import tw.umaya.tracker.data.ApiClient
import tw.umaya.tracker.data.PauseStateRequest
import tw.umaya.tracker.data.Prefs

/**
 * One-off signals (pause/resume/end) that must actually reach the server, not just fire
 * once and hope — the same retry-until-delivered contract SyncWorker gives track points.
 * Without this, a single timed-out end-hike call orphans that hike as "active" forever
 * with no way for the family page to ever show it as ended.
 */
class HikeActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val hikeId = inputData.getLong(KEY_HIKE_ID, -1L)
        val action = inputData.getString(KEY_ACTION)
        val token = Prefs(applicationContext).authToken
        if (hikeId == -1L || action == null || token == null) return Result.failure()

        return try {
            val bearer = "Bearer $token"
            val response = when (action) {
                ACTION_PAUSE -> ApiClient.service.setPauseState(bearer, hikeId, PauseStateRequest(true))
                ACTION_RESUME -> ApiClient.service.setPauseState(bearer, hikeId, PauseStateRequest(false))
                ACTION_END -> ApiClient.service.endHike(bearer, hikeId)
                else -> return Result.failure()
            }
            when {
                response.isSuccessful -> {
                    toast("✅ ${labelFor(action)} 已送達")
                    Result.success()
                }
                response.code() in 400..499 -> {
                    toast("${labelFor(action)} 失敗（伺服器拒絕請求）")
                    Result.failure()
                }
                else -> Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun labelFor(action: String) = when (action) {
        ACTION_PAUSE -> "暫停"
        ACTION_RESUME -> "繼續"
        ACTION_END -> "結束行程"
        else -> action
    }

    private fun toast(message: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_END = "end"
        private const val KEY_HIKE_ID = "hike_id"
        private const val KEY_ACTION = "action"

        fun enqueue(context: Context, hikeId: Long, action: String) {
            val request = OneTimeWorkRequestBuilder<HikeActionWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_HIKE_ID to hikeId, KEY_ACTION to action))
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
