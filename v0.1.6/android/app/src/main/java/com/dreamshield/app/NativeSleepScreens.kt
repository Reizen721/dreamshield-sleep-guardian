@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.dreamshield.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme as Theme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

@Composable internal fun SleepNote(text: String) {
    Text(text, style = Theme.typography.bodySmall, color = Theme.colorScheme.onSurfaceVariant)
}
@Composable internal fun SleepRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, Modifier.weight(1f), style = Theme.typography.bodyMedium, color = Theme.colorScheme.onSurfaceVariant)
        Text(value, style = Theme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
internal fun comfortLabel(value: String?) = when(value) { "cold" -> text(R.string.copy_cool); "hot" -> text(R.string.copy_warm); "comfortable" -> text(R.string.copy_comfortable); else -> text(R.string.copy_unrecorded) }

@Composable fun NativeTonightCard(state: NativeState) {
    val sleep = state.sleep
    val active = sleep.repo.snapshot.active
    DreamPaperCard(if (active != null) text(R.string.copy_tonight_is_underway) else text(R.string.copy_tonight_s_journal), tone = 1) {
        if (active != null) {
            Text(text(R.string.copy_in_bed_since_s, SleepCoordinator.time(active.startMillis)), style = Theme.typography.bodyLarge)
        }
        SleepRow(text(R.string.copy_planned_wake_time), SleepCoordinator.time(active?.plannedWakeMillis ?: sleep.plannedWake()))
        if(active == null) {
            SleepRow(text(R.string.copy_connections),state.connectionTitle)
            SleepRow(text(R.string.copy_room),if(state.temperature!="—") "${state.temperature} °C · ${state.humidity} %RH" else text(R.string.copy_no_climate_reading))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = { state.showPage("settings") }, label = { Text(text(R.string.copy_sleep_schedule)) })
                SuggestionChip(onClick = { state.showPage("control") }, label = {
                    Text(if(state.online && state.status.optJSONObject("config")?.optBoolean("environment_recording_enabled") == true) text(R.string.copy_climate_logging_on) else text(R.string.copy_check_devices)) })
            }
            Button(onClick = { sleep.start(state.scene, state.sleepSourceId()) }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(text(R.string.copy_start_tonight)) }
            SleepNote(if(state.online) text(R.string.copy_the_journal_stays_on_this_phone) else text(R.string.copy_you_can_journal_offline))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { sleep.nightMode = true }, Modifier.weight(1f).heightIn(min=48.dp)) { Text(text(R.string.copy_return_to_good_night)) }
                OutlinedButton(onClick = { sleep.finish(); state.showPage("history"); state.syncSleepHistory() }, Modifier.weight(1f).heightIn(min=48.dp)) { Text(text(R.string.copy_i_m_up)) }
            }
        }
        if(sleep.notice.isNotBlank()) SleepNote(sleep.notice)
        sleep.repo.storageError?.let { Text(it,color=Theme.colorScheme.error) }
    }
    val recent = sleep.repo.snapshot.latest?.takeIf { it.endMillis != null }
    if(active == null && recent != null && recent.feedback == null) {
        OutlinedButton(onClick = { sleep.feedbackNightId = recent.id }, Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(text(R.string.copy_last_night_s_feedback)) }
    }
}

@Composable fun NativeSleepRecords(state: NativeState) {
    val sleep = state.sleep
    val nights = sleep.repo.snapshot.nights.sortedByDescending { it.startMillis }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var range by remember { mutableIntStateOf(0) }
    val selected = nights.find { it.id == sleep.selectedNightId } ?: nights.firstOrNull()
    LaunchedEffect(selected?.date) { selected?.let { month=YearMonth.from(LocalDate.parse(it.date)) } }
    DreamPaperCard(text(R.string.copy_sleep_journal)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick={month=month.minusMonths(1)}) { Text(text(R.string.copy_previous_month)) }
            Text(text(R.string.copy_2_s_1_s, month.year, month.monthValue),Modifier.weight(1f),style=Theme.typography.titleMedium)
            TextButton(onClick={month=month.plusMonths(1)},enabled=month<YearMonth.now()) { Text(text(R.string.copy_next_month)) }
        }
        Row { listOf(text(R.string.copy_m),text(R.string.copy_t),text(R.string.copy_w),text(R.string.copy_t_474),text(R.string.copy_f),text(R.string.copy_s_476),text(R.string.copy_s_477)).forEach { Text(it,Modifier.weight(1f),style=Theme.typography.labelMedium) } }
        val first = month.atDay(1).dayOfWeek.value - 1
        val slots = ((first + month.lengthOfMonth() + 6)/7)*7
        for(row in 0 until slots/7) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            for(col in 0..6) {
                val day = row*7+col-first+1
                if(day !in 1..month.lengthOfMonth()) { Spacer(Modifier.weight(1f).height(44.dp)); continue }
                val date = month.atDay(day).toString()
                val matches = nights.filter { it.date == date }
                val checked = matches.any { it.id == selected?.id }
                Surface(Modifier.weight(1f).height(44.dp).clip(CircleShape).clickable(enabled=matches.isNotEmpty()) {
                    sleep.selectedNightId=matches.first().id
                },color=if(checked) Theme.colorScheme.primaryContainer else Color.Transparent,shape=CircleShape) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                        Text(day.toString(),color=if(matches.isEmpty()) Theme.colorScheme.onSurfaceVariant else Theme.colorScheme.onSurface)
                        if(matches.isNotEmpty()) Box(Modifier.size(4.dp).background(Theme.colorScheme.primary,CircleShape))
                    }
                }
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick={sleep.addingNight=true},Modifier.weight(1f)) { Text(text(R.string.copy_add_a_night)) }
            OutlinedButton(onClick={state.syncSleepHistory()},enabled=state.usable,modifier=Modifier.weight(1f)) { Text(text(R.string.copy_sync_observations)) }
        }
        SleepNote(sleep.historyMessage)
        if(nights.isEmpty()) Text(text(R.string.copy_no_nights_yet_start_tonight_or_add_a_past_night),style=Theme.typography.bodyMedium)
    }
    if(selected != null) {
        val sameDate = nights.filter { it.date == selected.date }
        if(sameDate.size > 1) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            sameDate.forEach { night -> FilterChip(night.id==selected.id,{sleep.selectedNightId=night.id},label={Text(SleepCoordinator.time(night.startMillis))}) }
        }
        SleepNightReport(state, selected)
    }
    DreamPaperCard(text(R.string.copy_sleep_trends)) {
        DreamSegmentedTrack(listOf(text(R.string.copy_7_days),text(R.string.copy_30_days)),range,{range=it})
        val days = if(range==0) 7 else 30
        val snapshot=sleep.repo.snapshot
        val summary = remember(snapshot,days) { sleep.repo.insights(days) }
        SleepRow(text(R.string.copy_nights_logged),text(R.string.copy_s_nights, summary.nightsCount))
        SleepRow(text(R.string.copy_morning_check_ins),text(R.string.copy_s_nights, summary.feedbackCount))
        SleepRow(text(R.string.copy_with_climate_samples),text(R.string.copy_s_nights, summary.dataNightsCount))
        summary.meanInBedMinutes?.let { SleepRow(text(R.string.copy_average_time_in_bed),SleepCoordinator.duration((it*60_000).toLong())) }
        summary.bedtimeDeviationMinutes?.let { SleepRow(text(R.string.copy_bedtime_variation),text(R.string.copy_about_s_min, it.toInt())) }
        summary.wakeDeviationMinutes?.let { SleepRow(text(R.string.copy_wake_time_variation),text(R.string.copy_about_s_min, it.toInt())) }
        if(summary.comfortAssociations.isEmpty()) SleepNote(text(R.string.copy_add_7_nights_of_feedback_with_3_nights_of_climate))
        summary.comfortAssociations.forEach { a ->
            SleepRow(text(R.string.copy_s_s_nights, comfortLabel(a.comfort), a.nights),String.format(java.util.Locale.CHINA,"%.1f °C · %.1f %%RH",a.temperatureMeanC,a.humidityMeanPercent))
        }
        SleepNote(text(R.string.copy_these_trends_use_time_in_bed_and_self_reported_com))
    }
    NativeHealthSleepCard(state)
}

@Composable private fun SleepNightReport(state: NativeState, night: SleepNight) {
    val sleep=state.sleep
    val snapshot=sleep.repo.snapshot
    val report=remember(snapshot,night) { sleep.repo.report(night) }
    var deleting by remember(night.id) { mutableStateOf(false) }
    DreamPaperCard(if(night.endMillis==null) text(R.string.copy_tonight_494) else text(R.string.copy_night_summary)) {
        Text(SleepCoordinator.dateTime(night.startMillis),style=Theme.typography.titleMedium)
        SleepRow(text(R.string.copy_end),night.endMillis?.let { SleepCoordinator.dateTime(it) } ?: text(R.string.copy_in_progress))
        SleepRow(text(R.string.copy_time_in_bed),SleepCoordinator.duration((night.endMillis ?: System.currentTimeMillis())-night.startMillis))
        night.feedback?.let {
            SleepRow(text(R.string.copy_morning_energy),it.rating?.let { value -> text(R.string.copy_s_5_self_rated, value) } ?: text(R.string.copy_not_recorded))
            SleepRow(text(R.string.copy_comfort),comfortLabel(it.comfort))
            if(it.note.isNotBlank()) Text(it.note,style=Theme.typography.bodyMedium)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick={sleep.feedbackNightId=night.id},enabled=night.endMillis!=null,modifier=Modifier.weight(1f)) { Text(if(night.endMillis!=null)text(R.string.copy_add_feedback) else text(R.string.copy_available_after_waking)) }
            OutlinedButton(onClick={sleep.editNightId=night.id},Modifier.weight(1f)) { Text(text(R.string.copy_edit_time)) }
        }
        if(report.points.isEmpty()) {
            Text(text(R.string.copy_no_climate_samples_for_this_night),style=Theme.typography.bodyMedium)
        } else {
            SleepRow(text(R.string.copy_climate_samples),text(R.string.copy_s_synced, report.points.size))
            report.tempMeanC?.let { SleepRow(text(R.string.copy_mean_temperature),String.format(java.util.Locale.CHINA,"%.1f °C",it)) }
            report.humidityMeanPercent?.let { SleepRow(text(R.string.copy_mean_humidity),String.format(java.util.Locale.CHINA,"%.1f %%RH",it)) }
            if(night.endMillis!=null) SleepRow(text(R.string.copy_sampling_coverage),text(R.string.copy_about_s, report.coveragePercent))
            SleepEnvironmentChart(report.points,night.startMillis,night.endMillis ?: System.currentTimeMillis())
        }
        SleepNote(text(R.string.copy_gaps_mark_missing_readings))
        if(night.wakings.isNotEmpty() || report.events.isNotEmpty()) {
            Text(text(R.string.copy_night_events),style=Theme.typography.titleMedium)
            val lines=(night.wakings.map { it to text(R.string.copy_logged_awakenings) }+report.events.map { it.atMillis to observationLabel(it) }).sortedBy{it.first}
            lines.takeLast(30).forEach { SleepRow(SleepCoordinator.time(it.first),it.second) }
        }
        TextButton(onClick={deleting=true}) { Text(text(R.string.copy_delete_night),color=Theme.colorScheme.error) }
    }
    if(deleting) AlertDialog(onDismissRequest={deleting=false},title={Text(text(R.string.copy_delete_this_night))},text={Text(text(R.string.copy_deletes_this_phone_s_entry_and_feedback_pc_records))},
        confirmButton={TextButton(onClick={sleep.act{sleep.repo.deleteNight(night.id)};deleting=false}){Text(text(R.string.copy_delete))}},
        dismissButton={TextButton(onClick={deleting=false}){Text(text(R.string.copy_keep))}})
}

@Composable fun SleepEnvironmentChart(points: List<RoomSample>, start: Long, end: Long) {
    var humidity by remember { mutableStateOf(false) }
    var selected by remember(points) { mutableStateOf<RoomSample?>(null) }
    DreamSegmentedTrack(listOf(text(R.string.copy_temperature),text(R.string.copy_humidity)),if(humidity)1 else 0,{humidity=it==1})
    val sorted=points.sortedBy{it.measuredAtMillis}
    val values=sorted.map{if(humidity)it.humidityPercent else it.temperatureC}
    val low=(values.minOrNull() ?: 0.0)-.5
    val high=(values.maxOrNull() ?: 1.0)+.5
    val color=Theme.colorScheme.primary
    val outline=Theme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        SleepNote(String.format(java.util.Locale.CHINA,"%.1f",high)+(if(humidity)" %RH" else " °C"))
        SleepNote(text(R.string.copy_tap_the_chart_for_a_reading))
    }
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription=text(R.string.copy_s_chart_s_samples_gaps_indicate_missing_data, if(humidity)text(R.string.copy_humidity) else text(R.string.copy_temperature), sorted.size) }
        .pointerInput(sorted,start,end) { detectTapGestures { tap ->
            val at=start+((tap.x/size.width).coerceIn(0f,1f)*(end-start)).toLong()
            selected=sorted.minByOrNull{abs(it.measuredAtMillis-at)}
        } }) {
        drawLine(outline,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())
        val span=(end-start).coerceAtLeast(1)
        fun pos(p:RoomSample)=Offset(((p.measuredAtMillis-start).toDouble()/span*size.width).toFloat(),
            (size.height-((if(humidity)p.humidityPercent else p.temperatureC)-low)/(high-low)*size.height).toFloat())
        sorted.forEachIndexed { i,p ->
            val here=pos(p)
            if(i>0 && p.measuredAtMillis-sorted[i-1].measuredAtMillis<=90_000) drawLine(color,pos(sorted[i-1]),here,2.dp.toPx())
            else drawCircle(color,2.dp.toPx(),here)
        }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { SleepNote(SleepCoordinator.time(start));SleepNote(SleepCoordinator.time(end)) }
    selected?.let { SleepNote("${SleepCoordinator.time(it.measuredAtMillis)} · ${it.temperatureC} °C · ${it.humidityPercent} %RH") }
}

private fun observationLabel(event: NightEvent): String {
    if (event.kind != "observation") return event.label
    return when (event.label) {
        "观察到盖被", "Blanket on" -> text(R.string.copy_blanket_on)
        "观察到被子移开", "Blanket off" -> text(R.string.copy_blanket_off)
        "观察到被子移位", "Blanket shifted" -> text(R.string.copy_blanket_shifted)
        "AI 观察", "AI observation" -> text(R.string.copy_ai_observation_589)
        else -> event.label
    }
}
