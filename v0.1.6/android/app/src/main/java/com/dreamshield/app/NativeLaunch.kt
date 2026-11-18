package com.dreamshield.app

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max

/** Reveals the actual scene beneath a soft night veil, without a second image/crop. */
@Composable
fun NativeLaunchCurtain(state: NativeState) {
    if (!state.launchVisible) return
    val progress = remember { Animatable(0f) }
    val ink = colorResource(R.color.dreamshield_launch_ink)
    val motion = ValueAnimator.areAnimatorsEnabled() && state.foreground
    val density = LocalDensity.current
    // Use the same height as Home so the light reaches the actual illustrated window,
    // including the taller freely scrolling cover used with large accessibility text.
    val minimumCoverPixels = with(density) {
        (480.dp + 360.dp * (fontScale - 1f).coerceAtLeast(0f)).toPx()
    }
    val scene = when (state.scene) {
        1 -> SceneEntrance(.80f, .58f, Color(0xff674051), Color(0xFFF6B28C))
        2 -> SceneEntrance(.79f, .65f, Color(0xff16494b), Color(0xFF9C88FF))
        else -> SceneEntrance(.82f, .70f, Color(0xff30264e), Color(0xFFF49D6E))
    }
    // A platform without an exit callback must never strand an opaque launch layer.
    LaunchedEffect(Unit) {
        delay(450)
        if (!state.launchRevealStarted) state.beginLaunchReveal(0f, 0f, 0f)
    }
    LaunchedEffect(state.launchRevealStarted, motion) {
        if (!motion) { state.launchVisible = false; return@LaunchedEffect }
        if (state.launchRevealStarted) {
            progress.animateTo(1f, tween(960, easing = FastOutSlowInEasing))
            state.launchVisible = false
        }
    }
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    }) {
        if (!state.launchRevealStarted) {
            drawRect(ink)
            return@Canvas
        }
        val p = progress.value.coerceIn(0f, 1f)
        // All three authored scenes are 941 x 1672. Map their warm-window focus
        // through the same centred Crop and initial breathing scale as Home.
        val coverHeight = max(size.height, minimumCoverPixels)
        val crop = max(size.width / 941f, coverHeight / 1672f) * 1.015f
        val focus = Offset(
            (size.width / 2f + (scene.x - .5f) * 941f * crop).coerceIn(size.width * .12f, size.width * .88f),
            (coverHeight / 2f + (scene.y - .5f) * 1672f * crop).coerceIn(size.height * .12f, size.height * .88f)
        )
        val fallback = state.launchOriginX == 0f && state.launchOriginY == 0f
        val origin = if (fallback) focus else Offset(
            state.launchOriginX.coerceIn(0f, size.width),
            state.launchOriginY.coerceIn(0f, size.height)
        )
        val travel = entrancePhase((p - .02f) / .58f)
        val center = Offset(
            origin.x + (focus.x - origin.x) * travel,
            origin.y + (focus.y - origin.y) * travel
        )
        val farCorner = hypot(max(center.x, size.width - center.x), max(center.y, size.height - center.y))
        val start = state.launchStartRadius.coerceIn(0f, farCorner)
        val radius = (start + (farCorner * 1.6f - start) * p).coerceAtLeast(1f)
        val opacity = 1f - entrancePhase((p - .56f) / .44f)
        val shade = lerpColor(ink, scene.veil, entrancePhase(p * 1.4f)).copy(alpha = opacity)
        val rimGlow = scene.glow.copy(alpha = opacity * (1f - p) * 0.28f)
        // A single bounded shader softly lifts darkness from the real background.
        // No duplicate artwork, blur buffer, particle layer or last-frame crop swap.
        drawRect(
            Brush.radialGradient(
                0f to shade.copy(alpha = 0f),
                .58f to shade.copy(alpha = 0f),
                .72f to rimGlow,
                .84f to shade.copy(alpha = opacity * .48f),
                1f to shade,
                center = center,
                radius = radius
            )
        )
    }
}

private data class SceneEntrance(val x: Float, val y: Float, val veil: Color, val glow: Color)

private fun entrancePhase(value: Float): Float {
    val phase = value.coerceIn(0f, 1f)
    return phase * phase * (3f - 2f * phase)
}
