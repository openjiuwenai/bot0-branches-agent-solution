"""Generate a natural-language user goal from a conversation trajectory."""

from __future__ import annotations

import math
from typing import Any

from openjiuwen.core.foundation.llm import (
    Model,
    ModelClientConfig,
    ModelRequestConfig,
    UserMessage,
)

from evo_agent.evaluator.domain.models import (
    GoalGenerationInput,
    GoalGenerationOutput,
)
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.prompts.goal_generation import GOAL_GENERATION_PROMPT_TEMPLATE
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
    StructuredOutputResult,
    ValidationResult,
    log_structured_output,
    parse_structured_output,
)
from evo_agent.llm.trajectory_compaction import (
    TrajectoryCompactionContext,
    TrajectoryCompactionError,
    TrajectoryCompactionPolicy,
    compact_trajectory,
)

_GOAL_POLICY = StructuredOutputPolicy(
    schema_name="goal_generation",
    required_keys=frozenset({"goal"}),
    allowed_comma_next_keys=frozenset({"goal", "reason", "confidence"}),
)
_GOAL_FORMAT_RETRY_INSTRUCTION = (
    "格式重试：请依据下方轨迹证据重新提炼真实目标，只输出一个合法 JSON 对象。"
    "对象必须包含 goal 字符串，可包含 reason 字符串和 confidence 有限数值。"
    "不要照抄字段说明；禁止 Markdown、code fence、注释、NaN 或 Infinity。"
)


class TrajectoryGoalGenerator:
    """Generate a Chinese natural-language user goal from trajectory messages."""

    def __init__(
        self,
        model_config: ModelRequestConfig,
        model_client_config: ModelClientConfig,
        invocation: LLMInvocation | None = None,
    ) -> None:
        self._model = Model(model_client_config, model_config)
        self._invocation = invocation or LLMInvocation(
            self._model,
            capabilities=LLMProviderCapabilities(32768, False, True, True, True, "either"),
            parallelism=4,
            safety_margin_tokens=512,
            chars_per_token=2.0,
            default_output_reserve_tokens=1200,
        )

    def generate(self, value: GoalGenerationInput) -> GoalGenerationOutput:
        """Generate the final user goal represented by the full trajectory."""
        if not value.trajectory.messages:
            raise EvaluationError("Empty trajectory: no messages to generate goal from")

        prompt_without_trajectory = GOAL_GENERATION_PROMPT_TEMPLATE.replace("{messages}", "")
        trajectory_budget = self._invocation.input_token_budget("goal_generator", 1200)
        trajectory_budget -= self._invocation.estimate_messages(
            (UserMessage(content=prompt_without_trajectory),)
        )
        try:
            compacted = compact_trajectory(
                value.trajectory,
                policy=TrajectoryCompactionPolicy(stage="goal_generator"),
                context=TrajectoryCompactionContext(),
                token_budget=trajectory_budget,
            )
        except TrajectoryCompactionError as exc:
            raise EvaluationError(
                category="prompt_budget_exceeded",
                safe_message=f"Goal trajectory cannot fit prompt budget: {exc}",
            ) from exc
        prompt = GOAL_GENERATION_PROMPT_TEMPLATE.replace("{messages}", compacted.text)
        retry_prompt = f"{_GOAL_FORMAT_RETRY_INSTRUCTION}\n\n轨迹证据：\n{compacted.text}"

        def parse(text: str) -> StructuredOutputResult:
            return parse_structured_output(
                text,
                policy=_GOAL_POLICY,
                validator=_validate_goal_output,
            )

        try:
            result = self._invocation.invoke_sync(
                LLMInvocationRequest(
                    stage="goal_generator",
                    messages=(UserMessage(content=prompt),),
                    retry_messages=(UserMessage(content=retry_prompt),),
                    result_validator=lambda text: parse(text).data is not None,
                    result_error_classifier=lambda text: parse(text).error_category,
                    context=LLMInvocationContext(run_id="goal_generator"),
                    retry_policy=LLMRetryPolicy(2, 120.0, 300.0, 1.0, 0.0),
                    output_schema_name="goal_generation",
                    reserved_output_tokens=1200,
                )
            )
            response = result.text
        except Exception as e:
            if isinstance(e, LLMInvocationError) and e.category == "unusable_response":
                message = (
                    "LLM response missing valid 'goal' field"
                    if e.output_error_category == "required_key"
                    else "Failed to extract JSON from LLM goal response"
                )
                raise EvaluationError(
                    category=e.output_error_category or "json_parse_error",
                    safe_message=message,
                ) from e
            raise EvaluationError(f"LLM goal generation failed: {e}") from e

        final_extraction = parse(response)
        if final_extraction.data is not None:
            log_structured_output(
                final_extraction,
                stage="goal_generator",
                schema_name=_GOAL_POLICY.schema_name,
                invocation_id=result.invocation_id,
                attempt=result.metadata.get("attempt", "unknown"),
                finish_reason=result.finish_reason or "unknown",
                transport_complete=result.transport_complete,
            )
        data = _parse_goal_response(response)
        goal = data["goal"]

        metadata: dict[str, Any] = {}
        reason = data.get("reason", "")
        if isinstance(reason, str):
            metadata["reason"] = reason

        confidence = data.get("confidence")
        if isinstance(confidence, (int, float)) and math.isfinite(confidence):
            metadata["confidence"] = max(0.0, min(1.0, float(confidence)))

        return GoalGenerationOutput(goal=goal, metadata=metadata)


def _parse_goal_response(response: str) -> dict[str, Any]:
    extraction = parse_structured_output(
        response,
        policy=_GOAL_POLICY,
        validator=_validate_goal_output,
    )
    data = extraction.data
    if data is None:
        if extraction.error_category == "field_type" and "goal" in extraction.error:
            message = "LLM response missing valid 'goal' field"
        else:
            message = f"Failed to extract JSON from LLM response: {extraction.error}"
        raise EvaluationError(
            category=extraction.error_category or "json_parse_error",
            safe_message=message,
        )

    goal = data["goal"]
    assert isinstance(goal, str)
    data["goal"] = goal.strip()
    return data


def _validate_goal_output(data: dict[str, Any]) -> ValidationResult:
    goal = data.get("goal")
    if not isinstance(goal, str) or not goal.strip():
        return ValidationResult(False, "field_type", "goal must be a non-empty string")
    confidence = data.get("confidence")
    if confidence is not None and (
        isinstance(confidence, bool)
        or not isinstance(confidence, (int, float))
        or not math.isfinite(confidence)
    ):
        return ValidationResult(False, "field_type", "confidence must be a finite number")
    return ValidationResult(True)
