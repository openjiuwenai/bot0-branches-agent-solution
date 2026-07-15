"""Shared contracts and helpers for trajectory filters."""

from __future__ import annotations

import re
from typing import Protocol

from evo_agent.evaluator.domain.models import StandardTrajectory
from evo_agent.evaluator.filters.models import FilterMatch

CompiledPattern = tuple[str, re.Pattern[str]]


class TrajectoryFilter(Protocol):
    """Inspect a standard trajectory and return every deterministic match."""

    name: str

    def inspect(self, trajectory: StandardTrajectory) -> list[FilterMatch]: ...


def build_patterns(
    defaults: dict[str, str],
    custom_patterns: list[str] | None,
    *,
    replace_defaults: bool,
) -> list[CompiledPattern]:
    """Compile default and custom regex patterns into a unified list.

    Args:
        defaults: Named default patterns (rule_id -> regex string).
        custom_patterns: Additional user-supplied regex strings.
        replace_defaults: When True, discard defaults and use only custom patterns.

    Returns:
        List of (rule_id, compiled_pattern) tuples.

    Raises:
        re.error: If any pattern is an invalid regular expression.
    """
    sources = {} if replace_defaults else dict(defaults)
    for index, pattern in enumerate(custom_patterns or [], start=1):
        sources[f"custom_{index}"] = pattern
    return [(rule_id, re.compile(pattern, re.IGNORECASE)) for rule_id, pattern in sources.items()]


def bounded_evidence(value: str, limit: int = 500) -> str:
    """Truncate evidence string to a bounded length with ellipsis suffix."""
    compact = value.strip()
    return compact if len(compact) <= limit else compact[:limit] + "..."
