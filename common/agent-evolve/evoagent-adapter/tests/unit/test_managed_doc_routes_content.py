"""Unit tests for POST /api/v1/managed-docs action=content (T4)."""

from pathlib import Path

from fastapi.testclient import TestClient

from agent_adapter.config import load_config
from agent_adapter.managed_doc.registry import ManagedDocRegistry
from agent_adapter.managed_doc.service import ManagedDocService
from agent_adapter.managed_doc.storage import DocStorage


def _make_app(tmp_path: Path, *, docs_yaml: str):
    """Create FastAPI app with managed_doc_service wired onto app.state."""
    yaml_path = tmp_path / "config.yaml"
    yaml_path.write_text(docs_yaml, encoding="utf-8")
    config = load_config(yaml_path)

    from agent_adapter.api.app import create_app

    app = create_app(config)
    registry = ManagedDocRegistry(agents=config.agents, defaults=config.managed_doc_defaults)
    app.state.managed_doc_service = ManagedDocService(registry=registry)
    return app


def _edp_yaml(
    tmp_path: Path,
    *,
    apply: str = "file_only",
    restart_cmd: str | None = None,
    restart_timeout: int | None = None,
) -> str:
    path = tmp_path / "host" / "edp" / "AgentRule.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    # 显式行构建（避免 textwrap.dedent 对多行 extra 的缩进错位）。
    item_fields = ["kind: agent_rule", f"path: {path}", f"apply: {apply}"]
    if restart_cmd:
        item_fields.append(f"restart_cmd: {restart_cmd}")
    if restart_timeout is not None:
        item_fields.append(f"restart_timeout: {restart_timeout}")
    lines = [
        "agents:",
        "  - name: edp",
        "    agent_url: http://localhost:8090",
        "    managed_docs:",
        "      - " + item_fields[0],
    ]
    lines += ["        " + f for f in item_fields[1:]]
    return "\n".join(lines) + "\n"


def _seed_rule(tmp_path: Path, body: str = "# Rule v1\n") -> Path:
    path = tmp_path / "host" / "edp" / "AgentRule.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")
    return path


# ── AC4.1 content 200 + 四字段 ───────────────────────────────────────


class TestContentRoute:
    def test_content_returns_four_fields(self, tmp_path: Path) -> None:
        _seed_rule(tmp_path, "# Rule v1\n")
        app = _make_app(tmp_path, docs_yaml=_edp_yaml(tmp_path))
        client = TestClient(app)

        resp = client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "content"},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["doc_kind"] == "agent_rule"
        assert data["content"] == "# Rule v1\n"
        assert data["file_revision"] == DocStorage.sha256("# Rule v1\n")
        assert "applied_revision" in data
        assert "pending_apply" in data
        # G2.3: content 新增 apply_mode + max_task_seconds（file_only → 0）
        assert data["apply_mode"] == "file_only"
        assert data["max_task_seconds"] == 0

    def test_content_restart_apply_mode_and_max_task_seconds(self, tmp_path: Path) -> None:
        _seed_rule(tmp_path, "# Rule v1\n")
        app = _make_app(
            tmp_path,
            docs_yaml=_edp_yaml(
                tmp_path,
                apply="restart",
                restart_cmd="docker restart edp",
                restart_timeout=30,
            ),
        )
        client = TestClient(app)
        resp = client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "content"},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["apply_mode"] == "restart"
        # burst 默认 + restart_timeout=30 → 217（见 T5 表驱动断言）
        assert data["max_task_seconds"] == 217

    def test_content_pending_true_when_no_meta(self, tmp_path: Path) -> None:
        _seed_rule(tmp_path, "# Rule v1\n")
        app = _make_app(tmp_path, docs_yaml=_edp_yaml(tmp_path))
        client = TestClient(app)

        resp = client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "content"},
        )
        data = resp.json()
        assert data["applied_revision"] is None
        assert data["pending_apply"] is True

    def test_content_pending_false_when_meta_matches(self, tmp_path: Path) -> None:
        body = "# Rule v1\n"
        _seed_rule(tmp_path, body)
        app = _make_app(tmp_path, docs_yaml=_edp_yaml(tmp_path))
        # 写 .meta 使 applied == file sha
        storage = DocStorage(
            kind="agent_rule",
            path=str(tmp_path / "host" / "edp" / "AgentRule.md"),
            allow_root=tmp_path / "host" / "edp",
        )
        storage.write_meta(revision=DocStorage.sha256(body))

        client = TestClient(app)
        resp = client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "content"},
        )
        data = resp.json()
        assert data["applied_revision"] == DocStorage.sha256(body)
        assert data["pending_apply"] is False

    # ── AC4.2 未知 agent / 未注册 doc_kind → 404 ────────────────────

    def test_content_unknown_agent_404(self, tmp_path: Path) -> None:
        _seed_rule(tmp_path)
        app = _make_app(tmp_path, docs_yaml=_edp_yaml(tmp_path))
        client = TestClient(app)
        resp = client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "missing", "doc_kind": "agent_rule", "action": "content"},
        )
        assert resp.status_code == 404
        assert resp.json()["error"]["code"] == "AGENT_NOT_FOUND"

    def test_content_unknown_doc_kind_404(self, tmp_path: Path) -> None:
        _seed_rule(tmp_path)
        app = _make_app(tmp_path, docs_yaml=_edp_yaml(tmp_path))
        client = TestClient(app)
        resp = client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "no_such", "action": "content"},
        )
        assert resp.status_code == 404
        assert resp.json()["error"]["code"] == "DOC_NOT_FOUND"

    def test_content_missing_file_404(self, tmp_path: Path) -> None:
        # 不 seed 文件
        app = _make_app(tmp_path, docs_yaml=_edp_yaml(tmp_path))
        client = TestClient(app)
        resp = client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "content"},
        )
        assert resp.status_code == 404
        assert resp.json()["error"]["code"] == "DOC_NOT_FOUND"

    # ── AC4.3 非法 action / 空 doc_kind → 400 INVALID_ACTION ────────

    def test_content_invalid_action_400(self, tmp_path: Path) -> None:
        _seed_rule(tmp_path)
        app = _make_app(tmp_path, docs_yaml=_edp_yaml(tmp_path))
        client = TestClient(app)
        resp = client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "delete"},
        )
        assert resp.status_code == 400
        assert resp.json()["error"]["code"] == "INVALID_ACTION"

    def test_content_empty_doc_kind_400(self, tmp_path: Path) -> None:
        _seed_rule(tmp_path)
        app = _make_app(tmp_path, docs_yaml=_edp_yaml(tmp_path))
        client = TestClient(app)
        resp = client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "", "action": "content"},
        )
        assert resp.status_code == 400
        assert resp.json()["error"]["code"] == "INVALID_ACTION"

    def test_content_invalid_json_400(self, tmp_path: Path) -> None:
        _seed_rule(tmp_path)
        app = _make_app(tmp_path, docs_yaml=_edp_yaml(tmp_path))
        client = TestClient(app)
        resp = client.post(
            "/api/v1/managed-docs",
            content="not json",
            headers={"content-type": "application/json"},
        )
        assert resp.status_code == 400
        assert resp.json()["error"]["code"] == "INVALID_ACTION"
