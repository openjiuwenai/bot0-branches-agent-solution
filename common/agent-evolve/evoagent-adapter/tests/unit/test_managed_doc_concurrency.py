"""Concurrency tests for per-agent lock passing protocol (T10)."""

from __future__ import annotations

import asyncio
from pathlib import Path

from httpx import ASGITransport, AsyncClient

from agent_adapter.config import AgentEntryConfig, ManagedDocConfig, ManagedDocDefaults
from agent_adapter.managed_doc.apply import ApplyResult
from agent_adapter.managed_doc.registry import ManagedDocRegistry
from agent_adapter.managed_doc.service import ManagedDocService
from agent_adapter.managed_doc.storage import DocStorage

RULE_V1 = "---\nauthor: x\n---\n# v1\n"
RULE_V2 = "---\nauthor: x\n---\n# v2\n"
RULE_V3 = "---\nauthor: x\n---\n# v3\n"


class GatedStrategy:
    """ApplyStrategy that blocks on an Event until released; counts calls."""

    def __init__(self) -> None:
        self.event = asyncio.Event()
        self.call_count = 0

    async def apply(self) -> ApplyResult:
        self.call_count += 1
        await self.event.wait()
        return ApplyResult(ok=True, down_seen=True)


def _build_service(tmp_path: Path, *, strategy: GatedStrategy) -> ManagedDocService:
    path = tmp_path / "host" / "edp" / "AgentRule.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(RULE_V1, encoding="utf-8")
    doc = ManagedDocConfig(kind="agent_rule", path=str(path), apply="file_only")
    agent = AgentEntryConfig(name="edp", managed_docs=[doc])
    registry = ManagedDocRegistry(agents=[agent], defaults=ManagedDocDefaults())
    return ManagedDocService(
        registry=registry,
        strategy_factory=lambda cfg: strategy,
    )


def _build_app(tmp_path: Path, *, strategy: GatedStrategy):
    from agent_adapter.api.app import create_app
    from agent_adapter.config import load_config

    path = tmp_path / "host" / "edp" / "AgentRule.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(RULE_V1, encoding="utf-8")
    yaml_path = tmp_path / "config.yaml"
    yaml_path.write_text(
        "agents:\n"
        "  - name: edp\n"
        "    managed_docs:\n"
        f"      - kind: agent_rule\n"
        f"        path: {path}\n"
        "        apply: file_only\n",
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


# ── AC10.1 并发两个 update 串行 ─────────────────────────────────────


async def test_concurrent_updates_serialize(tmp_path: Path) -> None:
    strategy = GatedStrategy()
    service = _build_service(tmp_path, strategy=strategy)

    # update1：apply gated（持 lock，后台运行）
    r1 = await service.update("edp", "agent_rule", RULE_V2)
    assert "task_id" in r1
    await asyncio.sleep(0)  # 让后台 _run_apply 启动
    assert strategy.call_count == 1  # 第一个 apply 已启动

    # update2 并发：应阻塞在 lock.acquire（第二个 apply 不启动）
    update2 = asyncio.create_task(service.update("edp", "agent_rule", RULE_V3))
    await asyncio.sleep(0.02)  # 让 update2 尝试 acquire
    assert not update2.done()  # 仍未返回
    assert strategy.call_count == 1  # 第二个 apply 未启动

    # 释放 update1 的 apply → lock 释放 → update2 进入
    strategy.event.set()
    await service.join_apply(r1["task_id"])
    r2 = await asyncio.wait_for(update2, timeout=2)
    assert "task_id" in r2
    await service.join_apply(r2["task_id"])

    assert strategy.call_count == 2
    # 最终文件为 V3（update2 后写），.meta 为 V3 sha
    path = tmp_path / "host" / "edp" / "AgentRule.md"
    storage = DocStorage(kind="agent_rule", path=str(path), allow_root=path.parent)
    assert storage.read_revision() == DocStorage.sha256(RULE_V3)


async def test_different_agents_apply_in_parallel(tmp_path: Path) -> None:
    """Per-Agent locks do not serialize independent Agents."""
    strategies = {"edp": GatedStrategy(), "other": GatedStrategy()}
    agents: list[AgentEntryConfig] = []
    for agent_name in strategies:
        path = tmp_path / "host" / agent_name / "AgentRule.md"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(RULE_V1, encoding="utf-8")
        agents.append(
            AgentEntryConfig(
                name=agent_name,
                managed_docs=[
                    ManagedDocConfig(kind="agent_rule", path=str(path), apply="file_only")
                ],
            )
        )
    registry = ManagedDocRegistry(agents=agents, defaults=ManagedDocDefaults())
    service = ManagedDocService(
        registry=registry,
        strategy_factory=lambda cfg: strategies[Path(cfg.path).parent.name],
    )

    results = await asyncio.gather(
        service.update("edp", "agent_rule", RULE_V2),
        service.update("other", "agent_rule", RULE_V2),
    )
    await asyncio.sleep(0)

    assert strategies["edp"].call_count == 1
    assert strategies["other"].call_count == 1

    for strategy in strategies.values():
        strategy.event.set()
    await asyncio.gather(*(service.join_apply(result["task_id"]) for result in results))


# ── AC10.2 事件循环不阻塞：apply 跑期间 GET /health 秒回 ─────────────


async def test_health_endpoint_returns_during_running_apply(tmp_path: Path) -> None:
    """AC10.2 端到端：apply 跑期间 GET /health 秒回（真 ASGI 路由，D-impl 验证）。"""
    strategy = GatedStrategy()
    app = _build_app(tmp_path, strategy=strategy)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        # POST update → 202，apply 后台 gated 运行
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

        # apply 跑期间 GET /health 必须秒回（事件循环不阻塞 — D-impl）
        health = await asyncio.wait_for(client.get("/health"), timeout=2)
        assert health.status_code == 200
        assert health.json() == {"status": "ok"}

        # 释放 apply 让后台 task 收尾，避免 event loop 关闭时 pending
        strategy.event.set()
        await asyncio.sleep(0.05)
