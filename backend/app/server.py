from __future__ import annotations

import base64
import binascii
import copy
import gzip
import hashlib
import heapq
import ipaddress
import json
import math
import mimetypes
import os
import re
import socket
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import webbrowser
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from environment_history import EnvironmentHistory, RoomEnvironment, associate_capture, make_observation
from automatic_ai import AutoAIEngine
from ble_provisioning import BleProvisioningController
from sensor_fallback import resolve_last_valid
from live_control import visual_state, timestamp
from sensor_settings import SensorSettingsError, apply_sensor_interval
from ac_control import AcController
from thermal_capture import ThermalCaptureService, ThermalError
from thermal_model_service import ThermalModelService, ModelError
from thermal_closed_loop import ThermalClosedLoop, make_reviewer
from runtime_paths import resolve_data_dir
from vlm_credentials import key_from_file
from control_policy import (
    POLICY_VERSION, COMMAND_COOLDOWN_SECONDS, VISUAL_MAX_AGE_SECONDS,
    CONFIRMATION_TARGET_SECONDS, ENVIRONMENT_PERSISTENCE_SECONDS,
    ENVIRONMENT_POLL_SECONDS, ENVIRONMENT_MAX_GAP_SECONDS,
    FRESH_ENVIRONMENT_MAX_AGE_SECONDS, EnvironmentTracker, decide_control,
    DEFAULT_POLICY_SETTINGS, CONTROL_PRESETS, identify_policy_preset, validate_policy_settings,
)


BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"
CONSOLE_RELEASE = "0.1.5"
CONSOLE_ASSETS = frozenset({
    "console_theme.js", "console_theme.css", "console_motion.js", "console_motion.css",
    "console_status.js", "console_status.css", "console_navigation.js", "console_shell.css",
    "mobile_connections.js", "environment.js", "sensor_fallback.js", "ac_control.js", "thermal_closed_loop.js",
    "automatic_ai.js", "policy_settings.js", "device_setup.js",
    "device_setup.css", "thermal_setup.js", "thermal_setup.css",
    "thermal_model.js", "thermal_model.css",
})
CONSOLE_ASSET_REFERENCE = re.compile(rb'\b(?:href|src)="/static/([a-z_]+\.(?:css|js))"')


@lru_cache(maxsize=96)
def _console_file(path: Path, modified_ns: int, size: int) -> tuple[bytes, str]:
    """The stat tuple invalidates cached bytes when editable sources change."""
    data = path.read_bytes()
    return data, hashlib.sha256(data).hexdigest()[:24]


def console_file(path: Path) -> tuple[bytes, str]:
    stat = path.stat()
    return _console_file(path, stat.st_mtime_ns, stat.st_size)


def versioned_console_page(path: Path) -> bytes:
    def version(match: re.Match) -> bytes:
        name = match[1].decode("ascii")
        if name not in CONSOLE_ASSETS:
            return match[0]
        _, digest = console_file(path.parent / name)
        return match[0][:-1] + b"?v=" + digest.encode("ascii") + b'"'

    data, _ = console_file(path)
    return CONSOLE_ASSET_REFERENCE.sub(version, data)


@lru_cache(maxsize=48)
def compressed_console_asset(data: bytes) -> bytes:
    return gzip.compress(data, compresslevel=4, mtime=0)


DATA_DIR = resolve_data_dir(BASE_DIR)
CAPTURES_DIR = DATA_DIR / "captures"
AUTOMATIC_AI_DIR = DATA_DIR / "automatic_ai"
CONFIG_PATH = DATA_DIR / "config.json"
COMPARE_PROMPT_PATH = BASE_DIR / "vlm_compare_prompt.txt"
THERMAL_DIR = DATA_DIR / "thermal_captures"
THERMAL_SETTINGS_PATH = DATA_DIR / "thermal_settings.json"

HOST = os.environ.get("DREAMSHIELD_HOST", "127.0.0.1")
PORT = int(os.environ.get("DREAMSHIELD_PORT", "8000"))
VLM_PROVIDER = os.environ.get("VLM_PROVIDER", "deepseek").strip().lower()
if VLM_PROVIDER == "openai":
    DEFAULT_VLM_MODEL = "gpt-5.4-mini"
    DEFAULT_VLM_API_URL = "https://api.openai.com/v1/responses"
else:
    DEFAULT_VLM_MODEL = "deepseek-flash"
    DEFAULT_VLM_API_URL = "https://api.deepseek.com/responses"
VLM_MODEL = os.environ.get(
    "VLM_MODEL",
    os.environ.get("OPENAI_VLM_MODEL", DEFAULT_VLM_MODEL),
).strip()
VLM_API_URL = os.environ.get("VLM_API_URL", DEFAULT_VLM_API_URL).strip()
MAX_JSON_BYTES = 45 * 1024 * 1024
MAX_IMAGE_BYTES = 10 * 1024 * 1024
HUMIDITY_CONTROL_UPPER_PERCENT = 65.0
MOBILE_GATEWAY: Any = None  # Enabled only by the explicit mobile launcher.
BLE_PROVISIONING = BleProvisioningController()  # Lazy: no Bluetooth scan on startup.

DEFAULT_CONFIG = {
    "device_base_url": "http://dreamshield-camera.local",
    "capture_interval_seconds": 60,
    "schedule_enabled": False,
    "environment_recording_enabled": False,
    "environment_interval_seconds": ENVIRONMENT_POLL_SECONDS,
    "control_policy": dict(DEFAULT_POLICY_SETTINGS),
    "control_preset": "balanced",
    "sensor_sample_target_seconds": 15,
    "sensor_sample_interval_seconds": None,
}

COMPARE_FRAME_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "image_usable": {"type": "boolean"},
        "person_state": {
            "type": "string",
            "enum": ["present", "empty", "uncertain"],
        },
        "coverage_state": {
            "type": "string",
            "enum": [
                "covered",
                "partially_uncovered",
                "uncovered",
                "uncertain",
                "empty",
            ],
        },
        "major_body_exposure_ratio": {
            "type": "number",
            "minimum": 0,
            "maximum": 1,
        },
        "blanket_position": {"type": "string"},
        "direct_evidence": {
            "type": "array",
            "items": {"type": "string"},
            "maxItems": 4,
        },
    },
    "required": [
        "image_usable",
        "person_state",
        "coverage_state",
        "major_body_exposure_ratio",
        "blanket_position",
        "direct_evidence",
    ],
}

VLM_COMPARE_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "before_frame": COMPARE_FRAME_SCHEMA,
        "after_frame": COMPARE_FRAME_SCHEMA,
        "frames_comparable": {"type": "boolean"},
        "scene_change": {
            "type": "string",
            "enum": ["stable", "minor", "major", "uncertain"],
        },
        "blanket_change": {
            "type": "string",
            "enum": [
                "moved_away_from_body",
                "moved_toward_body",
                "no_meaningful_change",
                "uncertain",
            ],
        },
        "exposed_body_change": {
            "type": "string",
            "enum": ["increased", "decreased", "unchanged", "uncertain"],
        },
        "blanket_displacement_assessment": {
            "type": "string",
            "enum": ["detected", "not_detected", "indeterminate"],
        },
        "temporal_evidence": {
            "type": "string",
            "enum": [
                "state_change_observed_between_frames",
                "no_state_change_observed",
                "comparison_unreliable",
            ],
        },
        "visible_changes": {
            "type": "array",
            "items": {"type": "string"},
            "maxItems": 6,
        },
        "confidence_components": {
            "type": "object",
            "additionalProperties": False,
            "properties": {
                "before_image_quality": {"type": "number", "minimum": 0, "maximum": 1},
                "after_image_quality": {"type": "number", "minimum": 0, "maximum": 1},
                "scene_comparability": {"type": "number", "minimum": 0, "maximum": 1},
                "person_consistency": {"type": "number", "minimum": 0, "maximum": 1},
                "assessment_clarity": {"type": "number", "minimum": 0, "maximum": 1},
            },
            "required": [
                "before_image_quality",
                "after_image_quality",
                "scene_comparability",
                "person_consistency",
                "assessment_clarity",
            ],
        },
        "limitations": {
            "type": "array",
            "items": {"type": "string"},
            "maxItems": 5,
        },
        "recommended_next_step": {
            "type": "string",
            "enum": [
                "capture_additional_frame",
                "request_clearer_images",
                "human_review",
            ],
        },
    },
    "required": [
        "before_frame",
        "after_frame",
        "frames_comparable",
        "scene_change",
        "blanket_change",
        "exposed_body_change",
        "blanket_displacement_assessment",
        "temporal_evidence",
        "visible_changes",
        "confidence_components",
        "limitations",
        "recommended_next_step",
    ],
}


class ApiError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message
        # Automatic workers expose only explicitly classified diagnostics, not
        # raw exception strings that might contain provider payloads/secrets.
        self.public_message = f"HTTP {status}"


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace(
        "+00:00", "Z"
    )


def normalize_base_url(value: str) -> str:
    value = (value or "").strip()
    if not value:
        raise ApiError(400, "Device URL is required")
    if "://" not in value:
        value = "http://" + value
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != "http":
        raise ApiError(400, "Only http:// LAN device URLs are allowed")
    if not parsed.hostname or parsed.username or parsed.password:
        raise ApiError(400, "Invalid device URL")
    if parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        raise ApiError(400, "Enter only the device base URL, without a path")
    port = parsed.port
    netloc = parsed.hostname if port is None else f"{parsed.hostname}:{port}"
    return f"http://{netloc}"


def assert_private_lan_host(base_url: str) -> None:
    parsed = urllib.parse.urlsplit(base_url)
    try:
        addresses = socket.getaddrinfo(
            parsed.hostname,
            parsed.port or 80,
            type=socket.SOCK_STREAM,
        )
    except socket.gaierror as exc:
        raise ApiError(502, f"Cannot resolve the device hostname: {exc}") from exc

    resolved = {item[4][0].split("%", 1)[0] for item in addresses}
    if not resolved:
        raise ApiError(502, "The device hostname did not resolve")
    for address in resolved:
        ip = ipaddress.ip_address(address)
        if not (ip.is_private or ip.is_loopback or ip.is_link_local):
            raise ApiError(400, "Only private LAN device addresses are allowed")


def fetch_device(base_url: str, path: str, timeout: float = 6.0) -> tuple[bytes, str]:
    data, content_type, _ = fetch_device_with_headers(base_url, path, timeout)
    return data, content_type


def fetch_device_with_headers(base_url: str, path: str, timeout: float = 6.0) -> tuple[bytes, str, dict[str, str]]:
    base_url = normalize_base_url(base_url)
    assert_private_lan_host(base_url)
    request = urllib.request.Request(
        base_url + path,
        headers={"User-Agent": "DreamShield-Local-Server/0.1"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            content_type = response.headers.get_content_type()
            headers = {key.lower(): value for key, value in response.headers.items()
                       if key.lower() in {"x-device-id", "x-boot-id", "x-frame-sequence", "x-uptime-ms"}}
            data = response.read(MAX_IMAGE_BYTES + 1)
    except urllib.error.HTTPError as exc:
        raise ApiError(502, f"Device returned HTTP {exc.code}") from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise ApiError(502, f"Cannot reach the ESP32 device: {exc}") from exc
    if len(data) > MAX_IMAGE_BYTES:
        raise ApiError(413, "Device response is too large")
    return data, content_type, headers


def validate_image_data_url(data_url: str) -> tuple[str, bytes]:
    if not isinstance(data_url, str) or not data_url.startswith("data:image/"):
        raise ApiError(400, "A JPEG, PNG or WebP image is required")
    try:
        header, encoded = data_url.split(",", 1)
    except ValueError as exc:
        raise ApiError(400, "Invalid image data URL") from exc
    allowed = {
        "data:image/jpeg;base64": "image/jpeg",
        "data:image/png;base64": "image/png",
        "data:image/webp;base64": "image/webp",
    }
    mime = allowed.get(header.lower())
    if mime is None:
        raise ApiError(400, "Only JPEG, PNG and WebP images are supported")
    try:
        raw = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise ApiError(400, "Invalid Base64 image data") from exc
    if not raw or len(raw) > MAX_IMAGE_BYTES:
        raise ApiError(413, "Image must be between 1 byte and 10 MB")
    signatures = {
        "image/jpeg": raw.startswith(b"\xff\xd8\xff"),
        "image/png": raw.startswith(b"\x89PNG\r\n\x1a\n"),
        "image/webp": raw.startswith(b"RIFF") and raw[8:12] == b"WEBP",
    }
    if not signatures[mime]:
        raise ApiError(400, "The uploaded file does not match its image type")
    return mime, raw


def extract_response_text(response: dict[str, Any]) -> str:
    direct = response.get("output_text")
    if isinstance(direct, str) and direct.strip():
        return direct.strip()
    output = response.get("output", [])
    if isinstance(output, list):
        for item in output:
            if not isinstance(item, dict) or item.get("type") != "message":
                continue
            content_items = item.get("content", [])
            if isinstance(content_items, str) and content_items.strip():
                return content_items.strip()
            if not isinstance(content_items, list):
                continue
            for content in content_items:
                if not isinstance(content, dict):
                    continue
                if content.get("type") in {"output_text", "text"} and content.get("text"):
                    return str(content["text"]).strip()
    raise ApiError(502, "The VLM returned no structured text output")


def get_vlm_api_key() -> str:
    explicit = os.environ.get("VLM_API_KEY", "").strip()
    if explicit:
        return explicit
    if VLM_PROVIDER == "openai":
        key = os.environ.get("OPENAI_API_KEY", "").strip()
    else:
        key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
    return key or key_from_file(os.environ.get("DREAMSHIELD_VLM_KEY_FILE", ""))


def request_vlm(payload: dict[str, Any]) -> dict[str, Any]:
    api_key = get_vlm_api_key()
    if not api_key:
        raise ApiError(
            503,
            "VLM API key is not configured. Set VLM_API_KEY or DREAMSHIELD_VLM_KEY_FILE.",
        )
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        VLM_API_URL,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "User-Agent": f"DreamShield-VLM-Test/0.1 ({VLM_PROVIDER})",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as api_response:
            response = json.loads(api_response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = ""
        try:
            error_body = json.loads(exc.read().decode("utf-8"))
            detail = error_body.get("error", {}).get("message", "")
        except Exception:
            detail = ""
        message = f"{VLM_PROVIDER} VLM API returned HTTP {exc.code}"
        if detail:
            message += f": {detail}"
        error = ApiError(502, message)
        error.public_message = f"{VLM_PROVIDER} VLM API HTTP {exc.code}"
        raise error from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        error = ApiError(502, f"Cannot reach the {VLM_PROVIDER} VLM API: {exc}")
        error.public_message = f"{VLM_PROVIDER} VLM network error or timeout"
        raise error from exc

    if not isinstance(response, dict):
        raise ApiError(502, "The VLM API returned an invalid response object")
    return response


def _score_component(components: dict[str, Any], name: str) -> float:
    value = components.get(name)
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ApiError(502, f"The VLM returned an invalid confidence component: {name}")
    return max(0.0, min(1.0, float(value)))


def _normalize_frame_coverage(
    frame: dict[str, Any],
    frame_name: str,
    notes: list[str],
) -> float:
    exposure = frame.get("major_body_exposure_ratio")
    if isinstance(exposure, bool) or not isinstance(exposure, (int, float)) or not math.isfinite(exposure):
        raise ApiError(502, f"The VLM returned an invalid exposure ratio: {frame_name}")
    exposure = max(0.0, min(1.0, float(exposure)))
    frame["major_body_exposure_ratio"] = round(exposure, 3)

    reported = frame.get("coverage_state")
    if reported not in {"covered", "partially_uncovered", "uncovered"}:
        return exposure
    if exposure >= 0.60:
        normalized = "uncovered"
    elif exposure >= 0.25:
        normalized = "partially_uncovered"
    else:
        normalized = "covered"
    if reported != normalized:
        frame["model_coverage_state"] = reported
        frame["coverage_state"] = normalized
        notes.append(
            f"{frame_name} 的覆盖标签已按主要身体暴露比例从 {reported} 校正为 {normalized}"
        )
    return exposure


def _optional_number(
    value: Any,
    name: str,
    minimum: float,
    maximum: float,
) -> float | None:
    if value is None or value == "":
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ApiError(400, f"{name} must be a number or null")
    number = float(value)
    if not math.isfinite(number) or number < minimum or number > maximum:
        raise ApiError(400, f"{name} must be between {minimum} and {maximum}")
    return round(number, 2)


def _parse_iso_datetime(
    value: Any,
    name: str,
) -> tuple[str | None, datetime | None]:
    if value in (None, ""):
        return None, None
    if not isinstance(value, str):
        raise ApiError(400, f"{name} must be an ISO-8601 string")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ApiError(400, f"{name} must be a valid ISO-8601 timestamp") from exc
    if parsed.tzinfo is None:
        raise ApiError(400, f"{name} must include a timezone")
    parsed = parsed.astimezone(timezone.utc)
    return (
        parsed.isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        parsed,
    )


def evaluate_preferences(preferences: Any) -> dict[str, Any]:
    if preferences is None:
        preferences = {}
    if not isinstance(preferences, dict):
        raise ApiError(400, "preferences must be a JSON object")
    target_input = preferences.get("target_temperature_c", 26)
    deadband_input = preferences.get("temperature_deadband_c", 2)
    target = _optional_number(26 if target_input in (None, "") else target_input,
                              "target_temperature_c", 16, 32)
    deadband = _optional_number(2 if deadband_input in (None, "") else deadband_input,
                                "temperature_deadband_c", 1, 7)
    settings = {key: preferences[key] for key in DEFAULT_POLICY_SETTINGS if key in preferences}
    settings.setdefault("temperature_lower_c", target-deadband)
    settings.setdefault("temperature_upper_c", target+deadband)
    try:
        settings = validate_policy_settings(settings)
    except ValueError as exc:
        raise ApiError(400, str(exc)) from exc
    lower, upper = settings["temperature_lower_c"], settings["temperature_upper_c"]
    return {**settings, "target_temperature_c": (lower+upper)/2,
            "temperature_deadband_c": (upper-lower)/2,
            "temperature_lower_limit_c": lower, "temperature_upper_limit_c": upper}


def _trend_state(value: float | None, threshold: float) -> str:
    if value is None:
        return "unavailable"
    if value >= threshold:
        return "rising"
    if value <= -threshold:
        return "falling"
    return "stable"


def evaluate_environment_data(
    environment: Any,
    preferences: Any,
) -> dict[str, Any]:
    if environment is None:
        environment = {}
    if preferences is None:
        preferences = {}
    if not isinstance(environment, dict):
        raise ApiError(400, "environment must be a JSON object")
    preferences_applied = evaluate_preferences(preferences)

    legacy_source = str(environment.get("source", "")).strip().lower()
    default_origin = (
        "manual_test"
        if legacy_source == "manual_test"
        else "esp32_telemetry"
        if legacy_source == "sht31"
        else "unknown"
    )
    input_origin = (
        str(environment.get("input_origin", default_origin)).strip().lower()
        or "unknown"
    )
    if input_origin not in {"manual_test", "esp32_telemetry", "unknown"}:
        raise ApiError(400, "Unsupported environment input origin")

    default_sensor_model = "sht31" if legacy_source == "sht31" else "unknown"
    sensor_model = (
        str(environment.get("sensor_model", default_sensor_model)).strip().lower()
        or "unknown"
    )
    if sensor_model not in {"sht31", "unknown"}:
        raise ApiError(400, "Unsupported environment sensor model")

    samples_input = environment.get("samples")
    if samples_input is None:
        legacy_keys = {
            "ambient_temperature_c",
            "relative_humidity_percent",
            "measured_at",
        }
        samples_input = (
            [
                {
                    "ambient_temperature_c": environment.get("ambient_temperature_c"),
                    "relative_humidity_percent": environment.get("relative_humidity_percent"),
                    "measured_at": environment.get("measured_at"),
                }
            ]
            if any(key in environment for key in legacy_keys)
            else []
        )
    if not isinstance(samples_input, list) or len(samples_input) > 240:
        raise ApiError(400, "environment.samples must be an array with at most 240 items")

    normalized_samples: list[dict[str, Any]] = []
    for index, sample in enumerate(samples_input):
        if not isinstance(sample, dict):
            raise ApiError(400, f"environment.samples[{index}] must be an object")
        measured_at_normalized, measured_dt = _parse_iso_datetime(
            sample.get("measured_at"),
            f"environment.samples[{index}].measured_at",
        )
        normalized_samples.append(
            {
                "ambient_temperature_c": _optional_number(
                    sample.get("ambient_temperature_c"),
                    f"environment.samples[{index}].ambient_temperature_c",
                    -40,
                    125,
                ),
                "relative_humidity_percent": _optional_number(
                    sample.get("relative_humidity_percent"),
                    f"environment.samples[{index}].relative_humidity_percent",
                    0,
                    100,
                ),
                "measured_at": measured_at_normalized,
                "_measured_dt": measured_dt,
            }
        )

    if normalized_samples:
        normalized_samples.sort(
            key=lambda sample: (
                sample["_measured_dt"] is not None,
                sample["_measured_dt"] or datetime.min.replace(tzinfo=timezone.utc),
            )
        )
    current = normalized_samples[-1] if normalized_samples else {
        "ambient_temperature_c": None,
        "relative_humidity_percent": None,
        "measured_at": None,
        "_measured_dt": None,
    }
    temperature_c = current["ambient_temperature_c"]
    humidity_percent = current["relative_humidity_percent"]
    measured_at_normalized = current["measured_at"]
    measurement_age_seconds: float | None = None
    timestamp_state = "missing"
    measured_dt = current["_measured_dt"]
    if measured_dt is not None:
        measurement_age_seconds = (datetime.now(timezone.utc) - measured_dt).total_seconds()
        if measurement_age_seconds < 0:
            timestamp_state = "future"
        elif measurement_age_seconds <= FRESH_ENVIRONMENT_MAX_AGE_SECONDS:
            timestamp_state = "fresh"
        else:
            timestamp_state = "stale"

    if temperature_c is None and humidity_percent is None:
        data_status = "missing"
    elif temperature_c is None or humidity_percent is None:
        data_status = "incomplete"
    elif timestamp_state == "fresh":
        data_status = "valid"
    elif timestamp_state == "stale":
        data_status = "stale"
    elif timestamp_state == "future":
        data_status = "future_timestamp"
    else:
        data_status = "timestamp_missing"

    lower_limit = preferences_applied["temperature_lower_limit_c"]
    upper_limit = preferences_applied["temperature_upper_limit_c"]
    if temperature_c is None:
        temperature_band_state = "unavailable"
    elif temperature_c <= lower_limit:
        temperature_band_state = "below_lower_limit"
    elif temperature_c >= upper_limit:
        temperature_band_state = "above_upper_limit"
    else:
        temperature_band_state = "within_comfort_band"

    if humidity_percent is None:
        humidity_reference_state = "unavailable"
    elif humidity_percent < 30:
        humidity_reference_state = "low"
    elif humidity_percent <= 60:
        humidity_reference_state = "reference_range_30_60"
    else:
        humidity_reference_state = "high"

    temperature_slope: float | None = None
    humidity_slope: float | None = None
    trend_window_seconds: float | None = None
    complete_trend_samples = [
        sample
        for sample in normalized_samples
        if sample["_measured_dt"] is not None
        and sample["ambient_temperature_c"] is not None
        and sample["relative_humidity_percent"] is not None
    ]
    if len(complete_trend_samples) >= 2:
        first = complete_trend_samples[0]
        last = complete_trend_samples[-1]
        trend_window_seconds = round(
            (last["_measured_dt"] - first["_measured_dt"]).total_seconds(), 1
        )
        if trend_window_seconds >= 30:
            minutes = trend_window_seconds / 60
            temperature_slope = round(
                (last["ambient_temperature_c"] - first["ambient_temperature_c"])
                / minutes,
                3,
            )
            humidity_slope = round(
                (last["relative_humidity_percent"] - first["relative_humidity_percent"])
                / minutes,
                3,
            )

    trend_data_status = (
        "valid"
        if temperature_slope is not None and humidity_slope is not None
        else "insufficient_samples"
    )
    if temperature_c is None or humidity_percent is None:
        operating_range_status = "unavailable"
    elif 5 <= temperature_c <= 40 and 10 <= humidity_percent <= 90:
        operating_range_status = "within_product_control_range"
    else:
        operating_range_status = "outside_product_control_range"

    public_samples = [
        {key: value for key, value in sample.items() if not key.startswith("_")}
        for sample in normalized_samples
    ]

    return {
        "ambient_temperature_c": temperature_c,
        "relative_humidity_percent": humidity_percent,
        "measured_at": measured_at_normalized,
        "measurement_age_seconds": measurement_age_seconds,
        "input_origin": input_origin,
        "sensor_model": sensor_model,
        "sensor_id": str(
            environment.get(
                "sensor_id",
                "manual-sht31" if input_origin == "manual_test" else "unknown",
            )
        ).strip()[:80]
        or "unknown",
        "data_status": data_status,
        "operating_range_status": operating_range_status,
        "temperature_band_state": temperature_band_state,
        "humidity_reference_state": humidity_reference_state,
        "temperature_trend_c_per_min": temperature_slope,
        "temperature_trend_state": _trend_state(temperature_slope, 0.05),
        "humidity_trend_percent_per_min": humidity_slope,
        "humidity_trend_state": _trend_state(humidity_slope, 0.2),
        "trend_window_seconds": trend_window_seconds,
        "trend_sample_count": len(complete_trend_samples),
        "trend_data_status": trend_data_status,
        "samples": public_samples,
    }


def evaluate_actuator_state(actuator_state: Any) -> dict[str, Any]:
    if actuator_state is None:
        actuator_state = {}
    if not isinstance(actuator_state, dict):
        raise ApiError(400, "actuator_state must be a JSON object")
    air_conditioner = actuator_state.get("air_conditioner", {})
    if air_conditioner is None:
        air_conditioner = {}
    if not isinstance(air_conditioner, dict):
        raise ApiError(400, "actuator_state.air_conditioner must be an object")

    power_state = str(air_conditioner.get("power_state", "unknown")).strip().lower()
    if power_state not in {"on", "off", "unknown"}:
        raise ApiError(400, "Unsupported air-conditioner power_state")
    mode = str(air_conditioner.get("mode", "unknown")).strip().lower()
    if mode not in {"cool", "dry", "heat", "fan", "auto", "off", "unknown"}:
        raise ApiError(400, "Unsupported air-conditioner mode")
    fan_level = str(air_conditioner.get("fan_level", "unknown")).strip().lower()
    if fan_level not in {"auto", "low", "medium", "high", "unknown"}:
        raise ApiError(400, "Unsupported air-conditioner fan_level")
    airflow_toward_bed = air_conditioner.get("airflow_toward_bed")
    if airflow_toward_bed not in {True, False, None}:
        raise ApiError(400, "airflow_toward_bed must be true, false, or null")
    setpoint = _optional_number(
        air_conditioner.get("setpoint_temperature_c"),
        "actuator_state.air_conditioner.setpoint_temperature_c",
        16,
        30,
    )
    reported_at, reported_dt = _parse_iso_datetime(
        air_conditioner.get("reported_at"),
        "actuator_state.air_conditioner.reported_at",
    )
    age_seconds: float | None = None
    timestamp_state = "missing"
    if reported_dt is not None:
        age_seconds = round((datetime.now(timezone.utc) - reported_dt).total_seconds(), 1)
        if age_seconds < -60:
            timestamp_state = "future"
        elif age_seconds <= 60:
            timestamp_state = "fresh"
        else:
            timestamp_state = "stale"

    any_state = any(
        value not in {None, "unknown"}
        for value in (power_state, mode, fan_level, setpoint, airflow_toward_bed)
    )
    complete_state = power_state != "unknown" and (
        power_state == "off"
        or (mode != "unknown" and fan_level != "unknown" and setpoint is not None)
    )
    if not any_state:
        data_status = "missing"
    elif not complete_state:
        data_status = "incomplete"
    elif timestamp_state == "fresh":
        data_status = "valid"
    elif timestamp_state == "stale":
        data_status = "stale"
    elif timestamp_state == "future":
        data_status = "future_timestamp"
    else:
        data_status = "timestamp_missing"

    return {
        "air_conditioner": {
            "power_state": power_state,
            "mode": mode,
            "setpoint_temperature_c": setpoint,
            "fan_level": fan_level,
            "airflow_toward_bed": airflow_toward_bed,
            "reported_at": reported_at,
            "state_age_seconds": age_seconds,
            "data_status": data_status,
        }
    }


def evaluate_control_context(control_context: Any, preferences: Any = None) -> dict[str, Any]:
    if control_context is None:
        control_context = {}
    if not isinstance(control_context, dict):
        raise ApiError(400, "control_context must be a JSON object")
    execution_mode = str(
        control_context.get("execution_mode", "dry_run")
    ).strip().lower()
    if execution_mode != "dry_run":
        raise ApiError(400, "Manual image tests only support execution_mode=dry_run")
    manual_override = control_context.get("manual_override_active", False)
    if not isinstance(manual_override, bool):
        raise ApiError(400, "manual_override_active must be a boolean")
    last_command_at, last_command_dt = _parse_iso_datetime(
        control_context.get("last_command_at"),
        "control_context.last_command_at",
    )
    event_handled = control_context.get("event_handled", False)
    if type(event_handled) is not bool:
        raise ApiError(400, "event_handled must be a boolean")
    event_active = control_context.get("event_active", event_handled)
    if type(event_active) is not bool:
        raise ApiError(400, "event_active must be a boolean")
    event_active = event_active or event_handled
    cooldown_seconds = evaluate_preferences(preferences)["command_cooldown_seconds"]
    cooldown_remaining = 0
    if last_command_dt is not None:
        age = (datetime.now(timezone.utc) - last_command_dt).total_seconds()
        cooldown_remaining = max(0, cooldown_seconds - age)
    return {
        "execution_mode": "dry_run",
        "manual_override_active": manual_override,
        "last_command_at": last_command_at,
        "command_cooldown_seconds": cooldown_seconds,
        "cooldown_remaining_seconds": cooldown_remaining,
        "policy_version": POLICY_VERSION,
        "event_handled": event_handled,
        "event_active": event_active,
    }


def evaluate_capture_timing(
    event_id: Any = None,
    before_captured_at: Any = None,
    after_captured_at: Any = None,
    confirmation_captured_at: Any = None,
    confirmation_target_seconds: int = CONFIRMATION_TARGET_SECONDS,
) -> dict[str, Any]:
    if type(confirmation_target_seconds) is not int or not 20 <= confirmation_target_seconds <= 50:
        raise ApiError(400, "Confirmation target must be an integer from 20 to 50 seconds (capture margin)")
    if event_id in (None, ""):
        event_id_normalized = str(uuid.uuid4())
    else:
        try:
            event_id_normalized = str(uuid.UUID(str(event_id)))
        except (ValueError, AttributeError) as exc:
            raise ApiError(400, "event_id must be a valid UUID") from exc
    before_iso, before_dt = _parse_iso_datetime(
        before_captured_at,
        "before_captured_at",
    )
    after_iso, after_dt = _parse_iso_datetime(after_captured_at, "after_captured_at")
    confirmation_iso, confirmation_dt = _parse_iso_datetime(
        confirmation_captured_at,
        "confirmation_captured_at",
    )
    frame_interval_seconds: float | None = None
    order_status = "missing_timestamps"
    if before_dt is not None and after_dt is not None:
        frame_interval_seconds = (after_dt - before_dt).total_seconds()
        order_status = "valid" if frame_interval_seconds > 0 else "invalid_order"

    confirmation_delay_seconds: float | None = None
    confirmation_timing_status = "not_provided"
    confirmation_due_at: str | None = None
    if after_dt is not None:
        confirmation_due_at = (
            after_dt + timedelta(seconds=confirmation_target_seconds)
        ).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    if confirmation_dt is not None:
        if after_dt is None:
            confirmation_timing_status = "after_timestamp_missing"
        else:
            confirmation_delay_seconds = (confirmation_dt - after_dt).total_seconds()
            if confirmation_delay_seconds < 20:
                confirmation_timing_status = "too_early"
            elif confirmation_delay_seconds <= 60:
                confirmation_timing_status = "within_window"
            else:
                confirmation_timing_status = "late"
    return {
        "event_id": event_id_normalized,
        "before_captured_at": before_iso,
        "after_captured_at": after_iso,
        "frame_interval_seconds": frame_interval_seconds,
        "frame_order_status": order_status,
        "confirmation_captured_at": confirmation_iso,
        "confirmation_due_at": confirmation_due_at,
        "confirmation_delay_seconds": confirmation_delay_seconds,
        "confirmation_timing_status": confirmation_timing_status,
    }


def evaluate_confirmation_state(
    visual_candidate_accepted: bool,
    confirmation_assessment: dict[str, Any] | None,
    capture_timing: dict[str, Any],
) -> dict[str, Any]:
    if not visual_candidate_accepted:
        return {
            "confirmation_required": False,
            "confirmation_state": "not_required",
            "confirmation_frame_count": 0,
            "confirmed_at": None,
            "blanket_event_state": "none",
        }
    if confirmation_assessment is None:
        return {
            "confirmation_required": True,
            "confirmation_state": "pending",
            "confirmation_frame_count": 0,
            "confirmed_at": None,
            "blanket_event_state": "confirmation_pending",
        }

    usable = confirmation_assessment.get("image_usable") is True
    present = confirmation_assessment.get("person_state") == "present"
    coverage = confirmation_assessment.get("coverage_state")
    blanket_position = confirmation_assessment.get("blanket_position_assessment")
    confirmation_confidence = confirmation_assessment.get("confidence")
    confidence_usable = (
        not isinstance(confirmation_confidence, bool)
        and isinstance(confirmation_confidence, (int, float))
        and math.isfinite(confirmation_confidence)
        and float(confirmation_confidence) <= 1.0
        and float(confirmation_confidence) >= 0.70
    )
    exposure = confirmation_assessment.get("major_body_exposure_ratio")
    if isinstance(exposure, bool) or not isinstance(exposure, (int, float)) or not math.isfinite(exposure):
        exposure = None
    else:
        exposure = max(0.0, min(1.0, float(exposure)))
    persistent_support = (
        coverage in {"partially_uncovered", "uncovered"}
        and blanket_position == "blanket_away_from_body"
        and exposure is not None
        and exposure >= 0.25
    )
    recovered_support = (
        coverage == "covered"
        and blanket_position == "normal_coverage"
        and exposure is not None
        and exposure < 0.25
    )
    if (not usable or not present or not confidence_usable or exposure is None
            or confirmation_assessment.get("scene_verified") is not True):
        state = "indeterminate"
    elif persistent_support:
        state = "persistent_uncovered"
    elif recovered_support:
        state = "recovered"
    else:
        state = "indeterminate"

    timing_ok = (
        capture_timing.get("frame_order_status") == "valid"
        and capture_timing.get("confirmation_timing_status") == "within_window"
    )
    if state == "persistent_uncovered" and timing_ok:
        blanket_event_state = "confirmed"
        confirmed_at = capture_timing.get("confirmation_captured_at")
    elif state == "recovered" and timing_ok:
        blanket_event_state = "recovered"
        confirmed_at = capture_timing.get("confirmation_captured_at")
    elif state in {"persistent_uncovered", "recovered"}:
        blanket_event_state = "confirmation_pending"
        confirmed_at = None
    else:
        blanket_event_state = "indeterminate"
        confirmed_at = None
    return {
        "confirmation_required": blanket_event_state in {
            "confirmation_pending",
            "indeterminate",
        },
        "confirmation_state": state,
        "confirmation_frame_count": 1,
        "confirmed_at": confirmed_at,
        "blanket_event_state": blanket_event_state,
    }


def build_control_decision(
    visual_candidate_accepted: bool,
    confirmation: dict[str, Any],
    capture_timing: dict[str, Any],
    environment_analysis: dict[str, Any],
    preferences_applied: dict[str, Any],
    actuator_state: dict[str, Any],
    control_context: dict[str, Any],
    live_visual: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Preview the SAME pure policy used by AcController; never send a command.

    The submitted samples are replayed at their own timestamps, not synthesized
    from a slope or duration. Preview input is isolated from live controller
    memory, device state and hardware authorization.
    """
    live = live_visual or {"state": "uncertain", "confirmation_required": False}
    ac = actuator_state["air_conditioner"]
    blockers = [{"code": "dry_run_mode", "message": "人工图片测试只预览统一策略，不发送设备指令"}]
    pending = []
    def block(code, message):
        blockers.append({"code": code, "message": message})

    tracker = EnvironmentTracker()
    sensor, sensor_error = None, "environment_unavailable_or_expired"
    identity = ("manual-preview", "isolated-session")
    seen_times = {}
    conflicting_samples = False
    for sample in environment_analysis.get("samples", []):
        measured = timestamp(sample.get("measured_at"))
        if measured is None:
            continue
        values = (sample.get("ambient_temperature_c"), sample.get("relative_humidity_percent"))
        if measured in seen_times:
            conflicting_samples |= seen_times[measured] != values
            continue
        seen_times[measured] = values
        sensor, sensor_error = tracker.observe({
            "control_usable": True, "source": "sensor",
            "device_id": identity[0], "boot_id": identity[1],
            "sensor_id": environment_analysis.get("sensor_id", "manual-sht31"),
            "sample_sequence": len(seen_times), "measured_at": sample["measured_at"],
            "ambient_temperature_c": sample.get("ambient_temperature_c"),
            "relative_humidity_percent": sample.get("relative_humidity_percent"),
        }, measured, identity, preferences_applied["temperature_lower_limit_c"],
           preferences_applied["temperature_upper_limit_c"],
           environment_persistence_seconds=preferences_applied["environment_persistence_seconds"])

    if conflicting_samples:
        block("conflicting_environment_samples", "同一采样时间存在不同读数，不能建立持续条件")
    if environment_analysis["data_status"] != "valid" or sensor_error:
        block("environment_data_not_fresh", "缺少有效温湿度，或当前样本超过45秒")
    if environment_analysis["operating_range_status"] == "outside_product_control_range":
        block("environment_outside_control_range", "温湿度超出实验控制范围")
    if environment_analysis["input_origin"] == "manual_test":
        block("manual_test_input", "输入只代表模拟场景，不能授权真实控制")
    if ac["data_status"] != "valid":
        block("actuator_state_not_fresh", "请提供新鲜的空调初始假设状态")
    if capture_timing.get("frame_order_status") != "valid":
        block("capture_timestamps_invalid", "前后图片时间缺失或顺序无效")
    visual_at = timestamp(capture_timing.get(
        "confirmation_captured_at" if live.get("state_evidence_frame") == "confirmation" else "after_captured_at"))
    if visual_at is None or not 0 <= time.time() - visual_at <= VISUAL_MAX_AGE_SECONDS:
        block("visual_expired_or_missing", "视觉证据缺失、来自未来或超过180秒")
    if control_context["manual_override_active"]:
        block("manual_override_active", "人工接管中，自动策略不得覆盖")
    if control_context["cooldown_remaining_seconds"] > 0:
        block("command_cooldown_active", "仍在所配置的命令冷却期")
    if live.get("confirmation_required"):
        pending.append({"code": "confirmation_frame_required",
                        "message": f"需要后图20–60秒内的同场景确认帧；当前目标{preferences_applied['confirmation_target_seconds']}秒"})

    setpoint = ac.get("setpoint_temperature_c")
    current = {"power": ac["power_state"], "mode": ac["mode"],
               "setpoint_c": int(setpoint) if isinstance(setpoint, (int, float)) and float(setpoint).is_integer() else setpoint,
               "fan": ac["fan_level"], "swing_vertical": "off", "swing_horizontal": "off"}
    sensor = sensor or {}
    hard_blocks = {item["code"] for item in blockers} - {
        "dry_run_mode", "manual_test_input", "manual_override_active", "command_cooldown_active"}
    policy = decide_control(
        current_state=current,
        visual_state=live.get("state", "uncertain") if not hard_blocks else "uncertain",
        temperature_c=sensor.get("temperature_c"),
        environment_direction=sensor.get("direction"),
        environment_persistent=sensor.get("persistent", False) if not hard_blocks else False,
        min_setpoint_c=preferences_applied["min_setpoint_c"],
        max_setpoint_c=preferences_applied["max_setpoint_c"],
        temperature_lower_c=preferences_applied["temperature_lower_limit_c"],
        temperature_upper_c=preferences_applied["temperature_upper_limit_c"],
        cooldown_remaining_seconds=control_context["cooldown_remaining_seconds"],
        event_handled=control_context.get("event_handled", False),
        event_active=control_context.get("event_active", False) or live.get("state") in {"uncovered_candidate", "confirmed_uncovered"},
        manual_override=control_context["manual_override_active"],
        temperature_step_c=preferences_applied["temperature_step_c"],
        environment_persistence_seconds=preferences_applied["environment_persistence_seconds"],
    )
    actions = []
    if policy["status"] == "proposed" and not hard_blocks:
        actions.append({"device": "air_conditioner", "action": "set_temperature",
                        "from": setpoint, "to": policy["desired_setpoint_c"],
                        "would_execute": False, "reason_code": policy["reason"]})
    if policy["reason"] == "coverage_event_already_handled":
        block("coverage_event_already_handled", "本次持续未盖被事件已处理，不重复调节")
    loops = []
    if live.get("state") in {"uncovered_candidate", "confirmed_uncovered"}:
        loops.append("blanket_fast_loop")
    if sensor.get("persistent"):
        loops.append("environment_slow_loop")
    status = ("proposed" if actions else "awaiting_confirmation" if pending
              else "blocked" if hard_blocks or control_context["manual_override_active"]
              or control_context["cooldown_remaining_seconds"] > 0 else "monitor")
    return {
        "execution_mode": "dry_run", "proposal_status": status,
        "execution_allowed": False, "source_loops": loops, "proposed_actions": actions,
        "blockers": blockers, "pending_requirements": pending,
        "cooldown_remaining_seconds": control_context["cooldown_remaining_seconds"],
        "next_review_seconds": preferences_applied["confirmation_target_seconds"] if pending else ENVIRONMENT_POLL_SECONDS,
        "policy_version": POLICY_VERSION, "policy_decision": policy,
        "event_memory": {"source": "manual_test_context", "handled": control_context.get("event_handled", False),
                         "active": control_context.get("event_active", False),
                         "meaning": "仅模拟本事件记忆；不会读取或重置真实自动控制事件"},
        "loop_evidence": {
            "temperature_persistence_met": bool(sensor.get("persistent")),
            "required_persistence_seconds": preferences_applied["environment_persistence_seconds"],
            "cold_environment_signal": bool(sensor.get("persistent") and sensor.get("direction") == "cold"),
            "warm_environment_signal": bool(sensor.get("persistent") and sensor.get("direction") == "hot"),
            "humid_environment_signal": False, "humidity_control_enabled": False,
            "confirmed_uncovered_signal": live.get("state") == "confirmed_uncovered",
        },
        "preferences_reference": dict(preferences_applied),
    }


def finalize_comparison_assessment(
    assessment: dict[str, Any],
    environment_data: dict[str, Any] | None = None,
    preferences_applied: dict[str, Any] | None = None,
    actuator_state: dict[str, Any] | None = None,
    control_context: dict[str, Any] | None = None,
    capture_timing: dict[str, Any] | None = None,
    confirmation_assessment: dict[str, Any] | None = None,
) -> dict[str, Any]:
    before = assessment.get("before_frame")
    after = assessment.get("after_frame")
    components = assessment.get("confidence_components")
    if not isinstance(before, dict) or not isinstance(after, dict):
        raise ApiError(502, "The VLM returned invalid before/after frame results")
    if not isinstance(components, dict):
        raise ApiError(502, "The VLM returned no confidence components")

    # Do not expose legacy causality fields from older prompts or saved fixtures.
    assessment.pop("cause_assessment", None)
    assessment.pop("alternative_explanations", None)
    components.pop("cause_ambiguity_risk", None)

    before_quality = _score_component(components, "before_image_quality")
    after_quality = _score_component(components, "after_image_quality")
    scene_comparability = _score_component(components, "scene_comparability")
    person_consistency = _score_component(components, "person_consistency")
    assessment_clarity = _score_component(components, "assessment_clarity")

    limiting_image_quality = min(before_quality, after_quality)
    weighted_base = (
        0.20 * limiting_image_quality
        + 0.25 * scene_comparability
        + 0.20 * person_consistency
        + 0.35 * assessment_clarity
    )
    raw_confidence = weighted_base

    warnings: list[str] = []
    notes: list[str] = []
    before_exposure = _normalize_frame_coverage(before, "BEFORE", notes)
    after_exposure = _normalize_frame_coverage(after, "AFTER", notes)
    exposure_delta = max(0.0, after_exposure - before_exposure)
    exposure_delta_strength = min(1.0, exposure_delta / 0.50)
    blanket_position_strength = (
        1.0 if assessment.get("blanket_change") == "moved_away_from_body" else 0.0
    )
    displacement_severity = round(
        0.55 * after_exposure
        + 0.35 * exposure_delta_strength
        + 0.10 * blanket_position_strength,
        2,
    )
    frames_usable = (
        before.get("image_usable") is True and after.get("image_usable") is True
    )
    both_people_present = (
        before.get("person_state") == "present"
        and after.get("person_state") == "present"
    )
    comparable = (
        assessment.get("frames_comparable") is True
        and assessment.get("scene_change") in {"stable", "minor"}
        and scene_comparability >= 0.6
    )
    high_confidence_gates = {
        "both_images_quality_at_least_0_65": limiting_image_quality >= 0.65,
        "scene_comparability_at_least_0_75": scene_comparability >= 0.75,
        "person_consistency_at_least_0_75": person_consistency >= 0.75,
        "assessment_clarity_at_least_0_75": assessment_clarity >= 0.75,
    }

    coverage_rank = {"covered": 0, "partially_uncovered": 1, "uncovered": 2}
    before_rank = coverage_rank.get(str(before.get("coverage_state")))
    after_rank = coverage_rank.get(str(after.get("coverage_state")))
    coverage_reduced = (
        before_rank is not None and after_rank is not None and after_rank > before_rank
    )
    direct_displacement_support = (
        frames_usable
        and both_people_present
        and comparable
        and coverage_reduced
        and assessment.get("exposed_body_change") == "increased"
        and assessment.get("blanket_change") == "moved_away_from_body"
        and assessment.get("temporal_evidence")
        == "state_change_observed_between_frames"
    )
    reliable_no_change_support = (
        frames_usable
        and both_people_present
        and comparable
        and all(high_confidence_gates.values())
        and before_rank is not None
        and after_rank is not None
        and before_rank == after_rank
        and abs(after_exposure - before_exposure) <= 0.15
        and assessment.get("blanket_change") == "no_meaningful_change"
        and assessment.get("exposed_body_change") == "unchanged"
        and assessment.get("temporal_evidence") == "no_state_change_observed"
    )

    model_assessment = assessment.get("blanket_displacement_assessment")
    if not frames_usable:
        decision = "indeterminate"
        warnings.append("至少一张图片不可可靠使用")
    elif not comparable:
        decision = "indeterminate"
        warnings.append("两张图片的场景、机位或构图不足以可靠比较")
    elif not both_people_present:
        decision = "indeterminate"
        warnings.append("无法在两张图片中都确认床上有人")
    elif model_assessment == "detected" and direct_displacement_support:
        decision = "blanket_displacement_observed"
    elif model_assessment == "not_detected" and reliable_no_change_support:
        decision = "no_meaningful_blanket_displacement"
    else:
        decision = "indeterminate"
        if model_assessment == "detected":
            warnings.append("模型报告被子移开，但覆盖减少、暴露增加和移动方向证据未同时满足")
        elif model_assessment == "not_detected" and direct_displacement_support:
            warnings.append("逐项证据显示覆盖减少，但模型总判定为未检测到变化")
        elif model_assessment == "not_detected":
            warnings.append("模型报告未检测到变化，但可靠的无变化证据未同时满足")
        else:
            warnings.append("模型未能形成确定的被子变化结论")

    confidence_cap = 1.0
    cap_reasons: list[str] = []
    if not frames_usable:
        confidence_cap = min(confidence_cap, 0.35)
        cap_reasons.append("图片不可用，上限 0.35")
    if not comparable:
        confidence_cap = min(confidence_cap, 0.40)
        cap_reasons.append("两帧不可可靠比较，上限 0.40")
    if not both_people_present:
        confidence_cap = min(confidence_cap, 0.45)
        cap_reasons.append("两帧未都确认有人，上限 0.45")
    if not all(high_confidence_gates.values()):
        confidence_cap = min(confidence_cap, 0.79)
        cap_reasons.append("至少一个高置信度关键门槛未通过，上限 0.79")
    if decision == "indeterminate":
        confidence_cap = min(confidence_cap, 0.49)
        cap_reasons.append("最终结论不确定，上限 0.49")
    if warnings and decision != "indeterminate":
        confidence_cap = min(confidence_cap, 0.55)
        cap_reasons.append("存在一致性警告，上限 0.55")

    confidence = round(min(raw_confidence, confidence_cap), 2)
    event_score = round(0.70 * confidence + 0.30 * displacement_severity, 2)
    visual_trigger_candidate = (
        decision == "blanket_displacement_observed"
        and before.get("coverage_state") == "covered"
        and after.get("coverage_state") in {"partially_uncovered", "uncovered"}
        and after_exposure >= 0.45
        and exposure_delta >= 0.30
        and displacement_severity >= 0.60
        and confidence >= 0.80
        and event_score >= 0.75
        and all(high_confidence_gates.values())
    )
    if visual_trigger_candidate:
        visual_event = "significant_coverage_reduction_candidate"
    elif decision == "no_meaningful_blanket_displacement":
        visual_event = "no_significant_coverage_change"
    elif decision == "blanket_displacement_observed":
        visual_event = "coverage_reduction_below_candidate_threshold"
    else:
        visual_event = "indeterminate"

    decision_names = {
        "blanket_displacement_observed": "significant_coverage_reduction_observed",
        "no_meaningful_blanket_displacement": "no_significant_coverage_change",
        "indeterminate": "indeterminate",
    }
    assessment["validated_visual_decision"] = decision_names[decision]
    assessment["visual_comparison_confidence"] = confidence
    assessment["review_required"] = decision != "no_meaningful_blanket_displacement"
    assessment["validation_warnings"] = warnings
    assessment["validation_notes"] = notes
    assessment["exposure_ratio_delta"] = round(exposure_delta, 3)
    assessment["displacement_severity"] = displacement_severity
    assessment["visual_candidate_score"] = event_score
    assessment["visual_event"] = visual_event
    assessment["visual_candidate_accepted"] = visual_trigger_candidate

    environment_data = environment_data or evaluate_environment_data({}, {})
    preferences_applied = preferences_applied or evaluate_preferences({})
    actuator_state = actuator_state or evaluate_actuator_state({})
    control_context = evaluate_control_context(control_context or {}, preferences_applied)
    capture_timing = capture_timing or evaluate_capture_timing()
    assessment["capture_timing"] = capture_timing
    assessment["confirmation_frame"] = None
    initial_live = visual_state(assessment)
    confirmation = evaluate_confirmation_state(
        initial_live["state"] == "uncovered_candidate",
        confirmation_assessment,
        capture_timing,
    )
    assessment.update(confirmation)
    assessment["event_id"] = capture_timing["event_id"]
    assessment["capture_timing"] = capture_timing
    assessment["confirmation_frame"] = confirmation_assessment
    assessment["environment_analysis"] = environment_data
    assessment["preferences_applied"] = preferences_applied
    assessment["actuator_state"] = actuator_state
    assessment["control_context"] = control_context
    live = visual_state(assessment)
    assessment["live_visual"] = live
    # An empty confirmation closes occupancy, but never authorizes a command.
    if initial_live["state"] == "uncovered_candidate" and live["state"] == "empty":
        confirmation.update(confirmation_required=False, confirmation_state="empty",
                            blanket_event_state="empty", confirmed_at=capture_timing.get("confirmation_captured_at"))
        assessment.update(confirmation)
    assessment["control_decision"] = build_control_decision(
        visual_trigger_candidate,
        confirmation,
        capture_timing,
        environment_data,
        preferences_applied,
        actuator_state,
        control_context,
        live,
    )
    assessment["confidence_calculation"] = {
        "meaning": "对前后覆盖状态变化结论的把握，不是动作原因概率，也不是控制安全概率",
        "limiting_image_quality": round(limiting_image_quality, 4),
        "weighted_base": round(weighted_base, 4),
        "high_confidence_gates": high_confidence_gates,
        "raw_confidence": round(raw_confidence, 4),
        "applied_cap": round(confidence_cap, 2),
        "cap_reasons": cap_reasons,
        "formula": (
            "0.20×两图较低质量 + 0.25×场景可比性 + "
            "0.20×人物连续性 + 0.35×判定清晰度"
        ),
    }
    return assessment


def comparison_confirmation_frame(observed: dict[str, Any]) -> dict[str, Any]:
    """Use the same AFTER/confirmation scene checks for manual and live paths."""
    frame = copy.deepcopy(observed["after_frame"])
    components = observed["confidence_components"]
    frame["scene_verified"] = (observed.get("frames_comparable") is True
        and observed.get("scene_change") in {"stable", "minor"}
        and components["scene_comparability"] >= .70
        and observed["before_frame"].get("image_usable") is True
        and components["before_image_quality"] >= .65
        and (frame.get("person_state") == "empty" or components["person_consistency"] >= .65))
    frame["confidence"] = min(components["after_image_quality"], components["assessment_clarity"],
                              components["scene_comparability"])
    frame["blanket_position_assessment"] = ("normal_coverage" if frame.get("coverage_state") == "covered"
        else "blanket_away_from_body" if frame.get("coverage_state") in {"partially_uncovered", "uncovered"}
        else "no_person" if frame.get("person_state") == "empty" else "indeterminate")
    return frame


def call_vlm_compare(
    before_image_data_url: str,
    after_image_data_url: str,
    prompt: str,
    environment: Any = None,
    preferences: Any = None,
    actuator_state: Any = None,
    control_context: Any = None,
    event_id: Any = None,
    before_captured_at: Any = None,
    after_captured_at: Any = None,
    confirmation_image_data_url: Any = None,
    confirmation_captured_at: Any = None,
) -> dict[str, Any]:
    validate_image_data_url(before_image_data_url)
    validate_image_data_url(after_image_data_url)
    if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 16000:
        raise ApiError(400, "Comparison prompt must contain 1-16000 characters")
    preferences_applied = evaluate_preferences(preferences)
    environment_data = evaluate_environment_data(environment, preferences)
    actuator_state_data = evaluate_actuator_state(actuator_state)
    control_context_data = evaluate_control_context(control_context, preferences_applied)
    capture_timing = evaluate_capture_timing(
        event_id,
        before_captured_at,
        after_captured_at,
        confirmation_captured_at,
        preferences_applied["confirmation_target_seconds"],
    )

    payload = {
        "model": VLM_MODEL,
        "store": False,
        "instructions": (
            "You compare two time-ordered still images conservatively. Report only "
            "visible state changes. Never claim that a kick or its cause was directly "
            "observed. Never infer temperature, humidity, thermal comfort, HVAC state, "
            "or control actions. Do not output a final confidence; the server calculates it."
        ),
        "input": [
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": prompt + "\n服务器提供的采集时间（不是画面文字）：" + json.dumps(capture_timing, ensure_ascii=False)},
                    {
                        "type": "input_text",
                        "text": "BEFORE：这是时间较早的第一张图片。",
                    },
                    {
                        "type": "input_image",
                        "image_url": before_image_data_url,
                        "detail": "high",
                    },
                    {
                        "type": "input_text",
                        "text": "AFTER：这是时间较晚的第二张图片。",
                    },
                    {
                        "type": "input_image",
                        "image_url": after_image_data_url,
                        "detail": "high",
                    },
                ],
            }
        ],
        "reasoning": {"effort": "none"},
        "max_output_tokens": 2400,
        "text": {
            "format": {
                "type": "json_schema",
                "name": "blanket_change_comparison",
                "strict": True,
                "schema": VLM_COMPARE_SCHEMA,
            }
        },
    }
    response = request_vlm(payload)
    output_text = extract_response_text(response)
    try:
        assessment = json.loads(output_text)
    except json.JSONDecodeError as exc:
        raise ApiError(502, "The VLM comparison output was not valid JSON") from exc
    if not isinstance(assessment, dict):
        raise ApiError(502, "The VLM comparison output was not a JSON object")
    confirmation_result: dict[str, Any] | None = None
    confirmation_assessment: dict[str, Any] | None = None
    if confirmation_image_data_url not in (None, ""):
        if not isinstance(confirmation_image_data_url, str):
            raise ApiError(400, "confirmation_image_data_url must be a data URL")
        confirmation_result = call_vlm_compare(
            after_image_data_url, confirmation_image_data_url, prompt,
            before_captured_at=after_captured_at,
            after_captured_at=confirmation_captured_at,
        )
        confirmation_assessment = comparison_confirmation_frame(confirmation_result["assessment"])
    assessment = finalize_comparison_assessment(
        assessment,
        environment_data,
        preferences_applied,
        actuator_state_data,
        control_context_data,
        capture_timing,
        confirmation_assessment,
    )
    result = {
        "assessment": assessment,
        "provider": VLM_PROVIDER,
        "model": response.get("model", VLM_MODEL),
        "response_id": response.get("id"),
        "usage": response.get("usage"),
    }
    if confirmation_result is not None:
        result["confirmation_response_id"] = confirmation_result.get("response_id")
        result["confirmation_usage"] = confirmation_result.get("usage")
    return result


def capture_image_data_url(capture: dict[str, Any]) -> str:
    """Read only a saved capture, never a user-provided arbitrary disk path."""
    url = capture.get("url", "")
    if not isinstance(url, str) or not url.startswith("/captures/"):
        raise ApiError(400, "Automatic AI requires a saved capture")
    relative = Path(urllib.parse.unquote(url.removeprefix("/captures/")))
    if relative.is_absolute() or ".." in relative.parts:
        raise ApiError(403, "Forbidden capture path")
    resolved = (CAPTURES_DIR / relative).resolve(strict=True)
    if CAPTURES_DIR.resolve() not in resolved.parents or resolved.suffix.lower() != ".jpg":
        raise ApiError(403, "Forbidden capture path")
    if resolved.stat().st_size > MAX_IMAGE_BYTES:
        raise ApiError(400, "Capture exceeds the VLM image size limit")
    image = resolved.read_bytes()
    if not image.startswith(b"\xff\xd8\xff"):
        raise ApiError(400, "Saved capture is not a JPEG")
    return "data:image/jpeg;base64," + base64.b64encode(image).decode("ascii")


def automation_environment(history: list[dict[str, Any]], current: dict[str, Any],
                           capture: dict[str, Any]) -> dict[str, Any]:
    """Same-boot, deduplicated, contiguous real samples; never backfill a failure."""
    headers = capture.get("image_device_headers") or {}
    device, boot = headers.get("x-device-id"), headers.get("x-boot-id")
    sensor = current.get("sensor_id")
    result = {"input_origin": "esp32_telemetry", "sensor_model": "sht31",
              "sensor_id": sensor or "unknown", "samples": [],
              "collection_error": current.get("error"),
              "time_basis": "estimated_sensor_time_not_synchronized_exposure"}
    if (not device or not boot or not sensor or current.get("valid") is not True
            or current.get("device_id") != device or current.get("boot_id") != boot
            or current.get("device_base_url") != capture.get("device_base_url")):
        result["collection_error"] = result["collection_error"] or "current_sample_unavailable_or_identity_changed"
        return result
    now = datetime.now(timezone.utc)
    seen, samples = set(), []
    for record in [current, *history]:
        if (record.get("valid") is not True or record.get("device_id") != device
                or record.get("boot_id") != boot or record.get("sensor_id") != sensor
                or record.get("device_base_url") != capture.get("device_base_url")):
            continue
        sequence = record.get("sample_sequence")
        if type(sequence) is not int or sequence < 1 or sequence in seen:
            continue
        try:
            _, measured = _parse_iso_datetime(record.get("measured_at_estimated"), "sensor timestamp")
        except ApiError:
            continue
        # The longest configurable condition is 600s. Keep its starting sample
        # even when the latest valid sample is up to 45s old, plus one cadence
        # gap; this is history retention, NOT an extension of freshness gates.
        history_window = 600 + FRESH_ENVIRONMENT_MAX_AGE_SECONDS + ENVIRONMENT_MAX_GAP_SECONDS
        if measured is None or not -1 <= (now - measured).total_seconds() <= history_window:
            continue
        temperature, humidity = record.get("ambient_temperature_c"), record.get("relative_humidity_percent")
        if (type(temperature) not in (int, float) or not math.isfinite(temperature) or not -40 <= temperature <= 125
                or type(humidity) not in (int, float) or not math.isfinite(humidity) or not 0 <= humidity <= 100):
            continue
        seen.add(sequence)
        samples.append((measured, sequence, {"ambient_temperature_c": temperature,
                       "relative_humidity_percent": humidity, "measured_at": record["measured_at_estimated"]}))
    samples.sort(key=lambda sample: sample[0])
    # Only the contiguous suffix can establish a trend. Outages/reordered samples
    # must not be smoothed into a seemingly reliable first-to-last slope.
    contiguous = []
    for sample in samples:
        if contiguous and ((sample[0] - contiguous[-1][0]).total_seconds() > ENVIRONMENT_MAX_GAP_SECONDS
                           or sample[1] <= contiguous[-1][1]):
            contiguous = []
        contiguous.append(sample)
    if not contiguous or contiguous[-1][1] != current.get("sample_sequence"):
        result["collection_error"] = "current_sample_timing_invalid"
        return result
    # 600s at the supported 5s cadence requires 121 distinct endpoints.
    result["samples"] = [item[2] for item in contiguous[-240:]]
    result["collection_error"] = None
    return result


def persist_automatic_ai(record: dict[str, Any]) -> dict[str, Any]:
    """Local audit records contain capture references/results, not keys or images."""
    record = copy.deepcopy(record)
    record["execution_allowed"] = False
    now = datetime.now(timezone.utc)
    relative = Path(now.strftime("%Y/%m/%d")) / f"{uuid.UUID(record['job_id'])}.json"
    path = AUTOMATIC_AI_DIR / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    record["result_url"] = "/automatic-ai-results/" + relative.as_posix()
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(record, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
    temporary.replace(path)
    return record


def list_automatic_ai_results(limit: int = 20) -> list[dict[str, Any]]:
    paths = sorted(AUTOMATIC_AI_DIR.rglob("*.json"), key=lambda path: path.stat().st_mtime, reverse=True)[:limit]
    items = []
    for path in paths:
        try:
            items.append(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, ValueError):
            continue
    return items


def persist_ac_command(record: dict[str, Any]) -> None:
    folder = DATA_DIR / "ac_commands"
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / (str(uuid.UUID(record["command_id"])) + ".json")
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(record, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
    temporary.replace(path)


def _ac_device_session(base_url: str):
    """A private, bounded, no-redirect/no-proxy session. Never automatically retries."""
    base = normalize_base_url(base_url)
    assert_private_lan_host(base)
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    def request(path, body=None, token=None):
        headers = {} if body is None else {
            "Content-Type": "application/x-www-form-urlencoded", "X-DreamShield-IR-Token": token}
        req = urllib.request.Request(base + path, data=body, headers=headers,
                                     method="GET" if body is None else "POST")
        with opener.open(req, timeout=6) as response:
            if response.headers.get_content_type() != "application/json":
                raise ValueError("Device AC endpoint must return JSON")
            data = response.read(65537)
            if len(data) > 65536:
                raise ValueError("Device AC response is too large")
            value = json.loads(data)
            if not isinstance(value, dict):
                raise ValueError("Invalid device AC response")
            return value
    return request


def _validate_ac_capability(status: dict[str, Any]) -> dict[str, Any]:
    capability = status.get("ac_control")
    if not isinstance(capability, dict):
        raise ValueError("Full AC control requires main firmware 0.8.0 or newer")
    token = capability.get("request_token")
    if (capability.get("ready") is not True or capability.get("profile") != "yapofb14_basic_128"
            or type(capability.get("state_schema_version")) is not int or capability["state_schema_version"] != 1
            or capability.get("ac_acknowledged") is not False
            or not isinstance(token, str) or not 8 <= len(token) <= 128
            or any(not isinstance(status.get(key), str) or not 1 <= len(status[key]) <= 128
                   for key in ("device_id", "boot_id"))):
        raise ValueError("Device AC capability or identity is unavailable")
    return capability


def read_ac_device(base_url: str) -> dict[str, Any]:
    status = _ac_device_session(base_url)("/api/v1/status")
    _validate_ac_capability(status)
    # Session arming does not retain credentials or trigger a transmission.
    return {key: status[key] for key in ("device_id", "boot_id")}


def send_ac_command(command: dict[str, Any]) -> dict[str, Any]:
    request = _ac_device_session(command["device_base_url"])
    status = request("/api/v1/status")
    capability = _validate_ac_capability(status)
    if any(status[key] != command[key] for key in ("device_id", "boot_id")):
        raise ValueError("Device identity changed; explicitly re-arm AC control")
    state = command["state"]
    if set(state) != {"power", "mode", "setpoint_c", "fan", "swing_vertical", "swing_horizontal"}:
        raise ValueError("AC command must contain a complete canonical state")
    fields = {"command_id": command["command_id"], "boot_id": command["boot_id"], **state}
    body = urllib.parse.urlencode(fields).encode("ascii")
    result = request("/api/v1/ac/command", body, capability["request_token"])
    # Never forward an unexpected token or provider-controlled fields into UI/audit.
    return {key: result[key] for key in ("status", "command_id", "boot_id", "device_id", "state",
        "emitted", "duplicate", "transmitted_this_request", "ac_acknowledged", "cooldown_remaining_ms")
        if key in result}


def thermal_download_links(record: dict[str, Any]) -> dict[str, Any]:
    result = dict(record)
    for key, output in (("raw_file", "raw_url"), ("metadata_file", "metadata_url")):
        if isinstance(record.get(key), str):
            result[output] = "/thermal-captures/" + urllib.parse.quote(record[key], safe="/")
    return result


class AppState:
    def __init__(self) -> None:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        CAPTURES_DIR.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.capture_lock = threading.Lock()
        self.stop_event = threading.Event()
        self.environment_lock = threading.Lock()
        self.automation_settings_lock = threading.RLock()
        self.environment_history = EnvironmentHistory(DATA_DIR / "environment.sqlite3")
        self.last_environment: dict[str, Any] | None = None
        self.last_environment_error: str | None = None
        self.environment_effective: dict[str, Any] | None = None
        self.ac_control = AcController(send_ac_command, persist_ac_command)
        self.config = self._load_config()
        self.room_environment = RoomEnvironment()
        self.room_environment.restore(self.environment_history, self.config["device_base_url"])
        self.thermal = ThermalCaptureService(THERMAL_DIR)
        self._load_thermal_settings()
        self.thermal_model = ThermalModelService(self.thermal.latest_frame, DATA_DIR / "thermal_model")
        self.ac_control.set_policy_settings(self.config["control_policy"])
        self.last_capture: dict[str, Any] | None = None
        self.last_error: str | None = None
        self.next_capture_epoch_ms: int | None = None
        self._reset_next_capture()
        # Cloud authorization is session-only: restart never resumes AI uploads.
        self.automatic_ai = AutoAIEngine(compare=self.compare_captures,
                                        confirm=self.confirm_capture,
                                        capture=self.capture_once,
                                        persist=persist_automatic_ai,
                                        on_result=self.integrate_ai_result)
        self.thermal_loop = ThermalClosedLoop(self.thermal_model, self.ac_control,
            make_reviewer(lambda payload: request_vlm(payload), extract_response_text, VLM_MODEL,
                          BASE_DIR / "thermal_closed_loop_prompt.txt"),
            lambda: self.live_environment({"fallback": copy.deepcopy(self.environment_effective) or {}}),
            lambda: copy.deepcopy(self.config["control_policy"]), DATA_DIR / "thermal_closed_loop")

    def update_thermal_loop(self, body):
        with self.automation_settings_lock:
            if body.get("enabled") is True:
                ai = self.automatic_ai.snapshot()
                if ai["enabled"] or ai["busy"]:
                    raise ApiError(409, "请先停止原自动 AI，等待任务结束")
                if not get_vlm_api_key():
                    raise ApiError(503, "请先配置本地 VLM API 密钥并重新启动服务器")
                if body.get("mode") == "automatic" and (not self.config["environment_recording_enabled"]
                        or self.config["environment_interval_seconds"] > 30):
                    raise ApiError(409, "自动闭环需要开启温湿度记录，轮询间隔不超过30秒")
            try:
                return self.thermal_loop.configure(body)
            except ValueError as exc:
                raise ApiError(400, str(exc)) from None

    def _load_thermal_settings(self) -> None:
        # Saved addresses survive restart, but collection needs a fresh opt-in.
        # This does not migrate or change existing camera/control configuration.
        try:
            settings = json.loads(THERMAL_SETTINGS_PATH.read_text(encoding="utf-8"))
            if isinstance(settings, dict):
                settings["enabled"] = False
                self.thermal.configure(settings, control_base_url=self.config["device_base_url"])
        except (OSError, ValueError, ThermalError):
            pass

    def update_thermal_settings(self, body: dict[str, Any]) -> dict[str, Any]:
        with self.automation_settings_lock:
            before = self.thermal.snapshot()["config"]
            proposed = dict(body)
            if any(key in body for key in ("base_url", "paired_control_base_url", "interval_seconds")):
                self.thermal.set_preview(False)
                proposed["enabled"] = False
            # Address/pair changes require a separate explicit start.
            if any(key in body and body[key] != before[key]
                   for key in ("base_url", "paired_control_base_url")):
                proposed["enabled"] = False
            snapshot = self.thermal.configure(proposed, control_base_url=self.config["device_base_url"])
            try:
                temporary = THERMAL_SETTINGS_PATH.with_suffix(".tmp")
                temporary.write_text(json.dumps(snapshot["config"], ensure_ascii=False, indent=2), encoding="utf-8")
                temporary.replace(THERMAL_SETTINGS_PATH)
            except OSError as exc:
                # A failed save must never leave an unreported background task running.
                self.thermal.configure({**before, "enabled": False})
                raise ApiError(500, "Cannot save thermal settings; collection was stopped") from exc
            return snapshot

    def thermal_environment_context(self) -> dict[str, Any]:
        with self.lock:
            base_url = self.config["device_base_url"]
            observation = copy.deepcopy(self.last_environment)
        result = {"paired_control_base_url": base_url,
                  "association": "configured_pair_cached_observation",
                  "synchronized": False, "new_sensor_sample": False,
                  "status": "unavailable", "observation": None}
        if observation and observation.get("device_base_url") == base_url:
            measured = observation.get("measured_at_estimated")
            try:
                age = (datetime.now(timezone.utc) - datetime.fromisoformat(measured.replace("Z", "+00:00"))).total_seconds()
            except (TypeError, ValueError, AttributeError):
                age = None
            result.update(observation=observation, sample_age_seconds=age,
                          status="fresh" if observation.get("valid") is True and age is not None and 0 <= age <= 45 else "stale")
        return result

    def capture_thermal(self, source: str = "manual") -> dict[str, Any]:
        context = self.thermal_environment_context()
        return self.thermal.capture_once(source, control_base_url=context["paired_control_base_url"],
                                         environment_context=context)

    def thermal_capture_loop(self) -> None:
        next_run, next_save, previous = 0.0, 0.0, None
        while not self.stop_event.wait(0.25):
            snapshot = self.thermal.snapshot()
            settings, preview = snapshot["config"], snapshot["preview_enabled"]
            identity = (settings, preview)
            if identity != previous:
                next_run, next_save, previous = 0.0, 0.0, identity
            if not (settings["enabled"] or preview) or time.monotonic() < next_run:
                continue
            try:
                save = settings["enabled"] and time.monotonic() >= next_save
                self.capture_thermal("scheduled" if save else "preview")
                if save:
                    next_save = time.monotonic() + settings["interval_seconds"]
            except ThermalError as exc:
                if exc.code == "thermal_pairing_mismatch":
                    self.thermal.configure({"enabled": False})
                    self.thermal.set_preview(False)
            except Exception:
                self.thermal.configure({"enabled": False})
                self.thermal.set_preview(False)
            finally:
                next_run = time.monotonic() + (1 if preview else settings["interval_seconds"])

    def _load_config(self) -> dict[str, Any]:
        if not CONFIG_PATH.exists():
            return copy.deepcopy(DEFAULT_CONFIG)
        try:
            loaded = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return copy.deepcopy(DEFAULT_CONFIG)
        config = copy.deepcopy(DEFAULT_CONFIG)
        config.update({key: loaded[key] for key in config if key in loaded})
        try:
            config["device_base_url"] = normalize_base_url(config["device_base_url"])
            config["capture_interval_seconds"] = max(
                5, min(86400, int(config["capture_interval_seconds"]))
            )
            config["schedule_enabled"] = bool(config["schedule_enabled"])
            config["environment_recording_enabled"] = bool(config["environment_recording_enabled"])
            config["environment_interval_seconds"] = max(5, min(3600, int(config["environment_interval_seconds"])))
            config["control_policy"] = validate_policy_settings(config["control_policy"])
            sample = config.get("sensor_sample_interval_seconds")
            if sample is not None and (type(sample) is not int or not 5 <= sample <= 30):
                config["sensor_sample_interval_seconds"] = None
            # Migrate old files without changing their policy or polling rate.
            # A known previous sampling value becomes the new saved target;
            # otherwise use the historical 15-second physical default.
            if "sensor_sample_target_seconds" not in loaded:
                config["sensor_sample_target_seconds"] = config["sensor_sample_interval_seconds"] or 15
            target = config["sensor_sample_target_seconds"]
            if type(target) is not int or not 5 <= target <= 30:
                config["sensor_sample_target_seconds"] = config["sensor_sample_interval_seconds"] or 15
            matched = identify_policy_preset(config["control_policy"], config["environment_interval_seconds"],
                                             config["sensor_sample_target_seconds"])
            if loaded.get("control_preset") != "custom":
                config["control_preset"] = matched
        except (ApiError, TypeError, ValueError):
            return copy.deepcopy(DEFAULT_CONFIG)
        return config

    def _save_config(self) -> None:
        temporary = CONFIG_PATH.with_suffix(".tmp")
        temporary.write_text(
            json.dumps(self.config, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        temporary.replace(CONFIG_PATH)

    def _reset_next_capture(self) -> None:
        if self.config["schedule_enabled"]:
            self.next_capture_epoch_ms = int(
                (time.time() + self.config["capture_interval_seconds"]) * 1000
            )
        else:
            self.next_capture_epoch_ms = None

    def update_config(self, body: dict[str, Any]) -> dict[str, Any]:
        with self.automation_settings_lock:
            old_base = self.config["device_base_url"]
            config = self._update_config(body)
            if config["device_base_url"] != old_base or not config["schedule_enabled"]:
                self.automatic_ai.disable("device_changed" if config["device_base_url"] != old_base else "schedule_disabled")
                if config["device_base_url"] != old_base:
                    self.thermal.configure({"enabled": False, "paired_control_base_url": ""})
                    self.ac_control.disable("device_changed")
                else:
                    self.ac_control.disable_automatic("schedule_disabled")
            elif config["capture_interval_seconds"] > 300:
                self.automatic_ai.disable("capture_interval_too_large")
                self.ac_control.disable_automatic("capture_interval_too_large")
            if not config["environment_recording_enabled"] or config["environment_interval_seconds"] > 30:
                self.ac_control.disable_automatic("environment_recording_unavailable")
            return config

    def _update_config(self, body: dict[str, Any]) -> dict[str, Any]:
        with self.lock:
            updated = dict(self.config)
            if "device_base_url" in body:
                updated["device_base_url"] = normalize_base_url(
                    str(body["device_base_url"])
                )
            if "capture_interval_seconds" in body:
                try:
                    interval = int(body["capture_interval_seconds"])
                except (TypeError, ValueError) as exc:
                    raise ApiError(400, "Capture interval must be an integer") from exc
                if not 5 <= interval <= 86400:
                    raise ApiError(400, "Capture interval must be 5-86400 seconds")
                updated["capture_interval_seconds"] = interval
            if "schedule_enabled" in body:
                updated["schedule_enabled"] = bool(body["schedule_enabled"])
            if "environment_recording_enabled" in body:
                if type(body["environment_recording_enabled"]) is not bool:
                    raise ApiError(400, "environment_recording_enabled must be a boolean")
                updated["environment_recording_enabled"] = body["environment_recording_enabled"]
            if "environment_interval_seconds" in body:
                interval = body["environment_interval_seconds"]
                if type(interval) is not int or not 5 <= interval <= 3600:
                    raise ApiError(400, "Environment interval must be an integer from 5 to 3600 seconds")
                updated["environment_interval_seconds"] = interval
            previous = self.config
            if updated["device_base_url"] != previous["device_base_url"]:
                updated["sensor_sample_interval_seconds"] = None
            if updated["environment_interval_seconds"] != previous["environment_interval_seconds"]:
                updated["control_preset"] = identify_policy_preset(updated["control_policy"],
                    updated["environment_interval_seconds"], updated["sensor_sample_target_seconds"])
            self.config = updated
            try:
                self._save_config()
            except OSError as exc:
                self.config = previous
                raise ApiError(500, "Cannot save settings; recording settings were not changed") from exc
            if updated["device_base_url"] != previous["device_base_url"]:
                self.room_environment = RoomEnvironment()
            if any(key in body for key in ("schedule_enabled", "capture_interval_seconds", "device_base_url")):
                self._reset_next_capture()
            return dict(self.config)

    def publish_room_environment(self, observation, started):
        with self.lock:
            if observation["device_base_url"] == self.config["device_base_url"]:
                self.room_environment.publish(observation, started)

    def room_environment_snapshot(self):
        with self.lock:
            return self.room_environment.snapshot(self.config["environment_recording_enabled"], datetime.now(timezone.utc))

    def snapshot(self) -> dict[str, Any]:
        with self.lock:
            result = {
                "config": dict(self.config),
                "room_environment": self.room_environment.snapshot(self.config["environment_recording_enabled"], datetime.now(timezone.utc)),
                "last_capture": self.last_capture,
                "last_error": self.last_error,
                "next_capture_epoch_ms": self.next_capture_epoch_ms,
                "last_environment": self.last_environment,
                "last_environment_error": self.last_environment_error,
                "environment_effective": self.environment_effective,
                "vlm_key_configured": bool(get_vlm_api_key()),
                "vlm_provider": VLM_PROVIDER,
                "vlm_model": VLM_MODEL,
            }
        result["automatic_ai"] = self.automatic_ai.snapshot()
        result["thermal"] = self.thermal.snapshot()
        result["ac_control"] = self.ac_control.snapshot()
        result["ac_control"]["automatic"]["environment_poll_seconds"] = result["config"]["environment_interval_seconds"]
        return result


    def policy_settings_snapshot(self) -> dict[str, Any]:
        with self.lock:
            return {"policy": copy.deepcopy(self.config["control_policy"]),
                    "preset": self.config["control_preset"], "presets": copy.deepcopy(CONTROL_PRESETS),
                    "defaults": dict(DEFAULT_POLICY_SETTINGS),
                    "limits": {"min_setpoint_c": [16, 30], "max_setpoint_c": [16, 30],
                        "temperature_lower_c": [18, 32], "temperature_upper_c": [18, 32],
                        "environment_persistence_seconds": [30, 600], "temperature_step_c": [1, 2],
                        "command_cooldown_seconds": [60, 3600], "confirmation_target_seconds": [20, 50],
                        "environment_interval_seconds": [5, 30], "sensor_sample_interval_seconds": [5, 30],
                        "sensor_sample_target_seconds": [5, 30]},
                    "environment_interval_seconds": self.config["environment_interval_seconds"],
                    "default_environment_interval_seconds": ENVIRONMENT_POLL_SECONDS,
                    "sensor_sample_interval_seconds": self.config.get("sensor_sample_interval_seconds"),
                    "sensor_sample_target_seconds": self.config["sensor_sample_target_seconds"],
                    "default_sensor_sample_interval_seconds": 15,
                    "sensor_setting_is_last_confirmed_only": True,
                    "sensor_setting_persistence": "until_device_restart",
                    "policy_version": POLICY_VERSION}

    def update_policy_settings(self, body: dict[str, Any]) -> dict[str, Any]:
        if (not isinstance(body, dict) or not body
                or set(body)-{"preset", "policy", "environment_interval_seconds", "sensor_sample_target_seconds"}):
            raise ApiError(400, "Use preset, policy, environment_interval_seconds and/or sensor_sample_target_seconds")
        with self.automation_settings_lock:
            selected = body.get("preset")
            if "preset" in body and (type(selected) is not str or selected not in {*CONTROL_PRESETS, "custom"}):
                raise ApiError(400, "preset must be light, balanced, efficient or custom")
            supplied = body.get("policy", {})
            if not isinstance(supplied, dict):
                raise ApiError(400, "policy must be an object")
            canonical = CONTROL_PRESETS.get(selected)
            try:
                policy = validate_policy_settings({**(canonical["policy"] if canonical else self.config["control_policy"]), **supplied})
            except ValueError as exc:
                raise ApiError(400, str(exc)) from exc
            interval = body.get("environment_interval_seconds", canonical["environment_interval_seconds"] if canonical
                                else self.config["environment_interval_seconds"])
            if type(interval) is not int or not 5 <= interval <= 30:
                raise ApiError(400, "Policy sensor polling interval must be 5-30 seconds")
            target = body.get("sensor_sample_target_seconds", canonical["sensor_sample_target_seconds"] if canonical
                              else self.config["sensor_sample_target_seconds"])
            if type(target) is not int or not 5 <= target <= 30:
                raise ApiError(400, "SHT31 sampling target must be an integer from 5 to 30 seconds")
            matched = identify_policy_preset(policy, interval, target)
            if canonical and matched != selected:
                raise ApiError(400, "Named preset values must match its definition; select custom to edit parameters")
            preset = selected if selected is not None else matched
            # Invalidate in-flight generation before swapping rules. No cloud call,
            # physical sampling or command is started by saving configuration.
            self.automatic_ai.disable("policy_changed_explicit_resume_required")
            self.ac_control.disable_automatic("policy_changed_explicit_resume_required")
            with self.lock:
                previous = self.config
                self.config = {**previous, "control_policy": policy, "environment_interval_seconds": interval,
                               "control_preset": preset, "sensor_sample_target_seconds": target}
                try:
                    self._save_config()
                except OSError as exc:
                    self.config = previous
                    raise ApiError(500, "Cannot save policy; previous values retained and automation paused") from exc
            self.ac_control.set_policy_settings(policy)
            return {**self.policy_settings_snapshot(), "automation_paused": True}

    def update_sensor_settings(self, body: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(body, dict) or set(body) != {"sample_interval_seconds"}:
            raise ApiError(400, "Use sample_interval_seconds only")
        interval = body["sample_interval_seconds"]
        if type(interval) is not int or not 5 <= interval <= 30:
            raise ApiError(400, "SHT31 sampling interval must be an integer from 5 to 30 seconds")
        with self.automation_settings_lock:
            self.automatic_ai.disable("sensor_settings_changed_explicit_resume_required")
            self.ac_control.disable_automatic("sensor_settings_changed_explicit_resume_required")
            try:
                result = apply_sensor_interval(self.config["device_base_url"], interval)
            except SensorSettingsError as exc:
                raise ApiError(exc.status, exc.message) from exc
            # Only a verified board reply is recorded. This is not a claim that
            # a future reboot will retain the setting; the board defaults to 15s.
            with self.lock:
                previous = self.config
                self.config = {**previous, "sensor_sample_interval_seconds": result["sample_interval_seconds"]}
                try:
                    self._save_config()
                except OSError as exc:
                    self.config = previous
                    raise ApiError(500, "Device sampling changed, but local record was not saved; automation remains paused") from exc
            return {**result, "automation_paused": True}

    def update_ac_control(self, body: dict[str, Any]) -> dict[str, Any]:
        if type(body.get("enabled")) is not bool or set(body) - {"enabled", "control_consent", "initial_state"}:
            raise ApiError(400, "Invalid AC control session settings")
        with self.automation_settings_lock:
            if not body["enabled"]:
                self.ac_control.disable("user_stopped")
                return self.ac_control.snapshot()
            if body.get("control_consent") is not True:
                raise ApiError(400, "Explicit consent is required; AC commands can power on/off the appliance")
            if self.ac_control.snapshot()["enabled"]:
                raise ApiError(409, "Disable AC control before changing its initial assumptions")
            try:
                identity = read_ac_device(self.config["device_base_url"])
                return self.ac_control.configure(enabled=True, base_url=self.config["device_base_url"],
                    **identity, initial_state=body.get("initial_state"))
            except ValueError as exc:
                raise ApiError(400, str(exc)) from exc
            except (OSError, urllib.error.URLError) as exc:
                raise ApiError(503, "Cannot reach the device AC interface; check IP and firmware 0.8.0") from exc

    def manual_ac_command(self, body: dict[str, Any]) -> dict[str, Any]:
        if set(body) != {"request_id", "state"}:
            raise ApiError(400, "A manual AC command requires request_id and the complete state")
        with self.automation_settings_lock:
            try:
                result = self.ac_control.manual(body["state"], body["request_id"])
                return {"ac_control": self.ac_control.snapshot(), "command": result}
            except ValueError as exc:
                raise ApiError(400, str(exc)) from exc
            except RuntimeError as exc:
                raise ApiError(409, str(exc)) from exc

    def update_ac_automatic(self, body: dict[str, Any]) -> dict[str, Any]:
        allowed = {"enabled", "min_setpoint_c", "max_setpoint_c", "temperature_lower_c", "temperature_upper_c"}
        if type(body.get("enabled")) is not bool or set(body) - allowed:
            raise ApiError(400, "Invalid automatic AC settings")
        with self.automation_settings_lock:
            if body["enabled"] and self.thermal_loop.status()["enabled"]:
                raise ApiError(409, "热像闭环运行中，请在热像判别页管理自动调节")
            if body["enabled"]:
                if any(key != "enabled" and value != self.config["control_policy"][key]
                       for key, value in body.items()):
                    raise ApiError(400, "Save changed thresholds on the policy settings page before resuming automation")
                if not self.automatic_ai.snapshot()["enabled"]:
                    raise ApiError(400, "Enable automatic AI before automatic AC adjustments")
                if (not self.config["schedule_enabled"] or not self.config["environment_recording_enabled"]
                        or self.config["environment_interval_seconds"] > 30):
                    raise ApiError(400, "Automatic AC requires scheduled capture and sensor recording every 5-30 seconds (15 recommended)")
            try:
                return self.ac_control.update_automatic(**body)
            except ValueError as exc:
                raise ApiError(400, str(exc)) from exc
            except RuntimeError as exc:
                raise ApiError(409, str(exc)) from exc

    def update_automatic_ai(self, body: dict[str, Any]) -> dict[str, Any]:
        if type(body.get("enabled")) is not bool:
            raise ApiError(400, "enabled must be a boolean")
        enabled = body["enabled"]
        with self.automation_settings_lock:
            if enabled and (self.thermal_loop.status()["enabled"] or self.thermal_loop.status()["busy"]):
                raise ApiError(409, "请先停止热像闭环并等待 VLM 结束，再启用原自动 AI")
            if not enabled:
                self.automatic_ai.disable("user_stopped")
                self.ac_control.disable_automatic("automatic_ai_stopped")
                return self.automatic_ai.snapshot()
            if body.get("cloud_upload_consent") is not True:
                raise ApiError(400, "Explicit cloud image-upload consent is required")
            if not get_vlm_api_key():
                raise ApiError(503, "Configure the VLM API key first")
            settings = {}
            for name, default, lower, upper in (
                ("min_interval_seconds", 60, 60, 3600),
                ("max_calls_per_hour", 20, 2, 120),
                ("max_calls_per_session", 50, 2, 500),
            ):
                value = body.get(name, default)
                if type(value) is not int or not lower <= value <= upper:
                    raise ApiError(400, f"{name} must be an integer from {lower} to {upper}")
                settings[name] = value
            if self.config["capture_interval_seconds"] > 300:
                raise ApiError(400, "For automatic AI, set the capture interval to 5-300 seconds (60 recommended)")
            base_url = normalize_base_url(str(body.get("device_base_url", self.config["device_base_url"])))
            interval = self.config["environment_interval_seconds"]
            if not 5 <= interval <= 30:
                raise ApiError(400, "Set environment polling to 5-30 seconds before enabling automatic AI")
            self.update_config({"device_base_url": base_url, "schedule_enabled": True,
                                "environment_recording_enabled": True, "environment_interval_seconds": interval})
            return self.automatic_ai.configure(enabled=True, device_base_url=base_url,
                confirmation_target_seconds=self.config["control_policy"]["confirmation_target_seconds"], **settings)

    def ai_environment(self, capture: dict[str, Any]) -> dict[str, Any]:
        current = self.observe_environment(capture["device_base_url"], "automatic_ai")
        try:
            history = self.environment_history.recent(capture["device_base_url"], 240)
        except (OSError, sqlite3.Error, ValueError):
            data = automation_environment([], current, capture)
            data["collection_error"] = "environment_history_unavailable"
            return data
        data = automation_environment(history, current, capture)
        # Query the last valid row independently of the recent history window.
        cached = self.environment_history.latest_valid(capture["device_base_url"])
        if cached:
            history = [*history, cached]
        data["fallback"] = resolve_last_valid(history, current, capture, datetime.now(timezone.utc))
        return data

    @staticmethod
    def live_environment(sensor_input: dict[str, Any]) -> dict[str, Any]:
        fallback = sensor_input.get("fallback") or {}
        reading = fallback.get("effective_observation") or {}
        return {"source": fallback.get("source", "unavailable"),
                "control_usable": fallback.get("control_usable") is True,
                "age_seconds": fallback.get("age_seconds"), "fault_reason": fallback.get("fault_reason"),
                "measured_at": reading.get("measured_at_estimated"),
                **{key: reading.get(key) for key in ("device_id", "boot_id", "sensor_id", "sample_sequence",
                    "ambient_temperature_c", "relative_humidity_percent")}}

    def integrate_ai_result(self, record: dict[str, Any]) -> dict[str, Any]:
        # Engine holds its generation lock. Do NOT acquire automation_settings_lock.
        env = (record.get("result") or {}).get("live_environment") or {}
        record["ac_control"] = self.ac_control.accept(record, env)
        return record

    def refresh_ai_decision(self, result: dict[str, Any], before: dict[str, Any],
                            after: dict[str, Any], confirmation: dict[str, Any] | None = None,
                            confirmation_assessment: dict[str, Any] | None = None) -> dict[str, Any]:
        result = copy.deepcopy(result)
        assessment = result["assessment"]
        timing = evaluate_capture_timing(assessment["capture_timing"]["event_id"],
                                        before["captured_at"], after["captured_at"],
                                        confirmation["captured_at"] if confirmation else None,
                                        self.config["control_policy"]["confirmation_target_seconds"])
        sensor_input = self.ai_environment(confirmation or after)
        preferences = evaluate_preferences(self.config["control_policy"])
        environment = evaluate_environment_data(sensor_input, preferences)
        # Never import the manual test form's mocked readings/AC state into automation.
        assessment = finalize_comparison_assessment(assessment, environment, preferences,
                       evaluate_actuator_state(None), evaluate_control_context(None), timing, confirmation_assessment)
        live = visual_state(assessment)
        assessment["live_visual"] = live
        assessment["confirmation_required"] = live["confirmation_required"]
        # Preview and real control share policy, but authorization/state memory
        # remain isolated. None of these fields are AC execution telemetry.
        fallback = sensor_input.get("fallback") or {}
        effective = fallback.get("effective_observation")
        if effective:
            environment.update(ambient_temperature_c=effective["ambient_temperature_c"],
                               relative_humidity_percent=effective["relative_humidity_percent"],
                               measured_at=fallback["original_measured_at"],
                               measurement_age_seconds=fallback["age_seconds"])
            if fallback["source"] == "last_valid":
                environment.update(data_status="last_valid" if fallback["control_usable"] else "fallback_expired",
                    temperature_trend_c_per_min=None, humidity_trend_percent_per_min=None,
                    temperature_trend_state="unknown", humidity_trend_state="unknown",
                    trend_data_status="disabled_cached_sample")
        environment.update(source=fallback.get("source", "unavailable"), fault_reason=fallback.get("fault_reason"),
                           age_seconds=fallback.get("age_seconds"),
                           fallback_control_usable=fallback.get("control_usable", False))
        result["assessment"] = assessment
        result["live_environment"] = self.live_environment(sensor_input)
        result["telemetry_collection"] = {"error": sensor_input.get("collection_error"),
                                         "time_basis": sensor_input["time_basis"]}
        result["mode"] = "automatic_vlm_observation"
        result["control_policy"] = "full_state_ac_v1_separately_enabled_legacy_compatible"
        result["execution_allowed"] = False
        return result

    def compare_captures(self, before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
        result = call_vlm_compare(capture_image_data_url(before), capture_image_data_url(after),
                                 COMPARE_PROMPT_PATH.read_text(encoding="utf-8"),
                                 event_id=str(uuid.uuid4()), before_captured_at=before["captured_at"],
                                 after_captured_at=after["captured_at"])
        return self.refresh_ai_decision(result, before, after)

    def confirm_capture(self, pair_result: dict[str, Any], before: dict[str, Any],
                        after: dict[str, Any], confirmation: dict[str, Any]) -> dict[str, Any]:
        check = call_vlm_compare(capture_image_data_url(after), capture_image_data_url(confirmation),
            COMPARE_PROMPT_PATH.read_text(encoding="utf-8"), before_captured_at=after["captured_at"],
            after_captured_at=confirmation["captured_at"])
        observed = check["assessment"]
        frame = comparison_confirmation_frame(observed)
        result = self.refresh_ai_decision(pair_result, before, after, confirmation, frame)
        result["confirmation_comparison"] = observed
        result["confirmation_response_id"] = check.get("response_id")
        result["confirmation_usage"] = check.get("usage")
        return result

    def capture_once(self, source: str, override_url: str | None = None) -> dict[str, Any]:
        if not self.capture_lock.acquire(blocking=False):
            raise ApiError(409, "A capture is already in progress")
        try:
            with self.lock:
                base_url = override_url or self.config["device_base_url"]
            base_url = normalize_base_url(base_url)
            image_started = datetime.now(timezone.utc)
            started = time.monotonic()
            image, content_type, image_headers = fetch_device_with_headers(base_url, "/api/v1/snapshot")
            image_received = datetime.now(timezone.utc)
            image_request_ms = (time.monotonic() - started) * 1000
            if content_type != "image/jpeg" or not image.startswith(b"\xff\xd8\xff"):
                raise ApiError(502, "The ESP32 did not return a valid JPEG")

            # One status query per saved image, even if continuous recording is off.
            # Missing SHT31 data must not discard a successfully captured JPEG.
            observation = self.observe_environment(base_url, "capture")
            pairing = associate_capture(image_headers, observation, image_started, image_received, image_request_ms)
            now = image_received
            folder = CAPTURES_DIR / now.strftime("%Y") / now.strftime("%m") / now.strftime("%d")
            folder.mkdir(parents=True, exist_ok=True)
            stem = now.strftime("%Y%m%dT%H%M%S_%fZ")
            image_path = folder / f"{stem}.jpg"
            metadata_path = folder / f"{stem}.json"
            image_path.write_bytes(image)
            relative = image_path.relative_to(CAPTURES_DIR).as_posix()
            result = {
                "captured_at": image_received.isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                "captured_at_basis": "server_image_received_at_not_exposure_time",
                "image_request_started_at": image_started.isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                "image_request_duration_ms": round(image_request_ms, 1),
                "image_device_headers": image_headers,
                "environment": observation,
                "environment_pairing": pairing,
                "source": source,
                "device_base_url": normalize_base_url(base_url),
                "bytes": len(image),
                "filename": image_path.name,
                "url": "/captures/" + urllib.parse.quote(relative, safe="/"),
                "metadata_url": "/captures/" + urllib.parse.quote(relative.removesuffix(".jpg") + ".json", safe="/"),
            }
            metadata_path.write_text(
                json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            with self.lock:
                self.last_capture = result
                self.last_error = None
            return result
        except ApiError as exc:
            with self.lock:
                self.last_error = exc.message
            raise
        except OSError as exc:
            with self.lock:
                self.last_error = "Cannot save capture files; check disk space and permissions"
            raise ApiError(500, self.last_error) from exc
        finally:
            self.capture_lock.release()

    def observe_environment(self, base_url: str, source: str) -> dict[str, Any]:
        base_url = normalize_base_url(base_url)
        # Serialize status observations; do not hold the configuration lock during I/O.
        with self.environment_lock:
            started = time.monotonic()
            payload, error = None, None
            try:
                data, kind = fetch_device(base_url, "/api/v1/status")
                if kind != "application/json":
                    error = "status_not_json"
                else:
                    payload = json.loads(data)
                    if not isinstance(payload, dict):
                        error = "invalid_status_object"
            except ApiError:
                error = "device_unreachable"
            except (ValueError, UnicodeError):
                error = "invalid_status_json"
            received_at = datetime.now(timezone.utc)
            request_ms = (time.monotonic() - started) * 1000
            observation = make_observation(payload, base_url, received_at, request_ms, error)
            self.publish_room_environment(observation, started)
            observation["source"] = source
            try:
                observation = self.environment_history.append(observation)
            except (OSError, sqlite3.Error):
                observation["storage_error"] = "environment_storage_failed"
            with self.lock:
                self.last_environment = observation
                self.last_environment_error = observation.get("storage_error") or observation.get("error")
            # Keep display/cache separate from raw observations; never record
            # an old cached value as a new physical measurement.
            reference = {"device_base_url": base_url, "image_device_headers": {
                "x-device-id": observation.get("device_id"), "x-boot-id": observation.get("boot_id")}}
            try:
                cached = self.environment_history.latest_valid(base_url)
                effective = resolve_last_valid([cached] if cached else [], observation, reference, received_at)
                with self.lock:
                    self.environment_effective = effective
            except (OSError, sqlite3.Error, ValueError):
                pass
            return observation

    def reevaluate_environment(self, observation: dict[str, Any]) -> None:
        if self.thermal_loop.status()["enabled"]:
            # The thermal worker owns the actuation gate; sensor collection above
            # continues independently during VLM requests.
            self.thermal_loop.observe_environment()
            return
        automatic = self.automatic_ai.snapshot()
        record = automatic.get("last_record") or {}
        if not automatic.get("enabled"):
            return
        # VLM work must pause emission, not sensor sampling. Skipping a 20s model
        # request here can break the <=45s continuity window and prevent the
        # configured environmental persistence threshold. Failed/paused AI
        # states likewise retain measurements without authorizing transmission.
        allow_emit = not (
            automatic.get("busy")
            or automatic.get("state") in {"paused_budget", "budget_exhausted", "backoff", "error"}
            or record.get("error") or record.get("integration_error") or record.get("persistence_error")
        )
        capture = record.get("confirmation") or record.get("after") or {}
        reference = {**capture, "image_device_headers": {"x-device-id": capture.get("device_id"),
                                                         "x-boot-id": capture.get("boot_id")}}
        cached = self.environment_history.latest_valid(observation["device_base_url"])
        fallback = resolve_last_valid([cached] if cached else [], observation, reference, datetime.now(timezone.utc))
        self.ac_control.evaluate(self.live_environment({"fallback": fallback}), allow_emit=allow_emit)

    def environment_loop(self) -> None:
        next_run = 0.0
        previous_settings = None
        while not self.stop_event.wait(0.5):
            with self.lock:
                settings = (self.config["environment_recording_enabled"],
                            self.config["device_base_url"], self.config["environment_interval_seconds"])
            if settings != previous_settings:
                next_run = 0.0
                previous_settings = settings
            enabled, base_url, interval = settings
            if not enabled or time.monotonic() < next_run:
                continue
            try:
                observation = self.observe_environment(base_url, "periodic")
                self.reevaluate_environment(observation)
            except Exception:
                # A malformed response or storage failure must not kill the worker.
                with self.lock:
                    self.last_environment_error = "environment_recording_failed"
            finally:
                next_run = time.monotonic() + interval

    def scheduler_loop(self) -> None:
        while not self.stop_event.wait(0.5):
            with self.lock:
                enabled = self.config["schedule_enabled"]
                interval = self.config["capture_interval_seconds"]
                next_run = self.next_capture_epoch_ms
            if not enabled or next_run is None:
                continue
            if int(time.time() * 1000) < next_run:
                continue
            try:
                capture = self.capture_once("scheduled")
                self.automatic_ai.submit(capture)
            except ApiError:
                pass
            finally:
                with self.lock:
                    self.next_capture_epoch_ms = int((time.time() + interval) * 1000)


STATE = AppState()


def list_captures(limit: int) -> list[dict[str, Any]]:
    if limit <= 0:
        return []

    def candidates():
        # Follow pathlib's existing directory order, including equal timestamps.
        # DirEntry.stat reuses size/mtime from the Windows directory enumeration.
        for directory in CAPTURES_DIR.glob("**/"):
            try:
                with os.scandir(directory) as entries:
                    for entry in entries:
                        if not os.path.normcase(entry.name).endswith(".jpg"):
                            continue
                        try:
                            if entry.is_file():
                                yield entry.stat().st_mtime, entry.path
                        except FileNotFoundError:
                            continue
            except (FileNotFoundError, NotADirectoryError, PermissionError):
                continue

    # Keep only the requested newest entries. nlargest preserves input order
    # among equal keys, like the previous stable full sort.
    newest = heapq.nlargest(limit, candidates(), key=lambda item: item[0])
    items: list[dict[str, Any]] = []
    for _, filename in newest:
        path = Path(filename)
        try:
            file_info = path.stat()
        except FileNotFoundError:
            continue
        relative = path.relative_to(CAPTURES_DIR).as_posix()
        metadata_path = path.with_suffix(".json")
        metadata: dict[str, Any] = {}
        if metadata_path.exists():
            try:
                metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                metadata = {}
        items.append(
            {
                "filename": path.name,
                "bytes": file_info.st_size,
                "captured_at": metadata.get(
                    "captured_at",
                    datetime.fromtimestamp(file_info.st_mtime, timezone.utc)
                    .isoformat(timespec="milliseconds")
                    .replace("+00:00", "Z"),
                ),
                "source": metadata.get("source", "unknown"),
                "environment": metadata.get("environment"),
                "environment_pairing": metadata.get("environment_pairing"),
                "metadata_url": "/captures/" + urllib.parse.quote(metadata_path.relative_to(CAPTURES_DIR).as_posix(), safe="/") if metadata_path.exists() else None,
                "url": "/captures/" + urllib.parse.quote(relative, safe="/"),
            }
        )
    return items


def adopt_provisioned_device() -> dict[str, Any]:
    """Explicit owner action: verify the BLE-selected identity over the LAN.

    This detects accidental wrong-device addresses, not cryptographic HTTP
    authentication. No cloud calls, images, credential storage or redirects.
    """
    snapshot = BLE_PROVISIONING.status()
    result = snapshot.get("result") or {}
    if snapshot.get("busy") or snapshot.get("state") != "provisioned":
        raise ApiError(409, "Complete Bluetooth Wi-Fi provisioning before adopting a device")
    expected_id = result.get("device_id")
    if not isinstance(expected_id, str) or not expected_id.startswith("dreamshield-"):
        raise ApiError(409, "The authenticated BLE session did not provide a DreamShield device identity")
    try:
        address = ipaddress.IPv4Address(result.get("ip_address", ""))
    except (ValueError, TypeError):
        raise ApiError(409, "The device has not reported a valid IPv4 address") from None
    if not any(address in ipaddress.ip_network(network) for network in
               ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")):
        raise ApiError(400, "Only an RFC1918 private LAN address may be adopted")
    base_url = f"http://{address}"

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(urllib.request.Request(base_url + "/api/v1/status",
                         headers={"User-Agent": "DreamShield-BLE-Setup/0.1"}), timeout=6) as response:
            if response.headers.get_content_type() != "application/json":
                raise ApiError(502, "The device status endpoint did not return JSON")
            data = response.read(65537)
        if len(data) > 65536:
            raise ApiError(502, "The device status response is too large")
        status = json.loads(data)
    except ApiError:
        raise
    except (OSError, ValueError, urllib.error.URLError):
        raise ApiError(502, "Device has Wi-Fi but is not yet reachable; check the PC network, guest isolation and firmware") from None
    if not isinstance(status, dict) or status.get("device_id") != expected_id:
        raise ApiError(409, "LAN device identity does not match the selected Bluetooth device")
    if status.get("wifi_connected") is not True or status.get("local_ip") != str(address):
        raise ApiError(409, "Device LAN status does not confirm the provisioned address")
    latest = BLE_PROVISIONING.status()
    if (latest.get("busy") or latest.get("state") != "provisioned"
            or latest.get("result") != result):
        raise ApiError(409, "Provisioning session changed while verifying the device; retry from the current session")
    # Stop existing capture/recording and AI rather than silently sending new
    # bedroom images into a previously enabled schedule. User re-enables them.
    config = STATE.update_config({"device_base_url": base_url, "schedule_enabled": False,
                                  "environment_recording_enabled": False})
    return {"ok": True, "verified": True, "device_id": expected_id, "config": config,
            "camera_ready": status.get("camera_ready") is True and status.get("hardware_jpeg") is True}


class DreamShieldHandler(BaseHTTPRequestHandler):
    server_version = "DreamShieldServer/0.1"

    def log_message(self, format: str, *args: Any) -> None:
        # Never let malformed provisioning URLs or library exceptions become
        # credential-bearing access logs. Valid clients put secrets in JSON.
        if urllib.parse.urlsplit(self.path).path.startswith("/api/provisioning/"):
            print(f"[{utc_now_iso()}] local BLE provisioning HTTP request")
            return
        print(f"[{utc_now_iso()}] {self.address_string()} - {format % args}")

    def send_json(self, status: int, payload: Any) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def send_bytes(self, status: int, data: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def read_json(self) -> dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as exc:
            raise ApiError(400, "Invalid Content-Length") from exc
        if length <= 0 or length > MAX_JSON_BYTES:
            raise ApiError(413, "Request body is empty or too large")
        try:
            body = json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ApiError(400, "Request body must be valid JSON") from exc
        if not isinstance(body, dict):
            raise ApiError(400, "JSON body must be an object")
        return body

    def query(self) -> dict[str, list[str]]:
        return urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)

    def handle_api_error(self, exc: ApiError) -> None:
        self.send_json(exc.status, {"ok": False, "error": exc.message})

    def require_local_owner(self, *, mutation: bool = False) -> None:
        """Pairing material is visible only to the loopback desktop owner.

        A loopback peer alone is insufficient: validate Host against DNS
        rebinding and require the exact local Origin on owner mutations.
        The authenticated LAN gateway never forwards these routes.
        """
        try:
            local_peer = ipaddress.ip_address(self.client_address[0]).is_loopback
        except ValueError:
            local_peer = False
        authority = self.headers.get("Host", "")
        try:
            parsed = urllib.parse.urlsplit("http://" + authority)
            valid_host = (parsed.hostname in {"127.0.0.1", "localhost"}
                          and parsed.port == self.server.server_address[1]
                          and not parsed.username and not parsed.password
                          and not parsed.path and not parsed.query and not parsed.fragment)
        except ValueError:
            valid_host = False
        if not local_peer or not valid_host:
            raise ApiError(403, "Owner setup is available only on the local computer")
        expected_origin = "http://" + authority
        origin = self.headers.get("Origin")
        if (origin and origin != expected_origin) or (mutation and origin != expected_origin):
            raise ApiError(403, "Local owner Origin required")
        if self.headers.get("Sec-Fetch-Site") in {"cross-site", "same-site"}:
            raise ApiError(403, "Cross-site owner setup is forbidden")

    def read_provisioning_json(self) -> dict[str, Any]:
        self.require_local_owner(mutation=True)
        if urllib.parse.urlsplit(self.path).query:
            raise ApiError(400, "Provisioning parameters must use a JSON body, not a URL")
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise ApiError(400, "Invalid Content-Length") from None
        if not 0 < length <= 4096:
            raise ApiError(413, "Provisioning JSON must be 1-4096 bytes")
        if self.headers.get("Transfer-Encoding"):
            raise ApiError(400, "Chunked provisioning requests are not accepted")
        return self.read_json()

    def provision_asset(self, name: str) -> None:
        # Invoked only for explicitly routed UI assets. API responses, captures,
        # QR codes and other private data never enter either cache.
        path = STATIC_DIR / name
        cache = "no-store"
        if path.suffix == ".html":
            data = versioned_console_page(path)
            content_type = "text/html"
        else:
            data, digest = console_file(path)
            content_type = "text/css" if path.suffix == ".css" else "text/javascript"
            if self.query().get("v") == [digest]:
                cache = "private, max-age=31536000, immutable"
        accept = self.headers.get("Accept-Encoding", "")
        use_gzip = bool(re.search(r"(?:^|,)\s*gzip\s*(?:,|$)", accept, re.I))
        if use_gzip:
            data = compressed_console_asset(data)
        self.send_response(200)
        self.send_header("Content-Type", content_type + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", cache)
        self.send_header("Vary", "Accept-Encoding")
        if use_gzip:
            self.send_header("Content-Encoding", "gzip")
        self.send_header("X-DreamShield-Console", CONSOLE_RELEASE)
        self.send_header("X-Content-Type-Options", "nosniff")
        if name != "index.html":
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
                             "style-src 'self' 'unsafe-inline'; connect-src 'self'; "
                             "img-src 'self'; object-src 'none'; "
                             "frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        self.end_headers()
        self.wfile.write(data)

    def mobile_setup(self) -> dict[str, Any]:
        if MOBILE_GATEWAY is None:
            return {"ok": True, "enabled": False, "urls": [], "sessions": [],
                    "pairing_payload": "", "pairing_available": False}
        return {"ok": True, **MOBILE_GATEWAY.setup_info()}

    def do_GET(self) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path
        try:
            self.require_local_owner()
            if path in {"/", "/settings", "/history", "/connections", "/mobile-setup"}:
                # Read-only console views share the same IDs and safe controller
                # boot path. Navigation never changes background task settings.
                self.serve_static(STATIC_DIR / "index.html")
            elif path == "/device-setup":
                self.provision_asset("device_setup.html")
            elif path == "/thermal-setup":
                self.provision_asset("thermal_setup.html")
            elif path.startswith("/static/") and path[8:] in CONSOLE_ASSETS:
                self.provision_asset(path[8:])
            elif path == "/api/thermal/status":
                self.send_json(200, {"ok": True, "thermal": STATE.thermal.snapshot()})
            elif path == "/api/thermal/frame":
                self.send_json(200, {"ok": True, "frame": STATE.thermal.latest_frame()})
            elif path == "/api/thermal-model/status":
                self.send_json(200, {"ok": True, "thermal_model": STATE.thermal_model.status()})
            elif path == "/api/thermal-loop/status":
                self.send_json(200, {"ok": True, "thermal_loop": STATE.thermal_loop.status()})
            elif path == "/api/thermal/captures":
                try:
                    limit = int(self.query().get("limit", ["20"])[0])
                except ValueError:
                    raise ApiError(400, "Thermal history limit must be an integer") from None
                if not 1 <= limit <= 100:
                    raise ApiError(400, "Thermal history limit must be 1-100")
                self.send_json(200, {"ok": True, "items": [thermal_download_links(item)
                    for item in STATE.thermal.list_captures(limit)]})
            elif path.startswith("/thermal-captures/"):
                self.serve_thermal_capture(path.removeprefix("/thermal-captures/"))
            elif path == "/api/provisioning/status":
                if parsed.query:
                    raise ApiError(400, "Provisioning status does not accept URL parameters")
                self.send_json(200, {"ok": True, "provisioning": BLE_PROVISIONING.status()})
            elif path == "/api/mobile/setup":
                self.require_local_owner()
                self.send_json(200, self.mobile_setup())
            elif path == "/api/policy-settings":
                self.send_json(200, {"ok": True, **STATE.policy_settings_snapshot()})
            elif path == "/api/automatic-ai/history":
                self.send_json(200, {"ok": True, "items": list_automatic_ai_results()})
            elif path.startswith("/automatic-ai-results/"):
                relative = Path(urllib.parse.unquote(path.removeprefix("/automatic-ai-results/")))
                if relative.is_absolute() or ".." in relative.parts:
                    raise ApiError(403, "Forbidden")
                try:
                    resolved = (AUTOMATIC_AI_DIR / relative).resolve(strict=True)
                except FileNotFoundError as exc:
                    raise ApiError(404, "Automatic AI result not found") from exc
                if AUTOMATIC_AI_DIR.resolve() not in resolved.parents or resolved.suffix.lower() != ".json":
                    raise ApiError(403, "Forbidden")
                self.send_bytes(200, resolved.read_bytes(), "application/json; charset=utf-8")
            elif path == "/api/app/status":
                self.send_json(200, {"ok": True, **STATE.snapshot()})
            elif path == "/api/environment/current":
                if parsed.query:
                    raise ApiError(400, "Room environment does not accept URL parameters")
                self.send_json(200, {"ok": True, **STATE.room_environment_snapshot()})
            elif path == "/api/device/status":
                with STATE.lock:
                    configured_url = STATE.config["device_base_url"]
                base_url = normalize_base_url(self.query().get("base_url", [configured_url])[0])
                started = time.monotonic()
                payload, error = None, "device_unreachable"
                try:
                    data, content_type = fetch_device(base_url, "/api/v1/status")
                    error = "invalid_status_object"
                    if content_type != "application/json":
                        raise ApiError(502, "The ESP32 status endpoint did not return JSON")
                    try:
                        payload = json.loads(data)
                    except (ValueError, UnicodeError) as exc:
                        raise ApiError(502, "The ESP32 status endpoint did not return valid JSON") from exc
                    if not isinstance(payload, dict):
                        raise ApiError(502, "The ESP32 status endpoint did not return a JSON object")
                    error = None
                finally:
                    received_at = datetime.now(timezone.utc)
                    elapsed_ms = (time.monotonic() - started) * 1000
                    STATE.publish_room_environment(make_observation(payload, base_url, received_at, elapsed_ms, error), started)
                if isinstance(payload.get("ac_control"), dict):
                    payload["ac_control"].pop("request_token", None)
                self.send_json(200, payload)
            elif path == "/api/device/snapshot":
                base_url = self.query().get(
                    "base_url", [STATE.snapshot()["config"]["device_base_url"]]
                )[0]
                data, content_type = fetch_device(base_url, "/api/v1/snapshot")
                if content_type != "image/jpeg":
                    raise ApiError(502, "The ESP32 snapshot endpoint did not return JPEG")
                self.send_bytes(200, data, "image/jpeg")
            elif path == "/api/captures":
                try:
                    limit = int(self.query().get("limit", ["24"])[0])
                except ValueError:
                    limit = 24
                self.send_json(200, {"ok": True, "items": list_captures(max(1, min(limit, 100)))})
            elif path == "/api/environment/history":
                query = self.query()
                base_url = normalize_base_url(query.get("base_url", [STATE.snapshot()["config"]["device_base_url"]])[0])
                try:
                    limit = int(query.get("limit", ["60"])[0])
                except ValueError:
                    raise ApiError(400, "History limit must be an integer")
                self.send_json(200, {"ok": True, "device_base_url": base_url,
                                    "items": STATE.environment_history.recent(base_url, limit)})
            else:
                self.send_json(404, {"ok": False, "error": "Not found"})
        except ApiError as exc:
            self.handle_api_error(exc)
        except Exception as exc:
            message = "BLE provisioning request failed" if path.startswith("/api/provisioning/") else f"Internal server error: {exc}"
            self.send_json(500, {"ok": False, "error": message})

    def do_POST(self) -> None:
        path = urllib.parse.urlsplit(self.path).path
        try:
            self.require_local_owner()
            if self.headers.get_content_type() != "application/json":
                raise ApiError(415, "Settings require application/json")
            if path.startswith("/api/provisioning/"):
                body = self.read_provisioning_json()
                if path == "/api/provisioning/adopt":
                    if body:
                        raise ApiError(400, "Device adoption requires an empty JSON object")
                    self.send_json(200, adopt_provisioned_device())
                else:
                    actions = {"scan": "scan", "connect": "connect", "wifi-scan": "scan_wifi",
                               "configure": "provision", "disconnect": "disconnect", "cancel": "cancel"}
                    action = actions.get(path.removeprefix("/api/provisioning/"))
                    if action is None:
                        raise ApiError(404, "Provisioning action not found")
                    try:
                        snapshot = BLE_PROVISIONING.submit(action, body)
                    except ValueError:
                        raise ApiError(400, "Invalid provisioning parameters; check the selected device, code and Wi-Fi fields") from None
                    except RuntimeError:
                        raise ApiError(409, "Provisioning is busy or unavailable; check status before retrying") from None
                    self.send_json(202, {"ok": True, "provisioning": snapshot})
            elif path == "/api/mobile/sessions/revoke":
                self.require_local_owner(mutation=True)
                if urllib.parse.urlsplit(self.path).query:
                    raise ApiError(400, "Mobile disconnect does not accept URL parameters")
                body = self.read_json()
                session_id = body.get("session_id")
                if (set(body) != {"session_id"} or not isinstance(session_id, str)
                        or not re.fullmatch(r"[0-9a-f]{24}", session_id)):
                    raise ApiError(400, "A valid session_id is required")
                if MOBILE_GATEWAY is None:
                    raise ApiError(409, "Start the server with start_mobile.bat first")
                info = MOBILE_GATEWAY.revoke_session(session_id)
                if not info["revoked"]:
                    raise ApiError(404, "Mobile session not found")
                self.send_json(200, info)
            elif path in {"/api/mobile/pairing/renew", "/api/mobile/revoke"}:
                self.require_local_owner(mutation=True)
                if self.headers.get_content_type() != "application/json":
                    raise ApiError(415, "Mobile setup requires application/json")
                if self.read_json():
                    raise ApiError(400, "Mobile setup request must be an empty object")
                if MOBILE_GATEWAY is None:
                    raise ApiError(409, "Start the server with start_mobile.bat first")
                if path.endswith("/renew"):
                    info = MOBILE_GATEWAY.renew_pairing_code()
                else:
                    info = MOBILE_GATEWAY.revoke_sessions()
                self.send_json(200, {"ok": True, **info})
            elif path == "/api/config":
                config = STATE.update_config(self.read_json())
                self.send_json(200, {"ok": True, "config": config})
            elif path == "/api/thermal-loop/config":
                self.require_local_owner(mutation=True)
                if urllib.parse.urlsplit(self.path).query:
                    raise ApiError(400, "Closed-loop parameters require a JSON body")
                try:
                    self.send_json(200, {"ok": True, "thermal_loop": STATE.update_thermal_loop(self.read_json())})
                except ModelError as exc:
                    self.send_json(exc.status, {"ok": False, "error": exc.message})
            elif path in {"/api/thermal-model/config", "/api/thermal-model/analyze"}:
                self.require_local_owner(mutation=True)
                if urllib.parse.urlsplit(self.path).query:
                    raise ApiError(400, "Model parameters require a JSON body")
                body = self.read_json()
                try:
                    if path.endswith("/config"):
                        result = STATE.thermal_model.configure(body)
                    else:
                        if set(body) != {"calibration_confirmed"}:
                            raise ApiError(400, "Analysis requires calibration_confirmed")
                        result = STATE.thermal_model.analyze(confirmed=body["calibration_confirmed"])
                    self.send_json(200, {"ok": True, "thermal_model": result})
                except ModelError as exc:
                    self.send_json(exc.status, {"ok": False, "error": exc.message})
            elif path in {"/api/thermal/config", "/api/thermal/capture", "/api/thermal/preview"}:
                self.require_local_owner(mutation=True)
                if urllib.parse.urlsplit(self.path).query:
                    raise ApiError(400, "Thermal settings require a JSON body without URL parameters")
                body = self.read_json()
                try:
                    if path.endswith("/config"):
                        result = STATE.update_thermal_settings(body)
                        self.send_json(200, {"ok": True, "thermal": result})
                    elif path.endswith("/preview"):
                        if set(body) != {"enabled"}:
                            raise ApiError(400, "Thermal preview requires only an enabled boolean")
                        with STATE.automation_settings_lock:
                            result = STATE.thermal.set_preview(body["enabled"],
                                control_base_url=STATE.config["device_base_url"])
                        self.send_json(200, {"ok": True, "thermal": result})
                    else:
                        if body:
                            raise ApiError(400, "Thermal capture requires an empty JSON object")
                        result = STATE.capture_thermal()
                        if result.get("capture"):
                            result["capture"] = thermal_download_links(result["capture"])
                        self.send_json(200, {"ok": True, **result})
                except ThermalError as exc:
                    self.send_json(exc.status, {"ok": False, "error": exc.message, "code": exc.code})
            elif path in {"/api/policy-settings", "/api/sensor-settings"}:
                self.require_local_owner(mutation=True)
                body = self.read_json()
                result = (STATE.update_policy_settings(body) if path == "/api/policy-settings"
                          else STATE.update_sensor_settings(body))
                self.send_json(200, {"ok": True, **result})
            elif path == "/api/automatic-ai":
                if self.headers.get_content_type() != "application/json":
                    raise ApiError(415, "Automatic AI settings require application/json")
                origin = self.headers.get("Origin")
                if origin and origin != "http://" + self.headers.get("Host", ""):
                    raise ApiError(403, "Cross-origin automatic AI settings are forbidden")
                state = STATE.update_automatic_ai(self.read_json())
                self.send_json(200, {"ok": True, "automatic_ai": state, "config": STATE.snapshot()["config"]})
            elif path in {"/api/ac-control/config", "/api/ac-control/manual", "/api/ac-control/automatic"}:
                origin = self.headers.get("Origin")
                if origin and origin != "http://" + self.headers.get("Host", ""):
                    raise ApiError(403, "Cross-origin AC commands are forbidden")
                body = self.read_json()
                if path.endswith("/manual"):
                    self.send_json(200, {"ok": True, **STATE.manual_ac_command(body)})
                else:
                    state = STATE.update_ac_control(body) if path.endswith("/config") else STATE.update_ac_automatic(body)
                    self.send_json(200, {"ok": True, "ac_control": state})
            elif path == "/api/capture":
                body = self.read_json()
                override = body.get("device_base_url")
                result = STATE.capture_once("manual", override)
                self.send_json(200, {"ok": True, "capture": result})
            else:
                self.send_json(404, {"ok": False, "error": "Not found"})
        except ApiError as exc:
            self.handle_api_error(exc)
        except Exception as exc:
            message = "BLE provisioning request failed" if path.startswith("/api/provisioning/") else f"Internal server error: {exc}"
            self.send_json(500, {"ok": False, "error": message})

    def serve_static(self, path: Path) -> None:
        try:
            resolved = path.resolve(strict=True)
        except FileNotFoundError as exc:
            raise ApiError(404, "Static file not found") from exc
        if STATIC_DIR.resolve() not in resolved.parents and resolved != STATIC_DIR.resolve():
            raise ApiError(403, "Forbidden")
        if resolved == (STATIC_DIR / "index.html").resolve():
            return self.provision_asset("index.html")
        content_type = mimetypes.guess_type(resolved.name)[0] or "application/octet-stream"
        self.send_bytes(200, resolved.read_bytes(), content_type)

    def serve_capture(self, encoded_relative: str) -> None:
        relative = Path(urllib.parse.unquote(encoded_relative))
        if relative.is_absolute() or ".." in relative.parts:
            raise ApiError(403, "Forbidden")
        try:
            resolved = (CAPTURES_DIR / relative).resolve(strict=True)
        except FileNotFoundError as exc:
            raise ApiError(404, "Capture not found") from exc
        if CAPTURES_DIR.resolve() not in resolved.parents or resolved.suffix.lower() not in {".jpg", ".json"}:
            raise ApiError(403, "Forbidden")
        content_type = "image/jpeg" if resolved.suffix.lower() == ".jpg" else "application/json; charset=utf-8"
        self.send_bytes(200, resolved.read_bytes(), content_type)

    def serve_thermal_capture(self, encoded_relative: str) -> None:
        relative = Path(urllib.parse.unquote(encoded_relative))
        if relative.is_absolute() or ".." in relative.parts:
            raise ApiError(403, "Forbidden")
        try:
            resolved = (THERMAL_DIR / relative).resolve(strict=True)
        except (FileNotFoundError, OSError, ValueError) as exc:
            raise ApiError(404, "Thermal capture not found") from exc
        if THERMAL_DIR.resolve() not in resolved.parents or resolved.suffix.lower() not in {".json", ".bin"}:
            raise ApiError(403, "Forbidden")
        kind = "application/json; charset=utf-8" if resolved.suffix == ".json" else "application/octet-stream"
        self.send_bytes(200, resolved.read_bytes(), kind)


def main() -> None:
    global MOBILE_GATEWAY
    mobile_enabled = os.environ.get("DREAMSHIELD_MOBILE") == "1"
    if mobile_enabled and HOST not in {"127.0.0.1", "localhost"}:
        raise RuntimeError("Mobile mode requires the desktop backend to stay on 127.0.0.1")
    server = ThreadingHTTPServer((HOST, PORT), DreamShieldHandler)
    bound_port = server.server_address[1]
    if mobile_enabled:
        try:
            from mobile_gateway import MobileGateway
            MOBILE_GATEWAY = MobileGateway(
                backend_url=f"http://127.0.0.1:{bound_port}", data_dir=DATA_DIR,
                port=int(os.environ.get("DREAMSHIELD_MOBILE_PORT", "8743")))
            MOBILE_GATEWAY.start()
        except Exception:
            server.server_close()
            raise
    scheduler = threading.Thread(target=STATE.scheduler_loop, daemon=True)
    scheduler.start()
    environment_worker = threading.Thread(target=STATE.environment_loop, daemon=True)
    environment_worker.start()
    ai_worker = threading.Thread(target=STATE.automatic_ai.run, args=(STATE.stop_event,), daemon=True)
    ai_worker.start()
    thermal_worker = threading.Thread(target=STATE.thermal_capture_loop, daemon=True)
    thermal_worker.start()
    url = f"http://127.0.0.1:{bound_port}/"
    print("DreamShield local server")
    print(f"Dashboard : {url}")
    print(f"Wi-Fi setup: {url}device-setup (Bluetooth is operated by this PC)")
    print(f"Thermal lab: {url}thermal-setup (30139; collection starts only on request)")
    if MOBILE_GATEWAY is not None:
        print(f"Phone setup: {url}mobile-setup")
        print("Mobile HTTPS: authenticated private LAN only; pairing is shown on the local setup page")
    print(f"Data      : {CAPTURES_DIR}")
    print(f"VLM provider: {VLM_PROVIDER}")
    print(f"VLM model   : {VLM_MODEL}")
    print(f"VLM endpoint: {VLM_API_URL}")
    print(
        "VLM key     : configured"
        if get_vlm_api_key()
        else "VLM key     : not configured (monitoring still works)"
    )
    if os.environ.get("DREAMSHIELD_NO_BROWSER") != "1":
        entry = (url + "device-setup" if os.environ.get("DREAMSHIELD_START_PAGE") == "device-setup"
                 else url + "mobile-setup" if MOBILE_GATEWAY is not None else url)
        threading.Timer(0.8, lambda: webbrowser.open(entry)).start()
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        print("\nStopping DreamShield server...")
    finally:
        STATE.stop_event.set()
        STATE.thermal_loop.close()
        STATE.thermal_model.close()
        STATE.thermal.configure({"enabled": False})
        STATE.thermal.set_preview(False)
        STATE.thermal.close_transport()
        STATE.automatic_ai.disable("server_stopped")
        STATE.ac_control.disable("server_stopped")
        BLE_PROVISIONING.close()
        if MOBILE_GATEWAY is not None:
            MOBILE_GATEWAY.stop()
            MOBILE_GATEWAY = None
        server.server_close()


if __name__ == "__main__":
    main()
