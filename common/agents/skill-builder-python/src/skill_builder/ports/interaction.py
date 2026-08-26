# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

from __future__ import annotations

from typing import Any, Protocol

from skill_builder.domain.execution import SkillBuilderPendingRequest


class SkillBuilderEventSink(Protocol):
    async def emit(self, event_type: str, summary: str, payload: dict[str, Any]) -> None:
        ...


class SkillBuilderHitlProvider(Protocol):
    async def request(self, pending: SkillBuilderPendingRequest) -> dict[str, Any]:
        ...


__all__ = ["SkillBuilderEventSink", "SkillBuilderHitlProvider"]
