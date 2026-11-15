"""Local-owner BLE provisioning, with no credentials on disk or in logs.

Importing this module does not start Bluetooth, a thread, or an operation.
HTTP handlers must authorize the local owner before calling ``submit``.
All GATT I/O and the stateful Security 1 cipher run on one asyncio thread.
"""
from __future__ import annotations

import asyncio
import copy
import importlib.util
import ipaddress
import json
import re
import secrets
import sys
import threading
import time
from typing import Any


SERVICE_UUID = "021a9004-0382-4aea-bff4-6b3f1c5adfb4"
DEVICE_NAME_PATTERN = re.compile(r"DreamShield-[0-9A-F]{6}\Z")
MODEL_NAME = "DreamShield RS081-dev"
SCAN_TTL_SECONDS = 120
SESSION_TTL_SECONDS = 300
MAX_SCAN_DEVICES = 32
MAX_WIFI_NETWORKS = 64
MAX_PACKET_BYTES = 8192
GATT_TIMEOUT_SECONDS = 12
OPERATION_TIMEOUTS = {
    "scan": 20, "connect": 45, "scan_wifi": 35,
    "provision": 100, "disconnect": 15,
}

ERROR_MESSAGES = {
    "dependencies_missing": "请先安装 ble_requirements.txt 中的蓝牙配网依赖。",
    "bluetooth_error": "蓝牙操作失败，请检查电脑蓝牙和设备配网窗口后重试。",
    "operation_timeout": "配网操作超时，请重新打开设备配网窗口后重试。",
    "authentication_failed": "设备认证失败，请核对这台设备的配网验证码。",
    "protocol_error": "设备配网协议或响应不兼容，请检查固件版本。",
    "wifi_auth_failed": "Wi-Fi 认证失败，请检查密码；可直接重新提交。",
    "wifi_network_not_found": "设备未找到该 Wi-Fi，请使用可见的 2.4 GHz 网络。",
    "wifi_failed": "设备连接 Wi-Fi 失败；可重新扫描和提交。",
    "session_expired": "蓝牙配网会话已过期，请重新开启设备配网窗口。",
    "device_disconnected": "蓝牙连接已断开，请重新开启配网窗口并连接。",
}


class BleProvisioningInputError(ValueError):
    """Safe input error: the message never includes the submitted value."""


class BleProvisioningBusy(RuntimeError):
    """Another operation is active; only cancellation is accepted."""


class _SafeFailure(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def dependencies_available() -> bool:
    try:
        return all(importlib.util.find_spec(name) is not None
                   for name in ("bleak", "google.protobuf", "cryptography"))
    except (ModuleNotFoundError, ValueError):
        return False


def validate_credentials(ssid: Any, password: Any) -> None:
    if not isinstance(ssid, str) or not isinstance(password, str):
        raise BleProvisioningInputError("SSID 和密码必须是文本。")
    try:
        ssid_bytes, password_bytes = ssid.encode("utf-8"), password.encode("utf-8")
    except UnicodeError:
        raise BleProvisioningInputError("Wi-Fi 信息包含不支持的字符。") from None
    if not 1 <= len(ssid_bytes) <= 32 or any(ord(c) < 32 or ord(c) == 127 for c in ssid):
        raise BleProvisioningInputError("SSID 必须为 1–32 个 UTF-8 字节且不能含控制字符。")
    if any(ord(c) < 32 or ord(c) == 127 for c in password):
        raise BleProvisioningInputError("Wi-Fi 密码不能含控制字符。")
    # The bundled Espressif network_provisioning SetConfig endpoint requires
    # a trailing NUL in password[64]; raw 64-hex PSKs are rejected upstream.
    if not 8 <= len(password_bytes) <= 63:
        raise BleProvisioningInputError("首版仅支持密码为 8–63 个 UTF-8 字节的家庭 Wi-Fi，不支持开放网络或 64 位十六进制密钥。")


def _safe_text(value: Any, maximum: int) -> str:
    if not isinstance(value, str) or len(value) > maximum:
        raise _SafeFailure("protocol_error")
    if any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise _SafeFailure("protocol_error")
    return value


def validate_identity(value: Any, expected_name: str) -> dict[str, Any]:
    """Only allowlisted identity data crosses the BLE-to-HTTP boundary."""
    if not isinstance(value, dict):
        raise _SafeFailure("protocol_error")
    device_id = _safe_text(value.get("device_id"), 80)
    boot_id = _safe_text(value.get("boot_id"), 80)
    model = _safe_text(value.get("model"), 60)
    hostname = _safe_text(value.get("hostname"), 80)
    if (not device_id or not boot_id or model != MODEL_NAME
            or not re.fullmatch(r"[A-Za-z0-9_.:-]+", device_id)
            or not re.fullmatch(r"[A-Za-z0-9_.:-]+", boot_id)
            or not re.fullmatch(r"[A-Za-z0-9-]+", hostname)):
        raise _SafeFailure("protocol_error")
    # A unique device_id is cryptographically read from the authenticated
    # session. The advertisement name alone is deliberately not trusted.
    result = {"device_id": device_id, "boot_id": boot_id, "model": model,
              "hostname": hostname, "device_name": expected_name}
    address = value.get("ip_address", "")
    if address and address != "0.0.0.0":
        try:
            parsed = ipaddress.IPv4Address(address)
        except (ipaddress.AddressValueError, TypeError):
            raise _SafeFailure("protocol_error") from None
        if not any(parsed in block for block in (
                ipaddress.IPv4Network("10.0.0.0/8"),
                ipaddress.IPv4Network("172.16.0.0/12"),
                ipaddress.IPv4Network("192.168.0.0/16"))):
            raise _SafeFailure("protocol_error")
        result["ip_address"] = str(parsed)
    else:
        result["ip_address"] = ""
    result["configured"] = value.get("configured") is True
    result["wifi_connected"] = value.get("wifi_connected") is True
    result["candidate_committed"] = value.get("candidate_committed") is True
    result["provisioning_state"] = _safe_text(value.get("provisioning_state", ""), 60)
    return result


class BleakProvisioningTransport:
    """Bleak transport: selected recent BLEDevice, fixed service/endpoints."""
    def __init__(self):
        self.client = None
        self.endpoints: dict[str, Any] = {}

    async def scan(self) -> list[tuple[Any, str, int]]:
        from bleak import BleakScanner
        discovery = await BleakScanner.discover(
            timeout=8.0, return_adv=True, service_uuids=[SERVICE_UUID])
        found = []
        for device, advertisement in discovery.values():
            name = advertisement.local_name or device.name or ""
            service_uuids = [s.lower() for s in advertisement.service_uuids]
            if DEVICE_NAME_PATTERN.fullmatch(name) and SERVICE_UUID in service_uuids:
                found.append((device, name, int(advertisement.rssi)))
        return sorted(found, key=lambda item: item[2], reverse=True)[:MAX_SCAN_DEVICES]

    async def connect(self, device: Any) -> None:
        from bleak import BleakClient
        self.client = BleakClient(device, timeout=20.0, services=[SERVICE_UUID],
                                  winrt={"use_cached_services": False})
        try:
            await self.client.connect()
            service = self.client.services.get_service(SERVICE_UUID)
            if service is None:
                raise _SafeFailure("protocol_error")
            allowed = {"prov-session", "prov-config", "prov-scan", "prov-ctrl",
                       "proto-ver", "dreamshield-info"}
            for characteristic in service.characteristics:
                for descriptor in characteristic.descriptors:
                    if descriptor.uuid.lower() != "00002901-0000-1000-8000-00805f9b34fb":
                        continue
                    data = await asyncio.wait_for(
                        self.client.read_gatt_descriptor(descriptor.handle), GATT_TIMEOUT_SECONDS)
                    if len(data) > 80:
                        raise _SafeFailure("protocol_error")
                    name = bytes(data).decode("ascii", errors="strict").rstrip("\x00")
                    if name in allowed:
                        if name in self.endpoints:
                            raise _SafeFailure("protocol_error")
                        self.endpoints[name] = characteristic
            if not allowed.issubset(self.endpoints):
                raise _SafeFailure("protocol_error")
        except BaseException:
            await self.disconnect()
            raise

    async def exchange(self, endpoint: str, data: bytes) -> bytes:
        if self.client is None or not self.client.is_connected:
            raise _SafeFailure("device_disconnected")
        characteristic = self.endpoints.get(endpoint)
        if characteristic is None or not 0 < len(data) <= MAX_PACKET_BYTES:
            raise _SafeFailure("protocol_error")
        await asyncio.wait_for(self.client.write_gatt_char(characteristic, data, response=True),
                               GATT_TIMEOUT_SECONDS)
        response = bytes(await asyncio.wait_for(self.client.read_gatt_char(characteristic),
                                               GATT_TIMEOUT_SECONDS))
        if not 0 < len(response) <= MAX_PACKET_BYTES:
            raise _SafeFailure("protocol_error")
        return response

    async def disconnect(self) -> None:
        client, self.client = self.client, None
        self.endpoints.clear()
        if client is not None:
            try:
                await asyncio.wait_for(client.disconnect(), 6)
            except Exception:
                pass  # No backend exception text may contain credentials.


class EspProvisioningProtocol:
    """Upstream Security1 + upstream generated protobuf messages, no CLI."""
    def __init__(self, transport: Any):
        from vendor.esp_prov import proto
        self.proto = proto
        self.transport = transport
        self.security = None

    async def authenticate(self, pop: str) -> None:
        from vendor.esp_prov.security.security1 import Security1
        self.security = Security1(pop, verbose=False)
        try:
            request = self.security.security_session(None)
            for index in range(2):
                response = await self.transport.exchange("prov-session", request.encode("latin-1"))
                parsed = self.proto.session_pb2.SessionData()
                parsed.ParseFromString(response)
                expected = (self.proto.sec1_pb2.Session_Response0 if index == 0
                            else self.proto.sec1_pb2.Session_Response1)
                field = "sr0" if index == 0 else "sr1"
                if (parsed.sec_ver != self.proto.session_pb2.SecScheme1
                        or parsed.WhichOneof("proto") != "sec1"
                        or parsed.sec1.msg != expected
                        or not parsed.sec1.HasField(field)
                        or getattr(parsed.sec1, field).status != 0):
                    raise _SafeFailure("authentication_failed")
                request = self.security.security_session(response.decode("latin-1"))
            if request is not None:
                raise _SafeFailure("authentication_failed")
        except _SafeFailure:
            raise
        except Exception:
            raise _SafeFailure("authentication_failed") from None
        finally:
            # Retain cipher state, not the user's proof string.
            if self.security is not None:
                self.security.pop = b""
            pop = ""

    async def _exchange(self, endpoint: str, request: Any, response_type: Any,
                        expected_msg: int, field: str) -> Any:
        if self.security is None:
            raise _SafeFailure("authentication_failed")
        data = self.security.encrypt_data(request.SerializeToString())
        response = await self.transport.exchange(endpoint, data)
        parsed = response_type()
        try:
            parsed.ParseFromString(self.security.decrypt_data(response))
        except Exception:
            raise _SafeFailure("protocol_error") from None
        if parsed.msg != expected_msg or not parsed.HasField(field):
            raise _SafeFailure("protocol_error")
        status = parsed.status if hasattr(parsed, "status") else getattr(parsed, field).status
        if status != 0:
            raise _SafeFailure("protocol_error")
        return parsed

    async def identity(self) -> dict[str, Any]:
        if self.security is None:
            raise _SafeFailure("authentication_failed")
        data = self.security.encrypt_data(b'{"action":"info"}')
        response = await self.transport.exchange("dreamshield-info", data)
        decrypted = self.security.decrypt_data(response)
        if len(decrypted) > 4096:
            raise _SafeFailure("protocol_error")
        try:
            return json.loads(decrypted.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError):
            raise _SafeFailure("protocol_error") from None

    async def scan_wifi(self) -> list[dict[str, Any]]:
        pb = self.proto.wifi_scan_pb2
        request = pb.WiFiScanPayload(msg=pb.TypeCmdScanStart)
        request.cmd_scan_start.blocking = False
        request.cmd_scan_start.group_channels = 5
        request.cmd_scan_start.period_ms = 120
        await self._exchange("prov-scan", request, pb.WiFiScanPayload,
                             pb.TypeRespScanStart, "resp_scan_start")
        count = 0
        for _ in range(35):
            request = pb.WiFiScanPayload(msg=pb.TypeCmdScanStatus)
            request.cmd_scan_status.SetInParent()
            response = await self._exchange("prov-scan", request, pb.WiFiScanPayload,
                                            pb.TypeRespScanStatus, "resp_scan_status")
            if response.resp_scan_status.scan_finished:
                count = min(int(response.resp_scan_status.result_count), MAX_WIFI_NETWORKS)
                break
            await asyncio.sleep(0.5)
        else:
            raise _SafeFailure("operation_timeout")
        results = []
        for start in range(0, count, 5):
            request = pb.WiFiScanPayload(msg=pb.TypeCmdScanResult)
            request.cmd_scan_result.start_index = start
            request.cmd_scan_result.count = min(5, count - start)
            response = await self._exchange("prov-scan", request, pb.WiFiScanPayload,
                                            pb.TypeRespScanResult, "resp_scan_result")
            if len(response.resp_scan_result.entries) > 5:
                raise _SafeFailure("protocol_error")
            for entry in response.resp_scan_result.entries:
                try:
                    ssid = bytes(entry.ssid).rstrip(b"\0").decode("utf-8", errors="strict")
                except UnicodeError:
                    continue  # Non-UTF8 SSIDs are not silently changed/replayed.
                if not ssid or len(ssid.encode("utf-8")) > 32 or any(ord(c) < 32 for c in ssid):
                    continue
                if not 1 <= entry.channel <= 14:
                    continue
                try:
                    auth = self.proto.wifi_constants_pb2.WifiAuthMode.Name(entry.auth)
                except ValueError:
                    auth = "unsupported"
                results.append({"ssid": ssid, "rssi": int(entry.rssi),
                                "channel": int(entry.channel), "auth": auth,
                                "supported": auth in {"WPA_PSK", "WPA2_PSK", "WPA_WPA2_PSK",
                                                       "WPA3_PSK", "WPA2_WPA3_PSK"}})
        # Dedupe SSIDs only for UI selection, preserving strongest network.
        unique = {}
        for row in sorted(results, key=lambda item: item["rssi"], reverse=True):
            unique.setdefault(row["ssid"], row)
        return list(unique.values())[:MAX_WIFI_NETWORKS]

    async def configure(self, ssid: str, password: str) -> None:
        pb = self.proto.wifi_config_pb2
        request = pb.WiFiConfigPayload(msg=pb.TypeCmdSetConfig)
        request.cmd_set_config.ssid = ssid.encode("utf-8")
        request.cmd_set_config.passphrase = password.encode("utf-8")
        try:
            await self._exchange("prov-config", request, pb.WiFiConfigPayload,
                                 pb.TypeRespSetConfig, "resp_set_config")
        finally:
            request.Clear()
            password = ""
        request = pb.WiFiConfigPayload(msg=pb.TypeCmdApplyConfig)
        request.cmd_apply_config.SetInParent()
        await self._exchange("prov-config", request, pb.WiFiConfigPayload,
                             pb.TypeRespApplyConfig, "resp_apply_config")

    async def wifi_status(self) -> dict[str, Any]:
        pb = self.proto.wifi_config_pb2
        request = pb.WiFiConfigPayload(msg=pb.TypeCmdGetStatus)
        request.cmd_get_status.SetInParent()
        response = await self._exchange("prov-config", request, pb.WiFiConfigPayload,
                                        pb.TypeRespGetStatus, "resp_get_status")
        state = response.resp_get_status.sta_state
        if state == self.proto.wifi_constants_pb2.ConnectionFailed:
            code = "wifi_failed"
            if response.resp_get_status.WhichOneof("state") == "fail_reason":
                code = {0: "wifi_auth_failed", 1: "wifi_network_not_found"}.get(
                    response.resp_get_status.fail_reason, "wifi_failed")
            return {"state": "failed", "error_code": code}
        if state == self.proto.wifi_constants_pb2.Connected:
            return {"state": "connected",
                    "ip_address": response.resp_get_status.connected.ip4_addr}
        if state == self.proto.wifi_constants_pb2.Connecting:
            return {"state": "connecting"}
        return {"state": "disconnected"}

    async def reset_failed(self) -> None:
        pb = self.proto.wifi_ctrl_pb2
        request = pb.WiFiCtrlPayload(msg=pb.TypeCmdCtrlReset)
        request.cmd_ctrl_reset.SetInParent()
        await self._exchange("prov-ctrl", request, pb.WiFiCtrlPayload,
                             pb.TypeRespCtrlReset, "resp_ctrl_reset")

    def clear(self) -> None:
        self.security = None


class BleProvisioningController:
    """Nonblocking serialized operations for a threaded local HTTP server.

    ``submit(action, payload)`` immediately returns a public status snapshot.
    A caller polls ``status()``. Only fresh opaque scan IDs may be connected.
    ``available`` means dependencies importable, not verified hardware health.
    Injected factories are for deterministic offline tests, never demo fallbacks.
    """
    def __init__(self, *, transport_factory: Any = None, protocol_factory: Any = None,
                 clock: Any = time.monotonic, operation_timeouts: dict | None = None):
        self._available = (True if transport_factory is not None else dependencies_available())
        self._transport_factory = transport_factory or BleakProvisioningTransport
        self._protocol_factory = protocol_factory or EspProvisioningProtocol
        self._clock = clock
        self._timeouts = dict(OPERATION_TIMEOUTS)
        if operation_timeouts:
            self._timeouts.update(operation_timeouts)
        self._lock = threading.RLock()
        self._loop = None
        self._thread = None
        self._future = None
        self._active_task = None
        self._cancel_requested = False
        self._closed = False
        self._transport = None
        self._protocol = None
        self._scan_records: dict[str, tuple[Any, str, float]] = {}
        self._session_started = 0.0
        self._needs_failure_reset = False
        self._identity = None
        self._state = {"available": self._available, "busy": False, "operation": None,
                       "state": "idle" if self._available else "unavailable",
                       "message": "尚未开始蓝牙配网。" if self._available else ERROR_MESSAGES["dependencies_missing"],
                       "error_code": None if self._available else "dependencies_missing",
                       "security_version": 1, "devices": [], "wifi_networks": [],
                       "connected_device": None, "result": None}

    def status(self) -> dict[str, Any]:
        with self._lock:
            return copy.deepcopy(self._state)

    def _update(self, **values: Any) -> None:
        with self._lock:
            self._state.update(values)

    def _start_worker(self) -> None:
        if self._thread is not None:
            return
        ready = threading.Event()
        def worker():
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            if sys.platform == "win32" and self._available:
                # Dedicated nongui thread: do not use allow_sta() to hide a
                # broken WinRT apartment. This performs no BLE operation.
                try:
                    from bleak.backends.winrt.util import uninitialize_sta
                    uninitialize_sta()
                except ImportError:
                    pass
            self._loop = loop
            ready.set()
            loop.create_task(self._idle_watchdog())
            loop.run_forever()
            pending = asyncio.all_tasks(loop)
            for task in pending:
                task.cancel()
            if pending:
                loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
            loop.close()
        self._thread = threading.Thread(target=worker, name="DreamShield-BLE", daemon=True)
        self._thread.start()
        if not ready.wait(5):
            raise RuntimeError("蓝牙配网工作线程启动失败。")

    def submit(self, action: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        if not isinstance(action, str) or action not in {*OPERATION_TIMEOUTS, "cancel"}:
            raise BleProvisioningInputError("未知的蓝牙配网操作。")
        if payload is None:
            payload = {}
        if not isinstance(payload, dict):
            raise BleProvisioningInputError("配网请求格式无效。")
        allowed_keys = {"connect": {"device_id", "id", "pop"},
                        "provision": {"ssid", "password"}}.get(action, set())
        if set(payload) - allowed_keys or ("device_id" in payload and "id" in payload):
            raise BleProvisioningInputError("配网请求包含不支持或冲突的字段。")
        if action == "cancel":
            return self.cancel()
        safe_payload = {}
        if action == "connect":
            selected = payload.get("device_id", payload.get("id", ""))
            pop = payload.get("pop", "")
            if (not isinstance(selected, str) or not re.fullmatch(r"[A-Za-z0-9_-]{20,80}", selected)
                    or not isinstance(pop, str) or not re.fullmatch(r"[0-9a-fA-F]{32}", pop)):
                raise BleProvisioningInputError("请选择扫描列表中的设备，并输入其 32 位十六进制配网验证码。")
            safe_payload = {"device_id": selected, "pop": pop}
        elif action == "provision":
            validate_credentials(payload.get("ssid"), payload.get("password"))
            safe_payload = {"ssid": payload["ssid"], "password": payload["password"]}
        with self._lock:
            if self._closed:
                safe_payload.clear()
                raise RuntimeError("蓝牙配网控制器已关闭。")
            if not self._available:
                safe_payload.clear()
                self._update(state="unavailable", error_code="dependencies_missing",
                             message=ERROR_MESSAGES["dependencies_missing"])
                return self.status()
            if self._state["busy"]:
                safe_payload.clear()
                raise BleProvisioningBusy("另一个蓝牙配网操作正在进行，请等待或取消。")
            if action == "connect":
                record = self._scan_records.get(safe_payload["device_id"])
                if record is None or self._clock() - record[2] >= SCAN_TTL_SECONDS:
                    safe_payload.clear()
                    raise BleProvisioningInputError("设备扫描结果已过期，请重新扫描并选择。")
            if action in {"scan_wifi", "provision"} and self._protocol is None:
                safe_payload.clear()
                raise BleProvisioningInputError("请先完成设备连接和配网验证码认证。")
            self._start_worker()
            self._cancel_requested = False
            self._update(busy=True, operation=action, error_code=None)
            self._future = asyncio.run_coroutine_threadsafe(self._run(action, safe_payload), self._loop)
            return self.status()

    async def _run(self, action: str, payload: dict[str, Any]) -> None:
        try:
            self._active_task = asyncio.current_task()
            if self._cancel_requested:
                raise asyncio.CancelledError
            await asyncio.wait_for(self._perform(action, payload), self._timeouts[action])
        except asyncio.CancelledError:
            await self._disconnect()
            self._update(state="cancelled", message="操作已取消，蓝牙连接已关闭。", error_code=None)
        except TimeoutError:
            await self._disconnect()
            self._update(state="error", error_code="operation_timeout",
                         message=ERROR_MESSAGES["operation_timeout"])
        except _SafeFailure as exc:
            code = exc.code
            if code.startswith("wifi_"):
                self._needs_failure_reset = True
            else:
                await self._disconnect()
            self._update(state="error", error_code=code,
                         message=ERROR_MESSAGES.get(code, ERROR_MESSAGES["protocol_error"]))
        except Exception:
            # Never stringify BLE/cryptography exceptions, which may embed
            # request bytes, SSIDs, or credentials; no traceback is published.
            await self._disconnect()
            self._update(state="error", error_code="bluetooth_error",
                         message=ERROR_MESSAGES["bluetooth_error"])
        finally:
            payload.clear()
            self._active_task = None
            self._update(busy=False, operation=None)

    async def _perform(self, action: str, payload: dict[str, Any]) -> None:
        if action == "disconnect":
            await self._disconnect()
            self._update(state="disconnected", message="蓝牙已断开；已确认的 Wi-Fi 不会被删除。")
            return
        if action == "scan":
            await self._disconnect()
            self._update(state="scanning", message="正在查找附近的 DreamShield 配网设备。",
                         devices=[], wifi_networks=[], result=None)
            transport = self._transport_factory()
            try:
                found = await transport.scan()
            finally:
                await transport.disconnect()
            now, records, devices = self._clock(), {}, []
            for device, name, rssi in found[:MAX_SCAN_DEVICES]:
                if not DEVICE_NAME_PATTERN.fullmatch(name):
                    continue
                token = secrets.token_urlsafe(24)
                records[token] = (device, name, now)
                devices.append({"id": token, "name": name, "rssi": int(rssi)})
            with self._lock:
                self._scan_records = records
            self._update(state="devices_found", devices=devices,
                         message="请选择设备并输入配网验证码。" if devices else "未找到设备，请在串口输入 p 打开配网窗口。")
            return
        if action == "connect":
            with self._lock:
                record = self._scan_records.get(payload["device_id"])
            if record is None or self._clock() - record[2] >= SCAN_TTL_SECONDS:
                raise _SafeFailure("session_expired")
            await self._disconnect()
            self._update(state="connecting", message="正在连接并验证这台设备。", result=None,
                         wifi_networks=[])
            self._transport = self._transport_factory()
            await self._transport.connect(record[0])
            self._protocol = self._protocol_factory(self._transport)
            await self._protocol.authenticate(payload.pop("pop"))
            self._identity = validate_identity(await self._protocol.identity(), record[1])
            self._session_started = self._clock()
            self._needs_failure_reset = False
            self._update(state="connected", connected_device={"id": payload["device_id"],
                         "name": record[1], "device_id": self._identity["device_id"],
                         "boot_id": self._identity["boot_id"], "model": self._identity["model"]},
                         message="设备认证成功；现在可扫描设备附近的 2.4 GHz Wi-Fi。")
            return
        if self._protocol is None or self._clock() - self._session_started >= SESSION_TTL_SECONDS:
            raise _SafeFailure("session_expired")
        if action == "scan_wifi":
            self._update(state="scanning_wifi", message="设备正在扫描附近的 2.4 GHz Wi-Fi。")
            networks = await self._protocol.scan_wifi()
            self._update(state="wifi_ready", wifi_networks=networks, message="请选择家庭 Wi-Fi 或手动输入隐藏 SSID。")
            return
        if action == "provision":
            self._update(state="configuring", message="正在加密发送 Wi-Fi 配置。", result=None)
            if self._needs_failure_reset:
                await self._protocol.reset_failed()
                self._needs_failure_reset = False
            ssid = payload["ssid"]
            await self._protocol.configure(ssid, payload.pop("password"))
            payload.clear()
            self._update(state="waiting_wifi", message="配置已发送，正在等待设备取得局域网 IP；这还不代表配网成功。")
            deadline = self._clock() + 75
            while self._clock() < deadline:
                status = await self._protocol.wifi_status()
                if status["state"] == "failed":
                    raise _SafeFailure(status.get("error_code", "wifi_failed"))
                if status["state"] == "connected":
                    name = self.status()["connected_device"]["name"]
                    identity = validate_identity(await self._protocol.identity(), name)
                    if (identity["device_id"] != self._identity["device_id"]
                            or identity["boot_id"] != self._identity["boot_id"]):
                        raise _SafeFailure("protocol_error")
                    # GOT_IP happens before the firmware loop can atomically
                    # commit NVS. Do not claim success for an old config or
                    # reject the harmless few-millisecond commit race.
                    if (not identity["candidate_committed"] or not identity["configured"]
                            or not identity["wifi_connected"] or not identity["ip_address"]
                            or identity["ip_address"] != status.get("ip_address")):
                        await asyncio.sleep(0.3)
                        continue
                    identity["ssid"] = ssid
                    self._update(state="provisioned", result=identity,
                                 message="设备已连接 Wi-Fi 并取得 IP；请再验证局域网服务并设置设备地址。")
                    await self._disconnect()
                    return
                await asyncio.sleep(1)
            raise _SafeFailure("operation_timeout")

    async def _disconnect(self) -> None:
        protocol, transport = self._protocol, self._transport
        self._protocol = None
        self._transport = None
        self._identity = None
        self._session_started = 0
        self._needs_failure_reset = False
        if protocol is not None:
            protocol.clear()
        if transport is not None:
            await transport.disconnect()
        self._update(connected_device=None)

    async def _idle_watchdog(self) -> None:
        while True:
            await asyncio.sleep(3)
            if (self._protocol is not None and not self.status()["busy"]
                    and self._clock() - self._session_started >= SESSION_TTL_SECONDS):
                await self._disconnect()
                self._update(state="error", error_code="session_expired",
                             message=ERROR_MESSAGES["session_expired"])

    def cancel(self) -> dict[str, Any]:
        with self._lock:
            if self._state["busy"]:
                self._cancel_requested = True
                if self._active_task is not None:
                    self._loop.call_soon_threadsafe(self._active_task.cancel)
                self._update(message="正在取消操作并关闭蓝牙连接。")
                return self.status()
            if self._protocol is None:
                self._update(state="cancelled", error_code=None, message="没有正在进行的配网操作。")
                return self.status()
        return self.submit("disconnect")

    def close(self) -> None:
        """Server shutdown cleanup; no new scan, credential send, or Wi-Fi reset."""
        with self._lock:
            if self._closed:
                return
            self._closed = True
            loop, thread = self._loop, self._thread
            if loop is None:
                return
            if self._state["busy"]:
                self._cancel_requested = True
                if self._active_task is not None:
                    loop.call_soon_threadsafe(self._active_task.cancel)
        async def cleanup():
            await self._disconnect()
        future = asyncio.run_coroutine_threadsafe(cleanup(), loop)
        try:
            future.result(timeout=1.5)
        except Exception:
            pass
        loop.call_soon_threadsafe(loop.stop)
        thread.join(timeout=1)

    # Convenient aliases for callers not using generic submit().
    def scan(self):
        return self.submit("scan")

    def connect(self, device_id: str, pop: str):
        return self.submit("connect", {"device_id": device_id, "pop": pop})

    def scan_wifi(self):
        return self.submit("scan_wifi")

    def provision(self, ssid: str, password: str):
        return self.submit("provision", {"ssid": ssid, "password": password})

    def disconnect(self):
        return self.submit("disconnect")
