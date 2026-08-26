# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Bounded repair policy for one Skill Builder Agent turn."""

from __future__ import annotations

import os
from typing import Any


DEFAULT_MAX_REPAIR_ATTEMPTS = 1
HARD_MAX_REPAIR_ATTEMPTS = 1
DEFAULT_AUTHOR_SELF_CHECK_MAX_RUNS = 4
HARD_AUTHOR_SELF_CHECK_MAX_RUNS = 6
DEFAULT_REPAIR_RESERVE_TIMEOUT_SECONDS = 180
HARD_REPAIR_RESERVE_TIMEOUT_SECONDS = 600


def resolve_max_repair_attempts(value: Any = None) -> int:
    """Resolve the configured repair count with a non-bypassable hard cap."""

    raw = os.getenv("SKILL_BUILDER_MAX_REPAIR_ATTEMPTS") if value is None else value
    if raw is None or not str(raw).strip():
        return DEFAULT_MAX_REPAIR_ATTEMPTS
    try:
        parsed = int(str(raw).strip())
    except ValueError:
        return DEFAULT_MAX_REPAIR_ATTEMPTS
    return max(0, min(parsed, HARD_MAX_REPAIR_ATTEMPTS))


def resolve_progressive_repair_attempt_limit(value: Any = None) -> int:
    """Return the bounded allowance for monotonic mechanical Repair turns."""

    return resolve_max_repair_attempts(value)


def resolve_author_self_check_max_runs(value: Any = None) -> int:
    """Resolve Author's independent, bounded self-check execution budget."""

    raw = (
        os.getenv("SKILL_BUILDER_AUTHOR_SELF_CHECK_MAX_RUNS")
        if value is None
        else value
    )
    if raw is None or not str(raw).strip():
        return DEFAULT_AUTHOR_SELF_CHECK_MAX_RUNS
    try:
        parsed = int(str(raw).strip())
    except (TypeError, ValueError):
        return DEFAULT_AUTHOR_SELF_CHECK_MAX_RUNS
    return max(1, min(parsed, HARD_AUTHOR_SELF_CHECK_MAX_RUNS))


def resolve_repair_reserve_timeout_seconds(value: Any = None) -> int:
    """Return a bounded extra Author budget after a rejected submission.

    The reserve is activated only after ``finish_draft`` returns a repairable
    failure and the candidate remains uncommitted.  It prevents a healthy
    first preflight from consuming the entire worker deadline before the
    bounded correction turn, while preserving a hard upper limit.
    """

    raw = (
        os.getenv("SKILL_BUILDER_AGENT_REPAIR_RESERVE_TIMEOUT_SECONDS")
        if value is None
        else value
    )
    if raw is None or not str(raw).strip():
        return DEFAULT_REPAIR_RESERVE_TIMEOUT_SECONDS
    try:
        parsed = int(str(raw).strip())
    except (TypeError, ValueError):
        return DEFAULT_REPAIR_RESERVE_TIMEOUT_SECONDS
    return max(0, min(parsed, HARD_REPAIR_RESERVE_TIMEOUT_SECONDS))


def repair_reserve_is_active(
    *,
    task_mode: str,
    submission_attempt_count: int,
    offline_self_check_status: str,
    completion_present: bool,
) -> bool:
    """Return whether an Author is inside its one bounded correction window."""

    return bool(
        str(task_mode or "").strip().lower() in {"author", "repair"}
        and not completion_present
        and (
            int(submission_attempt_count) > 0
            or str(offline_self_check_status or "").strip().lower() == "fail"
        )
    )


__all__ = [
    "DEFAULT_AUTHOR_SELF_CHECK_MAX_RUNS",
    "DEFAULT_MAX_REPAIR_ATTEMPTS",
    "HARD_AUTHOR_SELF_CHECK_MAX_RUNS",
    "HARD_MAX_REPAIR_ATTEMPTS",
    "DEFAULT_REPAIR_RESERVE_TIMEOUT_SECONDS",
    "HARD_REPAIR_RESERVE_TIMEOUT_SECONDS",
    "resolve_max_repair_attempts",
    "resolve_progressive_repair_attempt_limit",
    "resolve_repair_reserve_timeout_seconds",
    "repair_reserve_is_active",
    "resolve_author_self_check_max_runs",
]
