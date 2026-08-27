# coding: utf-8
# 参考宿主与部署级 E2E 装置：SPI 实现方法必须是实例方法；
# 按场景直接构造 runtime 内部状态是这一层的职责，不是越界访问。
# pylint: disable=protected-access


"""入站会话上下文写侧的部署级 E2E（Feat-Func-004b §2.3.1.1）。

## 为什么进程内判据不够

进程内判据分段验证：取值对不对、写入语义对不对、工厂透传没透传。
真实往返多覆盖三样，而这三样正是本项最容易静默失效的地方：

| 多出的 | 为什么进程内看不到 |
|---|---|
| 写入真的发生在真实请求路径上 | 进程内直接调写入函数；路由里漏调时进程内照样绿 |
| 写入的内容真的能被南向读回 | 进程内两侧各自构造映射，不经序列化与真实键名 |
| 纯本版部署下链路真的闭合 | 这正是本项要修的形态——只有起真实服务才验得到 |

**本项的实证**：链路断在任一段的表现都是「南向四字段为空」，不报错。
只有把「写」与「读」接在同一个真实服务里跑一遍，才能确认它闭合。

## 装置

一个服务同时扮演两个角色：

- `/v1/...` 是**自定义 REST 入口**：真实 HTTP 请求进来，写侧应把五字段落到共享键
- `/probe-session-context` 是**南向读侧的观察窗口**：按会话标识读回该键，
  报出实际落库的内容

替身只有一个：Redis 用进程内实现（真实 Redis 不在 E2E 依赖里）。
**被验的那一段全是真的**——真实 HTTP 请求、真实路由、真实写入件、真实键名与序列化。
"""
from __future__ import annotations

import os
from typing import AsyncIterator, Optional

from agent_runtime.bootstrap.rest_app import create_rest_app
from agent_runtime.domain.result import QueryChunk

_PORT = int(os.environ.get("PORT", "8090"))
_AGENT_ID = "session-ctx-echo"


class _InProcessRedis:
    """进程内 Redis 替身，只实现被测路径用到的方法。

    **`setnx` 按真实语义**：键在即不写、返回假。替身在这一点上偷懒的话，
    「读到即不写」这条就验不到——而它正是与存量对齐的关键一条。
    """

    def __init__(self) -> None:
        self._data: dict[str, bytes] = {}
        self._ttls: dict[str, int] = {}

    async def setnx(self, key: str, value: bytes, *, ttl_s: int) -> bool:
        if key in self._data:
            return False
        self._data[key] = value
        self._ttls[key] = ttl_s
        return True

    async def set(self, key: str, value: bytes, *, ttl_s: int) -> None:
        self._data[key] = value
        self._ttls[key] = ttl_s

    async def get(self, key: str) -> Optional[bytes]:
        return self._data.get(key)

    async def delete(self, key: str) -> None:
        self._data.pop(key, None)
        self._ttls.pop(key, None)

    def ttl_of(self, key: str) -> int:
        return self._ttls.get(key, -1)


class _EchoHandler:
    agent_id = _AGENT_ID
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    async def stream_query(request) -> AsyncIterator[QueryChunk]:
        yield QueryChunk.of_final_answer("已受理")

    async def query(self, request):
        chunks = [c async for c in self.stream_query(request)]
        return chunks[-1] if chunks else None

    @staticmethod
    async def start() -> None:
        ...

    @staticmethod
    async def stop() -> None:
        ...

    @staticmethod
    async def clear_session(conversation_id: str) -> None:
        ...


_redis = _InProcessRedis()


def _build_store():
    from agent_runtime.adapters.outbound.session.shared_keys import SharedSessionStore

    return SharedSessionStore(_redis)


_store = _build_store()
app = create_rest_app(_EchoHandler(), session_store=_store)


@app.get("/health")
async def _health():
    return {"status": "ok", "mode": "session-context-write"}


@app.get("/probe-session-context")
async def _probe(conversation_id: str):
    """从南向读侧的同一条通路读回该键，报出实际落库内容。

    **走 `get_request` 而不是直接读替身**：南向读的就是这个方法，
    绕开它去读裸键，验的就不是「南向能不能读到」这件事了。
    """
    from agent_runtime.adapters.outbound.session.shared_keys import session_request_key

    snapshot = await _store.get_request(conversation_id)
    return {
        "found": snapshot is not None,
        "fields": sorted(snapshot) if isinstance(snapshot, dict) else [],
        "snapshot": snapshot,
        "ttl_s": _redis.ttl_of(session_request_key(conversation_id)),
        "key": session_request_key(conversation_id),
    }


@app.get("/probe-southbound-context")
async def _probe_southbound(conversation_id: str, target: str = "downstream"):
    """经**南向出站件的真实取数路径**取会话上下文，报出它实际会发出去的内容。

    这一条比上一条更进一步：上一条验「键里有什么」，这一条验
    「南向发出去的报文片段长什么样」——中间还隔着出站件的组装规则。
    """
    from agent_runtime.adapters.outbound.remote.member_caller import RemoteMemberCaller
    from agent_runtime.domain.remote.delegation import RemoteDelegation

    # 本探针只驱动 `_read_session_context`，那条路径不碰调用器；传 `None` 是有意的。
    caller = RemoteMemberCaller(
        coordinator=None,  # type: ignore[arg-type]
        session_store=_store,
        parent_conversation_id=conversation_id,
    )
    ctx = await caller._read_session_context(  # noqa: SLF001  直取被测方法，不经传输
        RemoteDelegation(tool_call_id="tc-1", agent_id=target, parent_path=("A",))
    )
    return {
        "fields": sorted(ctx) if isinstance(ctx, dict) else [],
        "context": ctx,
        "headers_empty": not (ctx or {}).get("headers"),
        "body_empty": not (ctx or {}).get("body"),
    }
