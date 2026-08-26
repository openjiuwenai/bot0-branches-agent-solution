# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Typed semantic effects for HITL decisions."""

from __future__ import annotations

from typing import Any

from .decision_registry import (
    canonical_decision_value,
    decision_value_is_valid,
    normalize_decision_concept,
    project_decision_value,
)


def normalize_semantic_effects(value: Any) -> tuple[dict[str, str], list[str]]:
    """Compile one option's multi-concept effects through the platform registry."""

    if value in (None, {}):
        return {}, []
    if not isinstance(value, dict):
        return {}, ["semanticEffects must be an object"]
    effects: dict[str, str] = {}
    issues: list[str] = []
    for raw_concept, raw_value in value.items():
        declared = str(raw_concept or "").strip().lower()
        concept = normalize_decision_concept(declared)
        if concept is None and declared.startswith("business:") and declared.removeprefix("business:"):
            concept = declared
        if concept is None:
            issues.append(f"semanticEffects uses unknown concept {raw_concept}")
            continue
        canonical = canonical_decision_value(concept, raw_value)
        if canonical is None:
            if concept.startswith("business:") and isinstance(raw_value, (str, bool, int, float)):
                canonical = (
                    str(raw_value).strip().lower()
                    if isinstance(raw_value, bool)
                    else str(raw_value).strip()
                )
            elif decision_value_is_valid(concept, concept, raw_value):
                canonical = str(raw_value).strip()
        if not canonical:
            issues.append(f"semanticEffects {concept} uses unregistered value {raw_value}")
            continue
        projection = project_decision_value(concept, concept, canonical) or {concept: canonical}
        for projected_concept, projected_value in projection.items():
            previous = effects.get(projected_concept)
            if previous is not None and previous != projected_value:
                issues.append(
                    f"semanticEffects projects conflicting values for {projected_concept}: "
                    f"{previous} and {projected_value}"
                )
                continue
            effects[projected_concept] = projected_value
    return effects, list(dict.fromkeys(issues))


__all__ = ["normalize_semantic_effects"]
