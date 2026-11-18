package com.dreamshield.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.*

/** Local clock scheduling; no network, sensor or computer action is started. */
data class SleepScheduleStatus(
    val bedtimeAt: Long?, val notificationsGranted: Boolean, val message: String
)

object SleepScheduleMath {
    fun next(plan: SleepPlan, now: Long, zone: ZoneId = ZoneId.systemDefault(),
             completedNightDates: Set<String> = emptySet()): Long? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (-1L..8L).mapNotNull { offset ->
            val night = today.plusDays(offset)
            val rest = night.dayOfWeek == DayOfWeek.SATURDAY || night.dayOfWeek == DayOfWeek.SUNDAY
            val bed = runCatching { LocalTime.parse(if (rest) plan.weekendBed else plan.weekdayBed) }.getOrNull()
                ?: return@mapNotNull null
            val nightKey = SleepMath.nightDate(night.atTime(bed).atZone(zone).toInstant().toEpochMilli(), zone)
            if (nightKey == plan.skippedNightDate || nightKey in completedNightDates) return@mapNotNull null
            val point = night.atTime(bed).atZone(zone).minusMinutes(plan.reminderLeadMinutes.coerceIn(0, 180).toLong())
            point.toInstant().toEpochMilli().takeIf { it > now }
        }.minOrNull()
    }
}

object SleepSystem {
    const val EXTRA_ACTION = "dreamshield_action"
    const val OPEN_SLEEP = "open_sleep"
    const val START_TONIGHT = "start_tonight"
    const val BEDTIME = "com.dreamshield.app.BEDTIME"
    const val REMINDER_CHANNEL = "sleep_bedtime_v1"
    internal const val REMINDER_ID = 6101
    private const val PREF = "dreamshield_sleep_clock_v1"

    @JvmStatic fun canNotify(context: Context) = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL, text(R.string.copy_bedtime_reminder), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = text(R.string.copy_a_notification_before_your_planned_bedtime)
        })
    }
    private fun alarm(context: Context) = context.getSystemService(AlarmManager::class.java)
    private fun clockPrefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    internal fun broadcast(context: Context, action: String, code: Int, at: Long = 0): PendingIntent =
        PendingIntent.getBroadcast(context, code, Intent(context, SleepAlarmReceiver::class.java).setAction(action)
            .putExtra("scheduled_at", at), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    @JvmStatic fun openIntent(context: Context, action: String = OPEN_SLEEP): Intent =
        Intent(context, MainActivity::class.java).putExtra(EXTRA_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    internal fun openPending(context: Context, code: Int, action: String = OPEN_SLEEP): PendingIntent =
        PendingIntent.getActivity(context, code, openIntent(context, action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @JvmStatic fun sync(context: Context): SleepScheduleStatus {
        clearLegacyWakeAlarm(context)
        channels(context)
        val snapshot = SleepRepository(context).snapshot
        val plan = snapshot.plan
        val manager = alarm(context)
        manager.cancel(broadcast(context, BEDTIME, REMINDER_ID))
        val now = System.currentTimeMillis()
        val completed = snapshot.nights.filter { it.endMillis != null && it.kind != "nap" }.map { it.date }.toSet()
        val reminderAt = if (plan.reminderEnabled) SleepScheduleMath.next(plan, now, completedNightDates = completed) else null
        if (reminderAt != null) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminderAt,
                broadcast(context, BEDTIME, REMINDER_ID, reminderAt))
        }
        clockPrefs(context).edit().putLong("bedtime_at", reminderAt ?: 0L).apply()
        SleepWidget.updateAll(context)
        val notificationsGranted = canNotify(context)
        val message = when {
            plan.reminderEnabled && !notificationsGranted -> text(R.string.copy_allow_notifications_for_bedtime_reminders)
            reminderAt != null -> text(R.string.copy_bedtime_reminder_scheduled)
            else -> text(R.string.copy_schedule_saved)
        }
        return SleepScheduleStatus(reminderAt, notificationsGranted, message)
    }

    internal fun expectedBedtime(context: Context, at: Long): Boolean {
        val now = System.currentTimeMillis()
        return at > 0 && clockPrefs(context).getLong("bedtime_at", 0) == at && now >= at - 3000 && now - at < 15 * 60_000
    }

    private fun clearLegacyWakeAlarm(context: Context) {
        val prefs = clockPrefs(context)
        if (prefs.getBoolean("wake_alarm_removed", false)) return

        // Match the original v0.6.0 PendingIntents, including the receiver, actions and request codes.
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        for ((code, action) in listOf(6102 to "com.dreamshield.app.WAKE", 6103 to "com.dreamshield.app.WAKE.SNOOZED")) {
            PendingIntent.getBroadcast(context, code, Intent(context, SleepAlarmReceiver::class.java).setAction(action), flags)?.let {
                alarm(context).cancel(it)
                it.cancel()
            }
            PendingIntent.getActivity(context, code, openIntent(context), flags)?.cancel()
        }
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.cancel(6102)
        notifications.deleteNotificationChannel("sleep_wake_v1")
        prefs.edit().remove("wake_at").remove("snooze_at").remove("active_id")
            .putBoolean("wake_alarm_removed", true).apply()
    }

    @JvmStatic fun notificationSettings(context: Context) {
        safeStart(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    private fun safeStart(context: Context, intent: Intent) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { }
    }
}

class SleepAlarmReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SleepSystem.BEDTIME -> {
                if (!SleepSystem.expectedBedtime(context, intent.getLongExtra("scheduled_at", 0))) return
                if (SleepSystem.canNotify(context)) {
                    val note = NotificationCompat.Builder(context, SleepSystem.REMINDER_CHANNEL)
                        .setSmallIcon(R.drawable.ic_sleep_moon).setContentTitle(text(R.string.copy_time_to_wind_down))
                        .setContentText(text(R.string.copy_open_dreamshield_to_start_tonight))
                        .setContentIntent(SleepSystem.openPending(context, SleepSystem.REMINDER_ID))
                        .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
                    context.getSystemService(NotificationManager::class.java).notify(SleepSystem.REMINDER_ID, note)
                }
                SleepSystem.sync(context)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> SleepSystem.sync(context)
        }
    }
}
