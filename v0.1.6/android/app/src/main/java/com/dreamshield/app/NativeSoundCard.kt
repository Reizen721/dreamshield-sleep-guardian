package com.dreamshield.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme as ComposeTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The same native paper surface as the existing room cards. All sounds work offline. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeSoundCard(scene: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val playback = SoundscapeController.state
    var selected by rememberSaveable { mutableIntStateOf(if (playback.hasTrack) playback.scene else scene.coerceIn(0, 2)) }
    var minutes by rememberSaveable { mutableIntStateOf(playback.durationMinutes) }
    var volume by rememberSaveable { mutableFloatStateOf(playback.volume) }
    val currentTrack = playback.hasTrack && playback.scene == selected
    DreamPaperCard(text(R.string.copy_soundscapes_567), modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(text(R.string.copy_night_rain), text(R.string.copy_ocean_drift), text(R.string.copy_forest_breeze)).forEachIndexed { index, label ->
                FilterChip(selected = selected == index, onClick = { selected = index },
                    label = { Text(label) }, enabled = !playback.preparing,
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Text(text(R.string.copy_stop_after), style = ComposeTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(15, 30, 60, 90).forEach { value ->
                FilterChip(selected = minutes == value, onClick = {
                    minutes = value
                    SoundscapeController.setTimer(context, value)
                }, label = { Text(text(R.string.copy_s_min, value)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Text(text(R.string.copy_sound_volume), style = ComposeTheme.typography.titleSmall)
        Slider(value = volume, onValueChange = { volume = it },
            onValueChangeFinished = { SoundscapeController.setVolume(context, volume) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = text(R.string.copy_soundscape_volume) })
        val status = when {
            playback.preparing -> text(R.string.copy_loading_sound)
            playback.playing -> text(R.string.copy_s_s_min_left, SoundscapePolicy.title(playback.scene), (playback.remainingMs + 59_999L) / 60_000L)
            playback.hasTrack -> text(R.string.copy_s_paused, SoundscapePolicy.title(playback.scene))
            else -> text(R.string.copy_offline_fades_in_the_last_minute)
        }
        Text(status, style = ComposeTheme.typography.bodyMedium,
            color = ComposeTheme.colorScheme.onSurfaceVariant)
        if (playback.error.isNotBlank()) Text(playback.error, style = ComposeTheme.typography.bodyMedium,
            color = ComposeTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                when {
                    currentTrack && playback.playing -> SoundscapeController.pause(context)
                    currentTrack && !playback.preparing -> SoundscapeController.resume(context)
                    else -> SoundscapeController.play(context, selected, volume, minutes)
                }
            }, enabled = !playback.preparing, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(if (currentTrack && playback.playing) text(R.string.copy_pause) else if (currentTrack) text(R.string.copy_resume) else text(R.string.copy_play))
            }
            if (playback.hasTrack || playback.preparing) {
                OutlinedButton(onClick = { SoundscapeController.stop(context) },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(text(R.string.copy_end)) }
            }
        }
    }
}
