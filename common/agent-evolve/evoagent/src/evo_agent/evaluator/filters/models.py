"""Structured results produced by deterministic trajectory filters."""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

FilterType = Literal["tool_failure", "user_feedback"]
EvaluationStatus = Literal["evaluated", "filtered"]


class FilterMatch(BaseModel):
    """One deterministic signal that classified a trajectory as a bad case."""

    filter_type: FilterType
    rule_id: str
    message_index: int
    evidence: str
    pattern: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
