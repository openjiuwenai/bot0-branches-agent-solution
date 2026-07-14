"""Unit tests for multi-Agent Traces API adaptation (Issue #6)."""

import json
import textwrap

import pytest


def _write_archive(output_dir, conversation_id: str, records: list[dict]) -> None:
    """Write a JSONL trace archive file for a conversation."""
    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / f"{conversation_id}.jsonl"
    lines = [json.dumps(r, ensure_ascii=False) for r in records]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _make_app(tmp_path, agents_yaml: str):
    """Helper: create a FastAPI app from YAML config."""
    from agent_adapter.config import load_config
    from agent_adapter.api.app import create_app

    yaml_path = tmp_path / "config.yaml"
    yaml_path.write_text(agents_yaml, encoding="utf-8")
    config = load_config(yaml_path)
    return create_app(config)


class TestPerAgentTraces:
    """GET /api/v1/agents/{agent_name}/traces queries per-agent output dir."""

    def test_list_traces_for_specific_agent(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        edp_output = tmp_path / "output" / "edp"
        other_output = tmp_path / "output" / "other"

        _write_archive(edp_output, "conv-edp-001", [{"id": "a"}])
        _write_archive(other_output, "conv-other-001", [{"id": "b"}])

        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                output_dir: {edp_output}
                offset_file: {tmp_path}/offsets/edp.json
              - name: other_agent
                log_dir: {log_dir}
                output_dir: {other_output}
                offset_file: {tmp_path}/offsets/other.json
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.get("/api/v1/agents/edp_agent/traces")
        assert response.status_code == 200
        data = response.json()
        assert "conv-edp-001" in data["conversation_ids"]
        assert "conv-other-001" not in data["conversation_ids"]

    def test_get_traces_detail_for_specific_agent(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        edp_output = tmp_path / "output" / "edp"

        _write_archive(edp_output, "conv-edp-001", [{"id": "a", "type": "GENERATION"}])

        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                output_dir: {edp_output}
                offset_file: {tmp_path}/offsets/edp.json
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.get("/api/v1/agents/edp_agent/traces/conv-edp-001")
        assert response.status_code == 200
        data = response.json()
        assert data["conversation_id"] == "conv-edp-001"
        assert len(data["calls"]) == 1

    def test_nonexistent_agent_returns_404(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                output_dir: {tmp_path}/output/edp
                offset_file: {tmp_path}/offsets/edp.json
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.get("/api/v1/agents/nonexistent/traces")
        assert response.status_code == 404


class TestLegacyTracesCompat:
    """Original /api/v1/traces endpoints aggregate across all agents."""

    def test_list_traces_aggregates_all_agents(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        edp_output = tmp_path / "output" / "edp"
        other_output = tmp_path / "output" / "other"

        _write_archive(edp_output, "conv-edp-001", [{"id": "a"}])
        _write_archive(other_output, "conv-other-001", [{"id": "b"}])

        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                output_dir: {edp_output}
                offset_file: {tmp_path}/offsets/edp.json
              - name: other_agent
                log_dir: {log_dir}
                output_dir: {other_output}
                offset_file: {tmp_path}/offsets/other.json
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.get("/api/v1/traces")
        assert response.status_code == 200
        data = response.json()
        ids = data["conversation_ids"]
        assert "conv-edp-001" in ids
        assert "conv-other-001" in ids

    def test_get_traces_detail_searches_all_agents(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        other_output = tmp_path / "output" / "other"

        _write_archive(other_output, "conv-other-001", [{"id": "b"}])

        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                output_dir: {tmp_path}/output/edp
                offset_file: {tmp_path}/offsets/edp.json
              - name: other_agent
                log_dir: {log_dir}
                output_dir: {other_output}
                offset_file: {tmp_path}/offsets/other.json
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        # /api/v1/traces/conv-other-001 should find it in other_agent's output
        response = client.get("/api/v1/traces/conv-other-001")
        assert response.status_code == 200
        data = response.json()
        assert data["conversation_id"] == "conv-other-001"
        assert len(data["calls"]) == 1
