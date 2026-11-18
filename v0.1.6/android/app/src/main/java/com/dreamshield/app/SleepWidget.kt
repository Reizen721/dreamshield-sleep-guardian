package com.dreamshield.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Small, local and usable without a PC connection. Taps enter the native sleep flow. */
class SleepWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, render(context, it)) }
    }
    companion object {
        @JvmStatic fun refresh(context: Context) = updateAll(context)
        @JvmStatic fun updateAll(context: Context) {
            val manager = context.getSystemService(AppWidgetManager::class.java)
            val ids = manager.getAppWidgetIds(ComponentName(context, SleepWidget::class.java))
            ids.forEach { manager.updateAppWidget(it, render(context, it)) }
        }
        private fun render(context: Context, widgetId: Int): RemoteViews {
            val active = SleepRepository(context).snapshot.active
            val hasActive = active != null
            return RemoteViews(context.packageName, R.layout.sleep_widget).apply {
                setTextViewText(R.id.sleep_widget_title, if (hasActive) text(R.string.copy_tonight_in_progress_826) else text(R.string.copy_tonight_s_journal_827))
                setTextViewText(R.id.sleep_widget_action, if (hasActive) text(R.string.copy_open_night_records_825) else text(R.string.copy_start_tonight))
                setTextViewText(R.id.sleep_widget_detail, if (hasActive) text(R.string.copy_logging_time_in_bed) else text(R.string.copy_dreamshield_sleep_journal))
                val open = PendingIntent.getActivity(context, widgetId,
                    SleepSystem.openIntent(context, if (hasActive) SleepSystem.OPEN_SLEEP else SleepSystem.START_TONIGHT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                setOnClickPendingIntent(R.id.sleep_widget_root, open)
            }
        }
    }
}
