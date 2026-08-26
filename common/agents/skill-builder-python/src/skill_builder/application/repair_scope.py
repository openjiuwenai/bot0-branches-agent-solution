# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Controller-owned mutation scope for one bounded Repair attempt."""

from __future__ import annotations

import json
from pathlib import Path, PurePosixPath
from typing import Any

from skill_builder.domain.candidate_contract import export_package_path_allowed


REPAIR_PLAN_PATH = "validation/repair_plan.json"
REPAIR_PLAN_SCHEMA_VERSION = "skill-builder-repair-handoff/v1"


def _normalized_target_path(value: Any) -> str | None:
    normalized = str(value or "").strip().replace("\\", "/")
    while normalized.startswith("./"):
        normalized = normalized[2:]
    if normalized.startswith("generated-skill/"):
        normalized = normalized.removeprefix("generated-skill/")
    candidate = PurePosixPath(normalized)
    if (
        not normalized
        or candidate.is_absolute()
        or ".." in candidate.parts
        or not export_package_path_allowed(normalized)
    ):
        return None
    return candidate.as_posix()


def normalize_repair_plan(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    if str(value.get("schemaVersion") or "") != REPAIR_PLAN_SCHEMA_VERSION:
        return None
    raw_paths = value.get("targetPaths")
    if not isinstance(raw_paths, list):
        return None
    target_paths = sorted(
        {
            path
            for item in raw_paths
            if (path := _normalized_target_path(item)) is not None
        }
    )
    return {
        **value,
        "schemaVersion": REPAIR_PLAN_SCHEMA_VERSION,
        "targetPaths": target_paths,
    }


def persist_repair_plan(root: Path, value: dict[str, Any]) -> dict[str, Any]:
    plan = normalize_repair_plan(value)
    if plan is None:
        raise ValueError("invalid repair plan")
    target = root / REPAIR_PLAN_PATH
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".json.tmp")
    temporary.write_text(
        json.dumps(plan, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(target)
    return plan


def load_repair_plan(root: Path) -> dict[str, Any] | None:
    try:
        value = json.loads((root / REPAIR_PLAN_PATH).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, TypeError, ValueError, json.JSONDecodeError):
        return None
    return normalize_repair_plan(value)


def repair_target_paths(root: Path) -> frozenset[str] | None:
    plan = load_repair_plan(root)
    if plan is None:
        return None
    return frozenset(str(path) for path in plan["targetPaths"])


__all__ = [
    "REPAIR_PLAN_PATH",
    "REPAIR_PLAN_SCHEMA_VERSION",
    "load_repair_plan",
    "normalize_repair_plan",
    "persist_repair_plan",
    "repair_target_paths",
]
