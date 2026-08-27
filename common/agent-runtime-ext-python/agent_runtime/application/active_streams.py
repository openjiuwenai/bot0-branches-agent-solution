# coding: utf-8

"""在途流登记表（Feat-Func-000b §2.3.1、Feat-Func-002b §4.3）。

登记正在执行的结果流，支撑三件事：**按会话取消**、**关停排水**、**活跃计数**。

## 为什么是「会话 → 句柄集合」而不是「已取消的会话键集合」

这不是形态偏好，是正确性要求。曾经的实现是单一 ``set[str]``，并在每次执行开头
把自己的键 ``discard`` 掉——于是同一会话有并发执行时，**后启动的执行一开始就清掉了
前一个执行的取消标记**，前者的取消被静默撤销。该缺陷只在复用同一会话时显形，
单次调用的测试永远抓不到。

句柄粒度下，取消置位的是**当时存在的那些句柄**；此后新注册的是新对象，天然不受影响。
正确性由数据结构保证，不靠时序小心。对标的登记表同样是「会话 → 句柄集合」。

## 不外置

句柄引用的是本进程内的执行，外置一份副本对其他副本没有意义。这是上位的硬约束
（L1 物理视图明写不把流取消句柄迁入 Redis），不是本设计的取舍。跨副本的取消由宿主的
实例亲和承接——同一会话的请求路由到同一副本，这已是宿主义务。
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Iterable, Optional, cast

from agent_runtime.ports.contract import satisfies
from agent_runtime.ports.interrupt import InterruptNotifiable, InterruptReason

_logger = logging.getLogger(__name__)


class StreamHandle:
    """一条在途流的取消句柄。

    **每条流一个实例**。持有一个取消标志，由消费循环在每轮迭代前检查。
    用普通布尔而非事件对象：消费侧是主动轮询而非等待唤醒，事件对象的等待语义
    在此用不上，反而会让「检查」看起来像「阻塞等待」。
    """

    __slots__ = ("conversation_id", "_cancelled", "_reason")

    def __init__(self, conversation_id: str) -> None:
        self.conversation_id = conversation_id
        self._cancelled = False
        self._reason: Optional[InterruptReason] = None

    @property
    def cancelled(self) -> bool:
        """消费循环每轮检查它。置位后不会复位——句柄是一次性的。"""
        return self._cancelled

    @property
    def reason(self) -> Optional[InterruptReason]:
        """置位时的取消理由；未取消时为空。

        **两种取消的后续处置相反**：调用方主动取消时客户端在等一个明确答复、本轮须落
        取消终态；进程关停时执行是被服务端掐断的，上位把这一轮定性为「未完成」。
        中断通知契约早已按这两类分开（见 `ports/interrupt.py` 的取值说明：
        「把两者压成一个信号会让实现方无从区分」），而取消句柄此前只有一个布尔——
        **入口层因此分不出该落终态还是不落**，实测把主动取消也当成了关停。
        """
        return self._reason

    def cancel(self, reason: InterruptReason = InterruptReason.USER_REQUEST) -> None:
        """置位取消标志并记下理由。**已置位时保留先到的理由**。

        默认取调用方主动请求——既有调用点的语义都是它，不写理由的地方不会被改变含义。

        ## 为什么先到者胜

        入口层据理由判「本轮该不该落终态」：调用方主动取消时客户端在等一个明确答复、
        本轮须落取消终态；进程关停时执行是被服务端掐断的，上位把这一轮定性为「未完成」。

        **先到的那个才是真正掐断本轮的原因**。上一版无条件覆写，于是关停排水置的
        `LIFECYCLE_SHUTDOWN` 会被随后到达的用户取消改写成 `USER_REQUEST`——
        一个被服务端掐断的轮次就此按正常结果结算。

        改这条前确认过没有依赖「后到覆盖先到」的路径：四个调用点（编排层两处、
        本模块两处）都只在自己那次取消里写一次，判据侧也没有断言覆盖语义的条目。

        **取消本身仍然幂等**：标志一旦置位不复位，重复调用不改变任何可观察行为，
        变的只是理由不再被改写。
        """
        if self._cancelled:
            return
        self._cancelled = True
        self._reason = reason

    def __repr__(self) -> str:  # pragma: no cover - 诊断用
        return (
            f"StreamHandle({self.conversation_id!r}, "
            f"cancelled={self._cancelled}, reason={self._reason})"
        )


#: 预置取消的时效窗口（秒）。
#:
#: 它要覆盖的是「消息受理之后、在途流注册之前」那一段——量级是一次存储往返。
#: 取 2 秒：比那段路长一个数量级（够慢机器与慢存储），又远短于人发下一条消息的间隔。
#:
#: **这个值是取舍不是精确量**：调大会让空闲会话上的取消更容易误伤下一次请求，
#: 调小会让慢存储上的真实竞态漏掉。落在这两者之间的任何值都可用。
_PENDING_CANCEL_WINDOW_S = 2.0


class ActiveStreamRegistry:
    """在途流登记表。进程内，不外置。"""

    def __init__(self) -> None:
        self._active: dict[str, set[StreamHandle]] = {}
        #: **取消早于注册时的暂存**：会话标识 → （那次取消的理由, 暂存时刻）。
        #:
        #: 客户端可能在消息受理之后、本轮在途流注册之前就调取消端点——
        #: 窗口量级是一次存储往返，关停排水同样能触发。此前那次取消打在空登记表上
        #: 直接 `return 0`、**不留痕迹**，随后注册进来的流看到的是一张干净的表，
        #: 入口层据以判断「本轮是否已被取消」的依据为假。
        #:
        #: 实测形态（真入口路径探针，九种让出时点里的一种）：旧 Task 落取消态、
        #: 新建 Task 落要求输入态——**同一会话下 Task 分叉，会话对外仍显示要求输入**。
        #:
        #: **三条约束缺一不可**：只对已经有过在途轮次的会话暂存、一次性消费、时效窗口。
        #:
        #: - **只对有过在途轮次的会话暂存**：要修的窗口是「消息受理之后、在途流注册
        #:   之前」——那意味着这个会话此刻正在被处理。取消打在一个**从未跑过任何
        #:   轮次**的会话上时，它没有对象，不该被记住
        #: - **一次性消费**：防同一次取消被用两遍
        #: - **时效窗口**：兜住「有过轮次、但那次取消等了很久」的情形
        #:
        #: 第一条是实测逼出来的。只有后两条时，对等比对脚本先对某会话调取消端点
        #: （那时没有在途轮次），随后用**同一个会话**发流式请求——本版一帧都没产出、
        #: 存量产出三帧，差分当场报「帧数不同：本版 0、存量 3」。
        #: **这比原缺陷更坏**：原缺陷只在竞态窗口内偶发，这个是必现的。
        self._pending_cancel: dict[str, tuple[InterruptReason, float]] = {}
        #: 曾经登记过在途流的会话。**只增不删**——它回答的是「这个会话有没有被处理过」，
        #: 而不是「现在有没有在途流」；后者由 `_active` 回答。
        self._seen: set[str] = set()

    # ── 登记与注销 ────────────────────────────────────────────────

    def register(self, conversation_id: str) -> StreamHandle:
        """登记一条在途流，返回其句柄。

        实现者必须保证：返回的句柄由调用方持有并在收尾处注销——**注销必须无条件执行**
        （异常路径同样走到），否则句柄泄漏会让排水永远等不到零。
        """
        handle = StreamHandle(conversation_id)
        self._active.setdefault(conversation_id, set()).add(handle)
        self._seen.add(conversation_id)
        # **消费早到的取消**：取消若发生在本次注册之前，这里把它补置到新句柄上。
        # 不补的话，那次取消对本轮完全不可见——入口层会按「没被取消」结算。
        pending = self._pending_cancel.pop(conversation_id, None)
        if pending is not None:
            reason, stamped = pending
            if time.monotonic() - stamped <= _PENDING_CANCEL_WINDOW_S:
                handle.cancel(reason)
        return handle

    def unregister(self, handle: StreamHandle) -> None:
        """注销一条在途流。**只摘自己**，不影响同会话的兄弟句柄。

        对已注销的句柄重复调用是空操作——收尾路径可能被走两次（正常结束 + 异常清理）。
        """
        bucket = self._active.get(handle.conversation_id)
        if bucket is None:
            return
        bucket.discard(handle)
        if not bucket:
            self._active.pop(handle.conversation_id, None)

    # ── 取消 ──────────────────────────────────────────────────────

    def cancel(
        self,
        conversation_id: str,
        reason: InterruptReason = InterruptReason.USER_REQUEST,
    ) -> int:
        """取消某会话下**当时存在的全部**在途流，返回置位的句柄数。

        取消一个不存在的会话是空操作、返回 0——调用方可能在执行已结束后才发来取消。
        """
        handles = self._active.get(conversation_id)
        if not handles:
            # **取消早于注册**：暂存下来，等本轮的流注册时补置。
            # 直接返回 0 会让这次取消彻底消失——见 `_pending_cancel` 的说明。
            if conversation_id in self._seen:
                # 这个会话被处理过——取消可能落在「上一轮注销之后、下一轮注册之前」，
                # 那正是要修的窗口。从未跑过任何轮次的会话不暂存：那次取消没有对象。
                self._pending_cancel[conversation_id] = (reason, time.monotonic())
            return 0
        for handle in tuple(handles):
            handle.cancel(reason)
        return len(handles)

    def cancel_all(self) -> int:
        """取消全部在途流，返回置位的句柄数。关停排水超时后用它。

        **理由固定为进程关停**：本方法只有关停排水这一个调用方，而理由决定入口层
        要不要落终态——传错等于把被掐断的执行落成取消态。
        """
        total = 0
        for conversation_id in tuple(self._active):
            total += self.cancel(conversation_id, InterruptReason.LIFECYCLE_SHUTDOWN)
        return total

    # ── 观测与排水 ────────────────────────────────────────────────

    def active_count(self) -> int:
        return sum(len(v) for v in self._active.values())

    async def await_drain(self, timeout_s: float, *, poll_interval_s: float = 0.05) -> bool:
        """等待在途流降到零，返回是否在超时前排空。

        **不中断任何执行**——它只等。是否在超时后强停由调用方决定（关停编排器会在
        超时后调 ``cancel_all``）。把「等待」与「强停」分开，是因为权威的关停顺序里
        宽限期内明确不得中断在途执行。
        """
        loop = asyncio.get_running_loop()
        deadline = loop.time() + max(0.0, timeout_s)
        while self.active_count() > 0:
            if loop.time() >= deadline:
                return False
            await asyncio.sleep(poll_interval_s)
        return True


async def notify_interrupt(
    targets: Iterable[object], conversation_id: str, reason: InterruptReason
) -> int:
    """向实现了中断通知契约的目标发出通知，返回成功通知的数量。

    权威 `CL-8617f01064a7` 要求取消时「尽力通知底层框架或远端请求」。本函数即那个「尽力」：

    - 未实现该契约的目标**跳过**，不报错——契约是可选的
    - 通知抛出时**吞掉并记日志**，不影响取消返回。契约已要求实现方不抛，此处是兜底；
      让异常传播会使取消本身失败，而调用方已经收到受理成功的响应
    """
    notified = 0
    for target in targets:
        if not satisfies(target, InterruptNotifiable):
            continue
        # **判定已在上一行完成，此处只做静态收窄**：`satisfies` 返回裸 `bool`，
        # 静态检查看不出 `target` 已经满足该端口（理由见 `ports/contract.py`
        # 的「为什么不返回 TypeGuard」）。收窄不重做判定，故两者不会给出不同答案。
        notifiable = cast(InterruptNotifiable, target)
        try:
            await notifiable.on_interrupt(conversation_id, reason)
            notified += 1
        except Exception:
            _logger.exception(
                "中断通知失败，已忽略：目标=%s 会话=%s", type(target).__name__, conversation_id
            )
    return notified
