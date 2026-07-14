"""Unit tests for Agent Call API endpoint (Issue #4)."""

import textwrap

import pytest

from agent_adapter.config import AdapterConfig, AgentEntryConfig
from agent_adapter.schemas import AgentCallResponse, EventSummary


def _make_app_with_agents(tmp_path, agents_yaml: str):
    """Helper: create a FastAPI app from YAML config string."""
    from agent_adapter.config import load_config

    yaml_path = tmp_path / "config.yaml"
    yaml_path.write_text(agents_yaml, encoding="utf-8")
    config = load_config(yaml_path)

    from agent_adapter.api.app import create_app
    return create_app(config)


class TestAgentCallEndpointRouting:
    """POST /api/v1/agents/{agent_name}/conversations/{conversation_id} routes correctly."""

    def test_call_existing_agent(self, tmp_path):
        """Call an agent with agent_url configured returns AgentCallResponse."""
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        from unittest.mock import AsyncMock, patch

        app = _make_app_with_agents(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                agent_url: http://localhost:8090
                project_id: proj_001
                agent_id: edp_agent
        """))

        mock_response = AgentCallResponse(
            success=True,
            conversation_id="conv1",
            answer="推荐低风险产品",
            interrupted=False,
        )

        with patch.object(
            app.state.agent_clients["edp_agent"], "call", new_callable=AsyncMock
        ) as mock_call:
            mock_call.return_value = mock_response

            from fastapi.testclient import TestClient
            client = TestClient(app)

            response = client.post(
                "/api/v1/agents/edp_agent/conversations/conv1",
                json={"query": "推荐理财产品"},
            )

        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["answer"] == "推荐低风险产品"
        assert data["conversation_id"] == "conv1"

    def test_call_nonexistent_agent_returns_404(self, tmp_path):
        """Call an agent that doesn't exist returns 404."""
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_agents(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.post(
            "/api/v1/agents/nonexistent/conversations/conv1",
            json={"query": "hello"},
        )
        assert response.status_code == 404

    def test_call_agent_without_url_returns_400(self, tmp_path):
        """Call an agent that has no agent_url returns 400."""
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_agents(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: log_only_agent
                log_dir: {log_dir}
        """))

        from fastapi.testclient import TestClient
        client = TestClient(app)

        response = client.post(
            "/api/v1/agents/log_only_agent/conversations/conv1",
            json={"query": "hello"},
        )
        assert response.status_code == 400
        assert "未配置" in response.json()["detail"] or "agent_url" in response.json()["detail"]

    def test_call_agent_with_extra_data(self, tmp_path):
        """extra_data is forwarded to AgentClient.call()."""
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        from unittest.mock import AsyncMock, patch

        app = _make_app_with_agents(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                agent_url: http://localhost:8090
                project_id: proj_001
                agent_id: edp_agent
        """))

        mock_response = AgentCallResponse(
            success=True, conversation_id="conv1", answer="ok",
        )

        with patch.object(
            app.state.agent_clients["edp_agent"], "call", new_callable=AsyncMock
        ) as mock_call:
            mock_call.return_value = mock_response

            from fastapi.testclient import TestClient
            client = TestClient(app)

            response = client.post(
                "/api/v1/agents/edp_agent/conversations/conv1",
                json={"query": "hello", "extra_data": {"UNION_NO": "12345"}},
            )

        assert response.status_code == 200
        # Verify extra_data was passed to AgentClient.call()
        mock_call.assert_called_once_with(
            conversation_id="conv1", query="hello", extra_data={"UNION_NO": "12345"}
        )

    def test_call_agent_failure_returns_error_response(self, tmp_path):
        """AgentClient returns error response when Agent is unreachable."""
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        from unittest.mock import AsyncMock, patch

        app = _make_app_with_agents(tmp_path, textwrap.dedent(f"""\
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                agent_url: http://localhost:8090
                project_id: proj_001
                agent_id: edp_agent
        """))

        mock_response = AgentCallResponse(
            success=False, conversation_id="conv1",
            error="无法连接 Agent 服务: Connection refused",
        )

        with patch.object(
            app.state.agent_clients["edp_agent"], "call", new_callable=AsyncMock
        ) as mock_call:
            mock_call.return_value = mock_response

            from fastapi.testclient import TestClient
            client = TestClient(app)

            response = client.post(
                "/api/v1/agents/edp_agent/conversations/conv1",
                json={"query": "hello"},
            )

        assert response.status_code == 200
        data = response.json()
        assert data["success"] is False
        assert "无法连接" in data["error"]
