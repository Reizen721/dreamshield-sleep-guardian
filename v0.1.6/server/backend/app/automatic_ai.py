"""Opt-in, bounded automatic image comparisons. No hardware or cloud I/O on import.

The capture scheduler hands this engine already-saved metadata. A single worker
performs model requests; a separate, cancellation-aware timer preserves a
confirmation image while the first model request may still be in flight.
"""

from __future__ import annotations

import copy
import math
import re
import threading
import time
import uuid
from collections import deque
from datetime import datetime, timezone
from typing import Any, Callable
from control_policy import CONFIRMATION_TARGET_SECONDS


def _timestamp(value: Any) -> float | None:
    if not isinstance(value, str) or not value or len(value) > 80:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            return None
        result = parsed.timestamp()
        return result if math.isfinite(result) else None
    except (ValueError, OverflowError, OSError):
        return None


def _identity(capture: Any) -> tuple[str, str] | None:
    if not isinstance(capture, dict):
        return None
    headers = capture.get("image_device_headers")
    if not isinstance(headers, dict):
        return None
    lowered = {str(key).lower(): value for key, value in headers.items()}
    device, boot = lowered.get("x-device-id"), lowered.get("x-boot-id")
    if not all(isinstance(value, str) and value.strip() and len(value) <= 200
               for value in (device, boot)):
        return None
    return device.strip(), boot.strip()


def _safe_json(value: Any, depth: int = 0) -> Any:
    """Bound persisted/current results and exclude credentials/image payloads."""
    if depth > 24:
        return None
    if value is None or isinstance(value, (bool, int)):
        return value
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, str):
        if value.lower().startswith("data:"):
            return "[image payload omitted]"
        return value[:16000]
    if isinstance(value, (list, tuple)):
        return [_safe_json(item, depth + 1) for item in value[:200]]
    if isinstance(value, dict):
        blocked = {"api_key", "apikey", "authorization", "access_token", "secret",
                   "password", "image_data_url", "before_image_data_url",
                   "after_image_data_url", "confirmation_image_data_url"}
        return {
            str(key)[:200]: (False if str(key).lower() in {"execute", "would_execute", "execution_allowed"}
                            else _safe_json(item, depth + 1))
            for key, item in list(value.items())[:400]
            if str(key).lower() not in blocked
        }
    return None


def _capture_ref(capture: dict[str, Any] | None) -> dict[str, Any] | None:
    if capture is None:
        return None
    keys = ("captured_at", "captured_at_basis", "source", "device_base_url",
            "filename", "url", "metadata_url")
    reference = {key: capture[key] for key in keys if key in capture}
    identity = _identity(capture)
    if identity is not None:
        reference.update(device_id=identity[0], boot_id=identity[1])
    return _safe_json(reference)


def _error_message(exc: Exception) -> str:
    """Show only a deliberately public classification, never raw exception text.

    Adapters may attach ``public_message`` for e.g. "VLM API HTTP 401". Generic
    exceptions retain only their class name because HTTP errors can echo keys,
    submitted image payloads or provider request details.
    """
    summary = f"({type(exc).__name__})"
    public_message = getattr(exc, "public_message", None)
    if isinstance(public_message, str) and public_message.strip():
        message = " ".join(public_message.split())[:1000]
        message = re.sub(r"(?i)data:[^\s]+", "[image payload omitted]", message)
        message = re.sub(r"(?i)bearer\s+[^\s,;]+", "Bearer [redacted]", message)
        message = re.sub(r"(?i)\bsk-[A-Za-z0-9_-]+", "[redacted]", message)
        message = re.sub(r"(?i)\b(api[_-]?key|password|secret|access[_-]?token)\s*[=:]\s*[^\s,;]+",
                         r"\1=[redacted]", message)
        summary += ": " + message[:240]
    return summary


class _Confirmation:
    def __init__(self) -> None:
        self.done = threading.Event()
        self.cancel = threading.Event()
        self.capture: dict[str, Any] | None = None
        self.error: str | None = None
        self.thread: threading.Thread | None = None


class AutoAIEngine:
    """Latest-only work admission with lifetime call budgets and generation gates.

    ``compare(before, after)`` makes one pair request. ``confirm(pair_result,
    before, after, confirmation)`` makes one after/confirmation pair request and reuses
    the original pair assessment. Model output alone never authorizes hardware I/O.
    ``capture(source, override_url=...)`` and ``persist(record)`` are injected so
    the engine itself knows neither disk paths nor provider credentials.
    An optional ``on_result(record)`` runs once after the initial audit is saved,
    while the generation lock is held. It must not acquire a settings lock that
    is held by callers of configure/disable (lock order: settings -> engine ->
    controller). The callback must provide its own command idempotency and may
    attach a local emission receipt; it must not claim appliance acknowledgement.
    """

    def __init__(
        self,
        *,
        compare: Callable[..., dict[str, Any]],
        confirm: Callable[..., dict[str, Any]],
        capture: Callable[..., dict[str, Any]],
        persist: Callable[[dict[str, Any]], dict[str, Any]],
        on_result: Callable[[dict[str, Any]], dict[str, Any]] | None = None,
        clock: Callable[[], float] = time.monotonic,
        wall_clock: Callable[[], float] = time.time,
    ) -> None:
        self._compare, self._confirm = compare, confirm
        self._capture, self._persist = capture, persist
        self._on_result = on_result
        self._clock, self._wall_clock = clock, wall_clock
        self._lock = threading.RLock()
        self._wake = threading.Event()
        self._stop_event: threading.Event | None = None
        self._enabled = False
        self._min_interval = 60
        self._confirmation_target = CONFIRMATION_TARGET_SECONDS
        self._max_hour = 20
        self._max_session = 50
        self._base_url = ""
        self._generation = 0
        self._baseline: dict[str, Any] | None = None
        self._latest_capture_time: float | None = None
        self._pending: dict[str, Any] | None = None
        self._active_job: dict[str, Any] | None = None
        self._active_cancel: threading.Event | None = None
        self._busy = False
        self._calls: deque[float] = deque()
        self._session_calls = 0
        self._next_analysis = 0.0
        self._backoff_until = 0.0
        self._state = "disabled"
        self._last_error: str | None = None
        self._skip_reason: str | None = "not_enabled"
        self._last_record: dict[str, Any] | None = None

    def configure(
        self,
        *,
        enabled: bool,
        min_interval_seconds: int,
        max_calls_per_hour: int,
        max_calls_per_session: int,
        device_base_url: str,
        confirmation_target_seconds: int = CONFIRMATION_TARGET_SECONDS,
    ) -> dict[str, Any]:
        if not isinstance(enabled, bool):
            raise ValueError("enabled must be a boolean")
        settings = (("min_interval_seconds", min_interval_seconds, 60, 86400),
                    ("max_calls_per_hour", max_calls_per_hour, 2, 120),
                    ("max_calls_per_session", max_calls_per_session, 2, 500),
                    ("confirmation_target_seconds", confirmation_target_seconds, 20, 50))
        for name, value, low, high in settings:
            if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
                raise ValueError(f"{name} must be an integer from {low} to {high}")
        if not isinstance(device_base_url, str) or not device_base_url.strip():
            raise ValueError("device_base_url is required")
        base_url = device_base_url.strip().rstrip("/")
        with self._lock:
            old = (self._enabled, self._min_interval, self._max_hour,
                   self._max_session, self._base_url, self._confirmation_target)
            new = (enabled, min_interval_seconds, max_calls_per_hour,
                   max_calls_per_session, base_url, confirmation_target_seconds)
            if old != new:
                (self._enabled, self._min_interval, self._max_hour, self._max_session,
                 self._base_url, self._confirmation_target) = new
                self._invalidate_locked()
                self._state = "waiting_baseline" if enabled else "disabled"
                self._skip_reason = "baseline_required" if enabled else "not_enabled"
                self._last_error = None
                # Re-enabling cannot reset budgets, backoff or compare spacing.
                self._wake.set()
            return self._snapshot_locked()

    def _invalidate_locked(self) -> None:
        self._generation += 1
        self._baseline = None
        self._latest_capture_time = None
        self._pending = None
        if self._active_cancel is not None:
            self._active_cancel.set()

    def disable(self, reason: str = "not_enabled") -> dict[str, Any]:
        with self._lock:
            self._enabled = False
            self._invalidate_locked()
            self._state = "disabled"
            self._skip_reason = str(reason)[:200]
            self._wake.set()
            return self._snapshot_locked()

    def _prune_calls_locked(self) -> None:
        cutoff = self._clock() - 3600
        while self._calls and self._calls[0] <= cutoff:
            self._calls.popleft()

    def _budget_available_locked(self, count: int) -> bool:
        self._prune_calls_locked()
        return (len(self._calls) + count <= self._max_hour
                and self._session_calls + count <= self._max_session)

    def _current_locked(self, generation: int) -> bool:
        return (self._enabled and generation == self._generation
                and not (self._stop_event is not None and self._stop_event.is_set()))

    def _capture_problem(self, capture: Any) -> str | None:
        if not isinstance(capture, dict) or _identity(capture) is None:
            return "image_device_identity_missing"
        base_url = capture.get("device_base_url")
        if not isinstance(base_url, str) or base_url.rstrip("/") != self._base_url:
            return "capture_device_url_mismatch"
        timestamp = _timestamp(capture.get("captured_at"))
        if timestamp is None:
            return "capture_timestamp_invalid"
        if timestamp > self._wall_clock() + 5:
            return "capture_timestamp_in_future"
        if not isinstance(capture.get("url"), str) or not capture["url"].startswith("/captures/"):
            return "capture_file_reference_missing"
        return None

    def submit(self, capture: dict[str, Any]) -> bool:
        """Admit only scheduled, same-device/boot, forward-time frame pairs.

        Does no image/model/disk I/O. Busy/interval-skipped captures advance only
        the timestamp watermark, not the last assessed baseline; otherwise a
        change between two inference ticks could silently disappear. No backlog
        is queued. A first frame requires no model request.
        """
        if not isinstance(capture, dict) or capture.get("source") != "scheduled":
            return False
        with self._lock:
            if not self._enabled:
                return False
            problem = self._capture_problem(capture)
            if problem is not None:
                self._invalidate_locked()
                self._state = "waiting_baseline"
                self._skip_reason = problem
                return False
            identity = _identity(capture)
            timestamp = _timestamp(capture["captured_at"])
            if self._active_job is not None and identity != _identity(self._active_job["after"]):
                self._invalidate_locked()
                self._baseline = copy.deepcopy(capture)
                self._latest_capture_time = timestamp
                self._state = "waiting_frame"
                self._skip_reason = "device_or_boot_changed"
                return False
            before = self._baseline
            if before is None:
                self._baseline = copy.deepcopy(capture)
                self._latest_capture_time = timestamp
                self._state = "waiting_frame"
                self._skip_reason = "baseline_saved"
                return False
            if identity != _identity(before):
                self._invalidate_locked()
                self._baseline = copy.deepcopy(capture)
                self._latest_capture_time = timestamp
                self._state = "waiting_frame"
                self._skip_reason = "device_or_boot_changed"
                return False
            gap = timestamp - _timestamp(before["captured_at"])
            if gap <= 0 or (self._latest_capture_time is not None and timestamp <= self._latest_capture_time):
                self._skip_reason = "capture_not_newer"
                return False
            self._latest_capture_time = timestamp
            if self._busy or self._pending is not None:
                self._skip_reason = "worker_busy_frame_not_queued"
                return False
            if gap > 300:
                # A long outage/analysis interval requires a new local reference;
                # do not compare arbitrarily old frames across scene changes.
                self._baseline = copy.deepcopy(capture)
                self._skip_reason = "frame_gap_too_large"
                self._state = "waiting_frame"
                return False
            now = self._clock()
            if now < self._backoff_until:
                self._state = "backoff"
                self._skip_reason = "failure_backoff"
                return False
            if not self._budget_available_locked(2):
                self._state = "budget_exhausted"
                self._skip_reason = ("session_budget_exhausted"
                                     if self._session_calls + 2 > self._max_session
                                     else "hourly_budget_exhausted")
                return False
            if now < self._next_analysis:
                self._state = "waiting_frame"
                self._skip_reason = "minimum_interval"
                return False
            self._pending = {
                "job_id": str(uuid.uuid4()), "generation": self._generation,
                "before": copy.deepcopy(before), "after": copy.deepcopy(capture),
                "confirmation_target_seconds": self._confirmation_target,
            }
            self._state = "analyzing"
            self._skip_reason = None
            self._wake.set()
            return True

    def _consume_call(self, job: dict[str, Any], *, pair: bool) -> bool:
        with self._lock:
            if not self._current_locked(job["generation"]):
                return False
            if not self._budget_available_locked(2 if pair else 1):
                self._state = "budget_exhausted"
                self._skip_reason = "call_budget_exhausted"
                return False
            now = self._clock()
            self._calls.append(now)
            self._session_calls += 1
            if pair:
                self._next_analysis = now + self._min_interval
            return True

    def _prefetch(self, job: dict[str, Any], cancellation: threading.Event) -> _Confirmation:
        pending = _Confirmation()
        after_time = _timestamp(job["after"]["captured_at"])

        def take_confirmation() -> None:
            try:
                while True:
                    with self._lock:
                        valid = self._current_locked(job["generation"])
                    if not valid or cancellation.is_set() or pending.cancel.is_set():
                        pending.error = "confirmation_cancelled"
                        return
                    remaining = after_time + job.get("confirmation_target_seconds", CONFIRMATION_TARGET_SECONDS) - self._wall_clock()
                    if remaining <= 0:
                        break
                    pending.cancel.wait(min(remaining, 0.2))
                if self._wall_clock() > after_time + 60:
                    pending.error = "confirmation_deadline_missed"
                    return
                # No lock is held during device I/O. An already-started request
                # cannot be recalled, but its late/cancelled result is discarded.
                with self._lock:
                    if not self._current_locked(job["generation"]) or cancellation.is_set():
                        pending.error = "confirmation_cancelled"
                        return
                captured = self._capture("automatic_confirmation", override_url=job["after"]["device_base_url"])
                problem = self._capture_problem(captured)
                if problem is not None:
                    pending.error = problem
                    return
                if _identity(captured) != _identity(job["after"]):
                    pending.error = "confirmation_device_or_boot_changed"
                    return
                delay = _timestamp(captured["captured_at"]) - after_time
                if not 20 <= delay <= 60:
                    pending.error = "confirmation_outside_20_60_seconds"
                    return
                pending.capture = copy.deepcopy(captured)
            except Exception as exc:
                pending.error = f"confirmation_capture_failed {_error_message(exc)}"
            finally:
                pending.done.set()

        pending.thread = threading.Thread(target=take_confirmation, name="automatic-ai-confirmation", daemon=True)
        pending.thread.start()
        return pending

    def _wait_confirmation(self, job: dict[str, Any], pending: _Confirmation) -> dict[str, Any] | None:
        deadline = _timestamp(job["after"]["captured_at"]) + 60
        while not pending.done.wait(0.1):
            with self._lock:
                if not self._current_locked(job["generation"]):
                    pending.cancel.set()
                    return None
            if self._wall_clock() > deadline:
                pending.error = "confirmation_deadline_missed"
                pending.cancel.set()
                return None
        return pending.capture

    @staticmethod
    def _check_result(result: Any) -> dict[str, Any]:
        if not isinstance(result, dict) or not isinstance(result.get("assessment"), dict):
            raise ValueError("The model callback returned an invalid assessment")
        return result

    def _publish(self, job: dict[str, Any], record: dict[str, Any], state: str) -> dict[str, Any]:
        record = _safe_json(record)
        record["execute"] = False
        # Never promote a completed old-generation model response after a reset.
        with self._lock:
            current = self._current_locked(job["generation"])
        if not current:
            record.update(status="cancelled", result=None, error="session_changed_or_stopped")
        try:
            persisted = self._persist(copy.deepcopy(record))
            if isinstance(persisted, dict):
                record = _safe_json(persisted)
                record["execute"] = False
        except Exception as exc:
            record["persistence_error"] = f"result_save_failed {_error_message(exc)}"
            state = "backoff"
        # An audit must already exist before invoking any integration that can
        # emit a signal. Generation re-check + callback are indivisible with
        # disable/configure: once disable returns, no new callback can start.
        if self._on_result is not None and not record.get("persistence_error"):
            with self._lock:
                eligible = (
                    self._current_locked(job["generation"])
                    and record.get("status") in {"pair_completed", "confirmation_completed"}
                    and not record.get("error")
                    and isinstance(record.get("result"), dict)
                    and isinstance(record["result"].get("assessment"), dict)
                )
                if eligible:
                    try:
                        integrated = self._on_result(copy.deepcopy(record))
                        if not isinstance(integrated, dict):
                            raise TypeError("on_result must return a record")
                        record = _safe_json(integrated)
                        record["execute"] = False
                    except Exception as exc:
                        record["integration_error"] = f"result_integration_failed {_error_message(exc)}"
                        state = "backoff"
                    # No callback retry, including when receipt persistence
                    # fails after a signal may already have been emitted.
                    try:
                        persisted = self._persist(copy.deepcopy(record))
                        if isinstance(persisted, dict):
                            record = _safe_json(persisted)
                            record["execute"] = False
                    except Exception as exc:
                        record["persistence_error"] = f"result_receipt_save_failed {_error_message(exc)}"
                        state = "backoff"
        with self._lock:
            if self._current_locked(job["generation"]):
                self._last_record = copy.deepcopy(record)
                self._state = state
                self._last_error = record.get("persistence_error") or record.get("integration_error") or record.get("error")
                if state == "backoff":
                    self._backoff_until = max(self._backoff_until, self._clock() + 60)
        return record

    def process_pending(self) -> dict[str, Any] | None:
        """Process one admitted job; used by run() and deterministic unit tests."""
        with self._lock:
            if self._busy or self._pending is None:
                return None
            job, self._pending = self._pending, None
            if not self._current_locked(job["generation"]):
                return None
            self._busy = True
            self._active_job = job
            cancellation = self._active_cancel = threading.Event()
        prefetch: _Confirmation | None = None
        result: dict[str, Any] | None = None
        confirmation: dict[str, Any] | None = None
        error: str | None = None
        status, state = "pair_completed", "completed"
        created = datetime.fromtimestamp(self._wall_clock(), timezone.utc).isoformat()
        try:
            with self._lock:
                ready = self._current_locked(job["generation"]) and self._budget_available_locked(2)
            if not ready:
                status, state, error = "cancelled", "budget_exhausted", "session_changed_or_call_budget_exhausted"
            else:
                prefetch = self._prefetch(job, cancellation)
                if not self._consume_call(job, pair=True):
                    status, state, error = "cancelled", "budget_exhausted", "session_changed_or_call_budget_exhausted"
                else:
                    result = self._check_result(self._compare(job["before"], job["after"]))
            with self._lock:
                if not self._current_locked(job["generation"]):
                    status = "cancelled"
                elif result is not None and (
                    result["assessment"].get("confirmation_required") is True
                    or result["assessment"].get("visual_candidate_accepted") is True
                ):
                    self._state = "waiting_confirmation"
                    status = "confirmation_pending"
            if status == "confirmation_pending":
                confirmation = self._wait_confirmation(job, prefetch)
                if confirmation is None:
                    error = prefetch.error or "confirmation_unavailable"
                    state = "backoff"
                elif self._consume_call(job, pair=False):
                    try:
                        result = self._check_result(self._confirm(result, job["before"], job["after"], confirmation))
                        status = "confirmation_completed"
                    except Exception as exc:
                        # Keep the initial visual candidate, never invent a
                        # confirmed state when the confirmation request fails.
                        error = f"confirmation_model_failed {_error_message(exc)}"
                        state = "backoff"
                else:
                    error = "confirmation_cancelled_or_call_budget_exhausted"
            if result is not None and status != "cancelled":
                with self._lock:
                    if self._current_locked(job["generation"]):
                        # Only a successfully assessed frame advances the
                        # comparison baseline. Captured but failed confirmation
                        # frames and skipped busy frames are not references.
                        assessed = confirmation if status == "confirmation_completed" else job["after"]
                        baseline_time = _timestamp((self._baseline or {}).get("captured_at"))
                        assessed_time = _timestamp(assessed["captured_at"])
                        if baseline_time is None or assessed_time > baseline_time:
                            self._baseline = copy.deepcopy(assessed)
                        self._latest_capture_time = max(self._latest_capture_time or assessed_time, assessed_time)
        except Exception as exc:
            error = f"comparison_model_failed {_error_message(exc)}"
            status, state = "error", "backoff"
        finally:
            cancellation.set()
            if prefetch is not None:
                prefetch.cancel.set()
            record = {
                "job_id": job["job_id"],
                "event_id": (((result or {}).get("assessment", {}).get("capture_timing") or {}).get("event_id")
                             or job["job_id"]),
                "created_at": created,
                "completed_at": datetime.fromtimestamp(self._wall_clock(), timezone.utc).isoformat(),
                "mode": "automatic_compare", "status": status,
                "before": _capture_ref(job["before"]), "after": _capture_ref(job["after"]),
                "confirmation": _capture_ref(confirmation),
                "result": result, "error": error, "execute": False,
                "confirmation_policy": f"after_frame_plus_{job.get('confirmation_target_seconds', CONFIRMATION_TARGET_SECONDS)}_seconds; valid_window_20_to_60_seconds",
            }
            published = self._publish(job, record, state)
            with self._lock:
                if self._active_job is job:
                    self._busy = False
                    self._active_job = None
                    self._active_cancel = None
        return published

    def run(self, stop_event: threading.Event) -> None:
        with self._lock:
            self._stop_event = stop_event
        try:
            while not stop_event.is_set():
                self._wake.wait(0.25)
                self._wake.clear()
                if not stop_event.is_set():
                    self.process_pending()
        finally:
            self.disable("server_stopped")

    def _snapshot_locked(self) -> dict[str, Any]:
        self._prune_calls_locked()
        next_at = max(self._next_analysis, self._backoff_until)
        if len(self._calls) + 2 > self._max_hour and self._calls:
            slots_needed = len(self._calls) + 2 - self._max_hour
            next_at = max(next_at, self._calls[slots_needed - 1] + 3600)
        next_epoch = (round((self._wall_clock() + max(0, next_at - self._clock())) * 1000)
                      if self._enabled and self._session_calls + 2 <= self._max_session else None)
        return {
            "enabled": self._enabled, "state": self._state,
            "min_interval_seconds": self._min_interval,
            "confirmation_target_seconds": self._confirmation_target,
            "max_calls_per_hour": self._max_hour,
            "max_calls_per_session": self._max_session,
            "calls_this_hour": len(self._calls), "calls_this_session": self._session_calls,
            "next_analysis_epoch_ms": next_epoch,
            "last_record": copy.deepcopy(self._last_record), "last_error": self._last_error,
            "skip_reason": self._skip_reason, "busy": self._busy,
            "baseline": _capture_ref(self._baseline), "execute": False,
        }

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return self._snapshot_locked()
