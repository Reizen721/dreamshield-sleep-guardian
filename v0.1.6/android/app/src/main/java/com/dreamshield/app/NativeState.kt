package com.dreamshield.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.function.Consumer
import kotlin.math.max

/** Native presentation state. The Activity alone owns credentials, TLS, permissions and photo storage. */
class NativeState(private val activity: MainActivity) {
    val sleep = SleepCoordinator(activity)
    private var sleepHistoryBusy = false
    private var sleepHistoryAt = 0L
    fun sleepSourceId(): String? {
        val binding=profile.optString("server_binding")
        val board=status.optJSONObject("config")?.optString("device_base_url").orEmpty()
        return if(paired && binding.isNotBlank() && board.isNotBlank()) binding+"|"+board else null
    }
    fun onSleepAction(value: String) = onMain {
        dialog=null
        if(setupVisible) deferSetup()
        sleep.reload()
        sleep.action(value, scene, sleepSourceId())
        showPage("home")
    }
    fun syncSleepHistory(force: Boolean = true) = onMain {
        if (!usable || sleepHistoryBusy || (!force && SystemClock.elapsedRealtime() - sleepHistoryAt < 60_000L)) return@onMain
        val source = sleepSourceId() ?: return@onMain
        sleepHistoryBusy = true
        api("/api/environment/history?limit=1000", onFailure = {
            sleepHistoryBusy = false
            sleep.historyMessage = text(R.string.copy_climate_sync_unavailable_the_journal_is_on_this_ph)
        }) { data ->
            sleepHistoryBusy = false
            sleepHistoryAt = SystemClock.elapsedRealtime()
            if (source != sleepSourceId()) return@api
            if(data.optString("device_base_url").trimEnd('/') != source.substringAfter('|').trimEnd('/')) {
                sleep.historyMessage=text(R.string.copy_pc_source_changed_reconnect_to_sync)
                return@api
            }
            val samples = objects(data.optJSONArray("items")).sortedBy { it.optString("received_at") }
                .distinctBy { listOf(it.optString("device_id"),it.optString("boot_id"),it.optString("sensor_id"),it.optLong("sample_sequence")) }.mapNotNull { item ->
                runCatching {
                    if (item.opt("valid") != true || item.optString("status") != "fresh") return@runCatching null
                    RoomSample(source, Instant.parse(item.getString("measured_at_estimated")).toEpochMilli(),
                        item.getDouble("ambient_temperature_c"), item.getDouble("relative_humidity_percent"))
                }.getOrNull()
            }
            sleep.act { sleep.repo.ingestSamples(samples) }
            sleep.historyMessage = text(R.string.copy_available_climate_samples_synced)
            api("/api/automatic-ai/history", onFailure = { sleep.historyMessage = text(R.string.copy_climate_synced_observations_are_unavailable) }) { result ->
                if (source != sleepSourceId()) return@api
                val events = objects(result.optJSONArray("items")).mapNotNull { record ->
                    runCatching {
                        val board=record.optJSONObject("after")?.optString("device_base_url").orEmpty()
                        if(board.trimEnd('/') != source.substringAfter('|').trimEnd('/')) return@runCatching null
                        val raw = record.optJSONObject("after")?.optString("captured_at").orEmpty()
                            .ifBlank { record.optString("created_at").ifBlank { record.optString("completed_at") } }
                        val assessment = record.optJSONObject("result")?.optJSONObject("assessment")
                        val event = assessment?.optString("blanket_event_state").orEmpty()
                        val text = when(event) { "covered" -> text(R.string.copy_blanket_on); "uncovered" -> text(R.string.copy_blanket_off); "blanket_displaced" -> text(R.string.copy_blanket_shifted); else -> text(R.string.copy_ai_observation_589) }
                        NightEvent(raw + "|" + record.toString().hashCode(), Instant.parse(raw).toEpochMilli(), text, source, "observation")
                    }.getOrNull()
                }
                sleep.act { sleep.repo.ingestEvents(events) }
            }
        }
    }
    var page by mutableStateOf("home")
    var scene by mutableStateOf(0)
    var coverShowing by mutableStateOf(true)
    var coverProgress by mutableStateOf(0f)
    var launchVisible by mutableStateOf(true)
    var launchRevealStarted by mutableStateOf(false)
    var launchOriginX by mutableStateOf(0f)
    var launchOriginY by mutableStateOf(0f)
    var launchStartRadius by mutableStateOf(0f)
    var profile by mutableStateOf(JSONObject())
    var status by mutableStateOf(JSONObject())
    private var environment by mutableStateOf<PcRoomSample?>(null)
    var previewBitmap by mutableStateOf<Bitmap?>(null)
    var previewImageId by mutableStateOf("")
    var previewActive by mutableStateOf(false)
    var previewTime by mutableStateOf(text(R.string.copy_preview_unavailable))
    var captures by mutableStateOf<List<JSONObject>>(emptyList())
    var aiEvents by mutableStateOf<List<JSONObject>>(emptyList())
    var selectedImage by mutableStateOf<Bitmap?>(null)
    var selectedImagePath by mutableStateOf<String?>(null)
    var selectedImageCaption by mutableStateOf("")
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var policy by mutableStateOf<JSONObject?>(null)
    var policyFeedback by mutableStateOf(text(R.string.copy_connect_the_pc_to_read_policy))
    var sensorFeedback by mutableStateOf(text(R.string.copy_apply_the_target_interval_on_the_device))
    var dialog by mutableStateOf<String?>(null)
    var developerUnlocked by mutableStateOf(false)
    var pairingInput by mutableStateOf("")
    var online by mutableStateOf(false)
    var foreground by mutableStateOf(true)
    var setupVisible by mutableStateOf(true)
    var setupStep by mutableStateOf(0)
    var setupMode by mutableStateOf("ble")
    var freenoveReceipt by mutableStateOf<JSONObject?>(null)
        private set
    var setupProfileReady by mutableStateOf(false)
    var setupModelId by mutableStateOf("")
    internal val acCatalog = AcModelCatalog(activity)

    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private val preferences = activity.getSharedPreferences("dreamshield_native_ui_v1", Context.MODE_PRIVATE)
    private var closed = false
    private var started = false
    private var uiEpoch = 0L
    private var imageEpoch = 0L
    private var profileBusy = false
    private var refreshBusy = false
    private var historyBusy = false
    private var policyBusy = false
    private var previewBusy = false
    private var pairingBusy = false
    private var authorizationBlocked by mutableStateOf(false)
    private var roomBusy = false
    private var sampleClock by mutableStateOf(SystemClock.elapsedRealtime())
    private var sensorMessage by mutableStateOf(text(R.string.copy_waiting_for_climate_data))
    private var previewPath: String? = null
    private var tapStarted = 0L
    private var tapCount = 0
    private var queuedPairingInput: String? = null
    private var setupDeferred = false
    private var setupExplicit = false
    internal val ble = FreenoveBleSetup(activity) { receipt ->
        freenoveReceipt = receipt
        preferences.edit().putString("ble_setup_receipt", receipt.toString())
            .remove("ble_setup_completed").apply()
        setupStep = 2; saveSetupStep()
    }
    internal val selectedAcModel: AcModelChoice? get() = acCatalog.find(setupModelId)
    val setupCanContinue: Boolean get() = foreground && !closed && !busy && !ble.busy &&
        (setupMode == "ac" || freenoveReceipt != null)

    val paired: Boolean get() = profile.opt("paired") == true
    private val permissionGranted: Boolean get() = profile.opt("permission_granted") != false
    val usable: Boolean get() = paired && permissionGranted && online && foreground && !closed && !authorizationBlocked && !pairingBusy
    val connectionTitle: String get() = when {
        !paired -> text(R.string.copy_waiting_for_pc_pairing)
        !permissionGranted -> text(R.string.copy_local_network_access_required)
        authorizationBlocked -> text(R.string.copy_pairing_expired)
        !foreground -> text(R.string.copy_live_view_paused)
        online -> text(R.string.copy_pc_connected)
        else -> text(R.string.copy_pc_unreachable)
    }
    private val visibleSample: PcRoomSample? get() =
        environment?.takeIf { usable && it.visible(sampleClock) }
    val temperature: String get() = visibleSample?.temperature ?: "—"
    val humidity: String get() = visibleSample?.humidity ?: "—"
    val previousSample: Boolean get() = visibleSample?.cached(sampleClock) == true
    val sensorDetail: String get() {
        if (!paired) return text(R.string.copy_connect_the_pc_to_sync)
        if (!permissionGranted) return text(R.string.copy_allow_local_network_access)
        if (authorizationBlocked) return text(R.string.copy_pairing_revoked_pair_again)
        if (!online) return text(R.string.copy_waiting_for_pc_pairing)
        val sample = visibleSample ?: return if (environment != null) text(R.string.copy_pc_reading_is_stale) else sensorMessage
        val label = if (previousSample) text(R.string.copy_last_reading) else text(R.string.copy_recent_reading)
        return text(R.string.copy_pc_sync_s_s, label, formatTime(sample.measuredAt))
    }
    private val automatic: JSONObject get() = status.optJSONObject("automatic_ai") ?: JSONObject()
    private val assessment: JSONObject get() = automatic.optJSONObject("last_record")?.optJSONObject("result")?.optJSONObject("assessment") ?: JSONObject()
    private val aiEnabled: Boolean get() = automatic.opt("enabled") == true
    private val hasAiError: Boolean get() = (automatic.opt("last_error") as? String)?.isNotBlank() == true
    private val aiDanger: Boolean get() {
        val decision = assessment.optJSONObject("control_decision")
        return automatic.opt("execute") == true || decision?.opt("execution_allowed") == true || decision?.opt("would_execute") == true
    }
    val aiTitle: String get() = when {
        !paired -> text(R.string.copy_connect_your_pc)
        !permissionGranted -> text(R.string.copy_allow_network_access)
        !foreground -> text(R.string.copy_live_view_paused)
        !online || authorizationBlocked -> text(R.string.copy_pc_unreachable)
        aiDanger -> text(R.string.copy_check_observation_status)
        aiEnabled && automatic.optString("state") in setOf("paused_budget", "budget_exhausted") -> text(R.string.copy_ai_paused)
        aiEnabled && (hasAiError || automatic.optString("state") in setOf("backoff", "error", "failed")) -> text(R.string.copy_ai_incomplete)
        aiEnabled -> text(R.string.copy_ai_observation_on)
        else -> text(R.string.copy_room_connected)
    }
    val aiDetail: String get() = when {
        !paired -> text(R.string.copy_connect_the_pc_for_climate_and_camera_data)
        !permissionGranted -> text(R.string.copy_allow_local_network_access_in_settings)
        !foreground -> text(R.string.copy_status_refreshes_when_you_return)
        !online || authorizationBlocked -> text(R.string.copy_check_the_pc_connection_for_current_results)
        aiDanger -> text(R.string.copy_unexpected_server_permission_stop_ai_and_check_the)
        aiEnabled && automatic.optString("state") in setOf("paused_budget", "budget_exhausted") -> text(R.string.copy_budget_reached_uploads_paused_619)
        aiEnabled && hasAiError -> text(R.string.copy_last_observation_incomplete_check_pc_settings)
        aiEnabled -> label(automatic.optString("state"), text(R.string.copy_automatic_ai_on)) + automatic.optString("skip_reason").let { if (it.isBlank()) "" else " · " + label(it, text(R.string.copy_waiting_for_observation)) }
        else -> text(R.string.copy_climate_and_records_available_ai_off)
    }
    val aiEvent: String get() = if (!online || !foreground || authorizationBlocked) text(R.string.copy_status_unknown) else label(assessment.optString("validated_visual_decision", assessment.optString("visual_event")), text(R.string.copy_no_result_yet))
    val aiConfirmation: String get() = if (!online || !foreground || authorizationBlocked) "—" else label(assessment.optString("blanket_event_state", assessment.optString("confirmation_state")), text(R.string.copy_awaiting_confirmation))
    private fun backgroundControl(): Pair<String, String> {
        if (!online || !foreground || authorizationBlocked) return text(R.string.copy_pc_controls_unknown) to text(R.string.copy_check_current_control_status_on_the_pc)
        val ir = status.optJSONObject("ir_emission")
        val ac = status.optJSONObject("ac_control")
        val auto = ac?.optJSONObject("automatic")
        return when {
            ir?.opt("enabled") == true || auto?.opt("enabled") == true -> text(R.string.copy_pc_automation_on) to text(R.string.copy_the_pc_may_send_ir_based_on_observations_check_you)
            ac?.opt("enabled") == true -> text(R.string.copy_pc_remote_control_on) to text(R.string.copy_the_pc_can_send_ir_manually_check_your_ac_for_its)
            ir?.opt("enabled") == false && ac?.opt("enabled") == false && auto?.opt("enabled") == false -> text(R.string.copy_pc_automation_off) to text(R.string.copy_the_pc_last_reported_automation_off)
            else -> text(R.string.copy_pc_controls_unknown) to text(R.string.copy_check_current_automation_status_on_the_pc)
        }
    }
    val backgroundControlTitle: String get() = backgroundControl().first
    val backgroundControlDetail: String get() = backgroundControl().second

    private val statusTick = object : Runnable {
        override fun run() {
            if (closed || !started || !foreground) return
            if (paired && permissionGranted && !authorizationBlocked && !pairingBusy) refreshStatus()
            main.postDelayed(this, 5_000L)
        }
    }
    private val roomTick = object : Runnable {
        override fun run() {
            if (closed || !started || !foreground) return
            refreshRoom()
            main.postDelayed(this, 3_000L)
        }
    }
    private val freshnessTick = object : Runnable {
        override fun run() {
            if (closed || !started || !foreground) return
            sampleClock = SystemClock.elapsedRealtime()
            main.postDelayed(this, 1_000L)
        }
    }
    private val previewTick = Runnable { requestPreviewFrame() }

    fun start() = onMain {
        if (started || closed) return@onMain
        started = true
        scene = preferences.getInt("scene", 0).takeIf { it in 0..2 } ?: 0
        setupModelId = preferences.getString("setup_model_id", "").orEmpty()
        freenoveReceipt = try {
            JSONObject(preferences.getString("ble_setup_receipt", "").orEmpty()).takeIf {
                BleSetupPolicy.identity(it.optString("board"), it.optInt("setup_protocol"), it.optString("device_id")) &&
                    BleSetupPolicy.privateIp(it.optString("ip_address")) && it.optString("ssid").isNotEmpty()
            }
        } catch (_: Exception) { null }
        setupProfileReady = true
        setupStep = if (freenoveReceipt != null && preferences.getInt("ble_setup_step", 0) == 2) 2 else 0
        setupVisible = !(freenoveReceipt != null && preferences.getBoolean("ble_setup_completed", false))
        welcomeInline = !preferences.getBoolean("intro_seen", false)
        if (foreground) { startTimers(); loadProfile() }
    }

    fun isSetupDeferred(): Boolean = setupDeferred

    fun restoreDestination(destination: String, deferred: Boolean) = onMain {
        if (deferred) {
            setupDeferred = true
            setupVisible = false
        }
        if (!setupVisible) showPage(destination)
    }

    fun refresh() = onMain {
        if (closed || !foreground || pairingBusy) return@onMain
        authorizationBlocked = false
        loadProfile()
        if (page == "history") loadHistory()
    }

    fun showPage(target: String) = onMain {
        if (closed || target !in setOf("home", "history", "control", "settings", "developer")) return@onMain
        if (target == "developer") {
            if (developerUnlocked && foreground) dialog = "developer" else page = "settings"
            return@onMain
        }
        if (target != "home") stopPreview()
        page = target
        if (target == "history" && usable) loadHistory()
        if (target == "settings" && usable && policy == null) loadPolicy()
    }

    fun selectScene(value: Int) = onMain {
        if (closed || value !in 0..2) return@onMain
        scene = value
        preferences.edit().putInt("scene", value).apply()
    }

    fun openSetup() = onMain {
        if (closed || !foreground || busy) return@onMain
        ble.cancel("")
        setupMode = "ble"; setupDeferred = false; setupExplicit = true; setupVisible = true; setupStep = 0
        saveSetupStep()
        dialog = null; message = ""; stopPreview()
        selectedImage = null; selectedImagePath = null
    }

    fun openAcSetup() = onMain {
        if (closed || !foreground || busy) return@onMain
        ble.cancel(""); setupMode = "ac"; setupExplicit = true; setupVisible = true; setupStep = 1
        dialog = null; message = ""; stopPreview()
    }

    fun onBleAccessResult(granted: Boolean) {
        if (!closed && setupVisible && setupMode == "ble") ble.accessResult(granted) else ble.cancel("")
    }

    fun deferSetup() = onMain {
        if (busy) return@onMain
        ble.cancel("")
        setupDeferred = true; setupExplicit = false; setupVisible = false
        page = if (setupMode == "ac") "settings" else "home"; message = ""
    }

    fun previousSetupStep() = onMain {
        if (busy || ble.busy) return@onMain
        if (setupMode == "ac" && setupStep == 1) { deferSetup(); return@onMain }
        setupStep = if (setupMode == "ac") 1 else 0
        saveSetupStep(); message = ""
    }

    fun nextSetupStep() = onMain {
        if (!setupCanContinue) { message = text(R.string.copy_finish_freenove_bluetooth_setup_first); return@onMain }
        if (setupStep == 1 && selectedAcModel == null) { message = text(R.string.copy_select_the_ac_or_original_remote_model); return@onMain }
        if (setupMode == "ble") ble.cancel("")
        setupStep = 2; saveSetupStep(); message = ""
    }

    private fun saveSetupStep() {
        if (setupMode == "ble") preferences.edit().putInt("ble_setup_step", setupStep).apply()
    }

    fun selectAcModel(id: String) = onMain {
        if (!setupCanContinue || acCatalog.find(id) == null) return@onMain
        setupModelId = id
        preferences.edit().putString("setup_model_id", id).apply()
        message = ""
    }

    fun completeSetup() = onMain {
        if (!setupCanContinue || (setupMode == "ac" && selectedAcModel == null)) {
            message = text(R.string.copy_setup_unconfirmed_review_your_settings); return@onMain
        }
        ble.cancel("")
        if (setupMode == "ble") preferences.edit().putBoolean("ble_setup_completed", true).putBoolean("intro_seen", true).apply()
        welcomeInline = false; setupVisible = false; setupDeferred = false; setupExplicit = false
        setupStep = 0; page = if (setupMode == "ac") "settings" else "home"; message = ""
        saveSetupStep()
    }

    private fun reconcileSetup() {
        setupProfileReady = true
        val complete = freenoveReceipt != null && preferences.getBoolean("ble_setup_completed", false)
        if (!complete && !setupDeferred) setupVisible = true
        else if (complete && !setupExplicit) setupVisible = false
    }

    fun requestPermission() = onMain {
        if (closed || !foreground) return@onMain
        send("permission", epoch = null, allowBackground = true) { response ->
            if (response.opt("ok") == true) { if (foreground) loadProfile() }
            else message = friendlyError(response, text(R.string.copy_allow_network_access_and_try_again))
        }
    }

    fun pair(payload: String) = onMain {
        if (closed || !foreground || busy || pairingBusy) return@onMain
        val raw = payload.trim()
        try {
            val decoded = JSONObject(PairingPayloadCodec.decode(raw))
            require(decoded.optInt("version") == 1) { text(R.string.copy_unsupported_pairing_version) }
            NetworkPolicy.validateBaseUrl(decoded.getString("base_url"))
            NetworkPolicy.validateFingerprint(decoded.getString("certificate_sha256"))
            require(decoded.getString("pairing_code").matches(Regex("[A-Za-z0-9_-]{6,128}"))) { text(R.string.copy_invalid_code_copy_it_again_from_the_pc) }
        } catch (_: Exception) { message = text(R.string.copy_copy_the_full_ds1_code_generate_a_new_one_if_it_ex); return@onMain }
        invalidateUi(clearPictures = true)
        pairingInput = raw
        pairingBusy = true; busy = true
        val epoch = uiEpoch
        message = text(R.string.copy_confirm_the_pc_address_and_certificate)
        send("pair", JSONObject().put("pairing_payload", raw), epoch) { response ->
            pairingBusy = false; busy = false
            if (response.opt("ok") == true) {
                authorizationBlocked = false; pairingInput = ""
                profile = copy(response.optJSONObject("data") ?: JSONObject())
                reconcileSetup()
                message = text(R.string.copy_paired_ai_and_ir_settings_stay_as_configured)
                page = "home"; loadProfile()
            } else message = friendlyError(response, text(R.string.copy_check_the_code_server_and_wi_fi))
        }
    }

    fun forget() = onMain {
        if (closed || !foreground || busy || !paired) return@onMain
        dialog = null
        invalidateUi(clearPictures = true)
        busy = true
        val epoch = uiEpoch
        send("forget", epoch = epoch) { response ->
            busy = false
            if (response.opt("ok") == true) {
                profile = copy(response.optJSONObject("data") ?: JSONObject().put("paired", false))
                status = JSONObject(); authorizationBlocked = false; pairingInput = ""
                online = false; page = "settings"
                preferences.edit().remove("setup_model_binding")
                    .remove("setup_completed_binding").remove("setup_completed_version")
                    .remove("setup_step").remove("setup_step_binding").apply()
                message = text(R.string.copy_unpaired_pc_jobs_keep_running)
            } else { message = friendlyError(response, text(R.string.copy_could_not_unpair_try_again)); loadProfile() }
        }
    }

    fun togglePreview() = onMain {
        if (previewActive) { stopPreview(); return@onMain }
        if (!canAct() || busy || page != "home") return@onMain
        previewActive = true; imageEpoch++
        requestPreviewFrame()
    }

    fun capture() = onMain {
        val epoch = beginAction() ?: return@onMain
        stopPreview()
        val pictureEpoch = imageEpoch
        api("/api/capture", "POST", JSONObject(), epoch, onFailure = { busy = false; message = it }) { data ->
            val item = data.optJSONObject("capture")
            val path = safeCapturePath(item?.optString("url"))
            if (path == null) { busy = false; message = text(R.string.copy_no_usable_capture_returned_check_records); return@api }
            imageRequest(path, epoch, onFailure = { busy = false; message = it }) { response ->
                decodeImage(response, epoch, pictureEpoch, onFailure = { busy = false; message = it }, onDiscard = { busy = false }) { bitmap ->
                    previewBitmap = bitmap; previewImageId = ""; previewPath = path
                    previewTime = text(R.string.copy_saved_capture_s_still_image, formatTime(item?.opt("captured_at")))
                    busy = false; message = text(R.string.copy_saved_on_the_pc_you_can_copy_it_to_the_gallery)
                    refreshStatus()
                }
            }
        }
    }

    fun savePreview() = onMain {
        val epoch = beginAction() ?: return@onMain
        val path = previewPath
        if (previewBitmap == null || path == null || (path == SNAPSHOT && previewImageId.isBlank())) {
            busy = false; message = text(R.string.copy_refresh_the_preview_before_saving); return@onMain
        }
        val details = JSONObject().put("path", path)
        if (path == SNAPSHOT) details.put("image_id", previewImageId)
        send("save_image", details, epoch) { response ->
            busy = false
            if (response.opt("ok") == true) message = text(R.string.copy_saved_to_pictures_dreamshield)
            else if (!authenticationFailure(response)) message = friendlyError(response, text(R.string.copy_save_failed_refresh_the_preview_and_retry))
        }
    }

    fun loadHistory() = onMain {
        if (!usable || historyBusy) return@onMain
        historyBusy = true
        val epoch = uiEpoch
        api("/api/captures?limit=12", epoch = epoch, onFailure = { historyBusy = false; message = it }) { data ->
            val images = objects(data.optJSONArray("items")).filter { safeCapturePath(it.optString("url")) != null }.take(12)
            api("/api/automatic-ai/history", epoch = epoch, onFailure = { historyBusy = false; message = it }) { events ->
                historyBusy = false
                if (page == "history") { captures = images; aiEvents = objects(events.optJSONArray("items")).take(20) }
            }
        }
    }

    fun openCapture(item: JSONObject) = onMain {
        val path = safeCapturePath(item.optString("url")) ?: return@onMain
        val epoch = beginAction() ?: return@onMain
        val pictureEpoch = imageEpoch
        imageRequest(path, epoch, onFailure = { busy = false; message = it }) { response ->
            decodeImage(response, epoch, pictureEpoch, onFailure = { busy = false; message = it }, onDiscard = { busy = false }) { bitmap ->
                busy = false; selectedImage = bitmap; selectedImagePath = path
                selectedImageCaption = text(R.string.copy_s_s_original_on_pc, formatTime(item.opt("captured_at"), true), label(item.optString("source"), text(R.string.copy_captures)))
            }
        }
    }

    fun saveSelectedImage() = onMain {
        val path = safeCapturePath(selectedImagePath) ?: return@onMain
        val epoch = beginAction() ?: return@onMain
        send("save_image", JSONObject().put("path", path), epoch) { response ->
            busy = false
            if (response.opt("ok") == true) message = text(R.string.copy_saved_to_pictures_dreamshield_654)
            else if (!authenticationFailure(response)) message = friendlyError(response, text(R.string.copy_save_failed_check_the_connection))
        }
    }

    fun saveConfig(schedule: Boolean, interval: Int, environmentEnabled: Boolean) = onMain {
        if (interval !in 5..86400) { message = text(R.string.copy_use_a_whole_capture_interval_of_5_86400_s); return@onMain }
        val epoch = beginAction() ?: return@onMain
        val payload = JSONObject().put("schedule_enabled", schedule).put("capture_interval_seconds", interval).put("environment_recording_enabled", environmentEnabled)
        api("/api/config", "POST", payload, epoch, onFailure = { busy = false; message = it }) {
            busy = false
            message = if (schedule) text(R.string.copy_pc_logging_settings_saved) else text(R.string.copy_captures_and_automatic_ai_stopped_climate_logging)
            refreshStatus()
        }
    }

    fun enableAI(consent: Boolean, interval: Int = 60, hourly: Int = 20, session: Int = 50) = onMain {
        if (!foreground || dialog != "consent" || !consent) { message = text(R.string.copy_confirm_image_uploads_and_api_costs_first); return@onMain }
        if (aiEnabled) { message = text(R.string.copy_automatic_ai_is_already_on); return@onMain }
        if (status.opt("vlm_key_configured") != true) { message = text(R.string.copy_configure_the_model_api_key_on_the_pc); return@onMain }
        val captureInterval = strictNumber(status.optJSONObject("config"), "capture_interval_seconds")
        if (captureInterval == null || captureInterval % 1.0 != 0.0 || captureInterval !in 5.0..300.0) { message = text(R.string.copy_set_capture_interval_to_5_300_s_before_enabling_ai); return@onMain }
        // Locked advanced fields cannot alter the normal opt-in defaults saved by the PC.
        val a = automatic
        val actualInterval = if (developerUnlocked) interval else strictInt(a, "min_interval_seconds", 60)
        val actualHourly = if (developerUnlocked) hourly else strictInt(a, "max_calls_per_hour", 20)
        val actualSession = if (developerUnlocked) session else strictInt(a, "max_calls_per_session", 50)
        if (actualInterval !in 60..3600 || actualHourly !in 2..120 || actualSession !in 2..500) { message = text(R.string.copy_check_the_ai_interval_and_budget); return@onMain }
        val epoch = beginAction() ?: return@onMain
        val payload = JSONObject().put("enabled", true).put("cloud_upload_consent", true).put("min_interval_seconds", actualInterval)
            .put("max_calls_per_hour", actualHourly).put("max_calls_per_session", actualSession)
        api("/api/automatic-ai", "POST", payload, epoch, onFailure = { busy = false; dialog = null; message = it }) {
            busy = false; dialog = null
            message = text(R.string.copy_automatic_ai_on_the_first_capture_sets_a_baseline)
            refreshStatus()
        }
    }

    fun stopAI() = onMain {
        val epoch = beginAction() ?: return@onMain
        api("/api/automatic-ai", "POST", JSONObject().put("enabled", false), epoch, onFailure = { busy = false; message = it }) {
            busy = false; if (dialog == "consent") dialog = null
            message = text(R.string.copy_new_ai_tasks_stopped_logging_continues_requests_al)
            refreshStatus()
        }
    }

    fun loadPolicy() = onMain {
        if (!usable || policyBusy || busy) return@onMain
        policyBusy = true
        val epoch = uiEpoch
        api("/api/policy-settings", epoch = epoch, onFailure = {
            policyBusy = false; policy = null; policyFeedback = it
        }) { data ->
            policyBusy = false
            try {
                validateLoadedPolicy(data)
                policy = copy(data)
                policyFeedback = if (policyNumber(data, "environment_interval_seconds", 5.0, 3600.0, true) > 30) text(R.string.copy_choose_a_preset_or_set_polling_to_5_30_s) else text(R.string.copy_policy_loaded_save_to_apply)
                val confirmed = strictNumber(data, "sensor_sample_interval_seconds")
                sensorFeedback = if (confirmed != null && confirmed % 1.0 == 0.0 && confirmed in 5.0..30.0) text(R.string.copy_last_confirmed_s_s_apply_the_target_interval_separ, confirmed.toInt()) else text(R.string.copy_apply_the_target_interval_on_the_device)
            } catch (_: Exception) { policy = null; policyFeedback = text(R.string.copy_update_the_pc_server_to_read_policy) }
        }
    }

    fun savePolicy(payload: JSONObject) = onMain {
        if (policyBusy) { policyFeedback = text(R.string.copy_reloading_policy_wait_before_saving); return@onMain }
        val saved = policy
        if (saved == null) { policyFeedback = text(R.string.copy_read_the_policy_from_the_pc_first); return@onMain }
        val checked = try { validatePolicyPayload(payload, saved) } catch (error: IllegalArgumentException) { policyFeedback = error.message ?: text(R.string.copy_check_the_policy_values); return@onMain } catch (_: Exception) { policyFeedback = text(R.string.copy_policy_fields_are_missing_reload_and_check); return@onMain }
        val epoch = beginAction() ?: return@onMain
        api("/api/policy-settings", "POST", checked, epoch, onFailure = { busy = false; policyFeedback = text(R.string.copy_save_unconfirmed_reload_to_check_s, it) }) { data ->
            busy = false
            try {
                validateLoadedPolicy(data); policy = copy(data)
                policyFeedback = text(R.string.copy_policy_saved_ai_and_automation_paused)
                sensorFeedback = text(R.string.copy_sht_target_saved_apply_it_on_the_device)
            } catch (_: Exception) { policy = null; policyFeedback = text(R.string.copy_policy_unconfirmed_reload_to_check) }
            refreshStatus()
        }
    }

    fun applySensor(interval: Int) = onMain {
        if (policyBusy) { sensorFeedback = text(R.string.copy_reloading_policy_wait_before_applying); return@onMain }
        if (interval !in 5..30) { sensorFeedback = text(R.string.copy_use_a_whole_sht31_interval_of_5_30_s_679); return@onMain }
        if (policy == null) { sensorFeedback = text(R.string.copy_read_pc_policy_before_applying_the_interval); return@onMain }
        val epoch = beginAction() ?: return@onMain
        api("/api/sensor-settings", "POST", JSONObject().put("sample_interval_seconds", interval), epoch, onFailure = { busy = false; sensorFeedback = text(R.string.copy_apply_unconfirmed_check_the_device_s, it) }) { result ->
            busy = false
            val confirmed = strictNumber(result, "sample_interval_seconds")
            if (result.optString("status") == "applied" && confirmed == interval.toDouble() && result.opt("device_id") is String && result.optString("device_id").isNotBlank() && result.opt("boot_id") is String && result.optString("boot_id").isNotBlank()) {
                policy = policy?.let { copy(it).put("sensor_sample_interval_seconds", interval) }
                sensorFeedback = text(R.string.copy_device_confirmed_s_s_restart_restores_15_s_ai_and, interval)
            } else sensorFeedback = text(R.string.copy_device_confirmation_incomplete_check_the_device_be)
            refreshStatus()
        }
    }

    fun tapVersion() = onMain {
        if (closed || !foreground || dialog != "about") return@onMain
        val now = SystemClock.elapsedRealtime()
        if (tapCount == 0 || now - tapStarted > 8_000L || now < tapStarted) { tapCount = 0; tapStarted = now }
        tapCount++
        if (tapCount >= 5) { developerUnlocked = true; tapCount = 0; message = text(R.string.copy_developer_tools_unlocked_for_this_session) }
    }

    fun lockDeveloper() = onMain {
        developerUnlocked = false; tapCount = 0; tapStarted = 0
        if (dialog == "developer") dialog = null
        if (page == "developer") page = "settings"
    }

    var welcomeInline by mutableStateOf(false)

    fun dismissWelcome() = onMain {
        welcomeInline = false
        preferences.edit().putBoolean("intro_seen", true).apply()
    }

    fun dismissDialog() = onMain {
        if (dialog == "welcome") welcomeInline = false
        if (dialog == "welcome") preferences.edit().putBoolean("intro_seen", true).apply()
        if (dialog == "about") { tapCount = 0; tapStarted = 0 }
        if (dialog == "developer") lockDeveloper()
        dialog = null
    }

    fun onLifecycle(isForeground: Boolean) = onMain {
        if (closed) return@onMain
        foreground = isForeground
        if (!isForeground) {
            ble.lifecycle(false)
            invalidateUi(clearPictures = false)
            dialog = null; selectedImage = null; selectedImagePath = null
            stopTimers()
            sensorMessage = text(R.string.copy_live_readings_paused_pc_jobs_keep_running)
        } else { sleep.reload(); ble.lifecycle(true); if (started) { startTimers(); loadProfile() } }
    }

    fun onPairingLink() = onMain {
        if (closed) return@onMain
        page = "settings"
        if (foreground && started) loadProfile()
    }

    fun onThemeChanged() = onMain { sampleClock = SystemClock.elapsedRealtime() }

    fun beginLaunchReveal(x: Float, y: Float, radius: Float) = onMain {
        if (launchRevealStarted || !launchVisible) return@onMain
        launchOriginX = x.coerceAtLeast(0f)
        launchOriginY = y.coerceAtLeast(0f)
        launchStartRadius = radius.coerceAtLeast(0f)
        launchRevealStarted = true
    }

    fun handleBack(): Boolean {
        if (closed || !foreground) return false
        if (selectedImage != null || selectedImagePath != null) { selectedImage = null; selectedImagePath = null; selectedImageCaption = ""; return true }
        if (dialog != null) { dismissDialog(); return true }
        if (page != "home") { showPage("home"); return true }
        return false
    }

    fun close() = onMain {
        if (closed) return@onMain
        ble.lifecycle(false)
        closed = true; uiEpoch++; imageEpoch++
        previewActive = false; foreground = false; busy = false
        main.removeCallbacksAndMessages(null)
        decoder.shutdownNow()
        previewBitmap = null; selectedImage = null; environment = null
        queuedPairingInput = null
        developerUnlocked = false; dialog = null
    }

    private fun loadProfile() {
        if (closed || !foreground || profileBusy || pairingBusy) return
        profileBusy = true
        val epoch = uiEpoch
        send("profile", epoch = epoch) { response ->
            profileBusy = false
            if (response.opt("ok") != true) { setupProfileReady = true; online = false; message = friendlyError(response, text(R.string.copy_could_not_read_pairing_reopen_the_app)); return@send }
            val next = response.optJSONObject("data") ?: JSONObject()
            val pending = (next.opt("pending_pairing_input") as? String) ?: queuedPairingInput
            queuedPairingInput = null
            val safe = copy(next); safe.remove("pending_pairing_input"); profile = safe
            reconcileSetup()
            if (!pending.isNullOrBlank()) { pairingInput = pending; page = "settings"; message = text(R.string.copy_qr_code_loaded_verify_and_confirm_the_pc_certifica) }
            if (paired && permissionGranted && !authorizationBlocked) { refreshStatus(); refreshRoom() }
            else {
                online = false; environment = null; policy = null
                sensorMessage = if (!permissionGranted) text(R.string.copy_allow_local_network_access_first) else text(R.string.copy_connect_the_pc_to_read_sht31_data)
            }
        }
    }

    private fun refreshStatus() {
        if (closed || !foreground || !paired || !permissionGranted || authorizationBlocked || pairingBusy || refreshBusy) return
        refreshBusy = true
        val epoch = uiEpoch
        api("/api/app/status", epoch = epoch, onFailure = {
            refreshBusy = false; online = false; stopPreview(); environment = null
            sensorMessage = text(R.string.copy_pc_offline_no_current_reading)
            message = text(R.string.copy_pc_unreachable_check_its_power_server_and_wi_fi)
            policy = null
        }) { data ->
            refreshBusy = false
            status = copy(data); online = true
            syncSleepHistory(false)
            if (policy == null && !policyBusy) loadPolicy()
        }
    }

    private fun refreshRoom() {
        if (closed || !foreground || !paired || !permissionGranted || authorizationBlocked || pairingBusy || roomBusy) return
        roomBusy = true
        val startedAt = SystemClock.elapsedRealtime()
        api("/api/environment/current", onFailure = { error ->
            roomBusy = false; environment = null
            sensorMessage = if (error == roomUpgrade) error else text(R.string.copy_climate_sync_interrupted_retrying)
        }) { data ->
            roomBusy = false
            sampleClock = SystemClock.elapsedRealtime()
            environment = PcRoomSample.parse(startedAt, data::opt)
            sleepSourceId()?.let { source ->
                environment?.let { sample ->
                    runCatching { sleep.repo.ingestSamples(listOf(RoomSample(source,
                        Instant.parse(sample.measuredAt).toEpochMilli(), data.getDouble("temperature_c"), data.getDouble("humidity_percent")))) }
                }
            }
            sensorMessage = when (data.optString("reason")) {
                "device_unreachable" -> text(R.string.copy_pc_sync_board_unresponsive)
                "stale" -> text(R.string.copy_pc_reading_is_stale)
                "recording_off" -> text(R.string.copy_enable_and_save_climate_logging_in_observe)
                "waiting" -> text(R.string.copy_waiting_for_the_first_sample)
                else -> text(R.string.copy_no_valid_reading_from_the_pc)
            }
        }
    }

    private fun requestPreviewFrame() {
        if (closed || !usable || !previewActive || previewBusy || page != "home") return
        previewBusy = true
        val epoch = uiEpoch; val pictureEpoch = imageEpoch
        imageRequest(SNAPSHOT, epoch, onFailure = {
            if (pictureEpoch == imageEpoch) { previewBusy = false; stopPreview(); message = text(R.string.copy_preview_paused_after_an_error_check_the_camera_con) }
        }) { response ->
            if (pictureEpoch != imageEpoch) return@imageRequest
            if (!previewActive || page != "home") { previewBusy = false; return@imageRequest }
            decodeImage(response, epoch, pictureEpoch, onFailure = { previewBusy = false; stopPreview(); message = it }) { bitmap ->
                previewBusy = false
                if (!previewActive || page != "home") return@decodeImage
                previewBitmap = bitmap; previewPath = SNAPSHOT
                previewImageId = (response.opt("image_id") as? String)?.takeIf { it.length <= 100 } ?: ""
                previewTime = text(R.string.copy_last_frame_s_refreshes_about_every_2_5_s, formatTime(System.currentTimeMillis()))
                main.removeCallbacks(previewTick); main.postDelayed(previewTick, 2_500L)
            }
        }
    }

    private fun stopPreview() {
        previewActive = false; previewBusy = false; imageEpoch++
        main.removeCallbacks(previewTick)
        if (previewBitmap != null && !previewTime.contains(text(R.string.copy_still_image))) previewTime = previewTime.substringBefore(text(R.string.copy_about)) + text(R.string.copy_paused)
    }

    private fun imageRequest(path: String, epoch: Long, onFailure: (String) -> Unit, onSuccess: (JSONObject) -> Unit) {
        send("request", JSONObject().put("method", "GET").put("path", path), epoch) { response ->
            if (response.opt("ok") == true) onSuccess(response)
            else if (!authenticationFailure(response)) onFailure(friendlyError(response, text(R.string.copy_could_not_load_image_check_the_connection)))
        }
    }

    private fun decodeImage(response: JSONObject, epoch: Long, pictureEpoch: Long, onFailure: (String) -> Unit, onDiscard: () -> Unit = {}, onSuccess: (Bitmap) -> Unit) {
        val base64 = response.opt("base64") as? String
        val mime = response.optString("content_type").substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mime != "image/jpeg" || base64.isNullOrEmpty() || base64.length > 14_000_000 || !base64.matches(Regex("[A-Za-z0-9+/=\\r\\n]+"))) { onFailure(text(R.string.copy_image_format_or_size_unsupported)); return }
        try {
            decoder.execute {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    require(bytes.size in 4..10 * 1024 * 1024 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte())
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000L)
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IllegalArgumentException()
                    main.post {
                        if (isCurrent(epoch) && pictureEpoch == imageEpoch) onSuccess(bitmap)
                        else { bitmap.recycle(); if (isCurrent(epoch)) onDiscard() }
                    }
                } catch (_: Exception) { main.post { if (isCurrent(epoch)) { if (pictureEpoch == imageEpoch) onFailure(text(R.string.copy_could_not_decode_the_image_refresh_and_retry)) else onDiscard() } } }
            }
        } catch (_: Exception) { if (isCurrent(epoch)) onFailure(text(R.string.copy_image_load_canceled)) }
    }

    private fun api(path: String, method: String = "GET", body: JSONObject? = null, epoch: Long = uiEpoch, onFailure: (String) -> Unit = { message = it }, onSuccess: (JSONObject) -> Unit) {
        val details = JSONObject().put("path", path).put("method", method)
        if (body != null) details.put("body", body)
        send("request", details, epoch) { response ->
            if (response.opt("ok") != true) {
                val oldPolicyRoute = path in setOf("/api/policy-settings", "/api/sensor-settings") && response.optInt("status") == 403 && response.optString("error").contains("Route not available to mobile clients", true)
                val code = response.optInt("status")
                if (path == "/api/environment/current" && (code == 404 ||
                    (code == 403 && response.optString("error").contains("Route not available", true)))) onFailure(roomUpgrade)
                else if (oldPolicyRoute) onFailure(text(R.string.copy_update_and_restart_the_pc_server_to_change_policy))
                else if (!authenticationFailure(response)) onFailure(friendlyError(response, text(R.string.copy_check_pc_connection_and_settings_then_try_again)))
                return@send
            }
            val data = response.optJSONObject("data")
            if (data == null || (data.has("ok") && data.opt("ok") != true)) onFailure(text(R.string.copy_invalid_pc_response_check_the_server_version))
            else onSuccess(data)
        }
    }

    private fun send(action: String, details: JSONObject = JSONObject(), epoch: Long? = uiEpoch, allowBackground: Boolean = false, completion: (JSONObject) -> Unit) {
        if (closed) return
        val request = copy(details).put("action", action).put("request_id", "native_" + UUID.randomUUID().toString().replace("-", ""))
        var completed = false
        fun valid() = !closed && (allowBackground || foreground) && (epoch == null || epoch == uiEpoch)
        val timeout = Runnable {
            if (!completed && valid()) {
                completed = true
                completion(JSONObject().put("ok", false).put("status", 0).put("error", text(R.string.copy_connection_timed_out)))
            }
        }
        if (action != "permission" && action != "pair") main.postDelayed(timeout, 30_000L)
        try {
            activity.requestNative(request.toString(), Consumer { response ->
                onMain {
                    // publicProfile consumes a scanned intent. Keep that input even when a
                    // background transition invalidated the otherwise stale profile response.
                    if (!closed && action in setOf("profile", "pair", "forget")) {
                        (response.optJSONObject("data")?.opt("pending_pairing_input") as? String)?.takeIf { it.isNotBlank() }?.let { queuedPairingInput = it }
                    }
                    if (!completed) {
                        completed = true; main.removeCallbacks(timeout)
                        if (valid()) try { completion(response) } catch (_: Exception) { busy = false; message = text(R.string.copy_could_not_process_the_pc_response_refresh_and_retr) }
                    }
                }
            })
        } catch (_: Exception) {
            main.removeCallbacks(timeout)
            if (!completed && valid()) { completed = true; completion(JSONObject().put("ok", false).put("status", 0)) }
        }
    }

    private fun authenticationFailure(response: JSONObject): Boolean {
        val statusCode = response.optInt("status")
        if (statusCode != 401 && statusCode != 403) return false
        invalidateUi(clearPictures = true)
        online = false; authorizationBlocked = true; dialog = null
        if (statusCode == 401) {
            profile = JSONObject().put("paired", false).put("permission_granted", permissionGranted)
            status = JSONObject()
            message = text(R.string.copy_pc_unpaired_generate_a_new_code_and_pair_again_in)
        } else message = text(R.string.copy_pc_denied_access_check_pairing_permissions)
        return true
    }

    private fun invalidateUi(clearPictures: Boolean) {
        uiEpoch++; stopPreview()
        online = false
        profileBusy = false; refreshBusy = false; roomBusy = false; historyBusy = false; policyBusy = false; pairingBusy = false; busy = false
        environment = null; policy = null
        policyFeedback = text(R.string.copy_reconnect_to_reload_policy_draft_discarded)
        sensorFeedback = text(R.string.copy_apply_the_target_interval_on_the_device)
        lockDeveloper()
        if (clearPictures) {
            previewBitmap = null; previewImageId = ""; previewPath = null; previewTime = text(R.string.copy_preview_unavailable)
            selectedImage = null; selectedImagePath = null; selectedImageCaption = ""
            captures = emptyList(); aiEvents = emptyList()
        }
    }

    private fun canAct(): Boolean {
        if (usable) return true
        message = if (!permissionGranted) text(R.string.copy_allow_local_network_access_first) else text(R.string.copy_connect_the_pc_server_first)
        return false
    }

    private fun beginAction(): Long? {
        if (!canAct() || busy) return null
        busy = true
        return uiEpoch
    }

    private fun startTimers() {
        stopTimers()
        main.postDelayed(statusTick, 5_000L); main.postDelayed(roomTick, 3_000L); main.post(freshnessTick)
    }

    private fun stopTimers() { main.removeCallbacks(statusTick); main.removeCallbacks(roomTick); main.removeCallbacks(freshnessTick); main.removeCallbacks(previewTick) }
    private fun isCurrent(epoch: Long): Boolean = !closed && foreground && epoch == uiEpoch
    private fun onMain(action: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() } }

    companion object {
        private val roomUpgrade get() = text(R.string.copy_update_the_pc_console_to_sync_climate_data)
        private const val SNAPSHOT = "/api/device/snapshot"
        private val policyRanges = linkedMapOf("min_setpoint_c" to (16.0..30.0), "max_setpoint_c" to (16.0..30.0), "temperature_lower_c" to (18.0..32.0), "temperature_upper_c" to (18.0..32.0), "environment_persistence_seconds" to (30.0..600.0), "temperature_step_c" to (1.0..2.0), "command_cooldown_seconds" to (60.0..3600.0), "confirmation_target_seconds" to (20.0..50.0))
        private val modes = listOf("light", "balanced", "efficient")
        private fun copy(value: JSONObject): JSONObject = JSONObject(value.toString())
        private fun strictNumber(value: JSONObject?, name: String): Double? = (value?.opt(name) as? Number)?.toDouble()?.takeIf { it.isFinite() }
        private fun strictInt(value: JSONObject, name: String, fallback: Int): Int = strictNumber(value, name)?.takeIf { it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt() ?: fallback
        private fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        private fun safeCapturePath(path: String?): String? = try { path?.takeIf { it.startsWith("/captures/") && NetworkPolicy.validateImagePath(it) == it } } catch (_: Exception) { null }
        private fun formatTime(value: Any?, full: Boolean = false): String = try {
            val instant = when (value) { is Number -> Instant.ofEpochMilli(value.toLong()); is String -> Instant.parse(value); else -> null }
            if (instant == null) "—" else DateTimeFormatter.ofPattern(if (full) "MM-dd HH:mm:ss" else "HH:mm:ss").withZone(ZoneId.systemDefault()).format(instant)
        } catch (_: Exception) { "—" }
        private fun policyNumber(value: JSONObject, name: String, min: Double, max: Double, integer: Boolean): Double {
            val raw = value.opt(name)
            val number = when (raw) { is Number -> raw.toDouble(); is String -> raw.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull(); else -> null }
            require(number != null && number.isFinite() && number in min..max && (!integer || number % 1.0 == 0.0)) { text(R.string.copy_s_must_be_s_s_s, name, min, max, if (integer) text(R.string.copy_whole_number_297) else text(R.string.copy_number)) }
            return number
        }
        private fun validatePolicyValues(value: JSONObject): JSONObject {
            val normalized = JSONObject()
            for ((key, range) in policyRanges) {
                val integer = key !in setOf("temperature_lower_c", "temperature_upper_c")
                val number = policyNumber(value, key, range.start, range.endInclusive, integer)
                normalized.put(key, if (integer) number.toInt() else number)
            }
            require(normalized.getDouble("min_setpoint_c") <= normalized.getDouble("max_setpoint_c")) { text(R.string.copy_the_minimum_setpoint_must_be_at_or_below_the_maxim_718) }
            require(normalized.getDouble("temperature_upper_c") - normalized.getDouble("temperature_lower_c") >= 2.0) { text(R.string.copy_set_room_thresholds_at_least_2_c_apart) }
            return normalized
        }
        private fun validateLoadedPolicy(value: JSONObject) {
            validatePolicyValues(value.optJSONObject("policy") ?: throw IllegalArgumentException())
            policyNumber(value, "environment_interval_seconds", 5.0, 3600.0, true)
            policyNumber(value, "sensor_sample_target_seconds", 5.0, 30.0, true)
            val registry = value.optJSONObject("presets") ?: throw IllegalArgumentException()
            for (name in modes) {
                val preset = registry.optJSONObject(name) ?: throw IllegalArgumentException()
                require(preset.opt("label") is String && preset.opt("description") is String)
                validatePolicyValues(preset.optJSONObject("policy") ?: throw IllegalArgumentException())
                policyNumber(preset, "environment_interval_seconds", 5.0, 30.0, true)
                policyNumber(preset, "sensor_sample_target_seconds", 5.0, 30.0, true)
            }
        }
        private fun validatePolicyPayload(value: JSONObject, loaded: JSONObject): JSONObject {
            require(value.keys().asSequence().all { it in setOf("preset", "policy", "environment_interval_seconds", "sensor_sample_target_seconds") }) { text(R.string.copy_policy_includes_unsupported_settings) }
            val name = value.optString("preset")
            require(name == "custom" || name in modes) { text(R.string.copy_choose_gentle_balanced_efficient_or_custom) }
            val checked = validatePolicyValues(value.optJSONObject("policy") ?: throw IllegalArgumentException(text(R.string.copy_policy_fields_are_missing)))
            val polling = policyNumber(value, "environment_interval_seconds", 5.0, 30.0, true).toInt()
            val sample = policyNumber(value, "sensor_sample_target_seconds", 5.0, 30.0, true).toInt()
            if (name != "custom") {
                val preset = loaded.optJSONObject("presets")?.optJSONObject(name) ?: throw IllegalArgumentException(text(R.string.copy_reload_pc_presets))
                val expected = validatePolicyValues(preset.getJSONObject("policy"))
                require(policyRanges.keys.all { checked.getDouble(it) == expected.getDouble(it) } && polling.toDouble() == policyNumber(preset, "environment_interval_seconds", 5.0, 30.0, true) && sample.toDouble() == policyNumber(preset, "sensor_sample_target_seconds", 5.0, 30.0, true)) { text(R.string.copy_choose_custom_to_edit_preset_values) }
            }
            return JSONObject().put("preset", name).put("policy", checked).put("environment_interval_seconds", polling).put("sensor_sample_target_seconds", sample)
        }
        private fun friendlyError(response: JSONObject, fallback: String): String {
            val code = response.optInt("status")
            val raw = response.optString("error")
            return when {
                code == 401 || code == 403 -> text(R.string.copy_authorization_failed_check_the_pc_pairing)
                code in setOf(404, 405, 501) -> text(R.string.copy_update_the_pc_server_to_use_this_feature)
                raw.contains("TLS证书验证失败") || raw.contains("TLS certificate check failed") -> text(R.string.copy_certificate_mismatch_verify_the_fingerprint_ip_and)
                raw.contains("配对已取消") || raw.contains("Pairing canceled") -> text(R.string.copy_pairing_canceled_you_can_try_again)
                raw.contains("连接超时") || raw.contains("Connection timed out") || raw.contains("无法连接电脑") || raw.contains("Cannot reach the PC") -> text(R.string.copy_keep_the_server_running_and_check_wi_fi_and_firewa)
                raw.contains("请先允许") || raw.contains("Allow local network access") -> text(R.string.copy_allow_local_network_access_before_pairing_733)
                raw.contains("此预览已过期") || raw.contains("Preview expired") -> text(R.string.copy_refresh_the_expired_preview_before_saving)
                else -> fallback
            }
        }
        private val labels = mapOf(
            "disabled" to R.string.copy_off, "off" to R.string.copy_off, "waiting_baseline" to R.string.copy_waiting_for_a_baseline_frame, "waiting_frame" to R.string.copy_waiting_for_the_next_capture_736, "waiting_capture" to R.string.copy_waiting_for_scheduled_capture, "idle" to R.string.copy_waiting_for_scheduled_images,
            "analyzing" to R.string.copy_comparing_images, "comparing" to R.string.copy_comparing_images, "waiting_confirmation" to R.string.copy_waiting_about_30_s_for_follow_up, "confirming" to R.string.copy_analyzing_follow_up, "completed" to R.string.copy_observation_complete, "cooldown" to R.string.copy_waiting_for_the_next_interval,
            "backoff" to R.string.copy_request_failed_retrying_later, "paused_budget" to R.string.copy_budget_reached_uploads_paused, "budget_exhausted" to R.string.copy_budget_reached_uploads_paused, "error" to R.string.copy_observation_failed, "failed" to R.string.copy_observation_failed, "cancelled" to R.string.copy_canceled, "stopped" to R.string.copy_stopped,
            "confirmed" to R.string.copy_uncovered_state_confirmed, "recovered" to R.string.copy_coverage_restored, "no_candidate" to R.string.copy_no_significant_coverage_reduction, "confirmation_pending" to R.string.copy_waiting_for_a_follow_up_frame, "not_required" to R.string.copy_no_follow_up_needed, "inconclusive" to R.string.copy_review_needed,
            "suspected_blanket_kicking" to R.string.copy_possible_blanket_displacement, "significant_coverage_reduction_observed" to R.string.copy_coverage_significantly_reduced, "significant_coverage_reduction_candidate" to R.string.copy_possible_coverage_reduction, "coverage_reduction_below_candidate_threshold" to R.string.copy_coverage_reduction_below_threshold, "no_significant_coverage_change" to R.string.copy_no_significant_coverage_change, "indeterminate" to R.string.copy_review_needed, "no_person" to R.string.copy_no_person_detected,
            "candidate" to R.string.copy_possible_coverage_reduction, "pair_completed" to R.string.copy_image_comparison_complete, "confirmation_completed" to R.string.copy_follow_up_complete, "baseline_saved" to R.string.copy_baseline_set_waiting_for_next_image, "baseline_required" to R.string.copy_new_baseline_needed, "minimum_interval" to R.string.copy_minimum_interval_not_reached, "worker_busy_frame_not_queued" to R.string.copy_model_busy_new_images_skipped, "device_or_boot_changed" to R.string.copy_device_restarted_resetting_baseline, "frame_gap_too_large" to R.string.copy_frames_too_far_apart_resetting_baseline, "hourly_budget_exhausted" to R.string.copy_hourly_budget_reached, "session_budget_exhausted" to R.string.copy_session_budget_reached, "failure_backoff" to R.string.copy_recent_failure_uploads_delayed, "user_stopped" to R.string.copy_automatic_ai_stopped, "schedule_disabled" to R.string.copy_scheduled_captures_off, "not_enabled" to R.string.copy_off_754, "automatic_ai_confirmation" to R.string.copy_ai_follow_up_frame, "scheduled" to R.string.copy_scheduled_capture, "manual" to R.string.copy_manual_capture, "unknown" to R.string.copy_captures
        )
        private fun label(value: String, fallback: String = "—"): String = labels[value]?.let { text(it) } ?: fallback
    }
}
