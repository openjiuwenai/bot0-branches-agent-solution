# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Public lifecycle contracts for the standalone Skill Builder package."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path
from typing import Any, Mapping

from .policy import skill_builder_policy_version
from .conversation import TurnResult

SKILL_BUILDER_POLICY_VERSION = skill_builder_policy_version()


class SkillBuilderStatus(StrEnum):
    QUEUED = "queued"
    RUNNING = "running"
    WAITING_FOR_USER = "waiting_for_user"
    DRAFT_READY = "draft_ready"
    NEEDS_REVIEW = "needs_review"
    READY = "ready"
    FAILED = "failed"


class LifecycleCursor(StrEnum):
    """The only persisted lifecycle position for one execution."""

    NEW = "new"
    SCENARIO = "scenario"
    WAITING_FOR_USER = "waiting_for_user"
    AUTHORING = "authoring"
    CANDIDATE_COMMITTED = "candidate_committed"
    DRAFT_READY = "draft_ready"
    VALIDATING = "validating"
    REPAIRING = "repairing"
    READY = "ready"
    NEEDS_REVIEW = "needs_review"
    FAILED = "failed"


class DeliveryDecision(StrEnum):
    DRAFT_READY = "draft_ready"
    READY = "ready"
    NEEDS_REVIEW = "needs_review"
    BLOCKED = "blocked"
    FAILED = "failed"


class ExecutionAction(StrEnum):
    RESUME = "resume"
    VALIDATE = "validate"
    REPAIR = "repair"
    RETRY = "retry"
    INSPECT = "inspect"
    EDIT = "edit"
    EXPORT = "export"
    PUBLISH = "publish"


@dataclass(frozen=True, slots=True)
class SkillBuilderTurnRequest:
    message: str
    requested_action: str = "auto"


@dataclass(frozen=True, slots=True)
class ExecutionFailure:
    """Stable failure value; hosts must not classify exception strings."""

    code: str
    category: str
    retryable: bool
    repairable: bool
    user_message: str
    developer_message: str
    details: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "code": self.code,
            "category": self.category,
            "retryable": self.retryable,
            "repairable": self.repairable,
            "user_message": self.user_message,
            "developer_message": self.developer_message,
            "details": dict(self.details),
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> ExecutionFailure:
        details = value.get("details")
        return cls(
            code=str(value.get("code") or "skill_builder_failed"),
            category=str(value.get("category") or "platform_runtime"),
            retryable=bool(value.get("retryable", False)),
            repairable=bool(value.get("repairable", False)),
            user_message=str(value.get("user_message") or "Skill 抽取未完成。"),
            developer_message=str(
                value.get("developer_message")
                or value.get("user_message")
                or "Skill Builder execution failed"
            ),
            details=dict(details) if isinstance(details, Mapping) else {},
        )


_ACTIVE_CURSORS = frozenset(
    {
        LifecycleCursor.SCENARIO,
        LifecycleCursor.AUTHORING,
        LifecycleCursor.CANDIDATE_COMMITTED,
        LifecycleCursor.VALIDATING,
        LifecycleCursor.REPAIRING,
    }
)


def lifecycle_status(cursor: LifecycleCursor) -> SkillBuilderStatus:
    if cursor == LifecycleCursor.NEW:
        return SkillBuilderStatus.QUEUED
    if cursor in _ACTIVE_CURSORS:
        return SkillBuilderStatus.RUNNING
    if cursor == LifecycleCursor.WAITING_FOR_USER:
        return SkillBuilderStatus.WAITING_FOR_USER
    if cursor == LifecycleCursor.DRAFT_READY:
        return SkillBuilderStatus.DRAFT_READY
    if cursor == LifecycleCursor.READY:
        return SkillBuilderStatus.READY
    if cursor == LifecycleCursor.NEEDS_REVIEW:
        return SkillBuilderStatus.NEEDS_REVIEW
    return SkillBuilderStatus.FAILED


def cursor_for_phase(phase: str) -> LifecycleCursor:
    normalized = str(phase or "").strip().lower()
    if normalized == "scenario":
        return LifecycleCursor.SCENARIO
    if normalized in {"initial", "workflow"}:
        # The default attempt starts with the structured Scenario handoff;
        # Authoring is entered only after that handoff (and any one HITL).
        return LifecycleCursor.SCENARIO
    if normalized == "repair":
        return LifecycleCursor.REPAIRING
    if normalized in {"validate", "validation"}:
        return LifecycleCursor.VALIDATING
    return LifecycleCursor.AUTHORING


@dataclass(frozen=True, slots=True)
class SkillBuilderInput:
    """Host-neutral input required to generate one Skill workspace."""

    root: Path
    workspace_id: str
    skill_name: str
    display_name: str
    description: str
    version: str
    user_message: str
    materials_markdown: str
    tags: tuple[str, ...] = field(default_factory=tuple)

    def to_dict(self) -> dict[str, Any]:
        return {
            "root": str(self.root),
            "workspace_id": self.workspace_id,
            "skill_name": self.skill_name,
            "display_name": self.display_name,
            "description": self.description,
            "version": self.version,
            "user_message": self.user_message,
            "materials_markdown": self.materials_markdown,
            "tags": list(self.tags),
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> SkillBuilderInput:
        raw_tags = value.get("tags")
        tags = raw_tags if isinstance(raw_tags, (list, tuple)) else []
        return cls(
            root=Path(str(value.get("root") or ".")).resolve(),
            workspace_id=str(value.get("workspace_id") or "").strip(),
            skill_name=str(value.get("skill_name") or "").strip(),
            display_name=str(value.get("display_name") or "").strip(),
            description=str(value.get("description") or "").strip(),
            version=str(value.get("version") or "0.1.0").strip() or "0.1.0",
            user_message=str(value.get("user_message") or ""),
            materials_markdown=str(value.get("materials_markdown") or ""),
            tags=tuple(str(item).strip() for item in tags if str(item).strip()),
        )


@dataclass(frozen=True, slots=True)
class SkillBuilderOptions:
    """Execution policy kept separate from business input."""

    run_phase: str = "initial"
    policy_version: str = SKILL_BUILDER_POLICY_VERSION
    run_id: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "run_phase": self.run_phase,
            "policy_version": self.policy_version,
            "run_id": self.run_id,
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> SkillBuilderOptions:
        return cls(
            run_phase=str(value.get("run_phase") or "initial").strip() or "initial",
            policy_version=(
                str(value.get("policy_version") or SKILL_BUILDER_POLICY_VERSION).strip()
                or SKILL_BUILDER_POLICY_VERSION
            ),
            run_id=(str(value.get("run_id")) if value.get("run_id") is not None else None),
        )


@dataclass(frozen=True, slots=True)
class SkillBuilderPendingRequest:
    request: dict[str, Any]
    resume_token: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "request": dict(self.request),
            "resume_token": self.resume_token,
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> SkillBuilderPendingRequest:
        request = value.get("request")
        return cls(
            request=dict(request) if isinstance(request, Mapping) else {},
            resume_token=str(value.get("resume_token") or ""),
        )


@dataclass(slots=True)
class SkillBuilderState:
    """Serializable lifecycle state; storage remains a host responsibility."""

    input: SkillBuilderInput
    options: SkillBuilderOptions = field(default_factory=SkillBuilderOptions)
    cursor: LifecycleCursor = LifecycleCursor.NEW
    pending_request: SkillBuilderPendingRequest | None = None
    hitl_confirmations: tuple[dict[str, Any], ...] = field(default_factory=tuple)
    validation_result: dict[str, Any] | None = None
    artifact_sha256: str | None = None
    policy_version: str = SKILL_BUILDER_POLICY_VERSION
    failure: ExecutionFailure | None = None

    @property
    def workspace_id(self) -> str:
        return self.input.workspace_id

    @property
    def status(self) -> SkillBuilderStatus:
        return lifecycle_status(self.cursor)

    @status.setter
    def status(self, value: SkillBuilderStatus) -> None:
        status = SkillBuilderStatus(value)
        if status == SkillBuilderStatus.QUEUED:
            self.cursor = LifecycleCursor.NEW
        elif status == SkillBuilderStatus.RUNNING:
            if self.cursor not in _ACTIVE_CURSORS:
                self.cursor = cursor_for_phase(self.options.run_phase)
        elif status == SkillBuilderStatus.WAITING_FOR_USER:
            self.cursor = LifecycleCursor.WAITING_FOR_USER
        elif status == SkillBuilderStatus.DRAFT_READY:
            self.cursor = LifecycleCursor.DRAFT_READY
        elif status == SkillBuilderStatus.READY:
            self.cursor = LifecycleCursor.READY
        elif status == SkillBuilderStatus.NEEDS_REVIEW:
            self.cursor = LifecycleCursor.NEEDS_REVIEW
        else:
            self.cursor = LifecycleCursor.FAILED

    @property
    def last_error(self) -> str | None:
        return self.failure.developer_message if self.failure is not None else None

    @last_error.setter
    def last_error(self, value: str | None) -> None:
        self.failure = (
            ExecutionFailure(
                code="skill_builder_failed",
                category="platform_runtime",
                retryable=False,
                repairable=False,
                user_message=str(value),
                developer_message=str(value),
            )
            if value
            else None
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": "skill-builder-state/v7",
            "input": self.input.to_dict(),
            "options": self.options.to_dict(),
            "cursor": self.cursor.value,
            "pending_request": self.pending_request.to_dict() if self.pending_request else None,
            "hitl_confirmations": [dict(item) for item in self.hitl_confirmations],
            "validation_result": self.validation_result,
            "artifact_sha256": self.artifact_sha256,
            "policy_version": self.policy_version,
            "failure": self.failure.to_dict() if self.failure else None,
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> SkillBuilderState:
        raw_input = value.get("input")
        if not isinstance(raw_input, Mapping):
            raise ValueError("Skill Builder state is missing input")
        raw_pending = value.get("pending_request")
        raw_options = value.get("options")
        policy_version = str(value.get("policy_version") or SKILL_BUILDER_POLICY_VERSION)
        options = (
            SkillBuilderOptions.from_dict(raw_options)
            if isinstance(raw_options, Mapping)
            else SkillBuilderOptions(policy_version=policy_version)
        )
        raw_confirmations = value.get("hitl_confirmations")
        confirmations = raw_confirmations if isinstance(raw_confirmations, (list, tuple)) else []
        schema_version = str(value.get("schema_version") or "")
        if schema_version != "skill-builder-state/v7":
            raise ValueError(
                "unsupported Skill Builder state schema; rebuild the pre-release workspace"
            )
        try:
            cursor = LifecycleCursor(str(value.get("cursor") or LifecycleCursor.NEW.value))
        except ValueError as exc:
            raise ValueError("Skill Builder state has an invalid lifecycle cursor") from exc
        raw_failure = value.get("failure")

        return cls(
            input=SkillBuilderInput.from_dict(raw_input),
            options=options,
            cursor=cursor,
            pending_request=(
                SkillBuilderPendingRequest.from_dict(raw_pending)
                if isinstance(raw_pending, Mapping)
                else None
            ),
            hitl_confirmations=tuple(
                dict(item) for item in confirmations if isinstance(item, Mapping)
            ),
            validation_result=(
                dict(value.get("validation_result"))
                if isinstance(value.get("validation_result"), Mapping)
                else None
            ),
            artifact_sha256=(
                str(value.get("artifact_sha256"))
                if value.get("artifact_sha256") is not None
                else None
            ),
            policy_version=policy_version,
            failure=(
                ExecutionFailure.from_dict(raw_failure)
                if isinstance(raw_failure, Mapping)
                else None
            ),
        )


@dataclass(frozen=True, slots=True)
class SkillBuilderExecution:
    """Stable result returned to a host, CLI, or another Agent application."""

    cursor: LifecycleCursor
    input: SkillBuilderInput
    policy_version: str
    options: SkillBuilderOptions = field(default_factory=SkillBuilderOptions)
    agent_result: Any | None = None
    turn_result: TurnResult | None = None
    validation_result: dict[str, Any] | None = None
    pending_request: SkillBuilderPendingRequest | None = None
    hitl_confirmations: tuple[dict[str, Any], ...] = field(default_factory=tuple)
    artifact_sha256: str | None = None
    delivery_decision: DeliveryDecision = DeliveryDecision.NEEDS_REVIEW
    receipt_valid: bool = False
    blockers: tuple[str, ...] = field(default_factory=tuple)
    failure: ExecutionFailure | None = None

    @property
    def workspace_id(self) -> str:
        return self.input.workspace_id

    @property
    def generated_root(self) -> Path:
        return self.input.root / "generated-skill"

    @property
    def status(self) -> SkillBuilderStatus:
        return lifecycle_status(self.cursor)

    @property
    def publishable(self) -> bool:
        return self.delivery_decision == DeliveryDecision.READY and self.receipt_valid

    @property
    def error(self) -> str | None:
        return self.failure.developer_message if self.failure is not None else None

    @property
    def available_actions(self) -> tuple[ExecutionAction, ...]:
        if self.status == SkillBuilderStatus.WAITING_FOR_USER:
            return (ExecutionAction.RESUME,)
        if self.status == SkillBuilderStatus.RUNNING:
            return ()
        actions: list[ExecutionAction] = [ExecutionAction.INSPECT]
        if self.artifact_sha256:
            actions.extend(
                (ExecutionAction.EDIT, ExecutionAction.EXPORT, ExecutionAction.VALIDATE)
            )
        if self.status == SkillBuilderStatus.FAILED:
            actions.append(ExecutionAction.RETRY)
        if self.publishable:
            actions.append(ExecutionAction.PUBLISH)
        return tuple(dict.fromkeys(actions))


__all__ = [
    "SKILL_BUILDER_POLICY_VERSION",
    "DeliveryDecision",
    "ExecutionAction",
    "ExecutionFailure",
    "LifecycleCursor",
    "SkillBuilderExecution",
    "SkillBuilderInput",
    "SkillBuilderOptions",
    "SkillBuilderPendingRequest",
    "SkillBuilderState",
    "SkillBuilderStatus",
    "SkillBuilderTurnRequest",
    "cursor_for_phase",
    "lifecycle_status",
]
