package tw.umaya.tracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import tw.umaya.tracker.R
import tw.umaya.tracker.data.Prefs
import tw.umaya.tracker.location.LocationForegroundService
import tw.umaya.tracker.sync.HikeActionWorker
import tw.umaya.tracker.ui.MainActivity

/**
 * Home-screen widget: start (opens the app — a hike needs a name, which a widget can't
 * collect), pause/resume, and end, without opening the app for the latter two. Any state
 * change from the in-app screen also calls [updateAllWidgets] so the two stay in sync.
 */
class TrackerWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_WIDGET_PAUSE = "tw.umaya.tracker.widget.action.PAUSE"
        private const val ACTION_WIDGET_STOP = "tw.umaya.tracker.widget.action.STOP"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TrackerWidgetProvider::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = Prefs(context)
            val views = RemoteViews(context.packageName, R.layout.widget_tracker)

            val openAppIntent = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widgetBtnStart, openAppIntent)

            if (prefs.hasActiveHike) {
                views.setTextViewText(R.id.widgetStatus, if (prefs.isPaused) "行程進行中（已暫停）" else "行程進行中")
                views.setViewVisibility(R.id.widgetBtnStart, View.GONE)
                views.setViewVisibility(R.id.widgetBtnPause, View.VISIBLE)
                views.setViewVisibility(R.id.widgetBtnStop, View.VISIBLE)
                views.setTextViewText(R.id.widgetBtnPause, if (prefs.isPaused) "▶ 繼續" else "⏸ 暫停")

                views.setOnClickPendingIntent(
                    R.id.widgetBtnPause,
                    PendingIntent.getBroadcast(
                        context, 1, Intent(context, TrackerWidgetProvider::class.java).setAction(ACTION_WIDGET_PAUSE),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                views.setOnClickPendingIntent(
                    R.id.widgetBtnStop,
                    PendingIntent.getBroadcast(
                        context, 2, Intent(context, TrackerWidgetProvider::class.java).setAction(ACTION_WIDGET_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                views.setTextViewText(R.id.widgetStatus, "尚未開始行程")
                views.setViewVisibility(R.id.widgetBtnStart, View.VISIBLE)
                views.setViewVisibility(R.id.widgetBtnPause, View.GONE)
                views.setViewVisibility(R.id.widgetBtnStop, View.GONE)
            }

            manager.updateAppWidget(id, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs = Prefs(context)
        when (intent.action) {
            ACTION_WIDGET_PAUSE -> {
                if (!prefs.hasActiveHike) return
                val nowPaused = !prefs.isPaused
                prefs.isPaused = nowPaused
                context.startService(
                    Intent(context, LocationForegroundService::class.java).setAction(
                        if (nowPaused) LocationForegroundService.ACTION_PAUSE
                        else LocationForegroundService.ACTION_RESUME
                    )
                )
                updateAllWidgets(context)
            }
            ACTION_WIDGET_STOP -> {
                if (!prefs.hasActiveHike) return
                HikeActionWorker.enqueue(context, prefs.activeHikeId, HikeActionWorker.ACTION_END)
                context.startService(
                    Intent(context, LocationForegroundService::class.java)
                        .setAction(LocationForegroundService.ACTION_STOP)
                )
                prefs.clearActiveHike()
                updateAllWidgets(context)
            }
        }
    }
}
