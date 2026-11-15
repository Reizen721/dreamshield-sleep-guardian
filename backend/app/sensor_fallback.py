"""Resolve a display/bench reading without rewriting sensor history.

Only original successful SHT31 observations qualify. A fallback keeps its
original times and is never a fresh sample or evidence of an ongoing trend.
``control_usable`` is a data-age check for the separately gated bench, not
authorization to transmit an appliance command.
"""
from __future__ import annotations

import copy
import math
from datetime import datetime, timezone
from typing import Any

from environment_history import MAX_FRESH_AGE_SECONDS, MAX_REQUEST_DURATION_MS


MAX_CONTROL_AGE_SECONDS = 300


def _identifier(value: Any) -> str | None:
    return value if isinstance(value, str) and 0 < len(value) <= 128 else None


def _number(value: Any, low: float, high: float) -> bool:
    return type(value) in (int, float) and math.isfinite(value) and low <= value <= high


def _datetime(value: Any) -> datetime | None:
    try:
        if isinstance(value, str):
            value = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            return None
        return value.astimezone(timezone.utc)
    except (ValueError, TypeError, OverflowError):
        return None


def _original_sample(record: Any, now: datetime) -> tuple[datetime, datetime, float] | None:
    """Recheck the normalized observation contract; derived cache rows fail it."""
    if not isinstance(record, dict) or record.get("valid") is not True or record.get("status") != "fresh":
        return None
    if record.get("error") or record.get("source") == "last_valid":
        return None
    if record.get("sensor_model", "sht31") != "sht31" or record.get("input_origin", "sensor") not in ("sensor", "esp32_telemetry"):
        return None
    if not all(_identifier(record.get(key)) for key in ("device_id", "boot_id", "sensor_id")):
        return None
    if type(record.get("sample_sequence")) is not int or record["sample_sequence"] < 1:
        return None
    if not _number(record.get("ambient_temperature_c"), -40, 125) or not _number(record.get("relative_humidity_percent"), 0, 100):
        return None
    request_ms = record.get("request_duration_ms")
    sample_age_ms = record.get("sample_age_ms")
    freshness_ms = MAX_FRESH_AGE_SECONDS * 1000
    if (not _number(request_ms, 0, MAX_REQUEST_DURATION_MS)
            or not _number(sample_age_ms, 0, freshness_ms)
            or sample_age_ms + request_ms > freshness_ms):
        return None
    measured, received = _datetime(record.get("measured_at_estimated")), _datetime(record.get("received_at"))
    if measured is None or received is None or measured > received or received > now:
        return None
    # The two original timestamps must still describe the device-reported age.
    if abs((received - measured).total_seconds() * 1000 - sample_age_ms) > 2:
        return None
    return measured, received, (now - measured).total_seconds()


def resolve_last_valid(history: list[dict[str, Any]], current: dict[str, Any],
                       capture: dict[str, Any], now: datetime) -> dict[str, Any]:
    """Return an effective observation plus explicit source, age and fault.

``current`` and ``history`` are normalized ``make_observation`` records.
``capture`` uses ``image_device_headers`` and ``device_base_url`` from the
saved JPEG metadata. A failed current sample may reuse history only when this
snapshot independently supplies the same device and boot identity. A current
response reporting another boot/device blocks all fallback. Inputs are never
modified; repeated resolution cannot renew the original reading's lifetime.
"""
    current = current if isinstance(current, dict) else {}
    capture = capture if isinstance(capture, dict) else {}
    result: dict[str, Any] = {
        "effective_observation": None,
        "source": "unavailable",
        "age_seconds": None,
        "control_usable": False,
        "trend_usable": False,
        "fault_reason": current.get("error") or "sensor_unavailable",
        "original_measured_at": None,
        "original_received_at": None,
        "max_control_age_seconds": MAX_CONTROL_AGE_SECONDS,
        "control_blocker": "no_last_valid_sample",
        "execution_allowed": False,
    }
    now_utc = _datetime(now)
    if now_utc is None:
        result.update(fault_reason="invalid_current_time", control_blocker="invalid_current_time")
        return result

    headers = capture.get("image_device_headers")
    headers = headers if isinstance(headers, dict) else {}
    device, boot = _identifier(headers.get("x-device-id")), _identifier(headers.get("x-boot-id"))
    base_url = capture.get("device_base_url")
    if not device or not boot or not isinstance(base_url, str) or not base_url:
        result.update(control_blocker="missing_snapshot_identity")
        return result
    if (current.get("device_base_url") != base_url
            or (current.get("device_id") is not None and current.get("device_id") != device)
            or (current.get("boot_id") is not None and current.get("boot_id") != boot)):
        result.update(control_blocker="device_or_boot_mismatch", fault_reason="device_or_boot_mismatch")
        return result

    sensor = _identifier(current.get("sensor_id"))

    def matches(record: Any) -> bool:
        return (isinstance(record, dict) and record.get("device_base_url") == base_url
                and record.get("device_id") == device and record.get("boot_id") == boot
                and (sensor is None or record.get("sensor_id") == sensor))

    original_current = _original_sample(current, now_utc) if matches(current) else None
    selected = None
    source = "last_valid"
    if original_current and original_current[2] + current["request_duration_ms"] / 1000 <= MAX_FRESH_AGE_SECONDS:
        selected = (original_current, current)
        source = "fresh"
    else:
        if current.get("valid") is True and current.get("status") == "fresh":
            result["fault_reason"] = "current_sample_stale" if original_current else "invalid_current_sample"
        elif not current.get("error"):
            result["fault_reason"] = "stale_sample" if current.get("status") == "stale" else "sensor_unavailable"
        candidates = []
        # A previously successful current record may itself have aged into cache.
        for record in [current, *(history if isinstance(history, list) else [])]:
            original = _original_sample(record, now_utc) if matches(record) else None
            if original:
                candidates.append((original, record))
        if candidates:
            selected = max(candidates, key=lambda item: (item[0][0], item[0][1], item[1]["sample_sequence"]))
    if selected is None:
        return result

    original, record = selected
    age = original[2]
    effective = copy.deepcopy(record)
    effective.update(source=source, age_seconds=age, execution_allowed=False)
    if source == "last_valid":
        effective.update(valid=False, status="last_valid", error=result["fault_reason"],
                         fault_reason=result["fault_reason"], trend_usable=False)
    else:
        result["fault_reason"] = None
    result.update(
        effective_observation=effective,
        source=source,
        age_seconds=age,
        control_usable=age <= MAX_CONTROL_AGE_SECONDS,
        trend_usable=source == "fresh",
        original_measured_at=record["measured_at_estimated"],
        original_received_at=record["received_at"],
        control_blocker=None if age <= MAX_CONTROL_AGE_SECONDS else "last_valid_sample_expired",
    )
    return result
