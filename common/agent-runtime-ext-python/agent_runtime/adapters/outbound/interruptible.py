# coding: utf-8

"""可中断迭代：把取消标志接进一条在途的异步流。

## 它做什么

编排层的取消是置一个标志位（`application/active_streams.py` 的句柄），
而底层适配器的迭代循环不认识那个标志。本模块把两者接起来：**每轮取值前检查标志**，
置位即停止迭代并关闭源流。

## 它不做什么，以及为什么

**不打断帧内阻塞**。源流迟迟不产帧时（远端受理后长时间不响应），本模块仍卡在取值上——
标志置位要等下一帧到达才被看见。

曾尝试用「把取值放进独立任务 + 竞速等待」来覆盖这个盲区，**两次都撞上更严重的问题**：

- **上下文跨任务**：框架用上下文变量管理会话，在生成器开头设置令牌、收尾处重置。
  取值一旦跑在另一个任务里（哪怕显式传上下文），设置与重置就跨了上下文，
  框架收尾时报「令牌创建于另一个上下文」——**整条执行链失败**。
- **与生成器关闭语义冲突**：改用队列在任务间传值后，消费方 `aclose()` 触发的
  `GeneratorExit` 使收尾处无法再 `await`，而泵可能正阻塞在入队——**死锁**。

两条都是被判据与真实框架抓出来的，不是推演。故本模块回到最简形态。

**帧内阻塞由谁兜底**：编排层持有在途流的句柄，取消时可直接停掉消费该流的任务——
任务取消能穿透帧内阻塞，且生成器的清理仍在原上下文里执行（已验证）。
那是比本模块更靠外的一层，也是唯一能同时满足两个约束的位置。

## 落位

放在 `adapters/outbound/` 而非 `application/`：它操作的是传输层的迭代与连接释放，
是适配层的机制。应用层只持有取消标志，不感知底层怎么被叫停。
"""
from __future__ import annotations

import asyncio
import logging
from typing import AsyncIterator, TypeVar

T = TypeVar("T")

_logger = logging.getLogger(__name__)


async def interruptible(
    source: AsyncIterator[T], cancel_event: asyncio.Event
) -> AsyncIterator[T]:
    """迭代 source，直到它自然结束或 cancel_event 置位。

    参数 source：被迭代的异步流。
    参数 cancel_event：置位即停止迭代。**置位前已取到的帧照常产出**——
        取消是「不再继续」，不是「撤回已发生的事」。

    实现者必须保证：本函数结束时 source 已被关闭。调用方不再需要自行关闭。
    """
    iterator = source.__aiter__()
    try:
        while True:
            if cancel_event.is_set():
                return
            try:
                item = await iterator.__anext__()
            except StopAsyncIteration:
                return
            yield item
    finally:
        # **关闭源流**：消费方提前停止时，源流的清理逻辑（连接释放、上下文退出）
        # 需要有人驱动——不关它，连接可能悬到进程退出。
        #
        # **这里探的是可选钩子，不是必需契约**（端口 `ports/handler.py` 已写明）：
        # `aclose()` 属 `AsyncGenerator`，不在 `AsyncIterator` 的方法面上。
        # 实现方返回 async generator 时它自动具备；返回自定义迭代器时它不存在，
        # **取消照常生效**（取消由消费侧停止迭代表达），只是清理归实现方自己做。
        # 故此处探不到不报错、不降级——它本就允许缺席。
        aclose = getattr(iterator, "aclose", None)
        if aclose is not None:
            try:
                await aclose()
            except Exception:  # noqa: BLE001  清理失败不改变取消的结局
                _logger.debug("关闭源流时的异常已忽略", exc_info=True)
