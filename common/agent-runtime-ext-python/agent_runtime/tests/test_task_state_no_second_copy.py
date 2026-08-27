# coding: utf-8
# 判据按上游 ext-java 的同包语义组织：测试与被测件在 Java 侧同包，
# 读内部状态不构成受保护访问；fixture 参数同名是 pytest 的注入约定。
# pylint: disable=protected-access

"""任务态门面不得在有存储时往进程内留第二份。"""
from __future__ import annotations

from typing import Any

import pytest

from agent_runtime.application.legacy_routing.task_state import TaskStateManager


class _RecordingStore:
    """记账用的任务存储替身：只记调用，不做别的。"""

    def __init__(self) -> None:
        self.saved: dict[str, Any] = {}

    async def get(self, task_id: str, call_context: object = None) -> Any:
        return self.saved.get(task_id)

    async def save(self, task: Any, call_context: object = None) -> None:
        #: 没有协议库时门面回传字典，有则回传协议对象——两种形态都要能取到标识。
        task_id = getattr(task, "id", None) or (task or {}).get("id", "")
        self.saved[str(task_id)] = task


@pytest.mark.asyncio
async def test_no_in_process_copy_when_a_store_is_injected() -> None:
    """注入存储后写任务，进程内不得留副本。

    **这条挡的是内存无界增长**：读那一侧在有存储时提前返回、不看进程内那份，
    若写那一侧仍无条件写入，它就成了只写不读且永不删除的一份数据，
    每来一个任务多一条，攒的还是完整任务数据。
    """
    store = _RecordingStore()
    mgr = TaskStateManager(store)
    for i in range(50):
        await mgr.create_task(f"t{i}", "conv", source_agent="A")
    assert len(store.saved) == 50, "任务没落到存储上"
    assert mgr._tasks == {}, f"注入存储后仍在进程内留了 {len(mgr._tasks)} 份副本"


@pytest.mark.asyncio
async def test_in_process_still_works_without_a_store() -> None:
    """没有存储时进程内那份仍是唯一存放处——判据环境靠它驱动这一层。"""
    mgr = TaskStateManager()
    await mgr.create_task("t1", "conv", source_agent="A")
    task = await mgr.get_task("t1")
    assert task is not None and task["status_state"] == "WORKING"
