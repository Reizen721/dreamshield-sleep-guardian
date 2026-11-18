package com.dreamshield.app

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.math.sqrt

/** User-entered rest periods are deliberately distinct from measured sleep. */
data class MorningFeedback(
    val rating: Int? = null,
    val comfort: String? = null,
    val rememberedWakings: Int? = null,
    val note: String = "",
)

data class SleepNight(
    val id: String = UUID.randomUUID().toString(),
    val startMillis: Long,
    val endMillis: Long? = null,
    val plannedWakeMillis: Long? = null,
    val scene: Int = 0,
    val wakings: List<Long> = emptyList(),
    val feedback: MorningFeedback? = null,
    val zoneId: String = ZoneId.systemDefault().id,
    val sourceId: String? = null,
    val kind: String = "night",
) {
    val date: String get() = if (kind == "nap")
        Instant.ofEpochMilli(startMillis).atZone(SleepMath.safeZone(zoneId)).toLocalDate().toString()
        else SleepMath.nightDate(startMillis, SleepMath.safeZone(zoneId))
    val durationMinutes: Long? get() = endMillis?.let { (it - startMillis).coerceAtLeast(0) / 60_000 }
    val requiresTimeCorrection: Boolean get() = endMillis == null && System.currentTimeMillis() - startMillis > SleepMath.MAX_NIGHT_MILLIS
}

data class SleepPlan(
    val weekdayBed: String = "23:00",
    val weekdayWake: String = "07:00",
    val weekendBed: String = "23:30",
    val weekendWake: String = "08:00",
    val reminderLeadMinutes: Int = 30,
    val reminderEnabled: Boolean = false,
    val skippedNightDate: String? = null,
)

data class RoomSample(
    val sourceId: String,
    val measuredAtMillis: Long,
    val temperatureC: Double,
    val humidityPercent: Double,
)

data class NightEvent(
    val id: String,
    val atMillis: Long,
    val label: String,
    val sourceId: String,
    val kind: String = "observation",
    val detail: String = "",
)

data class TimeSpan(val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)
}

data class SleepSnapshot(
    val nights: List<SleepNight> = emptyList(),
    val plan: SleepPlan = SleepPlan(),
    val samples: List<RoomSample> = emptyList(),
    val events: List<NightEvent> = emptyList(),
) {
    val active: SleepNight? get() = nights.firstOrNull { it.endMillis == null }
    val latest: SleepNight? get() = nights.maxByOrNull { it.startMillis }
}

data class NightReport(
    val night: SleepNight,
    val points: List<RoomSample>,
    val events: List<NightEvent>,
    val tempMeanC: Double?,
    val humidityMeanPercent: Double?,
    val tempMinC: Double?,
    val tempMaxC: Double?,
    val coveredMillis: Long,
    val gaps: List<TimeSpan>,
    val sourceId: String?,
) {
    val temperatureMeanC: Double? get() = tempMeanC
    val coveragePercent: Int get() {
        val end = night.endMillis ?: return 0
        val duration = end - night.startMillis
        return if (duration > 0) ((coveredMillis * 100.0 / duration).toInt()).coerceIn(0, 100) else 0
    }
}

data class ComfortAssociation(
    val comfort: String,
    val nights: Int,
    val temperatureMeanC: Double,
    val humidityMeanPercent: Double,
)

data class SleepInsights(
    val nightsCount: Int,
    val feedbackCount: Int,
    val meanInBedMinutes: Double?,
    val bedtimeDeviationMinutes: Double?,
    val wakeDeviationMinutes: Double?,
    val comfortAssociations: List<ComfortAssociation>,
    val dataNightsCount: Int,
)

object SleepMath {
    const val MAX_NIGHT_MILLIS = 36L * 60 * 60 * 1000
    const val MAX_GAP_MILLIS = 90_000L

    fun safeZone(id: String): ZoneId = try { ZoneId.of(id) } catch (_: Exception) { ZoneId.systemDefault() }
    fun nightDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return (if (local.hour < 12) local.toLocalDate().minusDays(1) else local.toLocalDate()).toString()
    }
    fun validTime(value: String): Boolean = value.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))
    fun validatePlan(plan: SleepPlan): SleepPlan {
        require(listOf(plan.weekdayBed, plan.weekdayWake, plan.weekendBed, plan.weekendWake).all(::validTime)) { message("时间须使用 24 小时制 HH:mm", "Use 24-hour time (HH:mm).") }
        require(plan.reminderLeadMinutes in 0..180) { message("睡前提醒须提前 0–180 分钟", "Set the reminder 0–180 minutes before bed.") }
        require(plan.skippedNightDate == null || runCatching { LocalDate.parse(plan.skippedNightDate) }.isSuccess) { message("跳过日期不正确", "Invalid skip date") }
        return plan
    }
    fun isWeekend(date: LocalDate) = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    fun plannedBed(plan: SleepPlan, date: LocalDate, zone: ZoneId): ZonedDateTime =
        date.atTime(LocalTime.parse(if (isWeekend(date)) plan.weekendBed else plan.weekdayBed)).atZone(zone)
    fun plannedWake(plan: SleepPlan, date: LocalDate, zone: ZoneId): ZonedDateTime =
        date.atTime(LocalTime.parse(if (isWeekend(date)) plan.weekendWake else plan.weekdayWake)).atZone(zone)

    fun nextWake(plan: SleepPlan, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
        validatePlan(plan)
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0L..8L).asSequence().map { plannedWake(plan, date.plusDays(it), zone).toInstant().toEpochMilli() }
            .first { it > now }
    }
    fun nextBedtime(plan: SleepPlan, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
        validatePlan(plan)
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0L..8L).asSequence().map { plannedBed(plan, date.plusDays(it), zone).toInstant().toEpochMilli() }
            .first { it > now && nightDate(it, zone) != plan.skippedNightDate }
    }
    fun validateNight(night: SleepNight, now: Long = System.currentTimeMillis()): SleepNight {
        require(night.id.isNotBlank() && night.id.length <= 128) { message("记录编号不正确", "Invalid night ID") }
        require(night.startMillis in 1..now) { message("上床时间不能在未来", "Bedtime cannot be in the future.") }
        require(night.endMillis == null || (night.endMillis > night.startMillis && night.endMillis <= now
            && night.endMillis - night.startMillis <= MAX_NIGHT_MILLIS)) { message("起床时间须晚于上床，且记录不超过 36 小时", "Wake time must follow bedtime, within 36 hours.") }
        require(night.plannedWakeMillis == null || night.plannedWakeMillis in night.startMillis..(night.startMillis + MAX_NIGHT_MILLIS)) { message("预计起床时间须在上床后的 36 小时内", "Planned wake time must be within 36 hours of bedtime.") }
        require(night.scene in 0..2 && night.kind in setOf("night", "nap")) { message("场景或记录类型不正确", "Invalid scene or entry type") }
        require(night.sourceId == null || validSource(night.sourceId)) { message("设备来源不正确", "Invalid device source") }
        require(runCatching { ZoneId.of(night.zoneId) }.isSuccess) { message("时区不正确", "Invalid time zone") }
        val end = night.endMillis ?: now
        require(night.wakings.size <= 100 && night.wakings.all { it in night.startMillis..end }) { message("夜醒标记须在这次记录期间", "Awakenings must be within this night.") }
        night.feedback?.let(::validateFeedback)
        return night
    }
    fun validateFeedback(feedback: MorningFeedback): MorningFeedback {
        require(feedback.rating == null || feedback.rating in 1..5) { message("晨起精神感受须为 1–5", "Rate morning energy from 1 to 5.") }
        require(feedback.comfort == null || feedback.comfort in setOf("cold", "comfortable", "hot")) { message("请选择偏冷、舒适或偏热", "Choose cool, comfortable or warm.") }
        require(feedback.rememberedWakings == null || feedback.rememberedWakings in 0..50) { message("自述夜醒次数须为 0–50", "Enter 0–50 awakenings.") }
        require(feedback.note.length <= 1000) { message("备注最多 1000 字", "Use up to 1000 characters.") }
        return feedback
    }
    fun finishNight(night: SleepNight, now: Long = System.currentTimeMillis()): SleepNight {
        require(night.endMillis == null) { message("这条记录已经结束", "This night has ended.") }
        require(now - night.startMillis <= MAX_NIGHT_MILLIS) {
            message("这条记录已超过 36 小时。请在「记录」中选择「修改时间」，补上实际起床时间；原记录仍保留在手机。", "This night is over 36 hours long. Open Records and edit its actual wake time.")
        }
        return validateNight(night.copy(endMillis = now), now)
    }
    fun validateNoOverlap(nights: List<SleepNight>, candidate: SleepNight): SleepNight {
        val end = candidate.endMillis ?: Long.MAX_VALUE
        require(nights.none { other ->
            other.id != candidate.id && candidate.startMillis < (other.endMillis ?: Long.MAX_VALUE) && end > other.startMillis
        }) { message("记录时段不能与已有记录重叠，请检查日期", "This entry overlaps another night. Check the dates.") }
        return candidate
    }
    fun validSource(source: String) = source.isNotBlank() && source.length <= 300 && !source.any { it.isISOControl() }
    fun validSample(sample: RoomSample, now: Long = System.currentTimeMillis()): Boolean =
        validSource(sample.sourceId) && sample.measuredAtMillis in 1..(now + 60_000) &&
            sample.temperatureC.isFinite() && sample.temperatureC in -40.0..125.0 &&
            sample.humidityPercent.isFinite() && sample.humidityPercent in 0.0..100.0
    fun validEvent(event: NightEvent, now: Long = System.currentTimeMillis()): Boolean =
        validSource(event.sourceId) && event.id.isNotBlank() && event.id.length <= 200 &&
            event.atMillis in 1..(now + 60_000) && event.label.isNotBlank() && event.label.length <= 120 &&
            event.kind.length <= 40 && event.detail.length <= 1000

    fun report(snapshot: SleepSnapshot, night: SleepNight, now: Long = System.currentTimeMillis()): NightReport {
        val end = night.endMillis ?: now.coerceAtLeast(night.startMillis)
        val eligible = snapshot.samples.filter { validSample(it, now) && it.measuredAtMillis in night.startMillis..end }
        val eligibleEvents = snapshot.events.filter { validEvent(it, now) && it.atMillis in night.startMillis..end }
        // Never average two rooms when a phone changes computer or board.
        val source = night.sourceId ?: eligible.groupBy { it.sourceId }.maxByOrNull { it.value.size }?.key
            ?: eligibleEvents.groupBy { it.sourceId }.maxByOrNull { it.value.size }?.key
        val points = eligible.filter { it.sourceId == source }.distinctBy { it.measuredAtMillis }.sortedBy { it.measuredAtMillis }
        val events = eligibleEvents.filter { it.sourceId == source }.sortedBy { it.atMillis }
        val spans = points.zipWithNext().mapNotNull { (a, b) ->
            val interval = b.measuredAtMillis - a.measuredAtMillis
            if (interval in 1..MAX_GAP_MILLIS) TimeSpan(a.measuredAtMillis, b.measuredAtMillis) else null
        }
        val gaps = mutableListOf<TimeSpan>()
        var cursor = night.startMillis
        spans.forEach { span ->
            if (span.startMillis > cursor) gaps += TimeSpan(cursor, span.startMillis)
            cursor = maxOf(cursor, span.endMillis)
        }
        if (cursor < end) gaps += TimeSpan(cursor, end)
        return NightReport(night, points, events,
            points.takeIf { it.isNotEmpty() }?.map { it.temperatureC }?.average(),
            points.takeIf { it.isNotEmpty() }?.map { it.humidityPercent }?.average(),
            points.minOfOrNull { it.temperatureC }, points.maxOfOrNull { it.temperatureC },
            spans.sumOf { it.durationMillis }, gaps, source)
    }

    private fun circularDeviation(minutes: List<Int>): Double? {
        if (minutes.size < 2) return null
        // Select the midnight cut minimizing spread, so 23:55 and 00:05 are ten minutes apart.
        val possibilities = minutes.map { pivot ->
            minutes.map { if (it < pivot) it + 1440.0 else it.toDouble() }
        }
        val values = possibilities.minBy { v -> val mean = v.average(); v.sumOf { (it - mean) * (it - mean) } }
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    fun insights(snapshot: SleepSnapshot, days: Int = 7, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): SleepInsights {
        require(days in setOf(7, 30)) { message("回顾范围须为 7 或 30 天", "Choose 7 or 30 days.") }
        val today = LocalDate.parse(nightDate(now, zone))
        val earliest = today.minusDays((days - 1).toLong())
        val nights = snapshot.nights.filter {
            it.endMillis != null && it.kind == "night" && runCatching { LocalDate.parse(it.date) in earliest..today }.getOrDefault(false)
        }
        val feedback = nights.filter { it.feedback != null }
        fun minutes(time: Long, night: SleepNight): Int = Instant.ofEpochMilli(time).atZone(safeZone(night.zoneId)).let { it.hour * 60 + it.minute }
        val reports = nights.associateWith { report(snapshot, it, now) }
        val associations = if (feedback.size >= 7) listOf("cold", "comfortable", "hot").mapNotNull { comfort ->
            val data = feedback.filter { it.feedback?.comfort == comfort }.mapNotNull { night ->
                reports[night]?.takeIf { it.coveredMillis >= 30 * 60_000L && it.tempMeanC != null && it.humidityMeanPercent != null }
            }
            if (data.size < 3) null else ComfortAssociation(comfort, data.size,
                data.map { it.tempMeanC!! }.average(), data.map { it.humidityMeanPercent!! }.average())
        } else emptyList()
        return SleepInsights(nights.size, feedback.size, nights.takeIf { it.isNotEmpty() }?.map { (it.endMillis!! - it.startMillis) / 60_000.0 }?.average(),
            circularDeviation(nights.map { minutes(it.startMillis, it) }),
            circularDeviation(nights.map { minutes(it.endMillis!!, it) }),
            associations, reports.values.count { it.points.isNotEmpty() })
    }
}
