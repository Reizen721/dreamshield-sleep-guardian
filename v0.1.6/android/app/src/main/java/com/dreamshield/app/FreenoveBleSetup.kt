package com.dreamshield.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.*
import com.espressif.provisioning.security.Security1
import com.google.protobuf.ByteString
import espressif.Constants.Status.Success
import espressif.NetworkConfig.*
import espressif.NetworkCtrl.*
import espressif.NetworkScan.*
import espressif.NetworkConstants.*
import org.json.JSONObject

internal data class NearbyFreenove(val address: String, val name: String, val rssi: Int)
internal data class SetupWifi(val ssid: String, val rssi: Int, val auth: Int)

/** Only Freenove provisioning lives here. No PC, IR, camera or cloud commands. */
@SuppressLint("MissingPermission")
internal class FreenoveBleSetup(private val activity: MainActivity, private val confirmed: (JSONObject) -> Unit) {
    var devices by mutableStateOf<List<NearbyFreenove>>(emptyList()); private set
    var networks by mutableStateOf<List<SetupWifi>>(emptyList()); private set
    var selectedAddress by mutableStateOf("")
    var scanning by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var connected by mutableStateOf(false); private set
    var feedback by mutableStateOf(text(R.string.copy_move_closer_to_freenove_to_connect)); private set
    var inputEpoch by mutableStateOf(0); private set
    var deviceId by mutableStateOf(""); private set
    private val main = Handler(Looper.getMainLooper())
    private val transport = BleGattTransport()
    private val actualDevices = mutableMapOf<String, BluetoothDevice>()
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var security: Security1? = null
    private var epoch = 0
    private var active = true
    private val scanPolicy = BleScanPolicy()
    private var retryScanAfter = 0L
    private var deadline = 0L
    private var expectedSsid = ""
    private var initialState = ""
    private var persistentBle = false
    var savedNetworkName by mutableStateOf(""); private set
    private val infoRequest = "{\"action\":\"info\"}".toByteArray(Charsets.UTF_8)

    fun startScan() {
        if (!active) return
        if (scanning) return
        if (SystemClock.elapsedRealtime() < retryScanAfter) { feedback = text(R.string.copy_wait_30_seconds_before_scanning_again); return }
        cancel("")
        if (!runCatching { activity.ensureBleAccess() }.getOrDefault(false)) { feedback = text(R.string.copy_turn_on_bluetooth_and_allow_nearby_devices); return }
        val adapter = activity.getSystemService(BluetoothManager::class.java)?.adapter
        try {
            val current = adapter?.bluetoothLeScanner ?: run { feedback = text(R.string.copy_bluetooth_le_is_unavailable); return }
            devices = emptyList(); actualDevices.clear(); selectedAddress = ""
            val token = epoch
            val callback = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) { acceptScanResult(result, token) }
                override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { acceptScanResult(it, token) } }
                override fun onScanFailed(errorCode: Int) { main.post {
                    if (token == epoch) {
                        if (errorCode == 6) retryScanAfter = SystemClock.elapsedRealtime() + 30_000
                        fail(when (errorCode) {
                            6 -> text(R.string.copy_android_limited_scanning_try_again_in_30_seconds)
                            2, 3 -> text(R.string.copy_toggle_bluetooth_and_scan_again)
                            else -> text(R.string.copy_scan_incomplete_try_again)
                        })
                    }
                } }
            }
            scanner = current; scanCallback = callback; scanning = true
            feedback = text(R.string.copy_searching_for_freenove)
            current.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0).build(), callback)
            main.postDelayed({ if (token == epoch) {
                stopScan()
                feedback = if (devices.isEmpty()) text(R.string.copy_no_board_found_power_on_freenove_and_check_firmwar) else text(R.string.copy_select_your_freenove_to_connect)
            } }, 25_000)
        } catch (_: Exception) { fail(text(R.string.copy_check_bluetooth_and_nearby_device_access)) }
    }
    private fun acceptScanResult(result: ScanResult, token: Int) {
        main.post {
            if (token != epoch || !active || !scanning) return@post
            try {
                val advertised = result.scanRecord?.deviceName.orEmpty()
                val cached = runCatching { result.device.name.orEmpty() }.getOrDefault("")
                val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == BleGattTransport.SERVICE } == true
                if (!BleScanPolicy.isCandidate(advertised.ifBlank { cached }, hasService)) return@post
                val address = result.device.address
                actualDevices[address] = result.device
                val name = BleScanPolicy.displayName(advertised, cached, address)
                devices = (devices.filterNot { it.address == address } + NearbyFreenove(address, name, result.rssi))
                    .sortedByDescending { it.rssi }.take(20)
                if (selectedAddress.isEmpty()) selectedAddress = address
                feedback = text(R.string.copy_board_found_select_it_to_connect)
            } catch (_: SecurityException) { fail(text(R.string.copy_allow_nearby_device_access_and_scan_again)) }
        }
    }
    fun accessResult(granted: Boolean) {
        scanPolicy.accessResult(granted)
        if (granted) resumeApprovedScan()
        else feedback = text(R.string.copy_allow_nearby_devices_in_settings_android_10_and_11)
    }
    private fun resumeApprovedScan() {
        main.post { if (scanPolicy.consumeApprovedScan()) startScan() }
    }
    private fun stopScan() {
        try { scanCallback?.let { scanner?.stopScan(it) } } catch (_: Exception) {}
        scanner = null; scanCallback = null; scanning = false
    }
    fun connect() {
        if (!active || busy) return
        val device = actualDevices[selectedAddress] ?: run { feedback = text(R.string.copy_search_for_and_select_a_freenove); return }
        stopScan(); main.removeCallbacksAndMessages(null); epoch++
        val token = epoch
        security?.clear(); security = Security1(); inputEpoch++
        connected = false; busy = true; feedback = text(R.string.copy_connecting_securely)
        transport.connect(activity, device, {
            if (token == epoch) try {
                transport.exchange("prov-session", security!!.getNextRequestInSession(null)) { first ->
                    guarded(token) {
                        val second = security!!.getNextRequestInSession(first) ?: error("handshake")
                        transport.exchange("prov-session", second) { last ->
                            guarded(token) {
                                check(security!!.getNextRequestInSession(last) == null)
                                readInfo { info ->
                                    check(BleSetupPolicy.identity(info.optString("board"), info.optInt("setup_protocol"), info.optString("device_id")))
                                    check(info.optString("pairing_mode") == "no_code")
                                    deviceId = info.getString("device_id"); initialState = info.optString("provisioning_state")
                                    persistentBle = info.optBoolean("persistent_ble")
                                    savedNetworkName = if (info.optBoolean("configured") && info.optBoolean("wifi_connected") &&
                                        BleSetupPolicy.privateIp(info.optString("ip_address"))) info.optString("ssid") else ""
                                    connected = true; busy = false
                                    feedback = if (savedNetworkName.isNotEmpty()) text(R.string.copy_freenove_rejoined_its_saved_wi_fi) else text(R.string.copy_freenove_connected_choose_a_2_4_ghz_network)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) { fail(text(R.string.copy_update_freenove_to_the_latest_code_free_firmware_a)) }
        }, { if (token == epoch) fail(text(R.string.copy_bluetooth_disconnected_or_timed_out_scan_again)) })
    }
    private fun guarded(token: Int = epoch, action: () -> Unit) {
        if (token != epoch || !active) return
        try { action() } catch (_: Exception) { fail(text(R.string.copy_could_not_verify_the_board_check_its_firmware_and)) }
    }
    private fun exchange(endpoint: String, bytes: ByteArray, response: (ByteArray) -> Unit) {
        val token = epoch
        val encrypted = security!!.encrypt(bytes)
        transport.exchange(endpoint, encrypted) { data -> guarded(token) { response(security!!.decrypt(data)) } }
    }
    private fun readInfo(response: (JSONObject) -> Unit) = exchange("dreamshield-info", infoRequest) {
        response(JSONObject(it.toString(Charsets.UTF_8)))
    }
    fun scanWifi() {
        if (!active || !connected || busy) return
        busy = true; networks = emptyList(); feedback = text(R.string.copy_scanning_for_2_4_ghz_wi_fi); deadline = SystemClock.elapsedRealtime() + 25_000
        guarded {
            exchange("prov-scan", NetworkScanPayload.newBuilder().setMsg(NetworkScanMsgType.TypeCmdScanWifiStart)
                .setCmdScanWifiStart(CmdScanWifiStart.newBuilder().setBlocking(false).setPassive(false).setGroupChannels(5).setPeriodMs(120)).build().toByteArray()) {
                val reply = NetworkScanPayload.parseFrom(it)
                check(reply.msg == NetworkScanMsgType.TypeRespScanWifiStart && reply.status == Success && reply.hasRespScanWifiStart())
                pollScan()
            }
        }
    }
    private fun pollScan() {
        if (SystemClock.elapsedRealtime() > deadline) { busy = false; feedback = text(R.string.copy_wi_fi_scan_timed_out_enter_the_name_or_scan_again); return }
        exchange("prov-scan", NetworkScanPayload.newBuilder().setMsg(NetworkScanMsgType.TypeCmdScanWifiStatus)
            .setCmdScanWifiStatus(CmdScanWifiStatus.getDefaultInstance()).build().toByteArray()) {
            val reply = NetworkScanPayload.parseFrom(it)
            check(reply.msg == NetworkScanMsgType.TypeRespScanWifiStatus && reply.status == Success && reply.hasRespScanWifiStatus())
            if (reply.respScanWifiStatus.scanFinished) readScan(0, reply.respScanWifiStatus.resultCount.coerceIn(0, 40))
            else later(500) { pollScan() }
        }
    }
    private fun readScan(index: Int, count: Int) {
        if (index >= count) { busy = false; feedback = if (networks.isEmpty()) text(R.string.copy_no_wi_fi_found_enter_a_2_4_ghz_network_name) else text(R.string.copy_select_a_network_or_enter_a_hidden_name); return }
        val batch = minOf(4, count - index)
        exchange("prov-scan", NetworkScanPayload.newBuilder().setMsg(NetworkScanMsgType.TypeCmdScanWifiResult)
            .setCmdScanWifiResult(CmdScanWifiResult.newBuilder().setStartIndex(index).setCount(batch)).build().toByteArray()) {
            val reply = NetworkScanPayload.parseFrom(it)
            check(reply.msg == NetworkScanMsgType.TypeRespScanWifiResult && reply.status == Success && reply.hasRespScanWifiResult())
            val found = reply.respScanWifiResult.entriesList.filter { it.channel in 1..14 && it.ssid.isValidUtf8 && !it.ssid.isEmpty }
                .map { SetupWifi(it.ssid.toStringUtf8(), it.rssi, it.authValue) }
            networks = (networks + found).sortedByDescending { it.rssi }.distinctBy { it.ssid }
            readScan(index + batch, count)
        }
    }
    fun provision(ssid: String, password: String, open: Boolean) {
        if (!active || !connected || busy) return
        try { BleSetupPolicy.wifi(ssid, password, open) } catch (error: IllegalArgumentException) { feedback = error.message.orEmpty(); return }
        busy = true; expectedSsid = ssid; inputEpoch++
        feedback = text(R.string.copy_sending_wi_fi_settings_over_encrypted_bluetooth)
        guarded {
            val config = NetworkConfigPayload.newBuilder().setMsg(NetworkConfigMsgType.TypeCmdSetWifiConfig)
                .setCmdSetWifiConfig(CmdSetWifiConfig.newBuilder().setSsid(ByteString.copyFromUtf8(ssid)).setPassphrase(ByteString.copyFromUtf8(password))).build().toByteArray()
            val sendConfig = {
                exchange("prov-config", config) {
                    config.fill(0)
                    val reply = NetworkConfigPayload.parseFrom(it)
                    check(reply.msg == NetworkConfigMsgType.TypeRespSetWifiConfig && reply.hasRespSetWifiConfig() && reply.respSetWifiConfig.status == Success)
                    applyWifi()
                }
            }
            if (persistentBle) {
                exchange("dreamshield-info", "{\"action\":\"prepare_wifi\"}".toByteArray(Charsets.UTF_8)) {
                    val prepared = JSONObject(it.toString(Charsets.UTF_8))
                    check(prepared.optBoolean("wifi_update_prepared") && prepared.optString("device_id") == deviceId)
                    savedNetworkName = ""
                    sendConfig()
                }
            } else if (initialState in setOf("wifi_auth_error", "wifi_ap_not_found", "wifi_failed")) {
                exchange("prov-ctrl", NetworkCtrlPayload.newBuilder().setMsg(NetworkCtrlMsgType.TypeCmdCtrlWifiReset)
                    .setCmdCtrlWifiReset(CmdCtrlWifiReset.getDefaultInstance()).build().toByteArray()) {
                    val reply = NetworkCtrlPayload.parseFrom(it)
                    check(reply.msg == NetworkCtrlMsgType.TypeRespCtrlWifiReset && reply.status == Success && reply.hasRespCtrlWifiReset())
                    sendConfig()
                }
            } else sendConfig()
        }
    }
    fun useSavedNetwork() {
        if (!active || !connected || busy || savedNetworkName.isEmpty()) return
        val expected = savedNetworkName
        busy = true
        feedback = text(R.string.copy_checking_the_saved_connection)
        guarded { readInfo { info ->
            check(BleSetupPolicy.restored(info.optString("board"), info.optInt("setup_protocol"), info.optString("device_id"), deviceId,
                info.optString("ip_address"), info.optString("ssid"), expected, info.optBoolean("configured"), info.optBoolean("wifi_connected")))
            acceptReceipt(info)
        } }
    }
    private fun acceptReceipt(info: JSONObject) {
        val receipt = JSONObject().put("device_id", deviceId).put("board", info.getString("board")).put("setup_protocol", 2).put("pairing_mode", "no_code")
            .put("ip_address", info.getString("ip_address")).put("ssid", info.getString("ssid")).put("firmware_version", info.optString("firmware_version"))
        cancel(text(R.string.copy_wi_fi_saved_freenove_will_reconnect_after_restart))
        confirmed(receipt)
    }
    private fun applyWifi() = exchange("prov-config", NetworkConfigPayload.newBuilder().setMsg(NetworkConfigMsgType.TypeCmdApplyWifiConfig)
        .setCmdApplyWifiConfig(CmdApplyWifiConfig.getDefaultInstance()).build().toByteArray()) {
        val reply = NetworkConfigPayload.parseFrom(it)
        check(reply.msg == NetworkConfigMsgType.TypeRespApplyWifiConfig && reply.hasRespApplyWifiConfig() && reply.respApplyWifiConfig.status == Success)
        deadline = SystemClock.elapsedRealtime() + 90_000
        feedback = text(R.string.copy_joining_wi_fi_and_checking_the_ip)
        later(1000) { pollWifi() }
    }
    private fun pollWifi() {
        if (SystemClock.elapsedRealtime() > deadline) { fail(text(R.string.copy_setup_timed_out_reopen_provisioning_to_check)); return }
        exchange("prov-config", NetworkConfigPayload.newBuilder().setMsg(NetworkConfigMsgType.TypeCmdGetWifiStatus)
            .setCmdGetWifiStatus(CmdGetWifiStatus.getDefaultInstance()).build().toByteArray()) {
            val reply = NetworkConfigPayload.parseFrom(it)
            check(reply.msg == NetworkConfigMsgType.TypeRespGetWifiStatus && reply.hasRespGetWifiStatus() && reply.respGetWifiStatus.status == Success)
            val result = reply.respGetWifiStatus
            when (result.wifiStaState) {
                WifiStationState.Connected -> readCommitted()
                WifiStationState.ConnectionFailed -> fail(if (result.wifiFailReason == WifiConnectFailedReason.AuthError)
                    text(R.string.copy_wi_fi_password_rejected_reconnect_and_try_again) else text(R.string.copy_network_not_found_check_the_2_4_ghz_name_and_signa))
                else -> later(1000) { pollWifi() }
            }
        }
    }
    private fun readCommitted(): Unit = readInfo { info ->
        if (BleSetupPolicy.confirmed(info.optString("board"), info.optInt("setup_protocol"), info.optString("device_id"), deviceId,
                info.optString("ip_address"), info.optString("ssid"), expectedSsid,
                info.optBoolean("configured"), info.optBoolean("candidate_committed"), info.optBoolean("wifi_connected"))) {
            acceptReceipt(info)
        } else if (SystemClock.elapsedRealtime() > deadline) fail(text(R.string.copy_save_not_confirmed_reconnect_to_check))
        else later(500) { readCommitted() }
    }
    private fun later(delay: Long, action: () -> Unit) {
        val token = epoch
        main.postDelayed({ guarded(token, action) }, delay)
    }
    private fun fail(text: String) = cancel(text)
    fun cancel(text: String = text(R.string.copy_bluetooth_disconnected_the_board_may_still_be_chec), preservePendingScan: Boolean = false) {
        if (!preservePendingScan) scanPolicy.cancelPending()
        epoch++; main.removeCallbacksAndMessages(null); stopScan(); transport.close(); security?.clear(); security = null
        busy = false; connected = false; deviceId = ""; expectedSsid = ""; initialState = ""; persistentBle = false; savedNetworkName = ""; networks = emptyList(); inputEpoch++
        if (text.isNotEmpty()) feedback = text
    }
    fun lifecycle(foreground: Boolean) {
        active = foreground
        scanPolicy.foreground(foreground)
        if (!foreground) cancel(if (busy || connected || scanning) text(R.string.copy_bluetooth_disconnected_scan_again_when_you_return) else "", preservePendingScan = true)
        else resumeApprovedScan()
    }
}
