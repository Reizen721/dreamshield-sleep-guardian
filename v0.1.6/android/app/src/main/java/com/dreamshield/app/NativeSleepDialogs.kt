@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.dreamshield.app

import android.animation.ValueAnimator
import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme as Theme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable fun NativeSleepDialogs(state: NativeState) {
    val sleep=state.sleep
    val feedback=sleep.repo.snapshot.nights.find{it.id==sleep.feedbackNightId}
    if(feedback!=null) {
        var rating by remember(feedback.id) { mutableStateOf(feedback.feedback?.rating) }
        var comfort by remember(feedback.id) { mutableStateOf(feedback.feedback?.comfort) }
        var awake by remember(feedback.id) { mutableStateOf(feedback.feedback?.rememberedWakings?.toString().orEmpty()) }
        var note by remember(feedback.id) { mutableStateOf(feedback.feedback?.note.orEmpty()) }
        var error by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest={sleep.feedbackNightId=null}) {
            Column(Modifier.fillMaxWidth().heightIn(max=650.dp).verticalScroll(rememberScrollState()).padding(horizontal=24.dp).navigationBarsPadding(),
                verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text(text(R.string.copy_how_was_last_night),style=Theme.typography.headlineSmall)
                SleepNote(text(R.string.copy_optional_edit_anytime))
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(text(R.string.copy_exhausted),text(R.string.copy_sleepy),text(R.string.copy_okay),text(R.string.copy_good),text(R.string.copy_rested)).forEachIndexed { i,label ->
                        FilterChip(rating==i+1,{rating=if(rating==i+1)null else i+1},label={Text(label)})
                    }
                }
                Text(text(R.string.copy_comfort),style=Theme.typography.titleMedium)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("cold","comfortable","hot").forEach { id ->
                        FilterChip(comfort==id,{comfort=if(comfort==id)null else id},label={Text(comfortLabel(id))})
                    }
                }
                OutlinedTextField(awake,{awake=it.filter(Char::isDigit).take(2)},Modifier.fillMaxWidth(),label={Text(text(R.string.copy_times_you_woke_optional))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
                OutlinedTextField(note,{note=it.take(1000)},Modifier.fillMaxWidth(),label={Text(text(R.string.copy_note_optional))},minLines=2,maxLines=5)
                if(error.isNotEmpty()) Text(error,color=Theme.colorScheme.error)
                Button(onClick={
                    runCatching{sleep.repo.saveFeedback(feedback.id,rating,comfort,awake.toIntOrNull(),note)}
                        .onSuccess{sleep.feedbackNightId=null;sleep.notice=text(R.string.copy_feedback_saved)}
                        .onFailure{error=it.message ?: text(R.string.copy_could_not_save)}
                },Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(text(R.string.copy_save_feedback))}
                TextButton(onClick={sleep.feedbackNightId=null},Modifier.align(Alignment.CenterHorizontally)){Text(text(R.string.copy_later))}
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    val editing=sleep.repo.snapshot.nights.find{it.id==sleep.editNightId}
    if(editing!=null || sleep.addingNight) {
        val yesterday=LocalDate.now().minusDays(1).toString()
        var from by remember(editing?.id) { mutableStateOf(editing?.let{SleepCoordinator.editTime(it.startMillis)} ?: "$yesterday 23:00") }
        var to by remember(editing?.id) { mutableStateOf(editing?.endMillis?.let{SleepCoordinator.editTime(it)} ?: if(editing==null) "${LocalDate.now()} 07:00" else "") }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest={sleep.editNightId=null;sleep.addingNight=false},title={Text(if(editing==null)text(R.string.copy_add_a_night) else text(R.string.copy_edit_night))},
            text={Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                SleepNote(text(R.string.copy_local_record_24_hour_time))
                OutlinedTextField(from,{from=it},label={Text(text(R.string.copy_start_yyyy_mm_dd_hh_mm))},singleLine=true)
                OutlinedTextField(to,{to=it},label={Text(if(editing?.endMillis==null && editing!=null)text(R.string.copy_end_blank_if_ongoing) else text(R.string.copy_end_yyyy_mm_dd_hh_mm))},singleLine=true)
                if(error.isNotEmpty()) Text(error,color=Theme.colorScheme.error)
            }},
            confirmButton={TextButton(onClick={
                runCatching{
                    val start=SleepCoordinator.parseTime(from)
                    val end=to.takeIf{it.isNotBlank()}?.let{SleepCoordinator.parseTime(it)}
                    require(editing?.endMillis==null || end!=null){text(R.string.copy_enter_an_end_time_for_a_finished_night)}
                    if(editing==null) {
                        require(end!=null){text(R.string.copy_enter_an_end_time)}
                        sleep.repo.addNight(start,end,state.scene,state.sleepSourceId())
                    } else sleep.repo.editNight(editing.id,start,end,editing.plannedWakeMillis?.takeIf{it>start && it-start<=SleepMath.MAX_NIGHT_MILLIS})
                }.onSuccess{sleep.editNightId=null;sleep.addingNight=false;sleep.reload()}
                    .onFailure{error=it.message ?: text(R.string.copy_check_the_date_and_time_format)}
            }){Text(text(R.string.copy_save))}},
            dismissButton={TextButton(onClick={sleep.editNightId=null;sleep.addingNight=false}){Text(text(R.string.copy_cancel))}})
    }
}

@Composable fun NativeNightMode(state: NativeState) {
    val sleep=state.sleep
    if(!sleep.nightMode) return
    val active=sleep.repo.snapshot.active ?: return
    val activity=LocalActivity.current
    DisposableEffect(Unit) {
        val previous=activity?.window?.attributes?.screenBrightness
        activity?.window?.let{window->window.attributes=window.attributes.apply{screenBrightness=.06f}}
        onDispose { if(previous!=null) activity.window.attributes=activity.window.attributes.apply{screenBrightness=previous} }
    }
    var sounds by remember { mutableStateOf(false) }
    var breathe by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(120) }
    LaunchedEffect(breathe,state.foreground) {
        if(breathe && state.foreground) {
            while(remaining>0){delay(1000);remaining--}
            breathe=false;remaining=120
        }
    }
    Dialog(onDismissRequest={sleep.nightMode=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val dialogWindow=(LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            val before=dialogWindow?.attributes?.screenBrightness
            dialogWindow?.let{it.attributes=it.attributes.apply{screenBrightness=.06f}}
            onDispose { if(before!=null && dialogWindow!=null) dialogWindow.attributes=dialogWindow.attributes.apply{screenBrightness=before} }
        }
        Theme(colorScheme=if(android.os.Build.VERSION.SDK_INT>=31) dynamicDarkColorScheme(LocalContext.current) else darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(painterResource(dreamSceneArtwork(active.scene)),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha=.82f)))
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(28.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(24.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick={sleep.nightMode=false}){Text(text(R.string.copy_back_to_tonight),color=Color.White)}
                }
                Spacer(Modifier.height(20.dp))
                Text(text(R.string.copy_good_night),style=Theme.typography.displayMedium,color=Color.White)
                Text(text(R.string.copy_see_you_at_s, SleepCoordinator.time(active.plannedWakeMillis ?: sleep.plannedWake())),style=Theme.typography.titleMedium,color=Color.White)
                Text(text(R.string.copy_you_can_turn_off_the_screen),style=Theme.typography.bodyMedium,color=Color.White.copy(alpha=.8f))
                if(breathe) {
                    val motion=ValueAnimator.areAnimatorsEnabled() && state.foreground
                    val transition=rememberInfiniteTransition(label="quiet breathing")
                    val phase by transition.animateFloat(0f,1f,infiniteRepeatable(tween(4000,easing=LinearEasing),RepeatMode.Reverse),label="breath")
                    Canvas(Modifier.size(120.dp)) {
                        drawCircle(Color.White.copy(alpha=.12f),size.minDimension*(if(motion).30f+.16f*phase else .4f))
                        drawCircle(Color.White.copy(alpha=.75f),size.minDimension*.08f)
                    }
                    Text(if(phase<.5f)text(R.string.copy_breathe_gently) else text(R.string.copy_relax),color=Color.White)
                    TextButton(onClick={breathe=false;remaining=120}){Text(text(R.string.copy_end_breathing),color=Color.White)}
                } else OutlinedButton(onClick={breathe=true;remaining=120}){Text(text(R.string.copy_two_minute_breathing),color=Color.White)}
                FilledTonalButton(onClick={sleep.awake()},Modifier.fillMaxWidth().heightIn(min=60.dp)) { Text(text(R.string.copy_i_woke_briefly)) }
                if(sleep.notice.isNotBlank()) Text(sleep.notice,color=Color.White.copy(alpha=.8f))
                OutlinedButton(onClick={sounds=!sounds},Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(if(sounds)text(R.string.copy_hide_sounds) else text(R.string.copy_soundscapes),color=Color.White)}
                if(sounds) NativeSoundCard(active.scene)
                Button(onClick={sleep.finish();state.showPage("history");state.syncSleepHistory()},Modifier.fillMaxWidth().heightIn(min=60.dp)){Text(text(R.string.copy_i_m_up))}
                if(active.requiresTimeCorrection) TextButton(onClick={sleep.nightMode=false;sleep.editNightId=active.id}){Text(text(R.string.copy_enter_wake_time),color=Color.White)}
                Spacer(Modifier.height(24.dp))
            }
        }
        }
    }
}
