"""Configuration parsed by the agent-evaluator factory branch.

A plain frozen dataclass (not pydantic) documenting the fields the HTTP route
assembles into the raw ``config`` dict for ``type:"agent"`` evaluation. The
factory (:func:`_create_agent_evaluator`) reads that dict directly (this
dataclass is a typed mirror of it, not the construction path) and resolves the
preset, runtime adapter, scorer, and skill provider to construct
:class:`AgentEvaluator`.

The agent-as-judge chain is fully subprocess-driven (Agent + prompt + skill), so
there is no per-request LLM config here — the judge and attribution agents both
authenticate via the runtime subprocess's ambient environment.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

__all__ = ["AgentEvaluatorConfig"]


@dataclass(frozen=True)
class AgentEvaluatorConfig:
    """Parsed configuration for ``type:"agent"`` evaluation.

    Attributes:
        preset: registered preset name (selects dimensions/weights/runtime/...).
        runtime: override the preset's runtime; ``None`` → use preset.runtime.
        tool_allowlist: override the preset's tool allowlist; ``None`` → preset.
        max_concurrent / run_timeout: override the preset's concurrency/timeout.
        skill_source: where attribution-target skill docs come from.
        skill_root: local skill root dir (``skill_source="local"``).
        scorer: override the preset's weight-calc scorer name; ``None`` → preset.
        extra_env: extra environment for the judge subprocess (e.g. API keys).
        trajectory_budget: max token budget for the compacted trajectory
            (fed to the judge subprocess). ``None`` → module default
            (``_DEFAULT_TRAJECTORY_BUDGET = 4000``). Long trajectories (many
            messages or verbose tool returns) need a larger budget; if the
            compactor cannot fit the trajectory into the budget, evaluation
            fails with ``prompt_budget_exceeded``. Must be > 0 when set.
    """

    preset: str
    runtime: Literal["claude", "codex", "jiuwenswarm"] | None = None
    tool_allowlist: tuple[str, ...] | None = None
    max_concurrent: int | None = None
    run_timeout: float | None = None
    skill_source: Literal["local", "adapter", "none"] = "none"
    skill_root: str | None = None
    scorer: str | None = None
    extra_env: dict[str, str] = field(default_factory=dict)
    trajectory_budget: int | None = None
    agent_profile: str | None = None
