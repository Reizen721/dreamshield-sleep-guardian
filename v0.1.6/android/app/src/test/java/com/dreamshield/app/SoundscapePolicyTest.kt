package com.dreamshield.app

import org.junit.Assert.*
import org.junit.Test

class SoundscapePolicyTest {
    @Test fun volumeIsFiniteAndBounded() {
        assertEquals(.45f, SoundscapePolicy.volume(Float.NaN), 0f)
        assertEquals(.45f, SoundscapePolicy.volume(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, SoundscapePolicy.volume(-1f), 0f)
        assertEquals(1f, SoundscapePolicy.volume(2f), 0f)
        assertEquals(.4f, SoundscapePolicy.volume(.4f), 0f)
    }
    @Test fun timersUseSupportedDurationsAndExpireAtDeadline() {
        assertEquals(900_000L, SoundscapePolicy.durationMs(15))
        assertEquals(5_400_000L, SoundscapePolicy.durationMs(90))
        assertEquals(1_800_000L, SoundscapePolicy.durationMs(0))
        assertEquals(0L, SoundscapePolicy.remaining(100L, 101L))
        assertEquals(50L, SoundscapePolicy.remaining(100L, 50L))
    }
    @Test fun fadeIsContinuousAndFinalMinuteOnly() {
        assertEquals(1f, SoundscapePolicy.fadeGain(60001L), 0f)
        assertEquals(.5f, SoundscapePolicy.fadeGain(30000L), 0f)
        assertEquals(0f, SoundscapePolicy.fadeGain(0L), 0f)
        assertEquals(0f, SoundscapePolicy.fadeGain(-1L), 0f)
        assertTrue(SoundscapePolicy.fadeGain(100L) < SoundscapePolicy.fadeGain(1000L))
    }
    @Test fun everySceneHasItsOwnTitleAndSafeFallback() {
        assertEquals(listOf("雨落夜窗", "潮声入梦", "林间微风"), (0..2).map(SoundscapePolicy::title))
        assertEquals("雨落夜窗", SoundscapePolicy.title(-1))
    }
}
