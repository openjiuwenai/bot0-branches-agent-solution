"""Schema policies and pure validators for optimizer structured LLM output."""

from __future__ import annotations

from typing import Any

from evo_agent.llm.structured_output import StructuredOutputPolicy, ValidationResult

_SHARED_EDIT_KEYS = frozenset(
    {
        "reasoning",
        "edits",
        "op",
        "target",
        "content",
        "support_count",
    }
)
_MERGE_EDIT_KEYS = _SHARED_EDIT_KEYS | frozenset({"source_type", "source_ids"})

REFLECT_FAILURE_POLICY = StructuredOutputPolicy(
    schema_name="reflect_failure",
    allowed_comma_next_keys=frozenset(
        {
            "batch_size",
            "failure_summary",
            "failure_type",
            "count",
            "description",
            "patch",
            *_SHARED_EDIT_KEYS,
        }
    ),
)
REFLECT_SUCCESS_POLICY = StructuredOutputPolicy(
    schema_name="reflect_success",
    allowed_comma_next_keys=frozenset(
        {"batch_size", "success_patterns", "patch", *_SHARED_EDIT_KEYS}
    ),
)
MERGE_FAILURE_POLICY = StructuredOutputPolicy(
    schema_name="merge_failure",
    required_keys=frozenset({"edits"}),
    allowed_comma_next_keys=_MERGE_EDIT_KEYS,
)
MERGE_SUCCESS_POLICY = StructuredOutputPolicy(
    schema_name="merge_success",
    required_keys=frozenset({"edits"}),
    allowed_comma_next_keys=_MERGE_EDIT_KEYS,
)
MERGE_FINAL_POLICY = StructuredOutputPolicy(
    schema_name="merge_final",
    required_keys=frozenset({"edits"}),
    allowed_comma_next_keys=_MERGE_EDIT_KEYS,
)
RANKING_POLICY = StructuredOutputPolicy(
    schema_name="ranking",
    required_keys=frozenset({"selected_indices"}),
    allowed_comma_next_keys=frozenset({"reasoning", "selected_indices"}),
)
SLOW_UPDATE_POLICY = StructuredOutputPolicy(
    schema_name="slow_update",
    allowed_comma_next_keys=frozenset({"reasoning", "slow_update_content"}),
)
META_SKILL_POLICY = StructuredOutputPolicy(
    schema_name="meta_skill",
    allowed_comma_next_keys=frozenset({"reasoning", "meta_skill_content"}),
)


def validate_reflect_output(data: dict[str, Any]) -> ValidationResult:
    """Accept the canonical patch wrapper and the legacy bare-patch shape."""
    patch = data.get("patch", data)
    if not isinstance(patch, dict):
        return ValidationResult(False, "structure", "reflect patch must be an object")
    if not isinstance(patch.get("edits"), list):
        return ValidationResult(False, "structure", "reflect edits must be a list")
    return ValidationResult(True)


def validate_merge_output(data: dict[str, Any]) -> ValidationResult:
    if not isinstance(data.get("edits"), list):
        return ValidationResult(False, "structure", "merge edits must be a list")
    return ValidationResult(True)


def validate_ranking_output(data: dict[str, Any]) -> ValidationResult:
    if not isinstance(data.get("selected_indices"), list):
        return ValidationResult(False, "structure", "selected_indices must be a list")
    return ValidationResult(True)


def validate_slow_update_output(data: dict[str, Any]) -> ValidationResult:
    return _validate_optional_strings(data, ("reasoning", "slow_update_content"))


def validate_meta_skill_output(data: dict[str, Any]) -> ValidationResult:
    return _validate_optional_strings(data, ("reasoning", "meta_skill_content"))


def reflect_patch_data(data: dict[str, Any]) -> dict[str, Any]:
    """Project a validated reflect response onto its patch object."""
    patch = data.get("patch", data)
    return patch if isinstance(patch, dict) else {}


def valid_edit_items(edits: list[Any]) -> list[dict[str, Any]]:
    """Keep individually valid edit objects without rejecting the response container."""
    valid_ops = {"append", "insert_after", "replace", "delete"}
    return [item for item in edits if isinstance(item, dict) and item.get("op") in valid_ops]


def valid_selected_indices(values: list[Any], *, pool_size: int, budget: int) -> list[int]:
    """Filter invalid, bool, duplicate and out-of-range indices in model order."""
    selected: list[int] = []
    seen: set[int] = set()
    for value in values:
        if isinstance(value, bool) or not isinstance(value, int):
            continue
        if value < 0 or value >= pool_size or value in seen:
            continue
        selected.append(value)
        seen.add(value)
        if len(selected) >= budget:
            break
    return selected


def _validate_optional_strings(
    data: dict[str, Any],
    fields: tuple[str, ...],
) -> ValidationResult:
    for field in fields:
        if field in data and not isinstance(data[field], str):
            return ValidationResult(False, "field_type", f"{field} must be a string")
    return ValidationResult(True)


__all__ = [
    "MERGE_FAILURE_POLICY",
    "MERGE_FINAL_POLICY",
    "MERGE_SUCCESS_POLICY",
    "META_SKILL_POLICY",
    "RANKING_POLICY",
    "REFLECT_FAILURE_POLICY",
    "REFLECT_SUCCESS_POLICY",
    "SLOW_UPDATE_POLICY",
    "reflect_patch_data",
    "valid_edit_items",
    "valid_selected_indices",
    "validate_merge_output",
    "validate_meta_skill_output",
    "validate_ranking_output",
    "validate_reflect_output",
    "validate_slow_update_output",
]
