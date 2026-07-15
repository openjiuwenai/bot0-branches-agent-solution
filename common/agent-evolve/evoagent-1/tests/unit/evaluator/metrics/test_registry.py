"""Runtime metric registry tests — register/get, overwrite warning, unknown raises.

Built-in metrics (exact_match, contains, regex, set_overlap, ...) are registered
on package import; these tests also register throwaway custom metrics.
"""

from __future__ import annotations

import pytest

from evo_agent.evaluator.metrics.base import BatchMetric, Metric
from evo_agent.evaluator.metrics.registry import (
    get_batch_metric,
    get_metric,
    register_batch_metric,
    register_metric,
)


class TestBuiltinsRegistered:
    def test_per_case_builtins(self) -> None:
        for name in (
            "exact_match",
            "normalized_exact_match",
            "contains",
            "keyword_hit",
            "keyword_recall",
            "regex",
            "numeric_tolerance",
        ):
            metric = get_metric(name)()
            assert isinstance(metric, Metric)
            assert metric.name

    def test_batch_builtin(self) -> None:
        metric = get_batch_metric("set_overlap")()
        assert isinstance(metric, BatchMetric)
        assert metric.name == "set_overlap"

    def test_exact_match_is_case_sensitive(self) -> None:
        # exact_match must use normalize=False (preserve old factory semantics).
        from openjiuwen.agent_evolving.evaluator.metrics.exact_match import ExactMatchMetric

        assert isinstance(get_metric("exact_match")(), ExactMatchMetric)
        # case-sensitive: "Hello" != "hello"
        assert get_metric("exact_match")().compute("Hello", "hello") == 0.0
        # normalized: equal
        assert get_metric("normalized_exact_match")().compute("Hello", "hello") == 1.0


class _MyMetric(Metric):
    @property
    def name(self) -> str:
        return "my_metric"

    def compute(self, prediction: object, label: object, **kwargs: object) -> float:
        return 1.0


class _OtherMetric(Metric):
    @property
    def name(self) -> str:
        return "other_metric"

    def compute(self, prediction: object, label: object, **kwargs: object) -> float:
        return 0.0


class _MyBatch:
    name = "my_batch"

    def reset(self) -> None:
        pass

    def accumulate(self, prediction: object, label: object, **kwargs: object) -> None:
        pass

    def aggregate(self) -> dict[str, float]:
        return {"score": 0.5}


class TestRegisterCustom:
    def test_register_and_get_metric(self) -> None:
        register_metric("test_my_metric", _MyMetric)
        assert isinstance(get_metric("test_my_metric")(), _MyMetric)

    def test_register_and_get_batch_metric(self) -> None:
        register_batch_metric("test_my_batch", _MyBatch)
        instance = get_batch_metric("test_my_batch")()
        assert instance.aggregate() == {"score": 0.5}

    def test_overwrite_warns(self, caplog: pytest.LogCaptureFixture) -> None:
        register_metric("test_overwrite", _MyMetric)
        # Register a *different* factory under the same name → overwrite + warning.
        register_metric("test_overwrite", _OtherMetric)
        with caplog.at_level("WARNING"):
            register_metric("test_overwrite", _MyMetric)
        assert any("Overwriting" in rec.message for rec in caplog.records)
        assert isinstance(get_metric("test_overwrite")(), _MyMetric)

    def test_unknown_metric_raises(self) -> None:
        with pytest.raises(ValueError, match="Unknown metric"):
            get_metric("definitely_not_registered")

    def test_unknown_batch_metric_raises(self) -> None:
        with pytest.raises(ValueError, match="Unknown batch metric"):
            get_batch_metric("definitely_not_registered")
