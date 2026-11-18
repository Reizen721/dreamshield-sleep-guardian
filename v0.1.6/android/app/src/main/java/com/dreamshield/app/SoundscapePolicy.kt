package com.dreamshield.app

/** Pure rules shared by the player and its tests. Time is always monotonic. */
internal object SoundscapePolicy {
    val durations = listOf(15, 30, 60, 90)
    fun scene(value: Int) = value.coerceIn(0, 2)
    fun volume(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else .45f
    fun durationMs(minutes: Int) = (if (minutes in durations) minutes else 30) * 60_000L
    fun remaining(deadline: Long, now: Long) = (deadline - now).coerceAtLeast(0L)
    fun fadeGain(remainingMs: Long): Float = (remainingMs / 60_000f).coerceIn(0f, 1f)
    fun title(value: Int) = when (scene(value)) { 1 -> message("潮声入梦", "Ocean Drift"); 2 -> message("林间微风", "Forest Breeze"); else -> message("雨落夜窗", "Night Rain") }
}
