"""EvaluationResult.from_evaluated_case 单元测试。"""

from __future__ import annotations

import json
from typing import Any
from unittest.mock import MagicMock

import pytest
from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

from evo_agent.evaluator.domain.result import EvaluationResult
from evo_agent.evaluator.filters.models import FilterMatch


def _evaluated(
    *,
    score: float = 0.8,
    per_metric: dict[str, float] | None = None,
    reason: str = "",
) -> EvaluatedCase:
    """构造一个可控的 EvaluatedCase。"""
    case = Case(inputs={"trajectory": {"messages": []}}, label={"expected_result": None})
    evaluated = EvaluatedCase(case=case, answer={"evaluation_source": "conversation_trajectory"})
    evaluated.score = score
    evaluated.per_metric = per_metric
    evaluated.reason = reason
    return evaluated


class TestEvaluatedStatus:
    """正常评估结果 → status="evaluated"。"""

    def test_basic_fields_mapped(self) -> None:
        evaluated = _evaluated(score=0.9, per_metric={"safety": 1.0})
        result = EvaluationResult.from_evaluated_case(evaluated)
        assert result.status == "evaluated"
        assert result.score == 0.9
        assert result.is_pass is True
        assert result.per_metric == {"safety": 1.0}
        assert result.filter_matches == []

    def test_evaluated_reason_with_pass_and_attribution(self) -> None:
        """非 filtered 的 reason 仍可提取 is_pass / attributed_skill。"""
        reason = json.dumps(
            {"reason": "ok", "is_pass": False, "attributed_skill": "my_skill"}
        )
        evaluated = _evaluated(reason=reason)
        result = EvaluationResult.from_evaluated_case(evaluated)
        assert result.status == "evaluated"
        assert result.is_pass is False
        assert result.attributed_skill == "my_skill"
        assert result.filter_matches == []

    def test_none_per_metric_preserved(self) -> None:
        evaluated = _evaluated(per_metric=None)
        result = EvaluationResult.from_evaluated_case(evaluated)
        assert result.per_metric is None

    def test_empty_per_metric_becomes_none(self) -> None:
        """空 dict per_metric 被当作 None。"""
        evaluated = _evaluated(per_metric={})
        result = EvaluationResult.from_evaluated_case(evaluated)
        assert result.per_metric is None

    def test_non_str_attributed_skill_ignored(self) -> None:
        """非字符串 attributed_skill 被忽略，保持默认空串。"""
        reason = json.dumps({"attributed_skill": 123, "is_pass": True})
        evaluated = _evaluated(reason=reason)
        result = EvaluationResult.from_evaluated_case(evaluated)
        assert result.attributed_skill == ""

    def test_non_bool_is_pass_ignored(self) -> None:
        """非布尔 is_pass 被忽略，保持默认 True。"""
        reason = json.dumps({"is_pass": "yes"})
        evaluated = _evaluated(reason=reason)
        result = EvaluationResult.from_evaluated_case(evaluated)
        assert result.is_pass is True


class TestFilteredStatus:
    """filtered 状态解析 — reason JSON 中 status=="filtered"。"""

    def test_filtered_status_detected_with_matches(self) -> None:
        matches = [
            {
                "filter_type": "tool_failure",
                "rule_id": "structured_failure",
                "message_index": 2,
                "evidence": "timeout",
                "pattern": r"\btimeout\b",
            }
        ]
        reason = json.dumps(
            {
                "reason": "matched",
                "status": "filtered",
                "is_pass": False,
                "attributed_skill": "",
                "filter_matches": matches,
            }
        )
        evaluated = _evaluated(score=0.0, reason=reason)
        result = EvaluationResult.from_evaluated_case(evaluated)

        assert result.status == "filtered"
        assert result.is_pass is False
        assert result.score == 0.0
        assert len(result.filter_matches) == 1
        assert isinstance(result.filter_matches[0], FilterMatch)
        assert result.filter_matches[0].rule_id == "structured_failure"
        assert result.filter_matches[0].message_index == 2

    def test_filtered_status_without_is_pass_keeps_default(self) -> None:
        reason = json.dumps({"status": "filtered", "filter_matches": []})
        evaluated = _evaluated(reason=reason)
        result = EvaluationResult.from_evaluated_case(evaluated)
        assert result.status == "filtered"
        assert result.is_pass is True  # default

    def test_filtered_with_multiple_matches(self) -> None:
        matches = [
            {"filter_type": "tool_failure", "rule_id": "error", "message_index": 1, "evidence": "err"},
            {"filter_type": "user_feedback", "rule_id": "explicit_rejection", "message_index": 4, "evidence": "不对"},
        ]
        reason = json.dumps(
            {"status": "filtered", "is_pass": False, "filter_matches": matches}
        )
        evaluated = _evaluated(reason=reason)
        result = EvaluationResult.from_evaluated_case(evaluated)
        assert result.status == "filtered"
        assert len(result.filter_matches) == 2
        assert result.filter_matches[1].filter_type == "user_feedback"


class TestReasonFallback:
    """reason 非 JSON / 非 dict 的兜底逻辑。"""

    def test_non_json_reason_falls_back_to_evaluated(self) -> None:
        evaluated = _evaluated(reason="this is plain text, not json")
        result = EvaluationResult.from_evaluated_case(evaluated)
        assert result.status == "evaluated"
        assert result.filter_matches == []
        assert result.is_pass is True

    def test_empty_reason_skips_parsing(self) -> None:
        evaluated = _evaluated(reason="")
        result = EvaluationResult.from_evaluated_case(evaluated)
        assert result.status == "evaluated"
        assert result.attributed_skill == ""

    def test_json_array_reason_is_safe(self) -> None:
        """reason 解析为数组（非 dict）时不报错、不提取。"""
        evaluated = _evaluated(reason=json.dumps([1, 2, 3]))
        result = EvaluationResult.from_evaluated_case(evaluated)
        assert result.status == "evaluated"
        assert result.filter_matches == []

    def test_invalid_filter_match_item_falls_back(self) -> None:
        """filter_matches 中存在非法项（触发 ValidationError）→ 兜底为 evaluated。"""
        reason = json.dumps(
            {
                "status": "filtered",
                "filter_matches": [{"filter_type": "tool_failure"}],  # 缺 rule_id/message_index
            }
        )
        evaluated = _evaluated(reason=reason)
        result = EvaluationResult.from_evaluated_case(evaluated)
        assert result.status == "evaluated"
        assert result.filter_matches == []


class TestDuckTypedEvaluated:
    """from_evaluated_case 仅依赖 .score / .per_metric / .reason 属性。"""

    def test_accepts_duck_typed_object(self) -> None:
        fake = MagicMock()
        fake.score = 0.5
        fake.per_metric = {"m": 0.5}
        fake.reason = ""
        result = EvaluationResult.from_evaluated_case(fake)
        assert result.score == 0.5
        assert result.per_metric == {"m": 0.5}
