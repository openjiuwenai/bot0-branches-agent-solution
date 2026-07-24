"""Unit tests for TraceAssembler — tag classification, merge logic, and state machine."""

import json
from pathlib import Path

import pytest

from agent_adapter.parser import ParsedTagRecord
from agent_adapter.trace_assembler import (
    ObservationType,
    TraceAssembler,
    TraceArchiveWriter,
    classify_tag,
    merge_dicts_fill_missing,
)


# ── Tag classification ───────────────────────────────────────────────────────


class TestClassifyTag:
    """classify_tag maps full tag strings to observation types and attributes."""

    def test_llm_call_start(self):
        obs_type, base_tag, is_start, is_paired = classify_tag("TAG_LLM_CALL_START")
        assert obs_type == ObservationType.GENERATION
        assert base_tag == "TAG_LLM_CALL"
        assert is_start is True
        assert is_paired is True

    def test_llm_call_end(self):
        obs_type, base_tag, is_start, is_paired = classify_tag("TAG_LLM_CALL_END")
        assert obs_type == ObservationType.GENERATION
        assert base_tag == "TAG_LLM_CALL"
        assert is_start is False
        assert is_paired is True

    def test_http_request_start(self):
        obs_type, base_tag, is_start, is_paired = classify_tag("TAG_HTTP_REQUEST_START")
        assert obs_type == ObservationType.TRACE
        assert base_tag == "TAG_HTTP_REQUEST"
        assert is_start is True
        assert is_paired is True

    def test_tool_execute_end(self):
        obs_type, base_tag, is_start, is_paired = classify_tag("TAG_TOOL_EXECUTE_END")
        assert obs_type == ObservationType.TOOL
        assert base_tag == "TAG_TOOL_EXECUTE"
        assert is_start is False
        assert is_paired is True

    def test_skill_execute_start(self):
        obs_type, base_tag, is_start, is_paired = classify_tag("TAG_SKILL_EXECUTE_START")
        assert obs_type == ObservationType.SKILL
        assert base_tag == "TAG_SKILL_EXECUTE"
        assert is_start is True
        assert is_paired is True

    def test_versatile_start(self):
        obs_type, base_tag, is_start, is_paired = classify_tag("TAG_VERSATILE_START")
        assert obs_type == ObservationType.TOOL
        assert base_tag == "TAG_VERSATILE"
        assert is_start is True
        assert is_paired is True

    def test_planning_decision_singleton(self):
        obs_type, base_tag, is_start, is_paired = classify_tag("TAG_PLANNING_DECISION")
        assert obs_type == ObservationType.SPAN
        assert base_tag == "TAG_PLANNING_DECISION"
        assert is_paired is False


# ── Merge logic ──────────────────────────────────────────────────────────────


class TestMergeDictsFillMissing:
    """merge_dicts_fill_missing uses fill-missing strategy."""

    def test_adds_missing_keys(self):
        target = {"a": 1}
        source = {"b": 2}
        result = merge_dicts_fill_missing(target, source)
        assert result == {"a": 1, "b": 2}

    def test_preserves_existing_keys(self):
        target = {"a": 1, "b": "old"}
        source = {"b": "new", "c": 3}
        result = merge_dicts_fill_missing(target, source)
        assert result == {"a": 1, "b": "old", "c": 3}

    def test_never_overwrites_id(self):
        target = {"id": "original"}
        source = {"id": "new"}
        result = merge_dicts_fill_missing(target, source)
        assert result["id"] == "original"

    def test_recursive_dict_merge_for_metadata(self):
        target = {"metadata": {"a": 1}}
        source = {"metadata": {"b": 2}}
        result = merge_dicts_fill_missing(target, source)
        assert result == {"metadata": {"a": 1, "b": 2}}

    def test_metadata_preserves_existing_nested_keys(self):
        target = {"metadata": {"a": 1, "b": "old"}}
        source = {"metadata": {"b": "new", "c": 3}}
        result = merge_dicts_fill_missing(target, source)
        assert result == {"metadata": {"a": 1, "b": "old", "c": 3}}

    def test_list_append_dedup_for_tags(self):
        target = {"tags": ["a", "b"]}
        source = {"tags": ["b", "c"]}
        result = merge_dicts_fill_missing(target, source)
        assert result == {"tags": ["a", "b", "c"]}

    def test_start_end_merge_typical(self):
        """Simulates START providing input/start_time, END providing output/end_time."""
        start = {
            "id": "call-001",
            "type": "GENERATION",
            "start_time": "2026-06-10 14:30:15.123",
            "input": {"messages": [{"role": "user", "content": "hello"}]},
        }
        end = {
            "id": "call-001",
            "end_time": "2026-06-10 14:30:17.456",
            "output": {"role": "assistant", "content": "hi"},
            "total_cost": 2333,
        }
        result = merge_dicts_fill_missing(start, end)
        assert result["id"] == "call-001"
        assert result["start_time"] == "2026-06-10 14:30:15.123"
        assert result["end_time"] == "2026-06-10 14:30:17.456"
        assert result["input"]["messages"][0]["content"] == "hello"
        assert result["output"]["content"] == "hi"
        assert result["total_cost"] == 2333
        assert result["type"] == "GENERATION"


# ── TraceAssembler state machine ─────────────────────────────────────────────


def _make_record(
    tag: str,
    trace_id: str = "trace-001",
    agent_id: str = "agent-001",
    conversation_id: str = "conv-001",
    time: str = "2026-06-10 14:30:15.123",
    message: dict | None = None,
) -> ParsedTagRecord:
    """Helper to create a ParsedTagRecord for testing."""
    if message is None:
        message = {"id": "call-001"}
    return ParsedTagRecord(
        time=time,
        level="INFO",
        source="test:source:1",
        trace_id=trace_id,
        agent_id=agent_id,
        conversation_id=conversation_id,
        tag=tag,
        cost="0",
        message=message,
    )


class TestTraceAssemblerFeed:
    """TraceAssembler.feed() handles START/END pairing and singleton emission."""

    def test_start_cached_not_emitted(self):
        assembler = TraceAssembler()
        results = assembler.feed(_make_record("TAG_LLM_CALL_START"))
        assert results == []

    def test_start_end_pair_produces_merged_record(self):
        assembler = TraceAssembler()
        assembler.feed(_make_record(
            "TAG_LLM_CALL_START",
            message={"id": "call-001", "type": "GENERATION", "model": "model-sample",
                     "input": {"messages": [{"role": "user", "content": "hello"}]}},
        ))
        results = assembler.feed(_make_record(
            "TAG_LLM_CALL_END",
            message={"id": "call-001", "output": {"text": "hi"}, "total_cost": 2333},
        ))
        assert len(results) == 1
        record = results[0]
        assert record["id"] == "call-001"
        assert record["type"] == "GENERATION"
        assert record["model"] == "model-sample"
        assert record["start_time"] == "2026-06-10 14:30:15.123"
        assert record["end_time"] == "2026-06-10 14:30:15.123"
        assert record["input"]["messages"][0]["content"] == "hello"
        assert record["output"]["text"] == "hi"
        assert record["total_cost"] == 2333
        assert record["trace_id"] == "trace-001"

    def test_http_request_start_produces_trace_record(self):
        assembler = TraceAssembler()
        results = assembler.feed(_make_record(
            "TAG_HTTP_REQUEST_START",
            trace_id="trace-abc",
            conversation_id="conv-001",
            message={"input": {"request_header": {"host": "localhost"}}},
        ))
        # TRACE START is cached like other paired tags
        assert results == []
        # But when we check the pending, it should have id=trace_id
        assert "trace-abc" in assembler._pending

    def test_http_request_trace_uses_trace_id_as_id(self):
        assembler = TraceAssembler()
        assembler.feed(_make_record(
            "TAG_HTTP_REQUEST_START",
            trace_id="trace-abc",
            conversation_id="conv-001",
        ))
        results = assembler.feed(_make_record(
            "TAG_HTTP_REQUEST_END",
            trace_id="trace-abc",
            conversation_id="conv-001",
            message={"output": {"status_code": 200}},
        ))
        assert len(results) == 1
        record = results[0]
        assert record["id"] == "trace-abc"
        assert record["session_id"] == "conv-001"
        assert record["output"]["status_code"] == 200

    def test_http_request_trace_no_type_field(self):
        """TRACE records must NOT have a "type" field.

        TRACE records carry a "timestamp" field from the original payload;
        they do not use "type", "start_time", or "end_time" — those belong
        to observation records (GENERATION, TOOL, SKILL, SPAN).
        """
        assembler = TraceAssembler()
        assembler.feed(_make_record(
            "TAG_HTTP_REQUEST_START",
            trace_id="trace-abc",
            conversation_id="conv-001",
        ))
        results = assembler.feed(_make_record(
            "TAG_HTTP_REQUEST_END",
            trace_id="trace-abc",
            conversation_id="conv-001",
        ))
        assert len(results) == 1
        assert "type" not in results[0]

    def test_http_request_trace_preserves_timestamp(self):
        """TRACE records preserve the "timestamp" from the original payload
        and must NOT have "start_time" or "end_time" injected.
        """
        assembler = TraceAssembler()
        assembler.feed(_make_record(
            "TAG_HTTP_REQUEST_START",
            trace_id="trace-abc",
            conversation_id="conv-001",
            message={"timestamp": "2026-06-10 14:30:15.123", "name": "/api/chat"},
        ))
        results = assembler.feed(_make_record(
            "TAG_HTTP_REQUEST_END",
            trace_id="trace-abc",
            conversation_id="conv-001",
            message={"output": {"status_code": 200}},
        ))
        assert len(results) == 1
        record = results[0]
        # timestamp from the original payload is preserved
        assert record["timestamp"] == "2026-06-10 14:30:15.123"
        # start_time / end_time must NOT be injected
        assert "start_time" not in record
        assert "end_time" not in record
        # type must NOT be injected
        assert "type" not in record

    def test_observation_type_always_set(self):
        """All observation types must be set on the merged record,
        even if the message payload doesn't include "type".
        """
        for tag, expected_type in [
            ("TAG_LLM_CALL_START", "GENERATION"),
            ("TAG_TOOL_EXECUTE_START", "TOOL"),
            ("TAG_SKILL_EXECUTE_START", "SKILL"),
            ("TAG_VERSATILE_START", "TOOL"),
            ("TAG_PLANNING_DECISION", "SPAN"),
        ]:
            assembler = TraceAssembler()
            # Use singleton or start-only; for paired tags just check the start cache
            results = assembler.feed(_make_record(tag))
            if tag == "TAG_PLANNING_DECISION":
                # Singleton is emitted immediately
                assert results[0]["type"] == expected_type, f"tag={tag}"
            else:
                # Paired START is cached — check pending
                for rid, (pending_dict, _, _) in assembler._pending.items():
                    assert pending_dict["type"] == expected_type, f"tag={tag}"

    def test_singleton_emitted_immediately(self):
        assembler = TraceAssembler()
        results = assembler.feed(_make_record(
            "TAG_PLANNING_DECISION",
            message={"id": "plan-001", "thought": "user wants理财"},
        ))
        assert len(results) == 1
        assert results[0]["id"] == "plan-001"

    def test_orphan_end_marked_incomplete(self):
        assembler = TraceAssembler()
        results = assembler.feed(_make_record(
            "TAG_LLM_CALL_END",
            message={"id": "call-orphan", "output": {"text": "hi"}},
        ))
        assert len(results) == 1
        assert results[0]["_incomplete"] is True
        assert results[0]["_incomplete_reason"] == "orphan_end"

    def test_context_injection_for_observations(self):
        assembler = TraceAssembler()
        assembler.feed(_make_record("TAG_LLM_CALL_START"))
        results = assembler.feed(_make_record("TAG_LLM_CALL_END"))
        assert len(results) == 1
        # Observation records should have trace_id (foreign key)
        assert results[0]["trace_id"] == "trace-001"


class TestTraceAssemblerTimeout:
    """TraceAssembler.check_timeouts() emits incomplete records."""

    def test_no_timeout_before_threshold(self):
        assembler = TraceAssembler(pair_timeout_seconds=300)
        assembler.feed(_make_record("TAG_LLM_CALL_START"))
        results = assembler.check_timeouts()
        assert results == []

    def test_timeout_emits_incomplete(self):
        assembler = TraceAssembler(pair_timeout_seconds=-1)  # always timed out
        assembler.feed(_make_record("TAG_LLM_CALL_START"))
        results = assembler.check_timeouts()
        assert len(results) == 1
        assert results[0]["_incomplete"] is True
        assert results[0]["_incomplete_reason"] == "pair_timeout"


class TestTraceAssemblerPidSwitch:
    """TraceAssembler.mark_pid_switch() flushes all pending."""

    def test_pid_switch_flushes_pending(self):
        assembler = TraceAssembler()
        assembler.feed(_make_record("TAG_LLM_CALL_START"))
        results = assembler.mark_pid_switch()
        assert len(results) == 1
        assert results[0]["_incomplete"] is True
        assert results[0]["_incomplete_reason"] == "pid_switch"


# ── TraceArchiveWriter ───────────────────────────────────────────────────────


class TestTraceArchiveWriter:
    """TraceArchiveWriter accumulates records and writes JSONL files."""

    def _read_jsonl(self, path: Path) -> list[dict]:
        """Helper to read a JSONL file into a list of dicts."""
        import json
        records = []
        for line in path.read_text(encoding="utf-8").strip().split("\n"):
            if line.strip():
                records.append(json.loads(line))
        return records

    def test_write_and_finalize(self, tmp_path):
        writer = TraceArchiveWriter(output_dir=tmp_path)
        writer.write({"id": "trace-001", "session_id": "conv-001",
                       "timestamp": "2026-06-10 20:34:22.684"})
        writer.write({"id": "call-001", "session_id": "conv-001", "type": "GENERATION"})

        path = writer.finalize_conversation("conv-001")
        assert path is not None
        assert path.exists()

        data = self._read_jsonl(path)
        assert len(data) == 2
        # TRACE record: no "type" field, has "timestamp"
        assert "type" not in data[0]
        assert data[0]["timestamp"] == "2026-06-10 20:34:22.684"
        # Observation record: has "type" field
        assert data[1]["type"] == "GENERATION"

    def test_filename_is_conversation_id_jsonl(self, tmp_path):
        writer = TraceArchiveWriter(output_dir=tmp_path)
        writer.write({"id": "trace-001", "session_id": "conv-001",
                       "timestamp": "2026-06-10 20:34:22.684"})
        writer.finalize_conversation("conv-001")

        files = list(tmp_path.glob("*.jsonl"))
        assert len(files) == 1
        assert files[0].name == "conv-001.jsonl"

    def test_finalize_appends_to_existing_file(self, tmp_path):
        writer = TraceArchiveWriter(output_dir=tmp_path)
        writer.write({"id": "trace-001", "session_id": "conv-001", "timestamp": "2026-06-10 10:00:00.000"})
        writer.finalize_conversation("conv-001")

        # Second batch for the same conversation
        writer.write({"id": "call-001", "session_id": "conv-001", "type": "GENERATION"})
        writer.finalize_conversation("conv-001")

        data = self._read_jsonl(tmp_path / "conv-001.jsonl")
        assert len(data) == 2

    def test_finalize_all(self, tmp_path):
        writer = TraceArchiveWriter(output_dir=tmp_path)
        writer.write({"id": "t1", "session_id": "conv-a", "timestamp": "2026-06-10 10:00:00.000"})
        writer.write({"id": "t2", "session_id": "conv-b", "timestamp": "2026-06-10 11:00:00.000"})

        paths = writer.finalize_all()
        assert len(paths) == 2

    def test_finalize_empty_conversation(self, tmp_path):
        writer = TraceArchiveWriter(output_dir=tmp_path)
        path = writer.finalize_conversation("nonexistent")
        assert path is None

    def test_list_conversations(self, tmp_path):
        writer = TraceArchiveWriter(output_dir=tmp_path)
        writer.write({"id": "t1", "session_id": "conv-a", "timestamp": "2026-06-10 10:00:00.000"})
        writer.finalize_conversation("conv-a")

        convs = writer.list_conversations()
        assert "conv-a" in convs
