# coding: utf-8

"""协作式取消的收束报告（两条入站适配器共用）。

## 它解决什么

关停排水超时与按会话取消都是**置一个布尔标志**，编排层的中继循环轮询到它即 `break`
——生成器**正常退出**，不抛异常、不改帧序。于是从入口层看，「被掐断」与「跑完了」
完全同形，入口据此把被服务端掐断的那一轮落成完成态。

编排层在收束时经回调报告这个事实，入口层据此决定要不要落终态。**编排层只报告事实，
不决定处置**——那一轮该落什么属入口语义，两条入口各自按自己的对外契约决定。

## 为什么两条入口共用一份

上一版这个类写在自定义 REST 的路由模块里，标准协议入口没有对应物，于是同一次关停下
两条入口给出相反的解：REST 侧不落终态、协议侧补完成态，而详设写的是无限定的
「一个终态都不落」。**同一个领域事实由两处各自表达，必然漂移。**

放在 `adapters/inbound/` 而不是 `ports/` 或 `application/`：它不是端口契约（编排层的
回调签名是 `Callable[[Optional[InterruptReason]], None]`，不依赖本类型），也不是领域概念（取消这件事的领域
表达是编排层的取消标志）。它是**入口层记录一次请求内事实的载体**，只对入口有意义。
"""
from __future__ import annotations

from typing import Optional

from agent_runtime.ports.interrupt import InterruptReason


class CooperativeCancelReport:
    """本轮是否因协作式取消而收束。

    **每次请求一个实例，随本轮结束回收**——不是跨请求状态，不需外置。

    用类而不是单元素列表：后者要靠下标赋值从闭包里写，与「装配期建一张表、请求处理器
    往里写」在形态上无从分辨，而那个形态在多副本下是静默失守的（副本内是全局、
    副本间各持一份）。
    """

    __slots__ = ("_cancelled", "_reason")

    def __init__(self) -> None:
        self._cancelled = False
        self._reason: Optional[InterruptReason] = None

    def mark(self, reason: Optional[InterruptReason] = None) -> None:
        """编排层在本流因协作式取消而收束时调用一次，带上取消理由。

        **理由必须带**：两种取消的入口处置不同——调用方主动取消时协议入口的取消端点
        已经落过终态（再补一个完成态会把它覆盖，实测发生过），进程关停时则是轮次未完成。
        本件只记事实，判断留给入口。
        """
        self._cancelled = True
        self._reason = reason

    @property
    def cancelled(self) -> bool:
        return self._cancelled

    @property
    def reason(self) -> Optional[InterruptReason]:
        """置位时的取消理由；未取消或编排层未给时为空。"""
        return self._reason

    @property
    def settles_normally(self) -> bool:
        """本轮是否仍按正常结果结算。

        **只有「调用方主动取消」才是**：那条路上取消端点自己会落取消终态，
        本轮的等待态与终态照常结算不会冲突。

        其余理由（进程关停、生命周期中断、其余原因）**一律不落终态**——
        它们的共同点是**这一轮没跑完**，而入口的结算表把「正常完成」定义为
        「等待点已被这一轮推进完」。给一个没跑完的轮次落终态，是替它下结论。

        **判据取「白名单」而非「黑名单」**：上一版写 `by_shutdown`，
        只认进程关停一个取值，而中断原因有四个取值、三个落在「照常结算」一侧。
        更要命的是——裁定时引的那条上游依据（其中断件「全程不触碰 Task 状态」）
        用的正是 `LIFECYCLE_INTERRUPT`，**恰好是漏掉的那个**。
        取值域日后增值时，新值默认落在「不落终态」这一侧，那是安全的一侧。

        **「主动取消照常结算」不等于「落完成态」**：两条入口的取消端点在调编排层
        取消之后，各自紧接着落取消终态（自定义 REST 经任务绑定件、标准协议入口
        经取消事件）。绕过端点直调编排层的取消会落完成态——那是**测试装置的路径**，
        生产里进不来：全仓 `cancel_active` 的三个调用点里，两个是端点、一个是
        `reset_conversation`（传生命周期中断）。
        """
        # **没取消时当然照常结算**：`_reason` 为空既表示「本轮没被取消」，
        # 也表示「编排层没给理由」。前者是绝大多数轮次，后者按保守取「不落」——
        # 但那要先经 `cancelled` 判过，故此处先看有没有被取消。
        if not self._cancelled:
            return True
        return self._reason is InterruptReason.USER_REQUEST


__all__ = ["CooperativeCancelReport"]
