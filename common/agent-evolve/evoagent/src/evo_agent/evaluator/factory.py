"""Evaluator factory — build evaluators from explicit configuration."""

from __future__ import annotations

from typing import Any

from openjiuwen.agent_evolving.evaluator.evaluator import BaseEvaluator
from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig

from evo_agent.evaluator.evaluators.filtering import FilteringEvaluator
from evo_agent.evaluator.evaluators.llm import LLMEvaluator
from evo_agent.evaluator.evaluators.metric import MetricEvaluator
from evo_agent.evaluator.filters.base import TrajectoryFilter
from evo_agent.evaluator.filters.tool_failure import ToolFailureFilter
from evo_agent.evaluator.filters.user_feedback import UserFeedbackFilter
from evo_agent.evaluator.metrics.extract import (
    AnswerFieldExtractConfig,
    parse_extract_config,
)
from evo_agent.evaluator.metrics.field_exact_match import FieldExtractExactMatchMetric
from evo_agent.evaluator.metrics.registry import get_batch_metric, get_metric


def create_evaluator(config: dict[str, Any]) -> BaseEvaluator:
    """Build an evaluator from explicit configuration.

    Args:
        config: Evaluator configuration.

    Returns:
        An OpenJiuwen ``BaseEvaluator``.

    Raises:
        ValueError: unknown type or missing required configuration.

    Configuration examples::

        # Deterministic evaluation
        create_evaluator({"type": "metric", "metric": "exact_match"})
        create_evaluator({"type": "metric", "metric": "normalized_exact_match"})
        # Multiple per-case metrics (registered names; custom ones accepted)
        create_evaluator({"type": "metric", "metric": ["exact_match", "contains"]})
        # Batch-level F1/ACC as the validation score (coexists with _mean_score)
        create_evaluator({
            "type": "metric",
            "metric": "exact_match",
            "batch_metrics": ["set_overlap"],
            "batch_score": "f1",
        })

        # LLM evaluation
        create_evaluator({
            "type": "llm",
            "model_config": model_config,
            "model_client_config": model_client_config,
        })

        # Filtered evaluation
        create_evaluator({
            "type": "filtered",
            "delegate": {"type": "llm", ...},
            "filters": {
                "tool_failure": {"enabled": True},
                "user_feedback": {"enabled": True},
            },
        })

        # Agent-as-judge (HTTP-only; drives claude/codex CLIs as subprocesses)
        create_evaluator({
            "type": "agent",
            "preset": "default",
            "model_config": model_config,
            "model_client_config": model_client_config,
        })
    """
    evaluator_type = config.get("type")

    if evaluator_type == "metric":
        return _create_metric_evaluator(config)
    elif evaluator_type == "llm":
        return _create_llm_evaluator(config)
    elif evaluator_type == "filtered":
        return _create_filtering_evaluator(config)
    elif evaluator_type == "agent":
        return _create_agent_evaluator(config)
    else:
        raise ValueError(
            f"Unknown evaluator type: {evaluator_type!r}. "
            f"Supported types: 'metric', 'llm', 'filtered', 'agent'."
        )


def _create_metric_evaluator(config: dict[str, Any]) -> MetricEvaluator:
    # ``metric`` is a registered name (str) or a list of names. Defaults to
    # ``exact_match`` for backward compatibility. Each name resolves via the
    # runtime registry, so custom metrics registered through
    # ``register_metric`` are accepted here too.
    metric_spec = config.get("metric", "exact_match")
    if isinstance(metric_spec, str):
        metric_names: list[str] = [metric_spec]
    elif isinstance(metric_spec, list):
        if not metric_spec:
            raise ValueError("'metric' list must not be empty")
        metric_names = [str(m) for m in metric_spec]
    else:
        raise TypeError(f"'metric' must be a str or list[str], got {type(metric_spec).__name__}")

    extract_cfg = parse_extract_config(config.get("extract"))
    metrics = [_build_metric_instance(name, extract_cfg) for name in metric_names]

    aggregate = config.get("aggregate", "mean")

    batch_metric_names = config.get("batch_metrics", [])
    if not isinstance(batch_metric_names, list):
        raise TypeError("'batch_metrics' must be a list[str]")
    batch_score = config.get("batch_score", "")
    if not isinstance(batch_score, str):
        raise TypeError("'batch_score' must be a str")

    # batch_metrics and batch_score must be configured together (both set or
    # both empty) — MetricEvaluator.__init__ also enforces this, but validate
    # early with a clear message.
    if bool(batch_metric_names) != bool(batch_score):
        raise ValueError(
            "batch_metrics and batch_score must be configured together (both set, or both empty)."
        )

    batch_metrics = [get_batch_metric(str(name))() for name in batch_metric_names] or None

    return MetricEvaluator(
        metrics=metrics,
        aggregate=aggregate,
        batch_metrics=batch_metrics,
        batch_score=batch_score,
    )


def _build_metric_instance(name: str, extract_cfg: AnswerFieldExtractConfig | None) -> Any:
    """Build one metric; wrap exact_match with field extract when configured."""
    if extract_cfg is None:
        return get_metric(name)()

    if name == "exact_match":
        return FieldExtractExactMatchMetric(extract_cfg, normalize=False)
    if name == "normalized_exact_match":
        return FieldExtractExactMatchMetric(extract_cfg, normalize=True)

    raise ValueError(
        f"extract is only supported for exact_match / normalized_exact_match, got {name!r}"
    )


def _create_llm_evaluator(config: dict[str, Any]) -> LLMEvaluator:
    model_config = config.get("model_config")
    model_client_config = config.get("model_client_config")

    if model_config is None:
        raise ValueError("LLM evaluator requires 'model_config'.")
    if model_client_config is None:
        raise ValueError("LLM evaluator requires 'model_client_config'.")

    if not isinstance(model_config, ModelRequestConfig):
        raise TypeError(
            f"'model_config' must be ModelRequestConfig, got {type(model_config).__name__}."
        )
    if not isinstance(model_client_config, ModelClientConfig):
        raise TypeError(
            f"'model_client_config' must be ModelClientConfig, "
            f"got {type(model_client_config).__name__}."
        )

    aggregate = config.get("aggregate", "mean")
    prompt_template = config.get("prompt_template")
    return LLMEvaluator(
        model_config=model_config,
        model_client_config=model_client_config,
        aggregate=aggregate,
        prompt_template=prompt_template,
    )


def _create_filtering_evaluator(config: dict[str, Any]) -> FilteringEvaluator:
    """Build a FilteringEvaluator with recursive delegate and configured filters."""
    delegate_config = config.get("delegate")
    if not isinstance(delegate_config, dict):
        raise ValueError("Filtered evaluator requires a 'delegate' configuration.")
    if delegate_config.get("type") == "filtered":
        raise ValueError("Filtered evaluator delegate cannot be 'filtered'.")

    filter_config = config.get("filters", {})
    if not isinstance(filter_config, dict):
        raise TypeError("'filters' must be a dictionary.")

    filters: list[TrajectoryFilter] = []

    tool_config = filter_config.get("tool_failure", {})
    if not isinstance(tool_config, dict):
        raise TypeError("'tool_failure' filter configuration must be a dictionary.")
    if tool_config.get("enabled") is True:
        filters.append(
            ToolFailureFilter(
                patterns=tool_config.get("patterns"),
                replace_default_patterns=tool_config.get("replace_default_patterns", False),
            )
        )

    feedback_config = filter_config.get("user_feedback", {})
    if not isinstance(feedback_config, dict):
        raise TypeError("'user_feedback' filter configuration must be a dictionary.")
    if feedback_config.get("enabled") is True:
        filters.append(
            UserFeedbackFilter(
                patterns=feedback_config.get("patterns"),
                replace_default_patterns=feedback_config.get("replace_default_patterns", False),
                skip_initial_user_messages=feedback_config.get("skip_initial_user_messages", 1),
            )
        )

    if not filters:
        raise ValueError("Filtered evaluator requires at least one enabled filter.")
    return FilteringEvaluator(delegate=create_evaluator(delegate_config), filters=filters)


def _create_agent_evaluator(config: dict[str, Any]) -> Any:
    """Build an AgentEvaluator (agent-as-judge) from explicit configuration.

    Drives real coding-agent CLIs (claude / codex) as bounded subprocesses across
    a preset's dimensions, then fuses the verdicts via an LLM aggregator.
    Scope is HTTP-only — it is **not** wired into the optimization pipeline.

    Inputs are read from the raw ``config`` dict (the HTTP route assembles it);
    per-dimension overrides are folded into a resolved preset via
    :func:`dataclasses.replace` so :class:`AgentEvaluator` reads every knob from
    its preset object alone.

    Imports are local to avoid pulling the agent_judge package + LLM invocation
    stack into the import path of the other evaluator types.
    """
    import dataclasses
    from pathlib import Path

    from openjiuwen.core.foundation.llm import Model

    from evo_agent.evaluator.agent_judge.aggregator import SkillAggregator
    from evo_agent.evaluator.agent_judge.presets import get_preset
    from evo_agent.evaluator.agent_judge.runtime import make_runtime
    from evo_agent.evaluator.agent_judge.scorers import get_scorer
    from evo_agent.evaluator.evaluators.agent import AgentEvaluator
    from evo_agent.evaluator.golden_data.skill_provider import make_skill_provider
    from evo_agent.llm.invocation import LLMInvocation, LLMProviderCapabilities

    preset_name = config.get("preset")
    if not isinstance(preset_name, str) or not preset_name:
        raise ValueError("Agent evaluator requires a 'preset' name.")
    preset = get_preset(preset_name)

    runtime = config.get("runtime") or preset.runtime
    if runtime not in ("claude", "codex"):
        raise ValueError(f"Agent evaluator 'runtime' must be 'claude' or 'codex', got {runtime!r}.")

    model_config = config.get("model_config")
    model_client_config = config.get("model_client_config")
    if model_config is None:
        raise ValueError("Agent evaluator requires 'model_config'.")
    if model_client_config is None:
        raise ValueError("Agent evaluator requires 'model_client_config'.")
    if not isinstance(model_config, ModelRequestConfig):
        raise TypeError(
            f"'model_config' must be ModelRequestConfig, got {type(model_config).__name__}."
        )
    if not isinstance(model_client_config, ModelClientConfig):
        raise TypeError(
            f"'model_client_config' must be ModelClientConfig, "
            f"got {type(model_client_config).__name__}."
        )

    tool_allowlist_raw = config.get("tool_allowlist")
    if tool_allowlist_raw is None:
        tool_allowlist = preset.tool_allowlist
    elif isinstance(tool_allowlist_raw, list):
        tool_allowlist = tuple(str(t) for t in tool_allowlist_raw)
    else:
        raise TypeError("'tool_allowlist' must be a list[str].")

    max_concurrent = config.get("max_concurrent") or preset.max_concurrent
    run_timeout = config.get("run_timeout") or preset.run_timeout
    reserved = (
        config.get("aggregator_reserved_output_tokens") or preset.aggregator_reserved_output_tokens
    )

    extra_env = dict(preset.extra_env)
    extra_env_raw = config.get("extra_env")
    if isinstance(extra_env_raw, dict):
        extra_env.update({str(k): str(v) for k, v in extra_env_raw.items()})

    resolved_preset = dataclasses.replace(
        preset,
        runtime=runtime,
        tool_allowlist=tool_allowlist,
        max_concurrent=max_concurrent,
        run_timeout=run_timeout,
        aggregator_reserved_output_tokens=reserved,
        extra_env=extra_env,
    )

    # Skill doc source for the aggregator's attribution prompt.
    skill_source = config.get("skill_source", "none")
    skill_provider = None
    if skill_source == "local":
        skill_root = config.get("skill_root")
        if not isinstance(skill_root, str) or not skill_root:
            raise ValueError("skill_source='local' requires 'skill_root'.")
        skill_provider = make_skill_provider("local", skill_root=Path(skill_root))
    elif skill_source == "adapter":
        adapter_client = config.get("adapter_client")
        if adapter_client is None:
            raise ValueError("skill_source='adapter' requires 'adapter_client'.")
        skill_provider = make_skill_provider("adapter", adapter_client=adapter_client)
    elif skill_source != "none":
        raise ValueError(
            f"Unknown skill_source: {skill_source!r} (use 'local', 'adapter', or 'none')."
        )

    invocation = LLMInvocation(
        Model(model_client_config, model_config),
        capabilities=LLMProviderCapabilities(
            context_window_tokens=32768,
            supports_max_output_tokens=False,
            supports_finish_reason=True,
            supports_usage=True,
            supports_json_mode=True,
            completion_signal="either",
        ),
        parallelism=4,
        safety_margin_tokens=512,
        chars_per_token=2.0,
        default_output_reserve_tokens=1200,
    )
    scorer_name = config.get("scorer") or preset.scorer
    scorer = get_scorer(scorer_name)
    aggregator = SkillAggregator(
        invocation,
        scorer=scorer,
        reserved_output_tokens=reserved,
        skill_provider=skill_provider,
    )
    runtime_adapter = make_runtime(runtime, extra_env=extra_env)

    workdir_base = config.get("workdir_base")
    keep_on_error = bool(config.get("keep_on_error", False))

    return AgentEvaluator(
        preset=resolved_preset,
        runtime=runtime_adapter,
        aggregator=aggregator,
        workdir_base=workdir_base if isinstance(workdir_base, str) else None,
        keep_on_error=keep_on_error,
    )
