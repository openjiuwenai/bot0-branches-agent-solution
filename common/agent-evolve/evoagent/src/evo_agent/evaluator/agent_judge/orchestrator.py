"""Per-dimension orchestration — spawn the judge runtime once per dimension.

The orchestrator runs one :class:`JudgeAgentRuntime` ``judge()`` call per
dimension, bounded by a concurrency semaphore and a per-run timeout, and
**fails fast**: the first dimension to raise propagates and cancels the rest
(``asyncio.gather`` without ``return_exceptions``). An ``on_progress(done,
total)`` callback fires as each dimension completes so the HTTP route can push
coarse SSE progress events.

The runtime is responsible for stamping ``DimensionJudgment.dimension`` from
the requested dimension name (the agent's echoed label is not trusted).
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from pathlib import Path

from evo_agent.evaluator.agent_judge.dimensions import JudgeDimension
from evo_agent.evaluator.agent_judge.runtime import (
    JudgeAgentRuntime,
    RuntimeJudgeRequest,
)
from evo_agent.evaluator.agent_judge.schemas import DimensionJudgment
from evo_agent.evaluator.domain.scoring import EvaluationError

logger = logging.getLogger(__name__)

__all__ = ["DimensionOrchestrator"]


class DimensionOrchestrator:
    """Run all dimensions concurrently, fail-fast, with progress reporting."""

    def __init__(
        self,
        runtime: JudgeAgentRuntime,
        *,
        max_concurrent: int = 6,
        run_timeout: float = 300.0,
        on_progress: Callable[[int, int], None] | None = None,
    ) -> None:
        self._runtime = runtime
        self._max_concurrent = max_concurrent
        self._run_timeout = run_timeout
        self._on_progress = on_progress

    async def run(
        self,
        dimensions: list[JudgeDimension],
        prompt_builder: Callable[[JudgeDimension], str],
        *,
        workdir: Path,
        schema_path: Path,
        tool_allowlist: tuple[str, ...],
    ) -> list[DimensionJudgment]:
        """Judge every dimension; raise ``EvaluationError`` on the first failure."""
        if not dimensions:
            raise EvaluationError(
                category="agent_judge_dim_failed",
                safe_message="preset has no dimensions to judge",
            )
        semaphore = asyncio.Semaphore(self._max_concurrent)
        completed = 0
        total = len(dimensions)

        async def _run_one(dimension: JudgeDimension) -> DimensionJudgment:
            nonlocal completed
            request = RuntimeJudgeRequest(
                dimension_name=dimension.name,
                prompt=prompt_builder(dimension),
                workdir=workdir,
                schema_path=schema_path,
                tool_allowlist=tool_allowlist,
                run_timeout=self._run_timeout,
            )
            async with semaphore:
                judgment = await self._runtime.judge(request)
            completed += 1
            if self._on_progress is not None:
                self._on_progress(completed, total)
            return judgment

        tasks = [asyncio.create_task(_run_one(dim)) for dim in dimensions]
        try:
            return await asyncio.gather(*tasks)
        except Exception as exc:
            raise EvaluationError(
                category="agent_judge_dim_failed",
                safe_message=f"dimension run failed: {exc}",
            ) from exc
