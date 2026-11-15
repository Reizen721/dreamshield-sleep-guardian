"""Shared coverage interpretation and numeric validation for the AC controller."""
from __future__ import annotations
import math
from datetime import datetime

def timestamp(value):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.timestamp() if parsed.tzinfo else None
    except (AttributeError, TypeError, ValueError, OverflowError):
        return None


def number(value, low=0, high=1):
    return type(value) in (int, float) and math.isfinite(value) and low <= value <= high


def visual_state(assessment):
    """Current coverage is useful even when no NEW displacement was observed."""
    after = assessment.get("after_frame") or {}
    before = assessment.get("before_frame") or {}
    components = assessment.get("confidence_components") or {}
    score = min((components.get(key, 0) if number(components.get(key)) else 0)
                for key in ("after_image_quality", "assessment_clarity"))
    result = {"state": "uncertain", "confirmation_required": False, "state_evidence_frame": "after",
              "state_evidence_score": score, "reason": "quality_or_scene_uncertain",
              "meaning": "规则证据分，不是实测准确率或踢腿概率"}
    if after.get("image_usable") is not True or score < 0.70:
        return result
    scene_comparable = (assessment.get("frames_comparable") is True
                  and assessment.get("scene_change") in {"stable", "minor"}
                  and number(components.get("scene_comparability"), .70, 1)
                  and number(components.get("before_image_quality"), .65, 1)
                  and before.get("image_usable") is True)
    comparable = (scene_comparable and number(components.get("person_consistency"), .65, 1)
                  and before.get("person_state") == "present")
    exposure = after.get("major_body_exposure_ratio")
    if (scene_comparable and after.get("person_state") == "empty" and after.get("coverage_state") == "empty"):
        result.update(state="empty", reason="bed_empty")
    elif comparable and after.get("person_state") == "present" and number(exposure):
        if after.get("coverage_state") == "covered" and exposure < .25:
            result.update(state="covered", reason="current_covered")
        elif after.get("coverage_state") in {"partially_uncovered", "uncovered"} and exposure >= .25:
            result.update(state="uncovered_candidate", confirmation_required=True,
                          reason="current_uncovered_requires_persistence")
    if result["state"] == "uncertain":
        return result
    confirmation = assessment.get("confirmation_frame")
    timing = assessment.get("capture_timing") or {}
    if confirmation is None:
        return result
    was_uncovered = result["state"] == "uncovered_candidate"
    unreliable = {**result, "state": "uncovered_candidate" if was_uncovered else "uncertain",
                  "confirmation_required": was_uncovered, "reason": "confirmation_unreliable",
                  "state_evidence_frame": "confirmation"}
    if (timing.get("confirmation_timing_status") != "within_window"
            or timing.get("frame_order_status") != "valid"
            or confirmation.get("image_usable") is not True
            or confirmation.get("scene_verified") is not True
            or not number(confirmation.get("confidence"), .70, 1)):
        return unreliable
    # A provided newer frame always determines current occupancy/coverage. Never
    # attach its fresh timestamp to the old AFTER picture's covered/empty state.
    result = {**result, "state_evidence_frame": "confirmation",
              "state_evidence_score": min(score, confirmation["confidence"])}
    if confirmation.get("person_state") == "empty" and confirmation.get("coverage_state") == "empty":
        return {**result, "state": "empty", "confirmation_required": False, "reason": "bed_became_empty"}
    exposure = confirmation.get("major_body_exposure_ratio")
    if confirmation.get("person_state") != "present" or not number(exposure):
        return unreliable
    coverage = confirmation.get("coverage_state")
    if coverage == "covered" and exposure < .25:
        return {**result, "state": "covered", "confirmation_required": False, "reason": "coverage_recovered"}
    if coverage in {"partially_uncovered", "uncovered"} and exposure >= .25:
        if was_uncovered:
            return {**result, "state": "confirmed_uncovered", "confirmation_required": False,
                    "reason": "uncovered_persisted_20_to_60_seconds"}
        return {**result, "state": "uncovered_candidate", "confirmation_required": True,
                "reason": "new_uncovered_in_confirmation_requires_persistence"}
    return unreliable

