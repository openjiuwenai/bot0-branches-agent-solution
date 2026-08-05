"""ManagedDocApplier 深模块测试（spec F5 全 13 例）。

Applier 隐藏轮询/deadline/hash 去重/状态机；外部只暴露 apply_and_wait + 只读 records。
全程同步，无 asyncio.run。clock/sleeper 注入可测。
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from typing import Any

import httpx
import pytest

from evo_agent.adapter_client.applier import (
    AppliedDocument,
    ManagedDocApplier,
    ManagedDocApplyRecord,
)
from evo_agent.adapter_client.client import AdapterError
from evo_agent.adapter_client.types import (
    AlreadyApplied,
    ManagedDocSnapshot,
    TaskState,
    UpdateStarted,
)
from evo_agent.cancellation import CancellationToken
from evo_agent.errors import ManagedDocApplyError


def _hash(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


@dataclass
class FakeAdapterClient:
    """脚本化 AdapterClient：记录调用 + 按队列返回预设响应/异常。"""

    doc_kind: str = "agent_rule"
    # POST update 队列（每次 start_managed_doc_update_sync 弹一个）
    post_responses: list[Any] = field(default_factory=list)
    # GET task 队列（每次 get_managed_doc_task_sync 弹一个）
    task_responses: list[Any] = field(default_factory=list)
    # GET snapshot 队列
    snapshot_responses: list[Any] = field(default_factory=list)

    # 调用计数
    post_calls: int = 0
    task_calls: int = 0
    snapshot_calls: int = 0
    # 记录传给 GET 的 request_timeout
    task_timeouts: list[float] = field(default_factory=list)
    post_timeouts: list[float] = field(default_factory=list)

    def start_managed_doc_update_sync(
        self, kind: str, content: str, *, request_timeout: float | None = None
    ) -> Any:
        self.post_calls += 1
        self.post_timeouts.append(request_timeout or 0.0)
        resp = self.post_responses.pop(0)
        if isinstance(resp, Exception):
            raise resp
        return resp

    def get_managed_doc_task_sync(
        self, task_id: str, *, request_timeout: float | None = None
    ) -> Any:
        self.task_calls += 1
        self.task_timeouts.append(request_timeout or 0.0)
        resp = self.task_responses.pop(0)
        if isinstance(resp, Exception):
            raise resp
        return resp

    def get_managed_doc_sync(self, kind: str, *, request_timeout: float | None = None) -> Any:
        self.snapshot_calls += 1
        resp = self.snapshot_responses.pop(0)
        if isinstance(resp, Exception):
            raise resp
        return resp


def _task(status: str, revision: str | None = None, **over: Any) -> TaskState:
    base: dict[str, Any] = dict(
        status=status,
        task_id="task-1",
        revision=revision,
        pending_apply=False,
        last_error=None,
        attempts=1,
        down_seen=False,
        created_at="t",
        updated_at="t",
    )
    base.update(over)
    return TaskState(**base)  # type: ignore[arg-type]


def _snap(content_hash: str, **over: Any) -> ManagedDocSnapshot:
    base: dict[str, Any] = dict(
        content="c",
        file_revision=content_hash,
        applied_revision=content_hash,
        pending_apply=False,
        apply_mode="restart",
        max_task_seconds=60.0,
    )
    base.update(over)
    return ManagedDocSnapshot(**base)  # type: ignore[arg-type]


# ── 1. SUCCEEDED ──


def test_applier_succeeded_updates_hash_and_returns_task_id() -> None:
    """202 + 轮询 SUCCEEDED(revision=期望hash) → AppliedDocument(noop=False)，hash 更新。"""
    content = "# new rule"
    h = _hash(content)
    client = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="task-1")],
        task_responses=[_task("SUCCEEDED", revision=h)],
    )
    applier = ManagedDocApplier(
        adapter_client=client, doc_kind="agent_rule", poll_interval=2.0, deadline=600.0
    )
    doc = applier.apply_and_wait(content)
    assert isinstance(doc, AppliedDocument)
    assert doc.noop is False
    assert doc.task_id == "task-1"
    assert doc.final_status == "SUCCEEDED"
    assert applier.last_success_hash == h


def test_snapshot_recovers_apply_after_post_response_loss_without_receipt() -> None:
    content = "# baseline restore"
    revision = _hash(content)
    client = FakeAdapterClient(
        post_responses=[httpx.ReadError("response lost")],
        snapshot_responses=[
            _snap("old", pending_apply=True),
            _snap(revision),
        ],
    )
    applier = ManagedDocApplier(
        adapter_client=client,  # type: ignore[arg-type]
        doc_kind="agent_rule",
        sleeper=lambda _: None,
    )

    result = applier.apply_and_wait(content)

    assert result.recovered is True
    assert result.task_id is None
    assert client.post_calls == 1
    assert client.snapshot_calls == 2


def test_cancellation_during_apply_emits_waiting_but_polls_to_terminal() -> None:
    content = "# candidate"
    revision = _hash(content)
    token = CancellationToken()
    token.request()
    phases: list[str] = []
    client = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="task-1")],
        task_responses=[_task("RUNNING"), _task("SUCCEEDED", revision=revision)],
    )
    applier = ManagedDocApplier(
        adapter_client=client,  # type: ignore[arg-type]
        doc_kind="agent_rule",
        cancellation_token=token,
        phase_callback=lambda _event, data: phases.append(data["phase"]),
        sleeper=lambda _: None,
    )

    result = applier.apply_and_wait(content)

    assert result.final_status == "SUCCEEDED"
    assert client.task_calls == 2
    assert phases == ["cancel_waiting_inflight"]


# ── 2. AlreadyApplied 200 confirmed noop ──


def test_applier_update_200_already_applied_returns_noop() -> None:
    """POST 200 AlreadyApplied + snapshot 确认 (pending=false, revs=期望) → noop。"""
    content = "# rule"
    h = _hash(content)
    client = FakeAdapterClient(
        post_responses=[AlreadyApplied(revision=h)],
        snapshot_responses=[_snap(h)],
    )
    applier = ManagedDocApplier(adapter_client=client, doc_kind="agent_rule")
    doc = applier.apply_and_wait(content)
    assert doc.noop is True
    assert doc.task_id is None
    assert applier.last_success_hash == h


# ── 3. AlreadyApplied diverged snapshot fails ──


def test_applier_update_200_with_diverged_snapshot_fails_without_updating_hash() -> None:
    """POST 200 但 snapshot pending 或 revision 不匹配 → ManagedDocApplyError，hash 不更新。"""
    content = "# rule"
    h = _hash(content)
    client = FakeAdapterClient(
        post_responses=[AlreadyApplied(revision="other")],
        snapshot_responses=[_snap(h, pending_apply=True, applied_revision="other")],
    )
    applier = ManagedDocApplier(
        adapter_client=client, doc_kind="agent_rule", last_success_hash="prev"
    )
    with pytest.raises(ManagedDocApplyError):
        applier.apply_and_wait(content)
    assert applier.last_success_hash == "prev"  # 未更新


# ── 4. FAILED ──


def test_applier_failed_raises_managed_doc_apply_error() -> None:
    """轮询 FAILED → ManagedDocApplyError。"""
    content = "# rule"
    h = _hash(content)
    client = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="task-1")],
        task_responses=[_task("FAILED", revision=h, last_error="restart timeout")],
    )
    applier = ManagedDocApplier(adapter_client=client, doc_kind="agent_rule")
    with pytest.raises(ManagedDocApplyError):
        applier.apply_and_wait(content)


# ── 5. unknown terminal ──


def test_applier_unknown_terminal_state_raises() -> None:
    """task status 不在四态 → 未知终态 → ManagedDocApplyError。"""
    content = "# rule"
    h = _hash(content)
    client = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="task-1")],
        task_responses=[_task("WEIRD", revision=h)],
    )
    applier = ManagedDocApplier(adapter_client=client, doc_kind="agent_rule")
    with pytest.raises(ManagedDocApplyError):
        applier.apply_and_wait(content)


# ── 6. deadline caps request_timeout and sleep ──


def test_applier_deadline_caps_each_request_timeout_and_sleep() -> None:
    """小 deadline：每次 GET request_timeout ≤ remaining；sleep 不越过 deadline。"""
    content = "# rule"
    h = _hash(content)
    sleeps: list[float] = []
    client = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="task-1")],
        task_responses=[_task("RUNNING"), _task("SUCCEEDED", revision=h)],
    )
    applier = ManagedDocApplier(
        adapter_client=client,
        doc_kind="agent_rule",
        poll_interval=2.0,
        deadline=5.0,
        sleeper=lambda s: sleeps.append(s),
    )
    applier.apply_and_wait(content)
    # 每次 GET 的 request_timeout 都 ≤ deadline（5.0）
    assert all(t <= 5.0 for t in client.task_timeouts)
    # sleep 都 ≤ 剩余预算（≤ 5.0）
    assert all(s <= 5.0 for s in sleeps)


# ── 7. POST not retried on response loss ──


def test_applier_post_not_retried_on_response_loss() -> None:
    """POST transport 错误只读 snapshot 确认，绝不重发 POST。"""
    content = "# rule"
    client = FakeAdapterClient(
        post_responses=[httpx.ConnectError("connection lost")],
        snapshot_responses=[_snap("old")],
    )
    applier = ManagedDocApplier(adapter_client=client, doc_kind="agent_rule")
    with pytest.raises(ManagedDocApplyError):
        applier.apply_and_wait(content)
    assert client.post_calls == 1
    assert client.snapshot_calls == 1


@pytest.mark.parametrize(
    "snapshot_responses",
    [
        [_snap("old", pending_apply=True), _snap("old", pending_apply=True)],
        [httpx.ConnectError("unreachable"), httpx.ConnectError("still unreachable")],
    ],
    ids=["pending-until-timeout", "unreachable-until-timeout"],
)
def test_response_loss_confirmation_timeout_never_reposts(
    snapshot_responses: list[Any],
) -> None:
    ticks = [0.0]

    def advance(seconds: float) -> None:
        ticks[0] += seconds

    client = FakeAdapterClient(
        post_responses=[httpx.ReadError("response lost")],
        snapshot_responses=snapshot_responses,
    )
    applier = ManagedDocApplier(
        adapter_client=client,
        doc_kind="agent_rule",
        deadline=2.0,
        poll_interval=1.0,
        clock=lambda: ticks[0],
        sleeper=advance,
    )

    with pytest.raises(ManagedDocApplyError) as exc_info:
        applier.apply_and_wait("# rule")

    assert exc_info.value.phase == "deadline"
    assert client.post_calls == 1
    assert client.snapshot_calls == 2


# ── 8. GET transient error continues polling within deadline ──


def test_applier_get_transient_error_continues_polling_within_deadline() -> None:
    """GET 临时错误（TransportError/502）→ 不立即失败，继续轮询至 SUCCEEDED。"""
    content = "# rule"
    h = _hash(content)
    client = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="task-1")],
        task_responses=[
            httpx.ConnectError("transient"),
            AdapterError("502", status_code=502),
            _task("SUCCEEDED", revision=h),
        ],
    )
    applier = ManagedDocApplier(adapter_client=client, doc_kind="agent_rule", poll_interval=0.0)
    doc = applier.apply_and_wait(content)
    assert doc.noop is False
    assert doc.final_status == "SUCCEEDED"
    assert client.task_calls == 3


# ── 9. TASK_NOT_FOUND recovered ──


def test_applier_task_not_found_with_fully_matching_snapshot_recovers() -> None:
    """GET 404 TASK_NOT_FOUND + snapshot 全匹配 → recovered success。"""
    content = "# rule"
    h = _hash(content)
    client = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="task-1")],
        task_responses=[AdapterError("task not found", status_code=404)],
        snapshot_responses=[_snap(h)],
    )
    applier = ManagedDocApplier(adapter_client=client, doc_kind="agent_rule")
    doc = applier.apply_and_wait(content)
    assert doc.recovered is True
    assert applier.last_success_hash == h


# ── 10. TASK_NOT_FOUND while pending fails without repost ──


def test_applier_task_not_found_while_pending_fails_without_repost() -> None:
    """GET 404 + snapshot pending/不匹配 → fail-fast，不重发 POST。"""
    content = "# rule"
    h = _hash(content)
    client = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="task-1")],
        task_responses=[AdapterError("task not found", status_code=404)],
        snapshot_responses=[_snap(h, pending_apply=True, applied_revision="other")],
    )
    applier = ManagedDocApplier(adapter_client=client, doc_kind="agent_rule")
    with pytest.raises(ManagedDocApplyError):
        applier.apply_and_wait(content)
    assert client.post_calls == 1  # 没有重发


# ── 11. same content hash noop without POST ──


def test_applier_same_content_hash_returns_noop_without_post() -> None:
    """content hash == last_success_hash → noop，不调 POST。"""
    content = "# rule"
    h = _hash(content)
    client = FakeAdapterClient()
    applier = ManagedDocApplier(adapter_client=client, doc_kind="agent_rule", last_success_hash=h)
    doc = applier.apply_and_wait(content)
    assert doc.noop is True
    assert doc.task_id is None
    assert client.post_calls == 0


# ── 12. poll_interval 2s between GETs ──


def test_applier_poll_interval_2s_between_gets() -> None:
    """PENDING → sleep 2.0 → SUCCEEDED：sleeper 调 2.0 一次。"""
    content = "# rule"
    h = _hash(content)
    sleeps: list[float] = []
    client = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="task-1")],
        task_responses=[_task("RUNNING"), _task("SUCCEEDED", revision=h)],
    )
    applier = ManagedDocApplier(
        adapter_client=client,
        doc_kind="agent_rule",
        poll_interval=2.0,
        sleeper=lambda s: sleeps.append(s),
    )
    applier.apply_and_wait(content)
    assert sleeps == [2.0]


def test_applier_poll_time_includes_sleep_durations() -> None:
    """P2#7：poll_time 含 sleep 耗时，不再只计网络段（低估墙钟）。"""
    content = "# rule"
    h = _hash(content)
    ticks = [0.0]
    sleeps: list[float] = []

    def fake_clock() -> float:
        return ticks[0]

    def fake_sleeper(d: float) -> None:
        sleeps.append(d)
        ticks[0] += d  # sleep 推进时钟，网络段不推进

    client = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="task-1")],
        task_responses=[_task("RUNNING"), _task("SUCCEEDED", revision=h)],
    )
    applier = ManagedDocApplier(
        adapter_client=client,
        doc_kind="agent_rule",
        poll_interval=2.0,
        clock=fake_clock,
        sleeper=fake_sleeper,
    )
    applier.apply_and_wait(content)
    assert sleeps == [2.0]
    # fake clock 仅被 sleep 推进 → poll_time 全部来自 sleep（2.0），网络段为 0
    assert applier.records[-1].poll_time == 2.0


# ── 13. records success/failure/noop ──


def test_applier_records_success_failure_and_noop() -> None:
    """每次成功/失败/no-op 都追加一条 apply record。"""
    content = "# rule"
    h = _hash(content)
    # 1) noop（hash 命中）
    client1 = FakeAdapterClient()
    applier = ManagedDocApplier(adapter_client=client1, doc_kind="agent_rule", last_success_hash=h)
    applier.apply_and_wait(content)
    # 2) success
    client2 = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="t")],
        task_responses=[_task("SUCCEEDED", revision=h)],
    )
    applier2 = ManagedDocApplier(adapter_client=client2, doc_kind="agent_rule")
    applier2.apply_and_wait(content)
    # 3) failure
    client3 = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="t")],
        task_responses=[_task("FAILED", last_error="boom")],
    )
    applier3 = ManagedDocApplier(adapter_client=client3, doc_kind="agent_rule")
    with pytest.raises(ManagedDocApplyError):
        applier3.apply_and_wait(content)

    noop_rec = applier.records[-1]
    assert noop_rec.noop is True
    success_rec = applier2.records[-1]
    assert success_rec.noop is False
    assert success_rec.status == "SUCCEEDED"
    fail_rec = applier3.records[-1]
    assert fail_rec.error is not None
    assert "FAILED" in (fail_rec.status or "") or fail_rec.error != ""


def test_records_returns_immutable_tuple() -> None:
    """records 返回不可变 tuple 快照。"""
    content = "# rule"
    h = _hash(content)
    client = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="t")],
        task_responses=[_task("SUCCEEDED", revision=h)],
    )
    applier = ManagedDocApplier(adapter_client=client, doc_kind="agent_rule")
    applier.apply_and_wait(content)
    recs = applier.records
    assert isinstance(recs, tuple)
    assert isinstance(recs[0], ManagedDocApplyRecord)


def test_applier_no_asyncio_run_in_source() -> None:
    """Applier 全程同步：源码不得调用 asyncio.run / asyncio.to_thread（AST 检查
    调用点，允许 docstring 提及）。"""
    import ast
    import inspect

    from evo_agent.adapter_client import applier as applier_mod

    tree = ast.parse(inspect.getsource(applier_mod))
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            func = node.func
            if isinstance(func, ast.Attribute) and isinstance(func.value, ast.Name):
                if func.value.id == "asyncio" and func.attr in {"run", "to_thread"}:
                    pytest.fail(f"applier 禁止 asyncio.{func.attr} 调用")


# ── 14. schema 失败(status=200) 经 applier 转 fatal（不静默 retry）──


def test_applier_poll_schema_failure_200_is_fatal_not_silent() -> None:
    """client 层把 schema 失败(malformed/缺字段 2xx)包成 AdapterError(status_code=200)；

    applier poll 分支走「其他 4xx/schema → _fail」转 ManagedDocApplyError(fatal)，
    不静默 retry，records 留 failed_poll 条目（不静默吞 → 训练继续但 remote 未确认）。
    """
    content = "# rule v2"
    client = FakeAdapterClient(
        post_responses=[UpdateStarted(task_id="task-1")],
        # poll 首个响应 = client 层转出的 schema 失败 AdapterError(200)
        task_responses=[AdapterError("missing field in task state", status_code=200)],
    )
    applier = ManagedDocApplier(adapter_client=client, doc_kind="agent_rule", deadline=60.0)
    with pytest.raises(ManagedDocApplyError) as exc:
        applier.apply_and_wait(content)
    assert exc.value.phase == "failed_poll"
    assert any(r.phase == "failed_poll" for r in applier.records)
