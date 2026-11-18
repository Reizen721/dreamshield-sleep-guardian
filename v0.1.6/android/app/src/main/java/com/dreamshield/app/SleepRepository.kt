package com.dreamshield.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

/** Local, bounded and offline-first. No sensor access, network traffic or background work. */
class SleepRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("dreamshield_sleep_v1", Context.MODE_PRIVATE)
    @Volatile private var writeRevision = 0L
    var storageError by mutableStateOf<String?>(null)
        private set
    var snapshot by mutableStateOf(read())
        private set

    @Synchronized fun reload() {
        awaitWrites()
        // Do not replace a visible, unsaved snapshot with an older disk version.
        // The next explicit action retries the full snapshot and reports success only after commit.
        if (storageError == null) snapshot = read(await = false)
    }

    @Synchronized fun startNight(
        plannedWakeMillis: Long? = null,
        scene: Int = 0,
        now: Long = System.currentTimeMillis(),
        sourceId: String? = null,
        kind: String = "night",
    ): SleepNight {
        reload()
        require(snapshot.active == null) { text(R.string.copy_end_or_edit_the_current_night_first) }
        val night = SleepMath.validateNight(SleepNight(startMillis = now, plannedWakeMillis = plannedWakeMillis,
            scene = scene, zoneId = ZoneId.systemDefault().id, sourceId = sourceId, kind = kind), now)
        requireNoOverlap(night)
        persist(snapshot.copy(nights = (listOf(night) + snapshot.nights).sortedByDescending { it.startMillis }.take(MAX_NIGHTS)))
        return night
    }

    @Synchronized fun finishNight(now: Long = System.currentTimeMillis()): SleepNight {
        reload()
        val night = snapshot.active ?: throw IllegalArgumentException(text(R.string.copy_no_active_night))
        val updated = SleepMath.finishNight(night, now)
        replace(updated)
        return updated
    }

    @Synchronized fun addNight(
        startMillis: Long,
        endMillis: Long,
        scene: Int = 0,
        sourceId: String? = null,
    ): SleepNight {
        reload()
        val night = SleepMath.validateNight(SleepNight(startMillis = startMillis, endMillis = endMillis,
            scene = scene, sourceId = sourceId, zoneId = ZoneId.systemDefault().id))
        requireNoOverlap(night)
        persist(snapshot.copy(nights = (snapshot.nights + night).sortedByDescending { it.startMillis }.take(MAX_NIGHTS)))
        return night
    }

    @Synchronized fun markAwake(now: Long = System.currentTimeMillis()): SleepNight {
        reload()
        val night = snapshot.active ?: throw IllegalArgumentException(text(R.string.copy_start_tonight_before_logging_an_awakening))
        require(now >= night.startMillis) { text(R.string.copy_awakening_cannot_precede_bedtime) }
        if (night.wakings.lastOrNull()?.let { now - it in 0..59_999 } == true) return night
        require(night.wakings.size < 100) { text(R.string.copy_awakening_limit_reached_for_this_night) }
        val updated = SleepMath.validateNight(night.copy(wakings = (night.wakings + now).sorted()), now)
        replace(updated)
        return updated
    }

    @Synchronized fun editNight(
        id: String,
        startMillis: Long,
        endMillis: Long?,
        plannedWakeMillis: Long? = null,
    ): SleepNight {
        reload()
        val night = snapshot.nights.find { it.id == id } ?: throw IllegalArgumentException(text(R.string.copy_this_entry_was_removed))
        val updated = SleepMath.validateNight(night.copy(startMillis = startMillis, endMillis = endMillis,
            plannedWakeMillis = plannedWakeMillis,
            wakings = night.wakings.filter { it >= startMillis && (endMillis == null || it <= endMillis) }))
        require(updated.endMillis != null || snapshot.active?.id in listOf(null, id)) { text(R.string.copy_another_night_is_active) }
        requireNoOverlap(updated)
        replace(updated)
        return updated
    }

    @Synchronized fun saveFeedback(
        id: String,
        rating: Int?,
        comfort: String?,
        rememberedWakings: Int?,
        note: String,
    ): SleepNight {
        reload()
        val night = snapshot.nights.find { it.id == id } ?: throw IllegalArgumentException(text(R.string.copy_this_entry_was_removed))
        require(night.endMillis != null) { text(R.string.copy_finish_tonight_before_adding_feedback) }
        val feedback = SleepMath.validateFeedback(MorningFeedback(rating, comfort, rememberedWakings, note.trim()))
        val updated = night.copy(feedback = feedback)
        replace(updated)
        return updated
    }

    @Synchronized fun savePlan(plan: SleepPlan) {
        reload()
        persist(snapshot.copy(plan = SleepMath.validatePlan(plan)))
    }

    @Synchronized fun skipTonight(now: Long = System.currentTimeMillis()) {
        reload()
        val zone = ZoneId.systemDefault()
        val date = snapshot.active?.date ?: Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        persist(snapshot.copy(plan = snapshot.plan.copy(skippedNightDate = date)))
    }

    @Synchronized fun deleteNight(id: String) {
        reload()
        val night = snapshot.nights.find { it.id == id } ?: return
        val source = SleepMath.report(snapshot, night).sourceId
        val end = night.endMillis ?: System.currentTimeMillis()
        persist(snapshot.copy(nights = snapshot.nights.filterNot { it.id == id },
            samples = snapshot.samples.filterNot { it.sourceId == source && it.measuredAtMillis in night.startMillis..end },
            events = snapshot.events.filterNot { it.sourceId == source && it.atMillis in night.startMillis..end }))
    }

    @Synchronized fun clearAll() { persist(SleepSnapshot()) }

    @Synchronized fun ingestSamples(samples: List<RoomSample>, now: Long = System.currentTimeMillis()) {
        val valid = samples.filter { SleepMath.validSample(it, now) && now - it.measuredAtMillis <= RETENTION_MILLIS }
        if (valid.isEmpty()) return
        val existing = snapshot.samples.associateBy { it.sourceId to it.measuredAtMillis }
        val fresh = valid.distinctBy { it.sourceId to it.measuredAtMillis }.filterNot { (it.sourceId to it.measuredAtMillis) in existing }
        if (fresh.isEmpty()) return
        val all = compactSamples(snapshot.samples + fresh, now)
        var nights = snapshot.nights
        val active = snapshot.active
        if (active != null && active.sourceId == null) {
            fresh.filter { it.measuredAtMillis >= active.startMillis }.maxByOrNull { it.measuredAtMillis }?.let { sample ->
                nights = nights.map { if (it.id == active.id) it.copy(sourceId = sample.sourceId) else it }
            }
        }
        persist(snapshot.copy(nights = nights, samples = all), durable = false)
    }

    @Synchronized fun ingestEvents(events: List<NightEvent>, now: Long = System.currentTimeMillis()) {
        val valid = events.filter { SleepMath.validEvent(it, now) && now - it.atMillis <= RETENTION_MILLIS }
        if (valid.isEmpty()) return
        val keys = snapshot.events.mapTo(hashSetOf()) { it.sourceId to it.id }
        val fresh = valid.distinctBy { it.sourceId to it.id }.filterNot { (it.sourceId to it.id) in keys }
        if (fresh.isEmpty()) return
        persist(snapshot.copy(events = (snapshot.events + fresh).filter { now - it.atMillis <= RETENTION_MILLIS }
            .sortedBy { it.atMillis }.takeLast(MAX_EVENTS)), durable = false)
    }

    fun report(night: SleepNight, now: Long = System.currentTimeMillis()): NightReport = SleepMath.report(snapshot, night, now)
    fun insights(days: Int = 7, now: Long = System.currentTimeMillis()): SleepInsights = SleepMath.insights(snapshot, days, now)
    fun exportJson(): String = encode(snapshot).put("exported_at", Instant.now().toString())
        .put("recording_basis", "user_entered_in_bed_period_not_measured_sleep")
        .put("privacy", "local_export_may_contain_personal_diary_and_room_observations").toString(2)

    private fun requireNoOverlap(candidate: SleepNight) {
        SleepMath.validateNoOverlap(snapshot.nights, candidate)
    }
    private fun replace(night: SleepNight) = persist(snapshot.copy(
        nights = snapshot.nights.map { if (it.id == night.id) night else it }.sortedByDescending { it.startMillis }))
    private fun persist(value: SleepSnapshot, durable: Boolean = true) {
        val revision = ++writeRevision
        if (durable) {
            val saved = runCatching { writer.submit<Boolean> { write(value) }.get() }.getOrDefault(false)
            require(saved) { storageError ?: text(R.string.copy_could_not_save_locally_check_available_storage) }
            snapshot = value
        } else {
            snapshot = value
            writer.execute {
                // Only the latest queued observation snapshot needs encoding. Writes already
                // in flight finish before later edits because every instance uses this queue.
                if (revision == writeRevision) write(value)
            }
        }
    }

    private fun write(value: SleepSnapshot): Boolean {
        val saved = runCatching { preferences.edit().putString("snapshot", encode(value).toString()).commit() }.getOrDefault(false)
        storageError = if (saved) null else text(R.string.copy_could_not_save_locally_check_available_storage)
        return saved
    }
    private fun awaitWrites() { writer.submit { }.get() }
    private fun read(await: Boolean = true): SleepSnapshot {
        if (await) awaitWrites()
        return try {
            val raw = preferences.getString("snapshot", null)
            if (raw.isNullOrBlank()) SleepSnapshot() else decode(JSONObject(raw))
        } catch (_: Exception) { SleepSnapshot() }
    }

    private fun encode(value: SleepSnapshot): JSONObject {
        fun feedback(f: MorningFeedback) = JSONObject().putNullable("rating", f.rating).putNullable("comfort", f.comfort)
            .putNullable("remembered_wakings", f.rememberedWakings).put("note", f.note)
        val plan = value.plan
        return JSONObject().put("schema", 1).put("plan", JSONObject()
            .put("weekday_bed", plan.weekdayBed).put("weekday_wake", plan.weekdayWake)
            .put("weekend_bed", plan.weekendBed).put("weekend_wake", plan.weekendWake)
            .put("reminder_lead", plan.reminderLeadMinutes).put("reminder_enabled", plan.reminderEnabled)
            .putNullable("skipped_night", plan.skippedNightDate))
            .put("nights", JSONArray(value.nights.map { n ->
                JSONObject().put("id", n.id).put("start", n.startMillis).putNullable("end", n.endMillis)
                    .putNullable("planned_wake", n.plannedWakeMillis).put("scene", n.scene)
                    .put("wakings", JSONArray(n.wakings)).putNullable("feedback", n.feedback?.let(::feedback))
                    .put("zone", n.zoneId).putNullable("source", n.sourceId).put("kind", n.kind)
            }))
            .put("samples", JSONArray(value.samples.map { s -> JSONArray(listOf(s.sourceId, s.measuredAtMillis, s.temperatureC, s.humidityPercent)) }))
            .put("events", JSONArray(value.events.map { e -> JSONArray(listOf(e.id, e.atMillis, e.label, e.sourceId, e.kind, e.detail)) }))
    }

    private fun decode(raw: JSONObject): SleepSnapshot {
        require(raw.optInt("schema") == 1)
        val now = System.currentTimeMillis()
        val p = raw.optJSONObject("plan")
        val plan = runCatching { SleepMath.validatePlan(if (p == null) SleepPlan() else SleepPlan(
            p.optString("weekday_bed", "23:00"), p.optString("weekday_wake", "07:00"),
            p.optString("weekend_bed", "23:30"), p.optString("weekend_wake", "08:00"),
            p.optInt("reminder_lead", 30), p.optBoolean("reminder_enabled"), p.nullableString("skipped_night")))
        }.getOrDefault(SleepPlan())
        val nights = raw.optJSONArray("nights").objects(MAX_NIGHTS).mapNotNull { n -> runCatching {
            val f = n.optJSONObject("feedback")
            SleepMath.validateNight(SleepNight(
                n.getString("id"), n.getLong("start"), n.nullableLong("end"), n.nullableLong("planned_wake"),
                n.optInt("scene"), n.optJSONArray("wakings").longs(100),
                f?.let { MorningFeedback(it.nullableInt("rating"), it.nullableString("comfort"),
                    it.nullableInt("remembered_wakings"), it.optString("note", "")) },
                n.optString("zone", ZoneId.systemDefault().id), n.nullableString("source"), n.optString("kind", "night")
            ), now)
        }.getOrNull() }.sortedByDescending { it.startMillis }.let { rows ->
            // Corrupt/foreign data cannot create multiple simultaneously running sessions.
            var activeSeen = false
            rows.filter { if (it.endMillis != null) true else if (activeSeen) false else { activeSeen = true; true } }
        }
        val samples = raw.optJSONArray("samples").arrays(MAX_SAMPLES).mapNotNull { a -> runCatching {
            RoomSample(a.getString(0), a.getLong(1), a.getDouble(2), a.getDouble(3))
        }.getOrNull()?.takeIf { SleepMath.validSample(it, now) } }
        val events = raw.optJSONArray("events").arrays(MAX_EVENTS).mapNotNull { a -> runCatching {
            NightEvent(a.getString(0), a.getLong(1), a.getString(2), a.getString(3), a.getString(4), a.getString(5))
        }.getOrNull()?.takeIf { SleepMath.validEvent(it, now) } }
        return SleepSnapshot(nights, plan, compactSamples(samples, now), events.filter { now - it.atMillis <= RETENTION_MILLIS }.sortedBy { it.atMillis })
    }

    companion object {
        // Shared across short-lived scheduler/widget repositories. A barrier before a
        // reload orders history persistence before an explicit edit without a database.
        private val writer = Executors.newSingleThreadExecutor { task ->
            Thread(task, "dreamshield-sleep-store").apply { isDaemon = true }
        }
        const val MAX_NIGHTS = 400
        const val MAX_SAMPLES = 30_000
        const val MAX_EVENTS = 4_000
        const val RETENTION_MILLIS = 35L * 24 * 60 * 60 * 1000

        /** Archived charts keep real minute-spaced measurements, never synthetic interpolated values. */
        fun compactSamples(samples: List<RoomSample>, now: Long): List<RoomSample> {
            val fresh = samples.filter { now - it.measuredAtMillis <= RETENTION_MILLIS }
                .distinctBy { it.sourceId to it.measuredAtMillis }.sortedBy { it.measuredAtMillis }
            val old = fresh.filter { now - it.measuredAtMillis > 24 * 60 * 60_000L }
                .distinctBy { it.sourceId to (it.measuredAtMillis / 60_000) }
            val recent = fresh.filter { now - it.measuredAtMillis <= 24 * 60 * 60_000L }
            return (old + recent).sortedBy { it.measuredAtMillis }.takeLast(MAX_SAMPLES)
        }
    }
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
private fun JSONObject.nullableLong(key: String): Long? {
    if (isNull(key) || !has(key)) return null
    val number = opt(key) as? Number ?: throw IllegalArgumentException("Invalid numeric field")
    val value = number.toDouble()
    require(value.isFinite() && value == number.toLong().toDouble()) { "Invalid numeric field" }
    return number.toLong()
}
private fun JSONObject.nullableInt(key: String): Int? = nullableLong(key)?.let {
    require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid integer field" }
    it.toInt()
}
private fun JSONObject.nullableString(key: String): String? {
    if (isNull(key) || !has(key)) return null
    return opt(key) as? String ?: throw IllegalArgumentException("Invalid string field")
}
private fun JSONArray?.objects(max: Int): List<JSONObject> = if (this == null) emptyList() else
    (0 until length().coerceAtMost(max)).mapNotNull { optJSONObject(it) }
private fun JSONArray?.arrays(max: Int): List<JSONArray> = if (this == null) emptyList() else
    (0 until length().coerceAtMost(max)).mapNotNull { optJSONArray(it) }
private fun JSONArray?.longs(max: Int): List<Long> = if (this == null) emptyList() else
    (0 until length().coerceAtMost(max)).mapNotNull { (opt(it) as? Number)?.toLong() }
