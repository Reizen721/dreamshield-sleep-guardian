@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.dreamshield.app

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme as Theme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.launch

@Composable fun NativeSleepSettings(state: NativeState) {
    val sleep=state.sleep
    val context=LocalContext.current
    val hostActivity=LocalActivity.current
    val plan=sleep.repo.snapshot.plan
    var weekdayBed by remember(plan){mutableStateOf(plan.weekdayBed)}
    var weekdayWake by remember(plan){mutableStateOf(plan.weekdayWake)}
    var weekendBed by remember(plan){mutableStateOf(plan.weekendBed)}
    var weekendWake by remember(plan){mutableStateOf(plan.weekendWake)}
    var lead by remember(plan){mutableStateOf(plan.reminderLeadMinutes.toString())}
    var reminders by remember(plan){mutableStateOf(plan.reminderEnabled)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        sleep.reload()
        sleep.notice=if(it)text(R.string.copy_bedtime_notifications_allowed) else text(R.string.copy_allow_notifications_in_settings)
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri->
        if(uri!=null) sleep.act {
            context.contentResolver.openOutputStream(uri,"wt")?.use { it.write(sleep.repo.exportJson().toByteArray(Charsets.UTF_8)) }
                ?: error(text(R.string.copy_could_not_write_to_this_location))
            sleep.notice=text(R.string.copy_journal_exported)
        }
    }
    DreamPaperCard(text(R.string.copy_sleep_schedule_528)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fieldWidth=if(maxWidth>=600.dp) (maxWidth-12.dp)/2 else maxWidth
        FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(weekdayBed,{weekdayBed=it.take(5)},Modifier.width(fieldWidth),label={Text(text(R.string.copy_weekday_bedtime_hh_mm))},singleLine=true)
            OutlinedTextField(weekdayWake,{weekdayWake=it.take(5)},Modifier.width(fieldWidth),label={Text(text(R.string.copy_weekday_wake_hh_mm))},singleLine=true)
            OutlinedTextField(weekendBed,{weekendBed=it.take(5)},Modifier.width(fieldWidth),label={Text(text(R.string.copy_weekend_bedtime_hh_mm))},singleLine=true)
            OutlinedTextField(weekendWake,{weekendWake=it.take(5)},Modifier.width(fieldWidth),label={Text(text(R.string.copy_weekend_wake_hh_mm))},singleLine=true)
        }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(text(R.string.copy_bedtime_reminder), modifier = Modifier.weight(1f))
            Switch(reminders,{reminders=it})
        }
        OutlinedTextField(lead,{lead=it.filter(Char::isDigit).take(3)},Modifier.fillMaxWidth(),label={Text(text(R.string.copy_minutes_before_bed_0_180))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        Button(onClick={
            val minutes=lead.toIntOrNull()
            if(minutes==null) sleep.notice=text(R.string.copy_enter_the_reminder_lead_time)
            else {
                sleep.savePlan(plan.copy(weekdayBed=weekdayBed,weekdayWake=weekdayWake,weekendBed=weekendBed,weekendWake=weekendWake,
                    reminderLeadMinutes=minutes,reminderEnabled=reminders,skippedNightDate=null))
                if(reminders && Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },Modifier.fillMaxWidth().heightIn(min=50.dp)){Text(text(R.string.copy_save_schedule))}
        TextButton(onClick={sleep.act{sleep.repo.skipTonight();sleep.reload();sleep.notice=text(R.string.copy_tonight_s_reminder_skipped)}}){Text(text(R.string.copy_skip_tonight))}
        val system=sleep.scheduleStatus
        system.bedtimeAt?.let{SleepRow(text(R.string.copy_next_bedtime_reminder),SleepCoordinator.dateTime(it))}
        SleepNote(system.message)
        OutlinedButton(onClick={SleepSystem.notificationSettings(context)},Modifier.fillMaxWidth()){Text(text(R.string.copy_notification_settings))}
        if(sleep.notice.isNotBlank()) SleepNote(sleep.notice)
    }
    DreamPaperCard(text(R.string.copy_shortcuts)) {
        OutlinedButton(onClick={
            val manager=AppWidgetManager.getInstance(context)
            if(manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(context,SleepWidget::class.java),null,null)
            else sleep.notice=text(R.string.copy_long_press_the_home_screen_and_choose_the_dreamshi)
        },Modifier.fillMaxWidth()){Text(text(R.string.copy_add_home_widget))}
        if(Build.VERSION.SDK_INT>=33) OutlinedButton(onClick={
            runCatching{
                val activity=checkNotNull(hostActivity)
                activity.getSystemService(StatusBarManager::class.java).requestAddTileService(ComponentName(context,SleepTileService::class.java),
                    text(R.string.copy_dreamshield_tonight),Icon.createWithResource(context,R.drawable.ic_sleep_moon),activity.mainExecutor) {}
            }.onFailure{sleep.notice=text(R.string.copy_edit_quick_settings_to_add_dreamshield_tonight)}
        },Modifier.fillMaxWidth()){Text(text(R.string.copy_add_quick_settings_tile))}
        TextButton(onClick={export.launch(text(R.string.copy_dreamshield_sleep_journal_json))}){Text(text(R.string.copy_export_sleep_journal))}
        SleepNote(text(R.string.copy_manage_nights_in_records_journal_and_imports_are_s))
    }
    NativeHealthSleepCard(state)
}

@Composable fun NativeHealthSleepCard(state: NativeState) {
    val sleep=state.sleep
    val health=sleep.health
    val scope=rememberCoroutineScope()
    var days by remember{mutableIntStateOf(7)}
    var expanded by remember{mutableStateOf(false)}
    fun refresh() {
        if(sleep.healthBusy) return
        sleep.healthBusy=true
        val ticket=++sleep.healthRequest
        scope.launch {
            try { val result=health.refresh(days); if(ticket==sleep.healthRequest) sleep.healthResult=result }
            finally { if(ticket==sleep.healthRequest) sleep.healthBusy=false }
        }
    }
    val permission=rememberLauncherForActivityResult(health.permissionContract()) { granted->
        if(granted.containsAll(health.permissions)) refresh()
        else { sleep.healthRequest++;sleep.healthBusy=false;sleep.healthResult=null;sleep.healthMessage=text(R.string.copy_read_access_declined_the_journal_is_available)}
    }
    LaunchedEffect(state.foreground) {
        if(state.foreground) {
            if(health.granted()) sleep.healthResult=health.cached()
            else { sleep.healthRequest++;sleep.healthBusy=false;health.clearLocal();sleep.healthResult=null }
        } else {
            sleep.healthRequest++;sleep.healthBusy=false
        }
    }
    val result=sleep.healthResult
    DreamPaperCard(text(R.string.copy_health_connect)) {
        Text(text(R.string.copy_import_sleep_records_from_connected_apps_and_weara),style=Theme.typography.bodyMedium)
        SleepNote(result?.message ?: sleep.healthMessage.ifBlank{text(R.string.copy_read_only_access_source_stages)})
        DreamSegmentedTrack(listOf(text(R.string.copy_7_days),text(R.string.copy_30_days)),if(days==7)0 else 1,{days=if(it==0)7 else 30})
        when(health.availability()){
            HealthConnectClient.SDK_AVAILABLE -> Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Button(onClick={permission.launch(health.permissions)},enabled=!sleep.healthBusy,modifier=Modifier.weight(1f)){Text(text(R.string.copy_allow_access))}
                OutlinedButton(onClick={refresh()},enabled=!sleep.healthBusy,modifier=Modifier.weight(1f)){Text(if(sleep.healthBusy)text(R.string.copy_reading) else text(R.string.copy_refresh))}
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> OutlinedButton(onClick={health.openInstall()}){Text(text(R.string.copy_install_or_update_health_connect_557))}
            else -> SleepNote(text(R.string.copy_health_connect_is_unavailable_on_this_phone))
        }
        val sessions=result?.sessions.orEmpty()
        if(sessions.isNotEmpty()){
            SleepRow(text(R.string.copy_imported_sessions),text(R.string.copy_s_308, sessions.size))
            sessions.take(if(expanded)30 else 3).forEach { session ->
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text(SleepCoordinator.dateTime(session.startMillis),style=Theme.typography.titleSmall)
                    SleepRow(session.sourceName, "${SleepCoordinator.time(session.startMillis)}–${SleepCoordinator.time(session.endMillis)}")
                    val night=sleep.repo.snapshot.nights.firstOrNull{it.startMillis<session.endMillis && (it.endMillis ?: System.currentTimeMillis())>session.startMillis}
                    if(night!=null) TextButton(onClick={sleep.selectedNightId=night.id;state.showPage("history")}){Text(text(R.string.copy_view_matching_dreamshield_night))}
                    if(session.stages.isNotEmpty()){
                        val stages=session.stages.groupBy{it.label}.mapValues{(_,v)->v.sumOf{it.endMillis-it.startMillis}/60_000}
                        SleepNote(stages.entries.joinToString(" · "){text(R.string.copy_s_s_min, it.key, it.value)})
                    }
                    HorizontalDivider(color=Theme.colorScheme.outlineVariant)
                }
            }
            if(sessions.size>3) TextButton(onClick={expanded=!expanded}){Text(if(expanded)text(R.string.copy_show_less) else text(R.string.copy_show_all))}
        }
        if(result!=null || sessions.isNotEmpty()) TextButton(onClick={
            sleep.healthRequest++;sleep.healthBusy=false;sleep.healthResult=null
            scope.launch{sleep.healthResult=health.revoke();sleep.healthMessage=text(R.string.copy_local_imports_cleared)}
        }){Text(text(R.string.copy_revoke_access_and_clear_imports))}
        TextButton(onClick={health.openSettings()}){Text(text(R.string.copy_health_connect_permissions))}
    }
}
