# coding: utf-8

"""远端委派批次的执行端口（Feat-Func-004b §3.1）。

## 它划的是哪条线

编排层要的是「把这组委托跑完，给我各成员的结果」；**怎么跑**——并发预算、成员派发、
远端会话标识派生、批次跨轮快照——全是与外部世界打交道的活，归适配层。

本端口只表达前者。它是**编排端口**，不是存储端口：方法面里没有任何存取语义，
实现方内部如何持久化批次状态，编排层不知道也不需要知道。

## 与「不新增批次存储 SPI」的关系

上游《并行下游智能体任务生成与交接》详细设计禁止新增批次存储 SPI，要求协调器直接复用
已有的任务存储。本端口不违反该条：它不是存储抽象，实现方正是**直接复用注入的任务存储**，
不新增落盘通道、不新增键前缀、不新增过期策略。

## 签名里不得出现协议类型

参数与返回一律是领域对象或纯 Python 类型。端口是编排层与适配层的接触面，
一旦让 `Task`、卡片或客户端类型出现在这里，协议对象就等于被带进了内核。
"""
from __future__ import annotations

from typing import Callable, Iterable, Optional, Protocol, runtime_checkable

from agent_runtime.domain.remote.delegation import MemberOutcome, RemoteDelegation
from agent_runtime.domain.result import QueryChunk
from agent_runtime.ports.remote import CancellationSignal


@runtime_checkable
class RemoteBatchRunner(Protocol):
    """跑一个远端委派批次，产各成员结果。"""

    async def run_batch(
        self,
        delegations: Iterable[RemoteDelegation],
        *,
        parent_context_id: str = "",
        parent_task_id: str = "",
        #: **本轮**的链路标识（需求九）。南向会话上下文此前只从首跳快照取，
        #: 于是第二轮起南向带的还是首轮那个值，下游按它记的日志与本轮日志串不起来。
        #: **形参次序与实现一致**——端口与实现的参数序列有判据逐项比对。
        parent_trace_id: str = "",
        agent_id: str = "",
        call_depth: int = 0,
        #: 成员的**发起边界与中间输出**的转发钩子；不传即不转发。
        #:
        #: **同步回调、参数全是领域对象**——本端口明禁协议类型入参（见模块说明），
        #: 这一条同样适用于回调的参数面。做成协程会让批次的消费循环等在转发上，
        #: 而转发的下游是父流：父流慢就会把远端调用一起拖慢。
        #:
        #: **它承载两类帧**：成员真正被发起时的一帧 `node_start`（见下方必须保证的第五条），
        #: 与该成员随后的每一帧过程输出。上一版这里只写「中间输出」，而实现已经
        #: 靠它送发起边界——**换一个照契约写的合规实现，全部 `node_start` 会静默消失**。
        on_member_output: Optional[Callable[[RemoteDelegation, QueryChunk], None]] = None,
        #: 本批次的**协作式取消标志**；不传即本批次不可协作式取消。
        #:
        #: **它不是可选能力，是正确性的一部分**——取消发生而实现读不到它时，
        #: 成员会被归结为完成态，半截内容当完整答复上对外面。这一点与上面的
        #: 转发钩子相反：钩子缺失只是少几帧过程输出，取消标志缺失是**错误的落态**。
        #: 故实现方不得按签名探测「调用器支不支持」来决定传不传，须无条件透传。
        cancel_signal: Optional[CancellationSignal] = None,
    ) -> dict[str, MemberOutcome]:
        """并发执行批次，按委托关联键归档结果。

        参数 delegations：同轮产生的全部委托。空集合返回空字典，不视为异常。
        参数 parent_context_id：父请求的会话标识。多成员批次据此为各成员派生独立的
            远端会话标识——同批成员共用一个远端会话会让下游把它们合并、互相覆盖状态。
        参数 parent_task_id：父任务标识，用于定位承载批次快照的影子任务。
        参数 agent_id：发起委派的本地智能体标识，与父任务标识共同定位影子任务。
        参数 call_depth：本次派发所处的调用深度。**必须逐层传递**——递归深度收敛
            靠它判定「达限即拒」；端口漏了这个参数，实现即使有也接不上，
            深度会恒为初值而收敛静默失效。
        返回：委托关联键 → 成员结果。**含未达稳定态的成员**（如仍在等客户端输入），
            由调用方按结算规则判定屏障是否达成。

        实现者必须保证：
          - **成员失败隔离**：单个成员失败转为该成员的结构化失败结果，不取消同批其他成员；
          - **超出并发预算的委托进跳过清单，不得静默丢弃**——排队层默认关闭时
            回落截断形态（与存量一致），被截断的成员须出现在结果里并带跳过原因，
            调用方看得见；开启排队层后先排队、队列满才截断。此前本条只写了排队那一档，
            与默认关闭的实现读起来矛盾；
          - **批次标识跨轮一致**：有成员等待客户端输入时父任务挂起，续轮到达后各成员
            必须重新得到与首轮完全相同的远端会话标识，否则远端会话对不上；
          - 结果按委托关联键归档，**不得按完成顺序、工具名或目标智能体匹配**；
          - **成员真正被发起时，经 `on_member_output` 送出一帧发起边界**：
            用 `QueryChunk.of_event("node_start", data={"entity_name": <被调方标识>})`
            构造——**事件名必须落在 `of_event` 的事件名位**（即结果块的 `event_type`），
            不能只写进 `data`。编排层按这个位置识别它并转成对外的边界帧；
            换个载体则该帧被当成普通过程输出，收敛边界随之归零、事件名在 wire 上丢失
            （实测过一个照旧契约写的实现即是此形态）。「真正被发起」是硬边界：
            深度超限与并发预算超限的成员**不得**产生这一帧——存量对它们一帧不发
            （`.legacy-oracle/applications/a2a_service/orchestrator/
            handlers/remote_agent_handler.py` 的 `_handle_delegate` 起的派发准入，超限项只进跳过清单）。
            **这一条只有实现者能保证**：准入判定在实现内部，调用方看不见谁被发起了，
            而它决定对外的收敛边界发给谁。
          - **`cancel_signal` 无条件透传给每个成员调用，且成员调用须逐帧检查它**：
            置位时该成员归结为取消态（`outcome` 取消值）、批次照常收敛，
            **不得归结为完成**。存量在同一情形报
            `{"event":"node_end","status":"cancelled","reason":"用户取消"}`
            （`.legacy-oracle/applications/a2a_service/orchestrator/
            handlers/remote_agent_handler.py` 的 `_build_sub_agent_card`），归成完成即对外兼容破坏：
            集成方收到 `status=done` 与一段半截文本，无从知道这一轮被取消过。
            **不得按签名探测决定传不传**——探测落空时静默失能，而失能的表现
            恰是上面那个错误落态。
        """
        ...
