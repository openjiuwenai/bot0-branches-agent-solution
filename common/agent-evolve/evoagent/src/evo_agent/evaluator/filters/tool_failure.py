"""Detect deterministic failure signals in tool result messages."""

from __future__ import annotations

import json
from typing import Any

from evo_agent.evaluator.domain.models import StandardTrajectory
from evo_agent.evaluator.filters.base import bounded_evidence, build_patterns
from evo_agent.evaluator.filters.models import FilterMatch

DEFAULT_TOOL_FAILURE_PATTERNS = {
    "timeout": r"\btimeout\b|\btimed\s*out\b|超时",
    "failure": r"\bfail(?:ed)?\b|\bfailure\b|调用失败|执行失败|连接失败",
    "exception": r"\bexception\b|发生异常",
    "error": r"\berror\b",
}
_FAILURE_STATUSES = {"failed", "failure", "error", "timeout"}
_SUCCESS_STATUSES = {"success", "succeeded", "ok", "completed"}


class ToolFailureFilter:
    """Inspect tool result messages for structured and keyword-based failure signals."""

    name = "tool_failure"

    def __init__(
        self,
        patterns: list[str] | None = None,
        *,
        replace_default_patterns: bool = False,
    ) -> None:
        self._patterns = build_patterns(
            DEFAULT_TOOL_FAILURE_PATTERNS,
            patterns,
            replace_defaults=replace_default_patterns,
        )

    def inspect(self, trajectory: StandardTrajectory) -> list[FilterMatch]:
        """Return every failure match found in tool result messages."""
        matches: list[FilterMatch] = []
        for index, message in enumerate(trajectory.messages):
            if message.role != "tool":
                continue
            structured = _parse_structured(message.content)
            structured_state = _structured_failure_state(structured)
            evidence = bounded_evidence(_stringify(message.content))
            if structured_state is True:
                matches.append(
                    FilterMatch(
                        filter_type="tool_failure",
                        rule_id="structured_failure",
                        message_index=index,
                        evidence=evidence,
                        metadata=structured if isinstance(structured, dict) else {},
                    )
                )
                continue
            if structured_state is False:
                continue
            for rule_id, pattern in self._patterns:
                if pattern.search(evidence):
                    matches.append(
                        FilterMatch(
                            filter_type="tool_failure",
                            rule_id=rule_id,
                            message_index=index,
                            evidence=evidence,
                            pattern=pattern.pattern,
                        )
                    )
                    break
        return matches


def _parse_structured(content: Any) -> Any:
    """Attempt to parse content as JSON, returning the original on failure."""
    if isinstance(content, (dict, list)):
        return content
    if isinstance(content, str):
        try:
            return json.loads(content)
        except json.JSONDecodeError:
            return None
    return None


def _structured_failure_state(value: Any) -> bool | None:
    """Classify a structured value as failure (True), success (False), or unknown (None).

    Detection priority:
    1. Numeric ``code`` values other than 0 → failure; code 0 → success.
    2. String ``status`` in known failure/success sets.
    3. Boolean ``success`` field.
    4. Non-empty ``error`` or ``exception`` fields → failure.
    """
    if not isinstance(value, dict):
        return None

    code = value.get("code")
    if isinstance(code, int) and not isinstance(code, bool):
        if code != 0:
            return True
        return False

    status = value.get("status")
    if isinstance(status, str):
        normalized = status.strip().lower()
        if normalized in _FAILURE_STATUSES:
            return True
        if normalized in _SUCCESS_STATUSES:
            return False

    success = value.get("success")
    if success is False:
        return True
    if success is True:
        return False

    if value.get("error") not in (None, "", False, 0, [], {}):
        return True
    if value.get("exception") not in (None, "", False, 0, [], {}):
        return True
    return None


def _stringify(content: Any) -> str:
    """Convert content to a string representation for regex matching."""
    if isinstance(content, str):
        return content
    return json.dumps(content, ensure_ascii=False, default=str)
