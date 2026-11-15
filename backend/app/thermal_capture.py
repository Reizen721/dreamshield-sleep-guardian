"""Opt-in Waveshare 30139 preview/acquisition; no visual/control decisions.

The official Web Demo serves 4960 little-endian uint16 values at /thermal/frame.
Factory TCP firmware uses the separate checksum-validated SenXor adapter. Preview
keeps only a matrix/raw cache in RAM; recording requires a separate action.
Receive time below is a server
timestamp, not a sensor exposure timestamp. There is no device I/O on import or
construction. LAN HTTP connections bypass proxies and pin a validated IP.
"""
from __future__ import annotations

import copy
import hashlib
import http.client
import ipaddress
import json
import math
import re
import socket
import struct
import threading
import time
import urllib.parse
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

WIDTH, HEIGHT = 80, 62
PIXELS = WIDTH * HEIGHT
FRAME_BYTES = PIXELS * 2
DEFAULT_THERMAL_CONFIG = {
    "base_url": "", "paired_control_base_url": "", "enabled": False,
    "interval_seconds": 2,
}
SCHEMA_VERSION = "dreamshield-thermal-frame-v1"
FRESHNESS_SECONDS = 10.0
TIMEOUT_SECONDS = 4.0
_PRIVATE_V4 = tuple(ipaddress.ip_network(v) for v in
                    ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))


class ThermalError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def normalize_base_url(value: Any, *, allow_empty: bool = False) -> str:
    if allow_empty and value == "":
        return ""
    try:
        if not isinstance(value, str):
            raise ValueError()
        value = value.strip().rstrip("/")
        parsed = urllib.parse.urlsplit(value)
        if (parsed.scheme != "http" or not parsed.hostname or parsed.username
                or parsed.password or parsed.path or parsed.query or parsed.fragment
                or any(c.isspace() for c in value) or len(value) > 255):
            raise ValueError()
        port = parsed.port
        if port is not None and not 1 <= port <= 65535:
            raise ValueError()
        # Normalize explicit :80 and case so pairing comparisons are meaningful.
        hostname = parsed.hostname.lower()
        if "%" in hostname:
            raise ValueError()
        rendered = "[" + hostname + "]" if ":" in hostname else hostname
        return "http://" + rendered + (":" + str(port) if port and port != 80 else "")
    except (ValueError, TypeError):
        raise ThermalError(400, "invalid_thermal_url",
                           "请输入不含路径或账号的 http:// 局域网地址") from None


def _allowed_ip(value: str) -> bool:
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        return False
    if address.is_loopback:
        return True
    if address.version == 4:
        return any(address in network for network in _PRIVATE_V4)
    return address in ipaddress.ip_network("fc00::/7")


def _approved_address(host: str, port: int) -> str:
    try:
        addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except (OSError, ValueError):
        raise ThermalError(502, "thermal_dns_failed", "无法解析热像设备地址") from None
    if not addresses or any(not _allowed_ip(item[4][0]) for item in addresses):
        raise ThermalError(400, "thermal_not_lan", "热像设备必须位于局域网")
    return addresses[0][4][0]


@dataclass(frozen=True)
class ThermalFrame:
    frame_number: int
    raw: bytes
    temperatures_c: tuple[float | None, ...]
    resolution: int
    format: int
    invalid_pixels: int
    simulated: bool = False
    transport: str = "http"
    counter_source: str = "device"
    diagnostics: dict | None = None


@dataclass(frozen=True)
class ThermalReply:
    status: str
    frame: ThermalFrame | None = None


def decode_response(status: int, headers: Any, raw: bytes) -> ThermalReply:
    """Strictly decode official headers; malformed units are never guessed."""
    if status == 204:
        if raw:
            raise ThermalError(502, "thermal_invalid_frame", "无新帧响应不应包含数据")
        return ThermalReply("no_new_frame")
    if status != 200:
        code = "thermal_web_firmware_required" if status == 404 else "thermal_http_error"
        raise ThermalError(502, code, "设备未返回热像帧；请确认使用对应的 Web 示例固件")
    if not raw:
        return ThermalReply("not_ready")
    if len(raw) != FRAME_BYTES:
        raise ThermalError(502, "thermal_frame_size", "热像帧长度错误，应为 9920 字节")
    normalized = {str(k).lower(): str(v).strip() for k, v in headers.items()}
    if normalized.get("content-type", "").split(";", 1)[0].strip().lower() != "application/octet-stream":
        raise ThermalError(502, "thermal_content_type", "设备返回的不是二进制温度帧")
    scale = normalized.get("x-temp-res", "")
    number = normalized.get("x-frame-number", "")
    match = re.fullmatch(r"(10|100)\s*,\s*([012])", scale)
    if not match or not re.fullmatch(r"[0-9]{1,10}", number):
        raise ThermalError(502, "thermal_metadata", "热像帧号或温度单位元数据缺失、无效")
    frame_number = int(number)
    if frame_number > 0xFFFFFFFF:
        raise ThermalError(502, "thermal_metadata", "热像帧号超出范围")
    resolution, fmt = int(match[1]), int(match[2])
    temperatures: list[float | None] = []
    for value in struct.unpack("<" + str(PIXELS) + "H", raw):
        celsius = value / resolution
        if fmt == 0:
            celsius -= 273.15
        elif fmt == 2:
            celsius = (celsius - 32) * 5 / 9
        # Reserved values and outside the documented target range are invalid.
        temperatures.append(None if value >= 65500 or not -40 <= celsius <= 400
                            else round(celsius, 4))
    invalid = sum(value is None for value in temperatures)
    if invalid > PIXELS // 10:
        raise ThermalError(502, "thermal_pixels_invalid", "热像帧有效温度像素不足 90%")
    return ThermalReply("new_frame", ThermalFrame(
        frame_number, raw, tuple(temperatures), resolution, fmt, invalid,
        normalized.get("x-dreamshield-simulated", "").lower() in ("1", "true", "simulated_only")))


def fetch_thermal_frame(base_url: str, since: int | None = None,
                        timeout: float = TIMEOUT_SECONDS) -> ThermalReply:
    """One bounded GET. No redirects, environment proxies, or automatic retries."""
    base = normalize_base_url(base_url)
    parsed = urllib.parse.urlsplit(base)
    address = _approved_address(parsed.hostname or "", parsed.port or 80)
    path = "/thermal/frame" + ("?since=" + str(int(since)) if since is not None else "")
    connection = http.client.HTTPConnection(address, parsed.port or 80, timeout=timeout)
    try:
        connection.request("GET", path, headers={
            "Host": parsed.netloc, "Accept": "application/octet-stream",
            "Accept-Encoding": "identity", "Cache-Control": "no-cache",
            "User-Agent": "DreamShield-Thermal/0.1"})
        response = connection.getresponse()
        length = response.getheader("Content-Length")
        if length is not None:
            try:
                count = int(length)
            except ValueError:
                raise ThermalError(502, "thermal_frame_size", "热像响应长度无效") from None
            if count < 0 or count > FRAME_BYTES:
                raise ThermalError(502, "thermal_frame_size", "热像响应超过允许大小")
        if response.getheader("Content-Encoding", "identity").lower() != "identity":
            raise ThermalError(502, "thermal_encoding", "不支持压缩的热像数据响应")
        raw = response.read(FRAME_BYTES + 1)
        return decode_response(response.status, dict(response.getheaders()), raw)
    except ThermalError:
        raise
    except (OSError, http.client.HTTPException, ValueError):
        raise ThermalError(502, "thermal_unreachable", "热像设备连接失败或超时") from None
    finally:
        connection.close()


def normalize_thermal_url(value: Any, *, allow_empty: bool = False) -> str:
    if isinstance(value, str) and value.strip().lower().startswith("tcp://"):
        # Reuse strict host/LAN validation without accepting paths or credentials.
        try:
            original = urllib.parse.urlsplit(value.strip())
        except ValueError:
            raise ThermalError(400, "invalid_thermal_url", "热像 TCP 地址格式无效") from None
        http_url = normalize_base_url("http://" + value.strip()[6:])
        parsed = urllib.parse.urlsplit(http_url)
        host = parsed.hostname or ""
        host = "[" + host + "]" if ":" in host else host
        return "tcp://" + host + ":" + str(original.port or 3333)
    return normalize_base_url(value, allow_empty=allow_empty)


def _iso(epoch: float) -> str:
    return datetime.fromtimestamp(epoch, timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class ThermalCaptureService:
    """One thermal source paired to one control source, with isolated storage."""

    def __init__(self, storage_dir: str | Path, *, fetcher: Callable | None = None,
                 clock: Callable[[], float] = time.time):
        self.storage_dir = Path(storage_dir)
        self.fetcher = fetcher or self._fetch_frame
        self.tcp_client = None
        self.tcp_identity = None
        self.transport_lock = threading.Lock()
        self.preview_enabled = False
        self.pairing_invalidated = False
        self.cached_raw: bytes | None = None
        self.clock = clock
        self.lock = threading.RLock()
        self.capture_lock = threading.Lock()
        self.config = copy.deepcopy(DEFAULT_THERMAL_CONFIG)
        self.generation = 0
        self.stream_id = uuid.uuid4().hex
        self.latest: dict | None = None
        self.last_frame_number: int | None = None
        self.last_frame_hash: str | None = None
        self.last_error: dict | None = None
        self.last_attempt_at: str | None = None
        self.last_contact_epoch: float | None = None
        self.status = "disabled"
        self.counters = {"received": 0, "saved": 0, "duplicate": 0, "errors": 0}

    def close_transport(self):
        with self.transport_lock:
            client, self.tcp_client = self.tcp_client, None
            self.tcp_identity = None
        if client is not None:
            client.close()

    def _fetch_frame(self, base_url, since=None, timeout=TIMEOUT_SECONDS):
        base = normalize_thermal_url(base_url)
        parsed = urllib.parse.urlsplit(base)
        if parsed.scheme == "http":
            return fetch_thermal_frame(base, since=since, timeout=timeout)
        from senxor_tcp import SenxorTCPClient, SenxorTCPError
        address = _approved_address(parsed.hostname or "", parsed.port or 3333)
        identity = (base, address)
        with self.transport_lock:
            if self.tcp_identity != identity or self.tcp_client is None:
                if self.tcp_client is not None:
                    self.tcp_client.close()
                self.tcp_client = SenxorTCPClient(address, parsed.port or 3333)
                self.tcp_identity = identity
            client = self.tcp_client
        try:
            # AP+STA power saving and the factory identification handshake may
            # take several seconds. Keep HTTP timing unchanged; bound TCP's
            # first frame separately below the webpage's 15-second timeout.
            result = client.fetch(since=since, timeout=max(timeout, 8.0))
            return ThermalReply("new_frame", ThermalFrame(
                result["frame_number"], result["raw"], result["temperatures_c"],
                result["resolution"], result["format"], result["invalid_pixels"],
                False, "senxor_tcp", "sensor_header_u16", result["diagnostics"]))
        except SenxorTCPError as exc:
            raise ThermalError(502, exc.code, exc.message) from None

    def set_preview(self, enabled: bool, *, control_base_url: str | None = None):
        if type(enabled) is not bool:
            raise ThermalError(400, "thermal_settings_invalid", "预览开关必须是布尔值")
        with self.lock:
            if enabled:
                self._check_pair(self.config, control_base_url)
            self.preview_enabled = enabled
            self.generation += 1
        if not enabled and not self.config["enabled"]:
            self.close_transport()
        return self.snapshot()

    def configure(self, body: dict, *, control_base_url: str = "") -> dict:
        if not isinstance(body, dict) or set(body) - set(DEFAULT_THERMAL_CONFIG):
            raise ThermalError(400, "thermal_settings_invalid", "热像设置字段无效")
        with self.lock:
            config = {**self.config, **body}
            config["base_url"] = normalize_thermal_url(config["base_url"], allow_empty=True)
            config["paired_control_base_url"] = normalize_base_url(
                config["paired_control_base_url"], allow_empty=True)
            if type(config["enabled"]) is not bool:
                raise ThermalError(400, "thermal_settings_invalid", "采集开关必须是布尔值")
            interval = config["interval_seconds"]
            if type(interval) not in (int, float) or not math.isfinite(interval) or not 1 <= interval <= 60:
                raise ThermalError(400, "thermal_settings_invalid", "采集间隔必须在 1–60 秒之间")
            config["interval_seconds"] = float(interval)
            if "base_url" in body:
                self.pairing_invalidated = False
            if config["enabled"]:
                self._check_pair(config, control_base_url)
            if config != self.config:
                # In-flight replies may not cross any setting/pairing boundary.
                self.generation += 1
                identity_changed = any(config[k] != self.config[k] for k in
                                       ("base_url", "paired_control_base_url"))
                if identity_changed:
                    self.pairing_invalidated = bool(self.config["paired_control_base_url"]
                        and not config["paired_control_base_url"] and "base_url" not in body)
                    self.preview_enabled = False
                    self.close_transport()
                    self.latest = None
                    self.cached_raw = None
                    self.last_frame_number = self.last_frame_hash = None
                    self.last_contact_epoch = None
                    self.last_error = None
                    self.stream_id = uuid.uuid4().hex
                self.config = config
            self.status = "waiting" if config["enabled"] else "disabled"
            if not config["enabled"] and not self.preview_enabled:
                self.close_transport()
            return self.snapshot()

    def _check_pair(self, config: dict, control_base_url: str | None):
        if self.pairing_invalidated:
            raise ThermalError(409, "thermal_pairing_mismatch", "控制板地址已变化，请核对并重新保存热像设置")
        if not config["base_url"]:
            raise ThermalError(409, "thermal_pairing_required", "请先配置热像板地址")
        if (config["paired_control_base_url"] and control_base_url is not None
                and normalize_base_url(control_base_url, allow_empty=True) != config["paired_control_base_url"]):
            raise ThermalError(409, "thermal_pairing_mismatch", "控制板地址已变化，请重新绑定热像节点")
        if config["base_url"] == config["paired_control_base_url"]:
            raise ThermalError(400, "thermal_pairing_invalid", "热像板和控制板应使用不同设备地址")

    def _freshness(self, metadata: dict) -> dict:
        age = max(0.0, self.clock() - metadata["received_epoch"])
        fresh = age <= FRESHNESS_SECONDS and self.last_error is None
        return {"age_seconds": round(age, 3), "freshness": "fresh" if fresh else "stale",
                "online": fresh and self.last_contact_epoch is not None
                          and self.clock() - self.last_contact_epoch <= FRESHNESS_SECONDS}

    def snapshot(self) -> dict:
        with self.lock:
            latest = None
            if self.latest:
                latest = {k: copy.deepcopy(v) for k, v in self.latest.items()
                          if k not in ("temperatures_c", "received_epoch")}
                latest.update(self._freshness(self.latest))
            return {"config": copy.deepcopy(self.config), "status": self.status,
                    "preview_enabled": self.preview_enabled,
                    "latest": latest, "last_error": copy.deepcopy(self.last_error),
                    "last_attempt_at": self.last_attempt_at,
                    "counters": dict(self.counters), "hardware_validated": False,
                    "inference_enabled": False, "execution_allowed": False,
                    "control_eligible": False,
                    "limitations": ["server_receive_time_only", "thermal_model_not_validated",
                                    "not_connected_to_rgb_vlm_or_ir_control"]}

    def latest_frame(self) -> dict | None:
        with self.lock:
            if self.latest is None:
                return None
            result = {k: copy.deepcopy(v) for k, v in self.latest.items() if k != "received_epoch"}
            result.update(self._freshness(self.latest))
            return result

    def capture_once(self, source: str = "manual", *, control_base_url: str | None = None,
                     environment_context: dict | None = None) -> dict:
        if source not in ("manual", "scheduled", "preview"):
            raise ThermalError(400, "thermal_source_invalid", "采集来源无效")
        if not self.capture_lock.acquire(blocking=False):
            raise ThermalError(409, "thermal_busy", "热像采集正在进行")
        try:
            if environment_context is not None:
                try:
                    if not isinstance(environment_context, dict):
                        raise ValueError()
                    serialized_context = json.dumps(environment_context, allow_nan=False)
                    if len(serialized_context) > 32768:
                        raise ValueError()
                    environment_context = json.loads(serialized_context)
                except (TypeError, ValueError):
                    raise ThermalError(400, "thermal_environment_invalid", "关联的缓存温湿度信息无效") from None
            with self.lock:
                config = dict(self.config)
                self._check_pair(config, control_base_url)
                if (source == "manual" and self.preview_enabled and self.latest
                        and self._freshness(self.latest)["freshness"] == "fresh"
                        and self.cached_raw is not None):
                    if self.latest.get("raw_file"):
                        return {"status": "duplicate", "capture": None}
                    metadata = self._save_record(self.latest, self.cached_raw)
                    return {"status": "captured", "capture": metadata}
                if source == "scheduled" and not config["enabled"]:
                    return {"status": "disabled", "capture": None}
                if source == "preview" and not self.preview_enabled:
                    return {"status": "disabled", "capture": None}
                generation, since = self.generation, self.last_frame_number
                self.last_attempt_at = _iso(self.clock())
            try:
                reply = self.fetcher(config["base_url"], since=since, timeout=TIMEOUT_SECONDS)
                if not isinstance(reply, ThermalReply):
                    raise ThermalError(502, "thermal_invalid_reply", "热像数据适配器返回无效结果")
            except Exception as exc:
                error = exc if isinstance(exc, ThermalError) else ThermalError(
                    502, "thermal_unreachable", "热像设备连接失败或超时")
                raise error from None
            with self.lock:
                if generation != self.generation:
                    raise ThermalError(409, "thermal_settings_changed", "设置已变化，本次旧设备响应已丢弃")
                self.last_contact_epoch = self.clock()
                self.last_error = None
                if reply.status != "new_frame":
                    self.status = reply.status
                    return {"status": reply.status, "capture": None}
                frame = reply.frame
                if frame is None:
                    raise ThermalError(502, "thermal_invalid_reply", "热像帧内容缺失")
                digest = hashlib.sha256(frame.raw).hexdigest()
                if frame.frame_number == self.last_frame_number:
                    if digest != self.last_frame_hash:
                        raise ThermalError(502, "thermal_frame_identity_conflict", "同一帧号的数据发生变化")
                    self.counters["duplicate"] += 1
                    if source != "preview" and self.latest and not self.latest.get("raw_file"):
                        metadata = self._save_record(self.latest, frame.raw, source=source)
                        self.status = "captured"
                        return {"status": "captured", "capture": metadata}
                    self.status = "duplicate"
                    return {"status": "duplicate", "capture": None}
                counter_regressed = self.last_frame_number is not None and frame.frame_number < self.last_frame_number
                if counter_regressed:
                    self.stream_id = uuid.uuid4().hex
                now = self.clock()
                capture_id = uuid.uuid4().hex
                valid = [value for value in frame.temperatures_c if value is not None]
                metadata = {
                    "schema_version": SCHEMA_VERSION, "capture_id": capture_id,
                    "source": source, "device_model": "Waveshare Thermal-90 Camera ESP32 Module",
                    "sku": "30139", "thermal_base_url": config["base_url"],
                    "paired_control_base_url": config["paired_control_base_url"],
                    "pairing_identity_source": "configured_urls_not_hardware_identity",
                    "stream_id": self.stream_id, "frame_number": frame.frame_number,
                    "transport": frame.transport, "frame_number_source": frame.counter_source,
                    "transport_diagnostics": frame.diagnostics,
                    "counter_regressed_restart_inferred": counter_regressed,
                    "received_at": _iso(now), "captured_at": None,
                    "timestamp_source": "server_received_not_sensor_exposure",
                    "width": WIDTH, "height": HEIGHT, "raw_encoding": "uint16_le",
                    "raw_bytes": FRAME_BYTES, "raw_sha256": digest,
                    "temperature_resolution": frame.resolution,
                    "temperature_format": frame.format, "matrix_unit": "celsius",
                    "invalid_pixels": frame.invalid_pixels,
                    "min_temperature_c": min(valid), "max_temperature_c": max(valid),
                    "raw_file": None, "metadata_file": None,
                    "simulated": frame.simulated,
                    "validation_status": "SIMULATED_ONLY" if frame.simulated else "HARDWARE_NOT_VALIDATED",
                    "inference_enabled": False, "execution_allowed": False,
                    "control_eligible": False,
                    "environment_context": environment_context,
                    "environment_association": "caller_cached_observation_no_sensor_fetch",
                }
                matrix = [list(frame.temperatures_c[y * WIDTH:(y + 1) * WIDTH]) for y in range(HEIGHT)]
                record = {**metadata, "temperatures_c": matrix}
                if source != "preview":
                    self._save_record(record, frame.raw)
                self.last_frame_number, self.last_frame_hash = frame.frame_number, digest
                self.latest = {**record, "received_epoch": now}
                self.cached_raw = frame.raw
                self.counters["received"] += 1
                self.status = "previewing" if source == "preview" else "captured"
                result_metadata = {k: v for k, v in record.items() if k != "temperatures_c"}
                return {"status": self.status, "capture": {**result_metadata, **self._freshness(self.latest)}}
        except ThermalError as error:
            with self.lock:
                if "generation" in locals() and generation == self.generation:
                    self.last_error = {"code": error.code, "message": error.message,
                                       "at": _iso(self.clock())}
                    self.status = "error"
                    self.counters["errors"] += 1
            raise
        finally:
            try:
                with self.lock:
                    if not self.preview_enabled and not self.config["enabled"]:
                        self.close_transport()
            finally:
                self.capture_lock.release()

    def _save_record(self, record: dict, raw: bytes, *, source: str | None = None) -> dict:
        """Persist only on an explicit manual/scheduled save, never on preview."""
        capture_id = record["capture_id"]
        date_dir = record["received_at"][:10].replace("-", "/")
        relative = date_dir + "/" + capture_id
        saved = {k: v for k, v in record.items() if k != "received_epoch"}
        if saved["source"] == "preview":
            saved.update(source=source or "manual", acquisition_source="preview_cache")
        saved.update(raw_file=relative + ".bin", metadata_file=relative + ".json")
        target = self.storage_dir / date_dir
        raw_path, json_path = target / (capture_id + ".bin"), target / (capture_id + ".json")
        try:
            target.mkdir(parents=True, exist_ok=True)
            raw_path.write_bytes(raw)
            json_path.write_text(json.dumps(saved, ensure_ascii=False, allow_nan=False), encoding="utf-8")
        except OSError:
            for partial in (raw_path, json_path):
                try:
                    partial.unlink(missing_ok=True)
                except OSError:
                    pass
            raise ThermalError(500, "thermal_save_failed", "保存热像数据失败，请检查存储空间") from None
        record.update(raw_file=saved["raw_file"], metadata_file=saved["metadata_file"], source=saved["source"])
        if "acquisition_source" in saved:
            record["acquisition_source"] = saved["acquisition_source"]
        self.counters["saved"] += 1
        return {k: copy.deepcopy(v) for k, v in record.items()
                if k not in ("temperatures_c", "received_epoch")}

    def list_captures(self, limit: int = 20) -> list[dict]:
        limit = max(1, min(100, int(limit)))
        result = []
        if not self.storage_dir.exists():
            return result
        entries = []
        for path in self.storage_dir.glob("*/*/*/*.json"):
            try:
                entries.append((path.stat().st_mtime_ns, path))
            except OSError:
                # Users may clean an old capture while the history page loads.
                continue
        for _, path in sorted(entries, reverse=True):
            try:
                record = json.loads(path.read_text(encoding="utf-8"))
                if record.get("schema_version") != SCHEMA_VERSION:
                    continue
                result.append({key: value for key, value in record.items() if key != "temperatures_c"})
            except (OSError, ValueError, AttributeError):
                continue
            if len(result) >= limit:
                break
        return result

    def run(self, stop_event: threading.Event):
        while not stop_event.is_set():
            with self.lock:
                enabled, interval = self.config["enabled"], self.config["interval_seconds"]
            if enabled:
                try:
                    self.capture_once("scheduled")
                except ThermalError:
                    pass
                stop_event.wait(interval)
            else:
                stop_event.wait(0.25)
