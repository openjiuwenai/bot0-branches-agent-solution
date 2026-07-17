"""filters/base.py 与 filters/models.py 单元测试。"""

from __future__ import annotations

import re

import pytest

from evo_agent.evaluator.filters.base import build_patterns, bounded_evidence
from evo_agent.evaluator.filters.models import FilterMatch

_DEFAULTS = {
    "timeout": r"\btimeout\b",
    "error": r"\berror\b",
}


class TestBuildPatterns:
    """build_patterns — 默认 + 自定义 + replace_defaults 逻辑。"""

    def test_only_defaults(self) -> None:
        patterns = build_patterns(_DEFAULTS, None, replace_defaults=False)
        rule_ids = [rule_id for rule_id, _ in patterns]
        assert rule_ids == ["timeout", "error"]
        assert all(isinstance(p, re.Pattern) for _, p in patterns)

    def test_custom_appended_after_defaults(self) -> None:
        patterns = build_patterns(_DEFAULTS, [r"foo", r"bar"], replace_defaults=False)
        rule_ids = [rule_id for rule_id, _ in patterns]
        assert rule_ids == ["timeout", "error", "custom_1", "custom_2"]

    def test_replace_defaults_drops_defaults(self) -> None:
        patterns = build_patterns(_DEFAULTS, [r"foo"], replace_defaults=True)
        rule_ids = [rule_id for rule_id, _ in patterns]
        assert rule_ids == ["custom_1"]

    def test_replace_defaults_with_no_custom_returns_empty(self) -> None:
        patterns = build_patterns(_DEFAULTS, None, replace_defaults=True)
        assert patterns == []

    def test_patterns_compiled_case_insensitive(self) -> None:
        patterns = build_patterns({"err": r"error"}, None, replace_defaults=False)
        _, compiled = patterns[0]
        assert compiled.search("An ERROR occurred")  # 大写也匹配
        assert compiled.flags & re.IGNORECASE

    def test_custom_patterns_are_case_insensitive(self) -> None:
        patterns = build_patterns({}, ["FAILED"], replace_defaults=True)
        _, compiled = patterns[0]
        assert compiled.search("operation failed")

    def test_invalid_regex_raises(self) -> None:
        with pytest.raises(re.error):
            build_patterns(_DEFAULTS, ["[unclosed"], replace_defaults=False)


class TestBoundedEvidence:
    """bounded_evidence — 去空白 + 截断。"""

    def test_short_value_returned_stripped(self) -> None:
        assert bounded_evidence("  hello  ") == "hello"

    def test_exactly_at_limit_unchanged(self) -> None:
        value = "x" * 500
        assert bounded_evidence(value) == value

    def test_over_limit_truncated(self) -> None:
        value = "y" * 600
        result = bounded_evidence(value, limit=500)
        assert result.endswith("...")
        assert len(result) == 503  # 500 + "..."

    def test_whitespace_stripped_before_length_check(self) -> None:
        value = "  " + "z" * 600 + "  "
        result = bounded_evidence(value, limit=500)
        assert result.endswith("...")


class TestFilterMatchModel:
    """FilterMatch pydantic 模型。"""

    def test_minimal_valid(self) -> None:
        match = FilterMatch(
            filter_type="tool_failure",
            rule_id="error",
            message_index=1,
            evidence="boom",
        )
        assert match.pattern is None
        assert match.metadata == {}

    def test_extra_fields_ignored(self) -> None:
        """FilterMatch 默认忽略未知字段（不报错）。"""
        match = FilterMatch(
            filter_type="tool_failure",
            rule_id="error",
            message_index=0,
            evidence="x",
            unknown_field="bad",
        )
        assert match.rule_id == "error"
        assert not hasattr(match, "unknown_field")

    def test_metadata_defaults_to_empty_and_isolated(self) -> None:
        a = FilterMatch(filter_type="user_feedback", rule_id="r", message_index=0, evidence="x")
        b = FilterMatch(filter_type="user_feedback", rule_id="r", message_index=0, evidence="x")
        a.metadata["k"] = "v"
        assert b.metadata == {}  # default_factory 隔离

    def test_custom_metadata_preserved(self) -> None:
        match = FilterMatch(
            filter_type="tool_failure",
            rule_id="structured_failure",
            message_index=2,
            evidence="x",
            metadata={"code": 1},
        )
        assert match.metadata == {"code": 1}
