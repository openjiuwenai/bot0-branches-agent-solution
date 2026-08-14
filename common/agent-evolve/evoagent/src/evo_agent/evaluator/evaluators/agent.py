"""AgentEvaluator — drive real coding-agent CLIs as the judge.

Inherits ``EvaluateInputMixin`` + openjiuwen ``BaseEvaluator`` (the same shape
as :class:`LLMEvaluator`) but replaces the single LLM scoring call with a
two-stage pipeline:

1. **per-dimension subprocess runs** — one ``claude`` / ``codex`` spawn per
   dimension, in an isolated workdir, each emitting a ``DimensionJudgment``;
2. **LLM aggregator** — fuses the verdicts + candidate ``skill_names`` into an
   overall score and plural skill attribution.

Scope is HTTP-only: it does not wire into the optimization pipeline. The route
calls ``to_case_and_placeholder`` + ``evaluate`` directly and reads
``json.loads(evaluated.reason)`` for the full plural attribution (the inherited
``evaluate_input`` → ``from_evaluated_case`` path is lossy and drops
``skill_attributions``).

``evaluate`` is synchronous (mixin contract); the per-dim subprocess stage is
bridged with ``asyncio.run`` in the worker thread the route provides via
``asyncio.to_thread``. The aggregator's ``invoke_sync`` then submits to the
dedicated invocation loop (different thread) — never the asyncio loop we just
ran, so the threading constraint holds.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from collections.abc import Callable
from typing import Any

from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase
from openjiuwen.agent_evolving.evaluator.evaluator import BaseEvaluator

from evo_agent.evaluator.agent_judge.aggregator import SkillAggregator
from evo_agent.evaluator.agent_judge.dimensions import get_dimension
from evo_agent.evaluator.agent_judge.orchestrator import DimensionOrchestrator
from evo_agent.evaluator.agent_judge.presets import JudgePreset
from evo_agent.evaluator.agent_judge.runtime import JudgeAgentRuntime
from evo_agent.evaluator.agent_judge.schemas import (
    AggregatorOutput,
    DimensionJudgment,
    SkillAttribution,
    dimension_judgment_json_schema,
)
from evo_agent.evaluator.agent_judge.workdir import WorkdirManager
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.evaluators.base import EvaluateInputMixin
from evo_agent.llm.trajectory_compaction import (
    TrajectoryCompactionContext,
    TrajectoryCompactionError,
    TrajectoryCompactionPolicy,
    compact_trajectory,
)

logger = logging.getLogger(__name__)

__all__ = ["AgentEvaluator"]

_DEFAULT_TRAJECTORY_BUDGET = 4000


class AgentEvaluator(EvaluateInputMixin, BaseEvaluator):  # type: ignore[misc]
    """Judge a trajectory via subprocess coding agents + an LLM aggregator."""

    def __init__(
        self,
        *,
        preset: JudgePreset,
        runtime: JudgeAgentRuntime,
        aggregator: SkillAggregator,
        workdir_base: str | None = None,
        keep_on_error: bool = False,
    ) -> None:
        self._preset = preset
        self._runtime = runtime
        self._aggregator = aggregator
        self._workdir_base = workdir_base
        self._keep_on_error = (
            keep_on_error or os.environ.get("EVO_DEBUG_AGENT_JUDGE_WORKDIR") == "1"
        )
        self._trajectory_budget = _DEFAULT_TRAJECTORY_BUDGET
        # Optional, call-scoped progress reporter (set by the HTTP route).
        self.progress_callback: Callable[[int, int], None] | None = None

    def evaluate(
        self,
        case: Case,
        predict: dict[str, Any],
        *,
        enable_attribution: bool = True,  # noqa: ARG002 — agent-judge always attributes
    ) -> EvaluatedCase:
        """Evaluate one trajectory across all preset dimensions + aggregate."""
        if isinstance(predict, dict) and predict.get("error"):
            raise EvaluationError(
                category="rollout_error",
                safe_message=f"Rollout failed for case {case.case_id}",
            )

        inputs = case.inputs
        if not (isinstance(inputs, dict) and inputs.get("trajectory") is not None):
            raise ValueError("AgentEvaluator requires a trajectory in case.inputs.")
        trajectory_data = inputs["trajectory"]
        if isinstance(trajectory_data, dict):
            if not trajectory_data.get("messages"):
                raise EvaluationError(
                    category="trace_unavailable",
                    safe_message=f"Trace unavailable for case {case.case_id}",
                )

        skill_names = inputs.get("skill_names")
        if not isinstance(skill_names, list) or not skill_names:
            raise EvaluationError("skill_names is required and must be a non-empty list")

        compacted_text = self._compact_trajectory(trajectory_data, case.case_id)
        dimensions = [get_dimension(name) for name in self._preset.dimensions]
        prompt_builder = self._build_dimension_prompt_builder(compacted_text)

        with WorkdirManager(base_dir=self._workdir_base, keep_on_error=self._keep_on_error) as wd:
            wd.materialize_trajectory(trajectory_data, compacted_text=compacted_text)
            # Mount the preset's global helper skills plus every dimension's own
            # skills (e.g. faithfulness_checklist for the faithfulness dimension)
            # so each read-only judge subprocess can Read whichever applies.
            mounted: list[str] = []
            seen: set[str] = set()
            for name in (
                *self._preset.helper_skills,
                *(s for d in dimensions for s in d.skills),
            ):
                if name not in seen:
                    seen.add(name)
                    mounted.append(name)
            wd.materialize_helper_skills(tuple(mounted))
            schema_path = wd.write_schema(dimension_judgment_json_schema())

            orchestrator = DimensionOrchestrator(
                self._runtime,
                max_concurrent=self._preset.max_concurrent,
                run_timeout=self._preset.run_timeout,
                on_progress=self.progress_callback,
            )
            judgments = asyncio.run(
                orchestrator.run(
                    dimensions,
                    prompt_builder,
                    workdir=wd.path,
                    schema_path=schema_path,
                    tool_allowlist=self._preset.tool_allowlist,
                )
            )

        # Workdir is cleaned up above; the aggregator needs only the judgments
        # and skill docs (fetched via its SkillProvider), not the workdir files.
        agg = self._aggregator.aggregate(
            judgments,
            skill_names=skill_names,
            weights=self._preset.weights,
            case_id=case.case_id,
        )
        return self._build_evaluated_case(case, predict, judgments=judgments, agg=agg)

    # --- helpers -----------------------------------------------------------

    def _compact_trajectory(self, trajectory_data: Any, case_id: str) -> str:
        try:
            compacted = compact_trajectory(
                trajectory_data,
                policy=TrajectoryCompactionPolicy(stage="evaluator"),
                context=TrajectoryCompactionContext(),
                token_budget=self._trajectory_budget,
            )
        except TrajectoryCompactionError as exc:
            raise EvaluationError(
                category="prompt_budget_exceeded",
                safe_message=f"agent-judge trajectory cannot fit prompt budget: {exc}",
            ) from exc
        return compacted.text

    def _build_dimension_prompt_builder(self, compacted_text: str) -> Callable[[Any], str]:
        global_helpers = self._preset.helper_skills

        def build(dimension: Any) -> str:
            # This dimension's own skills first (e.g. the faithfulness checklist),
            # then the preset's global helpers (rubric guide applies to all),
            # deduped preserving order.
            relevant: list[str] = []
            seen: set[str] = set()
            for name in (*dimension.skills, *global_helpers):
                if name not in seen:
                    seen.add(name)
                    relevant.append(name)
            helper_hint = ""
            if relevant:
                helper_hint = (
                    "\n## 随附 helper 文档（位于当前工作目录，可用 Read 读取）\n"
                    + ", ".join(f"{name}.md" for name in relevant)
                )
            return (
                f"你是 Agent 轨迹评估专家，正在评估一条轨迹的「{dimension.name}」维度。\n\n"
                f"## 维度说明\n{dimension.prompt}\n\n"
                f"## 评分标尺\n{dimension.rubric}\n\n"
                "## 轨迹摘要（compact view；全量见当前工作目录的 "
                "trajectory.jsonl / trajectory.md，可用 Read/Grep 工具读取）\n"
                f"{compacted_text}\n\n"
                "## 评估要求\n"
                f"- 仅评估「{dimension.name}」维度，不要涉及其它维度，不要重复扣分。\n"
                "- 严格评分，仅在该维度完全无可挑剔时才给 1.0。\n"
                "- 只输出一个符合 schema 的 JSON 对象：{dimension, score, reasoning}，"
                "score 为 0-1 的数字，reasoning 引用轨迹中的具体证据。\n"
                "- 禁止 Markdown、code fence、注释或多余字段。"
                f"{helper_hint}"
            )

        return build

    def _build_evaluated_case(
        self,
        case: Case,
        predict: dict[str, Any],
        *,
        judgments: list[DimensionJudgment],
        agg: AggregatorOutput,
    ) -> EvaluatedCase:
        """Build EvaluatedCase with the bridge reason JSON blob.

        ``attributed_skill`` (singular, top-1) keeps the existing
        ``from_evaluated_case`` contract; the full plural ``skill_attributions``
        lives in the same blob for the HTTP route to read directly.
        """
        evaluated = EvaluatedCase(case=case, answer=predict)

        dimensions = {j.dimension: max(0.0, min(1.0, j.score)) for j in judgments}
        evaluated.per_metric = dict(dimensions) if dimensions else None
        evaluated.score = max(0.0, min(1.0, agg.overall_score))

        is_pass = agg.overall_score >= self._preset.pass_threshold
        attributed_skill = _select_top1_attribution(agg.skill_attributions)
        evaluated.reason = json.dumps(
            {
                "reason": (
                    f"agent-as-judge: {len(judgments)} dimensions, "
                    f"overall={agg.overall_score:.3f}, status={agg.attribution_status}"
                ),
                "is_pass": is_pass,
                "attributed_skill": attributed_skill,
                "repaired": False,
                "parse_mode": "exact",
                "repair_operations": [],
                "dimensions": dimensions,
                "skill_attributions": [a.model_dump() for a in agg.skill_attributions],
                "attribution_status": agg.attribution_status,
                "attribution_error": agg.attribution_error,
            },
            ensure_ascii=False,
        )
        return evaluated


def _select_top1_attribution(attributions: list[SkillAttribution]) -> str:
    """Pick the singular attributed skill (bridge for the legacy contract).

    Prefers an attribution with decisive impact (positive or negative), then
    falls back to the first attribution; empty list → ``""``. The full plural
    list is preserved in ``reason.skill_attributions``.
    """
    if not attributions:
        return ""
    decisive = [a for a in attributions if a.impact in ("positive", "negative")]
    if decisive:
        return decisive[0].skill_name
    return attributions[0].skill_name
