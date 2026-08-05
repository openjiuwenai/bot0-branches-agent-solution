"""Unit tests for GET /api/v1/managed-docs/tasks/{task_id} polling (T12)."""

from __future__ import annotations

import asyncio
from pathlib import Path

import pytest
from httpx import ASGITransport, AsyncClient

from agent_adapter.managed_doc.apply import ApplyResult
from agent_adapter.managed_doc.registry import ManagedDocRegistry
from agent_adapter.managed_doc.service import ManagedDocService

RULE_V1 = "---\nauthor: x\n---\n# v1\n"
RULE_V2 = "---\nauthor: x\n---\n# v2\n"


class GatedStrategy:
    def __init__(self, result: ApplyResult | None = None) -> None:
        self.event = asyncio.Event()
        self.result = result or ApplyResult(ok=True, down_seen=True)

    async def apply(self) -> ApplyResult:
        await self.event.wait()
        return self.result


def _build_app(tmp_path: Path, *, strategy: GatedStrategy):
    from agent_adapter.api.app import create_app
    from agent_adapter.config import load_config

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
    app = create_app(config)
    registry = ManagedDocRegistry(agents=config.agents, defaults=config.managed_doc_defaults)
    app.state.managed_doc_service = ManagedDocService(
        registry=registry,
        strategy_factory=lambda cfg: strategy,
    )
    return app


# ── AC12.2 不存在 task_id → 404 TASK_NOT_FOUND ─────────────────────


async def test_task_not_found_404(tmp_path: Path) -> None:
    app = _build_app(tmp_path, strategy=GatedStrategy())
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.get("/api/v1/managed-docs/tasks/t_missing")
        assert resp.status_code == 404
        assert resp.json()["error"]["code"] == "TASK_NOT_FOUND"


# ── AC12.1 轮询可见 PENDING→RUNNING→SUCCEEDED 流转 ──────────────────


async def test_task_poll_running_then_succeeded(tmp_path: Path) -> None:
    strategy = GatedStrategy()  # 成功
    app = _build_app(tmp_path, strategy=strategy)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/api/v1/managed-docs",
            json={
                "agent_name": "edp",
                "doc_kind": "agent_rule",
                "action": "update",
                "content": RULE_V2,
            },
        )
        assert resp.status_code == 202
        task_id = resp.json()["task_id"]

        # 让 _run_apply 启动 → 进入 RUNNING（gated，apply 待 event）
        await asyncio.sleep(0)
        running = await client.get(f"/api/v1/managed-docs/tasks/{task_id}")
        assert running.status_code == 200
        assert running.json()["status"] == "RUNNING"
        assert running.json()["action"] == "update"
        assert running.json()["doc_kind"] == "agent_rule"
        assert "attempts" in running.json()
        assert "down_seen" in running.json()
        assert "created_at" in running.json()
        assert "updated_at" in running.json()

        # 释放 apply → SUCCEEDED
        strategy.event.set()
        for _ in range(50):
            poll = await client.get(f"/api/v1/managed-docs/tasks/{task_id}")
            if poll.json().get("status") == "SUCCEEDED":
                break
            await asyncio.sleep(0.01)
        else:  # pragma: no cover
            pytest.fail("task did not reach SUCCEEDED")

        data = poll.json()
        assert data["status"] == "SUCCEEDED"
        assert data["pending_apply"] is False
        assert data["revision"] is not None


async def test_task_poll_failed(tmp_path: Path) -> None:
    strategy = GatedStrategy(result=ApplyResult(ok=False, error="health never green"))
    app = _build_app(tmp_path, strategy=strategy)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/api/v1/managed-docs",
            json={
                "agent_name": "edp",
                "doc_kind": "agent_rule",
                "action": "update",
                "content": RULE_V2,
            },
        )
        task_id = resp.json()["task_id"]
        strategy.event.set()
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
        assert data["last_error"] == "health never green"
