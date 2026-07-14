"""FilteringEvaluator 单元测试 — 短路 / 委托 / batch / 错误包裹。"""

from __future__ import annotations

import json
from typing import Any
from unittest.mock import MagicMock

import pytest
from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

from evo_agent.evaluator.domain.models import StandardTrajectory
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.evaluators.filtering import FilteringEvaluator
from evo_agent.evaluator.filters.models import FilterMatch


class _FakeFilter:
    """可控的过滤器桩件。"""

    def __init__(self, name: str, matches: list[FilterMatch] | None = None) -> None:
        self.name = name
        self._matches = matches if matches is not None else []

    def inspect(self, trajectory: StandardTrajectory) -> list[FilterMatch]:
        return list(self._matches)


def _case(trajectory: dict[str, Any] | None = None) -> Case:
    # Case.inputs 要求非空 dict；用占位 key 兜底，trajectory 存在时覆盖
    inputs: dict[str, Any] = {"__placeholder__": True}
    if trajectory is not None:
        inputs = {"trajectory": trajectory}
    return Case(inputs=inputs, label={"expected_result": None})


_TRAJECTORY: dict[str, Any] = {"messages": [{"role": "user", "content": "hi"}]}


def _match(rule_id: str = "error", index: int = 0) -> FilterMatch:
    return FilterMatch(
        filter_type="tool_failure",
        rule_id=rule_id,
        message_index=index,
        evidence="boom",
    )


class TestInitValidation:
    def test_empty_filters_raises(self) -> None:
        with pytest.raises(ValueError, match="at least one filter"):
            FilteringEvaluator(delegate=MagicMock(), filters=[])


class TestShortCircuit:
    """过滤器命中 → 短路返回零分结果，delegate 不被调用。"""

    def test_match_returns_zero_score_filtered_result(self) -> None:
        delegate = MagicMock()
        evaluator = FilteringEvaluator(delegate=delegate, filters=[_FakeFilter("f", [_match()])])
        result = evaluator.evaluate(_case(_TRAJECTORY), {"answer": "x"})

        assert isinstance(result, EvaluatedCase)
        assert result.score == 0.0
        assert result.per_metric == {"filter_failure": 0.0}
        delegate.evaluate.assert_not_called()

        reason_data = json.loads(result.reason)
        assert reason_data["status"] == "filtered"
        assert reason_data["is_pass"] is False
        assert reason_data["attributed_skill"] == ""
        assert len(reason_data["filter_matches"]) == 1

    def test_match_includes_all_filter_matches(self) -> None:
        evaluator = FilteringEvaluator(
            delegate=MagicMock(),
            filters=[
                _FakeFilter("tool", [_match("error", 0)]),
                _FakeFilter("feedback", [_match("explicit_rejection", 3)]),
            ],
        )
        result = evaluator.evaluate(_case(_TRAJECTORY), {"answer": "x"})
        reason_data = json.loads(result.reason)
        assert len(reason_data["filter_matches"]) == 2

    def test_answer_is_conversation_prediction(self) -> None:
        evaluator = FilteringEvaluator(delegate=MagicMock(), filters=[_FakeFilter("f", [_match()])])
        result = evaluator.evaluate(_case(_TRAJECTORY), {"answer": "x"})
        assert result.answer == {"evaluation_source": "conversation_trajectory"}


class TestDelegatePassThrough:
    """过滤器未命中 → 委托给 delegate，原样透传。"""

    def test_no_match_delegates(self) -> None:
        expected = EvaluatedCase(case=_case(), answer={"answer": "ok"}, score=0.9)
        delegate = MagicMock()
        delegate.evaluate.return_value = expected
        evaluator = FilteringEvaluator(delegate=delegate, filters=[_FakeFilter("f", [])])

        case = _case(_TRAJECTORY)
        predict = {"answer": "x"}
        result = evaluator.evaluate(case, predict)

        assert result is expected
        delegate.evaluate.assert_called_once_with(case, predict)

    def test_filters_run_in_order(self) -> None:
        calls: list[str] = []
        f1 = _FakeFilter("f1")
        f1.inspect = lambda trajectory: calls.append("f1") or []  # type: ignore[method-assign]
        f2 = _FakeFilter("f2")
        f2.inspect = lambda trajectory: calls.append("f2") or []  # type: ignore[method-assign]
        evaluator = FilteringEvaluator(delegate=MagicMock(), filters=[f1, f2])
        evaluator.evaluate(_case(_TRAJECTORY), {"answer": "x"})
        assert calls == ["f1", "f2"]


class TestTrajectoryFromCase:
    """case.inputs 中缺少 trajectory key → ValueError（inputs 必须是 dict 但无 trajectory）。"""

    def test_missing_trajectory_raises(self) -> None:
        evaluator = FilteringEvaluator(delegate=MagicMock(), filters=[_FakeFilter("f", [])])
        case = Case(inputs={"other": 1}, label={"expected_result": None})
        with pytest.raises(ValueError, match="trajectory"):
            evaluator.evaluate(case, {"answer": "x"})


class TestFilterErrorWrapping:
    """过滤器抛出异常 → 被包裹为 EvaluationError。"""

    def test_filter_exception_wrapped(self) -> None:
        class BoomFilter:
            name = "boom"

            def inspect(self, trajectory: StandardTrajectory) -> list[FilterMatch]:
                raise RuntimeError("filter crashed")

        evaluator = FilteringEvaluator(delegate=MagicMock(), filters=[BoomFilter()])
        with pytest.raises(EvaluationError, match="Filter 'boom' failed"):
            evaluator.evaluate(_case(_TRAJECTORY), {"answer": "x"})


class TestBatchEvaluate:
    """batch_evaluate — 长度校验、空、过滤结果保留、delegate 错误丢弃。"""

    def test_length_mismatch_raises(self) -> None:
        evaluator = FilteringEvaluator(delegate=MagicMock(), filters=[_FakeFilter("f", [])])
        with pytest.raises(ValueError, match="length"):
            evaluator.batch_evaluate([_case(_TRAJECTORY)], [])

    def test_empty_cases(self) -> None:
        evaluator = FilteringEvaluator(delegate=MagicMock(), filters=[_FakeFilter("f", [])])
        assert evaluator.batch_evaluate([], []) == []

    def test_filtered_results_always_retained(self) -> None:
        evaluator = FilteringEvaluator(delegate=MagicMock(), filters=[_FakeFilter("f", [_match()])])
        case = _case(_TRAJECTORY)
        results = evaluator.batch_evaluate([case], [{"answer": "x"}])
        assert len(results) == 1
        assert results[0].score == 0.0

    def test_delegate_errors_dropped(self) -> None:
        delegate = MagicMock()
        delegate.evaluate.side_effect = EvaluationError("boom")
        evaluator = FilteringEvaluator(delegate=delegate, filters=[_FakeFilter("f", [])])
        case = _case(_TRAJECTORY)
        results = evaluator.batch_evaluate([case, case], [{"a": 1}, {"a": 2}])
        assert results == []

    def test_order_retained_with_mixed(self) -> None:
        """filtered 保留 + delegate 失败丢弃，顺序正确。"""
        delegate = MagicMock()

        def delegate_evaluate(case: Case, predict: dict[str, Any]) -> EvaluatedCase:
            if predict.get("fail"):
                raise EvaluationError("boom")
            return EvaluatedCase(case=case, answer=predict, score=0.7)

        delegate.evaluate.side_effect = delegate_evaluate

        match_filter = _FakeFilter("f", [_match()])
        no_match_filter = _FakeFilter("f")
        evaluator = FilteringEvaluator(delegate=delegate, filters=[no_match_filter])
        case_ok = _case(_TRAJECTORY)
        case_fail = _case(_TRAJECTORY)
        case_filtered = _case(_TRAJECTORY)

        evaluator_filtered = FilteringEvaluator(delegate=delegate, filters=[match_filter])
        # 第 1 个走 delegate（ok），第 2 个走 delegate（fail），第 3 个走 filtered
        results = evaluator.batch_evaluate([case_ok, case_fail], [{"a": 1}, {"fail": True}])
        assert len(results) == 1
        assert results[0].score == 0.7

        results2 = evaluator_filtered.batch_evaluate([case_filtered], [{"a": 1}])
        assert len(results2) == 1
        assert results2[0].score == 0.0
