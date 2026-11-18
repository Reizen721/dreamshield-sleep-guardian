"""Shared deterministic policy for offline replay and device control; no I/O.

Visual evidence is supplied by validated VLM results. These rules do not infer
human comfort, measure AC execution, or permit the model to choose commands.
"""
from __future__ import annotations

import copy
import math
import uuid
from datetime import datetime

POLICY_VERSION = "dreamshield-policy-v0.3"
CONFIRMATION_TARGET_SECONDS = 20
ENVIRONMENT_PERSISTENCE_SECONDS = 45
ENVIRONMENT_MAX_GAP_SECONDS = 45
FRESH_ENVIRONMENT_MAX_AGE_SECONDS = 45
ENVIRONMENT_POLL_SECONDS = 15
COMMAND_COOLDOWN_SECONDS = 300
VISUAL_MAX_AGE_SECONDS = 180
FALLBACK_MAX_AGE_SECONDS = 300
EVENT_RECOVERY_SECONDS = 60
EVENT_RECOVERY_MAX_GAP_SECONDS = 180
DEFAULT_POLICY_SETTINGS = {
    "min_setpoint_c": 24, "max_setpoint_c": 28,
    "temperature_lower_c": 24., "temperature_upper_c": 28.,
    "environment_persistence_seconds": ENVIRONMENT_PERSISTENCE_SECONDS,
    "temperature_step_c": 1, "command_cooldown_seconds": COMMAND_COOLDOWN_SECONDS,
    "confirmation_target_seconds": CONFIRMATION_TARGET_SECONDS,
}

# Saved strategy targets only. Selecting one never contacts the device or
# implies that its SHT31 sampling interval has already been applied.
CONTROL_PRESETS = {
    "light": {
        "label": "轻享", "description": "更宽的室温区间与更长等待，减少调节次数。",
        "policy": {**DEFAULT_POLICY_SETTINGS, "temperature_lower_c": 23., "temperature_upper_c": 29.,
                   "environment_persistence_seconds": 120, "command_cooldown_seconds": 600,
                   "confirmation_target_seconds": 40},
        "environment_interval_seconds": 30, "sensor_sample_target_seconds": 30,
    },
    "balanced": {
        "label": "均衡", "description": "兼顾响应速度与调节频率，适合日常使用。",
        "policy": dict(DEFAULT_POLICY_SETTINGS),
        "environment_interval_seconds": 15, "sensor_sample_target_seconds": 15,
    },
    "efficient": {
        "label": "高效", "description": "更早响应室温变化，缩短环境等待与调节冷却。",
        "policy": {**DEFAULT_POLICY_SETTINGS, "temperature_lower_c": 25., "temperature_upper_c": 27.,
                   "environment_persistence_seconds": 30, "command_cooldown_seconds": 180},
        "environment_interval_seconds": 10, "sensor_sample_target_seconds": 10,
    },
}


def identify_policy_preset(policy, environment_interval_seconds, sensor_sample_target_seconds):
    """Match complete saved values without modifying or rounding them."""
    for key, preset in CONTROL_PRESETS.items():
        if (policy == preset["policy"]
                and environment_interval_seconds == preset["environment_interval_seconds"]
                and sensor_sample_target_seconds == preset["sensor_sample_target_seconds"]):
            return key
    return "custom"


def _number(value, lower, upper):
    return type(value) in (int, float) and math.isfinite(value) and lower <= value <= upper


def _timestamp(value):
    if _number(value, 0, 1e12):
        return float(value)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.timestamp() if parsed.tzinfo else None
    except (AttributeError, TypeError, ValueError, OverflowError):
        return None


def validate_policy_settings(settings=None):
    """Return a complete copy; reject unknown names and unsafe/type-invalid values."""
    if settings is None:
        settings = {}
    if not isinstance(settings, dict):
        raise ValueError("control policy settings must be an object")
    unknown = set(settings)-set(DEFAULT_POLICY_SETTINGS)
    if unknown:
        raise ValueError("Unknown control policy settings: " + ", ".join(sorted(map(str, unknown))))
    result = {**DEFAULT_POLICY_SETTINGS, **settings}
    ranges = {"min_setpoint_c": (16, 30), "max_setpoint_c": (16, 30),
              "environment_persistence_seconds": (30, 600), "temperature_step_c": (1, 2),
              "command_cooldown_seconds": (60, 3600), "confirmation_target_seconds": (20, 50)}
    for key, (minimum, maximum) in ranges.items():
        if type(result[key]) is not int or not minimum <= result[key] <= maximum:
            raise ValueError(f"{key} must be a whole number from {minimum} to {maximum}")
    if result["min_setpoint_c"] > result["max_setpoint_c"]:
        raise ValueError("min_setpoint_c must not exceed max_setpoint_c")
    for key in ("temperature_lower_c", "temperature_upper_c"):
        if not _number(result[key], 18, 32):
            raise ValueError(f"{key} must be a finite number from 18 to 32")
        result[key] = float(result[key])
    if result["temperature_upper_c"]-result["temperature_lower_c"] < 2:
        raise ValueError("Ambient temperature band must be at least 2 C wide")
    return result


def decide_control(*, current_state, visual_state, temperature_c, environment_direction=None,
                   environment_persistent=False, min_setpoint_c=24, max_setpoint_c=28,
                   temperature_lower_c=24., temperature_upper_c=28.,
                   cooldown_remaining_seconds=0, event_handled=False, event_active=False,
                   manual_override=False, temperature_step_c=1,
                   environment_persistence_seconds=ENVIRONMENT_PERSISTENCE_SECONDS):
    """Same proposal for replay/live; callers separately validate age/identity.

No sender is called. A proposed warming action in an active uncovered episode
must be latched before an actual send attempt, including uncertain outcomes.
"""
    result = {"status": "held", "reason": "within_band_no_change", "delta": 0,
              "desired_setpoint_c": None, "event_latch_required": False,
              "policy_version": POLICY_VERSION}

    def hold(reason):
        return {**result, "reason": reason}

    if manual_override:
        return hold("manual_override_requires_explicit_resume")
    if (not isinstance(current_state, dict) or current_state.get("power") != "on"
            or current_state.get("mode") != "cool" or type(current_state.get("setpoint_c")) is not int):
        return hold("automatic_requires_known_on_cool_state")
    try:
        validate_policy_settings({"min_setpoint_c": min_setpoint_c, "max_setpoint_c": max_setpoint_c,
            "temperature_lower_c": temperature_lower_c, "temperature_upper_c": temperature_upper_c,
            "temperature_step_c": temperature_step_c, "environment_persistence_seconds": environment_persistence_seconds})
    except ValueError:
        return hold("invalid_control_policy_settings")
    if not min_setpoint_c <= current_state["setpoint_c"] <= max_setpoint_c:
        return hold("invalid_control_policy_settings")
    if not _number(temperature_c, 5, 40):
        return hold("environment_unavailable_or_expired")
    if visual_state not in {"covered", "confirmed_uncovered"}:
        return hold("no_reliable_occupied_coverage")
    delta, reason = 0, "within_band_no_change"
    if visual_state == "confirmed_uncovered" and temperature_c <= temperature_lower_c+1:
        delta, reason = temperature_step_c, "persistent_uncovered_and_cool_environment"
    elif environment_persistent is True and environment_direction == "cold" and temperature_c <= temperature_lower_c:
        delta, reason = temperature_step_c, f"temperature_below_band_for_{environment_persistence_seconds}_seconds"
    elif (environment_persistent is True and environment_direction == "hot"
          and temperature_c >= temperature_upper_c and visual_state == "covered"):
        if event_active:
            return hold("coverage_event_recovery_pending")
        delta, reason = -temperature_step_c, f"temperature_above_band_for_{environment_persistence_seconds}_seconds_covered"
    if not delta:
        return hold("uncovered_but_not_cool_no_automatic_warming" if visual_state == "confirmed_uncovered"
                    else "collecting_environment_persistence" if environment_direction else reason)
    if delta > 0 and event_handled:
        return hold("coverage_event_already_handled")
    desired = current_state["setpoint_c"] + delta
    if not min_setpoint_c <= desired <= max_setpoint_c:
        return hold("automatic_setpoint_bound_reached")
    if not _number(cooldown_remaining_seconds, 0, 1e12):
        return hold("invalid_command_cooldown")
    if cooldown_remaining_seconds > 0:
        return hold("automatic_command_cooldown")
    return {**result, "status": "proposed", "reason": reason, "delta": delta,
            "desired_setpoint_c": desired,
            "event_latch_required": delta > 0 and (event_active or visual_state == "confirmed_uncovered")}


class EnvironmentTracker:
    """Distinct fresh measurements, never repeated polls or fallback trends."""

    def __init__(self):
        self.reset()

    def reset(self):
        self.excursion = None
        self.last_sensor_key = None
        self._last_measured = None

    def observe(self, env, now, identity, lower=24., upper=28.,
                environment_persistence_seconds=ENVIRONMENT_PERSISTENCE_SECONDS):
        def invalid(reason):
            self.excursion = None
            return None, reason

        if (type(environment_persistence_seconds) is not int or not 30 <= environment_persistence_seconds <= 600):
            return invalid("invalid_environment_persistence_settings")
        if (not isinstance(env, dict) or env.get("control_usable") is not True
                or (env.get("device_id"), env.get("boot_id")) != identity):
            return invalid("environment_unavailable_or_expired")
        fallback = env.get("source") == "last_valid"
        if env.get("source") not in {"fresh", "sensor", "last_valid"}:
            return invalid("environment_source_invalid")
        measured = _timestamp(env.get("measured_at"))
        if measured is None or not 0 <= now-measured <= (FALLBACK_MAX_AGE_SECONDS if fallback else FRESH_ENVIRONMENT_MAX_AGE_SECONDS):
            return invalid("environment_unavailable_or_expired")
        temperature, humidity = env.get("ambient_temperature_c"), env.get("relative_humidity_percent")
        if not _number(temperature, 5, 40) or not _number(humidity, 10, 90):
            return invalid("outside_experimental_sensor_range")
        key = (env.get("sensor_id"), env.get("sample_sequence"))
        if not isinstance(key[0], str) or not key[0] or type(key[1]) is not int or key[1] < 1:
            return invalid("invalid_sensor_sequence")
        direction = "cold" if temperature <= lower else "hot" if temperature >= upper else None
        if fallback:
            self.excursion = None
        elif key != self.last_sensor_key:
            previous, old = self.last_sensor_key, self.excursion
            reordered = bool(previous and key[0] == previous[0]
                             and (key[1] <= previous[1] or measured <= self._last_measured))
            if reordered:
                # Clear continuity, but keep the newest watermark: replaying old
                # sequence numbers cannot establish a second synthetic trend.
                return invalid("environment_sample_out_of_order")
            self.last_sensor_key, self._last_measured = key, measured
            if direction is None:
                self.excursion = None
            elif (not old or old["direction"] != direction
                    or not 0 < measured-old["last"] <= ENVIRONMENT_MAX_GAP_SECONDS
                    or (previous and key[0] != previous[0])):
                self.excursion = {"direction": direction, "first": measured, "last": measured, "count": 1}
            else:
                old.update(last=measured, count=old["count"]+1)
        persistent = bool(not fallback and self.excursion and self.excursion["direction"] == direction
                          and self.excursion["count"] >= 3
                          and self.excursion["last"]-self.excursion["first"] >= environment_persistence_seconds
                          and 0 <= now-self.excursion["last"] <= FRESH_ENVIRONMENT_MAX_AGE_SECONDS)
        return {"temperature": temperature, "temperature_c": temperature, "humidity_pct": humidity,
                "fallback": fallback, "direction": direction, "persistent": persistent}, None


class CoverageEventTracker:
    """Process-memory event latch; configuration toggles cannot erase it.

    observe accepts aware ISO timestamps or epoch seconds, and returns False for
    duplicate/out-of-order frames. Recovery requires two ordered observations of
    the SAME reliable covered/empty state spanning at least 60 seconds.
    """

    def __init__(self):
        self.identity = None
        self.reset(None)

    def reset(self, identity):
        if identity is not None and identity == self.identity:
            return False
        self.identity = identity
        self.event_id = None
        self.active = False
        self.handled = False
        self.phase = "normal"
        self.started_at = None
        self.handled_at = None
        self.recovery = None
        self.last_observed_at = None
        self._last_observation_id = None
        self._observation_ids = {}
        return True

    def observe(self, state, captured_at, observation_id=None):
        observed = _timestamp(captured_at)
        if (observed is None or (self.last_observed_at is not None and observed <= self.last_observed_at)
                or (observation_id is not None and observation_id in self._observation_ids)):
            return False
        self.last_observed_at, self._last_observation_id = observed, observation_id
        if observation_id is not None:
            self._observation_ids[observation_id] = observed
            if len(self._observation_ids) > 4096:
                del self._observation_ids[next(iter(self._observation_ids))]
        if state in {"uncovered_candidate", "confirmed_uncovered"}:
            if not self.active:
                self.event_id = str(uuid.uuid4())
                self.started_at = observed
                self.active, self.handled, self.handled_at = True, False, None
            self.recovery = None
            self.phase = "handled" if self.handled else ("confirmed" if state == "confirmed_uncovered" else "candidate")
        elif self.active and state in {"covered", "empty"}:
            old = self.recovery
            if (not old or old["state"] != state
                    or observed-old["last"] > EVENT_RECOVERY_MAX_GAP_SECONDS):
                self.recovery = {"state": state, "first": observed, "last": observed, "count": 1}
            else:
                old.update(last=observed, count=old["count"]+1)
            self.phase = "recovering"
            if self.recovery["count"] >= 2 and observed-self.recovery["first"] >= EVENT_RECOVERY_SECONDS:
                self.active, self.handled, self.phase = False, False, "recovered"
        elif self.active:
            self.recovery = None
            self.phase = "handled" if self.handled else "awaiting_reliable_visual"
        return True

    def mark_handled(self, now=None):
        if not self.active:
            return False
        self.handled, self.phase = True, "handled"
        self.handled_at = _timestamp(now) if now is not None else self.last_observed_at
        return True

    def snapshot(self):
        return copy.deepcopy({"id": self.event_id, "identity": self.identity, "phase": self.phase,
                              "active": self.active, "handled": self.handled,
                              "started_at": self.started_at, "handled_at": self.handled_at,
                              "last_observed_at": self.last_observed_at, "recovery": self.recovery,
                              "recovery_required_seconds": EVENT_RECOVERY_SECONDS,
                              "recovery_max_gap_seconds": EVENT_RECOVERY_MAX_GAP_SECONDS,
                              "memory_scope": "server_process_same_device_boot"})
