# coding: utf-8

"""取消契约的边界判据（依赖倒置：契约不得建立在没写进类型的行为上）。

## 这组判据防的是什么

端口 `agent_runtime/ports/handler.py` 原本把取消写成「消费侧停止迭代/`aclose()`
=isCancelled」——**一条斜杠把两件事糊在了一起**：

| | 由什么表达 | 声明类型 `AsyncIterator` 够不够 |
|---|---|---|
| 取消 | 消费侧停止迭代 | **够** |
| 清理（连接释放、上下文退出） | 消费方调 `aclose()` | 不够——`aclose()` 属 `AsyncGenerator` |

后果是：按声明类型写实现的人（返回自定义迭代器而非 async generator）
**无从知道自己少做了什么**——那条要求只活在适配层的一处属性探测里
（`adapters/outbound/interruptible.py` 的 `getattr(iterator, "aclose", None)`）。

## 本组判据钉住的边界

1. 返回**自定义迭代器**（无 `aclose`）的实现，取消照常生效、不报错
2. 返回 **async generator** 的实现，其 `aclose()` 真的被调用（清理被驱动）
3. 取消是「不再继续」，不是「撤回已发生的事」——置位前已取到的帧照常产出

## 审计报告的两处判词经查证不准确，一并记明

- 「一个严格按声明类型实现的 handler **无法表达取消**」——不准确。取消由
  `interruptible` 的循环判 `cancel_event.is_set()` 后 `return` 达成，
  `AsyncIterator` 完全够；无法被驱动的是**清理**。
- 「`interruptible()` 收 `asyncio.Event` 而 application 用普通布尔，**两种取消表示并存**
  形态不一致」——不准确。二者是**两层不同的取消**：`cancel_event` 由处理器自己登记
  （`adapters/outbound/agentcore/handler.py` 的 `_inflight`），用于打断**帧内阻塞**；
  `StreamHandle`（`application/active_streams.py`）是编排层对会话流的取消，作用在**帧间**。
  并存是设计使然。
"""
from __future__ import annotations

import asyncio
from typing import AsyncIterator

from agent_runtime.adapters.outbound.interruptible import interruptible


class _PlainAsyncIterator:
    """**严格按声明类型**实现的流：只有 `__aiter__` / `__anext__`，没有 `aclose`。

    这正是端口声明允许、而此前那条取消契约暗中排除的实现风格。
    """

    def __init__(self, items: list) -> None:
        self._items = list(items)
        self.exhausted = False

    def __aiter__(self) -> "_PlainAsyncIterator":
        return self

    async def __anext__(self):  # noqa: ANN204
        if not self._items:
            self.exhausted = True
            raise StopAsyncIteration
        return self._items.pop(0)


def _drain(source: AsyncIterator, event: asyncio.Event, *, stop_after: int = -1) -> list:
    async def _run() -> list:
        taken = []
        async for item in interruptible(source, event):
            taken.append(item)
            if stop_after >= 0 and len(taken) >= stop_after:
                event.set()
        return taken

    return asyncio.run(_run())


def test_cancellation_works_for_a_plain_async_iterator() -> None:
    """按声明类型实现（无 `aclose`）的流，取消照常生效且不报错。

    **这是本条最实质的一点**：取消由消费侧停止迭代表达，`AsyncIterator` 够用。
    此前那条契约把 `aclose()` 与取消并列，读起来像是没有它就无法取消。

    **这条判据能失败**：若取消路径真的依赖 `aclose`，这里会抛属性错误或取不到取消效果。
    """
    event = asyncio.Event()
    source = _PlainAsyncIterator(["a", "b", "c", "d"])

    taken = _drain(source, event, stop_after=2)

    assert taken == ["a", "b"], "取消未在预期位置生效"
    assert source.exhausted is False, "取消后源流仍被读到了尽头"


def test_cleanup_hook_is_driven_when_the_implementation_provides_it() -> None:
    """实现方提供 `aclose()` 时，它**真的被调用**——清理被驱动。

    不关它，源流的连接释放与上下文退出没人做，连接可能悬到进程退出。

    **这条判据能失败**：把收尾处的关闭去掉立刻转红。
    """
    closed: list[str] = []

    async def _generator():  # noqa: ANN202
        try:
            for item in ("a", "b", "c"):
                yield item
        finally:
            closed.append("closed")

    async def _run() -> tuple:
        taken = []
        async for item in interruptible(_generator(), event):
            taken.append(item)
            event.set()
        # **快照必须在这里取**：`asyncio.run` 收尾时会回收未耗尽的异步生成器
        # （`shutdown_asyncgens`），那时 `finally` 照样会跑。等协程返回后再看，
        # 读到的是「最终跑了」而不是「收尾处驱动了它」——变异验证当场读出了这一点：
        # 把收尾处的关闭去掉时，那一版判据**一条不红**。
        return taken, list(closed)

    event = asyncio.Event()
    taken, closed_at_exit = asyncio.run(_run())

    assert taken == ["a"]
    assert closed_at_exit == ["closed"], (
        "退出消费循环时源流尚未被关闭——清理没有在收尾处被驱动"
    )


def test_a_missing_cleanup_hook_is_not_an_error() -> None:
    """清理钩子缺席**不是错误**——它是可选的。

    端口已写明 `aclose()` 属 `AsyncGenerator`、不在 `AsyncIterator` 的方法面上，
    实现方返回自定义迭代器时它不存在。探不到就不做清理，不报错、不降级。

    **这条判据能失败**：把属性探测改成硬要求（例如直接 `await iterator.aclose()`）
    立刻转红。
    """
    event = asyncio.Event()
    source = _PlainAsyncIterator(["only"])

    # 完整读到尽头也不该因为缺少清理钩子而抛
    assert _drain(source, event) == ["only"]
    assert source.exhausted is True


def test_frames_taken_before_cancellation_are_kept() -> None:
    """取消是「不再继续」，不是「撤回已发生的事」。

    置位前已取到的帧照常产出——这条语义写在 `interruptible` 的文档里，
    但此前没有判据钉住。

    **这条判据能失败**：把取消实现成丢弃已取帧立刻转红。
    """
    event = asyncio.Event()
    source = _PlainAsyncIterator(list("abcdef"))

    taken = _drain(source, event, stop_after=3)

    assert taken == ["a", "b", "c"]
