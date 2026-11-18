package com.dreamshield.app

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Phone-only workflow: starting a night never enables a PC task or a model. */
class SleepCoordinator(private val activity: Activity) {
    val repo = SleepRepository(activity)
    var scheduleStatus by mutableStateOf(SleepSystem.sync(activity))
    var nightMode by mutableStateOf(repo.snapshot.active != null)
    var notice by mutableStateOf("")
    var selectedNightId by mutableStateOf<String?>(null)
    var feedbackNightId by mutableStateOf<String?>(null)
    var editNightId by mutableStateOf<String?>(null)
    var addingNight by mutableStateOf(false)
    var historyMessage by mutableStateOf(text(R.string.copy_connect_the_pc_to_sync_climate_data))
    var healthMessage by mutableStateOf("")
    val health = HealthSleepBridge(activity)
    var healthResult by mutableStateOf<HealthSleepResult?>(null)
    var healthBusy by mutableStateOf(false)
    var healthRequest = 0
    fun act(action: () -> Unit) {
        runCatching(action).onSuccess { SleepWidget.refresh(activity) }.onFailure {
            notice = it.message ?: text(R.string.copy_could_not_save_try_again)
        }
    }
    fun plannedWake(now: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        val local = Instant.ofEpochMilli(now).atZone(zone)
        var date = local.toLocalDate()
        fun wake(d: LocalDate): LocalTime = LocalTime.parse(if (d.dayOfWeek.value >= 6) repo.snapshot.plan.weekendWake else repo.snapshot.plan.weekdayWake)
        var instant = date.atTime(wake(date)).atZone(zone).toInstant().toEpochMilli()
        if (instant <= now) { date = date.plusDays(1); instant = date.atTime(wake(date)).atZone(zone).toInstant().toEpochMilli() }
        return instant
    }
    fun start(scene: Int, sourceId: String?) = act {
        repo.startNight(plannedWake(), scene, sourceId = sourceId)
        nightMode = true
        notice = ""
        scheduleStatus = SleepSystem.sync(activity)
    }
    fun finish() = act {
        val night = repo.finishNight()
        SoundscapeController.stop(activity)
        selectedNightId = night.id
        feedbackNightId = night.id
        nightMode = false
        notice = text(R.string.copy_good_morning_add_a_check_in)
        scheduleStatus = SleepSystem.sync(activity)
    }
    fun awake() = act {
        repo.markAwake()
        notice = text(R.string.copy_awakening_logged)
    }
    fun savePlan(plan: SleepPlan) = act {
        repo.savePlan(plan)
        scheduleStatus = SleepSystem.sync(activity)
        notice = scheduleStatus.message
    }
    fun reload() {
        repo.reload()
        scheduleStatus = SleepSystem.sync(activity)
        SleepWidget.refresh(activity)
    }
    fun action(value: String, scene: Int, sourceId: String?) {
        when(value) {
            "start_tonight" -> if(repo.snapshot.active == null) start(scene, sourceId) else nightMode = true
            "open_sleep" -> nightMode = repo.snapshot.active != null
        }
    }
    companion object {
        fun time(millis: Long) = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        fun dateTime(millis: Long) = DateTimeFormatter.ofPattern(text(R.string.copy_mmm_d_hh_mm), AppLanguage.locale).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        fun editTime(millis: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        fun parseTime(text: String): Long = LocalDateTime.parse(text.trim(), DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun duration(millis: Long): String { val m = (millis.coerceAtLeast(0L) / 60_000); return text(R.string.copy_s_h_s_min, m/60, m%60) }
    }
}
