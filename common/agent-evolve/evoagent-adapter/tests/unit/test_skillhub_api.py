"""Unit tests for SkillHub API actions on POST /api/v1/skills."""

from __future__ import annotations

import textwrap
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from agent_adapter.config import load_config
from agent_adapter.skillhub.types import DeleteVersionResult, PublishResult, PullResult


def _make_app(tmp_path, *, skillhub_enabled: bool = True):
    yaml_path = tmp_path / "config.yaml"
    log_dir = tmp_path / "logs"
    log_dir.mkdir()
    skills_root = tmp_path / "skills"
    edp_dir = skills_root / "edp_agent" / "demo-skill"
    edp_dir.mkdir(parents=True)
    (edp_dir / "SKILL.md").write_text(
        textwrap.dedent(
            """\
            ---
            name: demo-skill
            description: demo
            ---

            # Demo
            """
        ),
        encoding="utf-8",
    )
    yaml_path.write_text(
        textwrap.dedent(
            f"""\
            skills_root: {skills_root}
            skillhub_enabled: {str(skillhub_enabled).lower()}
            skillhub_base_url: http://skillhub.local:8100
            skillhub_token: test-token
            agents:
              - name: edp_agent
                log_dir: {log_dir}
                skills_dir: {skills_root / "edp_agent"}
            """
        ),
        encoding="utf-8",
    )
    config = load_config(yaml_path)
    from agent_adapter.api.app import create_app

    return create_app(config)


class TestSkillHubApi:
    @staticmethod
    def test_skillhub_disabled_returns_503(tmp_path):
        app = _make_app(tmp_path, skillhub_enabled=False)
        client = TestClient(app)
        response = client.post(
            "/api/v1/skills",
            json={"agent_name": "edp_agent", "action": "list_hub_skills"},
        )
        assert response.status_code == 503
        assert response.json()["error"]["code"] == "SKILLHUB_DISABLED"

    @staticmethod
    @patch("agent_adapter.skillhub.service.SkillHubService.list_hub_skills")
    def test_list_hub_skills(mock_list, tmp_path):
        mock_list.return_value = {"items": [{"asset_id": "abc", "name": "demo-skill"}], "total": 1}
        app = _make_app(tmp_path)
        client = TestClient(app)
        response = client.post(
            "/api/v1/skills",
            json={"agent_name": "edp_agent", "action": "list_hub_skills", "page": 1},
        )
        assert response.status_code == 200
        assert response.json()["total"] == 1

    @staticmethod
    @patch("agent_adapter.skillhub.service.SkillHubService.publish_skill")
    def test_publish_skill(mock_publish, tmp_path):
        mock_publish.return_value = PublishResult(
            asset_id="asset-1",
            skill_name="demo-skill",
            version="1.0.0",
            plugin_type="skill",
            publish_result="success",
            moderation_status="APPROVED",
            checksum_sha256="a" * 64,
            version_desc="optimized",
            local_revision="b" * 64,
        )
        app = _make_app(tmp_path)
        client = TestClient(app)
        response = client.post(
            "/api/v1/skills",
            json={
                "agent_name": "edp_agent",
                "action": "publish_skill",
                "skill_name": "demo-skill",
                "plugin_version": "1.0.0",
                "version_desc": "optimized",
            },
        )
        assert response.status_code == 200
        data = response.json()
        assert data["asset_id"] == "asset-1"
        assert data["version"] == "1.0.0"
        mock_publish.assert_called_once()

    @staticmethod
    @patch("agent_adapter.skillhub.service.SkillHubService.pull_skill")
    def test_pull_skill(mock_pull, tmp_path):
        mock_pull.return_value = PullResult(
            asset_id="asset-1",
            skill_name="demo-skill",
            version="1.0.0",
            local_path="/tmp/demo-skill",
            revision="c" * 64,
        )
        app = _make_app(tmp_path)
        client = TestClient(app)
        response = client.post(
            "/api/v1/skills",
            json={
                "agent_name": "edp_agent",
                "action": "pull_skill",
                "asset_id": "asset-1",
                "version": "1.0.0",
            },
        )
        assert response.status_code == 200
        assert response.json()["skill_name"] == "demo-skill"

    @staticmethod
    @patch("agent_adapter.skillhub.service.SkillHubService.delete_hub_version")
    def test_delete_hub_version(mock_delete, tmp_path):
        mock_delete.return_value = DeleteVersionResult(
            asset_id="asset-1",
            version="1.0.0",
            deleted=True,
        )
        app = _make_app(tmp_path)
        client = TestClient(app)
        response = client.post(
            "/api/v1/skills",
            json={
                "agent_name": "edp_agent",
                "action": "delete_hub_version",
                "asset_id": "asset-1",
                "version": "1.0.0",
            },
        )
        assert response.status_code == 200
        assert response.json()["deleted"] is True

    @staticmethod
    def test_publish_skill_missing_version_when_manual(tmp_path):
        app = _make_app(tmp_path)
        client = TestClient(app)
        with patch("agent_adapter.skillhub.service.SkillHubClient") as mock_client_cls:
            mock_client_cls.return_value = MagicMock()
            response = client.post(
                "/api/v1/skills",
                json={
                    "agent_name": "edp_agent",
                    "action": "publish_skill",
                    "skill_name": "demo-skill",
                },
            )
        assert response.status_code == 400
