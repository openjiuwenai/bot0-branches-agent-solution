"""Unit tests for TaskRegistry + TaskState (T6)."""

from datetime import datetime, timedelta, timezone

import pytest

from agent_adapter.managed_doc.storage import DocStorageError
from agent_adapter.managed_doc.task import (
    TaskNotFoundError,
    TaskRegistry,
    TaskStatus,
)


class FakeClock:
    """Controllable clock to test TTL expiry without freezegun."""

    def __init__(self) -> None:
        self._t = datetime(2025, 1, 1, tzinfo=timezone.utc)

    def now(self) -> datetime:
        return self._t

    def advance(self, seconds: float) -> None:
        self._t = self._t + timedelta(seconds=seconds)


# ── AC6.1 create → PENDING；get 返回快照 ─────────────────────────────


def test_create_returns_pending() -> None:
    reg = TaskRegistry(ttl_seconds=600)
    state = reg.create(doc_kind="agent_rule", action="update")
    assert state.task_id.startswith("t_")
    assert state.status is TaskStatus.PENDING
    assert state.doc_kind == "agent_rule"
    assert state.action == "update"
    assert state.attempts == 0
    assert state.pending_apply is False
    assert state.created_at == state.updated_at


def test_get_returns_snapshot() -> None:
    reg = TaskRegistry(ttl_seconds=600)
    state = reg.create(doc_kind="agent_rule", action="update")
    got = reg.get(state.task_id)
    assert got.task_id == state.task_id
    assert got.status is TaskStatus.PENDING


def test_get_unknown_raises() -> None:
    reg = TaskRegistry(ttl_seconds=600)
    with pytest.raises(TaskNotFoundError):
        reg.get("t_missing")


def test_create_unique_ids() -> None:
    reg = TaskRegistry(ttl_seconds=600)
    a = reg.create(doc_kind="agent_rule", action="update")
    b = reg.create(doc_kind="agent_rule", action="update")
    assert a.task_id != b.task_id


# ── update 状态流转 ──────────────────────────────────────────────────


def test_update_transitions_running_then_succeeded() -> None:
    reg = TaskRegistry(ttl_seconds=600)
    state = reg.create(doc_kind="agent_rule", action="update")
    reg.update(state.task_id, status=TaskStatus.RUNNING, attempts=1, down_seen=True)
    running = reg.get(state.task_id)
    assert running.status is TaskStatus.RUNNING
    assert running.attempts == 1
    assert running.down_seen is True
    assert running.updated_at >= running.created_at

    reg.update(state.task_id, status=TaskStatus.SUCCEEDED, revision="abc", pending_apply=False)
    done = reg.get(state.task_id)
    assert done.status is TaskStatus.SUCCEEDED
    assert done.revision == "abc"
    assert done.pending_apply is False


def test_update_unknown_raises() -> None:
    reg = TaskRegistry(ttl_seconds=600)
    with pytest.raises(TaskNotFoundError):
        reg.update("t_missing", status=TaskStatus.RUNNING)


def test_update_failed_sets_last_error() -> None:
    reg = TaskRegistry(ttl_seconds=600)
    state = reg.create(doc_kind="agent_rule", action="update")
    reg.update(
        state.task_id,
        status=TaskStatus.FAILED,
        last_error="health never turned green",
        pending_apply=True,
    )
    done = reg.get(state.task_id)
    assert done.status is TaskStatus.FAILED
    assert done.last_error == "health never turned green"
    assert done.pending_apply is True


# ── AC6.2 TTL 过期 → 404（时钟注入） ────────────────────────────────


def test_completed_expires_after_ttl() -> None:
    clock = FakeClock()
    reg = TaskRegistry(ttl_seconds=600, now=clock.now)
    state = reg.create(doc_kind="agent_rule", action="update")
    reg.update(state.task_id, status=TaskStatus.SUCCEEDED, revision="abc")

    # 未过期：可读
    assert reg.get(state.task_id).status is TaskStatus.SUCCEEDED

    # 推进超过 TTL → 404
    clock.advance(601)
    with pytest.raises(TaskNotFoundError):
        reg.get(state.task_id)


def test_failed_expires_after_ttl() -> None:
    clock = FakeClock()
    reg = TaskRegistry(ttl_seconds=600, now=clock.now)
    state = reg.create(doc_kind="agent_rule", action="update")
    reg.update(state.task_id, status=TaskStatus.FAILED, last_error="boom")
    clock.advance(601)
    with pytest.raises(TaskNotFoundError):
        reg.get(state.task_id)


def test_pending_does_not_expire() -> None:
    """未完成态（PENDING/RUNNING）不参与 TTL 清理。"""
    clock = FakeClock()
    reg = TaskRegistry(ttl_seconds=600, now=clock.now)
    state = reg.create(doc_kind="agent_rule", action="update")
    clock.advance(10_000)
    # PENDING 不过期
    assert reg.get(state.task_id).status is TaskStatus.PENDING

    reg.update(state.task_id, status=TaskStatus.RUNNING)
    clock.advance(10_000)
    # RUNNING 不过期
    assert reg.get(state.task_id).status is TaskStatus.RUNNING


def test_cleanup_expired_removes_completed() -> None:
    clock = FakeClock()
    reg = TaskRegistry(ttl_seconds=600, now=clock.now)
    s1 = reg.create(doc_kind="agent_rule", action="update")
    s2 = reg.create(doc_kind="agent_rule", action="update")
    reg.update(s1.task_id, status=TaskStatus.SUCCEEDED, revision="a")
    reg.update(s2.task_id, status=TaskStatus.RUNNING)
    clock.advance(601)

    removed = reg.cleanup_expired()
    assert s1.task_id in removed
    assert s2.task_id not in removed  # RUNNING 不清理
    with pytest.raises(TaskNotFoundError):
        reg.get(s1.task_id)
    assert reg.get(s2.task_id).status is TaskStatus.RUNNING


# ── 异常体系 ─────────────────────────────────────────────────────────


def test_task_not_found_is_doc_storage_error() -> None:
    assert issubclass(TaskNotFoundError, DocStorageError)


def test_task_state_fields_present() -> None:
    reg = TaskRegistry(ttl_seconds=600)
    s = reg.create(doc_kind="agent_rule", action="restore")
    # spec §7.3 字段集
    for field in (
        "task_id",
        "status",
        "doc_kind",
        "action",
        "attempts",
        "down_seen",
        "revision",
        "pending_apply",
        "last_error",
        "created_at",
        "updated_at",
    ):
        assert hasattr(s, field)
