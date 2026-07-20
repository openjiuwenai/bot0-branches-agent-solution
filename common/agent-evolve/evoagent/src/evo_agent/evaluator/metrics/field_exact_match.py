"""Exact-match metric that first extracts a configured field from the answer."""

from __future__ import annotations

from typing import Any

from openjiuwen.agent_evolving.evaluator.metrics.exact_match import ExactMatchMetric

from evo_agent.evaluator.metrics.extract import (
    AnswerFieldExtractConfig,
    extract_prediction_field,
)


class FieldExtractExactMatchMetric:
    """Extract a JSON field from ``<answer>`` then exact-match against the label.

    Without extract config this class is not used; plain ``ExactMatchMetric``
    remains the default for whole-payload comparison.
    """

    def __init__(
        self,
        extract: AnswerFieldExtractConfig,
        *,
        normalize: bool = False,
    ) -> None:
        self._metric = ExactMatchMetric(normalize=normalize)
        self._extract = extract
        self._normalize = normalize

    @property
    def name(self) -> str:
        return "exact_match" if not self._normalize else "normalized_exact_match"

    def compute(self, prediction: Any, label: Any, **kwargs: Any) -> float:
        extracted = extract_prediction_field(prediction, self._extract)
        return float(self._metric.compute(extracted, label, **kwargs))
