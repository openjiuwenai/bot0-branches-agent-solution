# coding: utf-8

"""JSON 面的缓存端口：状态存储的缓存侧依赖它。

**为什么不复用字节面的缓存端口**：状态存储存取的是业务字典，
序列化与反序列化若落在存储实现里，同一份载荷会在两处各写一遍编解码，
而两处一旦不一致，缓存里的东西读不回来。收在端口这一层，编解码只有一处。
"""
from __future__ import annotations

from typing import Optional, Protocol, runtime_checkable


@runtime_checkable
class JsonCachePort(Protocol):
    """与存量 `_CacheStoreLike` 逐字对齐的三个方法。"""

    async def get_json(self, key: str) -> Optional[object]:
        """读。返回任意可序列化值——**形状由调用方判定**，本端口不预设。

        标注为 `object` 而非 `Any`：缓存里存的确实可能是字典、列表或标量，
        但用 `Any` 会关掉调用点的类型检查，而判形状那一步正需要它。
        """
        ...

    async def set_json(self, key: str, value: object, ex: Optional[int] = None) -> None:
        ...

    async def delete(self, *keys: str) -> None:
        ...
