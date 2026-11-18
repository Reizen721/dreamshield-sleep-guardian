package com.dreamshield.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/** User-started offline playback; the service owns playback while the screen is locked. */
class SoundscapeService : Service() {
    companion object {
        const val PLAY = "com.dreamshield.app.sound.PLAY"
        const val PAUSE = "com.dreamshield.app.sound.PAUSE"
        const val RESUME = "com.dreamshield.app.sound.RESUME"
        const val STOP = "com.dreamshield.app.sound.STOP"
        const val VOLUME = "com.dreamshield.app.sound.VOLUME"
        const val TIMER = "com.dreamshield.app.sound.TIMER"
        private const val CHANNEL = "dreamshield_soundscape"
        private const val NOTICE = 6201

        fun updateChannel(context: Context, create: Boolean = false) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!create && manager.getNotificationChannel(CHANNEL) == null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, text(R.string.copy_soundscapes_831), NotificationManager.IMPORTANCE_LOW).apply {
                    description = text(R.string.copy_offline_audio_with_a_sleep_timer); setSound(null, null); enableVibration(false)
                })
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var session: MediaSession
    private lateinit var focus: AudioFocusRequest
    private var player: MediaPlayer? = null
    private var ready = false
    private var focused = false
    private var remaining = 0L
    private var deadline = 0L
    private var current = SoundscapeState()
    private var generation = 0
    private var isDestroying = false
    private val unplugged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayer()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!current.playing || isDestroying) return
            remaining = SoundscapePolicy.remaining(deadline, SystemClock.elapsedRealtime())
            if (remaining <= 0L) { stopPlayback(); return }
            applyVolume()
            val changedMinute = remaining / 60_000L != current.remainingMs / 60_000L
            publish()
            // The ongoing notification only changes once a minute.
            if (changedMinute) updateNotification()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        SoundscapeController.attached = true
        audio = getSystemService(AudioManager::class.java)
        updateChannel(this, create = true)
        session = MediaSession(this, "DreamShield soundscape").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resumePlayer() }
                override fun onPause() { pausePlayer() }
                override fun onStop() { stopPlayback() }
            }, handler)
            isActive = true
        }
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    // Resume only after a deliberate user action, including phone calls.
                    pausePlayer(text(R.string.copy_another_app_paused_the_sound_tap_play_to_continue))
                }
            }, handler).build()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(unplugged, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(unplugged, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PLAY -> {
                current = current.copy(
                    scene = SoundscapePolicy.scene(intent.getIntExtra("scene", 0)),
                    volume = SoundscapePolicy.volume(intent.getFloatExtra("volume", .45f)),
                    durationMinutes = intent.getIntExtra("minutes", 30).let { if (it in SoundscapePolicy.durations) it else 30 },
                    playing = false, preparing = true, error = "", hasTrack = true)
                remaining = SoundscapePolicy.durationMs(current.durationMinutes)
                // A foreground user action promotes the service before acquiring focus.
                startForeground(NOTICE, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                prepareTrack()
            }
            RESUME -> {
                startForeground(NOTICE, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                resumePlayer()
            }
            PAUSE -> pausePlayer()
            STOP -> stopPlayback()
            VOLUME -> {
                current = current.copy(volume = SoundscapePolicy.volume(intent.getFloatExtra("volume", current.volume)))
                applyVolume(); publish()
            }
            TIMER -> {
                val minutes = intent.getIntExtra("minutes", 30)
                current = current.copy(durationMinutes = if (minutes in SoundscapePolicy.durations) minutes else 30)
                remaining = SoundscapePolicy.durationMs(current.durationMinutes)
                if (current.playing) deadline = SystemClock.elapsedRealtime() + remaining
                applyVolume(); publish(); updateNotification()
            }
            else -> stopPlayback() // No restart/reboot autoplay.
        }
        return START_NOT_STICKY
    }

    private fun prepareTrack() {
        generation++
        val expected = generation
        handler.removeCallbacks(tick)
        releasePlayer()
        val resource = when (current.scene) {
            1 -> R.raw.sound_sea
            2 -> R.raw.sound_forest
            else -> R.raw.sound_rain
        }
        try {
            player = MediaPlayer().also { media ->
                media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                media.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                media.isLooping = true
                resources.openRawResourceFd(resource).use { asset ->
                    media.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
                }
                media.setOnPreparedListener {
                    if (generation == expected && player === it && !isDestroying) {
                        ready = true; current = current.copy(preparing = false)
                        resumePlayer()
                    }
                }
                media.setOnErrorListener { _, _, _ ->
                    if (generation == expected) fail(text(R.string.copy_could_not_load_this_sound_choose_another))
                    true
                }
                media.prepareAsync()
            }
            publish()
        } catch (_: Exception) { fail(text(R.string.copy_could_not_load_this_sound_choose_another)) }
    }

    private fun resumePlayer() {
        if (!ready || player == null || current.preparing || current.playing) return
        if (remaining <= 0L) remaining = SoundscapePolicy.durationMs(current.durationMinutes)
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focused = false
            current = current.copy(playing = false, preparing = false, error = text(R.string.copy_another_app_is_playing_audio_resume_later))
            publish(); updateNotification(); return
        }
        focused = true
        try {
            deadline = SystemClock.elapsedRealtime() + remaining
            current = current.copy(playing = true, preparing = false, error = "")
            applyVolume(); player?.start()
            session.isActive = true
            handler.removeCallbacks(tick); handler.post(tick)
            publish(); updateNotification()
        } catch (_: Exception) { fail(text(R.string.copy_could_not_play_this_sound_choose_another)) }
    }

    private fun pausePlayer(message: String = "") {
        if (!current.hasTrack) return
        if (current.preparing) {
            // A pause while loading invalidates its callback, so it cannot autoplay later.
            stopPlayback(message)
            return
        } else {
            if (current.playing) remaining = SoundscapePolicy.remaining(deadline, SystemClock.elapsedRealtime())
            try { if (ready && player?.isPlaying == true) player?.pause() } catch (_: Exception) { }
            current = current.copy(playing = false, error = message)
        }
        handler.removeCallbacks(tick)
        abandonFocus(); publish(); updateNotification()
    }

    private fun applyVolume() {
        val millis = if (current.playing) SoundscapePolicy.remaining(deadline, SystemClock.elapsedRealtime()) else remaining
        val value = current.volume * SoundscapePolicy.fadeGain(millis)
        try { player?.setVolume(value, value) } catch (_: Exception) { }
    }
    private fun abandonFocus() {
        if (focused) {
            focused = false
            audio.abandonAudioFocusRequest(focus)
        }
    }
    private fun publish() {
        current = current.copy(remainingMs = remaining.coerceAtLeast(0L))
        SoundscapeController.publish(current)
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(if (current.playing) PlaybackState.STATE_PLAYING else if (current.preparing)
                PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (current.playing) 1f else 0f).build())
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, SoundscapePolicy.title(current.scene))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, text(R.string.copy_dreamshield_soundscapes)).build())
    }
    private fun updateNotification() {
        if (!isDestroying) getSystemService(NotificationManager::class.java).notify(NOTICE, notification())
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 6200, Intent(this, MainActivity::class.java)
            .putExtra("dreamshield_destination", "sleep"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String, requestCode: Int) = PendingIntent.getService(this, requestCode,
            Intent(this, SoundscapeService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = current.playing || current.preparing
        val minutes = (remaining + 59_999L) / 60_000L
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_splash_mark)
            .setContentTitle(SoundscapePolicy.title(current.scene))
            .setContentText(if (current.preparing) text(R.string.copy_loading_sound) else
                (if (current.playing) text(R.string.copy_playing) else text(R.string.copy_paused_839)) + text(R.string.copy_s_min_left, minutes))
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(current.playing || current.preparing).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setDeleteIntent(action(STOP, 6204))
            .addAction(Notification.Action.Builder(if (pause) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (pause) text(R.string.copy_pause_841) else text(R.string.copy_continue), action(if (pause) PAUSE else RESUME, 6202)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, text(R.string.copy_end), action(STOP, 6203)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }
    private fun fail(message: String) {
        current = current.copy(playing = false, preparing = false, hasTrack = false, error = message)
        stopPlayback(message)
    }
    private fun stopPlayback(error: String = "") {
        generation++
        handler.removeCallbacks(tick)
        releasePlayer(); abandonFocus()
        remaining = 0L
        current = current.copy(playing = false, preparing = false, hasTrack = false, remainingMs = 0L, error = error)
        publish()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTICE)
        stopSelf()
    }
    private fun releasePlayer() {
        ready = false
        try { player?.release() } catch (_: Exception) { }
        player = null
    }
    override fun onDestroy() {
        isDestroying = true
        generation++; handler.removeCallbacksAndMessages(null)
        releasePlayer(); abandonFocus()
        try { unregisterReceiver(unplugged) } catch (_: Exception) { }
        session.isActive = false; session.release()
        getSystemService(NotificationManager::class.java).cancel(NOTICE)
        SoundscapeController.attached = false
        SoundscapeController.publish(current.copy(playing = false, preparing = false, hasTrack = false, remainingMs = 0L))
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
