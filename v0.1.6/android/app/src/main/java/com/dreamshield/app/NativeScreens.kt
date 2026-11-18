package com.dreamshield.app

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme as ComposeTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Native settings, history and consent surfaces. All actions use NativeState. */

@Composable
private fun NativePage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 1000.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            content()
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun NativeBlock(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = ComposeTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        content()
        HorizontalDivider(Modifier.padding(top = 8.dp), color = ComposeTheme.colorScheme.outlineVariant)
    }
}

private enum class NativeCardTone { Primary, Quiet, Outlined }
private val LocalNativeSupportColor = compositionLocalOf<Color?> { null }

/** Cards group a task, rather than wrapping each setting row. */
@Composable
private fun NativeSectionCard(title: String, modifier: Modifier = Modifier, tone: NativeCardTone = NativeCardTone.Quiet,
                              content: @Composable ColumnScope.() -> Unit) {
    val scheme = ComposeTheme.colorScheme
    val primary = tone == NativeCardTone.Primary
    val container = if (primary) scheme.primaryContainer else if (tone == NativeCardTone.Outlined) scheme.surface else scheme.surfaceContainerLow
    val foreground = if (primary) scheme.onPrimaryContainer else scheme.onSurface
    val supporting = if (primary) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    val body: @Composable ColumnScope.() -> Unit = {
        CompositionLocalProvider(LocalNativeSupportColor provides supporting) {
            content()
        }
    }
    if (tone == NativeCardTone.Outlined) {
        OutlinedCard(
            modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = container, contentColor = foreground)
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(title, style = ComposeTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                body()
            }
        }
    } else {
        DreamPaperCard(title, modifier, tone = if (primary) 1 else 0, content = body)
    }
}

@Composable
private fun NativeCardPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    val largeText = LocalDensity.current.fontScale >= 1.4f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 760.dp && !largeText) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1.15f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun NativeNote(text: String, error: Boolean = false) {
    if (text.isNotBlank()) Text(text, style = ComposeTheme.typography.bodySmall,
        color = if (error) ComposeTheme.colorScheme.error else LocalNativeSupportColor.current ?: ComposeTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NativeLine(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Text(title, Modifier.weight(1f), style = ComposeTheme.typography.bodyMedium,
            color = LocalNativeSupportColor.current ?: ComposeTheme.colorScheme.onSurfaceVariant)
        SelectionContainer(Modifier.weight(1.2f)) {
            Text(value.ifBlank { "—" }, style = ComposeTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun NativeSwitch(title: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = ComposeTheme.typography.titleSmall)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun NativeNumber(label: String, value: String, unit: String, enabled: Boolean, readOnly: Boolean = false,
                         range: ClosedFloatingPointRange<Double>? = null, integer: Boolean = true, change: (String) -> Unit) {
    val numeric = value.toDoubleOrNull()
    val invalid = value.isNotBlank() && (numeric == null || !numeric.isFinite() ||
        (range != null && numeric !in range) || (integer && numeric % 1.0 != 0.0))
    OutlinedTextField(
        value = value, onValueChange = change, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        label = { Text(label) }, suffix = { Text(unit) }, enabled = enabled, readOnly = readOnly,
        singleLine = true, isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        supportingText = if (range != null) ({ Text("${range.start.screenNumber()}–${range.endInclusive.screenNumber()} $unit${if (integer) text(R.string.copy_whole_number) else ""}") }) else null
    )
}

private fun Double.screenNumber(): String = if (this % 1.0 == 0.0) toInt().toString() else toString()
private fun screenTime(value: String): String = try {
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
} catch (_: Exception) { value.take(80).ifBlank { "—" } }

private fun recordHourBucket(rawTime: String): Int = try {
    val hour = Instant.parse(rawTime).atZone(ZoneId.systemDefault()).hour
    (hour / 4).coerceIn(0, 5)
} catch (_: Exception) { 5 }

private data class PolicyField(val key: String, val label: String, val unit: String,
                               val minimum: Double, val maximum: Double, val integer: Boolean = true)
private val policyFields get() = listOf(
    PolicyField("temperature_lower_c", text(R.string.copy_room_temperature_minimum), "°C", 18.0, 32.0, false),
    PolicyField("temperature_upper_c", text(R.string.copy_room_temperature_maximum), "°C", 18.0, 32.0, false),
    PolicyField("environment_persistence_seconds", text(R.string.copy_minimum_trigger_duration), text(R.string.copy_s), 30.0, 600.0),
    PolicyField("temperature_step_c", text(R.string.copy_temperature_adjustment_step), "°C", 1.0, 2.0),
    PolicyField("command_cooldown_seconds", text(R.string.copy_adjustment_cooldown), text(R.string.copy_s), 60.0, 3600.0),
    PolicyField("confirmation_target_seconds", text(R.string.copy_follow_up_image_delay), text(R.string.copy_s), 20.0, 50.0),
    PolicyField("min_setpoint_c", text(R.string.copy_minimum_ac_setpoint), "°C", 16.0, 30.0),
    PolicyField("max_setpoint_c", text(R.string.copy_maximum_ac_setpoint), "°C", 16.0, 30.0)
)

private fun screenLabel(value: String): String = when (value) {
    "manual" -> text(R.string.copy_manual_capture)
    "scheduled" -> text(R.string.copy_scheduled_capture)
    "automatic_ai_confirmation" -> text(R.string.copy_ai_follow_up_frame)
    "baseline_saved", "baseline_required" -> text(R.string.copy_baseline)
    "pair_completed" -> text(R.string.copy_image_comparison_complete)
    "confirmation_completed" -> text(R.string.copy_follow_up_complete)
    "no_significant_coverage_change" -> text(R.string.copy_no_significant_coverage_change)
    "significant_coverage_reduction_observed", "significant_coverage_reduction_candidate" -> text(R.string.copy_coverage_significantly_reduced)
    "suspected_blanket_kicking" -> text(R.string.copy_possible_blanket_displacement)
    "indeterminate", "inconclusive" -> text(R.string.copy_review_needed)
    "no_person" -> text(R.string.copy_no_person_detected)
    "confirmed" -> text(R.string.copy_uncovered_state_confirmed)
    "recovered" -> text(R.string.copy_coverage_restored)
    "not_required" -> text(R.string.copy_no_follow_up_needed)
    "confirmation_pending", "waiting_confirmation" -> text(R.string.copy_waiting_for_a_follow_up_frame)
    "waiting_baseline" -> text(R.string.copy_waiting_for_a_baseline_frame)
    "waiting_frame", "waiting_capture", "idle" -> text(R.string.copy_waiting_for_the_next_capture)
    "analyzing", "comparing" -> text(R.string.copy_comparing_images)
    "confirming" -> text(R.string.copy_analyzing_follow_up)
    "completed" -> text(R.string.copy_observation_complete)
    "cooldown", "minimum_interval" -> text(R.string.copy_waiting_for_the_next_interval)
    "backoff", "failure_backoff" -> text(R.string.copy_request_failed_retrying_later)
    "paused_budget", "budget_exhausted", "hourly_budget_exhausted", "session_budget_exhausted" -> text(R.string.copy_budget_reached_uploads_paused)
    "error", "failed" -> text(R.string.copy_observation_failed)
    "cancelled" -> text(R.string.copy_canceled)
    "stopped", "user_stopped" -> text(R.string.copy_stopped)
    "unknown" -> text(R.string.copy_captures)
    else -> "—"
}

private val helpParagraphs get() = listOf(
    text(R.string.copy_first_connection) to text(R.string.copy_in_settings_use_bluetooth_to_set_up_freenove_wi_fi),
    text(R.string.copy_connection_problems) to text(R.string.copy_keep_the_pc_awake_and_its_server_running_allow_loc),
    text(R.string.copy_preview_and_capture) to text(R.string.copy_live_preview_refreshes_on_the_home_screen_captures),
    text(R.string.copy_missing_ai_events) to text(R.string.copy_the_pc_needs_a_configured_model_and_upload_consent),
    text(R.string.copy_stopping_uploads) to text(R.string.copy_turn_off_automatic_ai_to_stop_new_uploads_closing)
)

private val privacyParagraphs get() = listOf(
    text(R.string.copy_bluetooth_setup) to text(R.string.copy_pair_over_bluetooth_without_a_code_the_password_is),
    text(R.string.copy_pc_records) to text(R.string.copy_the_pc_stores_captures_climate_samples_and_ai_even),
    text(R.string.copy_phone_data) to text(R.string.copy_saved_copies_are_in_pictures_dreamshield_unpairing),
    text(R.string.copy_cloud_ai) to text(R.string.copy_with_consent_the_pc_sends_scheduled_images_to_its),
    text(R.string.copy_local_network) to text(R.string.copy_pairing_verifies_the_https_certificate_fingerprint),
    text(R.string.copy_about_observations) to text(R.string.copy_dreamshield_shows_camera_and_climate_data_use_heal)
)

@Composable
private fun NativeParagraphs(items: List<Pair<String, String>>) {
    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items.forEach { (title, text) ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = ComposeTheme.typography.titleSmall)
                Text(text, style = ComposeTheme.typography.bodyMedium, color = ComposeTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun NativeRecords(state: NativeState) {
    var aiTab by remember { mutableStateOf(false) }
    val slotLabels = listOf("00–04", "04–08", "08–12", "12–16", "16–20", "20–24")
    val bucketCounts = remember(aiTab, state.captures, state.aiEvents) {
        val counts = IntArray(6)
        if (aiTab) {
            state.aiEvents.take(20).forEach { record ->
                val raw = record.optJSONObject("after")?.optString("captured_at").orEmpty()
                    .ifBlank { record.optString("created_at").ifBlank { record.optString("completed_at") } }
                counts[recordHourBucket(raw)]++
            }
        } else {
            state.captures.take(12).forEach { item ->
                counts[recordHourBucket(item.optString("captured_at"))]++
            }
        }
        counts
    }
    val maxBucket = (bucketCounts.maxOrNull() ?: 0).coerceAtLeast(1)
    val peakIndex = remember(bucketCounts) {
        val maxVal = bucketCounts.maxOrNull() ?: 0
        if (maxVal > 0) bucketCounts.indexOfFirst { it == maxVal } else 5
    }
    val chartBars = remember(bucketCounts, maxBucket) {
        slotLabels.mapIndexed { idx, label ->
            val fraction = if (bucketCounts.sum() == 0) 0.22f else (0.22f + 0.78f * (bucketCounts[idx].toFloat() / maxBucket))
            label to fraction
        }
    }

    NativePage {
        DreamSceneStrip(state.scene, height = 208.dp) {
            DreamMoonGuardianArt(
                scene = state.scene,
                animate = state.foreground,
                modifier = Modifier.align(Alignment.TopEnd).size(96.dp)
            )
            Text(text(R.string.copy_night_journal), Modifier.align(Alignment.BottomStart), style = ComposeTheme.typography.headlineMedium, color = Color.White)
        }
        NativeSleepRecords(state)
        // Segmented capsule bar inspired by the right reference screen ("Day | Week | Month")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DreamSegmentedTrack(
                options = listOf(text(R.string.copy_captures_210), text(R.string.copy_ai_events)),
                selectedIndex = if (aiTab) 1 else 0,
                onSelect = { aiTab = (it == 1) },
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(
                onClick = state::loadHistory,
                enabled = state.usable && !state.busy,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(text(R.string.copy_refresh)) }
        }

        // Overview card + bar chart inspired by the right reference screen
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = ComposeTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val totalCount = if (aiTab) state.aiEvents.size else state.captures.size
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = totalCount.toString(),
                                style = ComposeTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (aiTab) text(R.string.copy_observations) else text(R.string.copy_captures_213),
                                style = ComposeTheme.typography.titleMedium,
                                color = ComposeTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        Text(
                            text = if (!state.paired) text(R.string.copy_connect_the_pc_to_sync_night_records) else if (aiTab) state.aiTitle else state.connectionTitle,
                            style = ComposeTheme.typography.bodyMedium,
                            color = ComposeTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DreamHillWindowArt(Modifier.size(width = 112.dp, height = 84.dp))
                }
                DreamActivityBarChart(
                    bars = chartBars,
                    highlightIndex = peakIndex
                )
            }
        }

        if (!state.paired) {
            NativeBlock(text(R.string.copy_connect_a_pc)) {
                Button(onClick = { state.showPage("settings") }, shape = RoundedCornerShape(20.dp)) { Text(text(R.string.copy_connect_216)) }
            }
        } else if (aiTab) {
            if (state.aiEvents.isEmpty()) NativeBlock(text(R.string.copy_no_observations_yet)) {
            }
            state.aiEvents.take(20).forEach { record ->
                val assessment = record.optJSONObject("result")?.optJSONObject("assessment") ?: JSONObject()
                val event = assessment.optString("validated_visual_decision").ifBlank { assessment.optString("visual_event") }
                val confirmation = assessment.optString("blanket_event_state").ifBlank { assessment.optString("confirmation_state") }
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = ComposeTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        NativeNote(screenTime(record.optJSONObject("after")?.optString("captured_at").orEmpty().ifBlank { record.optString("created_at").ifBlank { record.optString("completed_at") } }))
                        Text(screenLabel(event).takeUnless { it == "—" } ?: text(R.string.copy_observation),
                            style = ComposeTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        NativeLine(text(R.string.copy_follow_up), screenLabel(confirmation))
                        val confidence = (assessment.opt("visual_comparison_confidence") as? Number)?.toDouble() ?: Double.NaN
                        if (state.developerUnlocked && confidence.isFinite() && confidence in 0.0..1.0) NativeLine(text(R.string.copy_model_confidence), "${(confidence * 100).toInt()}%")
                        if (assessment.optBoolean("visual_candidate_accepted", false)) NativeLine(text(R.string.copy_review_status), text(R.string.copy_follow_up_started))
                        val message = record.optString("skip_reason").ifBlank { record.optString("state") }
                        if (message.isNotBlank()) NativeNote(screenLabel(message))
                        if ((record.opt("error") as? String)?.isNotBlank() == true) NativeNote(text(R.string.copy_observation_incomplete_check_the_pc), error = true)
                        val emitted = record.optJSONObject("ac_control")?.optBoolean("emitted", false) == true || record.optJSONObject("ir_emission")?.optBoolean("emitted", false) == true
                        if (emitted) NativeLine(text(R.string.copy_ir_transmission), text(R.string.copy_sent_device_response_unknown))
                    }
                }
            }
        } else {
            if (state.captures.isEmpty()) NativeBlock(text(R.string.copy_no_captures_yet)) {
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= 760.dp) 3 else if (maxWidth >= 420.dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    state.captures.take(12).chunked(columns).forEach { items ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items.forEach { item ->
                                Card(
                                    onClick = { state.openCapture(item) },
                                    modifier = Modifier.weight(1f),
                                    enabled = state.usable && !state.busy,
                                    shape = RoundedCornerShape(22.dp),
                                    colors = CardDefaults.cardColors(containerColor = ComposeTheme.colorScheme.surfaceContainerLow)
                                ) {
                                    Box(
                                        Modifier.fillMaxWidth().height(104.dp).background(ComposeTheme.colorScheme.secondaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        DreamIcon(DreamIconKind.Camera, Modifier.size(32.dp).clearAndSetSemantics { }, ComposeTheme.colorScheme.onSecondaryContainer)
                                    }
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(screenTime(item.optString("captured_at")), style = ComposeTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        NativeNote(screenLabel(item.optString("source")))
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                            NativeNote(text(R.string.copy_view_full_image))
                                            DreamIcon(DreamIconKind.ArrowUpRight, Modifier.size(16.dp), ComposeTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            repeat(columns - items.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NativeControl(state: NativeState) {
    val config = state.status.optJSONObject("config") ?: JSONObject()
    val automatic = state.status.optJSONObject("automatic_ai") ?: JSONObject()
    val signature = config.toString()
    var schedule by remember(signature) { mutableStateOf(config.optBoolean("schedule_enabled", false)) }
    var environment by remember(signature) { mutableStateOf(config.optBoolean("environment_recording_enabled", false)) }
    var interval by remember(signature) { mutableStateOf(config.optInt("capture_interval_seconds", 60).toString()) }
    val intervalNumber = interval.toIntOrNull()
    val enabled = state.usable && !state.busy
    val aiEnabled = automatic.optBoolean("enabled", false)
    val aiCompatible = config.optInt("capture_interval_seconds", 0) in 5..300
    val callsHour = automatic.optInt("calls_this_hour", 0)
    val maxHour = automatic.optInt("max_calls_per_hour", 20).coerceAtLeast(1)
    val callsSession = automatic.optInt("calls_this_session", 0)
    val maxSession = automatic.optInt("max_calls_per_session", 50).coerceAtLeast(1)

    NativePage {
        DreamSceneStrip(state.scene, height = 212.dp) {
            DreamMoonGuardianArt(
                scene = state.scene,
                animate = state.foreground,
                modifier = Modifier.align(Alignment.TopEnd).size(96.dp)
            )
            Text(text(R.string.copy_quiet_watch), Modifier.align(Alignment.BottomStart), style = ComposeTheme.typography.headlineMedium, color = Color.White)
        }
        NativeSectionCard(text(R.string.copy_cloud_ai_observation), tone = NativeCardTone.Primary) {
            NativeLine(text(R.string.copy_status), if (state.online) state.aiTitle else text(R.string.copy_unknown_pc_offline))
            DreamBreakdownPill(
                label = text(R.string.copy_requests_this_hour),
                valueText = if (state.online) text(R.string.copy_s_s, callsHour, maxHour) else text(R.string.copy_unavailable),
                fraction = if (state.online) callsHour.toFloat() / maxHour else 0.38f,
                violetTone = true,
                icon = DreamIconKind.Moon
            )
            DreamBreakdownPill(
                label = text(R.string.copy_requests_this_session),
                valueText = if (state.online) text(R.string.copy_s_s, callsSession, maxSession) else text(R.string.copy_unavailable),
                fraction = if (state.online) callsSession.toFloat() / maxSession else 0.46f,
                violetTone = false,
                icon = DreamIconKind.Pulse
            )
            if (state.online && !state.status.optBoolean("vlm_key_configured", false)) NativeLine(text(R.string.copy_model), text(R.string.copy_not_configured))
            if (state.online && !aiCompatible) NativeLine(text(R.string.copy_capture_interval), text(R.string.copy_use_5_300_s))
            Button(
                onClick = { state.dialog = "consent" },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(22.dp),
                enabled = enabled && !aiEnabled && aiCompatible && state.status.optBoolean("vlm_key_configured", false)
            ) { Text(text(R.string.copy_enable_cloud_ai)) }
            OutlinedButton(
                onClick = state::stopAI,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(22.dp),
                enabled = enabled && aiEnabled
            ) { Text(text(R.string.copy_stop_automatic_ai)) }
        }
        NativeSectionCard(text(R.string.copy_background_logging)) {
            NativeSwitch(text(R.string.copy_scheduled_captures), schedule, enabled) { schedule = it }
            NativeNumber(text(R.string.copy_capture_interval), interval, text(R.string.copy_s), enabled, range = 5.0..86400.0) { interval = it }
            NativeSwitch(text(R.string.copy_climate_logging), environment, enabled) { environment = it }
            Button(
                onClick = { intervalNumber?.let { state.saveConfig(schedule, it, environment) } },
                enabled = enabled && intervalNumber != null && intervalNumber in 5..86400,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text(text(R.string.copy_save_logging_settings)) }
        }
        NativeSectionCard(text(R.string.copy_ac_control), tone = NativeCardTone.Outlined) {
            NativeLine(text(R.string.copy_availability), text(R.string.copy_unavailable_247))
            NativeLine(text(R.string.copy_target_temperature), "— °C")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {}, enabled = false, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) { Text("−") }
                OutlinedButton(onClick = {}, enabled = false, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(2f)) { Text(text(R.string.copy_ac_power)) }
                OutlinedButton(onClick = {}, enabled = false, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) { Text("+") }
            }
            NativeLine(text(R.string.copy_pc_background_tasks), state.backgroundControlTitle)
        }
    }
}

@Composable
fun NativeSettings(state: NativeState) {
    val clipboard = LocalClipboardManager.current
    NativePage {
        NativeLanguageCard()
        NativeSleepSettings(state)
        NativeSectionCard(text(R.string.copy_freenove)) {
            NativeLine(text(R.string.copy_last_wi_fi), state.freenoveReceipt?.optString("ssid") ?: text(R.string.copy_not_configured))
            state.freenoveReceipt?.let { NativeLine(text(R.string.copy_last_ip_address), it.optString("ip_address")) }
            OutlinedButton(onClick = state::openSetup, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Text(text(R.string.copy_bluetooth_and_wi_fi)) }
        }
        NativeSectionCard(text(R.string.copy_ac_model)) {
            NativeLine(text(R.string.copy_ac_or_remote), state.selectedAcModel?.displayName ?: text(R.string.copy_none_selected))
            OutlinedButton(onClick = state::openAcSetup, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Text(text(R.string.copy_choose_model)) }
        }
        NativeCardPair(first = { cardModifier ->
        NativeSectionCard(text(R.string.copy_pc_server), cardModifier, tone = NativeCardTone.Primary) {
            NativeLine(text(R.string.copy_connection), state.connectionTitle)
            NativeLine(text(R.string.copy_server_address), state.profile.optString("base_url", "—"))
            NativeLine(text(R.string.copy_paired_device), state.profile.optString("device_name", "—"))
            NativeLine(text(R.string.copy_pairing_expires), text(R.string.copy_managed_on_the_pc))
            OutlinedButton(onClick = state::requestPermission, enabled = !state.busy, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) { Text(text(R.string.copy_local_network_access)) }
            TextButton(onClick = { state.dialog = "forget" }, enabled = state.paired && !state.busy) { Text(text(R.string.copy_unpair_this_phone), color = ComposeTheme.colorScheme.error) }
        }
        }, second = { cardModifier ->
        NativeSectionCard(text(R.string.copy_connect_pc), cardModifier, tone = NativeCardTone.Outlined) {
            OutlinedTextField(
                value = state.pairingInput,
                onValueChange = { state.pairingInput = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                label = { Text(text(R.string.copy_pc_pairing_code)) },
                placeholder = { Text("DS1:…") },
                minLines = 3,
                maxLines = 6,
                enabled = !state.busy
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { clipboard.getText()?.text?.let { state.pairingInput = it } }, enabled = !state.busy, shape = RoundedCornerShape(20.dp)) { Text(text(R.string.copy_paste)) }
                Button(onClick = { state.pair(state.pairingInput.trim()) }, enabled = !state.busy && state.pairingInput.isNotBlank(), shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) { Text(text(R.string.copy_verify_and_pair)) }
            }
        }
        })
        NativePolicySettings(state)
        NativeSectionCard(text(R.string.copy_help_and_support), tone = NativeCardTone.Outlined) {
            Column {
                listOf("help" to text(R.string.copy_connection_help), "privacy" to text(R.string.copy_privacy_and_data), "about" to text(R.string.copy_about_dreamshield)).forEach { (dialog, title) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { state.dialog = dialog },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(title, Modifier.weight(1f), style = ComposeTheme.typography.bodyLarge)
                        DreamIcon(DreamIconKind.ChevronRight, Modifier.size(20.dp), ComposeTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = ComposeTheme.colorScheme.outlineVariant)
            Text(text(R.string.copy_more), style = ComposeTheme.typography.titleSmall)
            NativeLine(text(R.string.copy_bedtime_notifications), if (!state.sleep.repo.snapshot.plan.reminderEnabled) text(R.string.copy_enable_in_sleep_schedule) else if(state.sleep.scheduleStatus.notificationsGranted) text(R.string.copy_enabled) else text(R.string.copy_permission_required))
            NativeLine(text(R.string.copy_firmware_updates), text(R.string.copy_coming_later))
        }
    }
}

@Composable
internal fun NativeLanguageCard() {
    val activity = androidx.activity.compose.LocalActivity.current ?: return
    val choices = listOf(AppLanguage.SYSTEM, AppLanguage.CHINESE, AppLanguage.ENGLISH)
    var selected by remember { mutableIntStateOf(choices.indexOf(AppLanguage.choice(activity)).coerceAtLeast(0)) }
    NativeSectionCard(text(R.string.app_language)) {
        DreamSegmentedTrack(
            options = listOf(text(R.string.app_language_system), "简体中文", "English"),
            selectedIndex = selected,
            onSelect = { index ->
                AppLanguage.select(activity, choices[index])
                selected = index
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun NativePolicySettings(state: NativeState) {
    val bundle = state.policy
    val signature = bundle?.toString()
    var selected by remember(signature) { mutableStateOf(bundle?.optString("preset", "") ?: "") }
    val values = remember(signature) { mutableStateMapOf<String, String>().apply {
        policyFields.forEach { field -> put(field.key, bundle?.optJSONObject("policy")?.optString(field.key).orEmpty()) }
    } }
    var poll by remember(signature) { mutableStateOf(bundle?.optInt("environment_interval_seconds", 15)?.toString() ?: "15") }
    var sample by remember(signature) { mutableStateOf(bundle?.optInt("sensor_sample_target_seconds", 15)?.toString() ?: "15") }
    var customDraft by remember(signature) { mutableStateOf<Triple<Map<String, String>, String, String>?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf("") }
    val available = state.usable && !state.busy && bundle != null
    val names = listOf("light" to text(R.string.copy_gentle), "balanced" to text(R.string.copy_balanced), "efficient" to text(R.string.copy_efficient), "custom" to text(R.string.copy_custom))
    fun fill(source: JSONObject) {
        policyFields.forEach { field -> values[field.key] = source.optJSONObject("policy")?.optString(field.key).orEmpty() }
        poll = source.optInt("environment_interval_seconds", 15).toString()
        sample = source.optInt("sensor_sample_target_seconds", 15).toString()
    }
    fun choose(mode: String) {
        if (!available) return
        if (selected == "custom") customDraft = Triple(values.toMap(), poll, sample)
        selected = mode; localError = ""
        if (mode == "custom") {
            customDraft?.let { draft -> values.putAll(draft.first); poll = draft.second; sample = draft.third }
            expanded = true
        } else bundle?.optJSONObject("presets")?.optJSONObject(mode)?.let(::fill)
    }
    NativeSectionCard(text(R.string.copy_policy_mode)) {
        val currentIdx = names.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        DreamSegmentedTrack(
            options = names.map { it.second },
            selectedIndex = currentIdx,
            onSelect = { idx -> choose(names[idx].first) },
            enabled = available,
            modifier = Modifier.fillMaxWidth()
        )
        names.forEach { (key, title) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected == key, enabled = available, role = Role.RadioButton) { choose(key) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == key, onClick = null, enabled = available)
                Column(Modifier.weight(1f)) {
                    Text(title, style = ComposeTheme.typography.titleSmall)
                }
            }
        }
        TextButton(onClick = { expanded = !expanded }, enabled = bundle != null) { Text(if (expanded) text(R.string.copy_hide_parameters) else text(R.string.copy_show_parameters)) }
        val duration = if (ValueAnimator.areAnimatorsEnabled() && state.foreground) 260 else 0
        AnimatedVisibility(expanded,
            enter = expandVertically(tween(duration, easing = FastOutSlowInEasing)) + fadeIn(tween(duration)),
            exit = shrinkVertically(tween(duration, easing = FastOutSlowInEasing)) + fadeOut(tween(duration))) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            policyFields.forEach { field ->
                NativeNumber(field.label, values[field.key].orEmpty(), field.unit, available, readOnly = selected != "custom",
                    range = field.minimum..field.maximum, integer = field.integer) { values[field.key] = it; localError = "" }
            }
            NativeNumber(text(R.string.copy_pc_climate_polling), poll, text(R.string.copy_s), available, readOnly = selected != "custom", range = 5.0..30.0) { poll = it; localError = "" }
            NativeNumber(text(R.string.copy_target_sht31_interval), sample, text(R.string.copy_s), available, readOnly = selected != "custom", range = 5.0..30.0) { sample = it; localError = "" }
        }
        }
        if (localError.isNotBlank()) NativeNote(localError, error = true)
        Button(onClick = {
            try { state.savePolicy(policyPayload(selected, values, poll, sample)); localError = "" }
            catch (error: IllegalArgumentException) { localError = error.message ?: text(R.string.copy_check_the_values) }
        }, enabled = available, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text(R.string.copy_save_policy)) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = state::loadPolicy, enabled = state.usable && !state.busy, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) { Text(text(R.string.copy_reload)) }
            TextButton(onClick = { choose("balanced") }, enabled = available, modifier = Modifier.weight(1f)) { Text(text(R.string.copy_use_balanced_defaults)) }
        }
        NativeNote(state.policyFeedback)
        HorizontalDivider()
        NativeLine(text(R.string.copy_device_target_interval), if (bundle == null) text(R.string.copy_unavailable) else text(R.string.copy_s_s_293, sample))
        OutlinedButton(onClick = {
            val interval = sample.toIntOrNull()
            if (interval == null || interval !in 5..30) localError = text(R.string.copy_use_a_whole_sht31_interval_of_5_30_s)
            else state.applySensor(interval)
        }, enabled = available, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text(R.string.copy_apply_interval_to_device)) }
        NativeNote(state.sensorFeedback)
    }
}

private fun policyPayload(selected: String, values: Map<String, String>, poll: String, sample: String): JSONObject {
    val policy = JSONObject()
    policyFields.forEach { field ->
        val number = values[field.key]?.toDoubleOrNull()
        require(number != null && number.isFinite() && number in field.minimum..field.maximum && (!field.integer || number % 1.0 == 0.0)) {
            text(R.string.copy_s_must_be_s_s_s_s, field.label, field.minimum.screenNumber(), field.maximum.screenNumber(), field.unit, if (field.integer) text(R.string.copy_whole_number_297) else "")
        }
        policy.put(field.key, if (field.integer) number.toInt() else number)
    }
    require(policy.getDouble("temperature_upper_c") - policy.getDouble("temperature_lower_c") >= 2.0) { text(R.string.copy_set_room_temperature_thresholds_at_least_2_c_apart) }
    require(policy.getInt("min_setpoint_c") <= policy.getInt("max_setpoint_c")) { text(R.string.copy_the_minimum_setpoint_must_be_at_or_below_the_maxim) }
    val pollNumber = poll.toIntOrNull(); val sampleNumber = sample.toIntOrNull()
    require(pollNumber != null && pollNumber in 5..30) { text(R.string.copy_use_a_whole_polling_interval_of_5_30_s) }
    require(sampleNumber != null && sampleNumber in 5..30) { text(R.string.copy_use_a_whole_sht31_interval_of_5_30_s_301) }
    return JSONObject().put("preset", selected).put("policy", policy)
        .put("environment_interval_seconds", pollNumber).put("sensor_sample_target_seconds", sampleNumber)
}

@Composable
fun NativeDialogs(state: NativeState) {
    val automatic = state.status.optJSONObject("automatic_ai") ?: JSONObject()
    var interval by remember { mutableStateOf(automatic.optInt("min_interval_seconds", 60).toString()) }
    var hourly by remember { mutableStateOf(automatic.optInt("max_calls_per_hour", 20).toString()) }
    var session by remember { mutableStateOf(automatic.optInt("max_calls_per_session", 50).toString()) }
    var consent by remember(state.dialog) { mutableStateOf(false) }
    LaunchedEffect(state.developerUnlocked, state.dialog) {
        if (!state.developerUnlocked) {
            interval = automatic.optInt("min_interval_seconds", 60).toString()
            hourly = automatic.optInt("max_calls_per_hour", 20).toString()
            session = automatic.optInt("max_calls_per_session", 50).toString()
        }
    }
    val dismiss = {
        consent = false
        if (state.dialog == "developer") state.lockDeveloper()
        state.dismissDialog()
    }
    when (state.dialog) {
        "consent" -> {
            val parsedInterval = interval.toIntOrNull()
            val parsedHourly = hourly.toIntOrNull()
            val parsedSession = session.toIntOrNull()
            val valid = parsedInterval != null && parsedInterval in 60..3600 && parsedHourly != null && parsedHourly in 2..120 && parsedSession != null && parsedSession in 2..500
            AlertDialog(onDismissRequest = dismiss, title = { Text(text(R.string.copy_confirm_cloud_ai)) },
                text = {
                    Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(text(R.string.copy_the_pc_will_send_scheduled_images_to_its_cloud_ai))
                        NativeNote(text(R.string.copy_this_enables_pc_captures_and_climate_logging_the_f))
                        NativeNote(text(R.string.copy_check_pc_rules_that_may_send_ir_signals_based_on_a))
                        NativeLine(text(R.string.copy_minimum_ai_interval), text(R.string.copy_s_s_293, interval))
                        NativeLine(text(R.string.copy_hourly_request_limit), text(R.string.copy_s_308, hourly))
                        NativeLine(text(R.string.copy_session_request_limit), text(R.string.copy_s_308, session))
                        Row(Modifier.fillMaxWidth().toggleable(consent, enabled = !state.busy, role = Role.Checkbox, onValueChange = { consent = it }), verticalAlignment = Alignment.Top) {
                            Checkbox(checked = consent, onCheckedChange = null, enabled = !state.busy)
                            Text(text(R.string.copy_i_consent_to_uploads_understand_the_privacy_and_ap), Modifier.weight(1f).padding(top = 12.dp), style = ComposeTheme.typography.bodyMedium)
                        }
                        if (!valid) NativeNote(text(R.string.copy_check_the_limits_in_developer_tools), error = true)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (consent && valid) state.enableAI(true, parsedInterval!!, parsedHourly!!, parsedSession!!)
                    }, enabled = consent && valid && state.usable && !state.busy && state.foreground) { Text(text(R.string.copy_agree_and_enable)) }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text(text(R.string.copy_not_now)) } })
        }
        "forget" -> AlertDialog(onDismissRequest = dismiss, title = { Text(text(R.string.copy_unpair_this_phone_314)) }, text = {
            Text(text(R.string.copy_this_clears_the_phone_s_credentials_and_preview_pc))
        }, confirmButton = { TextButton(onClick = { state.forget() }, enabled = !state.busy) { Text(text(R.string.copy_unpair), color = ComposeTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = dismiss) { Text(text(R.string.copy_keep_pairing)) } })
        "help" -> AlertDialog(onDismissRequest = dismiss, title = { Text(text(R.string.copy_connection_help)) },
            text = { NativeParagraphs(helpParagraphs) }, confirmButton = { TextButton(onClick = dismiss) { Text(text(R.string.copy_ok)) } },
            dismissButton = { TextButton(onClick = { state.dialog = "welcome" }) { Text(text(R.string.copy_quick_guide)) } })
        "privacy" -> AlertDialog(onDismissRequest = dismiss, title = { Text(text(R.string.copy_privacy_and_data)) },
            text = { NativeParagraphs(privacyParagraphs) }, confirmButton = { TextButton(onClick = dismiss) { Text(text(R.string.copy_ok)) } })
        "welcome" -> AlertDialog(onDismissRequest = dismiss, title = { Text(text(R.string.copy_welcome_to_dreamshield)) },
            text = { NativeParagraphs(listOf(
                text(R.string.copy_connect_320) to text(R.string.copy_join_the_pc_s_wi_fi_and_use_its_pairing_code_to_se),
                text(R.string.copy_observe) to text(R.string.copy_open_the_camera_preview_when_needed_pc_jobs_keep_r),
                text(R.string.copy_choose_what_to_share) to text(R.string.copy_cloud_ai_starts_only_after_you_review_uploads_cons),
                text(R.string.copy_what_dreamshield_shows) to text(R.string.copy_use_it_to_review_your_room_and_sleep_journal_use_t)
            )) },
            confirmButton = { Button(onClick = { state.dismissDialog(); state.showPage("settings") }) { Text(text(R.string.copy_get_started)) } },
            dismissButton = { TextButton(onClick = { state.dialog = "privacy" }) { Text(text(R.string.copy_privacy)) } })
        "about" -> AlertDialog(onDismissRequest = dismiss, title = { Text(text(R.string.copy_about_dreamshield)) }, text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text(R.string.copy_dreamshield_330), style = ComposeTheme.typography.headlineSmall)
                NativeNote(text(R.string.copy_sleep_journal_and_room_climate))
                NativeLine(text(R.string.copy_device), text(R.string.copy_dreamshield_rs081_dev_prototype))
                TextButton(onClick = state::tapVersion, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(text(R.string.copy_app_version), Modifier.weight(1f)); Text(BuildConfig.VERSION_NAME)
                }
                if (state.developerUnlocked) OutlinedButton(onClick = { state.dialog = "developer" }, modifier = Modifier.fillMaxWidth()) { Text(text(R.string.copy_developer_tools)) }
            }
        }, confirmButton = { TextButton(onClick = dismiss) { Text(text(R.string.copy_done)) } })
        "developer" -> if (state.developerUnlocked) {
            AlertDialog(onDismissRequest = dismiss, title = { Text(text(R.string.copy_developer_tools_338)) }, text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    NativeNote(text(R.string.copy_tools_lock_when_you_leave_the_app_pc_jobs_keep_run))
                    NativeLine(text(R.string.copy_mode), "Wi-Fi - DebuggingServer")
                    NativeLine(text(R.string.copy_device), "DreamShield RS081-dev")
                    NativeLine(text(R.string.copy_pc_status), state.connectionTitle)
                    NativeLine(text(R.string.copy_pc_background_tasks), state.backgroundControlTitle)
                    HorizontalDivider()
                    Text(text(R.string.copy_advanced_observation_settings), style = ComposeTheme.typography.titleMedium)
                    NativeNote(text(R.string.copy_these_settings_apply_when_you_next_enable_cloud_ai))
                    NativeNumber(text(R.string.copy_minimum_ai_interval), interval, text(R.string.copy_s), !state.busy, range = 60.0..3600.0) { interval = it }
                    NativeNumber(text(R.string.copy_hourly_request_limit), hourly, text(R.string.copy_requests), !state.busy, range = 2.0..120.0) { hourly = it }
                    NativeNumber(text(R.string.copy_session_request_limit), session, text(R.string.copy_requests), !state.busy, range = 2.0..500.0) { session = it }
                    TextButton(onClick = { state.dialog = null; state.showPage("control") }) { Text(text(R.string.copy_open_observe)) }
                    HorizontalDivider()
                    NativeNote(text(R.string.copy_the_summary_uses_current_status_and_excludes_crede))
                    NativeLine(text(R.string.copy_app_version), BuildConfig.VERSION_NAME)
                    NativeLine(text(R.string.copy_this_phone), if (state.paired) text(R.string.copy_paired) else text(R.string.copy_unpaired))
                    NativeLine(text(R.string.copy_current_server), if (state.online) text(R.string.copy_online) else text(R.string.copy_offline_or_unknown))
                    NativeLine(text(R.string.copy_ai_status), state.aiTitle)
                }
            }, confirmButton = { Button(onClick = { state.lockDeveloper(); state.dismissDialog() }) { Text(text(R.string.copy_lock_tools)) } })
        }
    }
    val bitmap = state.selectedImage
    if (bitmap != null && state.dialog == null) {
        val clear = { state.selectedImage = null; state.selectedImagePath = null; state.selectedImageCaption = "" }
        Dialog(onDismissRequest = clear, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(20.dp).widthIn(max = 840.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = ComposeTheme.colorScheme.surface) {
                Column(Modifier.heightIn(max = 740.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text(R.string.copy_captures), Modifier.weight(1f), style = ComposeTheme.typography.titleLarge)
                        TextButton(onClick = clear) { Text(text(R.string.copy_close)) }
                    }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = text(R.string.copy_selected_capture),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
                    )
                    NativeNote(state.selectedImageCaption)
                    Button(
                        onClick = state::saveSelectedImage,
                        enabled = state.usable && !state.busy && state.selectedImagePath != null,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(text(R.string.copy_save_to_gallery)) }
                    NativeNote(text(R.string.copy_save_a_copy_in_pictures_dreamshield))
                }
            }
        }
    }
}
