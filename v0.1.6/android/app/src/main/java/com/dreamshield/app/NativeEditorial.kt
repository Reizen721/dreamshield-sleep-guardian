package com.dreamshield.app

import android.animation.ValueAnimator
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme as ComposeTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

internal fun dreamSceneArtwork(scene: Int): Int = when (scene) {
    1 -> R.drawable.theater_dusk
    2 -> R.drawable.theater_forest
    else -> R.drawable.theater_night
}

internal data class DreamScenePalette(
    val skyTop: Color,
    val skyMid: Color,
    val skyLower: Color,
    val horizonGlow: Color,
    val hillBack: Color,
    val hillMid: Color,
    val hillFront: Color,
    val moonTop: Color,
    val moonBottom: Color,
    val cardVioletStart: Color,
    val cardVioletEnd: Color,
    val cardBlueStart: Color,
    val cardBlueEnd: Color
)

internal fun dreamScenePalette(scene: Int): DreamScenePalette = when (scene) {
    1 -> DreamScenePalette(
        skyTop = Color(0xFF1F1147),
        skyMid = Color(0xFF5A2B82),
        skyLower = Color(0xFFB5579A),
        horizonGlow = Color(0xFFF6B28C),
        hillBack = Color(0xFF562E82),
        hillMid = Color(0xFF3E1E66),
        hillFront = Color(0xFF241042),
        moonTop = Color(0xFFF9AB7E),
        moonBottom = Color(0xFFD9659F),
        cardVioletStart = Color(0xFF7B5AF7),
        cardVioletEnd = Color(0xFF9C7AFF),
        cardBlueStart = Color(0xFF6B86E8),
        cardBlueEnd = Color(0xFF8CA5FA)
    )
    2 -> DreamScenePalette(
        skyTop = Color(0xFF141842),
        skyMid = Color(0xFF3B3685),
        skyLower = Color(0xFF7C65B8),
        horizonGlow = Color(0xFFEDA695),
        hillBack = Color(0xFF423A82),
        hillMid = Color(0xFF2E2764),
        hillFront = Color(0xFF19153E),
        moonTop = Color(0xFFF4B183),
        moonBottom = Color(0xFFC26AA8),
        cardVioletStart = Color(0xFF6B59E8),
        cardVioletEnd = Color(0xFF8C7BF7),
        cardBlueStart = Color(0xFF5E87E0),
        cardBlueEnd = Color(0xFF82A6F5)
    )
    else -> DreamScenePalette(
        skyTop = Color(0xFF191040),
        skyMid = Color(0xFF47287A),
        skyLower = Color(0xFF9C4FA6),
        horizonGlow = Color(0xFFF5AA8E),
        hillBack = Color(0xFF4B297A),
        hillMid = Color(0xFF351B5E),
        hillFront = Color(0xFF1F0E3D),
        moonTop = Color(0xFFF49D6E),
        moonBottom = Color(0xFFCA5A9D),
        cardVioletStart = Color(0xFF7356F5),
        cardVioletEnd = Color(0xFF9475FF),
        cardBlueStart = Color(0xFF6985E8),
        cardBlueEnd = Color(0xFF8AA4FA)
    )
}

/** Starry twilight atmosphere with twinkling stars, shooting star and layered hills. */
@Composable
internal fun DreamTwilightSkyOverlay(
    scene: Int,
    animate: Boolean,
    modifier: Modifier = Modifier,
    horizonHills: Boolean = true
) {
    val palette = dreamScenePalette(scene)
    val motion = animate && ValueAnimator.areAnimatorsEnabled()
    val infinite = if (motion) rememberInfiniteTransition(label = "twilight stars") else null
    val twinkle: State<Float> = if (infinite != null) {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(7200, easing = LinearEasing), RepeatMode.Restart),
            label = "star twinkle"
        )
    } else remember { mutableStateOf(1.2f) }
    val meteor: State<Float> = if (infinite != null) {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(6400, easing = LinearEasing), RepeatMode.Restart),
            label = "shooting star"
        )
    } else remember { mutableStateOf(0.28f) }

    Canvas(modifier.clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val stars = listOf(
            Triple(0.10f, 0.13f, 2.2f),
            Triple(0.24f, 0.08f, 1.6f),
            Triple(0.42f, 0.14f, 2.1f),
            Triple(0.66f, 0.10f, 2.5f),
            Triple(0.85f, 0.16f, 1.8f),
            Triple(0.15f, 0.27f, 1.5f),
            Triple(0.32f, 0.23f, 2.3f),
            Triple(0.56f, 0.21f, 1.7f),
            Triple(0.76f, 0.28f, 2.2f),
            Triple(0.91f, 0.25f, 1.5f),
            Triple(0.08f, 0.40f, 1.9f),
            Triple(0.89f, 0.39f, 2.1f),
            Triple(0.21f, 0.48f, 1.4f),
            Triple(0.79f, 0.47f, 1.6f)
        )
        val phase = twinkle.value
        stars.forEachIndexed { index, (sx, sy, radiusDp) ->
            val pulse = 0.40f + 0.60f * (0.5f + 0.5f * sin(phase + index * 0.85f))
            val center = Offset(w * sx, h * sy)
            val r = radiusDp.dp.toPx() * (0.85f + 0.25f * pulse)
            drawCircle(Color.White.copy(alpha = 0.80f * pulse), radius = r, center = center)
            if (index % 3 == 0) {
                val arm = r * 2.8f
                drawLine(Color(0xFFFFDFB3).copy(alpha = 0.58f * pulse), Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), strokeWidth = 1.1.dp.toPx())
                drawLine(Color(0xFFFFDFB3).copy(alpha = 0.58f * pulse), Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), strokeWidth = 1.1.dp.toPx())
            }
        }
        // Shooting star streak in upper-right sky
        val m = meteor.value
        val activeWindow = ((m - 0.12f) / 0.38f).coerceIn(0f, 1f)
        if (activeWindow > 0f && activeWindow < 1f) {
            val headX = w * (0.88f - 0.32f * activeWindow)
            val headY = h * (0.09f + 0.16f * activeWindow)
            val tailX = headX + w * 0.14f
            val tailY = headY - h * 0.075f
            val alpha = sin(activeWindow * PI).toFloat().coerceIn(0f, 1f)
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFFFE5B4).copy(alpha = 0.88f * alpha), Color.White.copy(alpha = 0f)),
                    start = Offset(headX, headY),
                    end = Offset(tailX, tailY)
                ),
                start = Offset(headX, headY),
                end = Offset(tailX, tailY),
                strokeWidth = 2.2.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(Color(0xFFFFE0A3).copy(alpha = 0.96f * alpha), radius = 3.2.dp.toPx(), center = Offset(headX, headY))
        }
        if (horizonHills) {
            val distantHill = Path().apply {
                moveTo(0f, h * 0.82f)
                cubicTo(w * 0.22f, h * 0.74f, w * 0.48f, h * 0.84f, w * 0.76f, h * 0.76f)
                cubicTo(w * 0.88f, h * 0.73f, w * 0.96f, h * 0.77f, w, h * 0.75f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(distantHill, palette.hillBack.copy(alpha = 0.46f))

            val midHill = Path().apply {
                moveTo(0f, h * 0.87f)
                cubicTo(w * 0.26f, h * 0.80f, w * 0.52f, h * 0.89f, w * 0.82f, h * 0.82f)
                cubicTo(w * 0.92f, h * 0.80f, w * 0.97f, h * 0.83f, w, h * 0.82f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(midHill, palette.hillMid.copy(alpha = 0.62f))

            val frontHill = Path().apply {
                moveTo(0f, h * 0.92f)
                cubicTo(w * 0.32f, h * 0.86f, w * 0.62f, h * 0.95f, w, h * 0.88f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(frontHill, palette.hillFront.copy(alpha = 0.78f))
        }
    }
}

/** Crescent moon and sleeping night guardian inspired by the reference centerpiece. */
@Composable
internal fun DreamMoonGuardianArt(
    scene: Int,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = dreamScenePalette(scene)
    val motion = animate && ValueAnimator.areAnimatorsEnabled()
    val infinite = if (motion) rememberInfiniteTransition(label = "moon cradle") else null
    val sway: State<Float> = if (infinite != null) {
        infinite.animateFloat(
            initialValue = -3.4f,
            targetValue = 3.4f,
            animationSpec = infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "moon sway"
        )
    } else remember { mutableStateOf(0f) }
    val floatY: State<Float> = if (infinite != null) {
        infinite.animateFloat(
            initialValue = -4.5f,
            targetValue = 4.5f,
            animationSpec = infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "moon float"
        )
    } else remember { mutableStateOf(0f) }

    Canvas(modifier.graphicsLayer {
        rotationZ = sway.value
        translationY = floatY.value.dp.toPx()
    }.clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.50f
        val r = minOf(w, h) * 0.42f

        // Multi-layered outer starlight aura
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.moonTop.copy(alpha = 0.30f), palette.moonBottom.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(cx, cy),
                radius = r * 1.48f
            ),
            radius = r * 1.48f,
            center = Offset(cx, cy)
        )

        // Hanging starlight thread from top sky
        drawLine(
            brush = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.0f), Color.White.copy(alpha = 0.38f))
            ),
            start = Offset(cx - r * 0.12f, 0f),
            end = Offset(cx - r * 0.12f, cy - r * 0.90f),
            strokeWidth = 1.3.dp.toPx()
        )

        // Crescent moon path
        val moonPath = Path().apply {
            moveTo(cx - r * 0.12f, cy - r * 0.92f)
            cubicTo(cx - r * 1.02f, cy - r * 0.65f, cx - r * 1.05f, cy + r * 0.58f, cx - r * 0.10f, cy + r * 0.88f)
            cubicTo(cx + r * 0.55f, cy + r * 1.05f, cx + r * 1.02f, cy + r * 0.62f, cx + r * 0.92f, cy + r * 0.22f)
            cubicTo(cx + r * 0.50f, cy + r * 0.52f, cx - r * 0.22f, cy + r * 0.35f, cx - r * 0.34f, cy - r * 0.18f)
            cubicTo(cx - r * 0.44f, cy - r * 0.55f, cx - r * 0.28f, cy - r * 0.78f, cx - r * 0.12f, cy - r * 0.92f)
            close()
        }
        drawPath(
            path = moonPath,
            brush = Brush.linearGradient(
                colors = listOf(palette.moonBottom, palette.moonTop),
                start = Offset(cx - r, cy - r),
                end = Offset(cx + r, cy + r)
            )
        )
        // Subtle moon craters
        drawCircle(Color(0xFFE07A72).copy(alpha = 0.45f), radius = r * 0.08f, center = Offset(cx - r * 0.52f, cy - r * 0.10f))
        drawCircle(Color(0xFFE07A72).copy(alpha = 0.38f), radius = r * 0.05f, center = Offset(cx - r * 0.38f, cy + r * 0.36f))
        drawCircle(Color(0xFFE07A72).copy(alpha = 0.35f), radius = r * 0.06f, center = Offset(cx + r * 0.44f, cy + r * 0.58f))

        // Sleeping koala/guardian body in cozy purple polka-dot pajamas resting on the crescent
        val bodyCenter = Offset(cx + r * 0.18f, cy + r * 0.18f)
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF7B61FF), Color(0xFF5E43D8)),
                start = Offset(bodyCenter.x - r * 0.35f, bodyCenter.y - r * 0.25f),
                end = Offset(bodyCenter.x + r * 0.35f, bodyCenter.y + r * 0.35f)
            ),
            topLeft = Offset(bodyCenter.x - r * 0.30f, bodyCenter.y - r * 0.20f),
            size = Size(r * 0.64f, r * 0.52f),
            cornerRadius = CornerRadius(r * 0.26f, r * 0.26f)
        )
        // Pajama dots
        drawCircle(Color(0xFFB8A8FF).copy(alpha = 0.65f), radius = r * 0.045f, center = Offset(bodyCenter.x + r * 0.06f, bodyCenter.y - r * 0.02f))
        drawCircle(Color(0xFFB8A8FF).copy(alpha = 0.65f), radius = r * 0.04f, center = Offset(bodyCenter.x + r * 0.20f, bodyCenter.y + r * 0.10f))
        drawCircle(Color(0xFFB8A8FF).copy(alpha = 0.65f), radius = r * 0.04f, center = Offset(bodyCenter.x - r * 0.04f, bodyCenter.y + r * 0.14f))

        // Koala ears
        val headCenter = Offset(cx - r * 0.06f, cy - r * 0.08f)
        drawCircle(Color(0xFF6E7399), radius = r * 0.19f, center = Offset(headCenter.x - r * 0.28f, headCenter.y - r * 0.08f))
        drawCircle(Color(0xFFB8BEDD), radius = r * 0.11f, center = Offset(headCenter.x - r * 0.28f, headCenter.y - r * 0.08f))
        drawCircle(Color(0xFF6E7399), radius = r * 0.19f, center = Offset(headCenter.x + r * 0.28f, headCenter.y - r * 0.04f))
        drawCircle(Color(0xFFB8BEDD), radius = r * 0.11f, center = Offset(headCenter.x + r * 0.28f, headCenter.y - r * 0.04f))

        // Sleep cap
        val capPath = Path().apply {
            moveTo(headCenter.x - r * 0.24f, headCenter.y - r * 0.16f)
            cubicTo(headCenter.x - r * 0.05f, headCenter.y - r * 0.44f, headCenter.x + r * 0.34f, headCenter.y - r * 0.36f, headCenter.x + r * 0.42f, headCenter.y - r * 0.08f)
            lineTo(headCenter.x - r * 0.24f, headCenter.y - r * 0.16f)
            close()
        }
        drawPath(capPath, Color(0xFFD95B96))
        drawCircle(Color(0xFFFFEEF6), radius = r * 0.07f, center = Offset(headCenter.x + r * 0.44f, headCenter.y - r * 0.06f))

        // Koala head
        drawRoundRect(
            color = Color(0xFF8D94BA),
            topLeft = Offset(headCenter.x - r * 0.28f, headCenter.y - r * 0.20f),
            size = Size(r * 0.56f, r * 0.44f),
            cornerRadius = CornerRadius(r * 0.22f, r * 0.22f)
        )
        // Soft rosy cheeks
        drawCircle(Color(0xFFF29BB8).copy(alpha = 0.42f), radius = r * 0.055f, center = Offset(headCenter.x - r * 0.18f, headCenter.y + r * 0.09f))
        drawCircle(Color(0xFFF29BB8).copy(alpha = 0.42f), radius = r * 0.055f, center = Offset(headCenter.x + r * 0.18f, headCenter.y + r * 0.09f))
        // Nose & closed sleeping eyes
        drawRoundRect(
            color = Color(0xFF312A56),
            topLeft = Offset(headCenter.x - r * 0.07f, headCenter.y - r * 0.03f),
            size = Size(r * 0.14f, r * 0.18f),
            cornerRadius = CornerRadius(r * 0.07f, r * 0.07f)
        )
        val eyeStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val leftEye = Path().apply {
            moveTo(headCenter.x - r * 0.20f, headCenter.y + r * 0.02f)
            quadraticTo(headCenter.x - r * 0.15f, headCenter.y + r * 0.08f, headCenter.x - r * 0.10f, headCenter.y + r * 0.02f)
        }
        val rightEye = Path().apply {
            moveTo(headCenter.x + r * 0.10f, headCenter.y + r * 0.02f)
            quadraticTo(headCenter.x + r * 0.15f, headCenter.y + r * 0.08f, headCenter.x + r * 0.20f, headCenter.y + r * 0.02f)
        }
        drawPath(leftEye, Color(0xFF312A56), style = eyeStroke)
        drawPath(rightEye, Color(0xFF312A56), style = eyeStroke)
        // Cozy resting paw
        drawRoundRect(
            color = Color(0xFF8D94BA),
            topLeft = Offset(bodyCenter.x - r * 0.18f, bodyCenter.y + r * 0.10f),
            size = Size(r * 0.22f, r * 0.30f),
            cornerRadius = CornerRadius(r * 0.11f, r * 0.11f)
        )
        // Soft floating star sparkles around the moon
        drawCircle(Color(0xFFFFE3B3).copy(alpha = 0.85f), radius = 3.dp.toPx(), center = Offset(cx + r * 0.72f, cy - r * 0.56f))
        drawCircle(Color(0xFFFFE3B3).copy(alpha = 0.65f), radius = 2.2.dp.toPx(), center = Offset(cx - r * 0.86f, cy + r * 0.42f))
    }
}

/** Pastel purple hill & crescent moon window illustration from the right reference screen. */
@Composable
internal fun DreamHillWindowArt(modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFFF3EEFF)).clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        // Soft arch sky
        drawCircle(
            brush = Brush.verticalGradient(listOf(Color(0xFFE4D9FF), Color(0xFFF7F4FF))),
            radius = minOf(w, h) * 0.62f,
            center = Offset(w * 0.56f, h * 0.56f)
        )
        // Crescent moon
        val mx = w * 0.62f
        val my = h * 0.36f
        val mr = minOf(w, h) * 0.16f
        drawCircle(Color(0xFF7B5AF7), radius = mr, center = Offset(mx, my))
        drawCircle(Color(0xFFE9E0FF), radius = mr * 0.82f, center = Offset(mx + mr * 0.38f, my - mr * 0.18f))
        // Sparkle stars
        drawCircle(Color(0xFFF39C6B), radius = 3.dp.toPx(), center = Offset(w * 0.30f, h * 0.30f))
        drawCircle(Color(0xFFF39C6B), radius = 2.5.dp.toPx(), center = Offset(w * 0.82f, h * 0.52f))
        // Layered purple hills
        val backHill = Path().apply {
            moveTo(w * 0.08f, h)
            quadraticTo(w * 0.42f, h * 0.58f, w * 0.82f, h)
            close()
        }
        drawPath(backHill, Color(0xFF9C88FF))
        val frontHill = Path().apply {
            moveTo(w * 0.28f, h)
            quadraticTo(w * 0.68f, h * 0.62f, w * 1.02f, h)
            close()
        }
        drawPath(frontHill, Color(0xFF6B53E6))
    }
}

/** Lavender calendar badge for the computer's scheduled tasks. */
@Composable
internal fun DreamScheduleBadge(modifier: Modifier = Modifier) {
    Canvas(modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFEDE7FF)).clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val hill = Path().apply {
            moveTo(0f, h)
            quadraticTo(w * 0.5f, h * 0.68f, w, h)
            close()
        }
        drawPath(hill, Color(0xFFD2C4FF))
        drawRoundRect(Color(0xFF7B61FF), Offset(w * .23f, h * .26f), Size(w * .54f, h * .50f), CornerRadius(w * .09f))
        drawRoundRect(Color.White, Offset(w * .28f, h * .40f), Size(w * .44f, h * .31f), CornerRadius(w * .05f))
        for (x in listOf(.36f, .64f)) {
            drawLine(Color(0xFF42308B), Offset(w * x, h * .21f), Offset(w * x, h * .33f), strokeWidth = w * .05f, cap = StrokeCap.Round)
        }
        for (x in listOf(.39f, .50f, .61f)) {
            drawCircle(Color(0xFF7B61FF), radius = w * .025f, center = Offset(w * x, h * .55f))
        }
    }
}

/** Segmented capsule switcher inspired by the Day / Week / Month bar in the reference image. */
@Composable
internal fun DreamSegmentedTrack(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val motion = ValueAnimator.areAnimatorsEnabled()
    Surface(
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(26.dp),
        color = ComposeTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Row(
            Modifier.padding(5.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val bg by animateColorAsState(
                    targetValue = if (selected) Color(0xFF7B61FF) else Color.Transparent,
                    animationSpec = tween(if (motion) 220 else 0),
                    label = "segment color"
                )
                val fg by animateColorAsState(
                    targetValue = if (selected) Color.White else ComposeTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(if (motion) 220 else 0),
                    label = "segment text"
                )
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp)
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { onSelect(index) }
                        ),
                    shape = RoundedCornerShape(20.dp),
                    color = bg,
                    contentColor = fg
                ) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            style = ComposeTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Breakdown horizontal progress pill with a circular icon badge riding the fill edge. */
@Composable
internal fun DreamBreakdownPill(
    label: String,
    valueText: String,
    fraction: Float,
    violetTone: Boolean,
    icon: DreamIconKind,
    modifier: Modifier = Modifier
) {
    val motion = ValueAnimator.areAnimatorsEnabled()
    val clamped = fraction.coerceIn(0.38f, 0.92f)
    val animatedFill by animateFloatAsState(
        targetValue = clamped,
        animationSpec = if (motion) tween(680, easing = FastOutSlowInEasing) else tween(0),
        label = "breakdown pill fill"
    )
    val fillStart = if (violetTone) Color(0xFFD5B4FF) else Color(0xFF7FA6FF)
    val fillEnd = if (violetTone) Color(0xFFC49BFF) else Color(0xFF6B92F5)
    val badgeTint = if (violetTone) Color(0xFF9B6BF2) else Color(0xFF5A82E8)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(ComposeTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f))
    ) {
        val barWidth = maxWidth * animatedFill
        Box(
            Modifier
                .width(barWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.horizontalGradient(listOf(fillStart, fillEnd)))
        ) {
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(34.dp),
                shape = CircleShape,
                color = Color.White
            ) {
                Box(contentAlignment = Alignment.Center) {
                    DreamIcon(icon, Modifier.size(18.dp), badgeTint)
                }
            }
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = ComposeTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF231942)
            )
            Text(
                text = valueText,
                style = ComposeTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = ComposeTheme.colorScheme.onSurface
            )
        }
    }
}

/** Vertical pill bar chart with hatched secondary bars and vibrant active bar. */
@Composable
internal fun DreamActivityBarChart(
    bars: List<Pair<String, Float>>,
    highlightIndex: Int,
    modifier: Modifier = Modifier
) {
    val motion = ValueAnimator.areAnimatorsEnabled()
    val growth by animateFloatAsState(
        targetValue = 1f,
        animationSpec = if (motion) tween(720, easing = FastOutSlowInEasing) else tween(0),
        label = "bar chart growth"
    )
    Row(
        modifier = modifier.fillMaxWidth().height(148.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        bars.forEachIndexed { index, (caption, rawFraction) ->
            val active = index == highlightIndex
            val fraction = (rawFraction.coerceIn(0.18f, 1f) * growth).coerceIn(0.15f, 1f)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height(108.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Canvas(Modifier.fillMaxWidth().fillMaxHeight(fraction).clearAndSetSemantics { }) {
                        val r = size.width / 2f
                        val pill = Path().apply {
                            addRoundRect(
                                androidx.compose.ui.geometry.RoundRect(
                                    left = 0f,
                                    top = 0f,
                                    right = size.width,
                                    bottom = size.height,
                                    cornerRadius = CornerRadius(r, r)
                                )
                            )
                        }
                        if (active) {
                            drawPath(
                                path = pill,
                                brush = Brush.verticalGradient(
                                    listOf(Color(0xFF7B5AF7), Color(0xFF9C82FF))
                                )
                            )
                        } else {
                            drawPath(pill, Color(0xFFD5CEFA))
                            clipPath(pill) {
                                val step = 8.dp.toPx()
                                var y = -size.width
                                while (y < size.height + size.width) {
                                    drawLine(
                                        color = Color(0xFFC2B7F2),
                                        start = Offset(0f, y),
                                        end = Offset(size.width, y - size.width * 0.8f),
                                        strokeWidth = 2.dp.toPx()
                                    )
                                    y += step
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = caption,
                    style = ComposeTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) ComposeTheme.colorScheme.onSurface else ComposeTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** Soft rounded card vocabulary shared by the gallery, home and working pages. */
@Composable
internal fun DreamPaperCard(
    title: String,
    modifier: Modifier = Modifier,
    tone: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = ComposeTheme.colorScheme
    val container = when (tone) {
        1 -> colors.primaryContainer
        2 -> colors.secondaryContainer
        3 -> colors.tertiaryContainer
        else -> colors.surfaceContainerLow
    }
    val ink = when (tone) {
        1 -> colors.onPrimaryContainer
        2 -> colors.onSecondaryContainer
        3 -> colors.onTertiaryContainer
        else -> colors.onSurface
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = ink),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            // Subtle decorative twilight corner curve inspired by the reference cards
            Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
                val w = size.width
                val h = size.height
                drawCircle(
                    color = ink.copy(alpha = 0.045f),
                    radius = minOf(w, h) * 0.42f,
                    center = Offset(w * 0.92f, h * 0.14f)
                )
            }
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(title, style = ComposeTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }
}

@Composable
internal fun DreamSectionHeading(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = ComposeTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = ComposeTheme.typography.bodyMedium, color = ComposeTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Artwork is always an illustration; real camera views keep their own frame. */
@Composable
internal fun DreamSceneStrip(
    scene: Int,
    modifier: Modifier = Modifier,
    height: Dp = 164.dp,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val palette = dreamScenePalette(scene)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(RoundedCornerShape(28.dp))
    ) {
        Crossfade(
            targetState = scene,
            modifier = Modifier.matchParentSize(),
            animationSpec = tween(if (ValueAnimator.areAnimatorsEnabled()) 350 else 0),
            label = "paper scene"
        ) { target ->
            Image(
                painterResource(dreamSceneArtwork(target)),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0f, .45f)
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(
                        palette.skyTop.copy(alpha = .28f),
                        palette.skyMid.copy(alpha = .42f),
                        palette.hillFront.copy(alpha = .82f)
                    )
                )
            )
        )
        DreamTwilightSkyOverlay(
            scene = scene,
            animate = true,
            modifier = Modifier.matchParentSize(),
            horizonHills = false
        )
        Box(Modifier.fillMaxWidth().heightIn(min = height).padding(22.dp), content = content)
    }
}
