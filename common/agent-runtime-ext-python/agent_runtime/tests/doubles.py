# coding: utf-8

"""满足端口协议的替身基类。

## 它解决什么

全仓有一百多个测试替身，各自只实现被测路径用到的那几个方法。后果有两层：

**表层**是静态检查看不过去——替身被当作实现传进产品代码，而它不满足端口协议。
**深层**是判据可能测不到真实约束：替身比真实实现宽松，集成方按端口写自己的实现时，
会撞上我方判据从未覆盖的要求。

散着补的问题在于下一次协议加方法时要再补一百多处，而漏掉的那处不会有任何提示。
此处提供满足协议的最小实现，各测试继承后只覆写自己关心的方法——**协议变动时改一处**。

## 默认实现不撒谎

能真做的就真做（缓存替身用内存字典，读写语义与真实实现同构），做不了的抛异常而非
静默返回成功。**「返回成功但什么也没做」是本仓反复踩到的形态**，替身里更危险：
它让判据在一个不存在的成功路径上通过。

## 每个默认实现都留痕

**这是本模块最要紧的一条。** 替身此前的「不完整」本身在充当一道断言：产品代码一旦
读到替身没实现的成员就当场抛属性错误，判据随即转红。补齐方法面等于拆掉这道免费防线——
若不补回等量的显式断言，判据会静默变弱。

实测确证过三处：判重改用「判存 + 带期写入」两条命令后判据仍绿（原判据只认得三个方法名）；
鉴权之前插一次存储访问后，四条「未鉴权不得触达存储」的判据全部失效；
处理器每轮结束误清一次框架会话，被基类的空实现静默吸收。

故所有默认实现都记录自己被调用过——`ConformingCache.ops` 记方法与键，
`ConformingHandler.calls` 记方法名。**判据可以断言这些痕迹**，那才是免费断言的等价替代。
"""
from __future__ import annotations

from typing import Any, AsyncIterator, Callable, Optional

from agent_runtime.domain.context import ServeRequest
from agent_runtime.domain.result import QueryChunk, QueryResponse
from agent_runtime.ports.interrupt import InterruptReason


class ConformingHandler:
    """满足 `AgentHandler` 全部方法面的最小处理器。

    覆写 `stream_query` 即可定制产出；`query` 默认聚合流式结果，与编排层的实际形态一致
    （阻塞路径就是把流式排干），不另写一套可能与流式分叉的逻辑。
    """

    #: **刻意醒目**：产品代码若把它当成装配处配置的服务名用出去，读日志或看报文
    #: 一眼就能认出取错了源。此前用的是一个看着像真名的值，取错源时看不出来。
    agent_id = "__conforming_double__"
    priority = 0

    def __init__(self) -> None:
        #: 默认实现的调用痕迹，按发生顺序记方法名。判据可据此断言「不该被调的没被调」——
        #: 例如每轮流式结束误清一次框架会话，此前被空实现静默吸收。
        self.calls: list[str] = []

    def is_healthy(self) -> bool:
        self.calls.append("is_healthy")
        return True

    async def stream_query(self, request: ServeRequest) -> AsyncIterator[QueryChunk]:
        self.calls.append("stream_query")
        yield QueryChunk.of_final_answer("ok")

    async def query(self, request: ServeRequest) -> QueryResponse:
        """按协议返回聚合响应，**不是帧列表**。

        我第一版写成了返回帧列表——静态检查立刻指出与协议不符。那正是补齐替身的
        收益：替身只实现被用到的方法时，这类误读没有任何东西会指出来，而集成方
        按协议写实现时会得到一个与我方判据不同的形态。
        """
        self.calls.append("query")
        chunks = [chunk async for chunk in self.stream_query(request)]
        return QueryResponse(
            result=chunks[-1].data if chunks else None,
            conversation_id=getattr(request, "conversation_id", ""),
        )

    async def start(self) -> None:
        """无需准备。**空实现不等于登记态桩**——此处确实没有要做的事。"""
        self.calls.append("start")

    async def stop(self) -> None:
        """无需释放，理由同 `start`。"""
        self.calls.append("stop")

    async def clear_session(self, conversation_id: str) -> None:
        """替身不持会话态，无可清理。**但记下这次调用**——误清是静默故障：
        多轮对话第二轮起丢上下文，而每一轮自身看起来都正常。
        """
        self.calls.append(f"clear_session:{conversation_id}")


class ConformingCache:
    """满足 `RuntimeRedisClient` 全部方法面的内存实现。

    **真做而非假装**：读写、判存、批量读、前缀遍历都按内存字典实现，语义与真实客户端
    同构。生存期不做真实过期（测试进程内不值得起定时器），但**记录下来**——
    需要断言"写入是否带了过期"的判据可以读 `ttls`。
    """

    def __init__(self) -> None:
        self.data: dict[str, bytes] = {}
        #: 每次写入携带的生存期，键同 `data`。值为 ``None`` 表示按外部治理写入（不带过期）。
        self.ttls: dict[str, Optional[int]] = {}
        #: **每次写入携带的生存期，按写入顺序记，删除不抹掉它。**
        #:
        #: `ttls` 反映的是「现在存着什么」，`delete` 会把它抹掉；
        #: 而「写入那一刻带没带过期」是另一回事——补偿式删除
        #: （例如幂等占位回滚）之后读 `ttls` 会读到空，
        #: 那与「写入没带存活期」是两个完全不同的事实。实测踩过。
        self.ttl_at_write: list[tuple[str, Optional[int]]] = []
        #: **协议全部九个方法的调用痕迹**，形如 ``"setnx:<键>"``，按发生顺序。
        #:
        #: 记在基类而不是各子类：子类只覆写自己关心的方法时，其余方法的调用不留痕，
        #: 「未鉴权不得触达存储」这类判据就只覆盖被覆写的那几个。实测过——
        #: 判重改用未被覆写的两条命令后，判据仍然全绿。
        self.ops: list[str] = []

    def _store(self, key: str, value: bytes, ttl_s: Optional[int]) -> None:
        """所有写入的落点。

        **公开方法各自调它，不互相调用**：子类常覆写公开方法来记录调用序列，
        基类内部若从一个公开方法调另一个，子类就会观察到基类的内部实现——
        判据断言「只发生了一次原子写」时，会因为基类内部多走了一跳而转红。
        实测踩过：判重判据看到 `setnx` 后面跟着一条 `set`，那条来自基类自己。
        """
        self.data[key] = value
        self.ttls[key] = ttl_s
        self.ttl_at_write.append((key, ttl_s))

    async def get(self, key: str) -> Optional[bytes]:
        self.ops.append(f"get:{key}")
        return self.data.get(key)

    async def set(self, key: str, value: bytes, *, ttl_s: int) -> None:
        self.ops.append(f"set:{key}")
        self._store(key, value, ttl_s)

    async def setex(self, key: str, ttl_s: int, value: bytes) -> None:
        self.ops.append(f"setex:{key}")
        self._store(key, value, ttl_s)

    async def setnx(self, key: str, value: bytes, *, ttl_s: int) -> bool:
        self.ops.append(f"setnx:{key}")
        if key in self.data:
            return False
        self._store(key, value, ttl_s)
        return True

    async def delete(self, key: str) -> None:
        self.ops.append(f"delete:{key}")
        self.data.pop(key, None)
        self.ttls.pop(key, None)

    async def exists(self, key: str) -> bool:
        self.ops.append(f"exists:{key}")
        return key in self.data

    async def write_externally_governed(self, key: str, value: bytes) -> None:
        self.ops.append(f"write_externally_governed:{key}")
        self._store(key, value, None)

    async def mget(self, keys: list[str]) -> list[Optional[bytes]]:
        self.ops.append(f"mget:{','.join(keys)}")
        return [self.data.get(k) for k in keys]

    async def aclose(self) -> None:
        """无底层连接可释放。**空实现不是登记态桩**——此处确实没事可做。

        端口把释放列为契约的一部分（对齐上游的可自动关闭形态），故替身也要有。
        """
        self.ops.append("aclose")

    def scan(self, match: str, *, count: int = 100) -> AsyncIterator[bytes]:
        self.ops.append(f"scan:{match}")
        # 只支持真实实现里被用到的那一种形态：尾部通配。
        # 不支持的形态**抛异常而非返回空**——返回空会让判据在"没匹配到"这条
        # 不存在的路径上通过，而真实实现本会匹配出结果。
        if not match.endswith("*") or "*" in match[:-1] or "?" in match:
            raise NotImplementedError(f"替身只支持尾部通配，实得 {match!r}")
        prefix = match[:-1]

        async def _iter() -> AsyncIterator[bytes]:
            for key in list(self.data):
                if key.startswith(prefix):
                    yield key.encode("utf-8")

        return _iter()


class ConformingOrchestrator:
    """满足入口所需编排器方法面的最小替身：`stream_query` / `resume_query` / `cancel_active`。

    **覆写 `emit` 定制产出**，不覆写两个入口方法——那两个方法各自负责记录调用痕迹，
    覆写它们会让痕迹断掉。与 `ConformingHandler` 的用法同构（那里覆写 `stream_query`，
    因为它是唯一的产出点；这里有两个入口，故产出点单独抽出来）。

    **记录与产出分开是有代价换来的**：一个自建替身曾让 `resume_query` 先记锚点、
    再复用 `stream_query`，而后者又追加了一项 `None` 把刚记下的顶掉——读数看起来像
    「产品没保留锚点」，实际是替身的记录顺序错了。**替身写错时，它报的是产品的错。**
    """

    #: 每次进入执行入口时记下本轮的续接锚点（非续接为 `None`），按发生顺序。
    #: 判据据此断言「这一轮是不是续接、带的是哪个锚点」。
    def __init__(self) -> None:
        self.recovery_points: list[Optional[str]] = []
        self.cancelled: list[str] = []

    @staticmethod
    async def emit() -> AsyncIterator[QueryChunk]:
        """产出什么由子类决定。默认产一条终答——最短的成功路径。"""
        yield QueryChunk.of_final_answer("ok")

    async def stream_query(
        self,
        request: ServeRequest,
        *,
        on_cooperative_cancel: Optional[Callable[[Optional[InterruptReason]], None]] = None,
    ) -> AsyncIterator[QueryChunk]:
        """`on_cooperative_cancel` 收下但不调——本替身不模拟协作式取消。

        **收下这个参数是必需的，不是形式**：真实编排器的两个入口都带它，
        入口层无条件透传。替身少一个关键字参数，入口层一驱动就 `TypeError`——
        那报的是替身的错，读起来却像产品的错。要模拟取消的判据自己覆写它。
        """
        self.recovery_points.append(None)
        async for chunk in self.emit():
            yield chunk

    async def resume_query(
        self,
        request: ServeRequest,
        resume: Any,
        *,
        on_cooperative_cancel: Optional[Callable[[Optional[InterruptReason]], None]] = None,
    ) -> AsyncIterator[QueryChunk]:
        """续接入口同样收下取消出参，理由见 `stream_query`。"""
        self.recovery_points.append(getattr(resume, "recovery_point_id", None) or None)
        async for chunk in self.emit():
            yield chunk

    async def cancel_active(self, conversation_id: str) -> int:
        self.cancelled.append(conversation_id)
        return 1


__all__ = ["ConformingHandler", "ConformingCache", "ConformingOrchestrator"]
