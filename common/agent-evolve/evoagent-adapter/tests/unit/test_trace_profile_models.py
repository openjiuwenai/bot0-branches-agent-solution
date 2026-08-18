"""TraceProfile / SpanFilter / FieldExtraction 模型验证。"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from agent_adapter.trace_profile.models import (
    FieldExtraction,
    MultiFieldExtraction,
    SpanFilter,
    TraceProfile,
)


class TestSpanFilter:
    def test_defaults_empty(self):
        f = SpanFilter()
        assert f.include_prefixes == []
        assert f.exclude_prefixes == []
        assert f.include_names == []
        assert f.exclude_names == []

    def test_full_config(self):
        f = SpanFilter(
            include_prefixes=["ai.streamText"],
            exclude_prefixes=["sql."],
            include_names=["Tool.execute"],
            exclude_names=["http.server"],
        )
        assert f.include_prefixes == ["ai.streamText"]
        assert f.exclude_prefixes == ["sql."]
        assert f.include_names == ["Tool.execute"]
        assert f.exclude_names == ["http.server"]


class TestFieldExtraction:
    def test_minimal(self):
        fe = FieldExtraction(attr="ai.prompt")
        assert fe.attr == "ai.prompt"
        assert fe.sub_key == ""
        assert fe.format == "json"

    def test_with_sub_key(self):
        fe = FieldExtraction(attr="gen_ai.prompt", sub_key="messages", format="python_repr")
        assert fe.attr == "gen_ai.prompt"
        assert fe.sub_key == "messages"
        assert fe.format == "python_repr"

    def test_invalid_format(self):
        with pytest.raises(ValidationError):
            FieldExtraction(attr="x", format="invalid")


class TestMultiFieldExtraction:
    def test_single_field(self):
        mfe = MultiFieldExtraction(
            fields=[FieldExtraction(attr="gen_ai.completion", sub_key="outputs", format="python_repr")],
            combine_as="assistant_message",
        )
        assert len(mfe.fields) == 1
        assert mfe.combine_as == "assistant_message"

    def test_multi_field(self):
        mfe = MultiFieldExtraction(
            fields=[
                FieldExtraction(attr="ai.response", sub_key="text"),
                FieldExtraction(attr="ai.response", sub_key="toolCalls"),
            ],
        )
        assert len(mfe.fields) == 2


class TestTraceProfile:
    def test_minimal(self):
        tp = TraceProfile(name="test", service_name="test-svc")
        assert tp.name == "test"
        assert tp.service_name == "test-svc"
        assert tp.llm_span_prefix == ""
        assert tp.tool_span_prefix == ""
        assert tp.root_span_name == ""
        assert isinstance(tp.ingest_filter, SpanFilter)
        assert isinstance(tp.query_filter, SpanFilter)

    def test_edp_agent_profile(self):
        tp = TraceProfile(
            name="edp_agent",
            service_name="EDPAgent",
            llm_span_prefix="llm.",
            tool_span_prefix="tool.",
            root_span_name="http.request",
            prompt_extraction=FieldExtraction(attr="gen_ai.prompt", sub_key="messages", format="python_repr"),
            response_extraction=MultiFieldExtraction(
                fields=[FieldExtraction(attr="gen_ai.completion", sub_key="outputs", format="python_repr")],
            ),
            ingest_filter=SpanFilter(include_prefixes=["llm.", "tool.", "http.request"]),
        )
        assert tp.llm_span_prefix == "llm."
        assert tp.root_span_name == "http.request"
        assert tp.prompt_extraction.format == "python_repr"
        assert tp.request_summary_attr == "openjiuwen.http.request_body"
        assert tp.response_summary_span_prefix == "chain."
        assert tp.response_summary_attr == "openjiuwen.agent.outputs"

    def test_opencode_profile(self):
        tp = TraceProfile(
            name="opencode",
            service_name="opencode",
            llm_span_prefix="ai.streamText",
            tool_span_prefix="ai.toolCall",
            prompt_extraction=FieldExtraction(attr="ai.prompt", sub_key="messages", format="json"),
            response_extraction=MultiFieldExtraction(
                fields=[
                    FieldExtraction(attr="ai.response", sub_key="text"),
                    FieldExtraction(attr="ai.response", sub_key="toolCalls"),
                    FieldExtraction(attr="ai.response", sub_key="reasoning"),
                ],
            ),
            request_summary_attr="",
            response_summary_span_prefix="ai.streamText",
            response_summary_attr="ai.response",
        )
        assert tp.llm_span_prefix == "ai.streamText"
        assert tp.root_span_name == ""
        assert tp.request_summary_attr == ""
        assert tp.response_summary_span_prefix == "ai.streamText"


class TestServiceLanguage:
    """service_language 字段：Python/Java EDPAgent 按 telemetry.sdk.language 消歧。"""

    def test_default_empty(self):
        tp = TraceProfile(name="x", service_name="svc")
        assert tp.service_language == ""

    def test_set_language(self):
        tp = TraceProfile(
            name="edp_agent_java", service_name="edp-agent", service_language="java"
        )
        assert tp.service_language == "java"

    def test_yaml_loads_service_language(self):
        import yaml as _yaml

        data = _yaml.safe_load(
            "profiles:\n  p:\n    service_name: svc\n    service_language: python\n"
        )
        tp = TraceProfile(name="p", **data["profiles"]["p"])
        assert tp.service_language == "python"