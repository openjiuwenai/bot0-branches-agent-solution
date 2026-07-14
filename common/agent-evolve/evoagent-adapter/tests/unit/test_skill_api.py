"""Unit tests for POST /api/v1/skills (adapter-api-contract §1)."""

import textwrap

import pytest
from fastapi.testclient import TestClient

from agent_adapter.config import load_config


def _make_app_with_skills(tmp_path, agents_yaml: str):
    """Create FastAPI app and seed skill files under tmp_path/skills."""
    yaml_path = tmp_path / "config.yaml"
    yaml_path.write_text(agents_yaml, encoding="utf-8")
    config = load_config(yaml_path)

    skills_root = tmp_path / "skills"
    edp_dir = skills_root / "edp_agent" / "demo_skill"
    edp_dir.mkdir(parents=True)
    (edp_dir / "SKILL.md").write_text("# Demo Skill\n", encoding="utf-8")

    from agent_adapter.api.app import create_app

    return create_app(config)


class TestSkillsApi:
    """POST /api/v1/skills dispatches by action."""

    def test_skill_list(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_skills(
            tmp_path,
            textwrap.dedent(f"""\
                skills_root: {tmp_path / "skills"}
                agents:
                  - name: edp_agent
                    log_dir: {log_dir}
                    skills_dir: {tmp_path / "skills" / "edp_agent"}
            """),
        )
        client = TestClient(app)

        response = client.post(
            "/api/v1/skills",
            json={"agent_name": "edp_agent", "action": "skill_list"},
        )

        assert response.status_code == 200
        data = response.json()
        assert data == {"skills": [{"name": "demo_skill"}]}

    def test_skill_content(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_skills(
            tmp_path,
            textwrap.dedent(f"""\
                skills_root: {tmp_path / "skills"}
                agents:
                  - name: edp_agent
                    log_dir: {log_dir}
                    skills_dir: {tmp_path / "skills" / "edp_agent"}
            """),
        )
        client = TestClient(app)

        response = client.post(
            "/api/v1/skills",
            json={
                "agent_name": "edp_agent",
                "action": "skill_content",
                "skill_name": "demo_skill",
            },
        )

        assert response.status_code == 200
        data = response.json()
        assert data["skill_name"] == "demo_skill"
        assert data["content"] == "# Demo Skill\n"

    def test_update_skill(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_skills(
            tmp_path,
            textwrap.dedent(f"""\
                skills_root: {tmp_path / "skills"}
                agents:
                  - name: edp_agent
                    log_dir: {log_dir}
                    skills_dir: {tmp_path / "skills" / "edp_agent"}
            """),
        )
        client = TestClient(app)

        response = client.post(
            "/api/v1/skills",
            json={
                "agent_name": "edp_agent",
                "action": "update_skill",
                "skill_name": "demo_skill",
                "skill_content": "# Updated\n",
            },
        )

        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["skill_name"] == "demo_skill"

        skill_path = tmp_path / "skills" / "edp_agent" / "demo_skill" / "SKILL.md"
        assert skill_path.read_text(encoding="utf-8") == "# Updated\n"

        snapshot = tmp_path / "skills" / "edp_agent" / ".meta" / "demo_skill.snapshot"
        assert snapshot.read_text(encoding="utf-8") == "# Demo Skill\n"

    def test_restore_skill_all_success(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_skills(
            tmp_path,
            textwrap.dedent(f"""\
                skills_root: {tmp_path / "skills"}
                agents:
                  - name: edp_agent
                    log_dir: {log_dir}
                    skills_dir: {tmp_path / "skills" / "edp_agent"}
            """),
        )
        client = TestClient(app)

        client.post(
            "/api/v1/skills",
            json={
                "agent_name": "edp_agent",
                "action": "update_skill",
                "skill_name": "demo_skill",
                "skill_content": "# Updated\n",
            },
        )

        response = client.post(
            "/api/v1/skills",
            json={
                "agent_name": "edp_agent",
                "action": "restore_skill",
                "skill_names": ["demo_skill"],
            },
        )

        assert response.status_code == 200
        assert response.json() == {
            "restored": [{"skill_name": "demo_skill", "success": True}],
        }
        skill_path = tmp_path / "skills" / "edp_agent" / "demo_skill" / "SKILL.md"
        assert skill_path.read_text(encoding="utf-8") == "# Demo Skill\n"

    def test_restore_skill_partial_failure(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_skills(
            tmp_path,
            textwrap.dedent(f"""\
                skills_root: {tmp_path / "skills"}
                agents:
                  - name: edp_agent
                    log_dir: {log_dir}
                    skills_dir: {tmp_path / "skills" / "edp_agent"}
            """),
        )
        client = TestClient(app)

        client.post(
            "/api/v1/skills",
            json={
                "agent_name": "edp_agent",
                "action": "update_skill",
                "skill_name": "demo_skill",
                "skill_content": "# Updated\n",
            },
        )

        response = client.post(
            "/api/v1/skills",
            json={
                "agent_name": "edp_agent",
                "action": "restore_skill",
                "skill_names": ["demo_skill", "no_such_skill"],
            },
        )

        assert response.status_code == 200
        data = response.json()
        assert data["restored"][0] == {
            "skill_name": "demo_skill",
            "success": True,
        }
        assert data["restored"][1]["skill_name"] == "no_such_skill"
        assert data["restored"][1]["success"] is False
        assert data["restored"][1]["message"] == "Skill 不存在"

    def test_restore_skill_without_snapshot(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_skills(
            tmp_path,
            textwrap.dedent(f"""\
                skills_root: {tmp_path / "skills"}
                agents:
                  - name: edp_agent
                    log_dir: {log_dir}
                    skills_dir: {tmp_path / "skills" / "edp_agent"}
            """),
        )
        client = TestClient(app)

        response = client.post(
            "/api/v1/skills",
            json={
                "agent_name": "edp_agent",
                "action": "restore_skill",
                "skill_names": ["demo_skill"],
            },
        )

        assert response.status_code == 200
        assert response.json()["restored"][0] == {
            "skill_name": "demo_skill",
            "success": False,
            "message": "未找到快照：该 Skill 未被更新过",
        }

    def test_restore_skill_idempotent(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_skills(
            tmp_path,
            textwrap.dedent(f"""\
                skills_root: {tmp_path / "skills"}
                agents:
                  - name: edp_agent
                    log_dir: {log_dir}
                    skills_dir: {tmp_path / "skills" / "edp_agent"}
            """),
        )
        client = TestClient(app)

        client.post(
            "/api/v1/skills",
            json={
                "agent_name": "edp_agent",
                "action": "update_skill",
                "skill_name": "demo_skill",
                "skill_content": "# Updated\n",
            },
        )
        payload = {
            "agent_name": "edp_agent",
            "action": "restore_skill",
            "skill_names": ["demo_skill"],
        }
        first = client.post("/api/v1/skills", json=payload).json()
        second = client.post("/api/v1/skills", json=payload).json()
        assert first == second
        skill_path = tmp_path / "skills" / "edp_agent" / "demo_skill" / "SKILL.md"
        assert skill_path.read_text(encoding="utf-8") == "# Demo Skill\n"

    def test_update_skill_not_found(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_skills(
            tmp_path,
            textwrap.dedent(f"""\
                skills_root: {tmp_path / "skills"}
                agents:
                  - name: edp_agent
                    log_dir: {log_dir}
                    skills_dir: {tmp_path / "skills" / "edp_agent"}
            """),
        )
        client = TestClient(app)

        response = client.post(
            "/api/v1/skills",
            json={
                "agent_name": "edp_agent",
                "action": "update_skill",
                "skill_name": "no_such_skill",
                "skill_content": "# Updated\n",
            },
        )

        assert response.status_code == 404
        assert response.json()["error"]["code"] == "SKILL_NOT_FOUND"

    def test_agent_not_found(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_skills(
            tmp_path,
            textwrap.dedent(f"""\
                skills_root: {tmp_path / "skills"}
                agents:
                  - name: edp_agent
                    log_dir: {log_dir}
                    skills_dir: {tmp_path / "skills" / "edp_agent"}
            """),
        )
        client = TestClient(app)

        response = client.post(
            "/api/v1/skills",
            json={"agent_name": "missing_agent", "action": "skill_list"},
        )

        assert response.status_code == 404
        data = response.json()
        assert data["error"]["code"] == "AGENT_NOT_FOUND"

    def test_skill_not_found(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_skills(
            tmp_path,
            textwrap.dedent(f"""\
                skills_root: {tmp_path / "skills"}
                agents:
                  - name: edp_agent
                    log_dir: {log_dir}
                    skills_dir: {tmp_path / "skills" / "edp_agent"}
            """),
        )
        client = TestClient(app)

        response = client.post(
            "/api/v1/skills",
            json={
                "agent_name": "edp_agent",
                "action": "skill_content",
                "skill_name": "no_such_skill",
            },
        )

        assert response.status_code == 404
        assert response.json()["error"]["code"] == "SKILL_NOT_FOUND"

    def test_invalid_action(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_skills(
            tmp_path,
            textwrap.dedent(f"""\
                skills_root: {tmp_path / "skills"}
                agents:
                  - name: edp_agent
                    log_dir: {log_dir}
                    skills_dir: {tmp_path / "skills" / "edp_agent"}
            """),
        )
        client = TestClient(app)

        response = client.post(
            "/api/v1/skills",
            json={"agent_name": "edp_agent", "action": "unknown_action"},
        )

        assert response.status_code == 400
        assert response.json()["error"]["code"] == "INVALID_ACTION"

    def test_missing_skill_name_for_content(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()

        app = _make_app_with_skills(
            tmp_path,
            textwrap.dedent(f"""\
                skills_root: {tmp_path / "skills"}
                agents:
                  - name: edp_agent
                    log_dir: {log_dir}
                    skills_dir: {tmp_path / "skills" / "edp_agent"}
            """),
        )
        client = TestClient(app)

        response = client.post(
            "/api/v1/skills",
            json={"agent_name": "edp_agent", "action": "skill_content"},
        )

        assert response.status_code == 400
        assert response.json()["error"]["code"] == "INVALID_ACTION"
