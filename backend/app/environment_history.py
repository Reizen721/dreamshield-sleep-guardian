"""Display/collection only: no actuator policy, VLM calls, or device commands."""
from __future__ import annotations

import json
import math
import sqlite3
from contextlib import closing
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


# Shared with cache validation: a 15-second physical sample remains fresh for
# at most three sample intervals. Network transaction limits remain separate.
MAX_FRESH_AGE_SECONDS = 45
MAX_REQUEST_DURATION_MS = 10000
MAX_CAPTURE_OFFSET_MS = 20000


def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def number(value: Any, low: float, high: float) -> bool:
    return type(value) in (int, float) and math.isfinite(value) and low <= value <= high


def identifier(value: Any) -> str | None:
    return value if isinstance(value, str) and 0 < len(value) <= 128 else None


def make_observation(payload: Any, base_url: str, received_at: datetime,
                     request_ms: float, error: str | None = None) -> dict[str, Any]:
    """Use conservative age checks; estimated UTC is not device exposure time."""
    payload = payload if isinstance(payload, dict) else {}
    env = payload.get("environment")
    env = env if isinstance(env, dict) else {}
    record = {
        "device_base_url": base_url,
        "received_at": iso(received_at),
        "device_id": identifier(payload.get("device_id")),
        "boot_id": identifier(payload.get("boot_id")),
        "sensor_id": identifier(env.get("sensor_id")),
        "sample_sequence": env.get("sample_sequence") if type(env.get("sample_sequence")) is int else None,
        "sample_age_ms": env.get("sample_age_ms") if number(env.get("sample_age_ms"), 0, 4294967295) else None,
        "request_duration_ms": round(request_ms, 1),
        "measured_at_estimated": None,
        "time_basis": "server_received_at_minus_device_age_approximate",
        "ambient_temperature_c": None,
        "relative_humidity_percent": None,
        "valid": False,
        "status": "error",
        "error": error,
        "execution_allowed": False,
    }
    if error:
        return record
    if not env:
        record["error"] = "missing_environment"
    elif not all(record.get(key) for key in ("device_id", "boot_id", "sensor_id")):
        record["error"] = "missing_identity"
    elif env.get("sensor_model") != "sht31" or env.get("input_origin") != "sensor":
        record["error"] = "unexpected_sensor"
    elif env.get("valid") is not True or env.get("status") != "fresh":
        reason = identifier(env.get("last_error"))
        record["error"] = reason if reason and reason != "none" else "sensor_unavailable"
        record["status"] = "stale" if env.get("status") == "stale" else "error"
    elif record["sample_sequence"] is None or record["sample_sequence"] < 1:
        record["error"] = "invalid_sequence"
    elif record["sample_age_ms"] is None or not number(request_ms, 0, MAX_REQUEST_DURATION_MS):
        record["error"] = "invalid_timing"
    elif record["sample_age_ms"] + request_ms > MAX_FRESH_AGE_SECONDS * 1000:
        record.update(status="stale", error="stale_sample")
    elif not number(env.get("ambient_temperature_c"), -40, 125) or not number(env.get("relative_humidity_percent"), 0, 100):
        record["error"] = "invalid_reading"
    else:
        record.update(
            valid=True, status="fresh", error=None,
            ambient_temperature_c=env["ambient_temperature_c"],
            relative_humidity_percent=env["relative_humidity_percent"],
            measured_at_estimated=iso(received_at - timedelta(milliseconds=record["sample_age_ms"])),
        )
    return record


def associate_capture(headers: dict[str, str], observation: dict[str, Any],
                      image_started: datetime, image_received: datetime,
                      image_request_ms: float) -> dict[str, Any]:
    """Link only nearby, same-boot observations; never claim synchronized capture."""
    reasons = []
    device_id = identifier(headers.get("x-device-id"))
    boot_id = identifier(headers.get("x-boot-id"))
    if not device_id or not boot_id:
        reasons.append("missing_snapshot_identity")
    elif device_id != observation.get("device_id") or boot_id != observation.get("boot_id"):
        reasons.append("device_or_boot_mismatch")
    if not observation.get("valid"):
        reasons.append("environment_unavailable")
    offset = None
    # Restrict uncertain/slow HTTP transactions; these limits are collection rules.
    if image_request_ms > 2000 or observation.get("request_duration_ms", 1e9) > 2000:
        reasons.append("request_too_slow")
    if image_received < image_started:
        reasons.append("server_clock_changed")
    if observation.get("measured_at_estimated"):
        measured = datetime.fromisoformat(observation["measured_at_estimated"].replace("Z", "+00:00"))
        offset = round((measured - image_received).total_seconds() * 1000)
        if abs(offset) + image_request_ms + observation["request_duration_ms"] > MAX_CAPTURE_OFFSET_MS:
            reasons.append("sample_not_near_capture")
    return {
        "status": "unavailable" if reasons else "nearby",
        "method": "post_snapshot_status_same_device_and_boot",
        "synchronized": False,
        "time_basis": "server_receive_times_approximate_not_exposure_time",
        "sample_offset_ms_estimated": offset,
        "max_offset_with_request_margin_ms": MAX_CAPTURE_OFFSET_MS,
        "environment_record_id": observation.get("record_id"),
        "reasons": reasons,
        "execution_allowed": False,
    }


class RoomEnvironment:
    """The single display reading shared by desktop and paired mobile clients.

    Writers are the existing observation paths. AppState serializes access.
    Reading this object never starts collection or changes the sample lifetime.
    """
    DISPLAY_MS = 300_000

    def __init__(self):
        self.sample = None
        self.measured = None
        self.reason = "waiting"
        self.last_request = float("-inf")

    def publish(self, observation, request_started):
        if request_started < self.last_request:
            return
        self.last_request = request_started
        keys = ("device_id", "boot_id", "sensor_id")
        if self.sample and any(observation.get(k) and observation[k] != self.sample[k] for k in keys):
            self.sample = self.measured = None
        error = observation.get("error")
        if not observation.get("valid") or observation.get("status") != "fresh":
            self.reason = ("device_unreachable" if error == "device_unreachable" else
                           "stale" if observation.get("status") == "stale" else "sensor_unavailable")
            return
        if (not all(identifier(observation.get(k)) for k in keys)
                or type(observation.get("sample_sequence")) is not int or observation["sample_sequence"] < 1
                or not number(observation.get("ambient_temperature_c"), -40, 125)
                or not number(observation.get("relative_humidity_percent"), 0, 100)
                or not number(observation.get("request_duration_ms"), 0, MAX_REQUEST_DURATION_MS)):
            self.reason = "sensor_unavailable"
            return
        try:
            measured = datetime.fromisoformat(observation["measured_at_estimated"].replace("Z", "+00:00"))
            if measured.utcoffset() is None:
                raise ValueError("missing timezone")
        except (KeyError, AttributeError, ValueError):
            self.reason = "sensor_unavailable"
            return
        if self.sample and observation["sample_sequence"] <= self.sample["sample_sequence"]:
            if observation["sample_sequence"] == self.sample["sample_sequence"]:
                self.reason = "fresh"
            return
        self.sample, self.measured, self.reason = observation, measured, "fresh"

    def restore(self, history, base_url, now=None):
        cutoff = (now or datetime.now(timezone.utc)) - timedelta(milliseconds=self.DISPLAY_MS)
        try:
            for observation in history.room_window(base_url, iso(cutoff)):
                self.publish(observation, self.last_request)
            if self.reason == "fresh":
                self.reason = "last_valid"
        except (OSError, sqlite3.Error, ValueError, KeyError, TypeError):
            pass  # Optional recovery; the next live observation can always populate the view.

    def snapshot(self, recording, now):
        reason = "recording_off" if self.reason == "waiting" and not recording else self.reason
        result = dict(state="unavailable", reason=reason, temperature_c=None, humidity_percent=None,
                      measured_at=None, age_ms=None, as_of=iso(now), fresh_for_ms=0, display_for_ms=0)
        if self.sample is None:
            return result
        age = int((now - self.measured).total_seconds() * 1000)
        display_for = self.DISPLAY_MS - age
        if display_for <= 0 or age < 0:
            result["reason"] = "stale"
            return result
        fresh_for = max(0, int(MAX_FRESH_AGE_SECONDS * 1000 - self.sample["request_duration_ms"] - age))
        fresh = self.reason == "fresh" and fresh_for > 0
        reason = "last_valid" if self.reason == "fresh" and not fresh else self.reason
        return dict(result, state="fresh" if fresh else "cached", reason=reason, age_ms=age,
                    measured_at=iso(self.measured), temperature_c=self.sample["ambient_temperature_c"],
                    humidity_percent=self.sample["relative_humidity_percent"],
                    fresh_for_ms=fresh_for if fresh else 0, display_for_ms=display_for)


class EnvironmentHistory:
    """One row per server observation, including failures and repeated sample IDs.

    Consumers must deduplicate by device/boot/sensor/sequence before treating rows
    as independent sensor samples. Polling does not trigger a physical conversion;
    with a 15-second sample interval, repeated polls can still share one sample.
    Connections are short-lived so scheduler and manual capture can share the DB.
    """
    def __init__(self, path: Path):
        self.path = path

    def append(self, record: dict[str, Any]) -> dict[str, Any]:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(self.path, timeout=10)) as connection, connection:
            connection.execute("""CREATE TABLE IF NOT EXISTS observations (
                id INTEGER PRIMARY KEY AUTOINCREMENT, base_url TEXT NOT NULL,
                received_at TEXT NOT NULL, payload TEXT NOT NULL)""")
            connection.execute("CREATE INDEX IF NOT EXISTS observations_source ON observations(base_url, id)")
            cursor = connection.execute(
                "INSERT INTO observations(base_url, received_at, payload) VALUES (?, ?, ?)",
                (record["device_base_url"], record["received_at"], json.dumps(record, ensure_ascii=False, allow_nan=False)),
            )
            return {**record, "record_id": cursor.lastrowid}

    def recent(self, base_url: str, limit: int = 60) -> list[dict[str, Any]]:
        if not self.path.exists():
            return []
        with closing(sqlite3.connect(self.path, timeout=10)) as connection:
            if not connection.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='observations'").fetchone():
                return []  # The first writer may not have committed its schema yet.
            rows = connection.execute(
                "SELECT id, payload FROM observations WHERE base_url=? ORDER BY id DESC LIMIT ?",
                (base_url, max(1, min(limit, 1000))),
            ).fetchall()
        return [{**json.loads(payload), "record_id": record_id} for record_id, payload in rows]

    def latest_valid(self, base_url: str) -> dict[str, Any] | None:
        """Retain last measured values even after many failure rows; no timestamp renewal."""
        if not self.path.exists():
            return None
        with closing(sqlite3.connect(self.path, timeout=10)) as connection:
            if not connection.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='observations'").fetchone():
                return None
            row = connection.execute("""SELECT id, payload FROM observations WHERE base_url=?
                AND json_extract(payload,'$.valid')=1 AND json_extract(payload,'$.status')='fresh'
                ORDER BY id DESC LIMIT 1""", (base_url,)).fetchone()
        return {**json.loads(row[1]), "record_id": row[0]} if row else None

    def room_window(self, base_url: str, since: str) -> list[dict]:
        """Recover only recent observations at startup, including identity changes and failures."""
        if not self.path.exists():
            return []
        with closing(sqlite3.connect(self.path, timeout=2)) as connection:
            if not connection.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='observations'").fetchone():
                return []
            rows = connection.execute(
                "SELECT received_at, payload FROM observations WHERE base_url=? ORDER BY id DESC",
                (base_url,))
            recent = []
            for received_at, payload in rows:
                if received_at < since:
                    break  # Existing (base_url, id) index lets startup skip older history.
                recent.append(json.loads(payload))
        return list(reversed(recent))
