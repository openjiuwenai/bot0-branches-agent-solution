"""Integration tests: managed-doc error contract alignment (T14, spec §7.4).

Verifies every error code maps to the correct HTTP status and the unified
``{"error": {"code", "message"}}`` body shape, including the two INVALID_ACTION
sub-cases (malformed action/doc_kind + V2 content validation failure).
"""

from __future__ import annotations

import asyncio
from pathlib import Path

import pytest
from httpx import ASGITransport, AsyncClient

from agent_adapter.config import load_config
from agent_adapter.managed_doc.apply import ApplyResult
from agent_adapter.managed_doc.registry import ManagedDocRegistry
from agent_adapter.managed_doc.service import ManagedDocService

RULE_V1 = "---\nauthor: x\n---\n# v1\n"
RULE_V2 = "---\nauthor: x\n---\n# v2\n"
BAD_CONTENT = "# no frontmatter\njust body\n"


def _build_app(tmp_path: Path, *, strategy_factory=None):
    path = tmp_path / "host" / "edp" / "AgentRule.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(RULE_V1, encoding="utf-8")
    yaml_path = tmp_path / "config.yaml"
    yaml_path.write_text(
        "agents:\n  - name: edp\n    managed_docs:\n"
        f"      - kind: agent_rule\n        path: {path}\n        apply: file_only\n",
        encoding="utf-8",
    )
    config = load_config(yaml_path)
    from agent_adapter.api.app import create_app

    app = create_app(config)
    registry = ManagedDocRegistry(agents=config.agents, defaults=config.managed_doc_defaults)
    app.state.managed_doc_service = ManagedDocService(
        registry=registry,
        strategy_factory=strategy_factory or (lambda cfg: _AlwaysFail()),
    )
    return app, path


class _AlwaysFail:
    async def apply(self) -> ApplyResult:
        return ApplyResult(ok=False, error="boom")


def _assert_contract(resp, status: int, code: str) -> None:
    assert resp.status_code == status, resp.text
    body = resp.json()
    assert set(body.keys()) == {"error"}, body
    err = body["error"]
    assert set(err.keys()) == {"code", "message"}, err
    assert err["code"] == code
    assert isinstance(err["message"], str) and err["message"]


# ── INVALID_ACTION (400) — AC14.2 两类 ─────────────────────────────


async def test_invalid_action_unknown_action(tmp_path: Path) -> None:
    app, _ = _build_app(tmp_path)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "delete"},
        )
        _assert_contract(resp, 400, "INVALID_ACTION")


async def test_invalid_action_empty_doc_kind(tmp_path: Path) -> None:
    app, _ = _build_app(tmp_path)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "", "action": "content"},
        )
        _assert_contract(resp, 400, "INVALID_ACTION")


async def test_invalid_action_update_missing_content(tmp_path: Path) -> None:
    app, _ = _build_app(tmp_path)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "update"},
        )
        _assert_contract(resp, 400, "INVALID_ACTION")


async def test_invalid_action_v2_content_validation_failure(tmp_path: Path) -> None:
    """AC14.2: V2 校验失败 → 400 INVALID_ACTION，且不落盘。"""
    app, path = _build_app(tmp_path)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/api/v1/managed-docs",
            json={
                "agent_name": "edp",
                "doc_kind": "agent_rule",
                "action": "update",
                "content": BAD_CONTENT,
            },
        )
        _assert_contract(resp, 400, "INVALID_ACTION")
        # 文件未变（不落盘）
        assert path.read_text(encoding="utf-8") == RULE_V1


async def test_invalid_action_invalid_json(tmp_path: Path) -> None:
    app, _ = _build_app(tmp_path)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/api/v1/managed-docs",
            content="not json",
            headers={"content-type": "application/json"},
        )
        _assert_contract(resp, 400, "INVALID_ACTION")


# ── AGENT_NOT_FOUND (404) ───────────────────────────────────────────


async def test_agent_not_found(tmp_path: Path) -> None:
    app, _ = _build_app(tmp_path)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "missing", "doc_kind": "agent_rule", "action": "content"},
        )
        _assert_contract(resp, 404, "AGENT_NOT_FOUND")


# ── DOC_NOT_FOUND (404) ─────────────────────────────────────────────


async def test_doc_not_found_unknown_kind(tmp_path: Path) -> None:
    app, _ = _build_app(tmp_path)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "no_such", "action": "content"},
        )
        _assert_contract(resp, 404, "DOC_NOT_FOUND")


async def test_doc_not_found_missing_file(tmp_path: Path) -> None:
    path = tmp_path / "host" / "edp" / "AgentRule.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    # 不写文件
    yaml_path = tmp_path / "config.yaml"
    yaml_path.write_text(
        "agents:\n  - name: edp\n    managed_docs:\n"
        f"      - kind: agent_rule\n        path: {path}\n        apply: file_only\n",
        encoding="utf-8",
    )
    config = load_config(yaml_path)
    from agent_adapter.api.app import create_app

    app = create_app(config)
    app.state.managed_doc_service = ManagedDocService(
        registry=ManagedDocRegistry(agents=config.agents, defaults=config.managed_doc_defaults),
        strategy_factory=lambda cfg: _AlwaysFail(),
    )
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "content"},
        )
        _assert_contract(resp, 404, "DOC_NOT_FOUND")


# ── TASK_NOT_FOUND (404) ────────────────────────────────────────────


async def test_task_not_found(tmp_path: Path) -> None:
    app, _ = _build_app(tmp_path)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.get("/api/v1/managed-docs/tasks/t_missing")
        _assert_contract(resp, 404, "TASK_NOT_FOUND")


# ── INTERNAL_ERROR (500) ────────────────────────────────────────────


async def test_internal_error_when_service_uninitialized(tmp_path: Path) -> None:
    app, _ = _build_app(tmp_path)
    app.state.managed_doc_service = None  # 模拟未装配
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "content"},
        )
        _assert_contract(resp, 500, "INTERNAL_ERROR")


# ── APPLY_FAILED（task FAILED，经轮询拿，非同步 HTTP 错误） ──────────


async def test_apply_failed_via_polling(tmp_path: Path) -> None:
    app, _ = _build_app(tmp_path)  # _AlwaysFail strategy
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        upd = await client.post(
            "/api/v1/managed-docs",
            json={
                "agent_name": "edp",
                "doc_kind": "agent_rule",
                "action": "update",
                "content": RULE_V2,
            },
        )
        assert upd.status_code == 202
        task_id = upd.json()["task_id"]
        for _ in range(50):
            poll = await client.get(f"/api/v1/managed-docs/tasks/{task_id}")
            if poll.json().get("status") == "FAILED":
                break
            await asyncio.sleep(0.01)
        else:  # pragma: no cover
            pytest.fail("task did not reach FAILED")
        data = poll.json()
        assert data["status"] == "FAILED"
        assert data["pending_apply"] is True
        assert data["last_error"] == "boom"


# ── 成功路径 body 形态 ──────────────────────────────────────────────


async def test_content_success_body_shape(tmp_path: Path) -> None:
    app, _ = _build_app(tmp_path)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "content"},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert set(data.keys()) == {
            "doc_kind",
            "content",
            "file_revision",
            "applied_revision",
            "pending_apply",
        }
