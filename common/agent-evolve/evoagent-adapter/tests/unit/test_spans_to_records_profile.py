"""spans_to_records 有/无 profile 两种路径测试。"""

from __future__ import annotations

import json

from agent_adapter.trace_profile.models import (
    FieldExtraction,
    MultiFieldExtraction,
    SpanFilter,
    TraceProfile,
)
from agent_adapter.trace_source.spans_to_records import spans_to_records


def _make_edp_profile() -> TraceProfile:
    return TraceProfile(
        name="edp_agent",
        service_name="EDPAgent",
        llm_span_prefix="llm.",
        tool_span_prefix="tool.",
        root_span_name="http.request",
        prompt_extraction=FieldExtraction(attr="gen_ai.prompt", sub_key="messages", format="python_repr"),
        response_extraction=MultiFieldExtraction(
            fields=[FieldExtraction(attr="gen_ai.completion", sub_key="outputs", format="python_repr")],
        ),
        query_filter=SpanFilter(),
    )


def _make_opencode_profile() -> TraceProfile:
    return TraceProfile(
        name="opencode",
        service_name="opencode",
        llm_span_prefix="ai.streamText",
        tool_span_prefix="ai.toolCall",
        query_filter=SpanFilter(include_prefixes=["ai.streamText", "ai.toolCall"]),
        prompt_extraction=FieldExtraction(attr="ai.prompt", sub_key="messages", format="json"),
        response_extraction=MultiFieldExtraction(
            fields=[FieldExtraction(attr="ai.response", sub_key="text")],
        ),
        tool_args_extraction=FieldExtraction(attr="ai.toolCall", sub_key="args", format="json"),
        tool_result_extraction=FieldExtraction(attr="ai.toolCall", sub_key="result", format="json"),
    )


class TestLegacyNoProfile:
    def test_no_profile_uses_legacy(self):
        spans = [
            {
                "name": "llm.gpt-4",
                "span_id": "s1",
                "trace_id": "t1",
                "start_time": "2024-01-01T00:00:00Z",
                "attributes": {
                    "gen_ai.prompt": '{"messages": [{"role": "user", "content": "hi"}]}',
                    "gen_ai.completion": '{"outputs": {"role": "assistant", "content": "hello"}}',
                },
            },
        ]
        records = spans_to_records(spans)
        assert len(records) == 1
        assert records[0]["type"] == "GENERATION"
        assert records[0]["input"]["messages"] == [{"role": "user", "content": "hi"}]


class TestProfileMode:
    def test_llm_span_to_generation_with_profile(self):
        profile = _make_opencode_profile()
        spans = [
            {
                "name": "ai.streamText",
                "span_id": "s1",
                "trace_id": "t1",
                "start_time": "2024-01-01T00:00:00Z",
                "attributes": {
                    "ai.prompt": json.dumps({"messages": [{"role": "user", "content": "hello"}]}),
                    "ai.response": json.dumps({"text": "Hi there!"}),
                },
            },
        ]
        records = spans_to_records(spans, profile)
        assert len(records) == 1
        assert records[0]["type"] == "GENERATION"
        assert records[0]["input"]["messages"] == [{"role": "user", "content": "hello"}]
        assert records[0]["output"] == {"role": "assistant", "content": "Hi there!"}

    def test_tool_span_to_tool_record_with_profile(self):
        profile = _make_opencode_profile()
        spans = [
            {
                "name": "ai.toolCall",
                "span_id": "s1",
                "trace_id": "t1",
                "start_time": "2024-01-01T00:00:00Z",
                "attributes": {
                    "ai.toolCall": json.dumps({
                        "args": {"query": "test"},
                        "result": "found",
                    }),
                },
            },
        ]
        records = spans_to_records(spans, profile)
        assert len(records) == 1
        assert records[0]["type"] == "TOOL"
        assert records[0]["args"] == {"query": "test"}
        assert records[0]["result"] == "found"

    def test_root_span_with_profile(self):
        profile = _make_edp_profile()
        spans = [
            {
                "name": "http.request",
                "kind": "SERVER",
                "span_id": "s1",
                "trace_id": "t1",
                "start_time": "2024-01-01T00:00:00Z",
                "attributes": {},
            },
        ]
        records = spans_to_records(spans, profile)
        assert len(records) == 1
        assert records[0]["id"] == "t1"

    def test_no_root_span_when_empty(self):
        # 隔离 root_span_name="" 的行为: 用 allow-all query_filter (不混淆 query 过滤),
        # http.request SERVER span 因 root_span_name 空 → 不成 TRACE record
        profile = TraceProfile(
            name="nocfg",
            service_name="svc",
            query_filter=SpanFilter(),  # allow-all, 排除 query_filter 干扰
            root_span_name="",         # 空 → 不提取 TRACE record
        )
        spans = [
            {
                "name": "http.request",
                "kind": "SERVER",
                "span_id": "s1",
                "trace_id": "t1",
                "start_time": "2024-01-01T00:00:00Z",
                "attributes": {},
            },
        ]
        records = spans_to_records(spans, profile)
        assert len(records) == 0

    def test_query_filter_excludes(self):
        profile = _make_opencode_profile()
        spans = [
            {
                "name": "sql.execute",
                "span_id": "s1",
                "trace_id": "t1",
                "start_time": "2024-01-01T00:00:00Z",
                "attributes": {},
            },
            {
                "name": "ai.streamText",
                "span_id": "s2",
                "trace_id": "t1",
                "start_time": "2024-01-01T00:00:01Z",
                "attributes": {
                    "ai.prompt": json.dumps({"messages": [{"role": "user", "content": "hi"}]}),
                    "ai.response": json.dumps({"text": "ok"}),
                },
            },
        ]
        records = spans_to_records(spans, profile)
        assert len(records) == 1
        assert records[0]["type"] == "GENERATION"

    def test_multiple_llm_spans_each_recorded_with_context(self):
        # 两个 llm span 各成 GENERATION record (无 last-wins 覆盖); 第 2 个 prompt 携带第 1 轮上文
        profile = _make_opencode_profile()
        spans = [
            {
                "name": "ai.streamText",
                "span_id": "s1",
                "trace_id": "t1",
                "start_time": "2024-01-01T00:00:00Z",
                "attributes": {
                    "ai.prompt": json.dumps({"messages": [{"role": "user", "content": "first"}]}),
                    "ai.response": json.dumps({"text": "reply1"}),
                },
            },
            {
                "name": "ai.streamText",
                "span_id": "s2",
                "trace_id": "t1",
                "start_time": "2024-01-01T00:00:01Z",
                "attributes": {
                    "ai.prompt": json.dumps({"messages": [
                        {"role": "user", "content": "first"},
                        {"role": "assistant", "content": "reply1"},
                        {"role": "user", "content": "second"},
                    ]}),
                    "ai.response": json.dumps({"text": "reply2"}),
                },
            },
        ]
        records = spans_to_records(spans, profile)
        assert len(records) == 2
        assert records[1]["input"]["messages"][0]["content"] == "first"