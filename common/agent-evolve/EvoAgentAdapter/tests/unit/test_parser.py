"""Unit tests for log line parser."""

import json

import pytest

from agent_adapter.parser import ParsedTagRecord, parse_log_line, parse_log_lines

# ── Helper: build a realistic \x01-delimited log line ──

SAMPLE_MESSAGE = json.dumps(
    {
        "id": "uuid-001",
        "type": "GENERATION",
        "model": "glm-5",
        "input": {"messages": [{"role": "user", "content": "hello"}]},
    },
    ensure_ascii=False,
)


def make_log_line(
    time: str = "2026-06-09 14:30:15.123",
    level: str = "INFO    ",
    source: str = "log_rail:before_model_call:78",
    trace_id: str = "trace-abc",
    agent_id: str = "agent-001",
    conversation_id: str = "conv-999",
    tag: str = "TAG_LLM_CALL_START",
    cost: str = "0",
    message: str = SAMPLE_MESSAGE,
) -> str:
    return "\x01".join([time, level, source, trace_id, agent_id, conversation_id, tag, cost, message])


DEFAULT_MATCH_TAGS = {"TAG_LLM_CALL_START", "TAG_LLM_CALL_END"}


class TestParseNormalTagLine:
    """Normal TAG lines parse into ParsedTagRecord with all fields."""

    def test_start_line_parses_all_fields(self):
        line = make_log_line(tag="TAG_LLM_CALL_START")
        result = parse_log_line(line, DEFAULT_MATCH_TAGS)
        assert result is not None
        assert result.time == "2026-06-09 14:30:15.123"
        assert result.level == "INFO"
        assert result.source == "log_rail:before_model_call:78"
        assert result.trace_id == "trace-abc"
        assert result.agent_id == "agent-001"
        assert result.conversation_id == "conv-999"
        assert result.tag == "TAG_LLM_CALL_START"
        assert result.cost == "0"
        assert isinstance(result.message, dict)
        assert result.message["id"] == "uuid-001"
        assert result.message["model"] == "glm-5"

    def test_end_line_parses_correctly(self):
        end_message = json.dumps(
            {"id": "uuid-001", "output": {"text": "hi"}, "total_cost": 2333},
            ensure_ascii=False,
        )
        line = make_log_line(tag="TAG_LLM_CALL_END", message=end_message)
        result = parse_log_line(line, DEFAULT_MATCH_TAGS)
        assert result is not None
        assert result.tag == "TAG_LLM_CALL_END"
        assert isinstance(result.message, dict)
        assert result.message["output"]["text"] == "hi"


class TestParseNonTagLine:
    """Lines without matching tags return None."""

    def test_non_tag_line_returns_none(self):
        line = make_log_line(tag="TAG_PLANNING_DECISION")
        result = parse_log_line(line, DEFAULT_MATCH_TAGS)
        assert result is None

    def test_empty_line_returns_none(self):
        result = parse_log_line("", DEFAULT_MATCH_TAGS)
        assert result is None

    def test_no_tag_field_line_returns_none(self):
        # A line with no \x01 separators at all
        result = parse_log_line("just a plain log line", DEFAULT_MATCH_TAGS)
        assert result is None

    def test_different_match_tags_set(self):
        line = make_log_line(tag="TAG_TOOL_EXECUTE_START")
        result = parse_log_line(line, {"TAG_TOOL_EXECUTE_START", "TAG_TOOL_EXECUTE_END"})
        assert result is not None
        assert result.tag == "TAG_TOOL_EXECUTE_START"


class TestParseInsufficientFields:
    """Lines with fewer than 9 fields still extract what they can."""

    def test_7_fields_extracts_up_to_tag(self):
        # Only 7 fields: time, level, source, trace_id, agent_id, conv_id, tag
        parts = [
            "2026-06-09 14:30:15.123",
            "INFO",
            "log_rail:fn:78",
            "trace-abc",
            "agent-001",
            "conv-999",
            "TAG_LLM_CALL_START",
        ]
        line = "\x01".join(parts)
        result = parse_log_line(line, DEFAULT_MATCH_TAGS)
        assert result is not None
        assert result.tag == "TAG_LLM_CALL_START"
        assert result.trace_id == "trace-abc"
        assert result.conversation_id == "conv-999"
        assert result.cost == ""
        assert result.message is None

    def test_8_fields_extracts_cost_but_no_message(self):
        parts = [
            "2026-06-09 14:30:15.123",
            "INFO",
            "log_rail:fn:78",
            "trace-abc",
            "agent-001",
            "conv-999",
            "TAG_LLM_CALL_START",
            "0",
        ]
        line = "\x01".join(parts)
        result = parse_log_line(line, DEFAULT_MATCH_TAGS)
        assert result is not None
        assert result.cost == "0"
        assert result.message is None

    def test_6_fields_returns_none(self):
        # Less than 7 fields — can't even identify the tag
        parts = ["2026-06-09 14:30:15.123", "INFO", "source", "trace", "agent", "conv"]
        line = "\x01".join(parts)
        result = parse_log_line(line, DEFAULT_MATCH_TAGS)
        assert result is None


class TestParseCorruptedJson:
    """JSON parse failure preserves raw_message and still extracts other fields."""

    def test_broken_json_preserves_raw_message(self):
        broken_message = '{"id": "uuid-001", "model": "glm-5", "input": BROKEN'
        line = make_log_line(message=broken_message)
        result = parse_log_line(line, DEFAULT_MATCH_TAGS)
        assert result is not None
        # message should contain extracted keywords or raw string
        assert result.message is not None

    def test_keyword_fallback_extracts_id_and_model(self):
        broken_message = '{"id": "uuid-001", "model": "glm-5", "type": "GENERATION", bad_field}'
        line = make_log_line(message=broken_message)
        result = parse_log_line(line, DEFAULT_MATCH_TAGS)
        assert result is not None
        # keyword fallback should extract id, model, type
        if isinstance(result.message, dict):
            assert result.message.get("id") == "uuid-001"
            assert result.message.get("model") == "glm-5"

    def test_other_fields_still_extracted_when_json_broken(self):
        broken_message = "not json at all"
        line = make_log_line(message=broken_message)
        result = parse_log_line(line, DEFAULT_MATCH_TAGS)
        assert result is not None
        # Fixed fields are still correct
        assert result.trace_id == "trace-abc"
        assert result.conversation_id == "conv-999"
        assert result.tag == "TAG_LLM_CALL_START"


class TestKeywordFallbackExtraction:
    """Keyword fallback extracts values from broken JSON strings."""

    def test_extracts_nested_object(self):
        broken = '{"id": "uuid-001", "input": {"messages": [{"role": "user"}]}, bad}'
        line = make_log_line(message=broken)
        result = parse_log_line(line, DEFAULT_MATCH_TAGS)
        assert result is not None
        if isinstance(result.message, dict):
            assert result.message.get("id") == "uuid-001"

    def test_completely_unparseable_returns_raw_string(self):
        garbage = "}}}not even close{{{"
        line = make_log_line(message=garbage)
        result = parse_log_line(line, DEFAULT_MATCH_TAGS)
        assert result is not None
        # Falls back to raw string
        assert result.message is not None


class TestMultiLineLog:
    """Multi-line messages (continuation lines) are concatenated correctly."""

    def test_continuation_line_appended_to_previous(self):
        line1 = make_log_line(
            message='{"id": "uuid-001", "input": {"messages": [{"role": "user",',
        )
        continuation = '  "content": "hello"}]}}'
        records, _, _ = parse_log_lines([line1, continuation], DEFAULT_MATCH_TAGS)
        assert len(records) == 1
        assert isinstance(records[0].message, dict)
        assert records[0].message["id"] == "uuid-001"
        assert records[0].message["input"]["messages"][0]["content"] == "hello"

    def test_two_independent_lines_both_parsed(self):
        line1 = make_log_line(tag="TAG_LLM_CALL_START")
        line2 = make_log_line(
            tag="TAG_LLM_CALL_END",
            conversation_id="conv-999",
            message='{"id": "uuid-001", "output": {"text": "hi"}}',
        )
        records, _, _ = parse_log_lines([line1, line2], DEFAULT_MATCH_TAGS)
        assert len(records) == 2
        assert records[0].tag == "TAG_LLM_CALL_START"
        assert records[1].tag == "TAG_LLM_CALL_END"

    def test_empty_lines_skipped(self):
        line1 = make_log_line(tag="TAG_LLM_CALL_START")
        records, _, _ = parse_log_lines(["", line1, ""], DEFAULT_MATCH_TAGS)
        assert len(records) == 1
