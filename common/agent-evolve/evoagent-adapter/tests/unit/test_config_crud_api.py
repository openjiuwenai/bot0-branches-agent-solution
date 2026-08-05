"""Unit tests for Config CRUD API + Hot Reload (Issue #5)."""

import textwrap

import pytest
import yaml


def _make_app(tmp_path, agents_yaml: str | None = None):
    """Helper: create a FastAPI app from YAML config."""
    from agent_adapter.config import load_config
    from agent_adapter.api.app import create_app

    yaml_path = tmp_path / "config.yaml"
    if agents_yaml:
        yaml_path.write_text(agents_yaml, encoding="utf-8")
    config = load_config(yaml_path)
    app = create_app(config)
    return app


class TestConfigListAndGet:
    """GET /api/v1/config/agents — list and inspect agent configs."""

    def test_list_agents(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                agent_url: http://localhost:8090
                project_id: proj_001
                agent_id: edp_agent
              - name: other_agent
                log_dir: {log_dir}
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.get("/api/v1/config/agents")
        assert response.status_code == 200
        data = response.json()
        names = [a["name"] for a in data["agents"]]
        assert "edp_agent" in names
        assert "other_agent" in names

    def test_get_single_agent(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                agent_url: http://localhost:8090
                project_id: proj_001
                agent_id: edp_agent
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.get("/api/v1/config/agents/edp_agent")
        assert response.status_code == 200
        data = response.json()
        assert data["name"] == "edp_agent"
        assert data["agent_url"] == "http://localhost:8090"

    def test_get_nonexistent_agent_returns_404(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.get("/api/v1/config/agents/nonexistent")
        assert response.status_code == 404


class TestConfigCreateAgent:
    """POST /api/v1/config/agents — add a new agent config."""

    def test_create_agent(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        new_log_dir = tmp_path / "new_logs"
        new_log_dir.mkdir()

        response = client.post("/api/v1/config/agents", json={
            "name": "new_agent",
            "log_dir": str(new_log_dir),
            "agent_url": "http://localhost:8091",
            "project_id": "proj_002",
            "agent_id": "new_agent",
        })
        assert response.status_code == 200
        data = response.json()
        assert data["name"] == "new_agent"

        # Pipeline should be created
        assert "new_agent" in app.state.pipelines
        # AgentClient should be created (has agent_url)
        assert "new_agent" in app.state.agent_clients

        # YAML file should be updated
        yaml_path = tmp_path / "config.yaml"
        with open(yaml_path, encoding="utf-8") as f:
            yaml_data = yaml.safe_load(f)
        agent_names = [a["name"] for a in yaml_data["agents"]]
        assert "new_agent" in agent_names

    def test_create_duplicate_agent_returns_409(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.post("/api/v1/config/agents", json={
            "name": "edp_agent",
            "log_dir": str(log_dir),
        })
        assert response.status_code == 409


class TestConfigUpdateAgent:
    """PUT /api/v1/config/agents/{name} — modify an existing agent config."""

    def test_update_agent(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                agent_url: http://localhost:8090
                project_id: proj_001
                agent_id: edp_agent
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        # Update agent_url
        response = client.put("/api/v1/config/agents/edp_agent", json={
            "name": "edp_agent",
            "log_dir": str(log_dir),
            "agent_url": "http://localhost:9090",
            "project_id": "proj_001",
            "agent_id": "edp_agent",
        })
        assert response.status_code == 200

        # AgentClient should be recreated with new URL
        assert "edp_agent" in app.state.agent_clients

        # YAML file should be updated
        yaml_path = tmp_path / "config.yaml"
        with open(yaml_path, encoding="utf-8") as f:
            yaml_data = yaml.safe_load(f)
        agent = next(a for a in yaml_data["agents"] if a["name"] == "edp_agent")
        assert agent["agent_url"] == "http://localhost:9090"

    def test_update_nonexistent_agent_returns_404(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.put("/api/v1/config/agents/nonexistent", json={
            "name": "nonexistent",
            "log_dir": str(log_dir),
        })
        assert response.status_code == 404


class TestConfigDeleteAgent:
    """DELETE /api/v1/config/agents/{name} — remove an agent config."""

    def test_delete_agent(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
              - name: other_agent
                log_dir: {log_dir}
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        assert "other_agent" in app.state.pipelines

        response = client.delete("/api/v1/config/agents/other_agent")
        assert response.status_code == 200

        # Pipeline should be removed
        assert "other_agent" not in app.state.pipelines
        # edp_agent should be unaffected
        assert "edp_agent" in app.state.pipelines

        # YAML file should be updated
        yaml_path = tmp_path / "config.yaml"
        with open(yaml_path, encoding="utf-8") as f:
            yaml_data = yaml.safe_load(f)
        agent_names = [a["name"] for a in yaml_data["agents"]]
        assert "other_agent" not in agent_names
        assert "edp_agent" in agent_names

    def test_delete_nonexistent_agent_returns_404(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        app = _make_app(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.delete("/api/v1/config/agents/nonexistent")
        assert response.status_code == 404
