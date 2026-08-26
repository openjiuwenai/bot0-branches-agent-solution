"""Execution capability supplied by a host, not owned by Skill Builder."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping, Protocol


@dataclass(frozen=True, slots=True)
class ExecutionRequest:
    """One bounded offline command planned by Core acceptance."""

    command: tuple[str, ...]
    cwd: Path
    timeout_seconds: int = 60
    env: Mapping[str, str] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class ExecutionResult:
    """Normalized result returned by a host sandbox/execution adapter."""

    exit_code: int | None
    stdout: str = ""
    stderr: str = ""
    timed_out: bool = False
    duration_seconds: float = 0.0


class SkillBuilderExecutionPort(Protocol):
    async def run(self, request: ExecutionRequest) -> ExecutionResult:
        """Run one Core-planned command in the host's bounded sandbox."""


__all__ = ["ExecutionRequest", "ExecutionResult", "SkillBuilderExecutionPort"]
