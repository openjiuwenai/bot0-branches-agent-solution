# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Small callable adapters for embedding the standalone lifecycle engine."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Awaitable, Callable

from skill_builder.ports import (
    SkillBuilderAgentRequest,
    SkillBuilderAgentResult,
)


AgentCallback = Callable[[SkillBuilderAgentRequest], Awaitable[SkillBuilderAgentResult]]


@dataclass(frozen=True, slots=True)
class CallableAgentRunner:
    callback: AgentCallback

    async def run(self, request: SkillBuilderAgentRequest) -> SkillBuilderAgentResult:
        return await self.callback(request)


__all__ = [
    "AgentCallback",
    "CallableAgentRunner",
]
