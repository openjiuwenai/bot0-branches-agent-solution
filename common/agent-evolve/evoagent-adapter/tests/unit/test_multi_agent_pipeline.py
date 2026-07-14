"""Unit tests for multi-Agent Pipeline lifecycle (Issue #2)."""

import textwrap

import pytest

from agent_adapter.config import AgentEntryConfig, AdapterConfig, load_config
from agent_adapter.pipeline import Pipeline, create_pipeline_for_agent


class TestPipelinePerAgent:
    """Pipeline can be created from an AgentEntryConfig with agent_name tracking."""

    def test_create_pipeline_from_agent_entry(self, tmp_path):
        """Pipeline created from AgentEntryConfig uses agent-specific paths."""
        log_dir = tmp_path / "edp_logs"
        log_dir.mkdir()
        output_dir = tmp_path / "output" / "edp_agent"
        offset_file = tmp_path / "offsets" / "edp_agent.json"

        agent_cfg = AgentEntryConfig(
            name="edp_agent",
            log_dir=str(log_dir),
            log_pattern="process_*.log",
            output_dir=str(output_dir),
            offset_file=str(offset_file),
        )
        shared_cfg = AdapterConfig(poll_interval=5, pair_timeout=300, start_from="head")

        pipeline = create_pipeline_for_agent(agent_cfg, shared_cfg)

        assert pipeline.agent_name == "edp_agent"
        status = pipeline.get_status()
        assert status["agent_name"] == "edp_agent"

    def test_pipeline_default_agent_name(self, tmp_path):
        """Pipeline created from AdapterConfig directly uses 'default' agent_name."""
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        config = AdapterConfig(
            log_dir=str(log_dir),
            output_dir=str(tmp_path / "output"),
            offset_file=str(tmp_path / "offsets.json"),
        )
        pipeline = Pipeline(config)
        assert pipeline.agent_name == "default"
        status = pipeline.get_status()
        assert status["agent_name"] == "default"


class TestCreateAppMultiAgent:
    """create_app creates per-agent pipelines and agent_clients from config."""

    def test_create_app_with_multiple_agents(self, tmp_path):
        """Two agents in config yield two pipelines and two agent_clients."""
        log_dir1 = tmp_path / "edp_logs"
        log_dir2 = tmp_path / "other_logs"
        log_dir1.mkdir()
        log_dir2.mkdir()

        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(
            textwrap.dedent(f"""\
                poll_interval: 5
                agents:
                  - name: edp_agent
                    log_dir: {log_dir1}
                    output_dir: {tmp_path}/output/edp
                    offset_file: {tmp_path}/offsets/edp.json
                    agent_url: http://localhost:8090
                    project_id: proj_001
                    agent_id: edp_agent
                  - name: other_agent
                    log_dir: {log_dir2}
                    output_dir: {tmp_path}/output/other
                    offset_file: {tmp_path}/offsets/other.json
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)

        from agent_adapter.api.app import create_app
        app = create_app(config)

        # Two pipelines created
        pipelines = app.state.pipelines
        assert isinstance(pipelines, dict)
        assert set(pipelines.keys()) == {"edp_agent", "other_agent"}
        assert pipelines["edp_agent"].agent_name == "edp_agent"
        assert pipelines["other_agent"].agent_name == "other_agent"

        # Two agent_clients created (edp_agent has URL, other_agent doesn't)
        agent_clients = app.state.agent_clients
        assert isinstance(agent_clients, dict)
        assert "edp_agent" in agent_clients
        assert "other_agent" not in agent_clients  # no agent_url

    def test_create_app_backward_compat_single_agent(self, tmp_path):
        """Without agents list, create_app creates a single default pipeline."""
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        config = AdapterConfig(
            log_dir=str(log_dir),
            output_dir=str(tmp_path / "output"),
            offset_file=str(tmp_path / "offsets.json"),
        )

        from agent_adapter.api.app import create_app
        app = create_app(config)

        pipelines = app.state.pipelines
        assert len(pipelines) == 1
        assert "default" in pipelines
        assert pipelines["default"].agent_name == "default"

    def test_create_app_agent_client_created_for_url_agents(self, tmp_path):
        """AgentClient is only created for agents with agent_url configured."""
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(
            textwrap.dedent(f"""\
                agents:
                  - name: with_url
                    log_dir: {log_dir}
                    agent_url: http://localhost:8090
                    project_id: proj_001
                    agent_id: edp_agent
                  - name: without_url
                    log_dir: {log_dir}
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)

        from agent_adapter.api.app import create_app
        app = create_app(config)

        agent_clients = app.state.agent_clients
        assert "with_url" in agent_clients
        assert "without_url" not in agent_clients


class TestStatusEndpointMultiAgent:
    """GET /api/v1/status returns per-agent status in multi-agent mode."""

    def test_status_returns_all_agents(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        yaml_path = tmp_path / "config.yaml"
        yaml_path.write_text(
            textwrap.dedent(f"""\
                agents:
                  - name: edp_agent
                    log_dir: {log_dir}
                  - name: other_agent
                    log_dir: {log_dir}
            """),
            encoding="utf-8",
        )
        config = load_config(yaml_path)

        from agent_adapter.api.app import create_app
        app = create_app(config)

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.get("/api/v1/status")
        assert response.status_code == 200
        data = response.json()
        # Status should contain per-agent info
        assert "agents" in data
        assert "edp_agent" in data["agents"]
        assert "other_agent" in data["agents"]
        assert data["agents"]["edp_agent"]["agent_name"] == "edp_agent"
