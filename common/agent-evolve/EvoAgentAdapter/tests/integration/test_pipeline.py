"""Integration tests for the three-phase pipeline and trace archive output."""

import json
import textwrap
from pathlib import Path

import pytest

from agent_adapter.config import AdapterConfig
from agent_adapter.pipeline import Pipeline


# ── Helper: write simulated EDPAgent log lines ──

def make_start_line(
    call_id: str = "uuid-001",
    conversation_id: str = "conv-999",
    trace_id: str = "trace-abc",
    agent_id: str = "agent-001",
    time: str = "2026-06-09 14:30:15.123",
    model: str = "glm-5",
) -> str:
    message = json.dumps({
        "id": call_id,
        "type": "GENERATION",
        "model": model,
        "input": {"messages": [{"role": "user", "content": "hello"}]},
    }, ensure_ascii=False)
    return "\x01".join([time, "INFO", "log_rail:before_model_call:78", trace_id, agent_id, conversation_id, "TAG_LLM_CALL_START", "0", message])


def make_end_line(
    call_id: str = "uuid-001",
    conversation_id: str = "conv-999",
    trace_id: str = "trace-abc",
    agent_id: str = "agent-001",
    time: str = "2026-06-09 14:30:17.456",
    output: dict | None = None,
) -> str:
    if output is None:
        output = {"text": "hi"}
    message = json.dumps({
        "id": call_id,
        "end_time": time,
        "output": output,
        "total_cost": 2333,
    }, ensure_ascii=False)
    return "\x01".join([time, "INFO", "log_rail:after_model_call:78", trace_id, agent_id, conversation_id, "TAG_LLM_CALL_END", "2333", message])


def make_http_request_start_line(
    trace_id: str = "trace-abc",
    conversation_id: str = "conv-999",
    agent_id: str = "agent-001",
    time: str = "2026-06-09 14:30:14.000",
) -> str:
    message = json.dumps({
        "input": {
            "request_header": {"host": "localhost:8090"},
            "request_body": {"query": "hello"},
        },
    }, ensure_ascii=False)
    return "\x01".join([time, "INFO", "orchestrator:dispatch:1", trace_id, agent_id, conversation_id, "TAG_HTTP_REQUEST_START", "0", message])


def make_http_request_end_line(
    trace_id: str = "trace-abc",
    conversation_id: str = "conv-999",
    agent_id: str = "agent-001",
    time: str = "2026-06-09 14:30:20.000",
) -> str:
    message = json.dumps({
        "output": {
            "mode": "stream",
            "status_code": 200,
            "events": 5,
            "finish_reason": "stream_completed",
        },
    }, ensure_ascii=False)
    return "\x01".join([time, "INFO", "orchestrator:dispatch:1", trace_id, agent_id, conversation_id, "TAG_HTTP_REQUEST_END", "0", message])


def make_non_tag_line(time: str = "2026-06-09 14:30:14.000") -> str:
    return "\x01".join([time, "INFO", "some:source:10", "trace-x", "agent-y", "conv-z", "SOME_OTHER_TAG", "0", "ignored"])


# Default match_tags for tests — must include all TAG types used in test log lines
_TEST_MATCH_TAGS = [
    "TAG_HTTP_REQUEST_START", "TAG_HTTP_REQUEST_END",
    "TAG_LLM_CALL_START", "TAG_LLM_CALL_END",
    "TAG_TOOL_EXECUTE_START", "TAG_TOOL_EXECUTE_END",
    "TAG_SKILL_EXECUTE_START", "TAG_SKILL_EXECUTE_END",
    "TAG_VERSATILE_START", "TAG_VERSATILE_END",
    "TAG_PLANNING_DECISION",
]


@pytest.fixture
def adapter_dirs(tmp_path):
    """Create isolated log_dir, output_dir, offset_file under tmp_path."""
    log_dir = tmp_path / "logs"
    log_dir.mkdir()
    output_dir = tmp_path / "output"
    output_dir.mkdir()
    offset_file = tmp_path / "offsets.json"
    return log_dir, output_dir, offset_file


class TestEndToEndPipeline:
    """Full pipeline: write log → poll → verify trace archive output."""

    def test_start_end_pair_produces_trace_archive(self, adapter_dirs):
        log_dir, output_dir, offset_file = adapter_dirs

        # Write HTTP_REQUEST + LLM_CALL_START/END + HTTP_REQUEST_END
        log_file = log_dir / "process_12345.log"
        log_file.write_text(
            make_http_request_start_line() + "\n"
            + make_start_line(call_id="uuid-001", conversation_id="conv-999") + "\n"
            + make_end_line(call_id="uuid-001", conversation_id="conv-999") + "\n"
            + make_http_request_end_line() + "\n",
            encoding="utf-8",
        )

        config = AdapterConfig(
            log_dir=str(log_dir),
            output_dir=str(output_dir),
            offset_file=str(offset_file),
            match_tags=_TEST_MATCH_TAGS,
            start_from="head",
        )
        pipeline = Pipeline(config)
        pipeline.poll_sync()

        # Check trace archive file exists
        jsonl_files = list(output_dir.glob("*.jsonl"))
        assert len(jsonl_files) == 1

        data = []
        for line in jsonl_files[0].read_text(encoding="utf-8").strip().split("\n"):
            if line.strip():
                data.append(json.loads(line))
        # Should have: TRACE record + GENERATION observation
        assert len(data) == 2

        # Find the records by type: TRACE has no "type" field but has "timestamp";
        # observations (GENERATION, TOOL, etc.) carry a "type" field.
        trace_rec = next((r for r in data if "type" not in r and "timestamp" in r), None)
        gen_rec = next((r for r in data if r.get("type") == "GENERATION"), None)

        assert trace_rec is not None
        assert trace_rec["id"] == "trace-abc"
        assert trace_rec["session_id"] == "conv-999"
        assert trace_rec["output"]["status_code"] == 200

        assert gen_rec is not None
        assert gen_rec["id"] == "uuid-001"
        assert gen_rec["trace_id"] == "trace-abc"
        assert gen_rec["input"]["messages"][0]["content"] == "hello"
        assert gen_rec["output"]["text"] == "hi"
        assert gen_rec["total_cost"] == 2333

    def test_non_tag_lines_are_filtered(self, adapter_dirs):
        log_dir, output_dir, offset_file = adapter_dirs

        log_file = log_dir / "process_12345.log"
        log_file.write_text(
            make_http_request_start_line() + "\n"
            + make_non_tag_line() + "\n"
            + make_start_line(call_id="uuid-001", conversation_id="conv-999") + "\n"
            + make_end_line(call_id="uuid-001", conversation_id="conv-999") + "\n"
            + make_http_request_end_line() + "\n",
            encoding="utf-8",
        )

        config = AdapterConfig(log_dir=str(log_dir), output_dir=str(output_dir),
                               offset_file=str(offset_file), match_tags=_TEST_MATCH_TAGS,
                               start_from="head")
        pipeline = Pipeline(config)
        pipeline.poll_sync()

        jsonl_files = list(output_dir.glob("*.jsonl"))
        assert len(jsonl_files) == 1
        data = []
        for line in jsonl_files[0].read_text(encoding="utf-8").strip().split("\n"):
            if line.strip():
                data.append(json.loads(line))
        # Only TRACE + GENERATION (non-tag line filtered)
        assert len(data) == 2

    def test_incremental_poll_only_reads_new_content(self, adapter_dirs):
        log_dir, output_dir, offset_file = adapter_dirs

        log_file = log_dir / "process_12345.log"
        log_file.write_text(
            make_http_request_start_line() + "\n"
            + make_start_line(call_id="uuid-001", conversation_id="conv-aaa") + "\n"
            + make_end_line(call_id="uuid-001", conversation_id="conv-aaa") + "\n"
            + make_http_request_end_line(conversation_id="conv-aaa") + "\n",
            encoding="utf-8",
        )

        config = AdapterConfig(log_dir=str(log_dir), output_dir=str(output_dir),
                               offset_file=str(offset_file), match_tags=_TEST_MATCH_TAGS,
                               start_from="head")
        pipeline = Pipeline(config)
        pipeline.poll_sync()

        # Second poll with new content
        with open(log_file, "a", encoding="utf-8") as f:
            f.write(make_start_line(call_id="uuid-002", conversation_id="conv-bbb") + "\n")
            f.write(make_end_line(call_id="uuid-002", conversation_id="conv-bbb") + "\n")

        pipeline.poll_sync()

        # First conversation should be archived; second should be buffered
        jsonl_files = list(Path(output_dir).glob("*.jsonl"))
        assert len(jsonl_files) >= 1


class TestMultipleConversationIds:
    """Records for different conversation_ids go to separate archives."""

    def test_separate_archives_per_conversation(self, adapter_dirs):
        log_dir, output_dir, offset_file = adapter_dirs

        log_file = log_dir / "process_12345.log"
        log_file.write_text(
            make_http_request_start_line(conversation_id="conv-111") + "\n"
            + make_start_line(call_id="uuid-001", conversation_id="conv-111") + "\n"
            + make_end_line(call_id="uuid-001", conversation_id="conv-111") + "\n"
            + make_http_request_end_line(conversation_id="conv-111") + "\n"
            + make_http_request_start_line(trace_id="trace-def", conversation_id="conv-222") + "\n"
            + make_start_line(call_id="uuid-002", conversation_id="conv-222",
                              trace_id="trace-def") + "\n"
            + make_end_line(call_id="uuid-002", conversation_id="conv-222",
                            trace_id="trace-def") + "\n"
            + make_http_request_end_line(trace_id="trace-def", conversation_id="conv-222") + "\n",
            encoding="utf-8",
        )

        config = AdapterConfig(log_dir=str(log_dir), output_dir=str(output_dir),
                               offset_file=str(offset_file), match_tags=_TEST_MATCH_TAGS,
                               start_from="head")
        pipeline = Pipeline(config)
        pipeline.poll_sync()

        jsonl_files = list(output_dir.glob("*.jsonl"))
        assert len(jsonl_files) == 2

        # Verify each archive has the right records
        for f in jsonl_files:
            data = []
            for line in f.read_text(encoding="utf-8").strip().split("\n"):
                if line.strip():
                    data.append(json.loads(line))
            conv_ids = {r.get("session_id") for r in data if r.get("session_id")}
            assert len(conv_ids) == 1  # Each archive has only one conversation


class TestPidSwitch:
    """PID switch: old file pending → incomplete, new file read normally."""

    def test_pid_switch_marks_pending_incomplete(self, adapter_dirs):
        log_dir, output_dir, offset_file = adapter_dirs

        # Write a START without END to old file
        old_file = log_dir / "process_111.log"
        old_file.write_text(
            make_http_request_start_line() + "\n"
            + make_start_line(call_id="uuid-001", conversation_id="conv-999") + "\n",
            encoding="utf-8",
        )

        config = AdapterConfig(log_dir=str(log_dir), output_dir=str(output_dir),
                               offset_file=str(offset_file), match_tags=_TEST_MATCH_TAGS,
                               start_from="head")
        pipeline = Pipeline(config)
        pipeline.poll_sync()

        # Now create a new PID file (simulates process restart)
        new_file = log_dir / "process_222.log"
        new_file.write_text(
            make_start_line(call_id="uuid-002", conversation_id="conv-aaa",
                            trace_id="trace-def") + "\n"
            + make_end_line(call_id="uuid-002", conversation_id="conv-aaa",
                            trace_id="trace-def") + "\n",
            encoding="utf-8",
        )

        pipeline.poll_sync()

        # Old file's pending START should be marked incomplete (pid_switch)
        # Check the writer's buffer — records are keyed by session_id or trace_id
        all_records = []
        for records in pipeline._writer._conversations.values():
            all_records.extend(records)
        incomplete = [r for r in all_records if r.get("_incomplete")]
        assert len(incomplete) >= 1
        assert incomplete[0]["_incomplete_reason"] == "pid_switch"
