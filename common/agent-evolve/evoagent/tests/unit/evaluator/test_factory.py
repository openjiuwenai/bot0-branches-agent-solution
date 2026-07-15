"""create_evaluator factory 单元测试 — 类型路由 + 配置校验。"""

from __future__ import annotations

from unittest.mock import patch

import pytest
from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig

from evo_agent.evaluator.evaluators.filtering import FilteringEvaluator
from evo_agent.evaluator.evaluators.llm import LLMEvaluator
from evo_agent.evaluator.evaluators.metric import MetricEvaluator
from evo_agent.evaluator.factory import create_evaluator

_MODEL_PATCH = "evo_agent.evaluator.evaluators.llm.Model"


def _model_config() -> ModelRequestConfig:
    return ModelRequestConfig(model_name="m", temperature=0.1, max_tokens=128)


def _client_config() -> ModelClientConfig:
    return ModelClientConfig(
        client_provider="OpenAI", api_key="k", api_base="http://x", verify_ssl=False
    )


class TestMetricFactory:
    def test_default_metric_is_exact_match(self) -> None:
        evaluator = create_evaluator({"type": "metric"})
        assert isinstance(evaluator, MetricEvaluator)

    def test_explicit_exact_match(self) -> None:
        evaluator = create_evaluator({"type": "metric", "metric": "exact_match"})
        assert isinstance(evaluator, MetricEvaluator)

    def test_normalized_exact_match(self) -> None:
        evaluator = create_evaluator({"type": "metric", "metric": "normalized_exact_match"})
        assert isinstance(evaluator, MetricEvaluator)

    def test_custom_aggregate(self) -> None:
        evaluator = create_evaluator({"type": "metric", "aggregate": "min"})
        assert isinstance(evaluator, MetricEvaluator)

    def test_unknown_metric_raises(self) -> None:
        with pytest.raises(ValueError, match="Unknown metric"):
            create_evaluator({"type": "metric", "metric": "f1"})

    def test_metric_list_multiple(self) -> None:
        evaluator = create_evaluator({"type": "metric", "metric": ["exact_match", "contains"]})
        assert isinstance(evaluator, MetricEvaluator)
        assert len(evaluator._metrics) == 2

    def test_metric_list_empty_raises(self) -> None:
        with pytest.raises(ValueError, match="empty"):
            create_evaluator({"type": "metric", "metric": []})

    def test_metric_wrong_type_raises(self) -> None:
        with pytest.raises(TypeError, match="must be a str or list"):
            create_evaluator({"type": "metric", "metric": 123})  # type: ignore[dict-item]

    def test_batch_metrics_and_score(self) -> None:
        evaluator = create_evaluator(
            {
                "type": "metric",
                "metric": "exact_match",
                "batch_metrics": ["set_overlap"],
                "batch_score": "f1",
            }
        )
        assert isinstance(evaluator, MetricEvaluator)
        assert evaluator.batch_score == "f1"

    def test_batch_metrics_without_score_raises(self) -> None:
        with pytest.raises(ValueError, match="together"):
            create_evaluator({"type": "metric", "batch_metrics": ["set_overlap"]})

    def test_batch_score_without_metrics_raises(self) -> None:
        with pytest.raises(ValueError, match="together"):
            create_evaluator({"type": "metric", "batch_score": "f1"})

    def test_unknown_batch_metric_raises(self) -> None:
        with pytest.raises(ValueError, match="Unknown batch metric"):
            create_evaluator(
                {
                    "type": "metric",
                    "batch_metrics": ["definitely_not_registered"],
                    "batch_score": "f1",
                }
            )

    def test_custom_registered_metric_resolves(self) -> None:
        from evo_agent.evaluator.metrics import Metric, register_metric

        class _Custom(Metric):
            @property
            def name(self) -> str:
                return "custom_factory_metric"

            def compute(self, prediction: object, label: object, **kwargs: object) -> float:
                return 1.0

        register_metric("test_factory_custom", _Custom)
        evaluator = create_evaluator({"type": "metric", "metric": "test_factory_custom"})
        assert isinstance(evaluator, MetricEvaluator)
        assert evaluator._metrics[0].name == "custom_factory_metric"


class TestLLMFactory:
    def test_creates_llm_evaluator(self) -> None:
        with patch(_MODEL_PATCH):
            evaluator = create_evaluator(
                {
                    "type": "llm",
                    "model_config": _model_config(),
                    "model_client_config": _client_config(),
                }
            )
        assert isinstance(evaluator, LLMEvaluator)

    def test_missing_model_config_raises(self) -> None:
        with pytest.raises(ValueError, match="model_config"):
            create_evaluator({"type": "llm", "model_client_config": _client_config()})

    def test_missing_client_config_raises(self) -> None:
        with pytest.raises(ValueError, match="model_client_config"):
            create_evaluator({"type": "llm", "model_config": _model_config()})

    def test_wrong_type_model_config_raises(self) -> None:
        with pytest.raises(TypeError, match="model_config"):
            create_evaluator(
                {"type": "llm", "model_config": "bad", "model_client_config": _client_config()}
            )

    def test_wrong_type_client_config_raises(self) -> None:
        with pytest.raises(TypeError, match="model_client_config"):
            create_evaluator(
                {"type": "llm", "model_config": _model_config(), "model_client_config": "bad"}
            )

    def test_prompt_template_forwarded(self) -> None:
        with patch(_MODEL_PATCH):
            evaluator = create_evaluator(
                {
                    "type": "llm",
                    "model_config": _model_config(),
                    "model_client_config": _client_config(),
                    "prompt_template": "custom {messages}",
                }
            )
        assert isinstance(evaluator, LLMEvaluator)
        assert evaluator._prompt_template == "custom {messages}"


class TestFilteredFactory:
    def test_creates_filtering_evaluator(self) -> None:
        with patch(_MODEL_PATCH):
            evaluator = create_evaluator(
                {
                    "type": "filtered",
                    "delegate": {
                        "type": "llm",
                        "model_config": _model_config(),
                        "model_client_config": _client_config(),
                    },
                    "filters": {"tool_failure": {"enabled": True}},
                }
            )
        assert isinstance(evaluator, FilteringEvaluator)
        assert len(evaluator._filters) == 1

    def test_both_filters_enabled(self) -> None:
        with patch(_MODEL_PATCH):
            evaluator = create_evaluator(
                {
                    "type": "filtered",
                    "delegate": {
                        "type": "llm",
                        "model_config": _model_config(),
                        "model_client_config": _client_config(),
                    },
                    "filters": {
                        "tool_failure": {"enabled": True},
                        "user_feedback": {"enabled": True},
                    },
                }
            )
        assert isinstance(evaluator, FilteringEvaluator)
        assert len(evaluator._filters) == 2

    def test_metric_delegate(self) -> None:
        evaluator = create_evaluator(
            {
                "type": "filtered",
                "delegate": {"type": "metric"},
                "filters": {"user_feedback": {"enabled": True}},
            }
        )
        assert isinstance(evaluator, FilteringEvaluator)

    def test_missing_delegate_raises(self) -> None:
        with pytest.raises(ValueError, match="delegate"):
            create_evaluator({"type": "filtered", "filters": {"tool_failure": {"enabled": True}}})

    def test_delegate_not_dict_raises(self) -> None:
        with pytest.raises(ValueError, match="delegate"):
            create_evaluator(
                {
                    "type": "filtered",
                    "delegate": "bad",
                    "filters": {"tool_failure": {"enabled": True}},
                }
            )

    def test_nested_filtered_delegate_raises(self) -> None:
        with pytest.raises(ValueError, match="cannot be 'filtered'"):
            create_evaluator(
                {
                    "type": "filtered",
                    "delegate": {"type": "filtered"},
                    "filters": {"tool_failure": {"enabled": True}},
                }
            )

    def test_filters_not_dict_raises(self) -> None:
        with pytest.raises(TypeError, match="'filters' must be a dictionary"):
            create_evaluator(
                {
                    "type": "filtered",
                    "delegate": {"type": "metric"},
                    "filters": "bad",
                }
            )

    def test_tool_failure_config_not_dict_raises(self) -> None:
        with pytest.raises(TypeError, match="tool_failure"):
            create_evaluator(
                {
                    "type": "filtered",
                    "delegate": {"type": "metric"},
                    "filters": {"tool_failure": "bad"},
                }
            )

    def test_user_feedback_config_not_dict_raises(self) -> None:
        with pytest.raises(TypeError, match="user_feedback"):
            create_evaluator(
                {
                    "type": "filtered",
                    "delegate": {"type": "metric"},
                    "filters": {"user_feedback": "bad"},
                }
            )

    def test_no_enabled_filter_raises(self) -> None:
        with pytest.raises(ValueError, match="at least one enabled filter"):
            create_evaluator(
                {
                    "type": "filtered",
                    "delegate": {"type": "metric"},
                    "filters": {"tool_failure": {"enabled": False}},
                }
            )

    def test_filter_options_forwarded(self) -> None:
        with patch(_MODEL_PATCH):
            evaluator = create_evaluator(
                {
                    "type": "filtered",
                    "delegate": {
                        "type": "llm",
                        "model_config": _model_config(),
                        "model_client_config": _client_config(),
                    },
                    "filters": {
                        "user_feedback": {
                            "enabled": True,
                            "patterns": [r"retry"],
                            "replace_default_patterns": True,
                            "skip_initial_user_messages": 2,
                        }
                    },
                }
            )
        feedback_filter = evaluator._filters[0]
        assert feedback_filter._skip_initial_user_messages == 2
        # custom pattern → rule_id custom_1；replace_default 后默认被丢弃
        # skip_initial=2 → 前 2 条 user 被跳过，第 3 条参与检查
        from evo_agent.evaluator.domain.models import StandardTrajectory

        traj = StandardTrajectory.model_validate(
            {
                "messages": [
                    {"role": "user", "content": "任务 1"},
                    {"role": "user", "content": "任务 2 也跳过"},
                    {"role": "user", "content": "retry please"},
                ]
            }
        )
        matches = feedback_filter.inspect(traj)
        assert len(matches) == 1
        assert matches[0].rule_id == "custom_1"


class TestUnknownType:
    def test_unknown_type_raises(self) -> None:
        with pytest.raises(ValueError, match="Unknown evaluator type"):
            create_evaluator({"type": "magic"})

    def test_missing_type_raises(self) -> None:
        with pytest.raises(ValueError, match="Unknown evaluator type"):
            create_evaluator({})  # type: ignore[dict-item]
