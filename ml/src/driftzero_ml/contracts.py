"""Stdlib validation of SensorFrame and NavigationState JSON instances."""

from __future__ import annotations

import math
import re
from typing import Any, Mapping

CLOCK_DOMAINS = frozenset(
    {"android_elapsed_realtime", "external_monotonic", "dataset_declared"}
)
SENSOR_KINDS = frozenset(
    {"accelerometer", "gyroscope", "magnetometer", "gnss_fix", "gnss_status", "raw_gnss"}
)
VECTOR_UNITS = {
    "accelerometer": "m/s^2",
    "gyroscope": "rad/s",
    "magnetometer": "uT",
}
VECTOR_FRAMES = frozenset({"android_device", "vehicle_flu", "unspecified"})
NAV_MODES = frozenset(
    {"GNSS_FUSED", "GNSS_DEGRADED", "DEAD_RECKONING", "REACQUIRING", "LOW_CONFIDENCE"}
)
MAP_STATUSES = frozenset({"MATCHED", "AMBIGUOUS", "UNMATCHED", "NO_MAP"})
CONSTELLATIONS = frozenset(
    {"GPS", "SBAS", "GLONASS", "QZSS", "BEIDOU", "GALILEO", "IRNSS", "UNKNOWN"}
)
TWO_PI = 2.0 * math.pi
SHA256_HEX = re.compile(r"^[a-fA-F0-9]{64}$")

_SENSOR_REQUIRED = (
    "schema_version",
    "source_id",
    "sequence",
    "timestamp_ns",
    "clock_domain",
    "kind",
    "quality",
    "payload",
)
_NAV_REQUIRED = (
    "schema_version",
    "sequence",
    "timestamp_ns",
    "mode",
    "position",
    "motion",
    "uncertainty",
    "gnss_health",
    "map_match",
    "health",
    "provenance",
)


class ContractError(ValueError):
    """A JSON instance does not match the DriftZero contract."""


def _require_keys(obj: Mapping[str, Any], keys: tuple[str, ...], label: str) -> None:
    missing = [key for key in keys if key not in obj]
    if missing:
        raise ContractError(f"{label} missing {', '.join(missing)}")


def _finite_number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ContractError(f"{label} must be a number")
    number = float(value)
    if not math.isfinite(number):
        raise ContractError(f"{label} must be finite")
    return number


def _nonneg_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ContractError(f"{label} must be a non-negative integer")
    return value


def _heading_rad(value: Any, label: str) -> float:
    number = _finite_number(value, label)
    if not 0.0 <= number < TWO_PI:
        raise ContractError(f"{label} must be in [0, 2pi)")
    return number


def validate_sensor_frame(obj: Mapping[str, Any]) -> None:
    """Reject a SensorFrame instance that violates contracts/sensor_frame.schema.json."""

    _require_keys(obj, _SENSOR_REQUIRED, "SensorFrame")
    extra = set(obj) - set(_SENSOR_REQUIRED) - {"metadata"}
    if extra:
        raise ContractError(f"SensorFrame unknown fields: {', '.join(sorted(extra))}")
    if obj["schema_version"] != "1.0.0":
        raise ContractError("schema_version must be 1.0.0")
    source_id = obj["source_id"]
    if not isinstance(source_id, str) or not 1 <= len(source_id) <= 128:
        raise ContractError("source_id must be a string of length 1..128")
    _nonneg_int(obj["sequence"], "sequence")
    _nonneg_int(obj["timestamp_ns"], "timestamp_ns")
    if obj["clock_domain"] not in CLOCK_DOMAINS:
        raise ContractError(f"unknown clock_domain: {obj['clock_domain']}")
    kind = obj["kind"]
    if kind not in SENSOR_KINDS:
        raise ContractError(f"unknown kind: {kind}")
    _validate_quality(obj["quality"])
    payload = obj["payload"]
    if not isinstance(payload, Mapping):
        raise ContractError("payload must be an object")
    if kind in VECTOR_UNITS:
        _validate_vector3(payload, VECTOR_UNITS[kind])
    elif kind == "gnss_fix":
        _validate_gnss_fix(payload)
    elif kind == "gnss_status":
        _validate_gnss_status(payload)
    else:
        _validate_raw_gnss(payload)
    metadata = obj.get("metadata")
    if metadata is not None:
        _validate_metadata(metadata)


def validate_navigation_state(obj: Mapping[str, Any]) -> None:
    """Reject a NavigationState instance that violates the contract schema."""

    _require_keys(obj, _NAV_REQUIRED, "NavigationState")
    extra = set(obj) - set(_NAV_REQUIRED)
    if extra:
        raise ContractError(f"NavigationState unknown fields: {', '.join(sorted(extra))}")
    if obj["schema_version"] != "1.0.0":
        raise ContractError("schema_version must be 1.0.0")
    _nonneg_int(obj["sequence"], "sequence")
    _nonneg_int(obj["timestamp_ns"], "timestamp_ns")
    if obj["mode"] not in NAV_MODES:
        raise ContractError(f"unknown mode: {obj['mode']}")
    _validate_position(obj["position"])
    _validate_motion(obj["motion"])
    _validate_uncertainty(obj["uncertainty"])
    _validate_gnss_health(obj["gnss_health"])
    _validate_map_match(obj["map_match"])
    _validate_health(obj["health"])
    _validate_provenance(obj["provenance"])


def _validate_quality(quality: Any) -> None:
    if not isinstance(quality, Mapping):
        raise ContractError("quality must be an object")
    _require_keys(quality, ("available", "accuracy_code", "flags"), "quality")
    if extra := set(quality) - {"available", "accuracy_code", "flags"}:
        raise ContractError(f"quality unknown fields: {', '.join(sorted(extra))}")
    if not isinstance(quality["available"], bool):
        raise ContractError("quality.available must be a boolean")
    code = quality["accuracy_code"]
    if isinstance(code, bool) or not isinstance(code, int) or not -1 <= code <= 3:
        raise ContractError("accuracy_code must be an integer in [-1, 3]")
    flags = quality["flags"]
    if not isinstance(flags, list) or len(flags) != len(set(flags)):
        raise ContractError("quality.flags must be a unique string list")
    if any(not isinstance(flag, str) for flag in flags):
        raise ContractError("quality.flags must contain strings")


def _validate_vector3(payload: Mapping[str, Any], unit: str) -> None:
    _require_keys(payload, ("x", "y", "z", "unit", "frame"), "vector payload")
    allowed = {"x", "y", "z", "unit", "frame", "bias_x", "bias_y", "bias_z"}
    if extra := set(payload) - allowed:
        raise ContractError(f"vector payload unknown fields: {', '.join(sorted(extra))}")
    for axis in ("x", "y", "z"):
        _finite_number(payload[axis], axis)
    for axis in ("bias_x", "bias_y", "bias_z"):
        if axis in payload:
            _finite_number(payload[axis], axis)
    if payload["unit"] != unit:
        raise ContractError(f"unit must be {unit}")
    if payload["frame"] not in VECTOR_FRAMES:
        raise ContractError(f"unknown frame: {payload['frame']}")


def _validate_gnss_fix(payload: Mapping[str, Any]) -> None:
    _require_keys(
        payload,
        ("latitude_deg", "longitude_deg", "horizontal_accuracy_m", "provider_time_ms"),
        "gnss_fix",
    )
    lat = _finite_number(payload["latitude_deg"], "latitude_deg")
    lon = _finite_number(payload["longitude_deg"], "longitude_deg")
    if not -90.0 <= lat <= 90.0:
        raise ContractError("latitude_deg out of range")
    if not -180.0 <= lon <= 180.0:
        raise ContractError("longitude_deg out of range")
    acc = _finite_number(payload["horizontal_accuracy_m"], "horizontal_accuracy_m")
    if acc < 0:
        raise ContractError("horizontal_accuracy_m must be >= 0")
    _nonneg_int(payload["provider_time_ms"], "provider_time_ms")


def _validate_gnss_status(payload: Mapping[str, Any]) -> None:
    _require_keys(payload, ("satellites_visible", "satellites_used"), "gnss_status")
    _nonneg_int(payload["satellites_visible"], "satellites_visible")
    _nonneg_int(payload["satellites_used"], "satellites_used")
    constellations = payload.get("constellations")
    if constellations is not None:
        if not isinstance(constellations, list) or len(constellations) != len(set(constellations)):
            raise ContractError("constellations must be a unique list")
        unknown = set(constellations) - CONSTELLATIONS
        if unknown:
            raise ContractError(f"unknown constellations: {', '.join(sorted(unknown))}")


def _validate_raw_gnss(payload: Mapping[str, Any]) -> None:
    _require_keys(payload, ("constellation", "svid"), "raw_gnss")
    if not isinstance(payload["constellation"], str) or not payload["constellation"]:
        raise ContractError("constellation must be a non-empty string")
    svid = payload["svid"]
    if isinstance(svid, bool) or not isinstance(svid, int) or svid < 1:
        raise ContractError("svid must be an integer >= 1")


def _validate_metadata(metadata: Any) -> None:
    if not isinstance(metadata, Mapping):
        raise ContractError("metadata must be an object")
    allowed = {"sensor_name", "vendor", "frame", "temperature_c"}
    if extra := set(metadata) - allowed:
        raise ContractError(f"metadata unknown fields: {', '.join(sorted(extra))}")
    frame = metadata.get("frame")
    if frame is not None and frame not in {"android_device", "vehicle_flu", "antenna", "unspecified"}:
        raise ContractError(f"unknown metadata.frame: {frame}")


def _validate_position(position: Any) -> None:
    if not isinstance(position, Mapping):
        raise ContractError("position must be an object")
    _require_keys(position, ("latitude_deg", "longitude_deg"), "position")
    lat = _finite_number(position["latitude_deg"], "latitude_deg")
    lon = _finite_number(position["longitude_deg"], "longitude_deg")
    if not -90.0 <= lat <= 90.0:
        raise ContractError("latitude_deg out of range")
    if not -180.0 <= lon <= 180.0:
        raise ContractError("longitude_deg out of range")


def _validate_motion(motion: Any) -> None:
    if not isinstance(motion, Mapping):
        raise ContractError("motion must be an object")
    _require_keys(motion, ("speed_mps", "heading_rad"), "motion")
    speed = _finite_number(motion["speed_mps"], "speed_mps")
    if speed < 0:
        raise ContractError("speed_mps must be >= 0")
    _heading_rad(motion["heading_rad"], "heading_rad")


def _validate_uncertainty(uncertainty: Any) -> None:
    if not isinstance(uncertainty, Mapping):
        raise ContractError("uncertainty must be an object")
    _require_keys(uncertainty, ("horizontal_95_m", "heading_95_rad", "is_calibrated"), "uncertainty")
    if _finite_number(uncertainty["horizontal_95_m"], "horizontal_95_m") < 0:
        raise ContractError("horizontal_95_m must be >= 0")
    if _finite_number(uncertainty["heading_95_rad"], "heading_95_rad") < 0:
        raise ContractError("heading_95_rad must be >= 0")
    if not isinstance(uncertainty["is_calibrated"], bool):
        raise ContractError("is_calibrated must be a boolean")


def _validate_gnss_health(health: Any) -> None:
    if not isinstance(health, Mapping):
        raise ContractError("gnss_health must be an object")
    _require_keys(health, ("score", "last_trusted_fix_age_s", "risk_flags"), "gnss_health")
    score = _finite_number(health["score"], "score")
    if not 0.0 <= score <= 1.0:
        raise ContractError("gnss_health.score must be in [0, 1]")
    if _finite_number(health["last_trusted_fix_age_s"], "last_trusted_fix_age_s") < 0:
        raise ContractError("last_trusted_fix_age_s must be >= 0")
    flags = health["risk_flags"]
    if not isinstance(flags, list) or len(flags) != len(set(flags)):
        raise ContractError("risk_flags must be a unique string list")


def _validate_map_match(match: Any) -> None:
    if not isinstance(match, Mapping):
        raise ContractError("map_match must be an object")
    _require_keys(match, ("status", "confidence"), "map_match")
    if match["status"] not in MAP_STATUSES:
        raise ContractError(f"unknown map_match.status: {match['status']}")
    confidence = _finite_number(match["confidence"], "confidence")
    if not 0.0 <= confidence <= 1.0:
        raise ContractError("map_match.confidence must be in [0, 1]")


def _validate_health(health: Any) -> None:
    if not isinstance(health, Mapping):
        raise ContractError("health must be an object")
    keys = ("sensor_ok", "model_ok", "filter_ok", "map_ok", "flags")
    _require_keys(health, keys, "health")
    for key in keys[:-1]:
        if not isinstance(health[key], bool):
            raise ContractError(f"health.{key} must be a boolean")
    flags = health["flags"]
    if not isinstance(flags, list) or len(flags) != len(set(flags)):
        raise ContractError("health.flags must be a unique string list")


def _validate_provenance(provenance: Any) -> None:
    if not isinstance(provenance, Mapping):
        raise ContractError("provenance must be an object")
    _require_keys(provenance, ("core_version", "config_hash"), "provenance")
    if not isinstance(provenance["core_version"], str) or not provenance["core_version"]:
        raise ContractError("core_version must be a non-empty string")
    digest = provenance["config_hash"]
    if not isinstance(digest, str) or not SHA256_HEX.fullmatch(digest):
        raise ContractError("config_hash must be 64 hex characters")
