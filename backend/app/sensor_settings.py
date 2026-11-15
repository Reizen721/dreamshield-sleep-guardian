"""Explicit LAN-only SHT31 interval configuration; no I/O at import time.

Settings are applied only by an explicit caller. A POST is never retried and
an unverified receipt never becomes an apparent success. This does not emit IR.
"""
from __future__ import annotations

import ipaddress
import json
import re
import socket
import urllib.error
import urllib.parse
import urllib.request


CONFIG_PATH = "/api/v1/environment/config"
DEFAULT_INTERVAL_SECONDS = 15
MIN_INTERVAL_SECONDS = 5
MAX_INTERVAL_SECONDS = 30


class SensorSettingsError(Exception):
    def __init__(self, status: int, code: str, message: str, *, outcome_unknown=False):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message
        self.outcome_unknown = outcome_unknown


def _base_url(value):
    try:
        if not isinstance(value, str):
            raise ValueError()
        value = value.strip().rstrip("/")
        parsed = urllib.parse.urlsplit(value)
        if (parsed.scheme != "http" or not parsed.hostname or parsed.username or parsed.password
                or parsed.path or parsed.query or parsed.fragment or any(c.isspace() for c in value)):
            raise ValueError()
        parsed.port
    except ValueError:
        raise SensorSettingsError(400, "invalid_device_url", "请输入不含路径或账号的 http:// 局域网设备地址") from None
    return value


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _request_json(method, url, payload=None):
    """Default injectable transport; private host, no proxies/redirects/retries."""
    parsed = urllib.parse.urlsplit(url)
    try:
        addresses = socket.getaddrinfo(parsed.hostname, parsed.port or 80, type=socket.SOCK_STREAM)
        if not addresses or any(not (ipaddress.ip_address(item[4][0].split("%", 1)[0]).is_private
                                     or ipaddress.ip_address(item[4][0].split("%", 1)[0]).is_loopback
                                     or ipaddress.ip_address(item[4][0].split("%", 1)[0]).is_link_local)
                                for item in addresses):
            raise SensorSettingsError(400, "not_private_lan", "只允许连接局域网设备")
    except (socket.gaierror, ValueError) as exc:
        raise SensorSettingsError(502, "device_unreachable", "无法解析设备地址；未发送设置") from exc
    data = json.dumps(payload, separators=(",", ":")).encode() if payload is not None else None
    headers = {"Accept": "application/json", "User-Agent": "DreamShield-Sensor-Settings/0.1"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())
    try:
        with opener.open(request, timeout=6) as response:
            raw = response.read(65537)
            if len(raw) > 65536 or response.headers.get_content_type() != "application/json":
                raise ValueError("invalid response envelope")
            result = json.loads(raw)
            if not isinstance(result, dict):
                raise ValueError("expected object")
            return result
    except urllib.error.HTTPError as exc:
        unsupported = exc.code in (404, 405)
        raise SensorSettingsError(
            409 if unsupported else 502,
            "unsupported_firmware" if unsupported else "device_rejected_settings",
            "当前设备固件不支持采样设置，请更新固件" if unsupported else f"设备拒绝设置（HTTP {exc.code}）；请检查设备状态",
            outcome_unknown=method == "POST" and exc.code not in (400, 403, 404, 405, 409, 413)) from exc
    except (urllib.error.URLError, OSError, TimeoutError, ValueError, UnicodeError) as exc:
        raise SensorSettingsError(502, "settings_outcome_unknown" if method == "POST" else "device_status_unavailable",
                                  "设置回执不可验证；结果未知，请读取设备状态，勿自动重试" if method == "POST"
                                  else "无法读取有效设备状态；未发送设置",
                                  outcome_unknown=method == "POST") from exc


def apply_sensor_interval(base_url, seconds, *, transport=None):
    """GET identity/capability, POST exactly once, validate the applied receipt.

    Optional test transport signature: ``transport(method, full_url, payload)``
    returning a decoded JSON object. No automatic reapplication after reboot.
    """
    if type(seconds) is not int or not MIN_INTERVAL_SECONDS <= seconds <= MAX_INTERVAL_SECONDS:
        raise SensorSettingsError(400, "invalid_sample_interval", "SHT31 采样间隔必须为 5–30 秒的整数，默认 15 秒")
    base = _base_url(base_url)
    request = transport or _request_json
    try:
        status = request("GET", base + "/api/v1/status", None)
    except SensorSettingsError:
        raise
    except Exception as exc:
        raise SensorSettingsError(502, "device_status_unavailable", "无法读取设备状态；未发送设置") from exc
    env = status.get("environment") if isinstance(status, dict) else None
    if (not isinstance(env, dict) or env.get("sensor_model") != "sht31"
            or env.get("sample_interval_configurable") is not True
            or env.get("configuration_endpoint") != CONFIG_PATH
            or env.get("configuration_persistence") != "until_restart"
            or env.get("sample_interval_min_seconds") != MIN_INTERVAL_SECONDS
            or env.get("sample_interval_max_seconds") != MAX_INTERVAL_SECONDS):
        raise SensorSettingsError(409, "unsupported_firmware", "当前固件未声明可设置 SHT31 采样间隔，请先更新固件")
    device, boot = status.get("device_id"), status.get("boot_id")
    if (not isinstance(device, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,31}", device)
            or not isinstance(boot, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,8}", boot)):
        raise SensorSettingsError(502, "device_identity_invalid", "设备身份信息无效；未发送设置")
    body = {"device_id": device, "boot_id": boot, "sample_interval_seconds": seconds}
    try:
        receipt = request("POST", base + CONFIG_PATH, body)
    except SensorSettingsError:
        raise
    except Exception as exc:
        raise SensorSettingsError(502, "settings_outcome_unknown", "未收到有效设置回执；结果未知，请读取设备状态，勿自动重试",
                                  outcome_unknown=True) from exc
    if (not isinstance(receipt, dict) or receipt.get("status") != "applied"
            or receipt.get("device_id") != device or receipt.get("boot_id") != boot
            or type(receipt.get("sample_interval_seconds")) is not int
            or receipt["sample_interval_seconds"] != seconds
            or type(receipt.get("sample_interval_ms")) is not int
            or receipt["sample_interval_ms"] != seconds * 1000
            or receipt.get("freshness_limit_ms") != 45000
            or receipt.get("persistence") != "until_restart"
            or receipt.get("default_sample_interval_seconds") != DEFAULT_INTERVAL_SECONDS
            or receipt.get("execution_allowed") is not False):
        raise SensorSettingsError(502, "settings_receipt_unverified", "设备回执与本次设置不一致；结果未知，请读取设备状态，勿自动重试",
                                  outcome_unknown=True)
    return {"status": "applied", "device_base_url": base, "device_id": device, "boot_id": boot,
            "sample_interval_seconds": seconds, "sample_interval_ms": seconds * 1000,
            "freshness_limit_ms": 45000, "persistence": "until_restart",
            "default_sample_interval_seconds": DEFAULT_INTERVAL_SECONDS, "execution_allowed": False}
