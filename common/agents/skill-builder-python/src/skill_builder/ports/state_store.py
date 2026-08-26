# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

from __future__ import annotations

from typing import Protocol

from skill_builder.domain.execution import SkillBuilderState


class SkillBuilderStateStore(Protocol):
    async def load(self, workspace_id: str) -> SkillBuilderState | None:
        ...

    async def save(self, state: SkillBuilderState) -> None:
        ...


__all__ = ["SkillBuilderStateStore"]
