"""Per-case metric unit tests — Contains / KeywordRecall / Regex / NumericTolerance.

``compute(prediction, label, **kwargs)`` returns a float in [0,1]. The EvoAgent
MetricEvaluator override passes ``predict`` (dict) as prediction and the unwrapped
``expected_result`` as label; both sides are stringified consistently, so these
tests use a mix of string and dict inputs.
"""

from __future__ import annotations

from typing import Any

import pytest

from evo_agent.evaluator.metrics.per_case import (
    ContainsMetric,
    KeywordHitMetric,
    KeywordRecallMetric,
    LLMJudgeMetric,
    NumericToleranceMetric,
    RegexMatchMetric,
)


class TestContainsMetric:
    def test_name(self) -> None:
        assert ContainsMetric().name == "contains"

    def test_hit(self) -> None:
        assert ContainsMetric().compute({"answer": "the result is PASS"}, "PASS") == 1.0

    def test_miss(self) -> None:
        assert ContainsMetric().compute({"answer": "no signal"}, "PASS") == 0.0

    def test_case_insensitive(self) -> None:
        m = ContainsMetric(case_insensitive=True)
        assert m.compute({"answer": "Hello World"}, "hello world") == 1.0
        assert m.compute({"answer": "Hello World"}, "GOODBYE") == 0.0

    def test_empty_label_is_miss(self) -> None:
        # Empty expected substring would trivially "match" everywhere; treat as 0.0.
        assert ContainsMetric().compute({"answer": "anything"}, "") == 0.0


class TestKeywordRecallMetric:
    def test_name(self) -> None:
        assert KeywordRecallMetric().name == "keyword_recall"

    def test_partial_recall(self) -> None:
        m = KeywordRecallMetric()
        result = m.compute({"answer": "北京 上海 广州"}, ["北京", "广州", "深圳"])
        assert result == pytest.approx(2 / 3)

    def test_full_recall(self) -> None:
        m = KeywordRecallMetric()
        assert m.compute({"answer": "北京 上海"}, ["北京", "上海"]) == 1.0

    def test_no_hits(self) -> None:
        m = KeywordRecallMetric()
        assert m.compute({"answer": "成都"}, ["北京", "上海"]) == 0.0

    def test_empty_keywords_is_zero(self) -> None:
        assert KeywordRecallMetric().compute({"answer": "anything"}, []) == 0.0

    def test_case_insensitive(self) -> None:
        m = KeywordRecallMetric(case_insensitive=True)
        assert m.compute({"answer": "Hello World"}, ["hello", "world"]) == 1.0

    def test_scalar_label(self) -> None:
        # A scalar label is treated as a single-keyword list.
        assert KeywordRecallMetric().compute({"answer": "PASS here"}, "PASS") == 1.0


class TestKeywordHitMetric:
    """``keyword_hit`` — 1.0 if ANY expected keyword present (binary hit/miss)."""

    def test_name(self) -> None:
        assert KeywordHitMetric().name == "keyword_hit"

    def test_hit_any_keyword(self) -> None:
        m = KeywordHitMetric()
        assert m.compute({"answer": "该诉求属实"}, ["属实", "供电公司责任"]) == 1.0
        assert m.compute({"answer": "不属于供电公司责任"}, ["属实", "供电公司责任"]) == 1.0

    def test_miss(self) -> None:
        assert KeywordHitMetric().compute({"answer": "无相关词"}, ["属实", "供电公司责任"]) == 0.0

    def test_empty_keywords_is_zero(self) -> None:
        assert KeywordHitMetric().compute({"answer": "anything"}, []) == 0.0

    def test_case_insensitive(self) -> None:
        m = KeywordHitMetric(case_insensitive=True)
        assert m.compute({"answer": "Hello World"}, ["hello"]) == 1.0
        assert m.compute({"answer": "Hello World"}, ["goodbye"]) == 0.0

    def test_scalar_label(self) -> None:
        # A scalar label is treated as a single-keyword list.
        assert KeywordHitMetric().compute({"answer": "PASS here"}, "PASS") == 1.0


class TestRegexMatchMetric:
    def test_name(self) -> None:
        assert RegexMatchMetric().name == "regex"

    def test_search_hit(self) -> None:
        assert RegexMatchMetric().compute({"answer": "order #12345 done"}, r"#\d+") == 1.0

    def test_search_miss(self) -> None:
        assert RegexMatchMetric().compute({"answer": "no id here"}, r"#\d+") == 0.0

    def test_fullmatch(self) -> None:
        m = RegexMatchMetric(fullmatch=True)
        assert m.compute("12345", r"\d+") == 1.0
        assert m.compute("order 12345", r"\d+") == 0.0

    def test_explicit_pattern_overrides_label(self) -> None:
        m = RegexMatchMetric(pattern=r"\d+", fullmatch=True)
        # label is ignored when an explicit pattern is given
        assert m.compute("12345", "not-a-regex") == 1.0

    def test_empty_label_is_miss(self) -> None:
        assert RegexMatchMetric().compute({"answer": "abc"}, "") == 0.0

    def test_invalid_regex_raises(self) -> None:
        with pytest.raises(Exception):  # re.error
            RegexMatchMetric().compute({"answer": "abc"}, "(")


class TestNumericToleranceMetric:
    def test_name(self) -> None:
        assert NumericToleranceMetric().name == "numeric_tolerance"

    def test_within_abs_tol(self) -> None:
        m = NumericToleranceMetric(abs_tol=0.5)
        assert m.compute({"answer": "42.3"}, "42.0") == 1.0

    def test_outside_abs_tol_no_rel(self) -> None:
        m = NumericToleranceMetric(abs_tol=0.1)
        assert m.compute({"answer": "42.3"}, "42.0") == 0.0

    def test_rel_tol(self) -> None:
        m = NumericToleranceMetric(abs_tol=0.0, rel_tol=0.01)
        # diff 1.0, base ~100 → rel 0.01 → within
        assert m.compute({"answer": "101.0"}, "100.0") == 1.0
        # diff 5.0, base ~100 → rel 0.05 → outside
        assert m.compute({"answer": "105.0"}, "100.0") == 0.0

    def test_no_number_in_prediction(self) -> None:
        assert NumericToleranceMetric().compute({"answer": "no digits"}, "42.0") == 0.0

    def test_no_number_in_label(self) -> None:
        assert NumericToleranceMetric().compute({"answer": "42"}, "n/a") == 0.0


class TestLLMJudgeMetric:
    """LLMJudgeMetric 读 judged_label kwarg；=gold → 1.0，否则 0.0。"""

    def test_name(self) -> None:
        assert LLMJudgeMetric().name == "llm_judge"

    def test_reads_judged_label(self) -> None:
        m = LLMJudgeMetric()
        assert m.compute("pred", "否", judged_label="否") == 1.0
        assert m.compute("pred", "否", judged_label="是") == 0.0

    def test_missing_judged_label_defaults_zero(self) -> None:
        # 无 judged_label kwarg → 视为判错（0.0）
        assert LLMJudgeMetric().compute("pred", "否") == 0.0


class TestPerCaseViaEvaluator:
    """Integration: per-case metrics flow through MetricEvaluator.evaluate."""

    def _eval(self, metric: Any, expected_result: Any, predict: dict[str, Any]) -> float:
        from openjiuwen.agent_evolving.dataset import Case

        from evo_agent.evaluator.evaluators.metric import MetricEvaluator

        case = Case(inputs={"task_input": "q"}, label={"expected_result": expected_result})
        return MetricEvaluator(metrics=metric).evaluate(case, predict).score

    def test_contains_via_evaluator(self) -> None:
        assert self._eval(ContainsMetric(), "PASS", {"answer": "the result is PASS"}) == 1.0

    def test_keyword_recall_via_evaluator(self) -> None:
        score = self._eval(KeywordRecallMetric(), ["北京", "广州"], {"answer": "北京 南京"})
        assert score == 0.5
