package com.dreamshield.app

import android.os.Build
import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme as ComposeTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Installs native Compose content. No browser, HTML, JavaScript or remote UI is loaded. */
object NativeUiHost {
    @JvmStatic
    fun install(activity: MainActivity): NativeState {
        val state = NativeState(activity)
        val content = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { DreamShieldNativeTheme { NativeApplication(activity, state) } }
        }
        activity.setContentView(content)
        return state
    }
}

@Composable
private fun DreamShieldNativeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        darkColorScheme(
            primary = Color(0xffd0bcff),
            primaryContainer = Color(0xff4f378b),
            secondaryContainer = Color(0xff4a4458),
            surface = Color(0xff141218)
        )
    } else {
        lightColorScheme(
            primary = Color(0xff6750a4),
            primaryContainer = Color(0xffeaddff),
            secondaryContainer = Color(0xffe8def8),
            surface = Color(0xfffffbfe)
        )
    }
    ComposeTheme(colorScheme = colors, typography = Typography(), content = content)
}

@Composable
private fun NativeApplication(activity: MainActivity, state: NativeState) {
    val pageStates = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        if (state.message.isNotBlank() && state.page != "home") snackbar.showSnackbar(state.message)
    }
    SideEffect { activity.updateNativeSystemBars(state.setupVisible || (state.page == "home" && state.coverShowing)) }
    val deferredGuide = state.dialog == "welcome" && state.page == "home" && state.coverShowing
    val consumesBack = state.setupVisible || state.page != "home" || state.selectedImagePath != null ||
        state.sleep.nightMode ||
        state.selectedImage != null || (state.dialog != null && !deferredGuide)
    BackHandler(enabled = consumesBack) {
        if (state.setupVisible) {
            if (state.setupStep > 0) state.previousSetupStep() else state.deferSetup()
        } else if(state.sleep.nightMode) state.sleep.nightMode=false
        else if (!state.handleBack()) activity.finishAfterTransition()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (state.setupVisible) {
            NativeSetup(state)
            NativeLaunchCurtain(state)
            return@BoxWithConstraints
        }
        val viewportHeight = maxHeight
        val expanded = maxWidth >= 840.dp
        val motion = ValueAnimator.areAnimatorsEnabled() && state.foreground
        val density = LocalDensity.current
        // Every destination shares the same viewport and the same transition.
        // Bars overlay it rather than changing the cover's size at a tab boundary.
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    snackbar,
                    Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(
                            start = if (expanded) 88.dp else 0.dp,
                            bottom = if (expanded) 16.dp else 104.dp
                        )
                )
            }
        ) { padding ->
            AnimatedContent(
                targetState = state.page,
                modifier = Modifier.fillMaxSize().padding(padding),
                transitionSpec = {
                    ((fadeIn(tween(if (motion) 260 else 0, delayMillis = if (motion) 60 else 0)) +
                        slideInVertically(tween(if (motion) 320 else 0, easing = FastOutSlowInEasing)) {
                            with(density) { 12.dp.roundToPx() }
                        }) togetherWith fadeOut(tween(if (motion) 110 else 0))).using(null)
                },
                label = "native destinations"
            ) { page ->
                val active = page == state.page
                val pageModifier = Modifier
                    .fillMaxSize()
                    .then(if (active) Modifier else Modifier.clearAndSetSemantics { })
                    .pointerInput(active) {
                        if (!active) awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                pageStates.SaveableStateProvider(page) {
                    if (page == "home") NativeHome(state, viewportHeight, pageModifier)
                    else Box(
                        pageModifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(
                                start = if (expanded) 88.dp else 0.dp,
                                bottom = if (expanded) 0.dp else 80.dp
                            )
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        when (page) {
                            "records", "history" -> NativeRecords(state)
                            "control" -> NativeControl(state)
                            else -> NativeSettings(state)
                        }
                    }
                }
            }
        }
        NativeAppNavigation(state, expanded)
        if(!state.setupVisible && !state.launchVisible) {
            NativeNightMode(state)
            if(!state.sleep.nightMode) NativeSleepDialogs(state)
        }
        // Let the requested panoramic first screen appear before the first-use guide.
        if (!state.launchVisible && !(state.dialog == "welcome" && state.page == "home" && state.coverShowing)) NativeDialogs(state)
        NativeLaunchCurtain(state)
    }
}

@Composable
private fun BoxScope.NativeAppNavigation(state: NativeState, expanded: Boolean) {
    val motion = ValueAnimator.areAnimatorsEnabled() && state.foreground
    // Page changes animate; drag progress is read directly in the draw layer.
    // A second tween chasing every finger update used to add lag to the cover.
    val pagePresence = animateFloatAsState(
        if (state.page == "home") 0f else 1f,
        animationSpec = tween(if (motion) 320 else 0, easing = FastOutSlowInEasing),
        label = "destination navigation"
    )
    val readReveal = remember(state, pagePresence) { {
        val phase = ((state.coverProgress - .55f) / .45f).coerceIn(0f, 1f)
        val cover = phase * phase * (3f - 2f * phase)
        pagePresence.value + (1f - pagePresence.value) * cover
    } }
    val visible by remember(readReveal) { derivedStateOf { readReveal() > .001f } }
    val enabled by remember(readReveal, state) { derivedStateOf { readReveal() >= .88f && state.foreground } }
    if (!visible) return
    val density = LocalDensity.current
    val hiddenSemantics = if (enabled) Modifier else Modifier.clearAndSetSemantics { }
    if (expanded) {
        NativeRail(
            state,
            enabled,
            Modifier
                .align(Alignment.CenterStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .graphicsLayer {
                    val reveal = readReveal()
                    alpha = reveal
                    translationX = with(density) { (-14.dp).toPx() } * (1f - reveal)
                }
                .then(hiddenSemantics)
        )
    } else {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .graphicsLayer {
                    val reveal = readReveal()
                    alpha = reveal
                    translationY = with(density) { 18.dp.toPx() } * (1f - reveal)
                }
                .then(hiddenSemantics)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                ComposeTheme.colorScheme.surfaceContainerLow.copy(alpha = 0f),
                                ComposeTheme.colorScheme.surfaceContainerLow
                            )
                        )
                    )
            )
            NativeBottomNavigation(state, enabled)
        }
    }
}

@Composable
private fun NativeBottomNavigation(state: NativeState, enabled: Boolean) {
    Surface(
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        color = ComposeTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp
    ) {
        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
            NativeDestination.entries.forEach { destination ->
                val selected = state.page == destination.page
                NavigationBarItem(
                    selected = selected,
                    enabled = enabled,
                    onClick = { state.showPage(destination.page) },
                    icon = { NativeNavIcon(destination, selected) },
                    label = { Text(destination.label) }
                )
            }
        }
    }
}

@Composable
private fun NativeRail(state: NativeState, enabled: Boolean, modifier: Modifier = Modifier) {
    NavigationRail(
        modifier.width(88.dp).fillMaxHeight().statusBarsPadding().navigationBarsPadding(),
        containerColor = ComposeTheme.colorScheme.surfaceContainerLow
    ) {
        Spacer(Modifier.height(24.dp))
        Text(text(R.string.copy_dreamshield), style = ComposeTheme.typography.titleMedium)
        Spacer(Modifier.height(26.dp))
        NativeDestination.entries.forEach { destination ->
            val selected = state.page == destination.page
            NavigationRailItem(
                selected = selected,
                enabled = enabled,
                onClick = { state.showPage(destination.page) },
                icon = { NativeNavIcon(destination, selected) },
                label = { Text(destination.label) }
            )
        }
    }
}

private enum class NativeDestination(val page: String, @param:androidx.annotation.StringRes private val labelId: Int) {
    Home("home", R.string.copy_tonight_755), Records("history", R.string.copy_records), Control("control", R.string.copy_observe_757), Settings("settings", R.string.copy_settings);

    val label: String get() = text(labelId)
}

@Composable
private fun NativeNavIcon(destination: NativeDestination, selected: Boolean) {
    val motion = ValueAnimator.areAnimatorsEnabled()
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = if (motion) spring(stiffness = 340f, dampingRatio = 0.65f) else tween(0),
        label = "nav icon scale"
    )
    val tint = if (selected) ComposeTheme.colorScheme.onSecondaryContainer else ComposeTheme.colorScheme.onSurfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // Subtle top indicator line inspired by the reference bottom navigation bar
        Box(
            Modifier
                .width(if (selected) 16.dp else 0.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(if (selected) Color(0xFF7B61FF) else Color.Transparent)
        )
        Canvas(Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
            val line = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val u = size.width / 24f
            fun path(vararg coordinates: Pair<Float, Float>, close: Boolean = false): Path = Path().apply {
                coordinates.forEachIndexed { index, p -> if (index == 0) moveTo(p.first * u, p.second * u) else lineTo(p.first * u, p.second * u) }
                if (close) close()
            }
            when (destination) {
                NativeDestination.Home -> {
                    drawPath(path(3f to 10f, 12f to 3f, 21f to 10f, 21f to 21f, 3f to 21f, close = true), tint, style = line)
                    drawPath(path(9f to 21f, 9f to 13f, 15f to 13f, 15f to 21f), tint, style = line)
                }
                NativeDestination.Records -> {
                    drawRoundRect(tint, Offset(4 * u, 3 * u), Size(16 * u, 18 * u), CornerRadius(3 * u), style = line)
                    for (y in listOf(8f, 12f, 16f)) drawLine(tint, Offset(8 * u, y * u), Offset((if (y == 16f) 13f else 16f) * u, y * u), 1.6f * u, cap = StrokeCap.Round)
                }
                NativeDestination.Control -> {
                    drawLine(tint, Offset(12 * u, 3 * u), Offset(12 * u, 21 * u), 1.7f * u, cap = StrokeCap.Round)
                    drawLine(tint, Offset(3 * u, 12 * u), Offset(21 * u, 12 * u), 1.7f * u, cap = StrokeCap.Round)
                    drawPath(path(12f to 4f, 15f to 9f, 20f to 12f, 15f to 15f, 12f to 20f, 9f to 15f, 4f to 12f, 9f to 9f, close = true), tint, style = line)
                }
                NativeDestination.Settings -> {
                    for ((x, y) in listOf(5f to 8f, 12f to 16f, 19f to 10f)) {
                        drawLine(tint, Offset(x * u, 3 * u), Offset(x * u, 21 * u), 1.6f * u, cap = StrokeCap.Round)
                        drawCircle(tint, 2.5f * u, Offset(x * u, y * u), style = line)
                    }
                }
            }
        }
    }
}
