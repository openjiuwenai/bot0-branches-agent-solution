# coding: utf-8

"""任务态读写门面：把任务存储收窄成编排需要的那几个动作。

## 元数据键是对外契约

三个键（来源智能体、子任务列表、远端任务标识）由存量的消费方按名字取值，
**改名即破兼容**，故在此具名导出而不是散在各处写字面量。

## 无存储时退回进程内

未注入任务存储时读写落在进程内字典上。**这不是降级，是替身档**：
判据要在没有外部依赖的环境里驱动这一层，而行为必须与有存储时一致。
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Optional, Protocol, runtime_checkable

_logger = logging.getLogger(__name__)

#: 来源智能体。**只在创建任务时写入**——续轮不覆盖，否则级联查找会顺着最后一跳回溯。
META_KEY_SOURCE_AGENT = "source_agent"
#: 子任务标识列表，级联查找按它逐层下探。
META_KEY_SUB_TASKS = "sub_tasks"
#: 远端任务标识，未指定任务的续轮靠它找到远端。
META_KEY_REMOTE_TASK_ID = "remote_task_id"

#: 终态集合：已到终态的任务不再被收敛覆盖。
_TERMINAL_STATES = ("COMPLETED", "FAILED")


@runtime_checkable
class TaskCodecLike(Protocol):
    """任务对象的编码器。**协议由消费方定义**——本层只要求「能把字典编成任务对象」。"""

    def to_task(self, task_id: str, task_data: dict[str, Any]) -> object:
        ...

    def from_task(self, task: object) -> dict[str, Any]:
        ...


@runtime_checkable
class TaskStoreLike(Protocol):
    """任务存储。**协议由消费方定义**——这一层只用得到两个动作。"""

    async def get(self, task_id: str, call_context: object = None) -> object:
        ...

    async def save(self, task: object, call_context: object = None) -> None:
        ...


@dataclass(frozen=True)
class InputRequiredState:
    """等待客户端输入时要落盘的那组事实。

    **合成一个值对象而不是散成形参**：这几项要么一起写、要么都不写，
    散成形参时调用方漏传一个不会报错，而漏的那项要等续轮找不到远端才现形。
    """

    task_id: str
    call_context: object
    remote_task_id: str = ""
    workflow_id: Optional[str] = None
    sub_tasks: Optional[list[str]] = None


class TaskStateManager:
    """任务态的读写门面。"""

    def __init__(
        self,
        task_store: Optional[TaskStoreLike] = None,
        codec: Optional[TaskCodecLike] = None,
    ) -> None:
        self._task_store = task_store
        self._codec = codec
        self._tasks: dict[str, dict[str, Any]] = {}

    async def get_task(
        self, task_id: str, call_context: object = None
    ) -> Optional[dict[str, Any]]:
        """读。不存在返回空，**不抛异常**——续轮判定按「取不到即首轮」处理。"""
        if self._task_store is not None:
            task = await self._task_store.get(task_id, call_context)
            return None if task is None else self._task_to_dict(task)
        return self._tasks.get(task_id)

    async def save_task(
        self, task_id: str, task_data: dict[str, Any], call_context: object = None
    ) -> None:
        """写。**注入了任务存储时不再往进程内留一份**。

        存量在此处无条件双写，而读那一侧在有存储时提前返回、根本不看进程内那份——
        于是它成了**只写不读、且永不删除**的一份数据：每来一个任务多一条，
        进程跑多久就攒多久，攒的还是完整任务数据。那是内存无界增长，不是缓存。

        **去掉它不改变对外行为**：那份数据本来就没有读者。故这里不构成
        「对外兼容」与「状态外置」的冲突（根设计的冲突判定第三项：
        领域语义能同时承载两侧要求的，不入冲突台账）。

        没有任务存储时仍写进程内——那时它是唯一存放处，判据环境靠它驱动这一层。
        """
        if self._task_store is not None:
            await self._task_store.save(self._dict_to_task(task_id, task_data), call_context)
            return
        self._tasks[task_id] = task_data

    async def create_task(
        self,
        task_id: str,
        conv_id: str,
        status_state: str = "WORKING",
        call_context: object = None,
        source_agent: Optional[str] = None,
    ) -> dict[str, Any]:
        """建任务并落盘来源智能体。"""
        task_data: dict[str, Any] = {"id": task_id, "status_state": status_state, "metadata": {}}
        if conv_id:
            task_data["context_id"] = conv_id
        if source_agent:
            task_data["metadata"][META_KEY_SOURCE_AGENT] = source_agent
        await self.save_task(task_id, task_data, call_context)
        return task_data

    async def update_task_status(
        self,
        task_id: str,
        status_state: str,
        call_context: object = None,
        metadata_updates: Optional[dict[str, Any]] = None,
    ) -> None:
        """改状态。任务不存在时**静默返回**——与存量一致。"""
        task = await self.get_task(task_id, call_context)
        if task is None:
            return
        task["status_state"] = status_state
        if metadata_updates:
            task.setdefault("metadata", {}).update(metadata_updates)
        await self.save_task(task_id, task, call_context)

    async def save_input_required(
        self,
        state: "InputRequiredState | str",
        call_context: object = None,
        *,
        remote_task_id: str = "",
        workflow_id: Optional[str] = None,
        sub_tasks: Optional[list[str]] = None,
    ) -> None:
        """落盘等待态。**两种调用形态都收**。

        存量当前把这几项合成一个值对象传进来；权威用例按散开的形参调用。
        本实现接受二者——**这是超集，不是签名变更**：值对象那一路逐字未动，
        既有调用方不受影响；散开那一路只是多一个入口。
        二选一会让另一边直接报参数错误，而那不是任何一方的错。

        **不写来源智能体**：它只在创建任务时写入。这里写会把它改成最后一跳，
        而级联查找正是靠它逐层回溯，改了就找不回去。

        **子任务是追加不是覆盖**：同一父任务可以分多轮派出子任务。
        """
        resolved = (
            state
            if isinstance(state, InputRequiredState)
            else InputRequiredState(
                task_id=state,
                call_context=call_context,
                remote_task_id=remote_task_id,
                workflow_id=workflow_id,
                sub_tasks=sub_tasks,
            )
        )
        task = await self.get_task(resolved.task_id, resolved.call_context)
        if not task:
            return
        task["status_state"] = "INPUT_REQUIRED"
        metadata = task.setdefault("metadata", {})
        existing = list(metadata.get(META_KEY_SUB_TASKS, []))
        if resolved.sub_tasks:
            existing.extend(resolved.sub_tasks)
        metadata.update(
            {
                META_KEY_REMOTE_TASK_ID: resolved.remote_task_id or "",
                "workflow_id": resolved.workflow_id or "",
                META_KEY_SUB_TASKS: existing,
            }
        )
        await self.save_task(resolved.task_id, task, resolved.call_context)

    async def add_sub_task(
        self, parent_task_id: str, sub_task_id: str, call_context: object = None
    ) -> None:
        """追加子任务，**去重**——重复派发同一子任务不该在路径上出现两次。"""
        task = await self.get_task(parent_task_id, call_context)
        if not task:
            return
        metadata = task.setdefault("metadata", {})
        sub_tasks = list(metadata.get(META_KEY_SUB_TASKS, []))
        if sub_task_id not in sub_tasks:
            sub_tasks.append(sub_task_id)
        metadata[META_KEY_SUB_TASKS] = sub_tasks
        await self.save_task(parent_task_id, task, call_context)

    async def finalize_completed(self, task_id: str, call_context: object = None) -> None:
        """收敛为完成。**已到终态的不再改**——幂等，重复调用不报错也不回退状态。"""
        task = await self.get_task(task_id, call_context)
        if task and task.get("status_state") not in _TERMINAL_STATES:
            task["status_state"] = "COMPLETED"
            await self.save_task(task_id, task, call_context)

    async def finalize_failed(
        self, task_id: str, call_context: object = None, error_text: str = ""
    ) -> None:
        """收敛为失败，并**清掉远端任务标识**——失败后不该还能按它续轮。"""
        task = await self.get_task(task_id, call_context)
        if not task:
            return
        metadata = task.setdefault("metadata", {})
        metadata[META_KEY_REMOTE_TASK_ID] = ""
        metadata["va_task_id"] = ""
        task["status_state"] = "FAILED"
        #: 错误文本落**顶层字段**、且**空串也写**——与存量逐字一致。
        #: 落元数据或只在非空时写，都会让读它的消费方取不到：
        #: 前者位置不对，后者「没有错误文本」与「字段不存在」变得无法区分。
        task["error"] = error_text
        await self.save_task(task_id, task, call_context)

    def _task_to_dict(self, task: object) -> dict[str, Any]:
        """任务对象 → 字典。**投射交给编码器**——

        任务对象的字段名是存量的对外词表，对外词表属适配层
        （根设计的分层裁定）。没有编码器时只认字典形态的任务。
        """
        if isinstance(task, dict):
            return task
        if self._codec is None:
            return {}
        return self._codec.from_task(task)

    def _dict_to_task(self, task_id: str, task_data: dict[str, Any]) -> object:
        """字典 → 任务对象。

        **编码器可注入，未注入时回传字典**：协议类型不进应用层（洋葱分层约束），
        编码归协议适配层；而判据环境里没有协议库也要能驱动这一层，
        任务存储的替身本就收字典。
        """
        if self._codec is None:
            return {**task_data, "id": task_id}
        return self._codec.to_task(task_id, task_data)


