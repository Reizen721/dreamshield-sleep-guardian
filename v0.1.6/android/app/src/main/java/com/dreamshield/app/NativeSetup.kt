package com.dreamshield.app

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme as ComposeTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

/** A functional first-run flow using the existing scene, Material roles and card shapes. */
@Composable
internal fun NativeSetup(state: NativeState) {
    val scheme = ComposeTheme.colorScheme
    val motion = ValueAnimator.areAnimatorsEnabled() && state.foreground
    Box(Modifier.fillMaxSize().background(scheme.background)) {
        Image(painterResource(dreamSceneArtwork(state.scene)), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .58f), .24f to scheme.background.copy(alpha = .92f),
            .60f to scheme.background, 1f to scheme.background)))
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
            contentAlignment = Alignment.TopCenter) {
            AnimatedContent(targetState = state.setupStep,
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                transitionSpec = { (fadeIn(tween(if (motion) 220 else 0)) togetherWith
                    fadeOut(tween(if (motion) 120 else 0))).using(null) }, label = "initial setup") { step ->
                SetupStage(state, step, active = step == state.setupStep)
            }
        }
    }
}

@Composable
private fun SetupStage(state: NativeState, step: Int, active: Boolean) {
    val scheme = ComposeTheme.colorScheme
    var brand by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    LaunchedEffect(brand) { list.scrollToItem(0) }
    val chosen = state.selectedAcModel
    val isBle = state.setupMode == "ble"
    val stageNumber = if (step == 2) 2 else 1
    val modelResults = remember(brand, query, state.acCatalog) { state.acCatalog.search(query, brand) }
    val showBrands = brand.isBlank() && query.isBlank()
    Column(Modifier.fillMaxSize().then(if (step == 1) Modifier.selectableGroup() else Modifier)) {
        LazyColumn(state = list, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "heading") {
                Column(Modifier.fillMaxWidth().background(scheme.surface, RoundedCornerShape(28.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(when (step) { 0 -> text(R.string.copy_connect_freenove); 1 -> text(R.string.copy_choose_ac_model); else -> text(R.string.copy_review_setup) },
                        style = ComposeTheme.typography.headlineMedium, color = scheme.onSurface,
                        modifier = Modifier.semantics { heading() })
                    LinearProgressIndicator(progress = { stageNumber / 2f }, Modifier.fillMaxWidth(),
                        color = scheme.primary, trackColor = scheme.surfaceContainerHighest)
                    Text("$stageNumber / 2 · ${if (step == 2) text(R.string.copy_review_and_save) else if (isBle) text(R.string.copy_bluetooth_and_wi_fi_363) else text(R.string.copy_ac_or_remote_model)}",
                        style = ComposeTheme.typography.labelLarge, color = scheme.onSurface)
                }
            }
            if (!state.setupProfileReady) item(key = "loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(text(R.string.copy_loading_saved_connection), style = ComposeTheme.typography.bodyMedium)
            }
            if (state.message.isNotBlank()) item(key = "feedback") {
                Surface(shape = RoundedCornerShape(20.dp), color = scheme.secondaryContainer) {
                    Text(state.message, Modifier.padding(16.dp), style = ComposeTheme.typography.bodyMedium,
                        color = scheme.onSecondaryContainer)
                }
            }
            when (step) {
                0 -> item(key = "connection") { SetupConnection(state, active) }
                1 -> {
                    item(key = "model-search") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(text(R.string.copy_check_the_label_on_your_ac_or_remote), style = ComposeTheme.typography.bodyMedium)
                            OutlinedTextField(value = query, onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                                label = { Text(text(R.string.copy_search_brand_or_model)) }, placeholder = { Text(text(R.string.copy_e_g_gree_yapofb14_arc)) },
                                singleLine = true, enabled = active)
                            if (brand.isNotBlank()) TextButton(onClick = { brand = ""; query = "" }, enabled = active) {
                                Text(text(R.string.copy_all_brands))
                            }
                            Text(text(R.string.copy_irremoteesp8266_s_s_models, state.acCatalog.libraryVersion, state.acCatalog.models.size),
                                style = ComposeTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            if (chosen != null) Text(text(R.string.copy_selected_s, chosen.displayName),
                                style = ComposeTheme.typography.titleSmall, color = scheme.primary)
                        }
                    }
                    if (showBrands) {
                        item(key = "yapofb14") {
                            SetupModelRow(state.acCatalog.find("dreamshield_yapofb14_basic128")!!,
                                chosen?.id, active && state.setupCanContinue) {
                                state.selectAcModel(it); keyboard?.hide()
                            }
                        }
                        items(state.acCatalog.brands, key = { "brand_${it.first}" }) { (key, label) ->
                            Card(onClick = { brand = key; query = ""; keyboard?.hide() },
                                enabled = active, shape = RoundedCornerShape(28.dp),
                                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
                                Row(Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(label, style = ComposeTheme.typography.titleMedium)
                                        if (label != key) Text(key, style = ComposeTheme.typography.bodySmall,
                                            color = scheme.onSurfaceVariant)
                                    }
                                    DreamIcon(DreamIconKind.ChevronRight, Modifier.size(20.dp))
                                }
                            }
                        }
                    } else {
                        if (modelResults.isEmpty()) item(key = "no-models") {
                            Text(text(R.string.copy_no_match_try_a_brand_or_shorter_model_name), style = ComposeTheme.typography.bodyMedium)
                        }
                        items(modelResults, key = { it.id }) { model ->
                            SetupModelRow(model, chosen?.id, active && state.setupCanContinue) {
                                state.selectAcModel(it); keyboard?.hide()
                            }
                        }
                    }
                    item(key = "model-scope") {
                        Text(text(R.string.copy_choose_a_model_to_save_it_on_this_phone_device_sup),
                            style = ComposeTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                else -> item(key = "summary") {
                    if (isBle) DreamPaperCard(text(R.string.copy_freenove_network)) {
                        SetupLine("Freenove", state.freenoveReceipt?.optString("device_id") ?: "—")
                        SetupLine("Wi-Fi", state.freenoveReceipt?.optString("ssid") ?: "—")
                        SetupLine(text(R.string.copy_board_ip_address), state.freenoveReceipt?.optString("ip_address") ?: "—")
                        Text(text(R.string.copy_freenove_confirmed_these_settings_the_camera_resum),
                            style = ComposeTheme.typography.bodyMedium)
                        Text(text(R.string.copy_connect_the_pc_later_in_settings_for_previews_and),
                            style = ComposeTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    } else
                    DreamPaperCard(text(R.string.copy_review_your_setup)) {
                        SetupLine(text(R.string.copy_ac_or_remote), chosen?.displayName ?: text(R.string.copy_none_selected))
                        SetupLine(text(R.string.copy_protocol), chosen?.protocol ?: "—")
                        Text(if (chosen?.projectProfile == true)
                            text(R.string.copy_yapofb14_uses_dreamshield_s_128_bit_profile) else text(R.string.copy_included_in_the_ir_library),
                            style = ComposeTheme.typography.bodyMedium)
                        Text(text(R.string.copy_the_model_is_saved_on_this_phone_for_future_ir_set),
                            style = ComposeTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
        Surface(color = scheme.surfaceContainerLow, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { keyboard?.hide(); if (step != 2) state.deferSetup() else state.previousSetupStep() },
                    enabled = active && !state.busy && !state.ble.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (step != 2) text(R.string.copy_set_up_later) else text(R.string.copy_back))
                }
                Button(onClick = {
                    keyboard?.hide()
                    if (step == 2) state.completeSetup() else state.nextSetupStep()
                }, enabled = active && state.setupCanContinue && (isBle || chosen != null),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(22.dp)) {
                    Text(if (step == 2) if (isBle) text(R.string.copy_open_dreamshield) else text(R.string.copy_save_model)
                        else if (isBle && state.freenoveReceipt != null) text(R.string.copy_use_saved_setup) else text(R.string.copy_continue))
                }
            }
        }
    }
}

@Composable
private fun SetupConnection(state: NativeState, active: Boolean) {
    val ble = state.ble
    val scheme = ComposeTheme.colorScheme
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(ble.inputEpoch, state.foreground) { password = "" }
    val enabled = active && state.foreground && !ble.busy
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DreamPaperCard(text(R.string.copy_connect_freenove)) {
            Text(text(R.string.copy_connect_over_bluetooth_to_set_up_wi_fi), style = ComposeTheme.typography.bodyMedium)
            Text(text(R.string.copy_firmware_0_9_3_enables_bluetooth_on_startup_and_re),
                style = ComposeTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            OutlinedButton(onClick = { ble.startScan() }, enabled = enabled && !ble.connected && !ble.scanning,
                modifier = Modifier.fillMaxWidth()) { Text(if (ble.scanning) text(R.string.copy_scanning) else text(R.string.copy_find_freenove)) }
            ble.devices.forEach { device ->
                Surface(shape = RoundedCornerShape(20.dp), color = if (ble.selectedAddress == device.address)
                    scheme.primaryContainer else scheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().selectable(selected = ble.selectedAddress == device.address,
                        enabled = enabled && !ble.connected, role = Role.RadioButton,
                        onClick = { ble.selectedAddress = device.address }).padding(12.dp).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = ble.selectedAddress == device.address, onClick = null,
                            enabled = enabled && !ble.connected)
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = ComposeTheme.typography.titleSmall)
                            Text(text(R.string.copy_signal_s_dbm, device.rssi), style = ComposeTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (!ble.connected) {
                Button(onClick = ble::connect, enabled = enabled && ble.selectedAddress.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text(R.string.copy_connect_board)) }
            } else SetupLine(text(R.string.copy_board_connected), ble.deviceId)
        }
        if (ble.connected && ble.savedNetworkName.isNotEmpty()) DreamPaperCard(text(R.string.copy_wi_fi_restored)) {
            SetupLine(text(R.string.copy_board_online), ble.savedNetworkName)
            Button(onClick = ble::useSavedNetwork, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text(R.string.copy_use_saved_wi_fi)) }
        }
        if (ble.connected) DreamPaperCard(if (ble.savedNetworkName.isEmpty()) text(R.string.copy_join_wi_fi) else text(R.string.copy_change_wi_fi)) {
            OutlinedButton(onClick = ble::scanWifi, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(text(R.string.copy_scan_for_wi_fi)) }
            ble.networks.forEach { network ->
                val supported = network.auth in setOf(0, 2, 3, 4, 6, 7)
                TextButton(onClick = { ssid = network.ssid; open = network.auth == 0; password = "" },
                    enabled = enabled && supported, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(network.ssid, Modifier.weight(1f))
                    Text(if (!supported) text(R.string.copy_unsupported) else if (network.auth == 0) text(R.string.copy_open) else text(R.string.copy_secured))
                }
            }
            OutlinedTextField(value = ssid, onValueChange = { ssid = it }, label = { Text(text(R.string.copy_2_4_ghz_wi_fi_name)) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), singleLine = true, enabled = enabled)
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(text(R.string.copy_wi_fi_password)) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), singleLine = true,
                visualTransformation = PasswordVisualTransformation(), enabled = enabled && !open,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = open, onCheckedChange = { open = it; password = "" }, enabled = enabled)
                Text(text(R.string.copy_this_network_is_open), style = ComposeTheme.typography.bodyMedium)
            }
            Button(onClick = { ble.provision(ssid, if (open) "" else password, open) }, enabled = enabled && ssid.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text(R.string.copy_send_and_verify)) }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = scheme.secondaryContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ble.busy || ble.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(ble.feedback, style = ComposeTheme.typography.bodyMedium, color = scheme.onSecondaryContainer)
                if (ble.busy || ble.connected || ble.scanning) TextButton(onClick = { ble.cancel() },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(text(R.string.copy_disconnect_bluetooth)) }
            }
        }
    }
}

@Composable
private fun SetupModelRow(model: AcModelChoice, selectedId: String?, enabled: Boolean, select: (String) -> Unit) {
    val selected = model.id == selectedId
    val scheme = ComposeTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp),
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface) {
        Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
            onClick = { select(model.id) }).padding(18.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(model.model, style = ComposeTheme.typography.titleMedium)
                Text("${model.brandLabel} · ${model.supportLabel}", style = ComposeTheme.typography.bodySmall)
                Text(model.protocol, style = ComposeTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SetupLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = ComposeTheme.typography.labelLarge, color = ComposeTheme.colorScheme.onSurfaceVariant)
        Text(value, style = ComposeTheme.typography.bodyLarge)
    }
}

