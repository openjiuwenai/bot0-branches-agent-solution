"""LLMEvaluator — Skill open-ended answer evaluator inheriting BaseEvaluator."""

from __future__ import annotations

import concurrent.futures
import hashlib
import json
import logging
import math
import os
from collections.abc import Mapping
from dataclasses import asdict
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
from evo_agent.evaluator.batch_result import (
    EvaluationBatchResult,
    EvaluationFailure,
    EvaluationOutcome,
)
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
from evo_agent.llm.invocation import (
    LLMInvocation,
    LLMInvocationContext,
    LLMInvocationError,
    LLMInvocationRequest,
    LLMProviderCapabilities,
    LLMRetryPolicy,
)
from evo_agent.llm.structured_output import (
    StructuredOutputPolicy,
    ValidationResult,
    parse_structured_output,
)
from evo_agent.llm.trajectory_compaction import (
    TrajectoryCompactionContext,
    TrajectoryCompactionError,
    TrajectoryCompactionPolicy,
    compact_trajectory,
)

logger = logging.getLogger(__name__)

# Dimension keys for best-effort per_metric extraction.
_DIM_KEYS = build_dimension_keys()
_EVALUATOR_JSON_KEYS = frozenset(
    {
        "task_completion",
        "trajectory_quality",
        "safety",
        "is_pass",
        "score",
        "attributed_skill",
        "reason",
    }
)
_EVALUATOR_POLICY = StructuredOutputPolicy(
    schema_name="llm_evaluation",
    required_keys=frozenset({"reason"}),
    allowed_comma_next_keys=_EVALUATOR_JSON_KEYS,
)


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
        invocation: LLMInvocation | None = None,
    ) -> None:
        self._model = Model(model_client_config, model_config)
        self._aggregate = aggregate  # noqa: F841 — unused, retained for compat
        self._prompt_template = prompt_template
        self._invocation = invocation or LLMInvocation(
            self._model,
            capabilities=LLMProviderCapabilities(
                context_window_tokens=32768,
                supports_max_output_tokens=False,
                supports_finish_reason=True,
                supports_usage=True,
                supports_json_mode=True,
                completion_signal="either",
            ),
            parallelism=4,
            safety_margin_tokens=512,
            chars_per_token=2.0,
            default_output_reserve_tokens=1200,
        )

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
        if isinstance(predict, dict) and predict.get("error"):
            raise EvaluationError(
                category="rollout_error",
                safe_message=f"Rollout failed for case {case.case_id}",
            )
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
                    category="trace_unavailable",
                    safe_message=f"Trace unavailable for case {case.case_id}",
                )

        # skill_names is required for attribution validation.
        skill_names = question.get("skill_names")
        if not isinstance(skill_names, list) or not skill_names:
            raise EvaluationError("skill_names is required and must be a non-empty list")

        expected_str = None
        if label is not None:
            expected_str = json.dumps(label, ensure_ascii=False, default=str)

        prompt_without_trajectory = self._build_prompt(
            expected_result=expected_str,
            trajectory="",
            skill_names=skill_names,
            enable_attribution=enable_attribution,
        )
        trajectory_budget = self._invocation.input_token_budget("evaluator", 1200)
        trajectory_budget -= self._invocation.estimate_messages(
            (UserMessage(content=prompt_without_trajectory),)
        )
        try:
            compacted = compact_trajectory(
                trajectory_data,
                policy=TrajectoryCompactionPolicy(stage="evaluator"),
                context=TrajectoryCompactionContext(),
                token_budget=trajectory_budget,
            )
        except TrajectoryCompactionError as exc:
            raise EvaluationError(
                category="prompt_budget_exceeded",
                safe_message=f"Evaluator trajectory cannot fit prompt budget: {exc}",
            ) from exc
        trajectory_str = compacted.text

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

        invalid_error: EvaluationError | None = None
        invalid_response: str | None = None

        def _result_is_valid(text: str) -> bool:
            nonlocal invalid_error, invalid_response
            try:
                parsed = self._parse_result(
                    text,
                    enable_attribution=enable_attribution,
                )
                _validate_attributed_skill(parsed.attributed_skill, skill_names)
            except EvaluationError as error:
                invalid_error = error
                invalid_response = text
                return False
            return True

        def _result_error_category(text: str) -> str | None:
            try:
                parsed = self._parse_result(text, enable_attribution=enable_attribution)
                _validate_attributed_skill(parsed.attributed_skill, skill_names)
            except EvaluationError as error:
                return error.category
            return None

        retry_prompt = _build_format_retry_prompt(prompt, enable_attribution)
        try:
            result = self._invocation.invoke_sync(
                LLMInvocationRequest(
                    stage="evaluator",
                    messages=(UserMessage(content=prompt),),
                    retry_messages=(UserMessage(content=retry_prompt),),
                    result_validator=_result_is_valid,
                    result_error_classifier=_result_error_category,
                    context=LLMInvocationContext(run_id="evaluator", case_id=case.case_id),
                    retry_policy=LLMRetryPolicy(2, 120.0, 300.0, 1.0, 0.0),
                    output_schema_name="llm_evaluation",
                    reserved_output_tokens=1200,
                )
            )
            response = result.text
        except Exception as e:
            if (
                isinstance(e, LLMInvocationError)
                and e.category == "unusable_response"
                and invalid_error is not None
                and invalid_response is not None
                and e.result is not None
            ):
                _attach_invocation_diagnostics(
                    invalid_error,
                    response=invalid_response,
                    result=e.result,
                )
                _log_evaluation_error(invalid_error, case_id=case.case_id)
                raise invalid_error from e
            category = getattr(e, "category", "llm_invoke_error")
            raise EvaluationError(
                category=category,
                safe_message=f"LLM evaluation failed for case {case.case_id}: {e}",
            ) from e

        try:
            scores = self._parse_result(response, enable_attribution=enable_attribution)
        except EvaluationError as error:
            _attach_invocation_diagnostics(error, response=response, result=result)
            _log_evaluation_error(error, case_id=case.case_id)
            raise
        if scores.repaired:
            _log_json_repair(scores, response=response, result=result, case_id=case.case_id)

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
        return list(
            self.batch_evaluate_detailed(
                cases,
                predicts,
                num_parallel=num_parallel,
                enable_attribution=enable_attribution,
            ).successes
        )

    def batch_evaluate_detailed(
        self,
        cases: list[Case] | Any,
        predicts: list[dict[str, Any]],
        num_parallel: int = 1,
        *,
        enable_attribution: bool = True,
    ) -> EvaluationBatchResult:
        """Evaluate every input into an ordered success-or-failure outcome."""
        if len(cases) != len(predicts):
            raise ValueError(
                f"length of cases: {len(cases)} does not equal length of predicts: {len(predicts)}"
            )

        if not cases:
            return EvaluationBatchResult(())

        num_workers = min(max(num_parallel, 1), len(cases))

        def _eval(case: Case, predict: dict[str, Any]) -> EvaluatedCase:
            return self.evaluate(case, predict, enable_attribution=enable_attribution)

        with concurrent.futures.ThreadPoolExecutor(max_workers=num_workers) as executor:
            futures = [
                executor.submit(_eval, case, predict) for case, predict in zip(cases, predicts)
            ]

            outcomes: list[EvaluationOutcome] = []
            for index, (future, case, _predict) in enumerate(zip(futures, cases, predicts)):
                trajectory = (
                    case.inputs.get("trajectory") if isinstance(case.inputs, dict) else None
                )
                try:
                    evaluated = future.result()
                    outcomes.append(
                        EvaluationOutcome(
                            index=index,
                            case_id=case.case_id,
                            case=case,
                            trajectory=trajectory,
                            evaluated=evaluated,
                            failure=None,
                        )
                    )
                except EvaluationError as e:
                    _log_evaluation_error(e, case_id=case.case_id)
                    outcomes.append(
                        EvaluationOutcome(
                            index=index,
                            case_id=case.case_id,
                            case=case,
                            trajectory=trajectory,
                            evaluated=None,
                            failure=EvaluationFailure(
                                category=e.category,
                                safe_message=e.safe_message,
                                invocation_id=e.invocation_id,
                                response_sha256=e.response_sha256,
                                response_chars=e.response_chars,
                            ),
                        )
                    )

        return EvaluationBatchResult(tuple(outcomes))

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
                "repaired": scores.repaired,
                "parse_mode": scores.parse_mode,
                "repair_operations": list(scores.repair_operations),
            },
            ensure_ascii=False,
        )

        return evaluated

    def _parse_result(self, response: str, *, enable_attribution: bool = False) -> EvaluationScores:
        """Parse LLM flat-output JSON into EvaluationScores.

        Validates 4 essential fields: ``is_pass``, ``score``, ``attributed_skill`,
        ``reason``.  Dimension scores (``task_completion``, ``trajectory_quality`,
        ``safety``) are best-effort — missing/invalid dimensions are silently
        skipped rather than raising errors.

        Raises:
            EvaluationError: when JSON extraction fails, the response is not
                a dict, or essential fields are missing/invalid.
        """
        policy = StructuredOutputPolicy(
            schema_name=_EVALUATOR_POLICY.schema_name,
            allow_single_missing_comma=_EVALUATOR_POLICY.allow_single_missing_comma,
            allowed_comma_next_keys=_EVALUATOR_POLICY.allowed_comma_next_keys,
            required_keys=frozenset(
                {"reason", "attributed_skill"} if enable_attribution else {"reason"}
            ),
        )
        extraction = parse_structured_output(
            response,
            policy=policy,
            validator=_validate_evaluator_output,
        )
        data = extraction.data
        if data is None:
            digest = hashlib.sha256(response.encode("utf-8")).hexdigest()
            raise EvaluationError(
                category=extraction.error_category or "json_parse_error",
                safe_message=f"Failed to extract JSON from evaluator response: {extraction.error}",
                response_sha256=digest,
                response_chars=len(response),
                raw_response=response,
            )

        # --- Essential field validation (critical path) ---
        raw_score = data["score"]
        assert isinstance(raw_score, (int, float)) and not isinstance(raw_score, bool)
        score = max(0.0, min(1.0, float(raw_score)))

        raw_is_pass = data["is_pass"]
        assert isinstance(raw_is_pass, bool)
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
            repaired=extraction.parse_mode not in {"exact", "failed"},
            parse_mode=extraction.parse_mode,
            repair_operations=tuple(
                asdict(operation) for operation in extraction.repair_operations
            ),
            repaired_response=extraction.repaired_text,
        )


def _validate_evaluator_output(data: dict[str, Any]) -> ValidationResult:
    raw_score = data.get("score")
    if isinstance(raw_score, bool) or not isinstance(raw_score, (int, float)):
        return ValidationResult(
            False,
            "field_type",
            "LLM response missing valid 'score' field",
        )
    if not math.isfinite(raw_score):
        return ValidationResult(
            False,
            "field_type",
            "LLM response 'score' has non-finite value",
        )
    raw_is_pass = data.get("is_pass")
    if not isinstance(raw_is_pass, bool):
        return ValidationResult(
            False,
            "field_type",
            "LLM response missing valid 'is_pass' field",
        )
    if not isinstance(data.get("reason"), str):
        return ValidationResult(False, "field_type", "reason must be a string")
    attributed_skill = data.get("attributed_skill", "")
    if not isinstance(attributed_skill, str):
        return ValidationResult(False, "field_type", "attributed_skill must be a string")
    for dimension in _DIM_KEYS:
        value = data.get(dimension)
        if value is not None and (
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not math.isfinite(value)
        ):
            return ValidationResult(
                False,
                "field_type",
                f"{dimension} must be a finite number",
            )
    return ValidationResult(True)


def _inject_custom_instruction(custom_instruction: str) -> str:
    """Inject a bare instruction into the default template's role-instruction position.

    Replaces the first paragraph of ``DEFAULT_PROMPT_TEMPLATE`` (the role
    description) with *custom_instruction*, preserving all data placeholders
    and scoring rules that follow.
    """
    _role, _sep, rest = DEFAULT_PROMPT_TEMPLATE.partition("\n\n")
    return f"{custom_instruction}\n\n{rest}"


def _build_format_retry_prompt(prompt: str, enable_attribution: bool) -> str:
    required = "is_pass、score、reason"
    if enable_attribution:
        required += "、attributed_skill"
    return (
        f"{prompt}\n\n格式重试：上一次输出未通过 JSON schema 校验。"
        f"请重新评估，并只输出一个合法 JSON 对象；必填字段：{required}。"
        "禁止 Markdown、code fence、注释、NaN 或 Infinity。"
    )


def _log_evaluation_error(error: EvaluationError, *, case_id: str) -> None:
    if error.logged:
        return
    raw = (
        json.dumps(error.raw_response, ensure_ascii=False)
        if error.raw_response is not None
        else "unknown"
    )
    diagnostics = error.invocation_diagnostics
    logger.warning(
        "Evaluation skipped case_id=%s category=%s invocation_id=%s stage=%s "
        "provider=%s prompt_estimated_tokens=%s output_reserve_tokens=%s "
        "compacted=%s finish_reason=%s completion_signal=%s chunk_count=%s "
        "raw_response=%s",
        case_id,
        error.category,
        error.invocation_id or "unknown",
        diagnostics.get("stage", "unknown"),
        diagnostics.get("provider", "unknown"),
        diagnostics.get("estimated_input_tokens", "unknown"),
        diagnostics.get("output_reserve_tokens", "unknown"),
        diagnostics.get("compacted", "unknown"),
        diagnostics.get("finish_reason", "unknown"),
        diagnostics.get("completion_signal", "unknown"),
        diagnostics.get("chunk_count", "unknown"),
        raw,
    )
    error.logged = True


def _attach_invocation_diagnostics(
    error: EvaluationError,
    *,
    response: str,
    result: Any,
) -> None:
    """Attach log-only evidence without changing the artifact-safe exception string."""
    error.raw_response = response
    error.response_sha256 = hashlib.sha256(response.encode("utf-8")).hexdigest()
    error.response_chars = len(response)
    error.invocation_id = getattr(result, "invocation_id", None)
    metadata = getattr(result, "metadata", {})
    diagnostics = dict(metadata) if isinstance(metadata, Mapping) else {}
    diagnostics["stage"] = "evaluator"
    diagnostics["finish_reason"] = getattr(result, "finish_reason", None) or "unknown"
    error.invocation_diagnostics = diagnostics


def _log_json_repair(
    scores: EvaluationScores,
    *,
    response: str,
    result: Any,
    case_id: str,
) -> None:
    metadata = getattr(result, "metadata", {})
    diagnostics = dict(metadata) if isinstance(metadata, Mapping) else {}
    logger.warning(
        "JSON repaired case_id=%s invocation_id=%s stage=evaluator "
        "schema_name=llm_evaluation attempt=%s provider=%s parse_mode=%s "
        "repair_operations=%s finish_reason=%s transport_complete=%s "
        "original_response=%s repaired_response=%s",
        case_id,
        getattr(result, "invocation_id", None) or "unknown",
        diagnostics.get("attempt", "unknown"),
        diagnostics.get("provider", "unknown"),
        scores.parse_mode,
        json.dumps(scores.repair_operations, ensure_ascii=False),
        getattr(result, "finish_reason", None) or "unknown",
        getattr(result, "transport_complete", "unknown"),
        json.dumps(response, ensure_ascii=False),
        json.dumps(scores.repaired_response or "", ensure_ascii=False),
    )


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
