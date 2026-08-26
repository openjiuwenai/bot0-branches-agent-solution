# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Default OpenJiuwen Python Agent implementation of the Agent runner port.

The base package can be imported, validated and packaged without OpenJiuwen.
This adapter imports the concrete Agent runtime only when generation is
actually requested, so it belongs to the optional ``agent-openjiuwen-python``
extra rather than the standalone core dependency set.
"""

from __future__ import annotations

from skill_builder.ports.runtime import SkillBuilderAgentRequest, SkillBuilderAgentResult


class OpenJiuwenPythonAgentAdapter:
    async def run(self, request: SkillBuilderAgentRequest) -> SkillBuilderAgentResult:
        from skill_builder.application.agent_core import run_skill_builder_agent_core

        return await run_skill_builder_agent_core(**request.core_kwargs())


__all__ = ["OpenJiuwenPythonAgentAdapter"]
