# coding: utf-8

"""三类来源各自的路由判定。

## 判定表

| 来源 | 条件 | 目标 |
|---|---|---|
| 请求方 | 取不到任务，或任务不在挂起集合里 | 本地智能体（首轮或已完成） |
| 请求方 | 挂起 · 未指定任务 · 有远端任务标识且那条远端任务在 | 远端智能体（取其来源） |
| 请求方 | 挂起 · 未指定任务 · 找不到远端 | 本地智能体 |
| 请求方 | 挂起 · 指定任务 · 来源在本地集合内 | 本地智能体 |
| 请求方 | 挂起 · 指定任务 · 其余 | 远端智能体（按级联路径定下一跳） |
| 本地智能体 | 事件类型在委托集合里 | 远端智能体（事件未带目标则用默认远端） |
| 本地智能体 | 其余 | 请求方 |
| 远端智能体 | 帧类型在终态集合里 | 本地智能体 |
| 远端智能体 | 其余（数据帧与非终态控制帧） | 请求方 |

**顺序即语义**：先判「是不是续轮」，再判「指没指定任务」，最后才走级联查找。
颠倒之后首轮请求会去做一次无谓的级联下探。
"""
from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from typing import Optional

from agent_runtime.application.legacy_routing.events import (
    NormalizedEvent,
    RouteContext,
    RouteTarget,
)
from agent_runtime.application.legacy_routing.profiles import (
    LocalAgentSourceProfile,
    RemoteAgentSourceProfile,
    RequesterSourceProfile,
)
from agent_runtime.application.legacy_routing.task_state import (
    META_KEY_REMOTE_TASK_ID,
    META_KEY_SOURCE_AGENT,
    META_KEY_SUB_TASKS,
    TaskStateManager,
)

_logger = logging.getLogger(__name__)

#: 级联查找的默认深度上限。**必须有上限**：任务树可以成环
#: （甲派给乙、乙又派回甲），无上限时查找不返回。
DEFAULT_MAX_CASCADE_DEPTH = 10


class RouteStrategy(ABC):
    """一类来源的判定。"""

    @abstractmethod
    async def route(self, event: NormalizedEvent, context: RouteContext) -> RouteTarget:
        ...


class RequesterSourceStrategy(RouteStrategy):
    """来自请求方：判首轮还是续轮，续轮再判去哪一跳。"""

    def __init__(
        self,
        state_mgr: TaskStateManager,
        profile: RequesterSourceProfile,
        local_agent_keys: Optional[set[str]] = None,
        max_cascade_depth: int = DEFAULT_MAX_CASCADE_DEPTH,
    ) -> None:
        self._state_mgr = state_mgr
        self._profile = profile
        self._suspended_states = set(profile.suspended_states)
        self._local_agent_keys = local_agent_keys or set()
        self._max_cascade_depth = max_cascade_depth

    async def route(self, event: NormalizedEvent, context: RouteContext) -> RouteTarget:
        target_task_id = context.task_id or context.root_task_id
        task = await self._state_mgr.get_task(target_task_id)

        # 首轮，或任务已离开挂起态：交本地智能体。
        if task is None or task.get("status_state") not in self._suspended_states:
            return RouteTarget(type="local_agent")

        # 老版本调用方不传任务标识：只能靠远端任务标识找回远端。
        if not context.is_specify_task:
            remote_task_id = task.get("metadata", {}).get(META_KEY_REMOTE_TASK_ID, "")
            if remote_task_id:
                remote_task = await self._state_mgr.get_task(remote_task_id)
                if remote_task:
                    remote_source = remote_task.get("metadata", {}).get(META_KEY_SOURCE_AGENT, "")
                    return RouteTarget(type="remote_agent", agent_key=remote_source)
            return RouteTarget(type="local_agent")

        source_agent = task.get("metadata", {}).get(META_KEY_SOURCE_AGENT, "")
        if source_agent in self._local_agent_keys:
            return RouteTarget(type="local_agent")

        route_path = await self._resolve_route_path(context)
        return RouteTarget(type="remote_agent", agent_key=self._determine_next_hop(route_path))

    async def _resolve_route_path(self, context: RouteContext) -> list[dict]:
        """从根任务下探到目标任务，产出这条链上每一跳的来源智能体。"""
        root_task = await self._state_mgr.get_task(context.root_task_id)
        if not root_task:
            return []
        path = [
            {
                "task_id": context.root_task_id,
                "source_agent": root_task.get("metadata", {}).get(META_KEY_SOURCE_AGENT, ""),
            }
        ]
        target = context.task_id
        if not target or target == context.root_task_id:
            return path
        return await self._find(root_task, target, path, depth=1)

    async def _find(
        self, parent_task: dict, target_task_id: str, path: list[dict], depth: int = 1
    ) -> list[dict]:
        """按子任务列表逐层下探。**达到深度上限即停**，返回已走到的路径。"""
        if depth > self._max_cascade_depth:
            return path
        sub_task_ids = parent_task.get("metadata", {}).get(META_KEY_SUB_TASKS, [])
        if not sub_task_ids:
            return path
        for sub_id in sub_task_ids:
            sub_task = await self._state_mgr.get_task(sub_id)
            if not sub_task:
                continue
            sub_path = path + [
                {
                    "task_id": sub_id,
                    "source_agent": sub_task.get("metadata", {}).get(META_KEY_SOURCE_AGENT, ""),
                }
            ]
            if sub_id == target_task_id:
                return sub_path
            result = await self._find(sub_task, target_task_id, sub_path, depth=depth + 1)
            if any(node["task_id"] == target_task_id for node in result):
                return result
        return path

    def _determine_next_hop(self, route_path: list[dict]) -> str:
        """下一跳 = 路径上**最后一个本地节点的下一个节点**。

        从尾部往前找本地节点，是因为链路可能是「本地→远端→本地→远端」，
        要的是当前这一段的下一跳，不是最开始那一跳。
        """
        if not route_path:
            return ""
        for i in range(len(route_path) - 1, -1, -1):
            if route_path[i]["source_agent"] in self._local_agent_keys and i + 1 < len(route_path):
                return route_path[i + 1]["source_agent"]
        return route_path[-1].get("source_agent", "")


class LocalAgentSourceStrategy(RouteStrategy):
    """来自本地智能体：委托事件出去，其余回请求方。"""

    def __init__(self, profile: LocalAgentSourceProfile) -> None:
        self._profile = profile
        self._delegate_types = set(profile.delegate_types)

    async def route(self, event: NormalizedEvent, context: RouteContext) -> RouteTarget:
        if event.type in self._delegate_types:
            # 事件数据对本层不透明，取出来的目标标识转成字符串再用。
            agent_key = event.data.get("agent_key") or self._profile.default_remote_agent
            return RouteTarget(type="remote_agent", agent_key=str(agent_key))
        return RouteTarget(type="requester")


class RemoteAgentSourceStrategy(RouteStrategy):
    """来自远端智能体：终态帧回本地收敛，其余直通请求方。"""

    def __init__(self, profile: RemoteAgentSourceProfile) -> None:
        self._profile = profile
        self._terminal_frame_types = set(profile.terminal_frame_types)
        self._frame_type_map = profile.frame_type_map

    async def route(self, event: NormalizedEvent, context: RouteContext) -> RouteTarget:
        if self._classify_frame(event) in self._terminal_frame_types:
            return RouteTarget(type="local_agent")
        return RouteTarget(type="requester")

    def _classify_frame(self, event: NormalizedEvent) -> str:
        """元数据里带了帧类型就用它，否则按事件类型查映射表。"""
        if "frame_type" in event.metadata:
            return str(event.metadata["frame_type"])
        return self._frame_type_map.get(event.type.upper(), self._profile.default_frame_type)
