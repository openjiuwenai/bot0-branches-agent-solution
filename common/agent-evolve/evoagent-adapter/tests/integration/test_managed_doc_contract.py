"""Integration tests: managed-doc error contract alignment (T14, spec §7.4).

Verifies every error code maps to the correct HTTP status and the unified
``{"error": {"code", "message"}}`` body shape, including the two INVALID_ACTION
sub-cases (malformed action/doc_kind + V2 content validation failure).
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import httpx
import pytest
from httpx import ASGITransport, AsyncClient

from agent_adapter.config import load_config
from agent_adapter.managed_doc.apply import ApplyResult
from agent_adapter.managed_doc.registry import ManagedDocRegistry
from agent_adapter.managed_doc.service import ManagedDocService
from agent_adapter.managed_doc.task import TaskRegistry

RULE_V1 = "---\nauthor: x\n---\n# v1\n"
RULE_V2 = "---\nauthor: x\n---\n# v2\n"
BAD_CONTENT = "# no frontmatter\njust body\n"


def _build_app(
    tmp_path: Path,
    *,
    strategy_factory=None,
    task_registry: TaskRegistry | None = None,
):
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
        task_registry=task_registry,
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


# ── managed-document capability listing ────────────────────────────


async def test_listing_joins_registry_and_http_contract(tmp_path: Path) -> None:
    app, _ = _build_app(tmp_path)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/api/v1/agents/edp/managed-docs")

    assert response.status_code == 200
    assert response.json() == {"agent_name": "edp", "items": [], "total": 0}


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


async def test_expired_task_falls_back_to_snapshot_confirmation(tmp_path: Path) -> None:
    from agent_adapter.managed_doc.storage import DocStorage

    app, _ = _build_app(
        tmp_path,
        strategy_factory=lambda cfg: _OkStrategy(),
        task_registry=TaskRegistry(ttl_seconds=-1),
    )
    target_revision = DocStorage.sha256(RULE_V2)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        update = await client.post(
            "/api/v1/managed-docs",
            json={
                "agent_name": "edp",
                "doc_kind": "agent_rule",
                "action": "update",
                "content": RULE_V2,
            },
        )
        task_id = update.json()["task_id"]
        await app.state.managed_doc_service.join_apply(task_id)

        lost = await client.get(f"/api/v1/managed-docs/tasks/{task_id}")
        _assert_contract(lost, 404, "TASK_NOT_FOUND")
        assert await _confirm_target_from_snapshot(client, target_revision) is True


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
            "apply_mode",  # G2.3 新增
            "max_task_seconds",  # G2.3 新增
        }
        # file_only doc：apply_mode 与 max_task_seconds 语义
        assert data["apply_mode"] == "file_only"
        assert data["max_task_seconds"] == 0


# ── G2/C2: update 200 no-op / 202 started + 四态轮询 + 新字段 ─────────


class _OkStrategy:
    """Always-succeed apply strategy for the 202 started + SUCCEEDED paths."""

    async def apply(self) -> ApplyResult:
        return ApplyResult(ok=True, down_seen=True)


class _GatedStrategy:
    """Blocks inside apply() until ``release`` is set, then returns a result."""

    def __init__(self) -> None:
        self.release = asyncio.Event()
        self.result = ApplyResult(ok=True, down_seen=True)

    async def apply(self) -> ApplyResult:
        await self.release.wait()
        return self.result


class _LoseUpdateResponseTransport(httpx.AsyncBaseTransport):
    """Let the ASGI side effect run, then simulate loss of the update response."""

    def __init__(self, app: object, *, snapshot_unreachable: bool = False) -> None:
        self._inner = ASGITransport(app=app)  # type: ignore[arg-type]
        self._snapshot_unreachable = snapshot_unreachable
        self.update_post_count = 0

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content) if request.content else {}
        if body.get("action") == "content" and self._snapshot_unreachable:
            raise httpx.ConnectError("snapshot unreachable", request=request)
        response = await self._inner.handle_async_request(request)
        if (
            request.method == "POST"
            and request.url.path == "/api/v1/managed-docs"
            and body.get("action") == "update"
        ):
            self.update_post_count += 1
            await response.aread()
            await response.aclose()
            raise httpx.ReadError("update response lost", request=request)
        return response

    async def aclose(self) -> None:
        await self._inner.aclose()


async def _confirm_target_from_snapshot(
    client: AsyncClient,
    target_revision: str,
    *,
    max_reads: int = 3,
) -> bool:
    """Test the consumer contract: bounded snapshot reads and no update fallback."""
    for _ in range(max_reads):
        try:
            response = await client.post(
                "/api/v1/managed-docs",
                json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "content"},
            )
        except httpx.TransportError:
            continue
        body = response.json()
        if body["pending_apply"]:
            continue
        return (
            body["file_revision"] == target_revision and body["applied_revision"] == target_revision
        )
    return False


async def test_update_same_revision_200_no_op(tmp_path: Path) -> None:
    """C2: 同 revision 重发 → 200 {success, revision, pending_apply:false}，无 task_id。"""
    from agent_adapter.managed_doc.storage import DocStorage

    app, path = _build_app(tmp_path, strategy_factory=lambda cfg: _OkStrategy())
    path.write_text(RULE_V2, encoding="utf-8")
    storage = DocStorage(kind="agent_rule", path=str(path), allow_root=path.parent)
    storage.write_meta(revision=DocStorage.sha256(RULE_V2))

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
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["success"] is True
        assert body["revision"] == DocStorage.sha256(RULE_V2)
        assert body["pending_apply"] is False
        assert "task_id" not in body

        snapshot = await client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "content"},
        )
        snapshot_body = snapshot.json()
        assert snapshot_body["pending_apply"] is False
        assert snapshot_body["file_revision"] == DocStorage.sha256(RULE_V2)
        assert snapshot_body["applied_revision"] == DocStorage.sha256(RULE_V2)


async def test_update_new_revision_202_started_pending(tmp_path: Path) -> None:
    """C2: 新 revision → 202 + task_id，响应体携带 PENDING（PENDING 态可观测）。"""
    app, _ = _build_app(tmp_path, strategy_factory=lambda cfg: _GatedStrategy())
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
        assert resp.status_code == 202, resp.text
        body = resp.json()
        assert body["status"] == "PENDING"
        assert "task_id" in body
        assert body["doc_kind"] == "agent_rule"


async def test_task_polling_running_then_succeeded(tmp_path: Path) -> None:
    """C3: 轮询可观测 RUNNING（gated 阻塞）→ SUCCEEDED（释放后）。"""
    from agent_adapter.managed_doc.storage import DocStorage

    gated = _GatedStrategy()
    app, _ = _build_app(tmp_path, strategy_factory=lambda cfg: gated)
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

        # 任务在 gated.apply 阻塞 → 处于 RUNNING（spawned task 需让事件循环调度，
        # 带退避重试直到脱离 PENDING）
        poll: object = None
        for _ in range(100):
            poll = await client.get(f"/api/v1/managed-docs/tasks/{task_id}")
            if poll.json()["status"] != "PENDING":  # type: ignore[union-attr]
                break
            await asyncio.sleep(0.01)
        assert poll is not None
        assert poll.json()["status"] == "RUNNING"  # type: ignore[union-attr]

        # 释放后 → SUCCEEDED
        gated.release.set()
        await app.state.managed_doc_service.join_apply(task_id)
        done = await client.get(f"/api/v1/managed-docs/tasks/{task_id}")
        body = done.json()
        assert body["status"] == "SUCCEEDED"
        assert body["revision"] == DocStorage.sha256(RULE_V2)
        assert body["pending_apply"] is False


async def test_task_polling_failed_carries_last_error(tmp_path: Path) -> None:
    """C3/C9: FAILED 携 last_error + pending_apply:true（默认 _AlwaysFail 策略）。"""
    app, _ = _build_app(tmp_path)  # default _AlwaysFail
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
        await app.state.managed_doc_service.join_apply(task_id)

        poll = await client.get(f"/api/v1/managed-docs/tasks/{task_id}")
        body = poll.json()
        assert body["status"] == "FAILED"
        assert body["last_error"] == "boom"
        assert body["pending_apply"] is True


# ── response-loss recovery uses snapshot only ──────────────────────


async def test_lost_update_response_is_confirmed_by_snapshot_revision(tmp_path: Path) -> None:
    """A caller that loses the 202 body can confirm apply without another update POST."""
    from agent_adapter.managed_doc.storage import DocStorage

    gated = _GatedStrategy()
    app, _ = _build_app(tmp_path, strategy_factory=lambda cfg: gated)
    target_revision = DocStorage.sha256(RULE_V2)
    transport = _LoseUpdateResponseTransport(app)

    async with AsyncClient(transport=transport, base_url="http://test") as client:
        with pytest.raises(httpx.ReadError, match="response lost"):
            await client.post(
                "/api/v1/managed-docs",
                json={
                    "agent_name": "edp",
                    "doc_kind": "agent_rule",
                    "action": "update",
                    "content": RULE_V2,
                },
            )

        pending = await client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "content"},
        )
        assert pending.json()["pending_apply"] is True
        assert pending.json()["file_revision"] == target_revision

        gated.release.set()
        for _ in range(100):
            snapshot = await client.post(
                "/api/v1/managed-docs",
                json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "content"},
            )
            body = snapshot.json()
            if body["pending_apply"] is False:
                break
            await asyncio.sleep(0.01)
        else:  # pragma: no cover
            pytest.fail("snapshot never reached a stable applied revision")

        assert body["file_revision"] == target_revision
        assert body["applied_revision"] == target_revision
        assert transport.update_post_count == 1


async def test_lost_response_pending_snapshot_times_out_without_second_update(
    tmp_path: Path,
) -> None:
    from agent_adapter.managed_doc.storage import DocStorage

    gated = _GatedStrategy()
    app, _ = _build_app(tmp_path, strategy_factory=lambda cfg: gated)
    target_revision = DocStorage.sha256(RULE_V2)
    transport = _LoseUpdateResponseTransport(app)

    async with AsyncClient(transport=transport, base_url="http://test") as client:
        with pytest.raises(httpx.ReadError):
            await client.post(
                "/api/v1/managed-docs",
                json={
                    "agent_name": "edp",
                    "doc_kind": "agent_rule",
                    "action": "update",
                    "content": RULE_V2,
                },
            )

        assert await _confirm_target_from_snapshot(client, target_revision) is False
        assert transport.update_post_count == 1
        gated.release.set()
        await asyncio.sleep(0.01)


async def test_lost_response_unreachable_snapshot_fails_without_second_update(
    tmp_path: Path,
) -> None:
    from agent_adapter.managed_doc.storage import DocStorage

    gated = _GatedStrategy()
    app, _ = _build_app(tmp_path, strategy_factory=lambda cfg: gated)
    target_revision = DocStorage.sha256(RULE_V2)
    transport = _LoseUpdateResponseTransport(app, snapshot_unreachable=True)

    async with AsyncClient(transport=transport, base_url="http://test") as client:
        with pytest.raises(httpx.ReadError):
            await client.post(
                "/api/v1/managed-docs",
                json={
                    "agent_name": "edp",
                    "doc_kind": "agent_rule",
                    "action": "update",
                    "content": RULE_V2,
                },
            )

        assert await _confirm_target_from_snapshot(client, target_revision) is False
        assert transport.update_post_count == 1
        gated.release.set()
        await asyncio.sleep(0.01)


async def test_snapshot_exposes_stable_mismatch_after_later_write(tmp_path: Path) -> None:
    """A stable non-target revision remains distinguishable from successful recovery."""
    from agent_adapter.managed_doc.storage import DocStorage

    app, _ = _build_app(tmp_path, strategy_factory=lambda cfg: _OkStrategy())
    target_revision = DocStorage.sha256(RULE_V2)
    later_revision = DocStorage.sha256("---\nauthor: x\n---\n# later\n")

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        for content in (RULE_V2, "---\nauthor: x\n---\n# later\n"):
            update = await client.post(
                "/api/v1/managed-docs",
                json={
                    "agent_name": "edp",
                    "doc_kind": "agent_rule",
                    "action": "update",
                    "content": content,
                },
            )
            await app.state.managed_doc_service.join_apply(update.json()["task_id"])

        snapshot = await client.post(
            "/api/v1/managed-docs",
            json={"agent_name": "edp", "doc_kind": "agent_rule", "action": "content"},
        )
        body = snapshot.json()
        assert body["pending_apply"] is False
        assert body["file_revision"] == later_revision
        assert body["applied_revision"] == later_revision
        assert body["applied_revision"] != target_revision
        assert await _confirm_target_from_snapshot(client, target_revision) is False
