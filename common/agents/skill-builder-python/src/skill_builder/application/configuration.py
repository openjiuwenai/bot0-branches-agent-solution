# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Optional host configuration for the standalone lifecycle engine."""

from __future__ import annotations

from dataclasses import dataclass
from skill_builder.ports import (
    SkillBuilderAgentRunner,
    SkillBuilderEventSink,
    SkillBuilderHitlProvider,
    SkillBuilderStateStore,
    SkillBuilderWorkspacePort,
    SkillBuilderExecutionPort,
)


@dataclass(frozen=True, slots=True)
class SkillBuilderAdapters:
    """Advanced extension points; normal API calls do not require this object."""

    state_store: SkillBuilderStateStore | None = None
    event_sink: SkillBuilderEventSink | None = None
    hitl_provider: SkillBuilderHitlProvider | None = None
    agent_runner: SkillBuilderAgentRunner | None = None
    workspace: SkillBuilderWorkspacePort | None = None
    execution_port: SkillBuilderExecutionPort | None = None


__all__ = ["SkillBuilderAdapters"]
