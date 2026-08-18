"""filter_spans_by_service 多 Agent 自动路由测试。"""

from __future__ import annotations

from agent_adapter.trace_profile.filters import filter_spans_by_service
from agent_adapter.trace_profile.loader import ProfileRegistry
from agent_adapter.trace_profile.models import SpanFilter, TraceProfile


def _make_registry() -> ProfileRegistry:
    edp = TraceProfile(
        name="edp_agent",
        service_name="EDPAgent",
        ingest_filter=SpanFilter(include_prefixes=["llm.", "tool.", "http.request"]),
        query_filter=SpanFilter(include_prefixes=["llm.", "tool."]),
    )
    oc = TraceProfile(
        name="opencode",
        service_name="opencode",
        ingest_filter=SpanFilter(
            include_prefixes=["ai.streamText", "ai.toolCall"],
            exclude_prefixes=["sql.", "http.server"],
        ),
        query_filter=SpanFilter(include_prefixes=["ai.streamText", "ai.toolCall"]),
    )
    return ProfileRegistry({"edp_agent": edp, "opencode": oc})


class TestIngestLayer:
    def test_edp_agent_spans_passed(self):
        reg = _make_registry()
        spans = [
            {"name": "llm.gpt-4", "service_name": "EDPAgent"},
            {"name": "tool.search", "service_name": "EDPAgent"},
            {"name": "http.request", "service_name": "EDPAgent"},
            {"name": "chain.EDPAgent", "service_name": "EDPAgent"},
        ]
        result = filter_spans_by_service(spans, reg, "ingest")
        assert len(result) == 3
        names = {s["name"] for s in result}
        assert names == {"llm.gpt-4", "tool.search", "http.request"}

    def test_opencode_noise_filtered(self):
        reg = _make_registry()
        spans = [
            {"name": "ai.streamText", "service_name": "opencode"},
            {"name": "ai.toolCall", "service_name": "opencode"},
            {"name": "sql.execute", "service_name": "opencode"},
            {"name": "http.server", "service_name": "opencode"},
            {"name": "Session.process", "service_name": "opencode"},
        ]
        result = filter_spans_by_service(spans, reg, "ingest")
        assert len(result) == 2
        names = {s["name"] for s in result}
        assert names == {"ai.streamText", "ai.toolCall"}

    def test_unknown_service_skipped(self):
        reg = _make_registry()
        spans = [
            {"name": "some.span", "service_name": "unknown"},
            {"name": "llm.gpt-4", "service_name": "EDPAgent"},
        ]
        result = filter_spans_by_service(spans, reg, "ingest")
        assert len(result) == 1
        assert result[0]["name"] == "llm.gpt-4"

    def test_missing_service_name_skipped(self):
        reg = _make_registry()
        spans = [
            {"name": "orphan.span", "service_name": ""},
            {"name": "llm.gpt-4", "service_name": "EDPAgent"},
        ]
        result = filter_spans_by_service(spans, reg, "ingest")
        assert len(result) == 1
        assert result[0]["name"] == "llm.gpt-4"


class TestQueryLayer:
    def test_unknown_service_kept(self):
        reg = _make_registry()
        spans = [
            {"name": "orphan.span", "service_name": "unknown"},
            {"name": "llm.gpt-4", "service_name": "EDPAgent"},
        ]
        result = filter_spans_by_service(spans, reg, "query")
        assert len(result) == 2

    def test_missing_service_name_kept(self):
        reg = _make_registry()
        spans = [
            {"name": "orphan.span", "service_name": ""},
            {"name": "llm.gpt-4", "service_name": "EDPAgent"},
        ]
        result = filter_spans_by_service(spans, reg, "query")
        assert len(result) == 2

    def test_edp_query_filter(self):
        reg = _make_registry()
        spans = [
            {"name": "llm.gpt-4", "service_name": "EDPAgent"},
            {"name": "tool.search", "service_name": "EDPAgent"},
            {"name": "http.request", "service_name": "EDPAgent"},
        ]
        result = filter_spans_by_service(spans, reg, "query")
        assert len(result) == 2
        names = {s["name"] for s in result}
        assert names == {"llm.gpt-4", "tool.search"}


class TestMixedAgents:
    def test_mixed_message_split(self):
        reg = _make_registry()
        spans = [
            {"name": "llm.gpt-4", "service_name": "EDPAgent"},
            {"name": "ai.streamText", "service_name": "opencode"},
            {"name": "sql.execute", "service_name": "opencode"},
            {"name": "tool.search", "service_name": "EDPAgent"},
        ]
        result = filter_spans_by_service(spans, reg, "ingest")
        assert len(result) == 3
        names = {s["name"] for s in result}
        assert names == {"llm.gpt-4", "ai.streamText", "tool.search"}