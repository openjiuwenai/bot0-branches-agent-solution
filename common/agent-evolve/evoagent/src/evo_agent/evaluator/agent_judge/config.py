"""Configuration parsed by the agent-evaluator factory branch.

A plain frozen dataclass (not pydantic) because it carries openjiuwen model
config objects (:class:`ModelRequestConfig` / :class:`ModelClientConfig`) whose
construction and validation already happen upstream in the HTTP route's
``_build_llm_configs``. The factory (:func:`_create_agent_evaluator`) builds
this from the raw ``config`` dict, then resolves the preset, runtime adapter,
and aggregator to construct :class:`AgentEvaluator`.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig

__all__ = ["AgentEvaluatorConfig"]


@dataclass(frozen=True)
class AgentEvaluatorConfig:
    """Parsed configuration for ``type:"agent"`` evaluation.

    Attributes:
        preset: registered preset name (selects dimensions/weights/runtime/...).
        runtime: override the preset's runtime; ``None`` → use preset.runtime.
        tool_allowlist: override the preset's tool allowlist; ``None`` → preset.
        max_concurrent / run_timeout: override the preset's concurrency/timeout.
        model_config / model_client_config: aggregator LLM (required).
        skill_source: where attribution-target skill docs come from.
        skill_root: local skill root dir (``skill_source="local"``).
        scorer: override the preset's weight-calc scorer name; ``None`` → preset.
        aggregator_reserved_output_tokens: explicit aggregator output reserve.
        extra_env: extra environment for the judge subprocess (e.g. API keys).
    """

    preset: str
    model_config: ModelRequestConfig
    model_client_config: ModelClientConfig
    runtime: Literal["claude", "codex"] | None = None
    tool_allowlist: tuple[str, ...] | None = None
    max_concurrent: int | None = None
    run_timeout: float | None = None
    skill_source: Literal["local", "adapter", "none"] = "none"
    skill_root: str | None = None
    scorer: str | None = None
    aggregator_reserved_output_tokens: int | None = None
    extra_env: dict[str, str] = field(default_factory=dict)
