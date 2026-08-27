# coding: utf-8

"""数据库处理器端口：状态存储落库时依赖的四个动作。

**为什么不直接依赖某个数据库客户端**：状态存储的行为（版本自增、冲突转更新）
是领域规则，与用哪个数据库无关。把数据库操作收成四个动作，
换后端只换实现，规则不动。

**冲突语义是契约的一部分**：`create` 在主键冲突时必须抛完整性错误，
状态存储据此转更新路径。抛别的异常类型会让「第二次写入走更新」这条行为失效。
"""
from __future__ import annotations

from typing import Any, Optional, Protocol, runtime_checkable


@runtime_checkable
class RowLike(Protocol):
    """数据库行。只要能转成字典即可，不约束具体类型。"""

    def to_dict(self) -> dict[str, Any]:
        ...


@runtime_checkable
class DbHandlerPort(Protocol):
    """数据库处理器。四个动作，与存量 `_DbHandlerLike` 逐字对齐。"""

    async def get(self, table_name: str, filters: dict[str, Any]) -> Optional[RowLike]:
        ...

    async def create(self, table_name: str, data: dict[str, Any]) -> object:
        """插入。主键冲突时**必须抛完整性错误**——见模块说明。

        **返回值标注为 `object` 而非 `Any`**：驱动会返回一个结果对象，
        但调用方不看它。用 `Any` 会连带关掉调用点的类型检查，
        用 `object` 表达的是「有个值、但对本层不透明」。
        """
        ...

    async def update(
        self, table_name: str, filters: dict[str, Any], data: dict[str, Any]
    ) -> object:
        ...

    async def delete(self, table_name: str, filters: dict[str, Any]) -> bool:
        ...
