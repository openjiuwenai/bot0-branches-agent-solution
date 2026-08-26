# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Rollout policy for heuristic Skill Builder acceptance gates."""

from __future__ import annotations

import os
from typing import Any


DEFAULT_HEURISTIC_GATE_MODE = "shadow"
DEFAULT_DOCUMENTATION_GATE_MODE = "shadow"
DEFAULT_CAPABILITY_GATE_MODE = "shadow"
DEFAULT_OFFLINE_PROTOCOL_GATE_MODE = "shadow"
HEURISTIC_GATE_MODES = frozenset({"shadow", "enforce"})


def _resolve_gate_mode(*, value: Any, env_name: str, default: str) -> str:
    if value is not None:
        raw = value
    else:
        raw = os.getenv(env_name)
        if raw in {None, ""}:
            raw = os.getenv("SKILL_BUILDER_HEURISTIC_GATE_MODE")
    normalized = str(raw or default).strip().lower()
    return normalized if normalized in HEURISTIC_GATE_MODES else default


def resolve_heuristic_gate_mode(value: Any = None) -> str:
    """Resolve the legacy shared rollout mode."""

    return _resolve_gate_mode(
        value=value,
        env_name="SKILL_BUILDER_HEURISTIC_GATE_MODE",
        default=DEFAULT_HEURISTIC_GATE_MODE,
    )


def resolve_documentation_gate_mode(value: Any = None) -> str:
    """Keep natural-language decision suspicion in shadow by default."""

    return _resolve_gate_mode(
        value=value,
        env_name="SKILL_BUILDER_DOCUMENTATION_GATE_MODE",
        default=DEFAULT_DOCUMENTATION_GATE_MODE,
    )


def resolve_capability_gate_mode(value: Any = None) -> str:
    """Keep prose capability inference diagnostic; typed requirements stay hard."""

    return _resolve_gate_mode(
        value=value,
        env_name="SKILL_BUILDER_CAPABILITY_GATE_MODE",
        default=DEFAULT_CAPABILITY_GATE_MODE,
    )


def resolve_offline_protocol_gate_mode(value: Any = None) -> str:
    """Resolve the rollout mode for generated self-check protocol evidence.

    Protocol coverage and host-dependent replay are useful diagnostics, but are
    not a reliable production capability signal when a generated package uses
    optional browser/network dependencies.  Keep this gate in shadow mode by
    default and make enforcement an explicit deployment choice.
    """

    return _resolve_gate_mode(
        value=value,
        env_name="SKILL_BUILDER_OFFLINE_PROTOCOL_GATE_MODE",
        default=DEFAULT_OFFLINE_PROTOCOL_GATE_MODE,
    )


__all__ = [
    "DEFAULT_CAPABILITY_GATE_MODE",
    "DEFAULT_DOCUMENTATION_GATE_MODE",
    "DEFAULT_HEURISTIC_GATE_MODE",
    "DEFAULT_OFFLINE_PROTOCOL_GATE_MODE",
    "HEURISTIC_GATE_MODES",
    "resolve_capability_gate_mode",
    "resolve_documentation_gate_mode",
    "resolve_heuristic_gate_mode",
    "resolve_offline_protocol_gate_mode",
]
