"""Standard evaluation input, trajectory, and LLM structured output models."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class TrajectoryMessage(BaseModel):
    """Single message in a trajectory, preserving the original dictionary structure."""

    role: str
    content: Any = None
    name: str | None = None
    tool_calls: list[dict[str, Any]] = Field(default_factory=list)
    tool_call_id: str | None = None
    reasoning_content: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class TrajectorySummary(BaseModel):
    """Trajectory summary for quick overview of the execution chain."""

    total_messages: int = 0
    tool_calls_used: list[str] = Field(default_factory=list)
    summary: str = ""
    total_steps: int = 0
    tool_calls_count: int = 0
    tokens_used: int = 0
    metadata: dict[str, Any] = Field(default_factory=dict)


class StandardTrajectory(BaseModel):
    """Complete conversation trajectory used as the evaluation source of truth."""

    model_config = ConfigDict(extra="forbid")

    summary: TrajectorySummary | None = None
    messages: list[TrajectoryMessage] = Field(default_factory=list)


class EvaluationStep(BaseModel):
    """Prompt-only representation of one useful trajectory event."""

    index: int
    role: str
    content: str | None = None
    tool_name: str | None = None
    tool_arguments: dict[str, Any] | str | None = None
    tool_result: str | None = None


class EvaluationTrajectory(BaseModel):
    """Simplified conversation trajectory sent to the evaluator model."""

    steps: list[EvaluationStep] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class EvaluationInput(BaseModel):
    """Conversation-level evaluation input.

    The trajectory is the primary fact source. ``skill_names`` is a required
    list of known skill names used solely for post-hoc attribution validation —
    it is not injected into the evaluator prompt.
    """

    model_config = ConfigDict(extra="forbid")

    trajectory: StandardTrajectory
    expected_result: dict[str, Any] | None = None
    skill_names: list[str]


class GoalGenerationInput(BaseModel):
    """Input for generating a user goal from a trajectory."""

    model_config = ConfigDict(extra="forbid")

    trajectory: StandardTrajectory


class GoalGenerationOutput(BaseModel):
    """Natural-language goal generated from a trajectory for optimizer use."""

    model_config = ConfigDict(extra="forbid")

    goal: str
    metadata: dict[str, Any] = Field(default_factory=dict)


class LLMEvaluationOutput(BaseModel):
    """Structured output from LLM evaluation — flat 3-dimension format."""

    task_completion: float = 0.0
    trajectory_quality: float = 0.0
    safety: float = 0.0
    is_pass: bool = True
    score: float = 0.0
    attributed_skill: str = ""
    reason: str = ""
