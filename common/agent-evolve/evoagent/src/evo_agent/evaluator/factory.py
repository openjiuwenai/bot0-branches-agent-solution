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
    """
    evaluator_type = config.get("type")

    if evaluator_type == "metric":
        return _create_metric_evaluator(config)
    elif evaluator_type == "llm":
        return _create_llm_evaluator(config)
    elif evaluator_type == "filtered":
        return _create_filtering_evaluator(config)
    else:
        raise ValueError(
            f"Unknown evaluator type: {evaluator_type!r}. "
            f"Supported types: 'metric', 'llm', 'filtered'."
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
