# coding: utf-8

"""中断通知契约（Feat-Func-002b §2.3.1）。

## 为什么它是独立契约，而不是执行契约的第七个方法

权威两条并存，合起来只允许这一种形态：

- `CL-fad58bb77d74`：Handler **无独立 `cancel()` 入口**——在执行契约上加取消方法直接违反
- `CL-8617f01064a7`：取消时「必须停止 runtime 对该执行流的继续消费，**并尽力通知底层框架或远端请求**」

唯一解法是把通知放在**另一个契约**上，由适配器自愿实现。对标同样如此：它的中断通知
是一个独立的可选钩子接口（`AgentInterruptHandler`），不在 `AgentHandler` 里。

## 它不改变取消的承诺强度

权威 `:38` 明确取消「至少要阻止 runtime 继续消费」，能否中断底层由适配器能力决定；
`:169` 把强制取消列为 OUT。本契约提供的是**通知通道**，不是中断保证——通知不到、
或底层收到了不理会，都属预期内。

## 谁实现它

| 实现方 | 收到通知后做什么 |
|---|---|
| 本地框架适配器 | 尽力通知框架停止当前执行；框架不支持则空操作 |
| 远端服务代理 | **取消在途的远端调用**——这就是存量取消时的级联行为，不是新增能力 |
| 未实现该契约的适配器 | 无通知，退化为「仅停止消费」——仍满足 `:38` 的下限 |
"""
from __future__ import annotations

from enum import Enum
from typing import Protocol, runtime_checkable


class InterruptReason(str, Enum):
    """中断原因。

    实现方据此区分处置：调用方主动取消时远端可能还需要清理，而进程关停时
    应当尽快放弃、不做重试——把两者压成一个信号会让实现方无从区分。

    取值与对标的四值枚举一一对应，语义等价、命名本地化。
    """

    #: 调用方主动请求取消（标准协议入口的取消、自定义入口的取消端点）
    USER_REQUEST = "user_request"
    #: 进程关停排水阶段，宽限期已过仍未结束的在途执行
    LIFECYCLE_SHUTDOWN = "lifecycle_shutdown"
    #: 生命周期中断（运维触发的会话级中止）
    LIFECYCLE_INTERRUPT = "lifecycle_interrupt"
    #: 其余原因
    OTHER = "other"


@runtime_checkable
class InterruptNotifiable(Protocol):
    """取消发生时接收通知，以便尽力停掉底层执行。

    **可选契约**：适配器不实现它时，取消退化为「仅停止消费」，仍满足权威 `:38` 的下限。
    编排层用结构化子类型判定实现与否，不要求继承。
    """

    async def on_interrupt(self, conversation_id: str, reason: InterruptReason) -> None:
        """处理一次中断通知。

        参数 conversation_id：被取消的会话标识。
        参数 reason：取消原因，见 ``InterruptReason``。

        实现者必须保证：**不抛出**。取消路径上的异常会掩盖取消本身，而调用方已经
        收到取消成功的响应；实现方内部的失败应自行吞掉并记日志。编排层仍会兜底捕获，
        但那是防御而非契约——依赖兜底会让失败在日志里以「编排层捕获」的面目出现，
        掩盖真正的出错位置。

        实现者必须保证：**不阻塞**取消的返回。取消是尽力而为的信号，等待底层确认
        会把「协作式取消」变成「同步等待」，与权威 `:38` 的协作式语义相悖。
        """
        ...
