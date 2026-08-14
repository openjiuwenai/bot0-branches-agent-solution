"""DimensionOrchestrator 单元测试 — 并发上限 / fail-fast / on_progress / 维度名传递。"""

from __future__ import annotations

import asyncio
from typing import Any

import pytest

from evo_agent.evaluator.agent_judge.dimensions import JudgeDimension, get_dimension
from evo_agent.evaluator.agent_judge.orchestrator import DimensionOrchestrator
from evo_agent.evaluator.agent_judge.schemas import DimensionJudgment
from evo_agent.evaluator.domain.scoring import EvaluationError


class _FakeRuntime:
    """记录每次 judge 调用；可注入失败维度 / 延迟。"""

    def __init__(
        self,
        *,
        score: float = 0.8,
        fail_dim: str | None = None,
        delay: float = 0.0,
    ) -> None:
        self._score = score
        self._fail_dim = fail_dim
        self._delay = delay
        self.requests: list[Any] = []
        self.started: list[str] = []
        self.completed: list[str] = []
        self.finished: list[str] = []
        self.in_flight = 0
        self.max_in_flight = 0

    async def judge(self, request: Any) -> DimensionJudgment:
        self.requests.append(request)
        self.started.append(request.dimension_name)
        self.in_flight += 1
        self.max_in_flight = max(self.max_in_flight, self.in_flight)
        try:
            if request.dimension_name == self._fail_dim:
                raise EvaluationError(
                    category="agent_judge_dim_failed",
                    safe_message=f"forced fail for {request.dimension_name}",
                )
            if self._delay:
                await asyncio.sleep(self._delay)
            self.finished.append(request.dimension_name)
            return DimensionJudgment(
                dimension=request.dimension_name, score=self._score, reasoning="ok"
            )
        finally:
            self.in_flight -= 1
            self.completed.append(request.dimension_name)


def _dims(names: tuple[str, ...]) -> list[JudgeDimension]:
    return [get_dimension(n) for n in names]


def _prompt_builder(d: JudgeDimension) -> str:
    return f"prompt:{d.name}"


_DEFAULT = (
    "task_completion",
    "trajectory_quality",
    "safety",
    "answer_faithfulness",
    "planning_rationality",
)


class TestDimensionOrchestrator:
    def test_runs_all_and_stamps_names(self) -> None:
        runtime = _FakeRuntime(score=0.7)
        orch = DimensionOrchestrator(runtime, max_concurrent=6)
        judgments = asyncio.run(
            orch.run(
                _dims(_DEFAULT),
                _prompt_builder,
                workdir=__import__("pathlib").Path("/tmp"),
                schema_path=__import__("pathlib").Path("/tmp/s.json"),
                tool_allowlist=("Read", "Grep"),
            )
        )
        assert len(judgments) == len(_DEFAULT)
        # each judgment carries the requested dimension name (passed through)
        assert {j.dimension for j in judgments} == set(_DEFAULT)
        # runtime received the right dimension_name per request
        assert [r.dimension_name for r in runtime.requests]  # populated
        assert set(r.dimension_name for r in runtime.requests) == set(_DEFAULT)

    def test_on_progress_monotonic(self) -> None:
        runtime = _FakeRuntime()
        progress: list[tuple[int, int]] = []
        orch = DimensionOrchestrator(
            runtime, max_concurrent=6, on_progress=lambda d, t: progress.append((d, t))
        )
        asyncio.run(
            orch.run(
                _dims(_DEFAULT),
                _prompt_builder,
                workdir=__import__("pathlib").Path("/tmp"),
                schema_path=__import__("pathlib").Path("/tmp/s.json"),
                tool_allowlist=("Read",),
            )
        )
        assert progress  # fired at least once
        assert progress[-1] == (len(_DEFAULT), len(_DEFAULT))
        # done is monotonic non-decreasing
        dones = [d for d, _ in progress]
        assert dones == sorted(dones)
        assert all(t == len(_DEFAULT) for _, t in progress)

    def test_max_concurrent_bound(self) -> None:
        runtime = _FakeRuntime(delay=0.03)
        orch = DimensionOrchestrator(runtime, max_concurrent=2)
        asyncio.run(
            orch.run(
                _dims(_DEFAULT),
                _prompt_builder,
                workdir=__import__("pathlib").Path("/tmp"),
                schema_path=__import__("pathlib").Path("/tmp/s.json"),
                tool_allowlist=("Read",),
            )
        )
        assert runtime.max_in_flight <= 2

    def test_fail_fast_raises_and_cancels_rest(self) -> None:
        # failing dim raises instantly; others sleep — gather must cancel them.
        runtime = _FakeRuntime(fail_dim="safety", delay=0.05)
        orch = DimensionOrchestrator(runtime, max_concurrent=6)
        with pytest.raises(EvaluationError, match="forced fail"):
            asyncio.run(
                orch.run(
                    _dims(_DEFAULT),
                    _prompt_builder,
                    workdir=__import__("pathlib").Path("/tmp"),
                    schema_path=__import__("pathlib").Path("/tmp/s.json"),
                    tool_allowlist=("Read",),
                )
            )
        # not all dims finished normally (the sleepers were cancelled before return)
        assert len(runtime.finished) < len(_DEFAULT)

    def test_empty_dimensions_raises(self) -> None:
        runtime = _FakeRuntime()
        orch = DimensionOrchestrator(runtime)
        with pytest.raises(EvaluationError, match="no dimensions"):
            asyncio.run(
                orch.run(
                    [],
                    _prompt_builder,
                    workdir=__import__("pathlib").Path("/tmp"),
                    schema_path=__import__("pathlib").Path("/tmp/s.json"),
                    tool_allowlist=("Read",),
                )
            )

    def test_non_evaluation_error_wrapped(self) -> None:
        class _BoomRuntime:
            async def judge(self, request: Any) -> DimensionJudgment:  # noqa: ARG002
                raise RuntimeError("unexpected boom")

        orch = DimensionOrchestrator(_BoomRuntime(), max_concurrent=6)  # type: ignore[arg-type]
        with pytest.raises(EvaluationError, match="dimension run failed") as exc_info:
            asyncio.run(
                orch.run(
                    _dims(("task_completion",)),
                    _prompt_builder,
                    workdir=__import__("pathlib").Path("/tmp"),
                    schema_path=__import__("pathlib").Path("/tmp/s.json"),
                    tool_allowlist=("Read",),
                )
            )
        assert exc_info.value.category == "agent_judge_dim_failed"
