"""OpenJiuwen adapter for conversation-level evaluation."""

from __future__ import annotations

from typing import Any

from openjiuwen.agent_evolving.dataset import Case

from evo_agent.evaluator.domain.models import EvaluationInput

CONVERSATION_PREDICTION = {"evaluation_source": "conversation_trajectory"}


def to_case_and_placeholder(value: EvaluationInput) -> tuple[Case, dict[str, Any]]:
    """Convert an ``EvaluationInput`` to OpenJiuwen's evaluator interface.

    OpenJiuwen requires a prediction argument. Conversation evaluation does not use
    one, so the adapter supplies a stable internal placeholder.
    """
    inputs: dict[str, Any] = {"trajectory": value.trajectory.model_dump()}
    if value.skill_names:
        inputs["skill_names"] = value.skill_names
    label: dict[str, Any] = {"expected_result": value.expected_result}
    case = Case(inputs=inputs, label=label)
    return case, dict(CONVERSATION_PREDICTION)
