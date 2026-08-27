# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Durable diagnostics for one atomic candidate submission."""

from __future__ import annotations

from typing import Any


CANDIDATE_SUBMISSION_DRAFT_PATH = "validation/diagnostics/candidate_submission.json"


def compact_submission_failure(value: Any) -> dict[str, Any]:
    """Keep only the diagnostic fields required to explain one failed submit."""

    failure = value if isinstance(value, dict) else {}
    compact = {}
    for key in ("stage", "error", "message", "issues", "files", "missing"):
        if failure.get(key) not in (None, "", []):
            compact[key] = failure.get(key)
    validation = failure.get("validation")
    if isinstance(validation, dict):
        errors = []
        for item in validation.get("findings") or []:
            if not isinstance(item, dict) or item.get("severity") != "fail":
                continue
            projected = {}
            for key in ("id", "path", "message"):
                if item.get(key) not in (None, ""):
                    projected[key] = item.get(key)
            errors.append(projected)
        compact["errors"] = errors[:20]
    return compact


__all__ = ["CANDIDATE_SUBMISSION_DRAFT_PATH", "compact_submission_failure"]
