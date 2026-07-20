"""Integration tests for HTTP API endpoints."""

import json
from pathlib import Path

import pytest
from httpx import ASGITransport, AsyncClient

from agent_adapter.config import AdapterConfig
from agent_adapter.pipeline import Pipeline


@pytest.fixture
def adapter_config(tmp_path: Path) -> AdapterConfig:
    """Create a test adapter config pointing to tmp directories."""
    log_dir = tmp_path / "logs"
    log_dir.mkdir()
    output_dir = tmp_path / "output"
    output_dir.mkdir()
    offset_file = tmp_path / "offsets.json"
    return AdapterConfig(
        log_dir=str(log_dir),
        output_dir=str(output_dir),
        offset_file=str(offset_file),
        start_from="head",
    )


def _write_archive(output_dir: Path, conversation_id: str, records: list[dict]) -> None:
    """Helper to write a JSONL archive file in the expected format."""
    file_path = output_dir / f"{conversation_id}.jsonl"
    lines = [json.dumps(r, ensure_ascii=False) for r in records]
    file_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


class TestHealthEndpoint:
    """GET /health returns {status: ok}."""

    async def test_health_returns_ok(self, adapter_config: AdapterConfig) -> None:
        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/health")

        assert response.status_code == 200
        assert response.json() == {"status": "ok"}


class TestTracesListEndpoint:
    """GET /api/v1/traces lists all conversation IDs from output_dir."""

    async def test_poll_before_list_fetches_new_data(self, adapter_config: AdapterConfig) -> None:
        """GET /api/v1/traces triggers a poll so new conversations appear immediately."""
        log_dir = Path(adapter_config.log_dir)

        # Write a complete HTTP_REQUEST START+END pair — this produces a TRACE
        # record which triggers finalize_conversation and writes the JSONL file.
        start_line = "\x01".join([
            "2026-06-10 10:00:00.000", "INFO", "src:1", "trace-x", "agent-y",
            "conv-new", "TAG_HTTP_REQUEST_START", "0",
            json.dumps({"id": "trace-x", "timestamp": "2026-06-10 10:00:00.000"}),
        ])
        end_line = "\x01".join([
            "2026-06-10 10:00:01.000", "INFO", "src:1", "trace-x", "agent-y",
            "conv-new", "TAG_HTTP_REQUEST_END", "0",
            json.dumps({"id": "trace-x", "output": {"status_code": 200}}),
        ])
        (log_dir / "process_99999.log").write_text(start_line + "\n" + end_line + "\n", encoding="utf-8")

        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/api/v1/traces")

        assert response.status_code == 200
        data = response.json()
        assert "conv-new" in data["conversation_ids"]

    async def test_returns_empty_when_no_output_files(self, adapter_config: AdapterConfig) -> None:
        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/api/v1/traces")

        assert response.status_code == 200
        data = response.json()
        assert data == {"conversation_ids": [], "total": 0}

    async def test_returns_conversation_ids_from_json_archive_files(
        self, adapter_config: AdapterConfig,
    ) -> None:
        # Pre-create JSON archive files in output_dir
        output_dir = Path(adapter_config.output_dir)
        _write_archive(output_dir, "conv-001", [{"id": "a"}])
        _write_archive(output_dir, "conv-002", [{"id": "b"}])

        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/api/v1/traces")

        assert response.status_code == 200
        data = response.json()
        assert sorted(data["conversation_ids"]) == ["conv-001", "conv-002"]
        assert data["total"] == 2


class TestTracesDetailEndpoint:
    """GET /api/v1/traces/{conversation_id} returns records for a conversation."""

    async def test_poll_before_read_fetches_new_data(self, adapter_config: AdapterConfig) -> None:
        """GET /api/v1/traces/{id} triggers a poll so new log data is visible immediately."""
        log_dir = Path(adapter_config.log_dir)

        # Write a complete HTTP_REQUEST START+END pair — this produces a TRACE
        # record which triggers finalize_conversation and writes the JSONL file.
        start_line = "\x01".join([
            "2026-06-10 10:00:00.000", "INFO", "src:1", "trace-x", "agent-y",
            "conv-001", "TAG_HTTP_REQUEST_START", "0",
            json.dumps({"id": "trace-x", "timestamp": "2026-06-10 10:00:00.000"}),
        ])
        end_line = "\x01".join([
            "2026-06-10 10:00:01.000", "INFO", "src:1", "trace-x", "agent-y",
            "conv-001", "TAG_HTTP_REQUEST_END", "0",
            json.dumps({"id": "trace-x", "output": {"status_code": 200}}),
        ])
        (log_dir / "process_12345.log").write_text(start_line + "\n" + end_line + "\n", encoding="utf-8")

        # Query WITHOUT manually polling first — the endpoint should poll itself
        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/api/v1/traces/conv-001")

        assert response.status_code == 200
        data = response.json()
        assert data["conversation_id"] == "conv-001"
        assert data["total"] >= 1
        # The record should contain the merged START+END data
        assert any("output" in c for c in data["calls"])

    async def test_returns_calls_for_existing_conversation(
        self, adapter_config: AdapterConfig,
    ) -> None:
        output_dir = Path(adapter_config.output_dir)
        records = [
            {"id": "uuid-001", "session_id": "conv-001", "type": "GENERATION"},
            {"id": "uuid-002", "session_id": "conv-001", "_incomplete": True, "_incomplete_reason": "orphan_end"},
        ]
        _write_archive(output_dir, "conv-001", records)

        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/api/v1/traces/conv-001")

        assert response.status_code == 200
        data = response.json()
        assert data["conversation_id"] == "conv-001"
        assert data["total"] == 2
        assert len(data["calls"]) == 2
        assert data["calls"][0]["id"] == "uuid-001"

    async def test_returns_empty_for_nonexistent_conversation(
        self, adapter_config: AdapterConfig,
    ) -> None:
        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/api/v1/traces/conv-nonexistent")

        assert response.status_code == 200
        data = response.json()
        assert data["conversation_id"] == "conv-nonexistent"
        assert data["calls"] == []
        assert data["total"] == 0

    async def test_complete_filter_returns_only_complete_records(
        self, adapter_config: AdapterConfig,
    ) -> None:
        output_dir = Path(adapter_config.output_dir)
        records = [
            {"id": "uuid-001", "session_id": "conv-001", "type": "GENERATION"},
            {"id": "uuid-002", "session_id": "conv-001", "_incomplete": True, "_incomplete_reason": "orphan_end"},
            {"id": "uuid-003", "session_id": "conv-001", "type": "TOOL"},
        ]
        _write_archive(output_dir, "conv-001", records)

        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/api/v1/traces/conv-001", params={"complete": True})

        assert response.status_code == 200
        data = response.json()
        # complete=True means _incomplete != True, so 2 records
        assert data["total"] == 2
        assert all("_incomplete" not in c or not c["_incomplete"] for c in data["calls"])

    async def test_limit_parameter_restricts_result_count(
        self, adapter_config: AdapterConfig,
    ) -> None:
        output_dir = Path(adapter_config.output_dir)
        records = [
            {"id": f"uuid-{i:03d}", "session_id": "conv-001", "type": "GENERATION"}
            for i in range(5)
        ]
        _write_archive(output_dir, "conv-001", records)

        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/api/v1/traces/conv-001", params={"limit": 2})

        assert response.status_code == 200
        data = response.json()
        assert data["total"] == 5
        assert len(data["calls"]) == 2


class TestStatusEndpoint:
    """GET /api/v1/status returns adapter runtime status."""

    async def test_returns_status_fields(self, adapter_config: AdapterConfig) -> None:
        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/api/v1/status")

        assert response.status_code == 200
        data = response.json()
        # Required fields
        assert "active_file" in data
        assert "offset" in data
        assert "pending_starts_count" in data
        assert "last_read_time" in data
        assert "output_dir_files" in data
        assert "uptime_seconds" in data

    async def test_status_reflects_pipeline_state(self, adapter_config: AdapterConfig) -> None:
        # Write a log, run pipeline so it has some state
        log_dir = Path(adapter_config.log_dir)
        output_dir = Path(adapter_config.output_dir)

        start_line = "\x01".join([
            "2026-06-10 10:00:00.000", "INFO", "src:1", "trace-x", "agent-y",
            "conv-001", "TAG_LLM_CALL_START", "0",
            json.dumps({"id": "uuid-001", "type": "GENERATION", "model": "glm-5"}),
        ])
        log_file = log_dir / "process_12345.log"
        log_file.write_text(start_line + "\n", encoding="utf-8")

        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)
        # Run one poll cycle to populate state
        app.state.pipeline.poll_sync()

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/api/v1/status")

        data = response.json()
        assert data["active_file"] == "process_12345.log"
        assert isinstance(data["offset"], int)
        assert data["pending_starts_count"] == 1
        assert data["output_dir_files"] == 0


class TestConcurrencySafety:
    """Concurrent requests to trace endpoints do not corrupt data.

    Under asyncio (single-threaded, cooperative multitasking):
    - asyncio.Lock serializes pipeline.poll() calls
    - _do_poll() is synchronous: once it starts, it runs without yielding
    - JSONL files are append-only: concurrent reads see consistent data
    """

    async def test_concurrent_trace_requests_no_cross_contamination(
        self, adapter_config: AdapterConfig,
    ) -> None:
        """Multiple concurrent GET /api/v1/traces/{id} return correct per-conversation data."""
        import asyncio

        log_dir = Path(adapter_config.log_dir)

        # Write 3 conversations each in its own log file
        for i in range(1, 4):
            conv_id = f"conv-{i:03d}"
            start_line = "\x01".join([
                "2026-06-10 10:00:00.000", "INFO", "src:1", f"trace-{i}", "agent-y",
                conv_id, "TAG_HTTP_REQUEST_START", "0",
                json.dumps({"id": f"trace-{i}", "timestamp": "2026-06-10 10:00:00.000"}),
            ])
            end_line = "\x01".join([
                "2026-06-10 10:00:01.000", "INFO", "src:1", f"trace-{i}", "agent-y",
                conv_id, "TAG_HTTP_REQUEST_END", "0",
                json.dumps({"id": f"trace-{i}", "output": {"status_code": 200}}),
            ])
            (log_dir / f"process_{i:05d}.log").write_text(
                start_line + "\n" + end_line + "\n", encoding="utf-8"
            )

        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)
        transport = ASGITransport(app=app)

        async with AsyncClient(transport=transport, base_url="http://test") as client:
            # Fire 9 concurrent requests (3 per conversation)
            tasks = []
            for i in range(1, 4):
                for _ in range(3):
                    tasks.append(client.get(f"/api/v1/traces/conv-{i:03d}"))
            responses = await asyncio.gather(*tasks)

        # Verify no cross-contamination between conversations
        for resp in responses:
            data = resp.json()
            cid = data["conversation_id"]
            for call in data["calls"]:
                # session_id must match the requested conversation
                if "session_id" in call:
                    assert call["session_id"] == cid, (
                        f"Cross-contamination: conv {cid} has record with "
                        f"session_id={call['session_id']}"
                    )

    async def test_concurrent_poll_serialized_by_lock(
        self, adapter_config: AdapterConfig,
    ) -> None:
        """Concurrent poll() calls are serialized — no interleaved state mutation."""
        import asyncio

        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)

        # All polls should complete without error
        results = await asyncio.gather(
            app.state.pipeline.poll(),
            app.state.pipeline.poll(),
            app.state.pipeline.poll(),
        )
        # Each poll returns a list — no exception raised
        assert all(isinstance(r, list) for r in results)


class TestAppLifecycle:
    """App lifespan manages a background poll loop."""

    async def test_lifespan_creates_poll_task(self, adapter_config: AdapterConfig) -> None:
        import asyncio

        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)

        async with app.router.lifespan_context(app):
            # Lifespan startup should have created the poll task and cleanup task
            assert app.state.poll_task is not None
            assert isinstance(app.state.poll_task, asyncio.Task)
            assert not app.state.poll_task.done()
            assert app.state.cleanup_task is not None
            assert isinstance(app.state.cleanup_task, asyncio.Task)
            assert not app.state.cleanup_task.done()

        # After exiting the context, both tasks should be cancelled
        await asyncio.sleep(0.1)
        assert app.state.poll_task.cancelled() or app.state.poll_task.done()
        assert app.state.cleanup_task.cancelled() or app.state.cleanup_task.done()

    async def test_poll_loop_runs_during_lifespan(self, adapter_config: AdapterConfig) -> None:
        import asyncio

        from agent_adapter.api.app import create_app

        app = create_app(adapter_config)

        # Write a log line so the poll has something to process
        log_dir = Path(adapter_config.log_dir)
        start_line = "\x01".join([
            "2026-06-10 10:00:00.000", "INFO", "src:1", "trace-x", "agent-y",
            "conv-001", "TAG_LLM_CALL_START", "0",
            json.dumps({"id": "uuid-001", "type": "GENERATION", "model": "glm-5"}),
        ])
        (log_dir / "process_12345.log").write_text(start_line + "\n", encoding="utf-8")

        # Use a short poll interval for testing
        adapter_config.poll_interval = 1

        async with app.router.lifespan_context(app):
            # Wait for at least one poll cycle
            await asyncio.sleep(1.5)
            # The status endpoint should reflect pipeline state
            status = app.state.pipeline.get_status()
            assert status["active_file"] is not None


class TestCLIServer:
    """CLI start command launches FastAPI server."""

    def test_start_command_builds_app(self, tmp_path: Path) -> None:
        """Verify the CLI can construct the app without starting uvicorn."""
        from agent_adapter.api.app import create_app

        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        output_dir = tmp_path / "output"
        output_dir.mkdir()

        config = AdapterConfig(
            log_dir=str(log_dir),
            output_dir=str(output_dir),
            offset_file=str(tmp_path / "offsets.json"),
        )
        app = create_app(config)
        assert app.title == "Agent Adapter"
