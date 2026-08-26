# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Standalone Skill Builder lifecycle facade.

The facade owns host-neutral lifecycle semantics. Host persistence, HTTP,
authentication, S3, and task scheduling remain outside and are supplied via
ports or the existing service adapter.
"""

from __future__ import annotations

import secrets
import uuid
from dataclasses import replace
from typing import Any

from skill_builder.adapters.openjiuwen_python import OpenJiuwenPythonAgentAdapter
from skill_builder.application.artifact_inventory import ensure_workspace_material_digest
from skill_builder.application.configuration import SkillBuilderAdapters
from skill_builder.application.agent_submission import (
    candidate_submission_status,
    ensure_workspace_package_revision,
    verified_candidate_receipt_status,
)
from skill_builder.application.delivery import (
    build_execution_export_archive,
    build_execution_publish_archive,
)
from skill_builder.application.revision_store import RevisionStore
from skill_builder.application.validation_reconciliation import (
    reconcile_preflight_and_delivery,
)
from skill_builder.application.draft_package_validation import validate_draft_package
from skill_builder.application.acceptance import (
    accept_skill_package,
    acceptance_result_payload,
    persist_acceptance_files,
)
from skill_builder.application.artifact_digest import skill_artifact_sha256
from skill_builder.application.conversation import project_agent_turn_result
from skill_builder.application.package_identity import resolve_package_identity
from skill_builder.application.execution_state import (
    apply_execution_to_state,
    build_hitl_confirmation,
    execution_from_candidate_failure,
    execution_from_operational_failure,
    execution_from_results,
    execution_from_state,
    hitl_answer_is_deferred,
)
from skill_builder.application.execution_failure import (
    execution_failure_from_exception,
)
from skill_builder.application.run_artifacts import (
    changed_repair_artifact_files,
    relevant_repair_artifact_files,
    repair_artifact_snapshot,
    record_agent_core_run,
)
from skill_builder.application.turns import (
    agent_read_uploaded_material,
    changed_paths_require_material_grounding,
    plan_execution_turn,
)
from skill_builder.application.lifecycle_io import (
    EventEmitter,
    HitlHandler,
    SkillBuilderLifecycleIO,
)
from skill_builder.application.workspace_transaction import (
    copy_workspace_artifact_snapshot,
    discard_workspace_artifact_snapshot,
    restore_workspace_artifact_snapshot,
)
from skill_builder.application.workflow import run_primary_workflow
from skill_builder.domain.execution import (
    ExecutionFailure,
    LifecycleCursor,
    SkillBuilderExecution,
    SkillBuilderInput,
    SkillBuilderOptions,
    SkillBuilderState,
    SkillBuilderStatus,
    SkillBuilderTurnRequest,
    cursor_for_phase,
)
from skill_builder.domain.conversation import (
    ConversationIntent,
    MutationPolicy,
    TurnResult,
    TurnStatus,
)
from skill_builder.ports import (
    SkillBuilderAgentRequest,
    SkillBuilderAgentResult,
)


async def _discard_event(
    _event_type: str,
    _summary: str,
    _payload: dict[str, Any] | None = None,
) -> None:
    return


def _bind_candidate_package_identity(builder_input: SkillBuilderInput) -> SkillBuilderInput:
    """Replace an orchestration placeholder with the submitted package identity."""

    identity = resolve_package_identity(builder_input.root, builder_input.skill_name)
    description = identity.candidate_description or builder_input.description
    if (
        identity.resolved_name == builder_input.skill_name
        and description == builder_input.description
    ):
        return builder_input
    return replace(
        builder_input,
        skill_name=identity.resolved_name,
        description=description,
    )


def _validation_terminal_phase(execution: SkillBuilderExecution) -> str:
    if execution.status == SkillBuilderStatus.READY:
        return "ready"
    return "blocked"


def _candidate_preflight_for_artifact(
    agent_result: SkillBuilderAgentResult | None,
    candidate_sha256: str | None,
) -> dict[str, Any] | None:
    """Return preflight only when it is bound to the current candidate."""

    response = getattr(agent_result, "final_response", None)
    if not isinstance(response, dict) or not candidate_sha256:
        return None
    candidate_commit = response.get("candidate_commit")
    if not isinstance(candidate_commit, dict):
        return None
    committed_sha256 = str(candidate_commit.get("artifactSha256") or "").strip()
    if not committed_sha256 or committed_sha256 != candidate_sha256:
        return None
    acceptance = response.get("acceptance")
    return dict(acceptance) if isinstance(acceptance, dict) else None


def _agent_self_check(agent_result: SkillBuilderAgentResult | None) -> dict[str, Any] | None:
    response = getattr(agent_result, "final_response", None)
    if not isinstance(response, dict):
        return None
    value = response.get("agent_self_check")
    return dict(value) if isinstance(value, dict) else None


class SkillBuilderEngine:
    def __init__(self, *, adapters: SkillBuilderAdapters | None = None) -> None:
        self.adapters = adapters or SkillBuilderAdapters()

    def plan_turn(
        self,
        execution: SkillBuilderExecution | SkillBuilderInput,
        request: SkillBuilderTurnRequest,
    ) -> ConversationIntent:
        return plan_execution_turn(execution, request)

    async def run_turn(
        self,
        workspace_id: str,
        request: SkillBuilderTurnRequest,
        *,
        emit_event: EventEmitter | None = None,
    ) -> SkillBuilderExecution:
        """Run one inspect/edit/repair turn under Core mutation policy."""

        execution = await self.load_execution(workspace_id)
        if execution is None:
            raise KeyError(f"Skill Builder workspace state not found: {workspace_id}")
        intent = self.plan_turn(execution, request)
        if intent.action == "repair" and intent.mutation_policy != MutationPolicy.FORBIDDEN:
            repaired = await self.repair(
                execution,
                instruction=request.message,
                emit_event=emit_event,
            )
            turn = TurnResult(
                status=(
                    TurnStatus.CHANGES_APPLIED
                    if repaired.artifact_sha256 != execution.artifact_sha256
                    else TurnStatus.ANSWERED
                ),
                answer=(
                    "显式草稿修订已执行一次，结果以当前 PackageRevision 为准。"
                ),
                changed_paths=(),
                metadata={"intent": intent.as_dict()},
            )
            repaired = replace(repaired, turn_result=turn)
            await self._persist_execution(repaired)
            return repaired

        read_only = intent.mutation_policy == MutationPolicy.FORBIDDEN
        phase = "chat" if read_only else "edit"
        root = execution.input.root
        backup_root = root / ".skill-builder" / "turn-transactions" / uuid.uuid4().hex
        before = repair_artifact_snapshot(root)
        copy_workspace_artifact_snapshot(root, backup_root)
        try:
            agent_result = await self._run_agent(
                builder_input=replace(execution.input, user_message=request.message),
                options=replace(
                    execution.options,
                    run_phase=phase,
                ),
                emit_event=emit_event or _discard_event,
            )
            changed = tuple(
                sorted(
                    relevant_repair_artifact_files(
                        changed_repair_artifact_files(before, repair_artifact_snapshot(root))
                    )
                )
            )
            turn = project_agent_turn_result(
                agent_result.final_response,
                changed_paths=changed,
            )
            if read_only:
                if changed:
                    restore_workspace_artifact_snapshot(root, backup_root)
                    turn = TurnResult(
                        status=TurnStatus.ROLLED_BACK,
                        answer="只读对话产生了候选写入，Core 已全部回滚。",
                        changed_paths=(),
                        metadata={"intent": intent.as_dict(), "candidate_paths": list(changed)},
                    )
                result = replace(execution, agent_result=agent_result, turn_result=turn)
                await self._persist_execution(result)
                return result

            if turn.status in {TurnStatus.NEEDS_INPUT, TurnStatus.FAILED} or not changed:
                if changed:
                    restore_workspace_artifact_snapshot(root, backup_root)
                    turn = replace(turn, status=TurnStatus.ROLLED_BACK, changed_paths=())
                result = replace(execution, agent_result=agent_result, turn_result=turn)
                await self._persist_execution(result)
                return result

            if (
                changed_paths_require_material_grounding(root, changed_paths=changed)
                and not agent_read_uploaded_material(agent_result.files_read)
            ):
                restore_workspace_artifact_snapshot(root, backup_root)
                turn = TurnResult(
                    status=TurnStatus.ROLLED_BACK,
                    answer=(
                        "本轮修改涉及业务内容或可执行产物，但 Agent 未读取当前 inputs/ 材料；"
                        "Core 已恢复修改前版本。"
                    ),
                    changed_paths=(),
                    metadata={"intent": intent.as_dict(), "candidate_paths": list(changed)},
                )
                result = replace(execution, agent_result=agent_result, turn_result=turn)
                await self._persist_execution(result)
                return result

            package_status = ensure_workspace_package_revision(root)
            if not package_status.get("ok"):
                restore_workspace_artifact_snapshot(root, backup_root)
                await self._persist_execution(execution)
                return replace(
                    execution,
                    agent_result=agent_result,
                    turn_result=TurnResult(
                        status=TurnStatus.ROLLED_BACK,
                        answer="候选修改破坏了最小包结构，Core 已恢复修改前版本。",
                        changed_paths=(),
                        metadata={
                            "intent": intent.as_dict(),
                            "candidate_paths": list(changed),
                            "candidate_status": package_status,
                        },
                    ),
                )
            revised_input = _bind_candidate_package_identity(execution.input)
            revised_state = SkillBuilderState(
                input=revised_input,
                options=replace(execution.options, run_phase="edit"),
                cursor=LifecycleCursor.CANDIDATE_COMMITTED,
                hitl_confirmations=execution.hitl_confirmations,
                policy_version=execution.policy_version,
            )
            revised = execution_from_results(
                state=revised_state,
                options=revised_state.options,
                agent_result=agent_result,
                validation_result=None,
            )
            result = replace(
                revised,
                agent_result=agent_result,
                turn_result=replace(turn, changed_paths=changed),
            )
            await self._persist_execution(result)
            validated = await self._validate_committed_package(
                revised_input,
                hitl_confirmations=execution.hitl_confirmations,
                agent_result=agent_result,
                source_options=revised_state.options,
                emit_event=emit_event,
            )
            return replace(validated, turn_result=result.turn_result)
        except Exception as exc:  # Preserve the accepted candidate on turn failure.
            restore_workspace_artifact_snapshot(root, backup_root)
            turn = TurnResult(
                status=TurnStatus.FAILED,
                answer=f"本轮没有应用修改：{str(exc)[:1000]}",
                changed_paths=(),
                metadata={"intent": intent.as_dict()},
            )
            result = replace(execution, turn_result=turn)
            await self._persist_execution(result)
            return result
        finally:
            discard_workspace_artifact_snapshot(backup_root)

    async def reconcile(
        self,
        builder_input: SkillBuilderInput,
        *,
        options: SkillBuilderOptions | None = None,
        hitl_confirmations: tuple[dict[str, Any], ...] = (),
        resume_answer: dict[str, Any] | None = None,
        advance: bool = True,
        emit_event: EventEmitter | None = None,
        ask_user: HitlHandler | None = None,
    ) -> SkillBuilderExecution:
        """Reconcile durable state and choose the only valid next use case."""

        resolved_options = options or SkillBuilderOptions(run_phase="workflow")
        try:
            state = (
                await self.adapters.state_store.load(builder_input.workspace_id)
                if self.adapters.state_store is not None
                else None
            )
            if (
                state is not None
                and state.status == SkillBuilderStatus.WAITING_FOR_USER
                and state.pending_request is not None
            ):
                if resume_answer is None:
                    return execution_from_state(state)
                return await self.resume(
                    builder_input.workspace_id,
                    resume_token=state.pending_request.resume_token,
                    answer=resume_answer,
                    emit_event=emit_event,
                    ask_user=ask_user,
                )

            if state is not None:
                current = execution_from_state(state)
                if current.status == SkillBuilderStatus.READY and current.receipt_valid:
                    return current
                if current.status in {
                    SkillBuilderStatus.DRAFT_READY,
                    SkillBuilderStatus.NEEDS_REVIEW,
                } and current.artifact_sha256 == skill_artifact_sha256(
                    builder_input.root / "generated-skill"
                ):
                    return current
                if not advance and current.status in {
                    SkillBuilderStatus.DRAFT_READY,
                    SkillBuilderStatus.READY,
                    SkillBuilderStatus.NEEDS_REVIEW,
                    SkillBuilderStatus.FAILED,
                }:
                    return current

            candidate_status = verified_candidate_receipt_status(builder_input.root)
            if candidate_status.get("ok"):
                return await self.resume_candidate(
                    builder_input,
                    options=resolved_options,
                    hitl_confirmations=(
                        state.hitl_confirmations if state is not None else hitl_confirmations
                    ),
                    emit_event=emit_event,
                    auto_validate=True,
                )
            if not advance:
                raise RuntimeError(
                    "execution_interrupted: no committed candidate checkpoint is available"
                )
            return await self.build(
                builder_input,
                options=resolved_options,
                hitl_confirmations=hitl_confirmations,
                emit_event=emit_event,
                ask_user=ask_user,
            )
        except Exception as exc:  # Core owns operational failure classification.
            failure = execution_failure_from_exception(exc)
            state = SkillBuilderState(
                input=builder_input,
                options=resolved_options,
                cursor=LifecycleCursor.FAILED,
                hitl_confirmations=hitl_confirmations,
                policy_version=resolved_options.policy_version,
                failure=failure,
            )
            execution = execution_from_operational_failure(
                state=state,
                options=resolved_options,
                failure=failure,
            )
            apply_execution_to_state(state, execution)
            await self._save_state(state)
            return execution

    async def build(
        self,
        builder_input: SkillBuilderInput,
        *,
        options: SkillBuilderOptions | None = None,
        hitl_confirmations: tuple[dict[str, Any], ...] = (),
        emit_event: EventEmitter | None = None,
        ask_user: HitlHandler | None = None,
    ) -> SkillBuilderExecution:
        resolved_options = options or SkillBuilderOptions()
        digest_path = ensure_workspace_material_digest(
            builder_input.root,
            materials_markdown=builder_input.materials_markdown,
        )
        if emit_event is not None:
            await emit_event(
                "artifact.skeleton",
                "Core 已生成轻量材料摘要。",
                {"files": [digest_path], "phase": "prepare"},
            )
        state = SkillBuilderState(
            input=builder_input,
            options=resolved_options,
            cursor=cursor_for_phase(resolved_options.run_phase),
            hitl_confirmations=hitl_confirmations,
            policy_version=resolved_options.policy_version,
        )
        await self._save_state(state)
        lifecycle_io = SkillBuilderLifecycleIO(
            state=state,
            adapters=self.adapters,
            save_state=self._save_state,
            emit_event=emit_event,
            ask_user=ask_user,
        )

        try:
            workflow_result = await run_primary_workflow(
                builder_input=builder_input,
                options=resolved_options,
                state=state,
                lifecycle_io=lifecycle_io,
                invoke_agent=self._invoke_workflow_agent,
            )
            agent_result = workflow_result.agent_result
            if state.status != SkillBuilderStatus.WAITING_FOR_USER and workflow_result.failed:
                submission_status = workflow_result.submission_status or {
                    "ok": False,
                    "error": "candidate_lifecycle_failed",
                }
                is_scenario = workflow_result.phase == "scenario"
                await lifecycle_io.emit(
                    "agent.scenario_commit_rejected" if is_scenario else "agent.candidate_commit_rejected",
                    (
                        "ScenarioContract 未通过原子提交边界，未进入 HITL 或写包阶段。"
                        if is_scenario
                        else "Skill 草稿未通过最小包提交边界。"
                    ),
                    submission_status,
                )
                execution = execution_from_candidate_failure(
                    state=state,
                    options=resolved_options,
                    agent_result=agent_result,
                    candidate_status=submission_status,
                    phase="scenario" if is_scenario else "candidate",
                )
                apply_execution_to_state(state, execution)
                await self._save_state(state)
                return execution
            return await self._commit_draft_and_persist(
                builder_input=builder_input,
                resolved_options=resolved_options,
                state=state,
                agent_result=agent_result,
                emit_event=emit_event,
                auto_validate=True,
            )
        except Exception as exc:
            state.cursor = LifecycleCursor.FAILED
            state.failure = ExecutionFailure(
                code=str(getattr(exc, "code", "") or "agent_runtime_failed"),
                category="platform_runtime",
                retryable=True,
                repairable=False,
                user_message="Skill 抽取运行未完成，请稍后重试。",
                developer_message=str(exc) or exc.__class__.__name__,
            )
            await self._save_state(state)
            raise

    async def resume_candidate(
        self,
        builder_input: SkillBuilderInput,
        *,
        options: SkillBuilderOptions | None = None,
        hitl_confirmations: tuple[dict[str, Any], ...] = (),
        emit_event: EventEmitter | None = None,
        auto_validate: bool = False,
    ) -> SkillBuilderExecution:
        """Resume a committed package directly as a draft-ready execution."""

        resolved_options = options or SkillBuilderOptions(run_phase="workflow")
        builder_input = _bind_candidate_package_identity(builder_input)
        state = SkillBuilderState(
            input=builder_input,
            options=resolved_options,
            cursor=LifecycleCursor.CANDIDATE_COMMITTED,
            hitl_confirmations=hitl_confirmations,
            policy_version=resolved_options.policy_version,
        )
        lifecycle_io = SkillBuilderLifecycleIO(
            state=state,
            adapters=self.adapters,
            save_state=self._save_state,
            emit_event=emit_event,
            ask_user=None,
        )
        await self._save_state(state)
        receipt_status = verified_candidate_receipt_status(builder_input.root)
        status = receipt_status
        if not status.get("ok"):
            execution = execution_from_candidate_failure(
                state=state,
                options=resolved_options,
                agent_result=None,
                candidate_status=status,
            )
            apply_execution_to_state(state, execution)
            await self._save_state(state)
            return execution
        await lifecycle_io.emit(
            "agent.candidate_handoff_recovered",
            "已从持久化 PackageRevision 恢复 Skill 草稿。",
            {
                "path": status.get("path") or receipt_status.get("path"),
                "artifactSha256": receipt_status.get("expectedArtifactSha256"),
            },
        )
        return await self._commit_draft_and_persist(
            builder_input=builder_input,
            resolved_options=resolved_options,
            state=state,
            agent_result=None,
            emit_event=emit_event,
            auto_validate=auto_validate,
        )

    async def _commit_draft_and_persist(
        self,
        *,
        builder_input: SkillBuilderInput,
        resolved_options: SkillBuilderOptions,
        state: SkillBuilderState,
        agent_result: SkillBuilderAgentResult | None,
        emit_event: EventEmitter | None = None,
        auto_validate: bool = False,
    ) -> SkillBuilderExecution:
        """Persist one candidate and optionally run independent delivery validation."""

        builder_input = _bind_candidate_package_identity(builder_input)
        state.input = builder_input
        execution = execution_from_results(
            state=state,
            options=resolved_options,
            agent_result=agent_result,
            validation_result=None,
        )
        apply_execution_to_state(state, execution)
        await self._save_state(state)
        if not auto_validate or execution.status == SkillBuilderStatus.WAITING_FOR_USER:
            return execution
        return await self._validate_committed_package(
            builder_input,
            hitl_confirmations=state.hitl_confirmations,
            agent_result=agent_result,
            source_options=resolved_options,
            emit_event=emit_event,
        )

    async def validate(
        self,
        builder_input: SkillBuilderInput,
        *,
        hitl_confirmations: tuple[dict[str, Any], ...] = (),
    ) -> SkillBuilderExecution:
        return await self._validate_committed_package(
            builder_input,
            hitl_confirmations=hitl_confirmations,
        )

    async def _validate_committed_package(
        self,
        builder_input: SkillBuilderInput,
        *,
        hitl_confirmations: tuple[dict[str, Any], ...] = (),
        agent_result: SkillBuilderAgentResult | None = None,
        source_options: SkillBuilderOptions | None = None,
        emit_event: EventEmitter | None = None,
    ) -> SkillBuilderExecution:
        """Run the authoritative validation after the authoring worker exits."""

        builder_input = _bind_candidate_package_identity(builder_input)
        options = SkillBuilderOptions(
            run_phase="validation",
            policy_version=(
                source_options.policy_version
                if source_options is not None
                else SkillBuilderOptions().policy_version
            ),
            run_id=(source_options.run_id if source_options is not None else None),
        )
        state = SkillBuilderState(
            input=builder_input,
            options=options,
            cursor=LifecycleCursor.VALIDATING,
            hitl_confirmations=hitl_confirmations,
            policy_version=options.policy_version,
        )
        package_status = ensure_workspace_package_revision(builder_input.root)
        if not package_status.get("ok"):
            validation_result = (
                dict(package_status.get("validation"))
                if isinstance(package_status.get("validation"), dict)
                else validate_draft_package(builder_input.root).to_result()
            )
            execution = execution_from_results(
                state=state,
                options=state.options,
                agent_result=agent_result,
                validation_result=validation_result,
            )
            apply_execution_to_state(state, execution)
            await self._save_state(state)
            if emit_event is not None:
                await emit_event(
                    "skill.delivery_validation_completed",
                    "独立交付验收未通过，当前 Skill 需要修改。",
                    {
                        "status": validation_result.get("status"),
                        "validationPhase": "delivery",
                        "result": {**validation_result, "validationPhase": "delivery"},
                        "validationRevision": None,
                    },
                )
            return execution
        try:
            validation_result = acceptance_result_payload(
                await accept_skill_package(
                    builder_input.root,
                    execution_port=self.adapters.execution_port,
                    agent_self_check=_agent_self_check(agent_result),
                )
            )
            validation_result = reconcile_preflight_and_delivery(
                preflight_result=_candidate_preflight_for_artifact(
                    agent_result,
                    str(
                        package_status.get("expectedArtifactSha256")
                        or package_status.get("actualArtifactSha256")
                        or ""
                    ),
                ),
                delivery_result=validation_result,
            )
            persisted = persist_acceptance_files(
                builder_input.root,
                validation_result,
                _agent_self_check(agent_result),
            )
            if not persisted.get("ok"):
                raise OSError(
                    "delivery validation report persistence failed: "
                    + "; ".join(str(item) for item in persisted.get("errors") or [])
                )
            execution = execution_from_results(
                state=state,
                options=options,
                agent_result=agent_result,
                validation_result=validation_result,
            )
            revision = RevisionStore(builder_input.root).record_validation(
                validation_result,
                phase=_validation_terminal_phase(execution),
            )
        except Exception as exc:
            failure = ExecutionFailure(
                code="delivery_validation_runtime_failed",
                category="platform_runtime",
                retryable=True,
                repairable=False,
                user_message="独立交付验收未完成，请稍后验证当前版本。",
                developer_message=str(exc) or exc.__class__.__name__,
                details={"stage": "delivery_validation"},
            )
            execution = execution_from_operational_failure(
                state=state,
                options=options,
                failure=failure,
            )
            apply_execution_to_state(state, execution)
            await self._save_state(state)
            if emit_event is not None:
                await emit_event(
                    "skill.delivery_validation_failed",
                    failure.user_message,
                    {"status": "failed", "failure": failure.to_dict()},
                )
            return execution
        apply_execution_to_state(state, execution)
        await self._save_state(state)
        if emit_event is not None:
            await emit_event(
                "skill.delivery_validation_completed",
                str(
                    (validation_result.get("summary") or {}).get("message")
                    if isinstance(validation_result.get("summary"), dict)
                    else "独立交付验收已完成。"
                )[:500],
                {
                    "status": validation_result.get("status"),
                    "validationPhase": "delivery",
                    "result": {**validation_result, "validationPhase": "delivery"},
                    "validationRevision": revision.get("validationRevision"),
                },
            )
        return execution

    async def repair(
        self,
        execution: SkillBuilderExecution,
        *,
        instruction: str | None = None,
        emit_event: EventEmitter | None = None,
    ) -> SkillBuilderExecution:
        repair_message = instruction or (
            "请根据用户的修改要求编辑当前 Skill 草稿，"
            "完成后调用 finish_authoring 提交结构化自检摘要，由控制器执行完整预检。"
        )
        options = replace(
            execution.options,
            run_phase="author",
            policy_version=execution.policy_version,
        )
        backup_root = execution.input.root / ".skill-builder" / "edit-transactions" / uuid.uuid4().hex
        before = repair_artifact_snapshot(execution.input.root)
        copy_workspace_artifact_snapshot(execution.input.root, backup_root)
        state = SkillBuilderState(
            input=execution.input,
            options=options,
            cursor=LifecycleCursor.AUTHORING,
            hitl_confirmations=execution.hitl_confirmations,
            policy_version=execution.policy_version,
        )
        try:
            agent_result = await self._run_agent(
                builder_input=replace(execution.input, user_message=repair_message),
                options=options,
                emit_event=emit_event or _discard_event,
            )
            submission = candidate_submission_status(execution.input.root, agent_result)
            if hasattr(agent_result, "submission_status"):
                agent_result.submission_status = submission
            if not submission.get("ok"):
                restore_workspace_artifact_snapshot(execution.input.root, backup_root)
                result = replace(
                    execution,
                    agent_result=agent_result,
                    turn_result=TurnResult(
                        status=TurnStatus.ROLLED_BACK,
                        answer="本轮草稿修订未通过最小包校验，已保留修订前版本。",
                        changed_paths=(),
                        metadata={"submission": submission},
                    ),
                )
                await self._persist_execution(result)
                return result
            package_status = ensure_workspace_package_revision(execution.input.root)
            if not package_status.get("ok"):
                restore_workspace_artifact_snapshot(execution.input.root, backup_root)
                result = replace(
                    execution,
                    agent_result=agent_result,
                    turn_result=TurnResult(
                        status=TurnStatus.ROLLED_BACK,
                        answer="本轮草稿修订未形成有效 PackageRevision，已保留修订前版本。",
                        changed_paths=(),
                        metadata={"submission": package_status},
                    ),
                )
                await self._persist_execution(result)
                return result
            revised = execution_from_results(
                state=state,
                options=options,
                agent_result=agent_result,
                validation_result=None,
            )
            changed = tuple(
                sorted(
                    relevant_repair_artifact_files(
                        changed_repair_artifact_files(
                            before,
                            repair_artifact_snapshot(execution.input.root),
                        )
                    )
                )
            )
            revised = replace(
                revised,
                turn_result=TurnResult(
                    status=TurnStatus.CHANGES_APPLIED,
                    answer="Skill 草稿修订已形成新的 draft-ready 版本。",
                    changed_paths=changed,
                    metadata={"submission": submission},
                ),
            )
            apply_execution_to_state(state, revised)
            await self._save_state(state)
            validated = await self._validate_committed_package(
                execution.input,
                hitl_confirmations=execution.hitl_confirmations,
                agent_result=agent_result,
                source_options=options,
                emit_event=emit_event,
            )
            return replace(validated, turn_result=revised.turn_result)
        except Exception as exc:
            restore_workspace_artifact_snapshot(execution.input.root, backup_root)
            result = replace(
                execution,
                turn_result=TurnResult(
                    status=TurnStatus.FAILED,
                    answer="本轮草稿修订未完成，已保留修订前版本。",
                    changed_paths=(),
                    metadata={"error": str(exc)[:1000]},
                ),
            )
            await self._persist_execution(result)
            return result
        finally:
            discard_workspace_artifact_snapshot(backup_root)

    async def load_execution(self, workspace_id: str) -> SkillBuilderExecution | None:
        if self.adapters.state_store is None:
            raise RuntimeError("load_execution requires a SkillBuilderStateStore")
        state = await self.adapters.state_store.load(workspace_id)
        if state is None:
            return None
        return execution_from_state(state)

    async def invalidate_receipt(self, workspace_id: str) -> SkillBuilderExecution:
        """Commit an external edit as a new draft revision and clear validation."""

        if self.adapters.state_store is None:
            raise RuntimeError("invalidate_receipt requires a SkillBuilderStateStore")
        state = await self.adapters.state_store.load(workspace_id)
        if state is None:
            raise KeyError(f"Skill Builder workspace state not found: {workspace_id}")
        execution = execution_from_state(state)
        if execution.receipt_valid:
            return execution
        package_status = ensure_workspace_package_revision(state.input.root)
        current_sha256 = skill_artifact_sha256(state.input.root / "generated-skill")
        state.validation_result = None
        state.artifact_sha256 = current_sha256
        state.pending_request = None
        if package_status.get("ok"):
            state.input = _bind_candidate_package_identity(state.input)
            state.cursor = LifecycleCursor.DRAFT_READY
            state.failure = None
            revised = execution_from_results(
                state=state,
                options=replace(state.options, run_phase="edit"),
                agent_result=None,
                validation_result=None,
            )
        else:
            validation = package_status.get("validation")
            finding_ids = [
                str(item.get("id"))
                for item in (validation.get("findings") if isinstance(validation, dict) else []) or []
                if isinstance(item, dict) and item.get("id")
            ]
            state.cursor = LifecycleCursor.NEEDS_REVIEW
            state.failure = ExecutionFailure(
                code="draft_package_invalid",
                category="candidate_lifecycle",
                retryable=True,
                repairable=True,
                user_message="当前修改破坏了 Skill 最小包结构，请修复后重新验证。",
                developer_message=str(package_status.get("error") or "draft_package_invalid"),
                details={"finding_ids": finding_ids},
            )
            revised = execution_from_state(state)
        await self._persist_execution(revised)
        return revised

    async def resume(
        self,
        workspace_id: str,
        *,
        resume_token: str,
        answer: dict[str, Any],
        emit_event: EventEmitter | None = None,
        ask_user: HitlHandler | None = None,
    ) -> SkillBuilderExecution:
        if self.adapters.state_store is None:
            raise RuntimeError("resume requires a SkillBuilderStateStore")
        state = await self.adapters.state_store.load(workspace_id)
        if state is None:
            raise KeyError(f"Skill Builder workspace state not found: {workspace_id}")
        pending = state.pending_request
        if pending is None or state.status != SkillBuilderStatus.WAITING_FOR_USER:
            raise RuntimeError("Skill Builder workspace is not waiting for user input")
        if not secrets.compare_digest(pending.resume_token, resume_token):
            raise PermissionError("Skill Builder resume token does not match the pending request")
        if hitl_answer_is_deferred(answer):
            raise ValueError("Skill Builder resume requires a completed HITL answer")
        confirmation = build_hitl_confirmation(
            pending,
            answer,
            ordinal=len(state.hitl_confirmations) + 1,
            root=state.input.root,
        )
        confirmations = (*state.hitl_confirmations, confirmation)
        resumed_options = replace(
            state.options,
            run_phase="workflow",
        )
        # Validate and canonicalize the answer before claiming the durable
        # pending request.  A malformed decision form must remain resumable.
        state.pending_request = None
        state.status = SkillBuilderStatus.QUEUED
        state.hitl_confirmations = confirmations
        state.options = resumed_options
        await self._save_state(state)
        return await self.build(
            state.input,
            options=resumed_options,
            hitl_confirmations=confirmations,
            emit_event=emit_event,
            ask_user=ask_user,
        )

    async def resume_execution(
        self,
        execution: SkillBuilderExecution,
        *,
        resume_token: str,
        answer: dict[str, Any],
        emit_event: EventEmitter | None = None,
        ask_user: HitlHandler | None = None,
    ) -> SkillBuilderExecution:
        pending = execution.pending_request
        if pending is None or execution.status != SkillBuilderStatus.WAITING_FOR_USER:
            raise RuntimeError("Skill Builder execution is not waiting for user input")
        if not secrets.compare_digest(pending.resume_token, resume_token):
            raise PermissionError("Skill Builder resume token does not match the pending request")
        if hitl_answer_is_deferred(answer):
            raise ValueError("Skill Builder resume requires a completed HITL answer")
        confirmation = build_hitl_confirmation(
            pending,
            answer,
            ordinal=len(execution.hitl_confirmations) + 1,
            root=execution.input.root,
        )
        confirmations = (*execution.hitl_confirmations, confirmation)
        # The structured confirmation remains durable workflow context and is
        # not appended to the user's prose a second time.
        resumed_input = execution.input
        return await self.build(
            resumed_input,
            options=replace(execution.options, run_phase="workflow"),
            hitl_confirmations=confirmations,
            emit_event=emit_event,
            ask_user=ask_user,
        )

    def build_export_archive(self, execution: SkillBuilderExecution) -> tuple[bytes, str, str]:
        return build_execution_export_archive(execution)

    def build_publish_archive(
        self,
        execution: SkillBuilderExecution,
        *,
        author: str,
    ) -> tuple[bytes, str, str]:
        return build_execution_publish_archive(execution, author=author)

    async def _run_agent(
        self,
        *,
        builder_input: SkillBuilderInput,
        options: SkillBuilderOptions,
        emit_event: EventEmitter,
    ) -> SkillBuilderAgentResult:
        request = SkillBuilderAgentRequest(
            root=builder_input.root,
            workspace_id=builder_input.workspace_id,
            skill_name=builder_input.skill_name,
            display_name=builder_input.display_name,
            description=builder_input.description,
            version=builder_input.version,
            tags=builder_input.tags,
            user_message=builder_input.user_message,
            materials_markdown=builder_input.materials_markdown,
            emit_event=emit_event,
            run_phase=options.run_phase,
            workspace=self.adapters.workspace,
        )
        runner = self.adapters.agent_runner or OpenJiuwenPythonAgentAdapter()
        # The concrete runtime/host owns deadlines and activity-aware timeout
        # semantics.  A second outer asyncio timeout used to cancel healthy
        # a healthy Agent while the OpenJiuwen runtime was still streaming or
        # persisting a checkpoint.
        result = None
        error = None
        try:
            result = await runner.run(request)
            return result
        except Exception as exc:
            error = str(exc)
            raise
        finally:
            record_agent_core_run(
                builder_input.root,
                result,
                error,
                phase=options.run_phase,
                attempt=0,
            )

    async def _invoke_workflow_agent(
        self,
        builder_input: SkillBuilderInput,
        options: SkillBuilderOptions,
        lifecycle_io: SkillBuilderLifecycleIO,
    ) -> SkillBuilderAgentResult:
        return await self._run_agent(
            builder_input=builder_input,
            options=options,
            emit_event=lifecycle_io.emit,
        )

    async def _save_state(self, state: SkillBuilderState) -> None:
        if self.adapters.state_store is not None:
            await self.adapters.state_store.save(state)

    async def _persist_execution(self, execution: SkillBuilderExecution) -> None:
        state = SkillBuilderState(
            input=execution.input,
            options=execution.options,
            cursor=execution.cursor,
            pending_request=execution.pending_request,
            hitl_confirmations=execution.hitl_confirmations,
            validation_result=execution.validation_result,
            artifact_sha256=execution.artifact_sha256,
            policy_version=execution.policy_version,
            failure=execution.failure,
        )
        await self._save_state(state)

__all__ = ["SkillBuilderAdapters", "SkillBuilderEngine"]
