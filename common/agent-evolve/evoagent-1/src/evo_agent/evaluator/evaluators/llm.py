"""LLMEvaluator — Skill open-ended answer evaluator inheriting BaseEvaluator."""

from __future__ import annotations

import asyncio
import concurrent.futures
import json
import logging
import math
import os
import re
from typing import Any

from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase
from openjiuwen.agent_evolving.evaluator.evaluator import BaseEvaluator
from openjiuwen.core.foundation.llm import (
    Model,
    ModelClientConfig,
    ModelRequestConfig,
    UserMessage,
)

from evo_agent.evaluator.adapters.openjiuwen import CONVERSATION_PREDICTION
from evo_agent.evaluator.domain.models import LLMEvaluationOutput
from evo_agent.evaluator.domain.scoring import EvaluationError, EvaluationScores
from evo_agent.evaluator.evaluators.base import EvaluateInputMixin
from evo_agent.evaluator.prompts.formatter import (
    build_dimension_keys,
    format_evaluation_prompt,
)
from evo_agent.evaluator.prompts.policy_v1 import (
    DEFAULT_PROMPT_TEMPLATE,
)
from evo_agent.evaluator.trajectory.simplify import simplify_trajectory

logger = logging.getLogger(__name__)

# Dimension keys for best-effort per_metric extraction.
_DIM_KEYS = build_dimension_keys()


class LLMEvaluator(EvaluateInputMixin, BaseEvaluator):  # type: ignore[misc]
    """Skill open-ended answer evaluator.

    Inherits openjiuwen BaseEvaluator, implements ``evaluate()`` directly,
    returning ``EvaluatedCase``. Manages LLM model calls internally.

    ``evaluate_input()`` is provided by ``EvaluateInputMixin``.

    Note: ``aggregate`` is retained for signature compatibility with
    ``factory.py`` callers but is **no longer used** — the LLM provides
    ``score`` directly in the flat output format.
    """

    def __init__(
        self,
        model_config: ModelRequestConfig,
        model_client_config: ModelClientConfig,
        aggregate: str = "mean",  # deprecated — kept for factory compat
        prompt_template: str | None = None,
    ) -> None:
        self._model = Model(model_client_config, model_config)
        self._aggregate = aggregate  # noqa: F841 — unused, retained for compat
        self._prompt_template = prompt_template

    def evaluate(
        self,
        case: Case,
        predict: dict[str, Any],
        *,
        enable_attribution: bool = True,
    ) -> EvaluatedCase:
        """Evaluate a complete conversations trajectory.

        ``enable_attribution=False`` 时 prompt 不要求 skill 归因，输出也不含
        ``attributed_skill`` 字段（validation 路径，归因仅在训练 bad case 做）。
        """
        del predict
        label = case.label.get("expected_result")
        question = case.inputs

        if not (isinstance(question, dict) and question.get("trajectory") is not None):
            raise ValueError("LLMEvaluator requires a trajectory in case.inputs.")

        trajectory_data = question.get("trajectory")

        # Reject empty trajectories (e.g. trace unavailable fallback) —
        # evaluating an empty conversation produces misleading zero scores.
        if isinstance(trajectory_data, dict):
            msgs = trajectory_data.get("messages", [])
            if not msgs:
                raise EvaluationError(
                    f"Empty trajectory for case {case.case_id}: "
                    "no messages to evaluate (trace likely unavailable)"
                )

        trajectory_str, _warnings = _simplify_for_prompt(trajectory_data)
        # Warnings are dropped — the new prompt template has no warnings placeholder.

        # skill_names is required for attribution validation.
        skill_names = question.get("skill_names")
        if not isinstance(skill_names, list) or not skill_names:
            raise EvaluationError("skill_names is required and must be a non-empty list")

        expected_str = None
        if label is not None:
            expected_str = json.dumps(label, ensure_ascii=False, default=str)

        prompt = self._build_prompt(
            expected_result=expected_str,
            trajectory=trajectory_str,
            skill_names=skill_names,
            enable_attribution=enable_attribution,
        )

        # Debug: log placeholder fill status on first evaluation only.
        # Gated by EVO_DEBUG_EVAL_PROMPT=1 — default silent (no stdout, no /tmp file).
        if os.environ.get("EVO_DEBUG_EVAL_PROMPT") == "1" and not getattr(
            self, "_debug_prompt_logged", False
        ):
            self._debug_prompt_logged = True
            logger.debug(
                "eval prompt: total_len=%d has_expected=%s has_messages=%s "
                "has_skill_list=%s has_skill_names=%s has_diag_rules=%s",
                len(prompt),
                "✅" if "可选期望结果" in prompt or "预期结果" in prompt else "❌",
                "✅" if "{messages}" in prompt else "❌",
                "✅" if "可用 Skill 列表" in prompt or "Skill 列表" in prompt else "❌",
                "✅" if "{skill_names}" in prompt else "❌",
                "✅" if "{diagnostic_rules}" not in prompt else "❌",
            )

        try:
            messages = [UserMessage(content=prompt)]
            response = _run_coroutine(self._model.invoke(messages)).content
        except Exception as e:
            raise EvaluationError(f"LLM evaluation failed for case {case.case_id}: {e}") from e

        scores = self._parse_result(response)

        # Validate attributed_skill against the provided skill_names list.
        _validate_attributed_skill(scores.attributed_skill, skill_names)

        return self._build_evaluated_case(case, dict(CONVERSATION_PREDICTION), scores)

    def batch_evaluate(
        self,
        cases: list[Case] | Any,
        predicts: list[dict[str, Any]],
        num_parallel: int = 1,
        *,
        enable_attribution: bool = True,
    ) -> list[EvaluatedCase]:
        """Evaluate multiple cases, skipping those that fail evaluation.

        Unlike ``BaseEvaluator.batch_evaluate``, individual ``EvaluationError``
        exceptions are caught and the corresponding cases are **excluded** from
        the result list rather than assigned a misleading zero score.

        This ensures that infrastructure failures (API timeout, rate-limit,
        malformed LLM output) do not pollute the skill optimization signal
        with false-zero scores.

        Failed cases are logged at WARNING level with their case_id and error.
        """
        if len(cases) != len(predicts):
            raise ValueError(
                f"length of cases: {len(cases)} does not equal length of predicts: {len(predicts)}"
            )

        if not cases:
            return []

        num_workers = min(max(num_parallel, 1), len(cases))

        def _eval(case: Case, predict: dict[str, Any]) -> EvaluatedCase:
            return self.evaluate(case, predict, enable_attribution=enable_attribution)

        with concurrent.futures.ThreadPoolExecutor(max_workers=num_workers) as executor:
            futures = [
                executor.submit(_eval, case, predict) for case, predict in zip(cases, predicts)
            ]

            evaluated: list[EvaluatedCase] = []
            for future, case, _predict in zip(futures, cases, predicts):
                try:
                    evaluated.append(future.result())
                except EvaluationError as e:
                    logger.warning("Evaluation skipped for case %s: %s", case.case_id, e)

        return evaluated

    @staticmethod
    def _mean_score(evaluated: list[EvaluatedCase]) -> float:
        """Compute mean score over evaluated cases.

        Defensive: also filters any ``NaN`` scores that might have been
        introduced outside ``batch_evaluate``, in addition to the primary
        mechanism of excluding failed cases from the list.
        """
        valid = [c for c in evaluated if not math.isnan(c.score)]
        if not valid:
            return 0.0
        return float(sum(c.score for c in valid)) / len(valid)

    def _build_prompt(
        self,
        *,
        expected_result: str | None,
        trajectory: str,
        skill_names: list[str] | None = None,
        enable_attribution: bool = True,
    ) -> str:
        """Select a template and fill placeholders, returning the full prompt string.

        If the custom prompt template lacks the ``{messages}`` placeholder
        (the trajectory data position), it is treated as a plain instruction
        text and injected into the default template's role-instruction position.
        This prevents silent data loss when callers pass a bare description
        instead of a full template.
        """
        if self._prompt_template is not None:
            template = self._prompt_template
            if "{messages}" not in template:
                logger.warning(
                    "Custom evaluator prompt missing {messages} placeholder; "
                    "treating as instruction text and injecting into default template."
                )
                template = _inject_custom_instruction(template)
        else:
            template = DEFAULT_PROMPT_TEMPLATE
        return format_evaluation_prompt(
            template,
            expected_result=expected_result,
            trajectory=trajectory,
            skill_names=skill_names,
            enable_attribution=enable_attribution,
        )

    def _build_evaluated_case(
        self,
        case: Case,
        predict: dict[str, Any],
        scores: EvaluationScores,
    ) -> EvaluatedCase:
        """Build an EvaluatedCase from scores — score comes directly from the LLM."""
        evaluated = EvaluatedCase(case=case, answer=predict)

        per_metric: dict[str, float] = dict(scores)
        evaluated.per_metric = per_metric if per_metric else None

        # score is provided directly by the LLM, not aggregated from dimensions.
        evaluated.score = scores.score

        evaluated.reason = json.dumps(
            {
                "reason": scores.reason,
                "is_pass": scores.is_pass,
                "attributed_skill": scores.attributed_skill,
            },
            ensure_ascii=False,
        )

        return evaluated

    def _parse_result(self, response: str) -> EvaluationScores:
        """Parse LLM flat-output JSON into EvaluationScores.

        Validates 4 essential fields: ``is_pass``, ``score``, ``attributed_skill`,
        ``reason``.  Dimension scores (``task_completion``, ``trajectory_quality`,
        ``safety``) are best-effort — missing/invalid dimensions are silently
        skipped rather than raising errors.

        Raises:
            EvaluationError: when JSON extraction fails, the response is not
                a dict, or essential fields are missing/invalid.
        """
        try:
            data = _extract_json(response)
        except Exception as e:
            raise EvaluationError(f"Failed to extract JSON from LLM response: {e}") from e

        if not isinstance(data, dict):
            raise EvaluationError(f"Failed to extract JSON from LLM response: {response[:200]}")

        # --- Essential field validation (critical path) ---
        raw_score = data.get("score")
        if not isinstance(raw_score, (int, float)):
            raise EvaluationError(f"LLM response missing valid 'score' field: {raw_score!r}")
        if not math.isfinite(raw_score):
            raise EvaluationError(f"LLM response 'score' has non-finite value: {raw_score!r}")
        score = max(0.0, min(1.0, float(raw_score)))

        raw_is_pass = data.get("is_pass")
        if not isinstance(raw_is_pass, bool):
            raise EvaluationError(f"LLM response missing valid 'is_pass' field: {raw_is_pass!r}")
        is_pass = raw_is_pass

        attributed_skill = data.get("attributed_skill", "")
        if not isinstance(attributed_skill, str):
            attributed_skill = ""

        reason = data.get("reason", "")
        if not isinstance(reason, str):
            reason = ""

        # --- Best-effort dimension extraction ---
        per_metric: dict[str, float] = {}
        for dim_key in _DIM_KEYS:
            raw_val = data.get(dim_key)
            if isinstance(raw_val, (int, float)) and math.isfinite(raw_val):
                per_metric[dim_key] = max(0.0, min(1.0, float(raw_val)))

        # --- Graceful degradation: try LLMEvaluationOutput for strict fields ---
        try:
            parsed = LLMEvaluationOutput.model_validate(data)
            reason = parsed.reason
            attributed_skill = parsed.attributed_skill
        except Exception:
            # Fall back to raw extraction above — essential fields already validated.
            pass

        return EvaluationScores(
            per_metric,
            reason=reason,
            is_pass=is_pass,
            score=score,
            attributed_skill=attributed_skill,
        )


def _inject_custom_instruction(custom_instruction: str) -> str:
    """Inject a bare instruction into the default template's role-instruction position.

    Replaces the first paragraph of ``DEFAULT_PROMPT_TEMPLATE`` (the role
    description) with *custom_instruction*, preserving all data placeholders
    and scoring rules that follow.
    """
    _role, _sep, rest = DEFAULT_PROMPT_TEMPLATE.partition("\n\n")
    return f"{custom_instruction}\n\n{rest}"


def _simplify_for_prompt(
    trajectory_data: Any,
) -> tuple[str, list[str]]:
    """Simplify a trajectory and extract warnings for the evaluation prompt.

    B3 (#5): 直接把 raw dict / StandardTrajectory 喂给 ``simplify_trajectory``，
    跳过 ``StandardTrajectory.model_validate`` 的 dict→object→dict 往返。
    仅在 simplify 自身抛错时回退为原始 JSON dump。
    """
    try:
        simplified = simplify_trajectory(trajectory_data)
    except Exception:
        return (
            json.dumps(trajectory_data, ensure_ascii=False, default=str),
            [],
        )

    return (
        json.dumps(simplified.model_dump(), ensure_ascii=False, default=str),
        simplified.warnings,
    )


def _run_coroutine(coroutine: Any) -> Any:
    """Run a coroutine synchronously; uses a thread when a loop is already running."""
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.run(coroutine)

    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
        return executor.submit(asyncio.run, coroutine).result()


def _extract_json(text: str) -> dict[str, Any] | None:
    """Extract JSON from an LLM response.

    Tries `````json ... ``````` code blocks first, then falls back to bare JSON.
    """
    match = re.search(r"```json\s*(.*?)\s*```", text, re.DOTALL)
    if match:
        data = json.loads(match.group(1))
        return data if isinstance(data, dict) else None

    match = re.search(r"\{.*\}", text, re.DOTALL)
    if match:
        data = json.loads(match.group(0))
        return data if isinstance(data, dict) else None

    return None


def _validate_attributed_skill(
    attributed_skill: str,
    skills_list: list[str],
) -> None:
    """Validate that ``attributed_skill`` exists in the known skills list.

    Raises ``EvaluationError`` if ``attributed_skill`` is non-empty and not
    in *skills_list* (case-sensitive exact match).

    An empty *attributed_skill* (no attribution) always passes.
    """
    if not attributed_skill:
        return

    known = set(skills_list)
    if attributed_skill not in known:
        raise EvaluationError(
            f"Attributed skill '{attributed_skill}' not in known skills list; "
            f"known skills: {sorted(known)!r}"
        )
