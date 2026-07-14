"""Unit tests for managed-doc restore path (T13)."""

from __future__ import annotations

import asyncio
from pathlib import Path

import pytest
from httpx import ASGITransport, AsyncClient

from agent_adapter.config import AgentEntryConfig, ManagedDocConfig, ManagedDocDefaults
from agent_adapter.managed_doc.apply import ApplyResult
from agent_adapter.managed_doc.registry import ManagedDocRegistry
from agent_adapter.managed_doc.service import ManagedDocService
from agent_adapter.managed_doc.storage import DocNotFoundError, DocStorage

RULE_V1 = "---\nauthor: x\n---\n# v1\n"
RULE_V2 = "---\nauthor: x\n---\n# v2\n"


class FakeStrategy:
    def __init__(self, result: ApplyResult) -> None:
        self.result = result
        self.call_count = 0

    async def apply(self) -> ApplyResult:
        self.call_count += 1
        return self.result


def _build_service(
    tmp_path: Path, *, strategy: FakeStrategy
) -> tuple[ManagedDocService, Path]:
    path = tmp_path / "host" / "edp" / "AgentRule.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(RULE_V1, encoding="utf-8")
    doc = ManagedDocConfig(kind="agent_rule", path=str(path), apply="file_only")
    agent = AgentEntryConfig(name="edp", managed_docs=[doc])
    registry = ManagedDocRegistry(agents=[agent], defaults=ManagedDocDefaults())
    service = ManagedDocService(
        registry=registry,
        strategy_factory=lambda cfg: strategy,
    )
    return service, path


def _storage(path: Path) -> DocStorage:
    return DocStorage(kind="agent_rule", path=str(path), allow_root=path.parent)


# ── AC13.1 无 snapshot → 404 ────────────────────────────────────────


async def test_restore_no_snapshot_raises(tmp_path: Path) -> None:
    service, _ = _build_service(tmp_path, strategy=FakeStrategy(ApplyResult(ok=True)))
    with pytest.raises(DocNotFoundError):
        await service.restore("edp", "agent_rule")


async def test_restore_no_snapshot_http_404(tmp_path: Path) -> None:
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
    app.state.managed_doc_service = ManagedDocService(
        registry=ManagedDocRegistry(agents=config.agents, defaults=config.managed_doc_defaults),
        strategy_factory=lambda cfg: FakeStrategy(ApplyResult(ok=True)),
    )
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "restore"},
        )
        assert resp.status_code == 404
        assert resp.json()["error"]["code"] == "DOC_NOT_FOUND"


# ── AC13.2 有 snapshot → 建 task 走 apply → .meta=snapshot sha ──────


async def test_restore_reverts_to_snapshot(tmp_path: Path) -> None:
    strategy = FakeStrategy(ApplyResult(ok=True, down_seen=True))
    service, path = _build_service(tmp_path, strategy=strategy)

    # 先 update 到 v2（写 snapshot v1 + apply 成功 → .meta=v2 sha）
    r1 = await service.update("edp", "agent_rule", RULE_V2)
    await service.join_apply(r1["task_id"])
    assert path.read_text(encoding="utf-8") == RULE_V2
    assert _storage(path).read_revision() == DocStorage.sha256(RULE_V2)

    # restore → 读 snapshot v1 → 写回 → apply → .meta=v1 sha
    r2 = await service.restore("edp", "agent_rule")
    assert r2["status"] == "PENDING"
    assert r2["doc_kind"] == "agent_rule"
    assert "task_id" in r2
    await service.join_apply(r2["task_id"])

    assert path.read_text(encoding="utf-8") == RULE_V1  # 文件回退
    assert _storage(path).read_revision() == DocStorage.sha256(RULE_V1)  # .meta=snapshot sha
    assert strategy.call_count == 2  # update + restore 各一次 apply


async def test_restore_apply_failure_leaves_meta_unchanged(tmp_path: Path) -> None:
    strategy = FakeStrategy(ApplyResult(ok=True))
    service, path = _build_service(tmp_path, strategy=strategy)
    # update v2 成功
    r1 = await service.update("edp", "agent_rule", RULE_V2)
    await service.join_apply(r1["task_id"])

    # restore 时 apply 失败 → .meta 不动（仍 v2 sha），文件已回退 v1，pending_apply=true
    strategy.result = ApplyResult(ok=False, error="restart failed")
    r2 = await service.restore("edp", "agent_rule")
    await service.join_apply(r2["task_id"])

    state = service._tasks.get(r2["task_id"])
    assert state.status.name == "FAILED"
    assert state.pending_apply is True
    assert _storage(path).read_revision() == DocStorage.sha256(RULE_V2)  # .meta 未变
    assert path.read_text(encoding="utf-8") == RULE_V1  # 文件已回退


# ── restore via HTTP 端到端 ─────────────────────────────────────────


async def test_restore_via_http(tmp_path: Path) -> None:
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
    strategy = FakeStrategy(ApplyResult(ok=True))
    app.state.managed_doc_service = ManagedDocService(
        registry=ManagedDocRegistry(agents=config.agents, defaults=config.managed_doc_defaults),
        strategy_factory=lambda cfg: strategy,
    )
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        # update v2
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
        # 等 update apply 完成
        for _ in range(50):
            poll = await client.get(f"/api/v1/managed-docs/tasks/{upd.json()['task_id']}")
            if poll.json().get("status") == "SUCCEEDED":
                break
            await asyncio.sleep(0.01)

        # restore
        rest = await client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "restore"},
        )
        assert rest.status_code == 202
        task_id = rest.json()["task_id"]
        for _ in range(50):
            poll = await client.get(f"/api/v1/managed-docs/tasks/{task_id}")
            if poll.json().get("status") == "SUCCEEDED":
                break
            await asyncio.sleep(0.01)
        else:  # pragma: no cover
            pytest.fail("restore task did not reach SUCCEEDED")

        assert poll.json()["status"] == "SUCCEEDED"
        assert path.read_text(encoding="utf-8") == RULE_V1
