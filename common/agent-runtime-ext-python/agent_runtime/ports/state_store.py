# coding: utf-8

"""状态存储端口：命名空间化的键值读写，以及它产出的记录值对象。

## 为什么 runtime 要有这一层

存量把 Task 快照与框架状态写在「缓存 + 数据库」两层上：读优先命中缓存，
未命中回源数据库并回填。**对外兼容要求替换件具备同一行为**——
权威特性表只列了缓存接入抽象、没有数据库层，但存量有该行为，替换件就要有
（用户 2026-08-21 裁定：数据库那一族属存量兼容缺口，不是超范围）。

## 边界

本端口只管「按命名空间与键存取一个业务字典」。它**不解释载荷**：
载荷对 runtime 不透明，键格式与序列化格式不对外承诺稳定。
过期由写入驱动——每次写入重设，不单独提供续期方法。
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any, Optional, Protocol, runtime_checkable


@dataclass
class StateRecord:
    """一条状态记录。

    **字段名是对外契约的一部分**：存量的消费方按这些名字取值，改名即破兼容。
    """

    namespace: str
    key: str
    value: dict[str, Any]
    version: int = 1
    ttl_seconds: Optional[int] = None
    metadata: Optional[dict[str, Any]] = None
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None


@runtime_checkable
class StateStorePort(Protocol):
    """状态存储的读写面。三个方法，不多不少。

    **不提供「单独设过期」**：该方法原用于活跃态滑动续期，而续期已改由写入驱动、
    每次写入重设，无消费方。上游端口有它，本实现有意不跟进。

    **不提供「比较并写入」**：在整值序列化的载体上做字节比较不成立
    （序列化非规范化会产生假失败），且只覆盖 runtime 一层。
    """

    async def write(
        self,
        namespace: str,
        key: str,
        value: dict[str, Any],
        ttl_seconds: Optional[int] = None,
        metadata: Optional[dict[str, Any]] = None,
    ) -> None:
        """写入。同一命名空间与键的重复写入等价于更新，版本自增。"""
        ...

    async def read(self, namespace: str, key: str) -> Optional[StateRecord]:
        """读取。不存在返回空，不抛异常。"""
        ...

    async def remove(self, namespace: str, key: str) -> None:
        """删除。删不存在的键不抛异常。"""
        ...
