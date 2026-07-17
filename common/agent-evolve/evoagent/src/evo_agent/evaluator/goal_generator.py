"""Generate a natural-language user goal from a conversation trajectory."""

from __future__ import annotations

import json
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
    StandardTrajectory,
)
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.evaluators.llm import _extract_json, _run_coroutine
from evo_agent.evaluator.prompts.goal_generation import GOAL_GENERATION_PROMPT_TEMPLATE

_CONTENT_LIMIT = 1000
_ARGUMENT_LIMIT = 1000
_TOOL_RESULT_LIMIT = 1200


class TrajectoryGoalGenerator:
    """Generate a Chinese natural-language user goal from trajectory messages."""

    def __init__(
        self,
        model_config: ModelRequestConfig,
        model_client_config: ModelClientConfig,
    ) -> None:
        self._model = Model(model_client_config, model_config)

    def generate(self, value: GoalGenerationInput) -> GoalGenerationOutput:
        """Generate the final user goal represented by the full trajectory."""
        if not value.trajectory.messages:
            raise EvaluationError("Empty trajectory: no messages to generate goal from")

        prompt = _build_goal_prompt(value.trajectory)

        try:
            response = _run_coroutine(self._model.invoke([UserMessage(content=prompt)])).content
        except Exception as e:
            raise EvaluationError(f"LLM goal generation failed: {e}") from e

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


def _build_goal_prompt(trajectory: StandardTrajectory) -> str:
    messages = _simplify_trajectory_for_goal(trajectory)
    return GOAL_GENERATION_PROMPT_TEMPLATE.replace("{messages}", messages)


def _simplify_trajectory_for_goal(trajectory: StandardTrajectory) -> str:
    """Serialize trajectory facts for goal generation.

    Unlike evaluator simplification, all tool results are truncated, including
    ``read_file`` outputs, so skill documents do not dominate user-goal extraction.
    """
    simplified: list[dict[str, Any]] = []
    tool_names_by_id: dict[str, str] = {}

    for index, message in enumerate(trajectory.messages):
        if message.role in {"user", "assistant"}:
            item: dict[str, Any] = {
                "index": index,
                "role": message.role,
            }
            content = _stringify_content(message.content)
            if content:
                item["content"] = _truncate(content, _CONTENT_LIMIT)

            tool_calls = [_parse_tool_call(tc) for tc in message.tool_calls]
            tool_calls = [tc for tc in tool_calls if tc["tool_name"]]
            if tool_calls:
                item["tool_calls"] = [
                    {
                        "tool_name": tc["tool_name"],
                        "tool_arguments": tc["tool_arguments"],
                    }
                    for tc in tool_calls
                ]
                for tc in tool_calls:
                    tool_call_id = tc.get("tool_call_id")
                    if isinstance(tool_call_id, str) and tool_call_id:
                        tool_names_by_id[tool_call_id] = str(tc["tool_name"])

            if "content" in item or "tool_calls" in item:
                simplified.append(item)

        if message.role == "tool":
            content = _stringify_content(message.content)
            if not content:
                continue
            simplified.append(
                {
                    "index": index,
                    "role": "tool",
                    "tool_name": tool_names_by_id.get(message.tool_call_id or ""),
                    "tool_result": _truncate(content, _TOOL_RESULT_LIMIT),
                }
            )

    return json.dumps({"messages": simplified}, ensure_ascii=False, default=str)


def _parse_tool_call(tool_call: dict[str, Any]) -> dict[str, Any]:
    function = tool_call.get("function")
    if not isinstance(function, dict):
        return {
            "tool_name": None,
            "tool_arguments": None,
            "tool_call_id": tool_call.get("id"),
        }

    name = function.get("name")
    arguments = _parse_arguments(function.get("arguments"))
    return {
        "tool_name": name if isinstance(name, str) else None,
        "tool_arguments": arguments,
        "tool_call_id": tool_call.get("id"),
    }


def _parse_arguments(arguments: Any) -> dict[str, Any] | str | None:
    if arguments is None:
        return None
    if isinstance(arguments, dict):
        return _limit_arguments(arguments)
    if not isinstance(arguments, str):
        return _truncate(str(arguments), _ARGUMENT_LIMIT)
    try:
        parsed = json.loads(arguments)
    except json.JSONDecodeError:
        return _truncate(arguments, _ARGUMENT_LIMIT)
    if isinstance(parsed, dict):
        return _limit_arguments(parsed)
    return _truncate(arguments, _ARGUMENT_LIMIT)


def _limit_arguments(arguments: dict[str, Any]) -> dict[str, Any] | str:
    serialized = json.dumps(arguments, ensure_ascii=False, default=str)
    if len(serialized) <= _ARGUMENT_LIMIT:
        return arguments
    return _truncate(serialized, _ARGUMENT_LIMIT)


def _stringify_content(content: Any) -> str | None:
    if content is None:
        return None
    if isinstance(content, str):
        stripped = content.strip()
        return stripped or None
    return json.dumps(content, ensure_ascii=False, default=str)


def _truncate(value: str, limit: int) -> str:
    if len(value) <= limit:
        return value
    return value[:limit] + "…"


def _parse_goal_response(response: str) -> dict[str, Any]:
    try:
        data = _extract_json(response)
    except Exception as e:
        raise EvaluationError(f"Failed to extract JSON from LLM response: {e}") from e

    if not isinstance(data, dict):
        raise EvaluationError(f"Failed to extract JSON from LLM response: {response[:200]}")

    goal = data.get("goal")
    if not isinstance(goal, str) or not goal.strip():
        raise EvaluationError(f"LLM response missing valid 'goal' field: {goal!r}")
    data["goal"] = goal.strip()
    return data
