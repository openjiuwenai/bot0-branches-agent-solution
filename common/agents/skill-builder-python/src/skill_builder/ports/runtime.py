# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Typed runtime ports for standalone Skill Builder generation."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, Awaitable, Callable, Protocol


SkillBuilderEventEmitter = Callable[
    [str, str, dict[str, Any] | None],
    Awaitable[None],
]
SkillBuilderHitlHandler = Callable[[dict[str, Any]], Awaitable[dict[str, Any]]]


class SkillBuilderWorkspacePort(Protocol):
    def create_accessor(self, *, root: Path, workspace_id: str, purpose: str) -> Any:
        ...


@dataclass(frozen=True, slots=True)
class SkillBuilderAgentRequest:
    root: Path
    workspace_id: str
    skill_name: str
    display_name: str
    description: str
    version: str
    tags: tuple[str, ...]
    user_message: str
    materials_markdown: str
    run_phase: str = "initial"
    timeout_seconds: int | None = None
    emit_event: SkillBuilderEventEmitter | None = None
    workspace: SkillBuilderWorkspacePort | None = None

    def core_kwargs(self) -> dict[str, Any]:
        return {
            "root": self.root,
            "workspace_id": self.workspace_id,
            "skill_name": self.skill_name,
            "display_name": self.display_name,
            "description": self.description,
            "version": self.version,
            "tags": list(self.tags),
            "user_message": self.user_message,
            "materials_markdown": self.materials_markdown,
            "emit_event": self.emit_event,
            "run_phase": self.run_phase,
            "timeout_seconds": self.timeout_seconds,
            "workspace": self.workspace,
        }


class SkillBuilderAgentResult(Protocol):
    raw_output_text: str
    session_id: str
    files_read: list[str]
    files_listed: list[str]
    files_written: list[str]
    final_response: dict[str, Any] | None


class SkillBuilderAgentRunner(Protocol):
    async def run(self, request: SkillBuilderAgentRequest) -> SkillBuilderAgentResult:
        ...


__all__ = [
    "SkillBuilderAgentRequest",
    "SkillBuilderAgentResult",
    "SkillBuilderAgentRunner",
    "SkillBuilderEventEmitter",
    "SkillBuilderHitlHandler",
    "SkillBuilderWorkspacePort",
]
