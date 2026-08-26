# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Event and HITL interaction for one lifecycle run."""

from __future__ import annotations

import secrets
from typing import Any, Awaitable, Callable

from skill_builder.application.configuration import SkillBuilderAdapters
from skill_builder.application.execution_state import (
    build_hitl_confirmation,
    hitl_answer_is_deferred,
)
from skill_builder.application.hitl_form_contract import (
    DecisionFormAnswerError,
    public_decision_form_fields,
)
from skill_builder.domain.execution import (
    LifecycleCursor,
    SkillBuilderPendingRequest,
    SkillBuilderState,
    SkillBuilderStatus,
)


EventEmitter = Callable[[str, str, dict[str, Any] | None], Awaitable[None]]
HitlHandler = Callable[[dict[str, Any]], Awaitable[dict[str, Any]]]
StateSaver = Callable[[SkillBuilderState], Awaitable[None]]


class SkillBuilderLifecycleIO:
    def __init__(
        self,
        *,
        state: SkillBuilderState,
        adapters: SkillBuilderAdapters,
        save_state: StateSaver,
        emit_event: EventEmitter | None,
        ask_user: HitlHandler | None,
    ) -> None:
        self.state = state
        self.adapters = adapters
        self.save_state = save_state
        self.emit_event = emit_event
        self.ask_user = ask_user

    async def emit(
        self,
        event_type: str,
        summary: str,
        payload: dict[str, Any] | None = None,
    ) -> None:
        normalized_payload = payload or {}
        if self.emit_event is not None:
            await self.emit_event(event_type, summary, normalized_payload)
        if self.adapters.event_sink is not None:
            await self.adapters.event_sink.emit(event_type, summary, normalized_payload)

    async def set_cursor(self, cursor: LifecycleCursor) -> None:
        if self.state.cursor == cursor:
            return
        self.state.cursor = cursor
        await self.save_state(self.state)

    async def request_user(self, payload: dict[str, Any]) -> dict[str, Any]:
        payload = dict(payload)
        if str(payload.get("kind") or "").strip().lower() == "decision_form":
            payload["options"] = public_decision_form_fields(payload.get("options"))
        pending = SkillBuilderPendingRequest(
            request=dict(payload),
            resume_token=secrets.token_urlsafe(24),
        )
        self.state.status = SkillBuilderStatus.WAITING_FOR_USER
        self.state.pending_request = pending
        await self.save_state(self.state)
        if self.ask_user is not None:
            answer = await self.ask_user({**payload, "resume_token": pending.resume_token})
        elif self.adapters.hitl_provider is not None:
            answer = await self.adapters.hitl_provider.request(pending)
        else:
            answer = {
                "status": "waiting_for_user",
                "resume_token": pending.resume_token,
                "message": "HITL provider is not configured; resume with the returned token.",
            }
        if not hitl_answer_is_deferred(answer):
            try:
                confirmation = build_hitl_confirmation(
                    pending,
                    answer,
                    ordinal=len(self.state.hitl_confirmations) + 1,
                    root=self.state.input.root,
                )
            except DecisionFormAnswerError as exc:
                await self.emit(
                    "hitl.answer_rejected",
                    "人工确认答案不完整，流程仍停留在当前确认点。",
                    {
                        "error": "hitl_answer_invalid",
                        "detail": str(exc),
                        "resume_token": pending.resume_token,
                    },
                )
                return {
                    "status": "waiting_for_user",
                    "error": "hitl_answer_invalid",
                    "message": str(exc),
                    "resume_token": pending.resume_token,
                }
            self.state.hitl_confirmations = (*self.state.hitl_confirmations, confirmation)
            self.state.status = SkillBuilderStatus.RUNNING
            self.state.pending_request = None
            await self.save_state(self.state)
        return answer


__all__ = [
    "EventEmitter",
    "HitlHandler",
    "SkillBuilderLifecycleIO",
]
