# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Stable public value types returned by :mod:`skill_builder.api`."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from skill_builder.application.package_builder import SkillPackageBuildError
from skill_builder.domain.execution import (
    DeliveryDecision,
    ExecutionAction,
    ExecutionFailure,
    LifecycleCursor,
    SKILL_BUILDER_POLICY_VERSION,
    SkillBuilderExecution,
    SkillBuilderInput,
    SkillBuilderOptions,
    SkillBuilderPendingRequest,
    SkillBuilderState,
    SkillBuilderStatus,
    SkillBuilderTurnRequest,
)
from skill_builder.domain.conversation import (
    ConversationIntent,
    MutationPolicy,
    TurnResult,
    TurnStatus,
)


@dataclass(frozen=True, slots=True)
class SkillBuilderArchive:
    """One immutable export or publish archive."""

    content: bytes
    filename: str
    sha256: str


@dataclass(frozen=True, slots=True)
class PackageProjection:
    skill_name: str
    display_name: str
    description: str
    version: str
    tags: tuple[str, ...] = field(default_factory=tuple)


@dataclass(frozen=True, slots=True)
class PresentationProjection:
    """Complete Core-owned state presented by a host or standalone service."""

    workspace_status: str
    draft_status: str
    validation_status: str
    cursor: LifecycleCursor
    delivery_decision: DeliveryDecision
    publishable: bool
    last_error: str | None
    summary: str
    blockers: tuple[str, ...]
    available_actions: tuple[ExecutionAction, ...]
    package: PackageProjection
    acceptance: dict[str, Any] | None = None
    agent_self_check: dict[str, Any] | None = None
    artifact_files: tuple[str, ...] = field(default_factory=tuple)
    turn: TurnResult | None = None


__all__ = [
    "SKILL_BUILDER_POLICY_VERSION",
    "DeliveryDecision",
    "ConversationIntent",
    "ExecutionAction",
    "ExecutionFailure",
    "LifecycleCursor",
    "MutationPolicy",
    "PackageProjection",
    "PresentationProjection",
    "SkillBuilderArchive",
    "SkillBuilderExecution",
    "SkillBuilderInput",
    "SkillBuilderOptions",
    "SkillBuilderPendingRequest",
    "SkillBuilderState",
    "SkillBuilderStatus",
    "SkillBuilderTurnRequest",
    "TurnResult",
    "TurnStatus",
    "SkillPackageBuildError",
]
