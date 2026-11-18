"""Explicit, session-only YAPOFB14 full-state IR control. No I/O on import.

Receipts describe local emission, never an air-conditioner acknowledgement.
Manual requests do not depend on a camera, VLM, or SHT31. Automatic requests can
change only the configured bounded cooling step, never power/mode/fan/swing.
The owning server must serialize configuration before its AI generation lock;
this controller never calls back into server/AI settings while holding its lock.
"""

from __future__ import annotations

import copy
import math
import re
import threading
import time
import uuid
from datetime import datetime, timezone
from urllib.parse import urlsplit

from live_control import number, timestamp, visual_state
from control_policy import (POLICY_VERSION, COMMAND_COOLDOWN_SECONDS, VISUAL_MAX_AGE_SECONDS,
                            FALLBACK_MAX_AGE_SECONDS, ENVIRONMENT_POLL_SECONDS,
                            ENVIRONMENT_PERSISTENCE_SECONDS, FRESH_ENVIRONMENT_MAX_AGE_SECONDS,
                            CoverageEventTracker, EnvironmentTracker, decide_control,
                            validate_policy_settings)


PROFILE = "yapofb14_basic_128"
STATE_KEYS = {"power", "mode", "setpoint_c", "fan", "swing_vertical", "swing_horizontal"}
_ENUMS = {"power": {"on", "off"}, "mode": {"auto", "cool", "dry", "fan", "heat"},
          "fan": {"auto", "low", "medium", "high"},
          "swing_vertical": {"on", "off"}, "swing_horizontal": {"on", "off"}}


def canonicalize_state(state):
    """Require a complete supported state; document the profile's two coercions."""
    if not isinstance(state, dict) or set(state) != STATE_KEYS:
        raise ValueError("Provide exactly power, mode, setpoint_c, fan, swing_vertical and swing_horizontal")
    result = copy.deepcopy(state)
    for key, allowed in _ENUMS.items():
        if not isinstance(result[key], str) or result[key] not in allowed:
            raise ValueError(f"Unsupported {key}")
    if type(result["setpoint_c"]) is not int or not 16 <= result["setpoint_c"] <= 30:
        raise ValueError("setpoint_c must be a whole degree from 16 to 30")
    if result["mode"] == "auto":
        result["setpoint_c"] = 25
    if result["mode"] == "dry":
        result["fan"] = "low"
    return result


# Friendly import alias for server callers and tests.
normalize_state = canonicalize_state


def _identifier(value, name):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", value):
        raise ValueError(f"Invalid {name}")
    return value


def _base_url(value):
    if not isinstance(value, str):
        raise ValueError("Invalid device URL")
    value = value.strip().rstrip("/")
    try:
        parsed = urlsplit(value)
        valid = (parsed.scheme == "http" and parsed.hostname and not parsed.username and not parsed.password
                 and not parsed.path and not parsed.query and not parsed.fragment)
        parsed.port
    except ValueError:
        valid = False
    if not valid:
        raise ValueError("Use an http:// device base URL without a path or credentials")
    return value


class AcController:
    """One board/boot per explicitly enabled session; injected sender and audit."""

    def __init__(self, sender, audit, clock=time.monotonic, wall_clock=time.time):
        self._send, self._audit = sender, audit
        self._clock, self._wall = clock, wall_clock
        self._lock = threading.RLock()
        self.enabled = False
        self.base_url = ""
        self.identity = None
        self.assumed_state = None
        self.last_commanded_state = None
        self._state_unknown = True
        self.last_result = None
        self.reason = "session_disabled"
        self._last_attempt = -math.inf
        self._requests = {}
        self._seen_jobs = set()
        self.automatic_enabled = False
        self.automatic_reason = "automatic_disabled"
        self.manual_override_active = False
        self.min_setpoint_c, self.max_setpoint_c = 24, 28
        self.temperature_lower_c, self.temperature_upper_c = 24., 28.
        self._automatic_since = None
        self.visual = None
        self.excursion = None
        self.last_sensor_key = None
        self.environment_tracker = EnvironmentTracker()
        self.coverage_event = CoverageEventTracker()
        self.policy_settings = validate_policy_settings()

    def _apply_policy_settings(self, settings):
        self.policy_settings = copy.deepcopy(settings)
        self.min_setpoint_c, self.max_setpoint_c = settings["min_setpoint_c"], settings["max_setpoint_c"]
        self.temperature_lower_c, self.temperature_upper_c = settings["temperature_lower_c"], settings["temperature_upper_c"]

    def set_policy_settings(self, settings):
        """Update shared settings without granting control or resetting safety memory."""
        validated = validate_policy_settings(settings)
        with self._lock:
            self._apply_policy_settings(validated)
            self.disable_automatic("policy_settings_changed_explicit_resume_required")
            return self.snapshot()

    def _state(self):
        if self._state_unknown:
            return None
        return self.last_commanded_state if self.last_commanded_state is not None else self.assumed_state

    def _state_source(self):
        if self._state_unknown or self._state() is None:
            return "unknown"
        return "last_local_emission" if self.last_commanded_state is not None else "user_assumption"

    @staticmethod
    def _settings(minimum, maximum, lower, upper):
        if (type(minimum) is not int or type(maximum) is not int
                or not 16 <= minimum <= maximum <= 30):
            raise ValueError("Automatic setpoint bounds must be whole degrees: 16 <= min <= max <= 30")
        if not number(lower, 18, 32) or not number(upper, 18, 32) or upper - lower < 2:
            raise ValueError("Ambient temperature band must be within 18–32 C and at least 2 C wide")
        return minimum, maximum, float(lower), float(upper)

    def configure(self, *, enabled, base_url="", device_id=None, boot_id=None, initial_state=None,
                  min_setpoint_c=None, max_setpoint_c=None, temperature_lower_c=None, temperature_upper_c=None):
        if type(enabled) is not bool:
            raise ValueError("enabled must be boolean")
        with self._lock:
            if not enabled:
                self.disable("user_stopped")
                return self.snapshot()
            if self.enabled:
                raise ValueError("Stop this control session before changing the device or initial assumption")
            base = _base_url(base_url)
            identity = (_identifier(device_id, "device_id"), _identifier(boot_id, "boot_id"))
            assumed = canonicalize_state(initial_state) if initial_state is not None else None
            settings = self._settings(
                self.min_setpoint_c if min_setpoint_c is None else min_setpoint_c,
                self.max_setpoint_c if max_setpoint_c is None else max_setpoint_c,
                self.temperature_lower_c if temperature_lower_c is None else temperature_lower_c,
                self.temperature_upper_c if temperature_upper_c is None else temperature_upper_c)
            self.coverage_event.reset(identity)
            self.enabled, self.base_url, self.identity = True, base, identity
            self.assumed_state, self.last_commanded_state = assumed, None
            self.last_result = None
            self._state_unknown = assumed is None
            (self.min_setpoint_c, self.max_setpoint_c,
             self.temperature_lower_c, self.temperature_upper_c) = settings
            self.policy_settings.update(min_setpoint_c=settings[0], max_setpoint_c=settings[1],
                                        temperature_lower_c=settings[2], temperature_upper_c=settings[3])
            self.manual_override_active = False
            self.disable_automatic("waiting_explicit_automatic_resume")
            self.reason = "session_enabled_no_transmission"
            # Neither cooldowns nor request IDs are reset by re-enabling.
            return self.snapshot()

    def disable(self, reason="user_stopped"):
        with self._lock:
            self.enabled = False
            self.reason = str(reason)[:160]
            self.disable_automatic(reason)

    def disable_automatic(self, reason="automatic_stopped"):
        """AI stop/failure must not revoke an otherwise valid manual session."""
        with self._lock:
            self.automatic_enabled = False
            self.automatic_reason = str(reason)[:160]
            self._automatic_since = None
            self.visual, self.excursion, self.last_sensor_key = None, None, None
            self.environment_tracker.reset()

    def update_automatic(self, *, enabled, min_setpoint_c=None, max_setpoint_c=None,
                         temperature_lower_c=None, temperature_upper_c=None):
        if type(enabled) is not bool:
            raise ValueError("enabled must be boolean")
        with self._lock:
            if not enabled:
                self.disable_automatic("user_stopped_automatic")
                return self.snapshot()
            if not self.enabled:
                raise ValueError("Enable the manual/control session first")
            current = self._state()
            if current is None or current["power"] != "on" or current["mode"] != "cool":
                raise ValueError("Automatic adjustment requires an explicitly assumed or locally commanded ON/COOL state")
            settings = self._settings(
                self.min_setpoint_c if min_setpoint_c is None else min_setpoint_c,
                self.max_setpoint_c if max_setpoint_c is None else max_setpoint_c,
                self.temperature_lower_c if temperature_lower_c is None else temperature_lower_c,
                self.temperature_upper_c if temperature_upper_c is None else temperature_upper_c)
            if not settings[0] <= current["setpoint_c"] <= settings[1]:
                raise ValueError("The current assumed/commanded setpoint is outside the automatic bounds")
            (self.min_setpoint_c, self.max_setpoint_c,
             self.temperature_lower_c, self.temperature_upper_c) = settings
            self.policy_settings.update(min_setpoint_c=settings[0], max_setpoint_c=settings[1],
                                        temperature_lower_c=settings[2], temperature_upper_c=settings[3])
            self.automatic_enabled = True
            self.manual_override_active = False
            self.automatic_reason = "waiting_new_verified_visual"
            self._automatic_since = self._wall()
            self.visual, self.excursion, self.last_sensor_key = None, None, None
            self.environment_tracker.reset()
            return self.snapshot()

    def resume_automatic(self, **settings):
        return self.update_automatic(enabled=True, **settings)

    def snapshot(self):
        with self._lock:
            elapsed = self._clock() - self._last_attempt
            return {"enabled": self.enabled, "profile": PROFILE, "experimental_compatibility": True,
                    "policy_settings": copy.deepcopy(self.policy_settings),
                    "ac_acknowledged": False, "device_base_url": self.base_url,
                    "device_id": self.identity[0] if self.identity else None,
                    "boot_id": self.identity[1] if self.identity else None,
                    "state": copy.deepcopy(self._state()), "state_source": self._state_source(),
                    "assumed_state": copy.deepcopy(self.assumed_state),
                    "last_commanded_state": copy.deepcopy(self.last_commanded_state),
                    "reason": self.reason, "last_result": copy.deepcopy(self.last_result),
                    "manual_cooldown_remaining_seconds": round(max(0, 5-elapsed), 1),
                    "automatic": {"enabled": self.automatic_enabled, "reason": self.automatic_reason,
                        "policy_settings": copy.deepcopy(self.policy_settings),
                        "policy_version": POLICY_VERSION, "coverage_event": self.coverage_event.snapshot(),
                        "manual_override_active": self.manual_override_active,
                        "min_setpoint_c": self.min_setpoint_c, "max_setpoint_c": self.max_setpoint_c,
                        "temperature_lower_c": self.temperature_lower_c, "temperature_upper_c": self.temperature_upper_c,
                        "command_cooldown_seconds": self.policy_settings["command_cooldown_seconds"],
                        "cooldown_remaining_seconds": round(max(0, self.policy_settings["command_cooldown_seconds"]-elapsed), 1),
                        "visual_state": (self.visual or {}).get("state"),
                        "visual_max_age_seconds": VISUAL_MAX_AGE_SECONDS,
                        "fallback_max_age_seconds": FALLBACK_MAX_AGE_SECONDS,
                        "environment_poll_seconds": ENVIRONMENT_POLL_SECONDS,
                        "environment_persistence_seconds": self.policy_settings["environment_persistence_seconds"],
                        "temperature_step_c": self.policy_settings["temperature_step_c"],
                        "confirmation_target_seconds": self.policy_settings["confirmation_target_seconds"],
                        "fresh_environment_max_age_seconds": FRESH_ENVIRONMENT_MAX_AGE_SECONDS}}

    def _hold(self, reason, *, automatic=False):
        if automatic:
            self.automatic_reason = reason
        else:
            self.reason = reason
        return {"status": "held", "reason": reason, "emitted": False,
                "transmitted_this_request": False, "ac_acknowledged": False}

    def manual(self, state, request_id):
        """Explicit full-state submission; a new request pauses auto even if held."""
        canonical = canonicalize_state(state)
        request_id = _identifier(request_id, "request_id")
        with self._lock:
            key = (self.base_url, self.identity, request_id)
            prior = self._requests.get(key)
            if prior is not None:
                if prior["state"] != canonical:
                    raise ValueError("request_id was already used for a different state")
                return {**copy.deepcopy(prior["result"]), "duplicate": True, "transmitted_this_request": False}
            self.disable_automatic("manual_override_requires_explicit_resume")
            self.manual_override_active = True
            if not self.enabled:
                return self._hold("session_disabled")
            if len(self._requests) >= 2000:
                self.disable("request_history_limit_rearm_required")
                return self._hold("request_history_limit_rearm_required")
            if self._clock() - self._last_attempt < 5:
                result = self._hold("manual_command_cooldown")
            else:
                result = self._emit(canonical, "manual", "explicit_manual_state", request_id=request_id)
            self._requests[key] = {"state": copy.deepcopy(canonical), "result": copy.deepcopy(result)}
            return copy.deepcopy(result)

    def accept(self, record, environment):
        """Accept only the owning engine's successfully audited, current AI result."""
        with self._lock:
            if not self.enabled or not self.automatic_enabled:
                return self._hold("automatic_disabled", automatic=True)
            job = record.get("job_id") if isinstance(record, dict) else None
            if not isinstance(job, str) or not job:
                return self._hold("invalid_visual_job", automatic=True)
            if job in self._seen_jobs:
                return {"status": "duplicate_result_ignored", "emitted": False, "ac_acknowledged": False}
            if (record.get("status") not in {"pair_completed", "confirmation_completed"}
                    or record.get("error") or record.get("integration_error") or record.get("persistence_error")):
                return self._hold("visual_result_incomplete_or_failed", automatic=True)
            assessment = (record.get("result") or {}).get("assessment")
            if not isinstance(assessment, dict):
                return self._hold("invalid_visual_result", automatic=True)
            self._seen_jobs.add(job)
            if len(self._seen_jobs) > 2000:
                self.disable_automatic("visual_history_limit")
                return self._hold("visual_history_limit", automatic=True)
            frame = record.get("confirmation") if assessment.get("confirmation_frame") else record.get("after")
            frame = frame if isinstance(frame, dict) else {}
            identity = (frame.get("device_id"), frame.get("boot_id"))
            if identity != self.identity or frame.get("device_base_url") != self.base_url:
                self.disable("device_identity_changed_rearm_required")
                return self._hold("device_identity_changed_rearm_required", automatic=True)
            observed = timestamp(frame.get("captured_at"))
            if observed is None or observed < self._automatic_since or not 0 <= self._wall()-observed <= VISUAL_MAX_AGE_SECONDS:
                self.visual = None
                return self._hold("visual_expired_or_predates_resume", automatic=True)
            validated_visual = visual_state(assessment)
            frame_id = frame.get("frame_id") or frame.get("capture_id") or frame.get("captured_at")
            if not self.coverage_event.observe(validated_visual["state"], observed, frame_id):
                return self._hold("visual_duplicate_or_out_of_order", automatic=True)
            self.visual = {**validated_visual, "captured_at": frame.get("captured_at"), "job_id": job}
            return self.evaluate(environment, trigger="visual")

    def _track_environment(self, env):
        """Advance the independent slow loop only with distinct real samples."""
        sensor, error = self.environment_tracker.observe(env, self._wall(), self.identity,
                                                         self.temperature_lower_c, self.temperature_upper_c,
                                                         self.policy_settings["environment_persistence_seconds"])
        self.excursion, self.last_sensor_key = self.environment_tracker.excursion, self.environment_tracker.last_sensor_key
        return sensor, error

    def accept_thermal(self, record, environment, *, dry_run=False):
        """Internal bridge for validated thermal fusion; no RGB identity fabrication.

        The orchestrator binds thermal stream and actuator identity separately,
        audits the evidence before calling here, and gates cancellation/freshness.
        This method is deliberately not exposed as an HTTP command endpoint.
        """
        with self._lock:
            if not self.enabled or not self.automatic_enabled:
                return self._hold("automatic_disabled", automatic=True)
            if tuple(record.get("actuator_identity", ())) != self.identity:
                return self._hold("thermal_actuator_binding_changed", automatic=True)
            observed = timestamp(record.get("captured_at"))
            state = record.get("state")
            if (observed is None or observed < self._automatic_since
                    or not 0 <= self._wall()-observed <= 90
                    or state not in {"covered", "empty", "confirmed_uncovered", "uncovered_candidate", "uncertain"}):
                self.visual = None
                return self._hold("invalid_or_expired_thermal_evidence", automatic=True)
            job = record.get("job_id")
            if not isinstance(job, str) or not job or not record.get("thermal_identity"):
                return self._hold("invalid_thermal_job", automatic=True)
            if job not in self._seen_jobs:
                if len(self._seen_jobs) >= 2000:
                    self.disable_automatic("visual_history_limit")
                    return self._hold("visual_history_limit", automatic=True)
                if (self.coverage_event.last_observed_at is None or observed > self.coverage_event.last_observed_at) and not self.coverage_event.observe(state, observed, job):
                    return self._hold("visual_duplicate_or_out_of_order", automatic=True)
                self._seen_jobs.add(job)
                self.visual = {"state": state, "captured_at": record["captured_at"], "job_id": job,
                               "thermal_identity": record["thermal_identity"]}
            return self.evaluate(environment, trigger="thermal_fusion", dry_run=dry_run)

    def preview_thermal(self, state, environment):
        """Ask the SAME policy whether a fresh review is needed. Never grants permission."""
        with self._lock:
            if not self.enabled or not self.automatic_enabled:
                return {"status":"held", "reason":"automatic_disabled"}
            sensor, error = self._track_environment(environment)
            if error:
                return {"status":"held", "reason":error}
            return decide_control(current_state=self._state(), visual_state=state,
                temperature_c=sensor['temperature_c'], environment_direction=sensor['direction'],
                environment_persistent=sensor['persistent'], min_setpoint_c=self.min_setpoint_c,
                max_setpoint_c=self.max_setpoint_c, temperature_lower_c=self.temperature_lower_c,
                temperature_upper_c=self.temperature_upper_c,
                cooldown_remaining_seconds=max(0,self.policy_settings['command_cooldown_seconds']-(self._clock()-self._last_attempt)),
                temperature_step_c=self.policy_settings['temperature_step_c'],
                environment_persistence_seconds=self.policy_settings['environment_persistence_seconds'],
                event_handled=self.coverage_event.handled, event_active=self.coverage_event.active,
                manual_override=self.manual_override_active)

    def evaluate(self, environment, trigger="environment", *, allow_emit=True, dry_run=False):
        with self._lock:
            if not self.enabled or not self.automatic_enabled:
                return self._hold("automatic_disabled", automatic=True)
            current = self._state()
            if current is None or current["power"] != "on" or current["mode"] != "cool":
                self.disable_automatic("automatic_requires_known_on_cool_state")
                return self._hold("automatic_requires_known_on_cool_state", automatic=True)
            sensor, error = self._track_environment(environment)
            if error:
                return self._hold(error, automatic=True)
            # Sensor continuity is independent of a model request's duration.
            # Missing/old visual evidence still blocks EVERY automatic command.
            if allow_emit is not True:
                return self._hold("emission_deferred_environment_tracking", automatic=True)
            visual = self.visual or {}
            observed = timestamp(visual.get("captured_at"))
            if observed is None or not 0 <= self._wall()-observed <= (90 if visual.get('thermal_identity') else VISUAL_MAX_AGE_SECONDS):
                return self._hold("visual_expired_or_missing", automatic=True)
            proposal = decide_control(current_state=current, visual_state=visual.get("state"),
                temperature_c=sensor["temperature_c"], environment_direction=sensor["direction"],
                environment_persistent=sensor["persistent"], min_setpoint_c=self.min_setpoint_c,
                max_setpoint_c=self.max_setpoint_c, temperature_lower_c=self.temperature_lower_c,
                temperature_upper_c=self.temperature_upper_c,
                cooldown_remaining_seconds=max(0, self.policy_settings["command_cooldown_seconds"]-(self._clock()-self._last_attempt)),
                temperature_step_c=self.policy_settings["temperature_step_c"],
                environment_persistence_seconds=self.policy_settings["environment_persistence_seconds"],
                event_handled=self.coverage_event.handled, event_active=self.coverage_event.active,
                manual_override=self.manual_override_active)
            if proposal["status"] != "proposed":
                return self._hold(proposal["reason"], automatic=True)
            state = {**current, "setpoint_c": proposal["desired_setpoint_c"]}
            if dry_run:
                return {**proposal, "status": "preview_only", "state": state, "emitted": False,
                        "transmitted_this_request": False, "ac_acknowledged": False}
            result = self._emit(state, "automatic", proposal["reason"], trigger=trigger, environment=environment,
                                visual_job_id=visual.get("job_id"),
                                coverage_event_latch=proposal["event_latch_required"])
            self.automatic_reason = result.get("reason", result["status"])
            return result

    def _emit(self, state, source, reason, *, request_id=None, trigger=None, environment=None, visual_job_id=None,
              coverage_event_latch=False):
        command = {"command_id": str(uuid.uuid4()), "request_id": request_id, "profile": PROFILE,
                   "state": canonicalize_state(state), "device_id": self.identity[0], "boot_id": self.identity[1],
                   "device_base_url": self.base_url, "source": source, "trigger": trigger or source,
                   "reason": reason, "visual_job_id": visual_job_id,
                   "created_at": datetime.fromtimestamp(self._wall(), timezone.utc).isoformat(),
                   "experimental_compatibility": True, "emission_only": True, "ac_acknowledged": False,
                   "status": "prepared"}
        if source == "automatic":
            command.update(policy_version=POLICY_VERSION, coverage_event_id=self.coverage_event.event_id,
                           coverage_event_latch_required=coverage_event_latch,
                           policy_settings=copy.deepcopy(self.policy_settings))
        if environment is not None:
            fields = {"source", "control_usable", "measured_at", "age_seconds", "device_id", "boot_id",
                      "sensor_id", "sample_sequence", "ambient_temperature_c", "relative_humidity_percent"}
            command["environment"] = {key: copy.deepcopy(value) for key, value in environment.items() if key in fields}
        try:
            self._audit(copy.deepcopy(command))
        except Exception:
            self.disable("audit_write_failed")
            result = {**command, "status": "not_emitted_audit_failed", "reason": "audit_write_failed",
                      "emitted": False, "transmitted_this_request": False}
            self.last_result = copy.deepcopy(result)
            return result
        if coverage_event_latch:
            # The send might physically succeed even if its reply is lost. The
            # event stays handled after auto/session toggles on this board boot.
            self.coverage_event.mark_handled(self._wall())
        self._last_attempt = self._clock()
        try:
            response = self._send(copy.deepcopy(command))
            duplicate = response.get("duplicate", False) if isinstance(response, dict) else None
            transmitted = response.get("transmitted_this_request") if isinstance(response, dict) else None
            if (not isinstance(response, dict) or response.get("status") != "emitted_local_only"
                    or response.get("emitted") is not True or response.get("ac_acknowledged") is not False
                    or response.get("command_id") != command["command_id"]
                    or response.get("boot_id") != command["boot_id"]
                    or response.get("device_id", command["device_id"]) != command["device_id"]
                    or response.get("state") != command["state"]
                    or type(duplicate) is not bool or type(transmitted) is not bool or transmitted == duplicate):
                raise ValueError("Unverified full-state emission response")
            result = {**command, "status": "emitted_local_only", "emitted": True,
                      "duplicate": duplicate, "transmitted_this_request": transmitted}
            self.last_commanded_state = copy.deepcopy(command["state"])
            self._state_unknown = False
            self.reason = "emitted_local_only_not_ac_acknowledged"
        except Exception:
            # Even a lost reply can follow a successful physical emission.
            self._state_unknown = True
            self.assumed_state = None
            self.disable("emission_outcome_unknown_rearm_required")
            result = {**command, "status": "outcome_unknown_no_retry", "emitted": None,
                      "transmitted_this_request": None, "reason": "emission_outcome_unknown_rearm_required"}
        self.last_result = copy.deepcopy(result)
        try:
            self._audit(copy.deepcopy(result))
        except Exception:
            self.disable("result_audit_write_failed")
            result["audit_error"] = "result_audit_write_failed"
            self.last_result = copy.deepcopy(result)
        return copy.deepcopy(result)
