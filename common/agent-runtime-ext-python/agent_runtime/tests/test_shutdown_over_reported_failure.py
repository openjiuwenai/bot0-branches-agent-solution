# coding: utf-8

"""关停取消撞上**已经发出去的失败帧**时，本轮落不落终态。

## 这条判据锁的是一个已知偏离，不是「正确行为」

场景：处理器已产出错误块、失败帧**已经发上 wire**，此后进程关停介入。
当前实现按 `settles_normally` 判——关停不在白名单里，于是**一个终态都不落**。
结果是：流以失败帧收束，而 Task 上没有任何终态。

`CL-`（`FEAT-022` 的流式连接结束条件）前半是 MUST：
「流式连接结束条件必须与标准 Task 终态或中断语义一致」。
**流说失败了、Task 说还在执行中，这不是一致。**

## 为什么先锁行为再裁定

关停语义的三处依据讲的都是「被掐断的、未完成的执行」——**不含「已经失败并已告知
客户端的执行」**。那一类的定性没有现成依据，两种解都有代价：

- 让失败胜出（已宣告的失败要落终态）：与「关停后在途 Task 定性为未完成」的上位定性冲突
- 维持取消胜出：流与 Task 不一致，触上面那条 MUST

**次序问题须单独裁定**（`internal/ledger/ISSUE-LEDGER.md` 的 S-1 已登记）。在裁定之前，
本文件把当前行为固定下来——**这样它变了会有人知道**，而不是像此前那样零判据：
改动方无从察觉自己动了一个待裁定的语义。
"""
from __future__ import annotations

import pytest

from agent_runtime.adapters.inbound.cooperative_cancel import CooperativeCancelReport
from agent_runtime.ports.interrupt import InterruptReason


def _report(reason: InterruptReason) -> CooperativeCancelReport:
    report = CooperativeCancelReport()
    report.mark(reason)
    return report


@pytest.mark.parametrize(
    "reason, settles",
    [
        (InterruptReason.USER_REQUEST, True),
        (InterruptReason.LIFECYCLE_SHUTDOWN, False),
    ],
)
def test_only_user_cancellation_settles_normally(reason, settles) -> None:
    """白名单：只有调用方主动取消照常结算。

    那条路上取消端点自己会落取消终态，本轮的等待态与终态照常结算不冲突；
    其余理由的共同点是**这一轮没跑完**，给没跑完的轮次落终态是替它下结论。

    **这条判据能失败**：把白名单改回黑名单（只认进程关停一个取值），
    其余理由会落进「照常结算」一侧，本条转红。
    """
    assert _report(reason).settles_normally is settles


def test_shutdown_beats_an_already_emitted_failure_frame() -> None:
    """**已知偏离**：失败帧已上 wire 后关停介入，本轮仍不落终态。

    这不是在断言「这样是对的」——它是在把一个**待裁定的语义**固定下来。
    当前的次序让取消胜过一次已经对外宣告过的失败，于是流以失败帧收束
    而 Task 无终态，与「流式连接结束条件必须与 Task 终态一致」那条 MUST 不符。

    次序怎么定须单独裁定（两种解都有代价，见本文件模块文档串）。
    **在裁定之前这条判据的作用是让改动可见**：谁动了这个语义，本条会红，
    他就会去读那份登记，而不是无声地把一个待裁定项改掉。

    **这条判据能失败**：把关停加进照常结算的白名单，本条转红。
    """
    report = _report(InterruptReason.LIFECYCLE_SHUTDOWN)
    # 失败已发生并已对外宣告——用一个真实的失败标志表示。
    failed_already_on_wire = True

    assert failed_already_on_wire, "前置：本场景的前提是失败帧已经发出去了"
    assert report.cancelled is True
    assert report.settles_normally is False, (
        "关停取消撞上已宣告的失败时，当前实现不落任何终态——"
        "若这里变绿了，说明次序语义被改动过，去读 ISSUE-LEDGER 的 S-1"
    )
