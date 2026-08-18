"""extract_field / extract_response 单字段提取 + 多字段拼接 + json/python_repr 格式测试。"""

from __future__ import annotations

import json

from agent_adapter.trace_profile.extractors import extract_field, extract_response
from agent_adapter.trace_profile.models import FieldExtraction, MultiFieldExtraction


class TestExtractFieldJson:
    def test_simple_json(self):
        fe = FieldExtraction(attr="ai.prompt", sub_key="messages")
        attrs = {"ai.prompt": json.dumps({"messages": [{"role": "user", "content": "hello"}]})}
        result = extract_field(attrs, fe)
        assert result == [{"role": "user", "content": "hello"}]

    def test_already_parsed(self):
        fe = FieldExtraction(attr="ai.prompt", sub_key="messages")
        attrs = {"ai.prompt": {"messages": [{"role": "user", "content": "hi"}]}}
        result = extract_field(attrs, fe)
        assert result == [{"role": "user", "content": "hi"}]

    def test_no_sub_key(self):
        fe = FieldExtraction(attr="simple")
        attrs = {"simple": "hello"}
        result = extract_field(attrs, fe)
        assert result == "hello"

    def test_missing_attr(self):
        fe = FieldExtraction(attr="nonexistent")
        attrs = {}
        result = extract_field(attrs, fe)
        assert result is None

    def test_json_decode_error_returns_raw(self):
        fe = FieldExtraction(attr="bad", sub_key="x")
        attrs = {"bad": "not valid json!!!"}
        result = extract_field(attrs, fe)
        assert result == "not valid json!!!"

    def test_json_string_no_sub_key(self):
        fe = FieldExtraction(attr="data")
        attrs = {"data": json.dumps({"a": 1, "b": 2})}
        result = extract_field(attrs, fe)
        assert result == {"a": 1, "b": 2}


class TestExtractFieldPythonRepr:
    def test_python_repr_format(self):
        fe = FieldExtraction(attr="gen_ai.prompt", sub_key="messages", format="python_repr")
        attrs = {"gen_ai.prompt": "[SystemMessage(role='system', content='You are helpful.')]"}
        result = extract_field(attrs, fe)
        assert isinstance(result, list)
        assert len(result) == 1
        assert result[0]["role"] == "system"


class TestExtractResponse:
    def test_single_field_assistant_message(self):
        spec = MultiFieldExtraction(
            fields=[FieldExtraction(attr="ai.response", sub_key="text")],
            combine_as="assistant_message",
        )
        span = {"attributes": {"ai.response": json.dumps({"text": "Hello!"})}}
        result = extract_response(span, spec)
        assert result == {"role": "assistant", "content": "Hello!"}

    def test_multi_field_combine(self):
        spec = MultiFieldExtraction(
            fields=[
                FieldExtraction(attr="ai.response", sub_key="text"),
                FieldExtraction(attr="ai.response", sub_key="toolCalls"),
                FieldExtraction(attr="ai.response", sub_key="reasoning"),
            ],
            combine_as="assistant_message",
        )
        attrs = {
            "ai.response": json.dumps({
                "text": "I'll help you.",
                "toolCalls": [{"name": "search", "args": {"q": "test"}}],
                "reasoning": "User wants help.",
            }),
        }
        span = {"attributes": attrs}
        result = extract_response(span, spec)
        assert result["role"] == "assistant"
        assert result["content"] == "I'll help you."
        assert result["toolCalls"] == [{"name": "search", "args": {"q": "test"}}]
        assert result["reasoning"] == "User wants help."

    def test_empty_attrs(self):
        spec = MultiFieldExtraction(
            fields=[FieldExtraction(attr="missing")],
        )
        result = extract_response({"attributes": {}}, spec)
        assert result is None

    def test_no_attributes(self):
        spec = MultiFieldExtraction(
            fields=[FieldExtraction(attr="x")],
        )
        result = extract_response({}, spec)
        assert result is None

    def test_trace_cleaner_compatible(self):
        spec = MultiFieldExtraction(
            fields=[
                FieldExtraction(attr="ai.response", sub_key="text"),
                FieldExtraction(attr="ai.response", sub_key="toolCalls"),
            ],
        )
        span = {"attributes": {"ai.response": json.dumps({"text": "ok", "toolCalls": []})}}
        result = extract_response(span, spec)
        assert result["role"] == "assistant"
        assert "content" in result