"""span_matches_filter 前缀/精确/黑白名单组合测试。"""

from __future__ import annotations

from agent_adapter.trace_profile.filters import span_matches_filter
from agent_adapter.trace_profile.models import SpanFilter


def _span(name: str) -> dict:
    return {"name": name}


class TestIncludePrefixes:
    def test_match(self):
        f = SpanFilter(include_prefixes=["ai.streamText"])
        assert span_matches_filter(_span("ai.streamText"), f)
        assert span_matches_filter(_span("ai.streamText.doStream"), f)

    def test_no_match(self):
        f = SpanFilter(include_prefixes=["ai.streamText"])
        assert not span_matches_filter(_span("ai.toolCall"), f)
        assert not span_matches_filter(_span("sql.execute"), f)


class TestExcludePrefixes:
    def test_blacklist_blocks(self):
        f = SpanFilter(exclude_prefixes=["sql."])
        assert not span_matches_filter(_span("sql.execute"), f)
        assert not span_matches_filter(_span("sql.query"), f)

    def test_blacklist_priority_over_whitelist(self):
        f = SpanFilter(include_prefixes=["ai."], exclude_prefixes=["ai.toolCall"])
        assert span_matches_filter(_span("ai.streamText"), f)
        assert not span_matches_filter(_span("ai.toolCall"), f)


class TestExactNames:
    def test_include_exact(self):
        f = SpanFilter(include_names=["Tool.execute"])
        assert span_matches_filter(_span("Tool.execute"), f)
        assert not span_matches_filter(_span("Tool.execute.sub"), f)

    def test_exclude_exact(self):
        f = SpanFilter(exclude_names=["http.server"])
        assert not span_matches_filter(_span("http.server"), f)


class TestDefaultAllow:
    def test_empty_filter_passes_all(self):
        f = SpanFilter()
        assert span_matches_filter(_span("anything"), f)
        assert span_matches_filter(_span("sql.execute"), f)

    def test_whitelist_blocks_unknown(self):
        f = SpanFilter(include_prefixes=["ai."])
        assert not span_matches_filter(_span("sql.execute"), f)
        assert not span_matches_filter(_span("http.server"), f)


class TestCombinations:
    def test_include_and_exclude_prefixes(self):
        f = SpanFilter(
            include_prefixes=["ai.", "tool."],
            exclude_prefixes=["tool.internal"],
        )
        assert span_matches_filter(_span("ai.streamText"), f)
        assert span_matches_filter(_span("tool.execute"), f)
        assert not span_matches_filter(_span("tool.internal.cleanup"), f)
        assert not span_matches_filter(_span("sql.query"), f)

    def test_empty_name(self):
        f = SpanFilter(include_prefixes=["ai."])
        assert not span_matches_filter(_span(""), f)