"""Detect explicit optimization feedback in later user messages."""

from __future__ import annotations

import json
from typing import Any

from evo_agent.evaluator.domain.models import StandardTrajectory
from evo_agent.evaluator.filters.base import bounded_evidence, build_patterns
from evo_agent.evaluator.filters.models import FilterMatch

DEFAULT_USER_FEEDBACK_PATTERNS = {
    "explicit_rejection": r"不对|错了|不是这样|这不正确",
    "correction_instruction": r"你应该|你要这样|应该先|重新做|重新回答",
    "unresolved_outcome": r"没有解决|还是不行|没有按要求",
}


class UserFeedbackFilter:
    """Inspect later user messages for explicit correction or rejection signals.

    The first user message is treated as the original task and is never checked.
    Only user-role messages are inspected — assistant and tool messages are skipped
    to avoid matching quoted or generated correction language.
    """

    name = "user_feedback"

    def __init__(
        self,
        patterns: list[str] | None = None,
        *,
        replace_default_patterns: bool = False,
        skip_initial_user_messages: int = 1,
    ) -> None:
        if skip_initial_user_messages < 0:
            raise ValueError("skip_initial_user_messages must be non-negative")
        self._skip_initial_user_messages = skip_initial_user_messages
        self._patterns = build_patterns(
            DEFAULT_USER_FEEDBACK_PATTERNS,
            patterns,
            replace_defaults=replace_default_patterns,
        )

    def inspect(self, trajectory: StandardTrajectory) -> list[FilterMatch]:
        """Return every feedback match found in later user messages."""
        matches: list[FilterMatch] = []
        user_count = 0
        for index, message in enumerate(trajectory.messages):
            if message.role != "user":
                continue
            user_count += 1
            if user_count <= self._skip_initial_user_messages:
                continue
            evidence = bounded_evidence(_stringify(message.content))
            for rule_id, pattern in self._patterns:
                if pattern.search(evidence):
                    matches.append(
                        FilterMatch(
                            filter_type="user_feedback",
                            rule_id=rule_id,
                            message_index=index,
                            evidence=evidence,
                            pattern=pattern.pattern,
                        )
                    )
                    break
        return matches


def _stringify(content: Any) -> str:
    """Convert content to a string representation for regex matching."""
    if isinstance(content, str):
        return content
    return json.dumps(content, ensure_ascii=False, default=str)
