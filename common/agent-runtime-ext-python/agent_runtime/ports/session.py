# coding: utf-8

"""会话寻址项的存储端口（洋葱内圈，零框架依赖）。

**为什么另立一个端口而不是往 `RuntimeRedisClient` 上加方法**：那个端口是
命令面（`get`/`set`/`setnx` 的 bytes I/O），键的选取与序列化明确不属于它
（见 `cache.py` 首段）。会话快照的读写带着键模板与 JSON 编码，是业务语义，
放进命令面会让两层职责混在一个契约里。

**方法面只有两个，不是把实现的六个方法照抄一遍**：端口声明的是消费方需要
什么，不是实现提供了什么。当前消费方各用一个——入站写侧只写、出站读侧只读。
把实现的全部方法搬上来会让端口对未被消费的形态做出承诺，换实现时凭空多出
四个必须实现的方法。
"""
from __future__ import annotations

from typing import Optional, Protocol, runtime_checkable


@runtime_checkable
class SessionRequestStore(Protocol):
    """会话请求快照的读写面。

    实现落在出站适配层（共享键面那件）；进站写侧与出站读侧都**只依赖本端口**。
    此前两侧的参数类型是裸 `Any`——静态检查看不见这条依赖边，换实现时没有任何
    兜底，方法名写错要到运行期才知道。
    """

    async def get_request(self, conversation_id: str) -> Optional[dict]:
        """读会话请求快照；不存在或无法解析时返回 `None`（由实现决定如何降级）。"""
        ...

    async def put_request_if_absent(
        self, conversation_id: str, snapshot: dict, *, ttl_s: int
    ) -> bool:
        """键不存在时才写；返回是否真的写入。

        **端口层面就要求「不存在才写」这一档**：无条件写入在混部形态下会盖掉
        另一侧刚写的快照。实现须以单命令原子完成，不得拆成先读后写。
        """
        ...


__all__ = ["SessionRequestStore"]
