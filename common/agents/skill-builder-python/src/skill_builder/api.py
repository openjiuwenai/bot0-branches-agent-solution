# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Use-case-level public API for the standalone Skill Builder."""

from __future__ import annotations

from typing import Any
import json
from pathlib import Path

from skill_builder.application.builder import SkillBuilderEngine
from skill_builder.application.presentation_projection import project_execution_presentation
from skill_builder.application.recovery import RecoveryPromptContext, build_recovery_prompt
from skill_builder.application.hitl_form_contract import (
    DecisionFormAnswerError,
    normalize_decision_form_answer,
)
from skill_builder.spi import (
    SkillBuilderAdapters,
    SkillBuilderEventEmitter,
    SkillBuilderHitlHandler,
)
from skill_builder.types import (
    SkillBuilderArchive,
    SkillBuilderExecution,
    SkillBuilderInput,
    SkillBuilderOptions,
    PresentationProjection,
    SkillBuilderTurnRequest,
)
from skill_builder.domain.conversation import ConversationIntent
from skill_builder.runtime.serialization import json_safe


def _recovery_failure_context(root: Path) -> str:
    """Return bounded controller facts for a failed recovery attempt."""

    failure_path = root / "validation" / "diagnostics" / "candidate_lifecycle_failure.json"
    try:
        failure = json.loads(failure_path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError):
        return ""
    if not isinstance(failure, dict):
        return ""
    payload: dict[str, Any] = {
        key: failure.get(key)
        for key in ("phase", "error", "issues", "nextAction")
        if failure.get(key) not in (None, "", [], {})
    }
    if str(failure.get("phase") or "") == "scenario":
        draft_path = root / ".skill-builder" / "drafts" / "scenario" / "current.json"
        try:
            draft = json.loads(draft_path.read_text(encoding="utf-8"))
        except (OSError, TypeError, ValueError):
            draft = None
        if isinstance(draft, dict):
            payload["rejectedScenarioDraft"] = draft
    if not payload:
        return ""
    return json.dumps(
        json_safe(payload, max_text_length=1000),
        ensure_ascii=False,
        sort_keys=True,
        indent=2,
    )[:12000]


class SkillBuilderClient:
    """Stable facade over the internal lifecycle engine.

    The facade intentionally contains no host policy. A service, CLI, or Agent
    application supplies adapters and invokes the same use cases.
    """

    def __init__(self, *, adapters: SkillBuilderAdapters | None = None) -> None:
        self._engine = SkillBuilderEngine(adapters=adapters)

    async def reconcile(
        self,
        builder_input: SkillBuilderInput,
        *,
        options: SkillBuilderOptions | None = None,
        hitl_confirmations: tuple[dict[str, Any], ...] = (),
        resume_answer: dict[str, Any] | None = None,
        advance: bool = True,
        emit_event: SkillBuilderEventEmitter | None = None,
        ask_user: SkillBuilderHitlHandler | None = None,
    ) -> SkillBuilderExecution:
        return await self._engine.reconcile(
            builder_input,
            options=options,
            hitl_confirmations=hitl_confirmations,
            resume_answer=resume_answer,
            advance=advance,
            emit_event=emit_event,
            ask_user=ask_user,
        )

    async def build(
        self,
        builder_input: SkillBuilderInput,
        *,
        options: SkillBuilderOptions | None = None,
        hitl_confirmations: tuple[dict[str, Any], ...] = (),
        emit_event: SkillBuilderEventEmitter | None = None,
        ask_user: SkillBuilderHitlHandler | None = None,
    ) -> SkillBuilderExecution:
        return await self._engine.build(
            builder_input,
            options=options,
            hitl_confirmations=hitl_confirmations,
            emit_event=emit_event,
            ask_user=ask_user,
        )

    async def resume_candidate(
        self,
        builder_input: SkillBuilderInput,
        *,
        options: SkillBuilderOptions | None = None,
        hitl_confirmations: tuple[dict[str, Any], ...] = (),
        emit_event: SkillBuilderEventEmitter | None = None,
    ) -> SkillBuilderExecution:
        return await self._engine.resume_candidate(
            builder_input,
            options=options,
            hitl_confirmations=hitl_confirmations,
            emit_event=emit_event,
        )

    async def validate(
        self,
        builder_input: SkillBuilderInput,
        *,
        hitl_confirmations: tuple[dict[str, Any], ...] = (),
    ) -> SkillBuilderExecution:
        return await self._engine.validate(
            builder_input,
            hitl_confirmations=hitl_confirmations,
        )

    async def accept(
        self,
        builder_input: SkillBuilderInput,
        *,
        hitl_confirmations: tuple[dict[str, Any], ...] = (),
    ) -> SkillBuilderExecution:
        """Run Core acceptance; kept as a semantic alias for ``validate``."""

        return await self.validate(
            builder_input,
            hitl_confirmations=hitl_confirmations,
        )

    async def repair(
        self,
        execution: SkillBuilderExecution,
        *,
        instruction: str | None = None,
        emit_event: SkillBuilderEventEmitter | None = None,
    ) -> SkillBuilderExecution:
        return await self._engine.repair(
            execution,
            instruction=instruction,
            emit_event=emit_event,
        )

    async def load(self, execution_id: str) -> SkillBuilderExecution | None:
        return await self._engine.load_execution(execution_id)

    async def invalidate_receipt(self, execution_id: str) -> SkillBuilderExecution:
        return await self._engine.invalidate_receipt(execution_id)

    @staticmethod
    def present(execution: SkillBuilderExecution) -> PresentationProjection:
        return project_execution_presentation(execution)

    @staticmethod
    def build_recovery_message(
        execution: SkillBuilderExecution,
        *,
        kind: str,
        user_message: str | None = None,
        from_event_id: str | None = None,
    ) -> str:
        confirmations = json.dumps(
            list(execution.hitl_confirmations),
            ensure_ascii=False,
            sort_keys=True,
        )
        return build_recovery_prompt(
            kind=kind,
            context=RecoveryPromptContext(
                workspace_id=execution.workspace_id,
                workspace_status=execution.status.value,
                last_error=execution.error,
                last_message=execution.input.user_message,
                hitl_answers=confirmations,
                user_message=user_message,
                from_event_id=from_event_id,
                has_checkpoint=bool(execution.artifact_sha256),
                previous_failure_context=_recovery_failure_context(
                    execution.input.root
                ),
            ),
        )

    @staticmethod
    def normalize_hitl_answer(
        *,
        answer: dict[str, Any],
        fields: list[dict[str, Any]],
        message: str | None = None,
        default_value: str | None = None,
    ) -> dict[str, Any]:
        try:
            return normalize_decision_form_answer(
                answer=answer,
                fields=fields,
                message=message,
                default_value=default_value,
            )
        except DecisionFormAnswerError as exc:
            raise ValueError(str(exc)) from exc

    def plan_turn(
        self,
        execution: SkillBuilderExecution | SkillBuilderInput,
        request: SkillBuilderTurnRequest,
    ) -> ConversationIntent:
        return self._engine.plan_turn(execution, request)

    async def run_turn(
        self,
        execution_id: str,
        request: SkillBuilderTurnRequest,
        *,
        emit_event: SkillBuilderEventEmitter | None = None,
    ) -> SkillBuilderExecution:
        return await self._engine.run_turn(
            execution_id,
            request,
            emit_event=emit_event,
        )

    async def resume(
        self,
        execution_id: str,
        *,
        resume_token: str,
        answer: dict[str, Any],
        emit_event: SkillBuilderEventEmitter | None = None,
        ask_user: SkillBuilderHitlHandler | None = None,
    ) -> SkillBuilderExecution:
        return await self._engine.resume(
            execution_id,
            resume_token=resume_token,
            answer=answer,
            emit_event=emit_event,
            ask_user=ask_user,
        )

    async def resume_execution(
        self,
        execution: SkillBuilderExecution,
        *,
        resume_token: str,
        answer: dict[str, Any],
        emit_event: SkillBuilderEventEmitter | None = None,
        ask_user: SkillBuilderHitlHandler | None = None,
    ) -> SkillBuilderExecution:
        return await self._engine.resume_execution(
            execution,
            resume_token=resume_token,
            answer=answer,
            emit_event=emit_event,
            ask_user=ask_user,
        )

    def build_export_archive(self, execution: SkillBuilderExecution) -> SkillBuilderArchive:
        content, filename, sha256 = self._engine.build_export_archive(execution)
        return SkillBuilderArchive(content=content, filename=filename, sha256=sha256)

    def build_publish_archive(
        self,
        execution: SkillBuilderExecution,
        *,
        author: str,
    ) -> SkillBuilderArchive:
        content, filename, sha256 = self._engine.build_publish_archive(
            execution,
            author=author,
        )
        return SkillBuilderArchive(content=content, filename=filename, sha256=sha256)


async def run_skill_builder(
    *,
    builder_input: SkillBuilderInput,
    options: SkillBuilderOptions | None = None,
    hitl_confirmations: tuple[dict[str, Any], ...] = (),
    adapters: SkillBuilderAdapters | None = None,
    emit_event: SkillBuilderEventEmitter | None = None,
    ask_user: SkillBuilderHitlHandler | None = None,
) -> SkillBuilderExecution:
    return await SkillBuilderClient(adapters=adapters).build(
        builder_input,
        options=options,
        hitl_confirmations=hitl_confirmations,
        emit_event=emit_event,
        ask_user=ask_user,
    )


async def validate_skill_builder(
    *,
    builder_input: SkillBuilderInput,
    hitl_confirmations: tuple[dict[str, Any], ...] = (),
    adapters: SkillBuilderAdapters | None = None,
) -> SkillBuilderExecution:
    return await SkillBuilderClient(adapters=adapters).validate(
        builder_input,
        hitl_confirmations=hitl_confirmations,
    )


async def accept_skill_builder(
    *,
    builder_input: SkillBuilderInput,
    hitl_confirmations: tuple[dict[str, Any], ...] = (),
    adapters: SkillBuilderAdapters | None = None,
) -> SkillBuilderExecution:
    return await SkillBuilderClient(adapters=adapters).accept(
        builder_input,
        hitl_confirmations=hitl_confirmations,
    )


async def repair_skill_builder(
    *,
    execution: SkillBuilderExecution,
    instruction: str | None = None,
    adapters: SkillBuilderAdapters | None = None,
    emit_event: SkillBuilderEventEmitter | None = None,
) -> SkillBuilderExecution:
    return await SkillBuilderClient(adapters=adapters).repair(
        execution,
        instruction=instruction,
        emit_event=emit_event,
    )


async def resume_skill_builder(
    *,
    execution: SkillBuilderExecution,
    resume_token: str,
    answer: dict[str, Any],
    adapters: SkillBuilderAdapters | None = None,
    emit_event: SkillBuilderEventEmitter | None = None,
    ask_user: SkillBuilderHitlHandler | None = None,
) -> SkillBuilderExecution:
    return await SkillBuilderClient(adapters=adapters).resume_execution(
        execution,
        resume_token=resume_token,
        answer=answer,
        emit_event=emit_event,
        ask_user=ask_user,
    )


__all__ = [
    "SkillBuilderClient",
    "repair_skill_builder",
    "resume_skill_builder",
    "run_skill_builder",
    "validate_skill_builder",
]
