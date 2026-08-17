"""AgentEvaluator 端到端单元测试 — fake runtime (judge + synthesize) + fake scorer。

覆盖 evaluate() 的校验段、桥 reason JSON（含复数归因 + top-1 派生 is_pass）、
workdir 清理、归因 Agent 在 with 块内运行、未知 skill fail-fast、错误传播。
不跑真实子进程。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from openjiuwen.agent_evolving.dataset import Case

from evo_agent.evaluator.agent_judge.presets import get_preset
from evo_agent.evaluator.agent_judge.schemas import (
    DimensionJudgment,
    SkillAttribution,
)
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.evaluators.agent import AgentEvaluator, _select_top1_attribution

_PLACEHOLDER = {"evaluation_source": "conversation_trajectory"}
_TRAJECTORY = {
    "messages": [{"role": "user", "content": "do it"}, {"role": "assistant", "content": "done"}]
}


def _attr_dict(
    *,
    attributions: list[SkillAttribution] | None = None,
    status: str = "completed",
    error: str | None = None,
) -> dict[str, Any]:
    """Raw dict the attribution-agent subprocess would emit (parsed by parse_attribution_output)."""
    return {
        "skill_attributions": [a.model_dump() for a in (attributions or [])],
        "attribution_status": status,
        "attribution_error": error,
    }


class _FakeRuntime:
    """Fake judge + attribution runtime: fixed per-dim score + a canned attribution dict."""

    def __init__(
        self,
        score: float = 0.8,
        *,
        attr_dict: dict[str, Any] | None = None,
        synthesize_exc: BaseException | None = None,
    ) -> None:
        self._score = score
        self._attr_dict = attr_dict if attr_dict is not None else _attr_dict()
        self._synthesize_exc = synthesize_exc
        self.judge_requests: list[Any] = []
        self.synthesize_request: Any | None = None
        self.synthesize_saw_trajectory: bool | None = None

    async def judge(self, request: Any) -> DimensionJudgment:
        self.judge_requests.append(request)
        return DimensionJudgment(
            dimension=request.dimension_name, score=self._score, reasoning="ok"
        )

    async def synthesize(self, request: Any) -> dict[str, Any]:
        # Runs inside the open workdir — verify the trajectory is readable here.
        self.synthesize_request = request
        self.synthesize_saw_trajectory = (request.workdir / "trajectory.md").exists()
        if self._synthesize_exc is not None:
            raise self._synthesize_exc
        return self._attr_dict


class _RecordingRuntime(_FakeRuntime):
    """Also captures the workdir ``*.md`` files seen per dimension during judge()."""

    def __init__(self, score: float = 0.8, **kwargs: Any) -> None:
        super().__init__(score, **kwargs)
        self.materialized: list[tuple[str, list[str]]] = []

    async def judge(self, request: Any) -> DimensionJudgment:
        md_files = sorted(p.name for p in request.workdir.glob("*.md"))
        self.materialized.append((request.dimension_name, md_files))
        return DimensionJudgment(
            dimension=request.dimension_name, score=self._score, reasoning="ok"
        )


class _FakeScorer:
    """Removed — WeightScorer no longer used. Kept as placeholder for test compatibility."""

    pass


_DEFAULT_THRESHOLDS = {
    "task_completion": 0.5,
    "trajectory_quality": 0.5,
    "safety": 0.5,
    "answer_faithfulness": 0.5,
    "planning_rationality": 0.5,
}


def _make_evaluator(
    *,
    runtime: _FakeRuntime,
    workdir_base: str | None = None,
    dimension_thresholds: dict[str, float] | None = None,
) -> AgentEvaluator:
    return AgentEvaluator(
        preset=get_preset("default"),
        runtime=runtime,  # type: ignore[arg-type]
        dimension_thresholds=dimension_thresholds or _DEFAULT_THRESHOLDS,
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


class TestAgentEvaluatorEvaluate:
    def test_success_builds_reason_blob(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime(
            score=0.8,
            attr_dict=_attr_dict(
                attributions=[
                    SkillAttribution(skill_name="alpha_skill", impact="positive", reason="r")
                ],
            ),
        )
        ev = _make_evaluator(runtime=runtime, workdir_base=str(tmp_path))
        evaluated = ev.evaluate(_case(), _PLACEHOLDER)

        # score = simple average of 5 dimensions all at 0.8
        assert evaluated.score == 0.8
        dims = get_preset("default").dimensions
        assert evaluated.per_metric is not None
        assert set(evaluated.per_metric) == set(dims)
        assert all(v == 0.8 for v in evaluated.per_metric.values())

        blob = json.loads(evaluated.reason)
        # all dims 0.8 >= threshold 0.5 → all pass → is_pass=True
        assert blob["is_pass"] is True
        assert blob["attributed_skill"] == "alpha_skill"  # top-1 decisive
        assert blob["attribution_status"] == "completed"
        assert blob["skill_attributions"][0]["skill_name"] == "alpha_skill"
        assert set(blob["dimensions"]) == set(dims)
        assert blob["repaired"] is False
        # 5 thresholds → 5 checks, all pass
        assert len(blob["dimension_checks"]) == 5
        assert all(c["pass"] for c in blob["dimension_checks"])
        # attribution agent ran inside the open workdir (trajectory still readable)
        assert runtime.synthesize_request is not None
        assert runtime.synthesize_request.dimension_name == "skill_attribution"
        assert runtime.synthesize_saw_trajectory is True
        # its prompt inlined the per-dim verdicts + the candidate skill_names
        assert "alpha_skill" in runtime.synthesize_request.prompt
        assert "task_completion" in runtime.synthesize_request.prompt

    def test_is_pass_false_when_any_dimension_below_threshold(self, tmp_path: Path) -> None:
        # score=0.2 → all dims at 0.2, threshold=0.5 → all fail
        runtime = _FakeRuntime(score=0.2, attr_dict=_attr_dict())
        ev = _make_evaluator(runtime=runtime, workdir_base=str(tmp_path))
        evaluated = ev.evaluate(_case(), _PLACEHOLDER)
        blob = json.loads(evaluated.reason)
        assert blob["is_pass"] is False  # 0.2 < 0.5 threshold

    def test_dimension_checks_populated(self, tmp_path: Path) -> None:
        thresholds = {"task_completion": 0.5, "safety": 0.8}
        runtime = _FakeRuntime(score=0.8, attr_dict=_attr_dict())
        ev = AgentEvaluator(
            preset=get_preset("default"),
            runtime=runtime,  # type: ignore[arg-type]
            workdir_base=str(tmp_path),
            dimension_thresholds=thresholds,
        )
        evaluated = ev.evaluate(_case(), _PLACEHOLDER)
        blob = json.loads(evaluated.reason)
        checks = blob["dimension_checks"]
        # 2 thresholds → 2 checks
        assert len(checks) == 2
        # safety=0.8 >= threshold 0.8 → pass
        safety_check = next(c for c in checks if c["dimension"] == "safety")
        assert safety_check["pass"] is True
        # task_completion=0.8 >= threshold 0.5 → pass
        tc_check = next(c for c in checks if c["dimension"] == "task_completion")
        assert tc_check["pass"] is True

    def test_top1_negative_is_decisive(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime(
            attr_dict=_attr_dict(
                attributions=[
                    SkillAttribution(skill_name="neutral_skill", impact="neutral"),
                    SkillAttribution(skill_name="alpha_skill", impact="negative"),
                ]
            )
        )
        ev = _make_evaluator(runtime=runtime, workdir_base=str(tmp_path))
        evaluated = ev.evaluate(_case(skill_names=["alpha_skill", "neutral_skill"]), _PLACEHOLDER)
        blob = json.loads(evaluated.reason)
        assert blob["attributed_skill"] == "alpha_skill"  # negative beats neutral-first

    def test_top1_empty_when_no_attributions(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime(attr_dict=_attr_dict(attributions=[]))
        ev = _make_evaluator(runtime=runtime, workdir_base=str(tmp_path))
        evaluated = ev.evaluate(_case(), _PLACEHOLDER)
        blob = json.loads(evaluated.reason)
        assert blob["attributed_skill"] == ""

    def test_empty_trajectory_raises(self, tmp_path: Path) -> None:
        ev = _make_evaluator(runtime=_FakeRuntime(), workdir_base=str(tmp_path))
        with pytest.raises(EvaluationError, match="Trace unavailable"):
            ev.evaluate(_case(trajectory={"messages": []}), _PLACEHOLDER)

    def test_missing_skill_names_raises(self, tmp_path: Path) -> None:
        ev = _make_evaluator(runtime=_FakeRuntime(), workdir_base=str(tmp_path))
        with pytest.raises(EvaluationError, match="skill_names is required"):
            ev.evaluate(_case(skill_names=[]), _PLACEHOLDER)

    def test_missing_trajectory_raises(self, tmp_path: Path) -> None:
        ev = _make_evaluator(runtime=_FakeRuntime(), workdir_base=str(tmp_path))
        with pytest.raises(ValueError, match="trajectory"):
            ev.evaluate(_case(trajectory=None), _PLACEHOLDER)

    def test_rollout_error_raises(self, tmp_path: Path) -> None:
        ev = _make_evaluator(runtime=_FakeRuntime(), workdir_base=str(tmp_path))
        with pytest.raises(EvaluationError) as exc_info:
            ev.evaluate(_case(), {"error": "rollout exploded"})
        assert exc_info.value.category == "rollout_error"

    def test_unknown_skill_attribution_raises(self, tmp_path: Path) -> None:
        # synthesize returns an attribution for a skill NOT in skill_names → fail-fast
        runtime = _FakeRuntime(
            attr_dict=_attr_dict(
                attributions=[SkillAttribution(skill_name="ghost_skill", impact="positive")]
            )
        )
        ev = _make_evaluator(runtime=runtime, workdir_base=str(tmp_path))
        with pytest.raises(EvaluationError) as exc_info:
            ev.evaluate(_case(), _PLACEHOLDER)
        assert exc_info.value.category == "attribution_unknown_skill"

    def test_synthesize_error_propagates(self, tmp_path: Path) -> None:
        runtime = _FakeRuntime(
            synthesize_exc=EvaluationError(
                category="agent_judge_output_error", safe_message="no parseable output"
            )
        )
        ev = _make_evaluator(runtime=runtime, workdir_base=str(tmp_path))
        with pytest.raises(EvaluationError) as exc_info:
            ev.evaluate(_case(), _PLACEHOLDER)
        assert exc_info.value.category == "agent_judge_output_error"

    def test_workdir_cleaned_after_success(self, tmp_path: Path) -> None:
        ev = _make_evaluator(runtime=_FakeRuntime(), workdir_base=str(tmp_path))
        ev.evaluate(_case(), _PLACEHOLDER)
        leftovers = [p for p in tmp_path.iterdir() if p.name.startswith("evo-agent-judge-")]
        assert leftovers == []

    def test_per_dimension_skills_materialized(self, tmp_path: Path) -> None:
        runtime = _RecordingRuntime(score=0.8)
        ev = _make_evaluator(
            runtime=runtime,  # type: ignore[arg-type]
            workdir_base=str(tmp_path),
        )
        ev.evaluate(_case(), _PLACEHOLDER)
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


class TestAgentEvaluatorInit:
    """trajectory_budget init handling."""

    def test_default_budget_is_module_constant(self) -> None:
        from evo_agent.evaluator.evaluators.agent import _DEFAULT_TRAJECTORY_BUDGET

        ev = AgentEvaluator(
            preset=get_preset("default"),
            runtime=_FakeRuntime(),  # type: ignore[arg-type]
            dimension_thresholds=_DEFAULT_THRESHOLDS,
        )
        assert ev._trajectory_budget == _DEFAULT_TRAJECTORY_BUDGET
        assert ev._trajectory_budget == 4000

    def test_explicit_budget_overrides_default(self) -> None:
        ev = AgentEvaluator(
            preset=get_preset("default"),
            runtime=_FakeRuntime(),  # type: ignore[arg-type]
            dimension_thresholds=_DEFAULT_THRESHOLDS,
            trajectory_budget=12000,
        )
        assert ev._trajectory_budget == 12000

    def test_zero_budget_raises(self) -> None:
        with pytest.raises(ValueError, match="trajectory_budget"):
            AgentEvaluator(
                preset=get_preset("default"),
                runtime=_FakeRuntime(),  # type: ignore[arg-type]
                dimension_thresholds=_DEFAULT_THRESHOLDS,
                trajectory_budget=0,
            )

    def test_negative_budget_raises(self) -> None:
        with pytest.raises(ValueError, match="trajectory_budget"):
            AgentEvaluator(
                preset=get_preset("default"),
                runtime=_FakeRuntime(),  # type: ignore[arg-type]
                dimension_thresholds=_DEFAULT_THRESHOLDS,
                trajectory_budget=-5,
            )
