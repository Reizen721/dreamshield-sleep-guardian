package com.dreamshield.app

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class SoundscapeState(
    val playing: Boolean = false,
    val preparing: Boolean = false,
    val scene: Int = 0,
    val volume: Float = .45f,
    val durationMinutes: Int = 30,
    val remainingMs: Long = 0,
    val hasTrack: Boolean = false,
    val error: String = "",
)

/** No playback flag is persisted. A killed process starts silent, never "playing". */
object SoundscapeController {
    var state by mutableStateOf(SoundscapeState())
        private set
    internal var attached = false
    internal fun publish(value: SoundscapeState) { state = value }

    fun play(context: Context, scene: Int, volume: Float = .45f, durationMinutes: Int = 30) {
        command(context, SoundscapeService.PLAY, true) {
            putExtra("scene", SoundscapePolicy.scene(scene))
            putExtra("volume", SoundscapePolicy.volume(volume))
            putExtra("minutes", durationMinutes)
        }
    }
    fun pause(context: Context) = command(context, SoundscapeService.PAUSE)
    fun resume(context: Context) {
        if (attached && state.hasTrack) command(context, SoundscapeService.RESUME, true)
    }
    fun stop(context: Context) = command(context, SoundscapeService.STOP)
    fun setVolume(context: Context, volume: Float) {
        val value = SoundscapePolicy.volume(volume)
        if (attached) command(context, SoundscapeService.VOLUME) { putExtra("volume", value) }
        else state = state.copy(volume = value)
    }
    fun setTimer(context: Context, minutes: Int) {
        if (attached) command(context, SoundscapeService.TIMER) { putExtra("minutes", minutes) }
        else state = state.copy(durationMinutes = if (minutes in SoundscapePolicy.durations) minutes else 30)
    }
    private fun command(context: Context, action: String, foreground: Boolean = false, extras: Intent.() -> Unit = {}) {
        if (!foreground && !attached) return
        val intent = Intent(context.applicationContext, SoundscapeService::class.java).setAction(action).apply(extras)
        try {
            if (foreground && Build.VERSION.SDK_INT >= 26) context.applicationContext.startForegroundService(intent)
            else context.applicationContext.startService(intent)
        } catch (_: Exception) {
            state = state.copy(error = text(R.string.copy_could_not_start_sound_reopen_the_app_and_retry))
        }
    }
}
