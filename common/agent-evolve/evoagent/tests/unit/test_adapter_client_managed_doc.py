"""AdapterClient managed-doc 同步 transport 方法测试（httpx.MockTransport）。

覆盖 F4 三个方法：get_managed_doc_sync / start_managed_doc_update_sync /
get_managed_doc_task_sync。每方法 ≥1 合法响应 + ≥1 HTTP/schema 错误；校验
URL、请求体（agent_name/doc_kind/action/content）、request_timeout 透传。
POST 永不自动重试；GET 临时网络错误不在 transport 层吞（交 Applier 轮询）。
"""

from __future__ import annotations

import json
from typing import Any

import httpx
import pytest

from evo_agent.adapter_client.client import AdapterClient, AdapterError
from evo_agent.adapter_client.types import (
    AlreadyApplied,
    ManagedDocSnapshot,
    TaskState,
    UpdateStarted,
)


def _make_mock_client(
    handler: httpx.MockTransport,
    *,
    agent_name: str = "edp_agent",
    timeout: float = 300.0,
) -> AdapterClient:
    """创建使用 MockTransport 的 AdapterClient，sync_http 显式设 timeout。"""
    client = AdapterClient("http://mock-adapter", agent_name=agent_name)
    client._sync_http = httpx.Client(
        transport=handler,
        base_url="http://mock-adapter",
        timeout=httpx.Timeout(timeout),
        trust_env=False,
    )
    return client


# ── get_managed_doc_sync ──


class TestGetManagedDocSync:
    def test_legal_200_returns_snapshot(self) -> None:
        """action=content 200 → ManagedDocSnapshot，全字段解析。"""
        captured: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["request"] = request
            return httpx.Response(
                200,
                json={
                    "doc_kind": "agent_rule",
                    "content": "# rule",
                    "file_revision": "sha-file",
                    "applied_revision": "sha-applied",
                    "pending_apply": False,
                    "apply_mode": "restart",
                    "max_task_seconds": 120.0,
                },
            )

        client = _make_mock_client(httpx.MockTransport(handler))
        snap = client.get_managed_doc_sync("agent_rule")

        assert isinstance(snap, ManagedDocSnapshot)
        assert snap.content == "# rule"
        assert snap.file_revision == "sha-file"
        assert snap.applied_revision == "sha-applied"
        assert snap.pending_apply is False
        assert snap.apply_mode == "restart"
        assert snap.max_task_seconds == 120.0
        # URL + 请求体
        assert captured["request"].url.path == "/api/v1/managed-docs"
        body = json.loads(captured["request"].content)
        assert body["agent_name"] == "edp_agent"
        assert body["doc_kind"] == "agent_rule"
        assert body["action"] == "content"

    def test_applied_revision_nullable(self) -> None:
        """文档未 apply 时 applied_revision 为 None。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={
                    "doc_kind": "agent_rule",
                    "content": "# rule",
                    "file_revision": "sha-file",
                    "applied_revision": None,
                    "pending_apply": True,
                    "apply_mode": "restart",
                    "max_task_seconds": 60.0,
                },
            )

        client = _make_mock_client(httpx.MockTransport(handler))
        snap = client.get_managed_doc_sync("agent_rule")
        assert snap.applied_revision is None
        assert snap.pending_apply is True

    def test_http_error_raises_adapter_error(self) -> None:
        """5xx → AdapterError（不在 transport 层吞）。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(500, json={"detail": "internal"})

        client = _make_mock_client(httpx.MockTransport(handler))
        with pytest.raises(AdapterError) as exc:
            client.get_managed_doc_sync("agent_rule")
        assert exc.value.status_code == 500

    def test_schema_error_missing_field_raises_adapter_error(self) -> None:
        """响应缺 content 字段 → schema 失败转 AdapterError(status_code=200)。

        使 Applier 的 except AdapterError 接住转 ManagedDocApplyError(fatal)，
        而非抛原生 KeyError/ValueError 被 applier 漏接 → 静默故障（训练继续但 remote 未确认）。
        """

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={  # 缺 content / file_revision 等
                    "doc_kind": "agent_rule",
                    "pending_apply": False,
                },
            )

        client = _make_mock_client(httpx.MockTransport(handler))
        with pytest.raises(AdapterError) as exc:
            client.get_managed_doc_sync("agent_rule")
        assert exc.value.status_code == 200

    def test_malformed_json_200_raises_adapter_error(self) -> None:
        """200 响应体非法 JSON → _handle_response 成功路径转 AdapterError(fatal)。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200, content=b"not-json{", headers={"content-type": "application/json"}
            )

        client = _make_mock_client(httpx.MockTransport(handler))
        with pytest.raises(AdapterError) as exc:
            client.get_managed_doc_sync("agent_rule")
        assert exc.value.status_code == 200

    def test_request_timeout_passthrough(self) -> None:
        """request_timeout 透传到 httpx 单次请求（限制 remaining deadline）。"""
        captured: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["timeout"] = request.extensions.get("timeout")
            return httpx.Response(
                200,
                json={
                    "doc_kind": "k",
                    "content": "c",
                    "file_revision": "f",
                    "applied_revision": "a",
                    "pending_apply": False,
                    "apply_mode": "restart",
                    "max_task_seconds": 1.0,
                },
            )

        client = _make_mock_client(httpx.MockTransport(handler), timeout=300.0)
        client.get_managed_doc_sync("k", request_timeout=7.0)
        to = captured["timeout"]
        assert to is not None
        assert to["read"] == 7.0

    def test_request_timeout_none_uses_client_default(self) -> None:
        """不传 request_timeout 时使用 Client 默认 timeout。"""
        captured: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["timeout"] = request.extensions.get("timeout")
            return httpx.Response(
                200,
                json={
                    "doc_kind": "k",
                    "content": "c",
                    "file_revision": "f",
                    "applied_revision": "a",
                    "pending_apply": False,
                    "apply_mode": "restart",
                    "max_task_seconds": 1.0,
                },
            )

        client = _make_mock_client(httpx.MockTransport(handler), timeout=300.0)
        client.get_managed_doc_sync("k")
        assert captured["timeout"]["read"] == 300.0


# ── start_managed_doc_update_sync ──


class TestStartManagedDocUpdateSync:
    def test_202_new_task_returns_update_started(self) -> None:
        """新任务 202 + task_id → UpdateStarted。"""
        captured: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["request"] = request
            return httpx.Response(
                202,
                json={"task_id": "task-1", "status": "PENDING", "doc_kind": "agent_rule"},
            )

        client = _make_mock_client(httpx.MockTransport(handler))
        result = client.start_managed_doc_update_sync("agent_rule", "# new rule")
        assert isinstance(result, UpdateStarted)
        assert result.task_id == "task-1"
        # 请求体
        body = json.loads(captured["request"].content)
        assert body["action"] == "update"
        assert body["doc_kind"] == "agent_rule"
        assert body["content"] == "# new rule"
        assert body["agent_name"] == "edp_agent"

    def test_200_idempotent_returns_already_applied(self) -> None:
        """幂等命中 200 + revision → AlreadyApplied。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={
                    "success": True,
                    "doc_kind": "agent_rule",
                    "revision": "sha-9",
                    "pending_apply": False,
                    "message": "already applied, no restart",
                },
            )

        client = _make_mock_client(httpx.MockTransport(handler))
        result = client.start_managed_doc_update_sync("agent_rule", "# same")
        assert isinstance(result, AlreadyApplied)
        assert result.revision == "sha-9"

    def test_post_never_auto_retries_on_transport_error(self) -> None:
        """POST 永不自动重试：transport 错误立即抛出，不重复触发 restart。"""
        call_count = 0

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal call_count
            call_count += 1
            raise httpx.ConnectError("connection refused")

        client = _make_mock_client(httpx.MockTransport(handler))
        with pytest.raises(Exception):
            client.start_managed_doc_update_sync("agent_rule", "# x")
        assert call_count == 1, "POST 不得自动重试"

    def test_post_http_error_raises(self) -> None:
        """4xx/5xx → AdapterError。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(400, json={"detail": "invalid content"})

        client = _make_mock_client(httpx.MockTransport(handler))
        with pytest.raises(AdapterError) as exc:
            client.start_managed_doc_update_sync("agent_rule", "# x")
        assert exc.value.status_code == 400

    def test_missing_field_200_raises_adapter_error(self) -> None:
        """202 响应缺 task_id → schema 失败转 AdapterError(status_code=202) fatal。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(202, json={"revision": "sha-x"})  # 缺 task_id

        client = _make_mock_client(httpx.MockTransport(handler))
        with pytest.raises(AdapterError) as exc:
            client.start_managed_doc_update_sync("agent_rule", "# x")
        assert exc.value.status_code == 202


# ── get_managed_doc_task_sync ──


class TestGetManagedDocTaskSync:
    def _task_body(self, status: str) -> dict[str, Any]:
        return {
            "task_id": "task-1",
            "status": status,
            "doc_kind": "agent_rule",
            "action": "update",
            "attempts": 1,
            "down_seen": False,
            "revision": "sha-x",
            "pending_apply": False,
            "last_error": None,
            "created_at": "2026-07-13T00:00:00Z",
            "updated_at": "2026-07-13T00:00:01Z",
        }

    def test_legal_200_returns_task_state(self) -> None:
        """GET /tasks/{task_id} 200 → TaskState，四态原样保留。"""
        captured: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            captured["request"] = request
            return httpx.Response(200, json=self._task_body("RUNNING"))

        client = _make_mock_client(httpx.MockTransport(handler))
        ts = client.get_managed_doc_task_sync("task-1")
        assert isinstance(ts, TaskState)
        assert ts.status == "RUNNING"
        assert ts.task_id == "task-1"
        assert ts.revision == "sha-x"
        assert ts.pending_apply is False
        assert ts.last_error is None
        assert ts.attempts == 1
        assert ts.down_seen is False
        # URL 含 task_id
        assert captured["request"].url.path == "/api/v1/managed-docs/tasks/task-1"

    @pytest.mark.parametrize("status", ["PENDING", "RUNNING", "SUCCEEDED", "FAILED"])
    def test_each_state_parses(self, status: str) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=self._task_body(status))

        client = _make_mock_client(httpx.MockTransport(handler))
        ts = client.get_managed_doc_task_sync("task-1")
        assert ts.status == status

    def test_404_task_not_found_raises(self) -> None:
        """404 TaskNotFound → AdapterError（不重发，交 Applier 决策）。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(404, json={"detail": "task not found"})

        client = _make_mock_client(httpx.MockTransport(handler))
        with pytest.raises(AdapterError) as exc:
            client.get_managed_doc_task_sync("unknown-task")
        assert exc.value.status_code == 404

    def test_failed_task_carries_last_error(self) -> None:
        """FAILED task 的 last_error 解析。"""

        def handler(request: httpx.Request) -> httpx.Response:
            body = self._task_body("FAILED")
            body["last_error"] = "restart timeout"
            body["down_seen"] = True
            return httpx.Response(200, json=body)

        client = _make_mock_client(httpx.MockTransport(handler))
        ts = client.get_managed_doc_task_sync("task-1")
        assert ts.status == "FAILED"
        assert ts.last_error == "restart timeout"
        assert ts.down_seen is True

    def test_missing_field_200_raises_adapter_error(self) -> None:
        """200 响应缺 status → schema 失败转 AdapterError(status_code=200) fatal。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(  # 缺 status
                200, json={"task_id": "task-1", "attempts": 1, "pending_apply": False}
            )

        client = _make_mock_client(httpx.MockTransport(handler))
        with pytest.raises(AdapterError) as exc:
            client.get_managed_doc_task_sync("task-1")
        assert exc.value.status_code == 200
