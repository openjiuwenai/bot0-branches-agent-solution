"""adapter_client transport DTO 测试（ManagedDocSnapshot / UpdateStarted /
AlreadyApplied / TaskState）。

DTO 全部 @dataclass(frozen=True)；序列字段用 tuple，避免 frozen 外壳中保留
可变 list。status 原样使用 Adapter 四态 PENDING|RUNNING|SUCCEEDED|FAILED，
不伪造第二套词汇。
"""

from __future__ import annotations

import dataclasses
from typing import get_args, get_origin, get_type_hints

import pytest

from evo_agent.adapter_client import types as ac_types
from evo_agent.adapter_client.types import (
    AlreadyApplied,
    ManagedDocSnapshot,
    ManagedDocUpdateResult,
    TaskState,
    UpdateStarted,
)


class TestManagedDocSnapshot:
    def _make(self, **over: object) -> ManagedDocSnapshot:
        defaults: dict[str, object] = dict(
            content="# rule content",
            file_revision="rev-1",
            applied_revision="rev-1",
            pending_apply=False,
            apply_mode="restart",
            max_task_seconds=120.0,
        )
        defaults.update(over)
        return ManagedDocSnapshot(**defaults)  # type: ignore[arg-type]

    def test_frozen_dataclass(self) -> None:
        assert dataclasses.is_dataclass(ManagedDocSnapshot)
        assert getattr(ManagedDocSnapshot, "__dataclass_params__").frozen is True

    def test_fields_populated(self) -> None:
        snap = self._make()
        assert snap.content == "# rule content"
        assert snap.file_revision == "rev-1"
        assert snap.applied_revision == "rev-1"
        assert snap.pending_apply is False
        assert snap.apply_mode == "restart"
        assert snap.max_task_seconds == 120.0

    def test_immutable(self) -> None:
        snap = self._make()
        with pytest.raises(dataclasses.FrozenInstanceError):
            snap.content = "mutated"  # type: ignore[misc]

    def test_revisions_nullable(self) -> None:
        """文档尚未 apply 时 revision 可为 None。"""
        snap = self._make(file_revision=None, applied_revision=None, pending_apply=True)
        assert snap.file_revision is None
        assert snap.applied_revision is None
        assert snap.pending_apply is True


class TestUpdateStarted:
    def test_frozen_dataclass(self) -> None:
        assert dataclasses.is_dataclass(UpdateStarted)
        assert getattr(UpdateStarted, "__dataclass_params__").frozen is True

    def test_fields(self) -> None:
        r = UpdateStarted(task_id="task-1")
        assert r.task_id == "task-1"

    def test_immutable(self) -> None:
        r = UpdateStarted(task_id="task-1")
        with pytest.raises(dataclasses.FrozenInstanceError):
            r.task_id = "task-2"  # type: ignore[misc]


class TestAlreadyApplied:
    def test_frozen_dataclass(self) -> None:
        assert dataclasses.is_dataclass(AlreadyApplied)
        assert getattr(AlreadyApplied, "__dataclass_params__").frozen is True

    def test_fields(self) -> None:
        r = AlreadyApplied(revision="rev-9")
        assert r.revision == "rev-9"


class TestManagedDocUpdateResult:
    def test_is_union_of_two(self) -> None:
        """ManagedDocUpdateResult 是 UpdateStarted | AlreadyApplied 联合别名。"""
        assert get_origin(ManagedDocUpdateResult) is not None
        args = set(get_args(ManagedDocUpdateResult))
        assert UpdateStarted in args
        assert AlreadyApplied in args


class TestTaskState:
    def _make(self, **over: object) -> TaskState:
        defaults: dict[str, object] = dict(
            status="RUNNING",
            task_id="task-1",
            revision=None,
            pending_apply=True,
            last_error=None,
            attempts=1,
            down_seen=False,
            created_at="2026-07-13T00:00:00Z",
            updated_at="2026-07-13T00:00:01Z",
        )
        defaults.update(over)
        return TaskState(**defaults)  # type: ignore[arg-type]

    def test_frozen_dataclass(self) -> None:
        assert dataclasses.is_dataclass(TaskState)
        assert getattr(TaskState, "__dataclass_params__").frozen is True

    def test_fields_populated(self) -> None:
        ts = self._make()
        assert ts.status == "RUNNING"
        assert ts.task_id == "task-1"
        assert ts.revision is None
        assert ts.pending_apply is True
        assert ts.last_error is None
        assert ts.attempts == 1
        assert ts.down_seen is False
        assert ts.created_at == "2026-07-13T00:00:00Z"
        assert ts.updated_at == "2026-07-13T00:00:01Z"

    def test_status_literal_four_states(self) -> None:
        """status 用 Literal 四态 PENDING|RUNNING|SUCCEEDED|FAILED。"""
        hints = get_type_hints(TaskState)
        allowed = set(get_args(hints["status"]))
        assert allowed == {"PENDING", "RUNNING", "SUCCEEDED", "FAILED"}

    @pytest.mark.parametrize("status", ["PENDING", "RUNNING", "SUCCEEDED", "FAILED"])
    def test_each_state_constructs(self, status: str) -> None:
        ts = self._make(status=status)
        assert ts.status == status

    def test_immutable(self) -> None:
        ts = self._make()
        with pytest.raises(dataclasses.FrozenInstanceError):
            ts.status = "FAILED"  # type: ignore[misc]


def test_module_exports_dto_names() -> None:
    """模块导出四个 DTO 名 + 联合别名，供 client/applier 导入。"""
    for name in (
        "ManagedDocSnapshot",
        "UpdateStarted",
        "AlreadyApplied",
        "ManagedDocUpdateResult",
        "TaskState",
    ):
        assert hasattr(ac_types, name), f"adapter_client.types 缺少导出 {name}"
