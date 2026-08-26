# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Derive the stable host/UI projection from one Core execution."""

from __future__ import annotations

from skill_builder.application.artifact_inventory import actual_artifact_files
from skill_builder.application.package_builder import resolve_skill_package_metadata
from skill_builder.application.package_identity import resolve_package_identity
from skill_builder.application.agent_submission import verified_candidate_receipt_status
from skill_builder.domain.execution import SkillBuilderExecution, SkillBuilderStatus
from skill_builder.types import PackageProjection, PresentationProjection


def project_execution_presentation(
    execution: SkillBuilderExecution,
) -> PresentationProjection:
    identity = resolve_package_identity(execution.input.root, execution.input.skill_name)
    metadata = resolve_skill_package_metadata(
        skill_name=identity.resolved_name,
        fallback_skill_name=identity.resolved_name,
        display_name=execution.input.display_name,
        description=identity.candidate_description or execution.input.description,
        version=execution.input.version,
        tags=execution.input.tags,
    )
    agent_response = (
        execution.agent_result.final_response
        if execution.agent_result is not None
        and isinstance(getattr(execution.agent_result, "final_response", None), dict)
        else {}
    )
    agent_self_check = (
        agent_response.get("agent_self_check")
        if isinstance(agent_response.get("agent_self_check"), dict)
        else None
    )
    visible_validation = (
        execution.validation_result
        if isinstance(execution.validation_result, dict)
        else None
    )
    summary_value = (
        visible_validation.get("summary")
        if isinstance(visible_validation, dict)
        and isinstance(visible_validation.get("summary"), dict)
        else {}
    )
    agent_summary = (
        agent_response.get("summary") or None
    )
    summary = str(
        summary_value.get("message")
        or agent_summary
        or (execution.failure.user_message if execution.failure else "")
        or "Skill Builder 已完成当前生命周期步骤。"
    )
    last_error = None
    if execution.failure is not None:
        last_error = execution.failure.developer_message
    elif execution.status not in {
        SkillBuilderStatus.DRAFT_READY,
        SkillBuilderStatus.READY,
        SkillBuilderStatus.NEEDS_REVIEW,
        SkillBuilderStatus.WAITING_FOR_USER,
    }:
        last_error = summary
    artifact_files = tuple(actual_artifact_files(execution.input.root))
    package_committed = bool(
        execution.artifact_sha256
        and verified_candidate_receipt_status(execution.input.root).get("ok")
    )
    return PresentationProjection(
        workspace_status=execution.status.value,
        draft_status=(
            "ready"
            if package_committed
            else "not_ready"
        ),
        validation_status=(
            str(visible_validation.get("status") or "not_run")
            if isinstance(visible_validation, dict)
            else "fail"
            if execution.status == SkillBuilderStatus.NEEDS_REVIEW
            else "not_run"
        ),
        cursor=execution.cursor,
        delivery_decision=execution.delivery_decision,
        publishable=execution.publishable,
        last_error=last_error,
        summary=summary,
        blockers=execution.blockers,
        available_actions=execution.available_actions,
        package=PackageProjection(
            skill_name=metadata.skill_name,
            display_name=metadata.display_name,
            description=metadata.description,
            version=metadata.version,
            tags=tuple(metadata.tags),
        ),
        acceptance=visible_validation if isinstance(visible_validation, dict) else None,
        agent_self_check=agent_self_check,
        artifact_files=artifact_files,
        turn=execution.turn_result,
    )


__all__ = ["project_execution_presentation"]
