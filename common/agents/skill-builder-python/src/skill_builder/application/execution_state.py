# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Lifecycle result and HITL state normalization.

This module keeps serialization-oriented state decisions out of the generation
orchestrator.  It is intentionally host-neutral so the same execution object
can be used in-process, through the CLI, or by a future plugin host.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from skill_builder.application.artifact_digest import skill_artifact_sha256
from skill_builder.application.draft_package_validation import validate_draft_package
from skill_builder.application.hitl_form_contract import (
    DecisionFormAnswerError,
    normalize_decision_form_answer,
)
from skill_builder.application.scenario_projection import authoritative_decision_form_fields
from skill_builder.domain.execution import (
    DeliveryDecision,
    ExecutionFailure,
    LifecycleCursor,
    SkillBuilderExecution,
    SkillBuilderOptions,
    SkillBuilderPendingRequest,
    SkillBuilderState,
    SkillBuilderStatus,
)
from skill_builder.application.validation_status import project_validation_status


UNANSWERED_HITL_STATUSES = frozenset(
    {
        "deferred",
        "expired",
        "failed",
        "timeout",
        "unavailable",
        "waiting_for_user",
    }
)


def hitl_answer_status(answer: Any) -> str:
    if not isinstance(answer, dict):
        return "completed"
    return str(answer.get("status") or "completed").strip().lower() or "completed"


def hitl_answer_is_deferred(answer: Any) -> bool:
    return hitl_answer_status(answer) in UNANSWERED_HITL_STATUSES


def normalize_bound_decision_form_answer(
    *,
    root: Path | None,
    scenario_contract_hash: str,
    answer: dict[str, Any],
    fields: list[dict[str, Any]],
    message: str | None = None,
    default_value: str | None = None,
) -> dict[str, Any]:
    authoritative_fields = fields
    bound_hash = str(scenario_contract_hash or "").strip()
    if bound_hash:
        if root is None:
            raise DecisionFormAnswerError(
                "Scenario HITL 回答缺少当前 Workspace 根路径，不能恢复权威决策契约"
            )
        authoritative_fields, issues = authoritative_decision_form_fields(
            root,
            scenario_contract_hash=bound_hash,
            public_fields=fields,
        )
        if issues:
            raise DecisionFormAnswerError("；".join(issues))
    return normalize_decision_form_answer(
        answer=answer,
        fields=authoritative_fields,
        message=message,
        default_value=default_value,
    )


def build_hitl_confirmation(
    pending: SkillBuilderPendingRequest,
    answer: Any,
    *,
    ordinal: int,
    root: Path | None = None,
) -> dict[str, Any]:
    request = pending.request
    status = hitl_answer_status(answer)
    answer_value = answer
    if isinstance(answer, dict) and status == "completed" and "answer" in answer:
        answer_value = answer.get("answer")
    if str(request.get("kind") or "").strip().lower() == "decision_form":
        if isinstance(answer_value, dict) and (
            "decisions" in answer_value or "value" in answer_value
        ):
            decision_answer = answer_value
        elif isinstance(answer_value, dict):
            # Standalone providers and the CLI return the public form object
            # directly, while some hosts wrap it in ``decisions``. Both enter
            # the same canonical confirmation boundary here.
            decision_answer = {"decisions": answer_value}
        else:
            decision_answer = {"value": answer_value}
        answer_value = normalize_bound_decision_form_answer(
            root=root,
            scenario_contract_hash=str(
                request.get("scenario_contract_hash") or ""
            ),
            answer=decision_answer,
            fields=request.get("options") if isinstance(request.get("options"), list) else [],
            message=str(request.get("message") or ""),
            default_value=(
                str(request.get("default_value"))
                if request.get("default_value") is not None
                else None
            ),
        )
    try:
        answer_text = (
            answer_value
            if isinstance(answer_value, str)
            else json.dumps(answer_value, ensure_ascii=False, sort_keys=True)
        )
    except (TypeError, ValueError):
        answer_text = str(answer_value)
    return {
        "id": f"standalone-hitl-{ordinal}",
        "kind": str(request.get("kind") or "input"),
        "title": str(request.get("title") or "人工确认"),
        "message": str(request.get("message") or ""),
        "options": request.get("options") if isinstance(request.get("options"), list) else [],
        "answer": answer_value,
        "answer_text": answer_text,
        "scenarioContractHash": str(request.get("scenario_contract_hash") or "").strip(),
    }


def execution_from_results(
    *,
    state: SkillBuilderState,
    options: SkillBuilderOptions,
    agent_result: Any | None,
    validation_result: dict[str, Any] | None,
) -> SkillBuilderExecution:
    artifact_sha256 = skill_artifact_sha256(state.input.root / "generated-skill")
    package_validation = validate_draft_package(state.input.root)
    if state.status == SkillBuilderStatus.WAITING_FOR_USER and state.pending_request is not None:
        cursor = LifecycleCursor.WAITING_FOR_USER
        decision = DeliveryDecision.NEEDS_REVIEW
        blockers = ("waiting_for_user",)
        failure = None
    elif validation_result is None:
        if not artifact_sha256:
            failure_code = "draft_package_missing"
            cursor = LifecycleCursor.FAILED
            decision = DeliveryDecision.FAILED
            blockers = (failure_code,)
            message = "lifecycle completed without a generated Skill package"
            failure = ExecutionFailure(
                code=failure_code,
                category="candidate_lifecycle",
                retryable=True,
                repairable=False,
                user_message="本轮未形成可用的 Skill 草稿。",
                developer_message=f"{failure_code}: {message}",
                details={"findings": list(package_validation.errors)},
            )
        else:
            cursor = LifecycleCursor.DRAFT_READY
            decision = DeliveryDecision.DRAFT_READY
            blockers = ()
            failure = None
    else:
        projection = project_validation_status(
            validation_result,
            artifact_available=bool(artifact_sha256),
        )
        blockers = projection.blocking_failure_ids
        if projection.ready:
            cursor = LifecycleCursor.READY
            decision = DeliveryDecision.READY
        elif projection.failed:
            cursor = LifecycleCursor.FAILED
            decision = DeliveryDecision.BLOCKED
        else:
            cursor = LifecycleCursor.NEEDS_REVIEW
            decision = DeliveryDecision.NEEDS_REVIEW
            if not blockers:
                blockers = ("validation_not_ready",)
        failure = None
    return SkillBuilderExecution(
        cursor=cursor,
        input=state.input,
        policy_version=options.policy_version,
        options=options,
        agent_result=agent_result,
        validation_result=validation_result,
        pending_request=(
            state.pending_request
            if cursor == LifecycleCursor.WAITING_FOR_USER
            else None
        ),
        hitl_confirmations=state.hitl_confirmations,
        artifact_sha256=artifact_sha256,
        delivery_decision=decision,
        receipt_valid=bool(
            artifact_sha256
            and validation_result is not None
            and cursor == LifecycleCursor.READY
        ),
        blockers=blockers,
        failure=failure,
    )


def execution_from_candidate_failure(
    *,
    state: SkillBuilderState,
    options: SkillBuilderOptions,
    agent_result: Any | None,
    candidate_status: dict[str, Any],
    phase: str = "candidate",
) -> SkillBuilderExecution:
    """Return a failed execution for an incomplete candidate transaction.

    A candidate that never crossed the package handoff boundary has not
    completed delivery validation and is therefore a generation failure.  The
    persisted diagnostic remains reviewable, but the public terminal state is
    deliberately distinct from ``needs_review`` (which requires a committed
    candidate and an independent delivery validation result).
    """

    default_code = "scenario_lifecycle_failed" if phase == "scenario" else "candidate_lifecycle_failed"
    root_code = str(candidate_status.get("error") or default_code)
    report_path = state.input.root / "validation" / "diagnostics" / "candidate_lifecycle_failure.json"
    report = {
        "schemaVersion": "skill-builder-candidate-lifecycle-failure/v1",
        "workspaceId": state.input.workspace_id,
        "phase": phase,
        "status": "failed",
        "validationPhase": "preflight" if phase == "candidate" else phase,
        "severity": "p0",
        "error": root_code,
        "issues": candidate_status.get("issues") or [],
        "candidateStatus": candidate_status,
        "artifactAvailable": bool(skill_artifact_sha256(state.input.root / "generated-skill")),
        "publishable": False,
        "retryable": True,
        "repairable": False,
        "nextAction": "inspect_diagnostics_and_regenerate",
    }
    try:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2, default=str) + "\n",
            encoding="utf-8",
        )
    except OSError:
        # The execution failure is still returned; report persistence must not
        # turn a deterministic candidate rejection into a host crash.
        report_path = None
    return SkillBuilderExecution(
        cursor=LifecycleCursor.FAILED,
        input=state.input,
        policy_version=options.policy_version,
        options=options,
        agent_result=agent_result,
        validation_result=None,
        hitl_confirmations=state.hitl_confirmations,
        artifact_sha256=skill_artifact_sha256(state.input.root / "generated-skill"),
        delivery_decision=DeliveryDecision.FAILED,
        receipt_valid=False,
        blockers=(root_code,),
        failure=ExecutionFailure(
            code=f"{phase}_lifecycle_failed",
            category="candidate_lifecycle",
            retryable=True,
            repairable=False,
            user_message=(
                "场景抽取未形成可确认结果，请检查结构化诊断后重新生成。"
                if phase == "scenario"
                else "候选 Skill 未完成原子提交，请检查结构化诊断后重新生成。"
            ),
            developer_message=f"{phase}_lifecycle_failed: {root_code}",
            details={
                "candidate_status": candidate_status,
                "diagnostic_path": str(report_path.relative_to(state.input.root))
                if report_path is not None
                else None,
            },
        ),
    )


def execution_from_operational_failure(
    *,
    state: SkillBuilderState,
    options: SkillBuilderOptions,
    failure: ExecutionFailure,
) -> SkillBuilderExecution:
    artifact_sha256 = skill_artifact_sha256(state.input.root / "generated-skill")
    return SkillBuilderExecution(
        cursor=LifecycleCursor.FAILED,
        input=state.input,
        policy_version=options.policy_version,
        options=options,
        validation_result=None,
        hitl_confirmations=state.hitl_confirmations,
        artifact_sha256=artifact_sha256,
        delivery_decision=DeliveryDecision.FAILED,
        receipt_valid=False,
        blockers=(failure.code,),
        failure=failure,
    )
def execution_from_state(state: SkillBuilderState) -> SkillBuilderExecution:
    validation_result = state.validation_result
    current_artifact_sha256 = skill_artifact_sha256(state.input.root / "generated-skill")
    receipt_valid = bool(
        state.artifact_sha256
        and current_artifact_sha256 == state.artifact_sha256
        and validation_result is not None
    )
    cursor = state.cursor
    failure = None
    # A persisted cursor is only a recovery hint.  It must never manufacture
    # a draft state after a worker was interrupted before the first portable
    # package file was written.  Older workspaces can contain ``scenario`` or
    # ``authoring`` here with no artifact and no failure, so normalize that
    # combination at the single Core projection boundary.
    package_validation = validate_draft_package(state.input.root)
    no_package_terminal = cursor not in {
        LifecycleCursor.NEW,
        LifecycleCursor.WAITING_FOR_USER,
        LifecycleCursor.NEEDS_REVIEW,
        LifecycleCursor.FAILED,
    }
    if (
        validation_result is None
        and not package_validation.ok
        and no_package_terminal
    ):
        cursor = LifecycleCursor.FAILED
        decision = DeliveryDecision.FAILED
        failure_code = (
            "draft_package_missing"
            if not current_artifact_sha256
            else "draft_package_invalid"
        )
        blockers = tuple(
            dict.fromkeys(
                (
                    *(() if state.failure is None else (state.failure.code,)),
                    failure_code,
                    *(
                        str(item.get("id"))
                        for item in package_validation.errors
                        if item.get("id")
                    ),
                )
            )
        )
        failure = state.failure or ExecutionFailure(
            code=failure_code,
            category="candidate_lifecycle",
            retryable=True,
            repairable=bool(current_artifact_sha256),
            user_message=(
                "本轮未形成可用的 Skill 草稿。"
                if not current_artifact_sha256
                else "本轮仅形成不完整的 Skill 草稿。"
            ),
            developer_message=(
                f"{failure_code}: persisted lifecycle cursor had no valid "
                "portable generated-skill package"
            ),
            details={"findings": list(package_validation.errors)},
        )
    elif validation_result is None and cursor == LifecycleCursor.NEW:
        blockers = ()
        decision = DeliveryDecision.NEEDS_REVIEW
    elif validation_result is None:
        blockers: tuple[str, ...] = ()
        decision = DeliveryDecision.DRAFT_READY
        if cursor == LifecycleCursor.NEEDS_REVIEW:
            decision = DeliveryDecision.NEEDS_REVIEW
            blockers = (state.failure.code,) if state.failure is not None else ("draft_package_invalid",)
        elif cursor not in {
            LifecycleCursor.WAITING_FOR_USER,
            LifecycleCursor.FAILED,
        }:
            cursor = LifecycleCursor.DRAFT_READY
    else:
        projection = project_validation_status(
            validation_result,
            artifact_available=receipt_valid,
        )
        blockers = projection.blocking_failure_ids
        if projection.ready and receipt_valid:
            decision = DeliveryDecision.READY
        elif projection.failed:
            cursor = LifecycleCursor.FAILED
            decision = DeliveryDecision.BLOCKED
        else:
            cursor = LifecycleCursor.NEEDS_REVIEW
            decision = DeliveryDecision.NEEDS_REVIEW
    if state.status == SkillBuilderStatus.WAITING_FOR_USER:
        blockers = ("waiting_for_user",)
        decision = DeliveryDecision.NEEDS_REVIEW
    elif state.status == SkillBuilderStatus.NEEDS_REVIEW and not blockers:
        blockers = ("validation_not_ready",) if validation_result else ("validation_not_run",)
    elif state.status == SkillBuilderStatus.FAILED:
        decision = DeliveryDecision.FAILED
    if state.status == SkillBuilderStatus.READY and not receipt_valid:
        cursor = LifecycleCursor.NEEDS_REVIEW
        decision = DeliveryDecision.NEEDS_REVIEW
        blockers = tuple(dict.fromkeys((*blockers, "artifact_changed_after_validation")))
    return SkillBuilderExecution(
        cursor=cursor,
        input=state.input,
        policy_version=state.policy_version,
        options=state.options,
        validation_result=validation_result,
        pending_request=state.pending_request,
        hitl_confirmations=state.hitl_confirmations,
        # Always expose the digest observed during this projection.  Returning
        # a stale persisted digest makes an interrupted/externally edited
        # workspace look like it still owns a candidate receipt.
        artifact_sha256=current_artifact_sha256,
        delivery_decision=decision,
        receipt_valid=receipt_valid,
        blockers=blockers,
        failure=failure if failure is not None else state.failure,
    )


def apply_execution_to_state(state: SkillBuilderState, execution: SkillBuilderExecution) -> None:
    state.options = execution.options
    state.cursor = execution.cursor
    state.pending_request = execution.pending_request
    state.hitl_confirmations = execution.hitl_confirmations
    state.validation_result = execution.validation_result
    state.artifact_sha256 = execution.artifact_sha256
    state.policy_version = execution.policy_version
    state.failure = execution.failure


__all__ = [
    "UNANSWERED_HITL_STATUSES",
    "apply_execution_to_state",
    "build_hitl_confirmation",
    "normalize_bound_decision_form_answer",
    "execution_from_candidate_failure",
    "execution_from_operational_failure",
    "execution_from_results",
    "execution_from_state",
    "hitl_answer_is_deferred",
    "hitl_answer_status",
]
