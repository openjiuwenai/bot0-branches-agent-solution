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

    def test_unknown_service_kept_failopen(self):
        # 未知 service 无匹配 profile → fail-open 保留 (升级后不静默丢未知 agent)
        reg = _make_registry()
        spans = [
            {"name": "some.span", "service_name": "unknown"},
            {"name": "llm.gpt-4", "service_name": "EDPAgent"},
        ]
        result = filter_spans_by_service(spans, reg, "ingest")
        assert len(result) == 2
        assert {s["name"] for s in result} == {"some.span", "llm.gpt-4"}

    def test_missing_service_name_kept_failopen(self):
        # 空 service_name 无 profile → fail-open 保留 (孤儿 span 不丢)
        reg = _make_registry()
        spans = [
            {"name": "orphan.span", "service_name": ""},
            {"name": "llm.gpt-4", "service_name": "EDPAgent"},
        ]
        result = filter_spans_by_service(spans, reg, "ingest")
        assert len(result) == 2
        assert {s["name"] for s in result} == {"orphan.span", "llm.gpt-4"}


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


class TestLanguageRouting:
    """Python/Java EDPAgent 撞 service.name 时，靠 telemetry.sdk.language 自动分流。"""

    @staticmethod
    def _reg() -> ProfileRegistry:
        py = TraceProfile(
            name="edp_agent",
            service_name="edp-agent",
            service_language="python",
            ingest_filter=SpanFilter(include_prefixes=["llm.", "tool."]),
            query_filter=SpanFilter(include_prefixes=["llm.", "tool."]),
        )
        ja = TraceProfile(
            name="edp_agent_java",
            service_name="edp-agent",
            service_language="java",
            ingest_filter=SpanFilter(include_prefixes=["llm.", "tool."]),
            query_filter=SpanFilter(include_prefixes=["llm.", "tool."]),
        )
        return ProfileRegistry({"edp_agent": py, "edp_agent_java": ja})

    def test_ingest_splits_by_language(self):
        reg = self._reg()
        spans = [
            {"name": "llm.py-span", "service_name": "edp-agent",
             "resource_attributes": {"telemetry.sdk.language": "python"}},
            {"name": "llm.java-span", "service_name": "edp-agent",
             "resource_attributes": {"telemetry.sdk.language": "java"}},
            {"name": "llm.rust-span", "service_name": "edp-agent",
             "resource_attributes": {"telemetry.sdk.language": "rust"}},
        ]
        result = filter_spans_by_service(spans, reg, "ingest")
        # python/java 各自命中 profile；rust 无匹配语言 → fail-open 保留 (不丢)
        assert {s["name"] for s in result} == {"llm.py-span", "llm.java-span", "llm.rust-span"}

    def test_query_keeps_unmatched_language(self):
        reg = self._reg()
        spans = [
            {"name": "llm.rust-span", "service_name": "edp-agent",
             "resource_attributes": {"telemetry.sdk.language": "rust"}},
        ]
        result = filter_spans_by_service(spans, reg, "query")
        # rust 无匹配 profile → query 层兜底保留
        assert len(result) == 1

    def test_no_resource_attrs_falls_back_single_candidate(self):
        # service.name 不撞时，无 resource_attributes 也能路由（向后兼容）
        reg = _make_registry()  # edp(EDPAgent) / opencode — 不撞名
        spans = [{"name": "llm.gpt-4", "service_name": "EDPAgent"}]
        result = filter_spans_by_service(spans, reg, "ingest")
        assert len(result) == 1