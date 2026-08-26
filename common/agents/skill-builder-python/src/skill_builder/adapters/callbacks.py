# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Awaitable, Callable

from skill_builder.domain.execution import SkillBuilderPendingRequest


EventCallback = Callable[[str, str, dict[str, Any]], Awaitable[None]]
HitlCallback = Callable[[dict[str, Any]], Awaitable[dict[str, Any]]]


@dataclass(frozen=True, slots=True)
class CallbackEventSink:
    callback: EventCallback

    async def emit(self, event_type: str, summary: str, payload: dict[str, Any]) -> None:
        await self.callback(event_type, summary, payload)


@dataclass(frozen=True, slots=True)
class CallbackHitlProvider:
    callback: HitlCallback

    async def request(self, pending: SkillBuilderPendingRequest) -> dict[str, Any]:
        payload = {**pending.request, "resume_token": pending.resume_token}
        return await self.callback(payload)


__all__ = ["CallbackEventSink", "CallbackHitlProvider", "EventCallback", "HitlCallback"]
