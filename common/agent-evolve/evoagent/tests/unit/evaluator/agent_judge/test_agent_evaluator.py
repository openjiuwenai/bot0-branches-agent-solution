"""AgentEvaluator 端到端单元测试 — fake runtime + fake aggregator。

覆盖 evaluate() 的校验段、桥 reason JSON（含复数归因 + top-1 派生 is_pass）、
workdir 清理、错误传播。不跑真实子进程 / LLM。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from openjiuwen.agent_evolving.dataset import Case

from evo_agent.evaluator.agent_judge.presets import get_preset
from evo_agent.evaluator.agent_judge.schemas import (
    AggregatorOutput,
    DimensionJudgment,
    SkillAttribution,
)
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.evaluators.agent import AgentEvaluator, _select_top1_attribution

_PLACEHOLDER = {"evaluation_source": "conversation_trajectory"}
_TRAJECTORY = {
    "messages": [{"role": "user", "content": "do it"}, {"role": "assistant", "content": "done"}]
}


class _FakeRuntime:
    def __init__(self, score: float = 0.8) -> None:
        self._score = score
        self.requests: list[Any] = []

    async def judge(self, request: Any) -> DimensionJudgment:
        self.requests.append(request)
        return DimensionJudgment(
            dimension=request.dimension_name, score=self._score, reasoning="ok"
        )


class _RecordingRuntime:
    """Captures the workdir ``*.md`` files seen per dimension during judge()."""

    def __init__(self, score: float = 0.8) -> None:
        self._score = score
        self.materialized: list[tuple[str, list[str]]] = []

    async def judge(self, request: Any) -> DimensionJudgment:
        md_files = sorted(p.name for p in request.workdir.glob("*.md"))
        self.materialized.append((request.dimension_name, md_files))
        return DimensionJudgment(
            dimension=request.dimension_name, score=self._score, reasoning="ok"
        )


class _FakeAggregator:
    def __init__(self, output: AggregatorOutput, *, raise_exc: BaseException | None = None) -> None:
        self._output = output
        self._raise = raise_exc
        self.last_skill_names: list[str] | None = None
        self.last_judgments: list[Any] | None = None

    def aggregate(
        self,
        judgments: list[Any],
        *,
        skill_names: list[str],
        weights: dict[str, float],
        case_id: str,
    ) -> AggregatorOutput:
        self.last_judgments = judgments
        self.last_skill_names = skill_names
        if self._raise is not None:
            raise self._raise
        return self._output


def _make_evaluator(
    *,
    runtime: _FakeRuntime,
    aggregator: _FakeAggregator,
    workdir_base: str | None = None,
) -> AgentEvaluator:
    return AgentEvaluator(
        preset=get_preset("default"),
        runtime=runtime,  # type: ignore[arg-type]
        aggregator=aggregator,  # type: ignore[arg-type]
        workdir_base=workdir_base,
    )


def _case(
    *,
    trajectory: Any = _TRAJECTORY,
    skill_names: list[str] | None = None,
) -> Case:
    if skill_names is None:
        skill_names = ["alpha_skill"]
    inputs: dict[str, Any] = {}
    if trajectory is not None:
        inputs["trajectory"] = trajectory
    if skill_names:
        inputs["skill_names"] = skill_names
    return Case(inputs=inputs, label={"expected_result": None})


def _agg(
    *,
    overall_score: float = 0.75,
    attributions: list[SkillAttribution] | None = None,
    status: str = "completed",
    error: str | None = None,
) -> AggregatorOutput:
    return AggregatorOutput(
        overall_score=overall_score,
        skill_attributions=attributions or [],
        attribution_status=status,  # type: ignore[arg-type]
        attribution_error=error,
    )


class TestAgentEvaluatorEvaluate:
    def test_success_builds_reason_blob(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime(score=0.8)
        aggregator = _FakeAggregator(
            _agg(
                overall_score=0.75,
                attributions=[
                    SkillAttribution(skill_name="alpha_skill", impact="positive", reason="r")
                ],
            )
        )
        ev = _make_evaluator(runtime=runtime, aggregator=aggregator, workdir_base=str(tmp_path))
        evaluated = ev.evaluate(_case(), _PLACEHOLDER)

        assert evaluated.score == 0.75
        # per_metric has all 5 preset dims at the runtime's score
        dims = get_preset("default").dimensions
        assert evaluated.per_metric is not None
        assert set(evaluated.per_metric) == set(dims)
        assert all(v == 0.8 for v in evaluated.per_metric.values())

        blob = json.loads(evaluated.reason)
        assert blob["is_pass"] is True  # 0.75 >= 0.6
        assert blob["attributed_skill"] == "alpha_skill"  # top-1 decisive
        assert blob["attribution_status"] == "completed"
        assert blob["skill_attributions"][0]["skill_name"] == "alpha_skill"
        assert set(blob["dimensions"]) == set(dims)
        assert blob["repaired"] is False
        # aggregator received the runtime's judgments + the request skill_names
        assert aggregator.last_skill_names == ["alpha_skill"]
        assert aggregator.last_judgments is not None and len(aggregator.last_judgments) == len(dims)

    def test_is_pass_below_threshold(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime(score=0.2)
        aggregator = _FakeAggregator(_agg(overall_score=0.4))
        ev = _make_evaluator(runtime=runtime, aggregator=aggregator, workdir_base=str(tmp_path))
        evaluated = ev.evaluate(_case(), _PLACEHOLDER)
        blob = json.loads(evaluated.reason)
        assert blob["is_pass"] is False  # 0.4 < 0.6

    def test_top1_negative_is_decisive(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime(score=0.5)
        aggregator = _FakeAggregator(
            _agg(
                overall_score=0.5,
                attributions=[
                    SkillAttribution(skill_name="neutral_skill", impact="neutral"),
                    SkillAttribution(skill_name="alpha_skill", impact="negative"),
                ],
            )
        )
        ev = _make_evaluator(runtime=runtime, aggregator=aggregator, workdir_base=str(tmp_path))
        evaluated = ev.evaluate(_case(), _PLACEHOLDER)
        blob = json.loads(evaluated.reason)
        assert blob["attributed_skill"] == "alpha_skill"  # negative beats neutral-first

    def test_top1_empty_when_no_attributions(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime(score=0.5)
        aggregator = _FakeAggregator(_agg(overall_score=0.5, attributions=[]))
        ev = _make_evaluator(runtime=runtime, aggregator=aggregator, workdir_base=str(tmp_path))
        evaluated = ev.evaluate(_case(), _PLACEHOLDER)
        blob = json.loads(evaluated.reason)
        assert blob["attributed_skill"] == ""

    def test_empty_trajectory_raises(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime()
        aggregator = _FakeAggregator(_agg())
        ev = _make_evaluator(runtime=runtime, aggregator=aggregator, workdir_base=str(tmp_path))
        with pytest.raises(EvaluationError, match="Trace unavailable"):
            ev.evaluate(_case(trajectory={"messages": []}), _PLACEHOLDER)

    def test_missing_skill_names_raises(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime()
        aggregator = _FakeAggregator(_agg())
        ev = _make_evaluator(runtime=runtime, aggregator=aggregator, workdir_base=str(tmp_path))
        with pytest.raises(EvaluationError, match="skill_names is required"):
            ev.evaluate(_case(skill_names=[]), _PLACEHOLDER)

    def test_missing_trajectory_raises(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime()
        aggregator = _FakeAggregator(_agg())
        ev = _make_evaluator(runtime=runtime, aggregator=aggregator, workdir_base=str(tmp_path))
        with pytest.raises(ValueError, match="trajectory"):
            ev.evaluate(_case(trajectory=None), _PLACEHOLDER)

    def test_rollout_error_raises(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime()
        aggregator = _FakeAggregator(_agg())
        ev = _make_evaluator(runtime=runtime, aggregator=aggregator, workdir_base=str(tmp_path))
        with pytest.raises(EvaluationError) as exc_info:
            ev.evaluate(_case(), {"error": "rollout exploded"})
        assert exc_info.value.category == "rollout_error"

    def test_aggregator_error_propagates(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime(score=0.8)
        aggregator = _FakeAggregator(
            _agg(),
            raise_exc=EvaluationError(category="attribution_unknown_skill", safe_message="ghost"),
        )
        ev = _make_evaluator(runtime=runtime, aggregator=aggregator, workdir_base=str(tmp_path))
        with pytest.raises(EvaluationError) as exc_info:
            ev.evaluate(_case(), _PLACEHOLDER)
        assert exc_info.value.category == "attribution_unknown_skill"

    def test_workdir_cleaned_after_success(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime(score=0.8)
        aggregator = _FakeAggregator(_agg())
        ev = _make_evaluator(runtime=runtime, aggregator=aggregator, workdir_base=str(tmp_path))
        ev.evaluate(_case(), _PLACEHOLDER)
        # no leftover workdir dirs in tmp_path after a clean run
        leftovers = [p for p in tmp_path.iterdir() if p.name.startswith("evo-agent-judge-")]
        assert leftovers == []

    def test_per_dimension_skills_materialized(self, tmp_path: Path) -> None:
        runtime = _RecordingRuntime(score=0.8)
        aggregator = _FakeAggregator(_agg())
        ev = _make_evaluator(
            runtime=runtime,  # type: ignore[arg-type]
            aggregator=aggregator,
            workdir_base=str(tmp_path),
        )
        ev.evaluate(_case(), _PLACEHOLDER)
        # preset global helper (judge_rubric_guide) + the faithfulness dim's own
        # skill (faithfulness_checklist) are both materialized into the shared workdir
        all_md = {f for _, files in runtime.materialized for f in files}
        assert "judge_rubric_guide.md" in all_md
        assert "faithfulness_checklist.md" in all_md
        dims_run = {d for d, _ in runtime.materialized}
        assert "answer_faithfulness" in dims_run


class TestSelectTop1:
    def test_prefers_positive(self) -> None:
        attrs = [
            SkillAttribution(skill_name="a", impact="neutral"),
            SkillAttribution(skill_name="b", impact="positive"),
        ]
        assert _select_top1_attribution(attrs) == "b"

    def test_prefers_negative(self) -> None:
        attrs = [
            SkillAttribution(skill_name="a", impact="neutral"),
            SkillAttribution(skill_name="b", impact="negative"),
        ]
        assert _select_top1_attribution(attrs) == "b"

    def test_falls_back_to_first(self) -> None:
        attrs = [
            SkillAttribution(skill_name="a", impact="neutral"),
            SkillAttribution(skill_name="b", impact="none"),
        ]
        assert _select_top1_attribution(attrs) == "a"

    def test_empty_returns_empty(self) -> None:
        assert _select_top1_attribution([]) == ""
