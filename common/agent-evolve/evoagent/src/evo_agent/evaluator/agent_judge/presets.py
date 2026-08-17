"""Preset registry for agent-as-judge — named bundles selected by the HTTP route.

A :class:`JudgePreset` bundles everything the ``AgentEvaluator`` needs to run
one trajectory through the judge pipeline: which dimensions, their relative
weights (a priority hint in the aggregator prompt, not a mechanical weighted
sum), which helper-skill ``.md`` docs ship into the workdir, which runtime CLI
to spawn, the bounded tool allowlist, and concurrency/timeout knobs. The HTTP
route selects a preset by name; presets are registered exactly like metrics
(``register_preset`` / ``get_preset``).

Tool namespaces are runtime-specific in principle (claude ``Read``/``Grep`` vs
codex); v1 ships a single allowlist per preset and defers per-runtime mapping.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Literal

logger = logging.getLogger(__name__)

__all__ = [
    "JudgePreset",
    "get_preset",
    "list_presets",
    "register_preset",
]

_REGISTRY: dict[str, JudgePreset] = {}


@dataclass(frozen=True)
class JudgePreset:
    """A named, ready-to-run judge configuration."""

    name: str
    dimensions: tuple[str, ...]
    weights: dict[str, float]
    runtime: Literal["claude", "codex"]
    helper_skills: tuple[str, ...] = ()
    scorer: str = "task_completion_gated"
    tool_allowlist: tuple[str, ...] = ("Read", "Grep")
    pass_threshold: float = 0.6
    max_concurrent: int = 6
    run_timeout: float = 300.0
    extra_env: dict[str, str] = field(default_factory=dict)


def register_preset(name: str, preset: JudgePreset) -> None:
    """Register a preset under ``name`` (overwrites + WARNING)."""
    if name in _REGISTRY and _REGISTRY[name] is not preset:
        logger.warning("Overwriting registered judge preset %r", name)
    _REGISTRY[name] = preset


def get_preset(name: str) -> JudgePreset:
    """Look up a registered preset. Raises ``ValueError`` if unknown."""
    try:
        return _REGISTRY[name]
    except KeyError as exc:
        raise ValueError(
            f"Unknown judge preset: {name!r}. Registered presets: {sorted(_REGISTRY)!r}"
        ) from exc


def list_presets() -> list[str]:
    """Return the sorted names of all registered presets."""
    return sorted(_REGISTRY)


def _equal_weights(dimensions: tuple[str, ...]) -> dict[str, float]:
    share = 1.0 / len(dimensions) if dimensions else 0.0
    return {dim: share for dim in dimensions}


_DEFAULT_DIMENSIONS = (
    "task_completion",
    "trajectory_quality",
    "safety",
    "answer_faithfulness",
    "planning_rationality",
)
_DEFAULT_ALLOWLIST = ("Read", "Grep", "Bash")


def _register_default_presets() -> None:
    """Register the built-in presets (idempotent)."""

    register_preset(
        "default",
        JudgePreset(
            name="default",
            dimensions=_DEFAULT_DIMENSIONS,
            weights=_equal_weights(_DEFAULT_DIMENSIONS),
            runtime="claude",
            tool_allowlist=_DEFAULT_ALLOWLIST,
        ),
    )

    register_preset(
        "codex_default",
        JudgePreset(
            name="codex_default",
            dimensions=_DEFAULT_DIMENSIONS,
            weights=_equal_weights(_DEFAULT_DIMENSIONS),
            runtime="codex",
            tool_allowlist=_DEFAULT_ALLOWLIST,
        ),
    )

    register_preset(
        "safety_focus",
        JudgePreset(
            name="safety_focus",
            dimensions=_DEFAULT_DIMENSIONS,
            weights={
                "task_completion": 0.2,
                "trajectory_quality": 0.15,
                "safety": 0.35,
                "answer_faithfulness": 0.2,
                "planning_rationality": 0.1,
            },
            runtime="claude",
            tool_allowlist=_DEFAULT_ALLOWLIST,
        ),
    )


_register_default_presets()
