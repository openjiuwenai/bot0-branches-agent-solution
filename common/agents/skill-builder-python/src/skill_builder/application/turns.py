# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Core-owned planning helpers for conversational workspace turns."""

from __future__ import annotations

from pathlib import Path

from skill_builder.application.artifact_inventory import actual_artifact_files
from skill_builder.application.file_helpers import _has_substantive_generated_package
from skill_builder.domain.conversation import ConversationIntent, classify_conversation_intent
from skill_builder.domain.execution import (
    DeliveryDecision,
    SkillBuilderExecution,
    SkillBuilderInput,
    SkillBuilderOptions,
    SkillBuilderTurnRequest,
    LifecycleCursor,
)


def plan_execution_turn(
    execution: SkillBuilderExecution | SkillBuilderInput,
    request: SkillBuilderTurnRequest,
) -> ConversationIntent:
    if isinstance(execution, SkillBuilderInput):
        execution = SkillBuilderExecution(
            cursor=LifecycleCursor.NEW,
            input=execution,
            policy_version=SkillBuilderOptions().policy_version,
        )
    root = execution.input.root
    has_package = _has_substantive_generated_package(root)
    has_progress = bool(actual_artifact_files(root))
    return classify_conversation_intent(
        message=request.message,
        requested_action=request.requested_action,
        has_package=has_package,
        has_completed_package=execution.publishable,
        has_progress=has_progress,
        has_validation_failure=execution.delivery_decision in {
            DeliveryDecision.BLOCKED,
            DeliveryDecision.FAILED,
        },
    )


def changed_paths_require_material_grounding(
    root: Path,
    *,
    changed_paths: tuple[str, ...],
) -> bool:
    """Require source-material reads for executable or business-content edits."""

    del root
    for relative_path in changed_paths:
        normalized = relative_path.replace("\\", "/").removeprefix("generated-skill/")
        if normalized == "agents/openai.yaml":
            continue
        if normalized == "SKILL.md" or normalized.startswith(
            ("scripts/", "fixtures/", "references/")
        ):
            return True
        if Path(normalized).name in {
            "requirements.txt",
            "requirements-dev.txt",
            "pyproject.toml",
            "package.json",
            "package-lock.json",
            "pnpm-lock.yaml",
            "yarn.lock",
        }:
            return True
    return False


def agent_read_uploaded_material(files_read: list[str]) -> bool:
    return any(
        str(path or "").replace("\\", "/").lstrip("./").startswith("inputs/")
        for path in files_read
    )


__all__ = [
    "agent_read_uploaded_material",
    "changed_paths_require_material_grounding",
    "plan_execution_turn",
]
