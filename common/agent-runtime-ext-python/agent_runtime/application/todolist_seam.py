# coding: utf-8

"""DeepAgent Todolist task-scoped 存储接缝（FEAT-003 §4.3）。

runtime 只提供 task-scoped 的 **opaque payload** 存储接缝：Task 下发/挂起边界写入、恢复回灌；
执行期由 Core/DeepAgent 自治 save/load，复用同一 RuntimeRedisClient 连接。**runtime 不理解、不合并、
不校验 TodoItem 领域模型**（payload 对 runtime 不透明，序列化/语义归 Core/adapter）。

key = 部署方 keyPrefix + taskId（任务级隔离，MUST：不得仅以 tenant/agent/conversation/session
为隔离粒度）。分布式场景不回退本地文件存储（经 RuntimeRedisClient 端口）。
"""
from __future__ import annotations

from typing import Optional

from agent_runtime.ports.cache import RuntimeRedisClient

#: 默认键命名空间。仅为兜底——key 的权威来源是部署方经 `key_prefix` 注入（见模块 docstring）。
#: 取协议/框架中立名：原值把宿主框架名写进了内层，而内层不该知道宿主叫什么。
DEFAULT_TODOLIST_KEY_PREFIX = "runtime:todolist:"


class TodolistSeam:
    """Todolist opaque payload 存储接缝（消费 RuntimeRedisClient 的具名外部治理写入）。

    **上游对标范围内尚未实现该能力**——`openJiuwen/agent-runtime-java` 与
    `openJiuwen/agent-solution` 的 runtime 扩展模块中均无存储注入的实现。本接缝是我方
    多走的一步，依据是权威规格把该能力列为 MUST；**等上游追认**，其形态可能因上游落地
    而调整。同类先例见数据架构视图关键决策的「有界重试保留」一条。
    """

    def __init__(
        self, cache: RuntimeRedisClient, *,
        key_prefix: str = DEFAULT_TODOLIST_KEY_PREFIX,
    ) -> None:
        self._cache = cache
        self._prefix = key_prefix

    def _key(self, task_id: str) -> str:
        return f"{self._prefix}{task_id}"

    async def save(self, task_id: str, payload: bytes) -> None:
        """写入 task-scoped opaque payload（下发/挂起边界；payload 由 Core 序列化，runtime 不解释）。

        **走端口的具名外部治理写入，不带过期**：权威明写 Todolist 存储不自行设置过期、
        不调用 `setex` 或 `expire`，键的回收由外部 Redis 连接池配置或运维侧统一管理。
        此处若改用通用写入（强制带过期），就是给权威划归外部治理的键擅自设了回收时限。
        """
        await self._cache.write_externally_governed(self._key(task_id), payload)

    async def load(self, task_id: str) -> Optional[bytes]:
        """恢复回灌：读回 opaque payload（无则 None，由 Core 决定语义）。"""
        return await self._cache.get(self._key(task_id))

    async def clear(self, task_id: str) -> None:
        await self._cache.delete(self._key(task_id))
