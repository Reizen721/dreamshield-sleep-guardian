package com.dreamshield.app

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme as ComposeTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

/** The cover owns the atmosphere; the room's actual information stays quiet and direct. */
@Composable
fun NativeHome(state: NativeState, coverHeight: Dp, modifier: Modifier = Modifier) {
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val minimumReadableHeight = 480.dp + 360.dp * (density.fontScale - 1f).coerceAtLeast(0f)
    val actualCoverHeight = coverHeight.coerceAtLeast(minimumReadableHeight)
    val freeCoverScroll = actualCoverHeight > coverHeight + 1.dp
    val coverPixels = with(density) { actualCoverHeight.toPx() }.coerceAtLeast(1f)
    val thresholdPixels = with(density) { CoverMotionPolicy.INTENT_DISTANCE_DP.dp.toPx() }
    val motion = ValueAnimator.areAnimatorsEnabled() && state.foreground
    val currentMotion by rememberUpdatedState(motion)
    val coverVisible by remember(scroll) { derivedStateOf { scroll.firstVisibleItemIndex == 0 } }
    val progress by remember(scroll, coverPixels) { derivedStateOf {
        CoverMotionPolicy.normalizedProgress(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, coverPixels)
    } }
    val boundary = remember(scroll, coverPixels, thresholdPixels) {
        NativeCoverBoundary(scroll, coverPixels, thresholdPixels) { currentMotion }
    }
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pinnedHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 126.dp
    LaunchedEffect(scroll, coverPixels) {
        snapshotFlow {
            CoverMotionPolicy.normalizedProgress(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, coverPixels)
        }.distinctUntilChanged().collect {
            state.coverProgress = it
            state.coverShowing = it < .72f
        }
    }
    Box(modifier.fillMaxSize().background(ComposeTheme.colorScheme.background)) {
        // One fixed-size scene stays behind the cover and becomes the pinned header.
        // Its crop never changes while dragging; only its mask and opacity change.
        NativeCoverArtwork(state.scene, actualCoverHeight, pinnedHeight, motion && coverVisible) { progress }
        LazyColumn(
            state = scroll,
            modifier = Modifier
                .fillMaxSize()
                .then(if (freeCoverScroll) Modifier else Modifier.nestedScroll(boundary)),
            contentPadding = PaddingValues(bottom = 104.dp + navigationBottom)
        ) {
            item(key = "panorama") {
                NativePanorama(state, actualCoverHeight, scroll, motion && coverVisible, freeCoverScroll) {
                    scope.launch { boundary.enterFromButton() }
                }
            }
            item(key = "overview") {
                HomeInset(top = pinnedHeight * .48f, bottom = 24.dp) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp, bottomStart = 28.dp, bottomEnd = 28.dp),
                        color = ComposeTheme.colorScheme.surface,
                        tonalElevation = 2.dp
                    ) {
                        Column(
                            Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(26.dp)
                        ) {
                            // Subtle sheet handle pill inspired by the left reference screen
                            Box(
                                Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .width(42.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(ComposeTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    DreamSectionHeading(text(R.string.copy_tonight))
                                    Text(
                                        state.connectionTitle,
                                        style = ComposeTheme.typography.bodyMedium,
                                        color = ComposeTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                FilledTonalButton(
                                    onClick = state::refresh,
                                    enabled = !state.busy,
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.heightIn(min = 44.dp)
                                ) { Text(text(R.string.copy_refresh)) }
                            }
                            if (state.message.isNotBlank()) {
                                Surface(
                                    color = ComposeTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text(
                                        state.message,
                                        Modifier.padding(16.dp),
                                        style = ComposeTheme.typography.bodyMedium,
                                        color = ComposeTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            if (state.welcomeInline) NativeWelcome(state)
                            NativeTonightCard(state)
                            NativeSoundCard(state.scene)
                            NativeComputer(state)
                            BoxWithConstraints {
                                if (maxWidth >= 740.dp) Row(
                                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    NativeEnvironment(state, Modifier.weight(1.05f))
                                    NativeObservation(state, Modifier.weight(1f))
                                } else Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                                    NativeEnvironment(state)
                                    NativeObservation(state)
                                }
                            }
                        }
                    }
                }
            }
            item(key = "camera") {
                HomeInset(top = 4.dp, bottom = 28.dp) {
                    NativeCamera(state)
                }
            }
        }
    }
}

/** A touch-only boundary. Programmatic/assistive scrolling can always enter the content. */
private class NativeCoverBoundary(
    private val scroll: LazyListState,
    private val coverPixels: Float,
    private val thresholdPixels: Float,
    private val motion: () -> Boolean
) : NestedScrollConnection {
    private val policy = CoverMotionPolicy()
    private var gestureStartedOnCover = false
    private var settling = false
    private var interrupted = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || settling || kotlin.math.abs(available.y) < .01f) return Offset.Zero
        if (scroll.firstVisibleItemIndex == 0) gestureStartedOnCover = true
        policy.observeDirection(available.y)
        if (scroll.firstVisibleItemIndex != 0) return Offset.Zero
        if (scroll.firstVisibleItemScrollOffset == 0 && available.y > 0f) {
            policy.reset()
            return Offset.Zero
        }
        // A restored partial cover or a reverse drag from the room view is already intentional.
        if (!policy.isUnlocked() && policy.intentPixels() == 0f &&
            scroll.firstVisibleItemScrollOffset > thresholdPixels * CoverMotionPolicy.RESISTANCE + 2f) {
            policy.unlockForButton()
            policy.observeDirection(available.y)
        }
        scroll.dispatchRawDelta(policy.resistedDelta(available.y, thresholdPixels))
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!gestureStartedOnCover) return Velocity.Zero
        // Clear before suspending: an interrupted settle must not consume a later content fling.
        gestureStartedOnCover = false
        if (scroll.firstVisibleItemIndex == 0) settle(policy.shouldEnterContent(), policy.scrollVelocity(available.y, thresholdPixels))
        // The opening gesture lands, rather than projecting its velocity deep into the room view.
        return available
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (interrupted) { interrupted = false; return Velocity.Zero }
        if (scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset > 0 && !settling) {
            settle(policy.shouldEnterContent())
        }
        if (scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset == 0) policy.reset()
        return Velocity.Zero
    }

    suspend fun enterFromButton() {
        if (settling) return
        policy.unlockForButton()
        settle(true, fromButton = true)
    }

    private suspend fun settle(toContent: Boolean, releaseVelocity: Float = 0f, fromButton: Boolean = false) {
        if (settling || scroll.firstVisibleItemIndex != 0) return
        settling = true
        interrupted = false
        if (toContent) policy.unlockForButton()
        try {
            if (!motion()) scroll.scrollToItem(if (toContent) 1 else 0)
            else {
                val distance = if (toContent) coverPixels - scroll.firstVisibleItemScrollOffset else -scroll.firstVisibleItemScrollOffset.toFloat()
                if (fromButton) {
                    // A slow beginning and end preserve the requested 1-second button reveal.
                    scroll.animateScrollBy(distance, tween(CoverMotionPolicy.ENTER_DURATION_MS, easing = FastOutSlowInEasing))
                } else {
                    // Keep the release velocity instead of restarting a front-loaded tween.
                    // The cap prevents a high-velocity flick from overshooting the boundary.
                    val velocity = releaseVelocity.coerceIn(-kotlin.math.abs(distance) * 3f, kotlin.math.abs(distance) * 3f)
                    scroll.scroll {
                        val scrollScope = this
                        var previous = 0f
                        AnimationState(initialValue = 0f, initialVelocity = velocity).animateTo(distance,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = if (toContent) 55f else 180f, visibilityThreshold = .5f)) {
                            scrollScope.scrollBy(value - previous)
                            previous = value
                        }
                    }
                }
                scroll.scrollToItem(if (toContent) 1 else 0)
            }
        } catch (cancelled: CancellationException) {
            interrupted = true
            throw cancelled
        } finally {
            settling = false
            if (!toContent || (scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset == 0)) policy.reset()
        }
    }
}

@Composable
private fun NativeCoverArtwork(scene: Int, coverHeight: Dp, pinnedHeight: Dp, animate: Boolean, readProgress: () -> Float) {
    val density = LocalDensity.current
    val pinnedPixels = with(density) { pinnedHeight.toPx() }
    val palette = dreamScenePalette(scene)
    val breathing: State<Float> = if (animate) rememberInfiniteTransition(label = "cover breathing").animateFloat(1.015f, 1.045f,
        infiniteRepeatable(tween(18000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "artwork scale")
        else remember { mutableStateOf(1.015f) }
    // The scene is decorative and never intercepts content or scrolling.
    Box(Modifier.fillMaxWidth().height(coverHeight).graphicsLayer {
        val p = readProgress()
        val eased = p * p * (3f - 2f * p)
        alpha = 1f - .68f * eased
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithContent {
        val p = readProgress()
        val visibleHeight = (size.height * (1f - p) + pinnedPixels * p).coerceIn(1f, size.height)
        val fade = (p / .6f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val contentScope = this
        clipRect(bottom = visibleHeight) {
            contentScope.drawContent()
            drawRect(Brush.verticalGradient(0f to Color.White,
                .24f to Color.White.copy(alpha = 1f - .2f * fade),
                .72f to Color.White.copy(alpha = 1f - .82f * fade),
                1f to Color.White.copy(alpha = 1f - fade), endY = visibleHeight),
                size = androidx.compose.ui.geometry.Size(size.width, visibleHeight), blendMode = BlendMode.DstIn)
        }
    }) {
        Crossfade(targetState = scene, animationSpec = tween(if (animate) 350 else 0), label = "scene change") { artwork ->
            Image(painterResource(sceneArtwork(artwork)), null, Modifier.fillMaxSize().graphicsLayer {
                val rest = 1f - readProgress()
                scaleX = 1f + (breathing.value - 1f) * rest * rest
                scaleY = scaleX
            }, contentScale = ContentScale.Crop)
        }
        // Rich twilight gradient veil inspired by the reference sleep tracking backdrop
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to palette.skyTop.copy(alpha = 0.74f),
                    0.42f to palette.skyMid.copy(alpha = 0.58f),
                    0.76f to palette.skyLower.copy(alpha = 0.54f),
                    1f to palette.horizonGlow.copy(alpha = 0.66f)
                )
            )
        )
        DreamTwilightSkyOverlay(
            scene = scene,
            animate = animate,
            modifier = Modifier.fillMaxSize(),
            horizonHills = true
        )
    }
}

private val sceneNames get() = listOf(text(R.string.copy_night_window), text(R.string.copy_afterglow), text(R.string.copy_forest_hush))
private fun sceneArtwork(index: Int) = dreamSceneArtwork(index)

@Composable
private fun NativePanorama(state: NativeState, height: Dp, scroll: LazyListState, animate: Boolean,
                           freeCoverScroll: Boolean, onEnter: () -> Unit) {
    val density = LocalDensity.current
    val fadeDistance = with(density) { height.toPx() * .7f }.coerceAtLeast(1f)
    val coverPixels = with(density) { height.toPx() }.coerceAtLeast(1f)
    val footerEnabled by remember(scroll, coverPixels, freeCoverScroll) { derivedStateOf {
        freeCoverScroll || CoverMotionPolicy.normalizedProgress(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, coverPixels) < .30f
    } }
    val arrowInteractions = remember { MutableInteractionSource() }
    val arrowFloat: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "arrow float").animateFloat(
            initialValue = -4f,
            targetValue = 4f,
            animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "arrow offset"
        )
    } else remember { mutableStateOf(0f) }

    BoxWithConstraints(Modifier.fillMaxWidth().height(height.coerceAtLeast(480.dp))) {
        val wide = maxWidth >= 740.dp
        val compact = height < 620.dp || density.fontScale > 1.35f
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = if (wide) 54.dp else 24.dp, vertical = if (compact) 14.dp else 20.dp)
        ) {
            // Top bar with circular badge and connection status pill
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        DreamIcon(DreamIconKind.Grid, Modifier.size(20.dp), Color.White)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text(R.string.copy_dreamshield), color = Color.White, style = ComposeTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                }
                Surface(
                    onClick = { state.showPage("settings") },
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                    modifier = Modifier.widthIn(max = 200.dp).heightIn(min = 44.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (state.online) Color(0xFF7CF29C) else Color(0xFFFFC078))
                        )
                        Text(
                            state.connectionTitle,
                            color = Color.White,
                            style = ComposeTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(if (compact) 14.dp else 24.dp))

            // Hero crescent moon illustration + refined poetic display typography
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val offset = if (scroll.firstVisibleItemIndex == 0) scroll.firstVisibleItemScrollOffset.toFloat() else fadeDistance
                        alpha = if (animate) (1f - offset / fadeDistance).coerceIn(0f, 1f) else 1f
                        translationY = if (animate) -(offset * .08f).coerceAtMost(45f) else 0f
                    },
                horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp)
            ) {
                if (!compact) {
                    DreamMoonGuardianArt(
                        scene = state.scene,
                        animate = animate,
                        modifier = Modifier.size(if (wide) 160.dp else 144.dp)
                    )
                }
                // Delicate starlight divider above the poetic display headline
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.width(24.dp).height(1.dp).background(Color.White.copy(alpha = 0.38f)))
                    DreamIcon(DreamIconKind.Sparkle, Modifier.size(12.dp), Color(0xFFFFDFB3).copy(alpha = 0.88f))
                    Box(Modifier.width(24.dp).height(1.dp).background(Color.White.copy(alpha = 0.38f)))
                }
                val baseHeadline = when {
                    compact -> ComposeTheme.typography.headlineMedium
                    wide -> ComposeTheme.typography.displayMedium
                    else -> ComposeTheme.typography.headlineLarge
                }
                Text(
                    when (state.scene) {
                        1 -> text(R.string.copy_fold_the_day_ninto_dusk)
                        2 -> text(R.string.copy_follow_the_trees_ninto_quiet)
                        else -> text(R.string.copy_let_the_night_nsettle_softly)
                    },
                    color = Color.White,
                    textAlign = if (wide) TextAlign.Start else TextAlign.Center,
                    style = baseHeadline.copy(
                        lineHeight = if (wide) 52.sp else 42.sp,
                        letterSpacing = 1.4.sp
                    ),
                    fontWeight = FontWeight.Normal
                )
            }

            Spacer(Modifier.weight(1f))

            // Footer controls leave with the artwork, before the light room surface is exposed.
            // This opacity-only scroll relationship also applies when system animations are off.
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val p = CoverMotionPolicy.normalizedProgress(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, coverPixels)
                        val phase = ((p - .04f) / .34f).coerceIn(0f, 1f)
                        alpha = if (freeCoverScroll) 1f else 1f - phase * phase * (3f - 2f * phase)
                    }
                    .then(if (footerEnabled) Modifier else Modifier.clearAndSetSemantics { })
                    .then(
                        if (freeCoverScroll) Modifier.background(Color.Black.copy(alpha = .65f), RoundedCornerShape(24.dp)).padding(16.dp)
                        else Modifier
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Floating 3-column summary card inspired by the reference center screen
                if (!compact) {
                    Surface(
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = Color.White.copy(alpha = 0.93f),
                        contentColor = Color(0xFF231942),
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverMetricColumn(
                                value = if (state.temperature == "—") "—" else "${state.temperature} °C",
                                label = if (state.previousSample) text(R.string.copy_last_room_temperature) else text(R.string.copy_room_temperature),
                                modifier = Modifier.weight(1f)
                            )
                            Box(Modifier.width(1.dp).height(32.dp).background(Color(0xFFE2DCF0)))
                            CoverMetricColumn(
                                value = if (state.humidity == "—") "—" else "${state.humidity} %",
                                label = if (state.previousSample) text(R.string.copy_last_humidity) else text(R.string.copy_relative_humidity),
                                modifier = Modifier.weight(1f)
                            )
                            Box(Modifier.width(1.dp).height(32.dp).background(Color(0xFFE2DCF0)))
                            CoverMetricColumn(
                                value = if (state.online) text(R.string.copy_connected) else text(R.string.copy_connect),
                                label = text(R.string.copy_room_status),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                Row(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    sceneNames.forEachIndexed { index, title ->
                        NativeSceneTicket(
                            index = index,
                            title = title,
                            selected = state.scene == index,
                            enabled = footerEnabled,
                            compact = compact,
                            modifier = Modifier.weight(1f)
                        ) { state.selectScene(index) }
                    }
                }
            }
        }
        // Only the stroke is visible. The transparent 56dp target stays centred
        // on the panorama and uses the same slow explicit entry as the old CTA.
        Box(
            Modifier
                .align(Alignment.Center)
                .size(56.dp)
                .graphicsLayer {
                    val p = CoverMotionPolicy.normalizedProgress(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, coverPixels)
                    val phase = ((p - .04f) / .34f).coerceIn(0f, 1f)
                    alpha = if (freeCoverScroll) 1f else 1f - phase * phase * (3f - 2f * phase)
                    translationY = if (animate && footerEnabled) arrowFloat.value.dp.toPx() else 0f
                }
                .then(
                    if (footerEnabled) Modifier.clickable(
                        interactionSource = arrowInteractions,
                        indication = null,
                        role = Role.Button,
                        onClick = onEnter
                    ).semantics { contentDescription = text(R.string.copy_swipe_to_explore_tonight) }
                    else Modifier.clearAndSetSemantics { }
                ),
            contentAlignment = Alignment.Center
        ) {
            DreamIcon(DreamIconKind.ArrowDown, Modifier.size(32.dp), Color.White)
        }
    }
}

@Composable
private fun CoverMetricColumn(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            style = ComposeTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF231942),
            maxLines = 1
        )
        Text(
            text = label,
            style = ComposeTheme.typography.labelSmall,
            color = Color(0xFF6E6588),
            maxLines = 1
        )
    }
}

@Composable
private fun NativeSceneTicket(index: Int, title: String, selected: Boolean, enabled: Boolean,
                              compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = ComposeTheme.colorScheme
    val motion = ValueAnimator.areAnimatorsEnabled()
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.03f else 1f,
        animationSpec = if (motion) spring(stiffness = 320f, dampingRatio = 0.75f) else tween(0),
        label = "ticket scale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) Color.White else Color.White.copy(alpha = 0.36f),
        animationSpec = tween(if (motion) 220 else 0),
        label = "ticket border"
    )
    OutlinedCard(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { stateDescription = if (selected) text(R.string.copy_selected) else text(R.string.copy_not_selected) },
        enabled = enabled,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) scheme.primaryContainer.copy(alpha = 0.94f) else Color(0xFF1E1344).copy(alpha = .62f),
            contentColor = if (selected) scheme.onPrimaryContainer else Color.White
        )
    ) {
        Box(Modifier.fillMaxWidth().height(if (compact) 36.dp else 50.dp)) {
            Image(
                painterResource(sceneArtwork(index)),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0f, .45f)
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF1A103D).copy(alpha = 0.42f)))
                )
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            val iconKind = when (index) {
                0 -> DreamIconKind.Moon
                1 -> DreamIconKind.Sparkle
                else -> DreamIconKind.Pulse
            }
            DreamIcon(iconKind, Modifier.size(14.dp))
            Text(
                title,
                Modifier.weight(1f),
                style = ComposeTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
            if (selected) DreamIcon(DreamIconKind.Check, Modifier.size(14.dp))
        }
    }
}

@Composable
private fun HomeInset(top: Dp = 0.dp, bottom: Dp = 0.dp, content: @Composable () -> Unit) {
    val window = LocalWindowInfo.current
    val windowWidth = with(LocalDensity.current) { window.containerSize.width.toDp() }
    val railInset = if (windowWidth >= 840.dp) 88.dp else 0.dp
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(start = 18.dp + railInset, end = 18.dp, top = top, bottom = bottom),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(Modifier.widthIn(max = 1100.dp).fillMaxWidth()) { content() }
    }
}

@Composable
private fun NativeWelcome(state: NativeState) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = ComposeTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
        contentColor = ComposeTheme.colorScheme.onPrimaryContainer
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text(R.string.copy_connect_to_see_your_room), style = ComposeTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { state.dismissWelcome(); state.showPage("settings") }) { Text(text(R.string.copy_connect_pc)) }
                FilledTonalButton(onClick = { state.dialog = "welcome" }) { Text(text(R.string.copy_quick_guide)) }
            }
            TextButton(onClick = state::dismissWelcome, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(text(R.string.copy_later)) }
        }
    }
}

@Composable
private fun NativeEnvironment(state: NativeState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text(R.string.copy_room_climate), style = ComposeTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(state.sensorDetail, style = ComposeTheme.typography.labelMedium, color = ComposeTheme.colorScheme.onSurfaceVariant)
        val largeText = LocalDensity.current.fontScale > 1.3f
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 320.dp || largeText) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                NativeWeatherNote(text(R.string.copy_room_temperature), state.temperature, "°C", true, state.scene, Modifier.fillMaxWidth())
                NativeWeatherNote(text(R.string.copy_relative_humidity), state.humidity, "% RH", false, state.scene, Modifier.fillMaxWidth())
            } else Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                NativeWeatherNote(text(R.string.copy_room_temperature), state.temperature, "°C", true, state.scene, Modifier.weight(1f))
                NativeWeatherNote(text(R.string.copy_relative_humidity), state.humidity, "% RH", false, state.scene, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NativeWeatherNote(title: String, value: String, unit: String, prominent: Boolean, scene: Int, modifier: Modifier) {
    val palette = dreamScenePalette(scene)
    val gradStart = if (prominent) palette.cardVioletStart else palette.cardBlueStart
    val gradEnd = if (prominent) palette.cardVioletEnd else palette.cardBlueEnd
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(gradStart, gradEnd)))
        ) {
            // Illustrated twilight hills and moon/waves in bottom-right corner like "Your activities" cards
            Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
                val w = size.width
                val h = size.height
                val wave1 = Path().apply {
                    moveTo(0f, h * 0.78f)
                    quadraticTo(w * 0.45f, h * 0.62f, w, h * 0.74f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(wave1, Color.White.copy(alpha = 0.14f))
                val wave2 = Path().apply {
                    moveTo(w * 0.25f, h)
                    quadraticTo(w * 0.68f, h * 0.68f, w, h * 0.82f)
                    lineTo(w, h)
                    close()
                }
                drawPath(wave2, Color.White.copy(alpha = 0.18f))
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f),
                    radius = 18.dp.toPx(),
                    center = Offset(w * 0.82f, h * 0.28f)
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 148.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(title, style = ComposeTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.92f))
                    DreamIcon(
                        if (prominent) DreamIconKind.Moon else DreamIconKind.Pulse,
                        Modifier.size(18.dp),
                        Color.White.copy(alpha = 0.88f)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(value, style = ComposeTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(unit, style = ComposeTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
    }
}

@Composable
private fun NativeObservation(state: NativeState, modifier: Modifier = Modifier) {
    DreamPaperCard(text(R.string.copy_ai_observation), modifier, tone = 1) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.aiTitle, style = ComposeTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            DreamHillWindowArt(Modifier.size(width = 76.dp, height = 60.dp))
        }
        HorizontalDivider(color = ComposeTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeLine(text(R.string.copy_latest_visual_event), state.aiEvent)
            HomeLine(text(R.string.copy_follow_up), state.aiConfirmation)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { state.showPage("control") }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Text(text(R.string.copy_manage_observation)) }
                FilledTonalButton(onClick = { state.showPage("history") }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Text(text(R.string.copy_open_night_records)) }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { state.showPage("control") }, Modifier.weight(1f), shape = RoundedCornerShape(20.dp)) { Text(text(R.string.copy_manage_observation)) }
                FilledTonalButton(onClick = { state.showPage("history") }, Modifier.weight(1f), shape = RoundedCornerShape(20.dp)) { Text(text(R.string.copy_night_records)) }
            }
        }
    }
}

@Composable
private fun NativeCamera(state: NativeState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        color = ComposeTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            DreamSectionHeading(text(R.string.copy_room_camera))
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF1D153B),
                contentColor = Color.White
            ) {
                val bitmap = state.previewBitmap
                if (bitmap != null) {
                    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentAlignment = Alignment.Center) {
                        Image(bitmap.asImageBitmap(), text(R.string.copy_current_dreamshield_camera_image), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        if (!state.previewActive) Surface(
                            Modifier.align(Alignment.BottomCenter).padding(12.dp),
                            color = ComposeTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text(R.string.copy_preview_paused_still_image),
                                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = ComposeTheme.typography.labelMedium,
                                color = ComposeTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                } else Box(
                    Modifier.fillMaxWidth().heightIn(min = 216.dp),
                    contentAlignment = Alignment.Center
                ) {
                    DreamTwilightSkyOverlay(
                        scene = state.scene,
                        animate = state.foreground,
                        modifier = Modifier.matchParentSize(),
                        horizonHills = true
                    )
                    Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                DreamIcon(DreamIconKind.Camera, Modifier.size(32.dp), Color.White)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (state.previewActive) text(R.string.copy_loading_preview) else text(R.string.copy_preview_paused),
                            style = ComposeTheme.typography.titleLarge,
                            color = Color.White
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = state::togglePreview,
                    enabled = !state.busy && (state.usable || state.previewActive),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.weight(1.2f).heightIn(min = 48.dp)
                ) { Text(if (state.previewActive) text(R.string.copy_pause_preview) else text(R.string.copy_open_preview)) }
                FilledTonalButton(
                    onClick = state::capture,
                    enabled = state.usable && !state.busy,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(text(R.string.copy_capture)) }
            }
            TextButton(
                onClick = state::savePreview,
                enabled = state.usable && !state.busy && state.previewBitmap != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 0.dp)
            ) {
                DreamIcon(DreamIconKind.Download, Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(text(R.string.copy_save_image_to_gallery))
            }
        }
    }
}

@Composable
private fun NativeComputer(state: NativeState, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val config = state.status.optJSONObject("config")
    val schedule = if (!state.online) text(R.string.copy_unknown) else if (config?.optBoolean("schedule_enabled") == true)
        text(R.string.copy_on_every_s_s, config.optInt("capture_interval_seconds")) else text(R.string.copy_off)
    val recording = if (!state.online) text(R.string.copy_unknown) else if (config?.optBoolean("environment_recording_enabled") == true)
        text(R.string.copy_logging_every_s_s, config.optInt("environment_interval_seconds")) else text(R.string.copy_off)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = ComposeTheme.colorScheme.surfaceContainerLow,
        contentColor = ComposeTheme.colorScheme.onSurface,
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) text(R.string.copy_expanded) else text(R.string.copy_collapsed) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                DreamScheduleBadge()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text(R.string.copy_pc_background_tasks), style = ComposeTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        state.backgroundControlTitle,
                        style = ComposeTheme.typography.bodySmall,
                        color = ComposeTheme.colorScheme.onSurfaceVariant
                    )
                }
                DreamIcon(
                    if (expanded) DreamIconKind.ChevronDown else DreamIconKind.ChevronRight,
                    Modifier.size(20.dp),
                    ComposeTheme.colorScheme.onSurfaceVariant
                )
            }
            val duration = if (ValueAnimator.areAnimatorsEnabled() && state.foreground) 260 else 0
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(duration)) + fadeIn(tween(duration)),
                exit = shrinkVertically(tween(duration)) + fadeOut(tween(duration))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = ComposeTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    HomeLine(text(R.string.copy_connection), state.connectionTitle)
                    HomeLine(text(R.string.copy_scheduled_capture), schedule)
                    HomeLine(text(R.string.copy_climate_log), recording)
                    val next = state.status.optLong("next_capture_epoch_ms", 0)
                    HomeLine(
                        text(R.string.copy_next_capture),
                        if (state.online && next > 0) DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(next)) else "—"
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeLine(label: String, value: String, labelColor: Color? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(.9f), style = ComposeTheme.typography.bodyMedium, color = labelColor ?: ComposeTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, Modifier.weight(1.1f), style = ComposeTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
