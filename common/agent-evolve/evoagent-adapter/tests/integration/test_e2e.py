"""End-to-end integration tests — full adapter lifecycle scenarios.

These tests verify the complete chain: write simulated EDPAgent logs →
run adapter poll → verify JSON archive output → query HTTP API. They cover
scenarios that cross module boundaries (parser + assembler + pipeline + API).
"""

import json
import os
from pathlib import Path

import pytest
from httpx import ASGITransport, AsyncClient

from agent_adapter.config import AdapterConfig
from agent_adapter.pipeline import Pipeline


# ── Helpers ──

def make_start_line(
    call_id: str = "uuid-001",
    conversation_id: str = "conv-999",
    trace_id: str = "trace-abc",
    agent_id: str = "agent-001",
    time: str = "2026-06-10 14:30:15.123",
    model: str = "glm-5",
) -> str:
    message = json.dumps({
        "id": call_id,
        "type": "GENERATION",
        "model": model,
        "input": {"messages": [{"role": "user", "content": "hello"}]},
    }, ensure_ascii=False)
    return "\x01".join([
        time, "INFO", "log_rail:before_model_call:78", trace_id,
        agent_id, conversation_id, "TAG_LLM_CALL_START", "0", message,
    ])


def make_end_line(
    call_id: str = "uuid-001",
    conversation_id: str = "conv-999",
    trace_id: str = "trace-abc",
    agent_id: str = "agent-001",
    time: str = "2026-06-10 14:30:17.456",
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
    return "\x01".join([
        time, "INFO", "log_rail:after_model_call:78", trace_id,
        agent_id, conversation_id, "TAG_LLM_CALL_END", "2333", message,
    ])


def make_http_request_start_line(
    trace_id: str = "trace-abc",
    conversation_id: str = "conv-999",
    agent_id: str = "agent-001",
    time: str = "2026-06-10 14:30:14.000",
) -> str:
    message = json.dumps({
        "timestamp": time,
        "input": {
            "request_header": {"host": "localhost:8090"},
            "request_body": {"query": "hello"},
        },
    }, ensure_ascii=False)
    return "\x01".join([
        time, "INFO", "orchestrator:dispatch:1", trace_id,
        agent_id, conversation_id, "TAG_HTTP_REQUEST_START", "0", message,
    ])


def make_http_request_end_line(
    trace_id: str = "trace-abc",
    conversation_id: str = "conv-999",
    agent_id: str = "agent-001",
    time: str = "2026-06-10 14:30:20.000",
) -> str:
    message = json.dumps({
        "output": {
            "mode": "stream",
            "status_code": 200,
            "events": 5,
            "finish_reason": "stream_completed",
        },
    }, ensure_ascii=False)
    return "\x01".join([
        time, "INFO", "orchestrator:dispatch:1", trace_id,
        agent_id, conversation_id, "TAG_HTTP_REQUEST_END", "0", message,
    ])


# Default match_tags for tests
_TEST_MATCH_TAGS = [
    "TAG_HTTP_REQUEST_START", "TAG_HTTP_REQUEST_END",
    "TAG_LLM_CALL_START", "TAG_LLM_CALL_END",
    "TAG_TOOL_EXECUTE_START", "TAG_TOOL_EXECUTE_END",
    "TAG_SKILL_EXECUTE_START", "TAG_SKILL_EXECUTE_END",
    "TAG_VERSATILE_START", "TAG_VERSATILE_END",
    "TAG_PLANNING_DECISION",
]


@pytest.fixture
def setup(tmp_path: Path):
    """Create test directories and config, return (log_dir, output_dir, config)."""
    log_dir = tmp_path / "logs"
    log_dir.mkdir()
    output_dir = tmp_path / "output"
    output_dir.mkdir()
    offset_file = tmp_path / "offsets.json"
    config = AdapterConfig(
        log_dir=str(log_dir),
        output_dir=str(output_dir),
        offset_file=str(offset_file),
        match_tags=_TEST_MATCH_TAGS,
        start_from="head",
    )
    return log_dir, output_dir, config


class TestFullLifecycle:
    """Write log → poll → verify JSONL archive → query API."""

    def test_complete_chain_log_to_api(self, setup):
        log_dir, output_dir, config = setup

        # Write HTTP_REQUEST + LLM_CALL_START/END + HTTP_REQUEST_END
        log_file = log_dir / "process_12345.log"
        log_file.write_text(
            make_http_request_start_line(conversation_id="conv-e2e") + "\n"
            + make_start_line(call_id="uuid-001", conversation_id="conv-e2e") + "\n"
            + make_end_line(call_id="uuid-001", conversation_id="conv-e2e") + "\n"
            + make_http_request_end_line(conversation_id="conv-e2e") + "\n",
            encoding="utf-8",
        )

        # Run pipeline
        pipeline = Pipeline(config)
        records = pipeline.poll_sync()
        assert len(records) >= 1

        # Verify JSONL archive output
        jsonl_files = list(output_dir.glob("*.jsonl"))
        assert len(jsonl_files) == 1

        data = []
        for line in jsonl_files[0].read_text(encoding="utf-8").strip().split("\n"):
            if line.strip():
                data.append(json.loads(line))
        # Should have: TRACE record + GENERATION observation
        assert len(data) == 2

        # Find records: TRACE has no "type" but has "timestamp";
        # observations have a "type" field
        trace_rec = next((r for r in data if "type" not in r and "timestamp" in r), None)
        gen_rec = next((r for r in data if r.get("type") == "GENERATION"), None)

        assert trace_rec is not None
        assert trace_rec["id"] == "trace-abc"
        assert trace_rec["session_id"] == "conv-e2e"
        assert trace_rec["output"]["status_code"] == 200
        # TRACE record must NOT have "type", "start_time", or "end_time"
        assert "type" not in trace_rec
        assert "start_time" not in trace_rec
        assert "end_time" not in trace_rec

        assert gen_rec is not None
        assert gen_rec["id"] == "uuid-001"
        assert gen_rec["trace_id"] == "trace-abc"


class TestLogRotation:
    """Log file rotation/rebuild: offset resets, data not lost."""

    def test_file_shrunk_resets_and_rereads(self, setup):
        log_dir, output_dir, config = setup

        # Write initial content with HTTP request wrapper
        log_file = log_dir / "process_12345.log"
        log_file.write_text(
            make_http_request_start_line(conversation_id="conv-aaa") + "\n"
            + make_start_line(call_id="uuid-001", conversation_id="conv-aaa") + "\n"
            + make_end_line(call_id="uuid-001", conversation_id="conv-aaa") + "\n"
            + make_http_request_end_line(conversation_id="conv-aaa") + "\n",
            encoding="utf-8",
        )

        pipeline = Pipeline(config)
        pipeline.poll_sync()

        # File shrinks (rotation) — new content with different data
        log_file.write_text(
            make_http_request_start_line(trace_id="trace-def", conversation_id="conv-bbb") + "\n"
            + make_start_line(call_id="uuid-002", conversation_id="conv-bbb",
                              trace_id="trace-def") + "\n"
            + make_end_line(call_id="uuid-002", conversation_id="conv-bbb",
                            trace_id="trace-def") + "\n"
            + make_http_request_end_line(trace_id="trace-def", conversation_id="conv-bbb") + "\n",
            encoding="utf-8",
        )

        pipeline.poll_sync()
        # Should detect integrity issue, reset offset, and re-read
        jsonl_files = list(output_dir.glob("conv-bbb.jsonl"))
        assert len(jsonl_files) >= 1


class TestHalfLineWrite:
    """Incomplete line at poll boundary → completed on next poll."""

    def test_half_line_completed_on_next_poll(self, setup):
        log_dir, output_dir, config = setup

        log_file = log_dir / "process_12345.log"
        # Write HTTP_REQUEST_START + LLM_CALL START + half of END
        http_start = make_http_request_start_line(conversation_id="conv-half")
        start = make_start_line(call_id="uuid-001", conversation_id="conv-half")
        end_line = make_end_line(call_id="uuid-001", conversation_id="conv-half")
        # Write START lines + half of END without newline
        half_end = end_line[:len(end_line) // 2]
        log_file.write_text(http_start + "\n" + start + "\n" + half_end, encoding="utf-8")

        pipeline = Pipeline(config)
        records = pipeline.poll_sync()
        # LLM_CALL START should be pending (no END yet)
        # HTTP_REQUEST START should be pending too
        # No completed pairs yet
        assert len(records) == 0

        # Complete the END line + HTTP_REQUEST_END
        remaining = end_line[len(end_line) // 2:] + "\n"
        http_end = make_http_request_end_line(conversation_id="conv-half") + "\n"
        with open(log_file, "a", encoding="utf-8") as f:
            f.write(remaining)
            f.write(http_end)

        records = pipeline.poll_sync()
        # Should have merged LLM_CALL and TRACE records
        assert len(records) >= 1
        # Verify archive was written
        jsonl_files = list(output_dir.glob("*.jsonl"))
        assert len(jsonl_files) >= 1


class TestHTTPAPIFullChain:
    """HTTP API queries return correct data after pipeline processes logs."""

    async def test_api_returns_processed_data(self, setup):
        log_dir, output_dir, config = setup

        # Write and process a complete set with HTTP request
        log_file = log_dir / "process_12345.log"
        log_file.write_text(
            make_http_request_start_line(conversation_id="conv-api") + "\n"
            + make_start_line(call_id="uuid-001", conversation_id="conv-api") + "\n"
            + make_end_line(call_id="uuid-001", conversation_id="conv-api") + "\n"
            + make_http_request_end_line(conversation_id="conv-api") + "\n",
            encoding="utf-8",
        )

        from agent_adapter.api.app import create_app

        app = create_app(config)
        # Run one poll to populate data
        app.state.pipeline.poll_sync()

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            # Test /health
            r = await client.get("/health")
            assert r.status_code == 200
            assert r.json() == {"status": "ok"}

            # Test /api/v1/traces
            r = await client.get("/api/v1/traces")
            assert r.status_code == 200
            data = r.json()
            assert "conv-api" in data["conversation_ids"]

            # Test /api/v1/traces/conv-api
            r = await client.get("/api/v1/traces/conv-api")
            assert r.status_code == 200
            data = r.json()
            assert data["conversation_id"] == "conv-api"
            assert data["total"] >= 1

            # Test /api/v1/status
            r = await client.get("/api/v1/status")
            assert r.status_code == 200
            data = r.json()
            assert data["active_file"] is not None
            assert data["pending_starts_count"] == 0


class TestConfigLoadingE2E:
    """YAML + environment variable configuration loading."""

    def test_yaml_and_env_override(self, tmp_path):
        yaml_path = tmp_path / "adapter_config.yaml"
        yaml_path.write_text(
            "log_dir: /yaml/logs\n"
            "poll_interval: 15\n"
            "port: 9100\n",
            encoding="utf-8",
        )

        # Set env var to override poll_interval
        os.environ["ADAPTER_POLL_INTERVAL"] = "20"
        try:
            from agent_adapter.config import load_config

            config = load_config(yaml_path)
            assert config.log_dir == "/yaml/logs"  # from YAML
            assert config.poll_interval == 20  # env overrides YAML
            assert config.port == 9100  # from YAML, not overridden
        finally:
            del os.environ["ADAPTER_POLL_INTERVAL"]
