# coding: utf-8
# 超长行全在注释与文档串：权威路径必须连写才可复制跳转，Markdown 表格断行即损坏。
# 对齐上游 checkstyle 对 Javadoc 续行的同类排除；代码行宽由 ruff formatter 保证。
# pylint: disable=line-too-long


"""用例编排 ServeOrchestrator（application 层，ServeOrchestrator 语义沿用 L1）。

把一次标准调用编排为对**单 Agent** handler 的执行 + 结果流中继。同步与流式共享同一
执行路径（FEAT-001 §2.4）：handler 恒以流产出，query 由本层聚合（drain），stream_query
直接中继。单 Agent（FEAT-002）：handler 在装配期选定并注入（依赖倒置，只依赖 port）。

并发（L2-overview §9.2）：每请求独立 ctx、不共享可变执行态；取消标记以 Task 键隔离。
"""
from __future__ import annotations

import asyncio
import logging
from dataclasses import replace
from typing import Any, AsyncIterator, Callable, Optional

from agent_runtime.application.active_streams import (
    ActiveStreamRegistry,
    StreamHandle,
    notify_interrupt,
)
from agent_runtime.application.remote_batch import RemoteBatchSettlement
from agent_runtime.domain.aggregate import aggregate_stream
from agent_runtime.domain.context import ServeRequest
from agent_runtime.domain.remote.delegation import (
    OUTCOME_CANCELED,
    OUTCOME_COMPLETED,
    OUTCOME_FAILED,
    MemberOutcome,
    RemoteDelegation,
)
from agent_runtime.domain.result import QueryChunk, QueryResponse
from agent_runtime.domain.waiting.continuation import ResumeInput
from agent_runtime.ports.callback import CallbackSink
from agent_runtime.ports.handler import AgentHandler
from agent_runtime.ports.interrupt import InterruptReason
from agent_runtime.ports.remote_batch import RemoteBatchRunner

_logger = logging.getLogger(__name__)


class ServeOrchestrator(CallbackSink):
    """标准调用 → 单 Agent handler 执行 + 结果流编排。

    **显式声明 `CallbackSink`**：本类此前满足该协议纯靠鸭型——
    `application/serve.py` 不 import `ports.callback`，于是「谁实现了这份契约」
    这件事在代码里没有任何一处写着。与 `adapters/outbound/remote/card_resolver.py`
    对 `RemoteAgentCardResolver` 的处置同形：认领契约这件事只写在继承链上。
    """

    def __init__(
        self,
        handler: AgentHandler,
        *,
        batch_runner: Optional[RemoteBatchRunner] = None,
        streams: Optional[ActiveStreamRegistry] = None,
    ) -> None:
        self._handler = handler
        # 在途流登记表。编排器**不再自持取消状态**——单一键集合无法表达同会话并发，
        # 后启动的执行会清掉前者的取消标记（Feat-Func-002b §4.3.1）。可注入以便宿主
        # 复用同一张表做关停排水。
        self._streams = streams or ActiveStreamRegistry()
        # 远端委派批次执行器（FEAT-004，端口）。未注入=未配远端编排，收到委派即报可见错误
        # （不静默吞掉——没配却收到委派是装配错误，吞掉会让智能体永远等不到结果）。
        self._batch = batch_runner

    @property
    def active_streams(self) -> ActiveStreamRegistry:
        """供关停编排器做排水与全量取消。"""
        return self._streams

    @staticmethod
    def _key(ctx: ServeRequest) -> str:
        """取消与隔离的键 = **会话标识**。

        对标的两条取消通道（标准协议入口取消、生命周期中断）都用会话维度的键，
        无一用任务标识；标准协议入口详设 §4.1 亦明确禁止任务标识优先。
        """
        return ctx.conversation_id or ""

    async def stream_query(
        self,
        ctx: ServeRequest,
        *,
        on_cooperative_cancel: Optional[Callable[[Optional[InterruptReason]], None]] = None,
    ) -> AsyncIterator[QueryChunk]:
        """驱动 handler.stream_query，中继 QueryChunk 流；协作式取消即停止消费本流。

        `on_cooperative_cancel` 在本流因协作式取消而收束时回调一次。**本层只报告
        事实，不决定处置**——那一轮的状态该落成什么（或不落）属入口层的语义，
        由入口层按各自的对外契约决定。

        **为什么要有这个出参**：协作式取消的收束形态是生成器**正常退出**
        （`_relay` 见到 `handle.cancelled` 即 `break`），入口层的 `finally`
        因此看到「两个失败标志皆假」，据此把被关停掐断的在途轮次结算成完成态。
        实测读数 `settle_turn=[(False, False)]`，而
        `bootstrap/lifespan.py` 的排水日志逐字声明「不写 Task 终态」——
        **代码声明的行为与它实际引发的行为不符**。取消这件事必须在收束时可辨认，
        否则它与「跑完了」在入口层完全同形。

        不用异常表达：`stream_query` 的消费点遍布两个入口与聚合路径，
        改成抛异常会波及全部消费者，而它们中绝大多数与关停无关。
        """
        handle = self._streams.register(self._key(ctx))
        try:
            async for result in self._relay(ctx, handle):
                # **让出之前把取消事实同步反映给报告**。下面 `finally` 里那次报告
                # 发生在生成器结束之后，而入口层是在 `async for` 循环体内读这个事实
                # 的——它据「本轮是否已被取消」决定中断锚点落不落，那个判断发生在
                # 每一帧到达时，收束时才报告等于永远读不到。
                #
                # **这里守的是「入口层读到的值」，与帧从哪一处 `yield` 出来无关。**
                # 曾误以为「取消置位后没有帧再到达」（因中继循环判到取消即 `break`），
                # 据此认定本参数在流式路径恒为假是正确的。那个完备性断言是错的：
                # 远端委派屏障未满足时（`:343-349`）直接产出中断帧，不经该守护；
                # 独立复核实测真 HTTP 24 组合命中 18 组，形态与最初那条缺陷逐字相同。
                # 本文件另有五处无守护的 `yield`，将来任一处产出中断帧都不必再补一次。
                #
                # 重复报告安全：报告件只记「被取消了、理由是什么」，同一理由重复置位
                # 不改变结果。
                if handle.cancelled and on_cooperative_cancel is not None:
                    on_cooperative_cancel(handle.reason)
                yield result
        finally:
            # **先报告再注销**：注销后句柄仍可读，但顺序反过来会让人以为
            # 报告读的是注销后的残值。
            # **两种理由都报告，处置交给入口层**——本层只报事实。
            #
            # 上一版只报关停这一种，于是标准协议入口的显式取消收不到信号：
            # 取消端点先落 `CANCELED`，执行侧随后照常补 `COMPLETED` 把它覆盖掉，
            # **客户端按最后一个事件读到的是「完成」**（实测事件序列末尾
            # `[... CANCELED, COMPLETED]`）。
            #
            # 两种理由的处置本就不同，但那是**入口层的判断**：
            # 自定义 REST 入口按理由决定落不落终态；标准协议入口两种都不补帧
            # （关停时不补是因为轮次未完成，显式取消时不补是因为取消端点已经落过了）。
            if handle.cancelled and on_cooperative_cancel is not None:
                on_cooperative_cancel(handle.reason)
            # 注销必须无条件执行，异常路径同样走到——句柄泄漏会让排水永远等不到零。
            self._streams.unregister(handle)

    async def _relay(self, ctx: ServeRequest, handle: StreamHandle) -> AsyncIterator[QueryChunk]:
        """中继一轮 handler 流；遇远端委派则拦截、跑批次、单次回灌后**续跑下一轮**。

        委派不上线给客户端（FEAT-004 §4.2）——它是 runtime 内部的编排事实，
        客户端看到的仍是 Agent 的内容/中断/错误——委派本身不上线。（远端成员的业务输出
        另有 `remote_agent_output` 类型承载，其转发链路尚未接线，见 ISSUE-LEDGER 的 E1b。）
        """
        pending: list = []
        async for result in self._handler.stream_query(ctx):
            if handle.cancelled:
                break  # 停止消费本流；底层的停止由中断通知契约尽力达成（FEAT-002 §4.3）
            delegation = result.delegation
            if delegation is not None:
                pending.append(delegation)
                continue  # 拦下，不投影给客户端
            yield result

        if not pending or handle.cancelled:
            return
        if self._batch is None:
            # 装配错误可见化：收到委派却没配协调器，不静默丢弃（丢弃会让 Agent 永远等不到结果）。
            _logger.error(
                "收到远端委派但未装配批次执行器：%d 个委派无法执行", len(pending)
            )
            yield QueryChunk.of_error(
                "远端编排未装配", code="REMOTE_ORCHESTRATION_NOT_CONFIGURED"
            )
            return

        # 三个标识各有用途，不可省：
        #   父会话标识 —— 多成员批次据此为各成员派生独立的远端会话标识，避免同一父会话的
        #     多个成员在远端共用一个会话而互相串扰（单成员不派生，见 member_context_id）；
        #   父任务标识 + 智能体标识 —— 定位承载批次快照的影子任务，使批次标识跨轮保持
        #     （Feat-Func-004b §5.1）。缺它则续轮重新派生、远端会话对不上。
        # 本节点的入站层级路径：**长度即当前调用深度**，路径本身要随南向报文传下去
        # （Feat-Func-004b §6.3.1.1）。取不到按首跳处理——纯本版部署下无人写入（§13 已登记），
        # 首跳本来也没有父路径，两者都不该让调用失败。
        parent_path = ctx.sub_task_path
        pending = [replace(d, parent_path=parent_path) for d in pending]
        # ── 成员中间输出的转发通道（FEAT-004；上游 `RemoteInvocationBatchCoordinator.java` 的 `replayEarlyCallbacks`
        # 的 `memberOutputObserver`、存量 `remote_agent_handler.py` 的 `_run_one_workflow` 的逐帧上抛）──
        #
        # **三条避坑写死在这里**，对应 `adapters/outbound/interruptible.py` 记录的
        # 两次实测失败：
        #
        # 1. 入队用 `put_nowait`、队列不设上限——生产者永不阻塞在入队。
        #    那条记录里的死锁正是「消费方关闭时泵还阻塞在入队」。
        # 2. 批次仍在**本协程起的任务**上跑，不把取值搬到另一个上下文——
        #    那条记录里的另一半是「令牌创建于另一个上下文」导致整条执行链失败。
        # 3. 收尾只做 `task.cancel()`，它是同步的——生成器被关闭时不能再 `await`。
        outputs: "asyncio.Queue[QueryChunk]" = asyncio.Queue()

        # 收到过发起边界帧的成员。**「已发起」不能靠 pending 推断**——
        # 深度超限与预算超限的成员根本没被发起，存量对它们一帧不发。
        # 上一版在批次异常时对全部 pending 发收敛帧，实测 1 个成员发起、3 个收敛。
        #
        # **它是每请求的局部量，不是跨请求状态**：本方法是异步生成器，每次调用各有一份，
        # 随本轮执行结束即回收；副本间不共享、也无需共享，故不存在「换个副本就查不到」
        # 这个失效形态——那正是该原则要防的东西。
        #
        # 依据是根设计的实例亲和裁定
        # （`internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-overview.md` 的 `§8.1`：事件队列、流取消句柄、临时连接表
        # 均为进程内运行态、**不得外置**，同会话续接靠 sticky routing）——
        # 本表与那三样同类：绑在这一次执行上、随它生灭。
        #
        # **不引原则表述本身**：引一条原则来豁免这条原则是自指，门禁已按此拦截。
        started: set[str] = set()

        def _forward(delegation: RemoteDelegation, chunk: QueryChunk) -> None:
            # **整帧带过去，不只带正文**。上一版只传 `chunk.content`，而远端帧的
            # 事件名、其余载荷与插件位在 `adapters/outbound/remote/client.py` 早已解析出来
            # ——在这里丢掉之后，自定义 REST 出口的内层事件名恒落到兜底值 `thought`、
            # `data` 恒空，与存量逐帧转发原始帧的形态每一帧都不同。
            # 路径同理：存量是「父路径 + 本跳标识」，只带本跳会让层级信息在出口处丢一半。
            frame = _remote_frame_of(chunk)
            # **只据事件名记账，不产出对外键集**。
            #
            # `node_start` 这个事件名是端口契约层面的约定
            # （`agent_runtime/ports/remote_batch.py` 的必须保证清单第五条写明用
            # `QueryChunk.of_event("node_start", …)` 构造），本层据它判「谁真的被发起」；
            # 而 `{"event": …, "entity_name": …}` 那套**键集**是对外 wire 形态，
            # 归一在适配层（`agent_runtime/adapters/inbound/rest/projection.py`
            # 的 `normalize_boundary_frame`）。
            #
            # 上一版在此手写对外键集——对外形态的字面量落进 application 层，
            # 与本文件自陈的分层说明正面矛盾。
            if frame.get("type") == "node_start":
                started.add(delegation.tool_call_id)
            outputs.put_nowait(
                QueryChunk.of_remote_agent_output(
                    chunk.content,
                    batch_id=ctx.task_id,
                    tool_call_id=delegation.tool_call_id,
                    target=delegation.agent_id,
                    source=frame,
                    sub_task_path=delegation.sub_task_path,
                    # **FEAT-027 的生产者标签跟着帧走**：成员帧上的标签由投射轨贴，
                    # 本层重建对外帧时不带上就在这一跳丢掉——对外表现是
                    # 「帧到了，只是看不出是哪个下游产的」。
                    agent_event=(chunk.data or {}).get("agentEvent"),
                    artifact_id=str((chunk.data or {}).get("artifactId", "") or ""),
                )
            )

        # **生命周期帧：每个成员一个 `node_start`，落定后一个 `node_end`**。
        #
        # 存量对子智能体族发这两个边界事件，共 13 个产出点分属两个函数
        #（`.legacy-oracle/applications/a2a_service/orchestrator/handlers/
        # remote_agent_handler.py` 的 `_run_one_workflow` 与 `_run_one_sub_agent`：
        # 各一个 `node_start`，`node_end` 分别有 6 种与 5 种落态）。
        # 本版此前**零产出方**——依赖 `node_end` 判子智能体是否收敛的集成方，
        # 在本版上一帧都收不到，而全仓判据与门禁对此无反应。
        #
        # **两个边界各在一处产出，不在同一层**：
        #
        # - `node_start` 由**执行件**发（`adapters/outbound/remote/batch_runner.py` 的
        #   `_lifecycle_start_chunk`，经 `on_member_output` 送来）。只有它知道谁**真正被发起**
        #   ——深度与预算超限的成员根本走不到那一行，存量对它们一帧不发。
        #   该义务已写进 `ports/remote_batch.py` 的必须保证清单第五条。
        # - `node_end` 由**本层**发：它要的是落定后的成员结果，那正是 `run_batch` 的返回值。
        #
        # 本段上一版写「时机是批次级」并对全部 pending 发 `node_start`，与同一函数里
        # 「跳过成员零帧」的规则自相矛盾；现按真实发起记账（见 `started`）。
        batch_task = asyncio.ensure_future(self._batch.run_batch(
            pending,
            parent_context_id=ctx.conversation_id,
            parent_task_id=ctx.task_id,
            # **本轮的链路标识，不是首跳那个**（需求九）。存量把它从会话快照里取
            # （`.legacy-oracle/applications/a2a_service/orchestrator/handlers/
            # remote_agent_handler.py` 的 `_drive_sub_agent`：`cached.get("trace_id", "")`），
            # 而快照只在首跳写一次——于是续轮南向带的是首轮的值，
            # 下游按它记的日志与本轮日志串不起来。这里取本次执行请求上的那一个。
            parent_trace_id=str(ctx.metadata.get("trace_id") or ""),
            # **直接取，不做属性探测**：`agent_id` 是端口 `ports/handler.py` 声明的数据成员，
            # 装配期的 `require()` 已校验处理器提供了它。此前写作
            # `getattr(self._handler, "agent_id", "") or ""`——探测落空时静默取空串，
            # 而这个值是影子任务的定位键之一：**它一变，批次快照就找不回来，且无任何信号**。
            # 端口声明过的成员用探测取，等于在契约之外又留了一条「可以没有」的路。
            agent_id=self._handler.agent_id,
            call_depth=len(parent_path),
            on_member_output=_forward,
            # **本轮的在途流句柄即协作式取消标志**：它已被上面的消费循环用作
            # 「停止消费本流」的判据（`handle.cancelled`），批次这一段此前不看它，
            # 于是取消发生在批次执行期时，远端成员照跑到完并各自归结为**完成**——
            # 存量在同一情形报 `status=cancelled`。
            #
            # 传的是同一个句柄对象，不是它的当前取值：取值在传参那一刻恒为假，
            # 传取值等于把「取消发生在批次开始之后」这一整类情形排除在外，
            # 而那恰是唯一需要它的情形。
            cancel_signal=handle,
        ))
        try:
            while True:
                drain = asyncio.ensure_future(outputs.get())
                done, _ = await asyncio.wait(
                    {batch_task, drain}, return_when=asyncio.FIRST_COMPLETED
                )
                if drain in done:
                    yield drain.result()
                    continue
                drain.cancel()  # 批次先结束：取消这次等待，转去排空剩余的帧
                break
            while not outputs.empty():
                yield outputs.get_nowait()
            outcomes = batch_task.result()
            # 落定即发收敛边界——**每个成员都发，含跳过与失败**：
            # 存量的 5 种 `node_end` 落态覆盖的正是这些，只发成功的等于让集成方
            # 在失败成员上永远等不到收敛信号。
            #
            # **成员失败与批次异常是两回事**：前者在这里照发（`outcome` 带 `failed`
            # 或 `skipped`），后者（`batch_task.result()` 自身抛出）不发——那时整条流
            # 以错误终态结束，客户端收到的是失败帧而不是「某个成员收敛了」。
            # 帧序因此恒为：**每个被发起的成员**一个 `node_start` → 过程输出 →
            # 一个 `node_end`，前者由执行件经队列送来、后者在此直接产出，
            # 两条路不会交错（队列在此之前已排空）。
            # **未被发起的成员两头都没有**（深度／预算超限，存量对它们零帧）。
            for delegation in pending:
                end_frame = _lifecycle_end_frame(outcomes.get(delegation.tool_call_id))
                if end_frame is not None:
                    yield _lifecycle_chunk(ctx, delegation, end_frame)
        except Exception as exc:
            # **批次整体异常时也发收敛边界**——存量对每个已发起的成员各发一个
            # `node_end status=failed`（`.legacy-oracle/applications/
            # a2a_service/orchestrator/handlers/remote_agent_handler.py` 的 `_drive_workflow_va`
            # 在每个成员自己的执行函数里，故批次层面的异常同样落到每个成员上）。
            #
            # 上一版这条路上一帧不发：靠 `node_end` 判子智能体是否收敛的集成方
            # 会一直等下去。发完再把异常抛出去，本轮仍以失败终态结束。
            for delegation in pending:
                if delegation.tool_call_id not in started:
                    continue  # 从未发起过的成员不发收敛边界——它没有可收敛的东西
                # **走与正常落定同一条构造**，不在此处手写对外取值。
                # 上一版这里写死 `{"status": "failed", "error": "batch aborted"}`——
                # 三样存量 wire 取值（键名、落态、情境键）完整落在 application 层，
                # 与本文件自陈的「载荷是领域落态、对外词表由适配层投射」相隔一百多行自相矛盾；
                # 且该帧因缺 `outcome` 而在投射处原样透传，适配层退化为透传。
                # 附带：`"batch aborted"` 不是存量任何一个取值——存量该位是异常原文。
                aborted = _lifecycle_end_frame(
                    MemberOutcome(
                        tool_call_id=delegation.tool_call_id,
                        outcome=OUTCOME_FAILED,
                        content=str(exc),
                    )
                )
                if aborted is not None:
                    yield _lifecycle_chunk(ctx, delegation, aborted)
            raise
        finally:
            # 父流被提前关闭时批次仍在跑——取消它。**同步调用**，
            # 生成器关闭时无法再 `await`（见上方第 3 条）。
            if not batch_task.done():
                batch_task.cancel()
        if not RemoteBatchSettlement.barrier_satisfied(outcomes):
            # 屏障未满足：有成员在等客户端输入 → 父 Task 挂起并**并列**暴露全部 pending
            # member（§4.4），不带半个批次去恢复 core。
            for member in RemoteBatchSettlement.pending_members(outcomes):
                yield QueryChunk.of_interrupt(
                    content=member.content, interaction_id=member.tool_call_id
                )
            return

        backfill = RemoteBatchSettlement.build_backfill(outcomes, pending)
        resume_request = ctx.for_resume(keyed_results=backfill)  # 单次回灌恢复 core
        async for result in self._relay(resume_request, handle):
            yield result

    #: 回调回灌是否**真的可用**。
    #:
    #: **调用方须据此判断，而不是据「有没有回灌方法」**：本类始终有那个方法
    #: （它承担「记录收到但未处理」这件事），故属性探测必然成功。两者分叉时，
    #: 接收器会对外报告已回灌，而实际只落了一行日志——投递方据此停止重试，
    #: 结果永久丢失。实测确证过这一形态。
    #:
    #: **定为可写字段而非只读属性**：装配处按注入事实置真，只读属性会让那行赋值
    #: 直接崩溃。默认假——分派需要读取任务状态，而编排层不持有任务存储
    #: （见 Feat-Func-001b §13）；接入该通道后由装配处置真。
    can_backfill_callback: bool = False

    async def accept_push_callback(self, task_id: str, payload: dict) -> bool:
        """接收完成回调的回灌（Feat-Func-001b §4.6 第五步）。

        参数 task_id：回调关联的本地父任务标识，由接收器从载荷定位后传入。
        参数 payload：回调载荷。**本层不解析其业务内容**——它是远端产出的结果表面，
            语义归产生它的那一方。

        **本方法是适配层向内的唯一回灌入口**。鉴权、受信来源校验、幂等判重、
        本地关联四步都已在接收器完成，本层只做「交回执行链路」这一件事。

        **当前为登记态**：回灌的目标执行链路取决于该任务当时在等什么——
        等远端委派结果、等客户端输入、还是已终结。这三种情形的分派需要读取任务状态，
        而编排层不持有任务存储。故本版只记录事实、不做分派，待任务状态的读取通道
        接入后补齐（见 Feat-Func-001b §13）。

        **不静默吞掉**：记录足以定位的事实，使「回调到了但没被处理」这件事可被发现。
        """
        _logger.info(
            "收到完成回调回灌：task=%s 载荷键=%s",
            task_id, sorted(payload.keys()) if isinstance(payload, dict) else "非映射",
        )
        # **返回假**：登记态没有把结果交回执行链路。返回类型与装配处注入的实现一致，
        # 两者分叉时静态检查会报——此前一处返回空、一处返回布尔，报错被忽略标记盖住了。
        return False

    async def query(self, ctx: ServeRequest) -> QueryResponse:
        """非流式路径：走**同一条编排链路**，聚合为 `QueryResponse{result, conversation_id}`。

        **返回类型对齐上游 SPI**（`openJiuwen/agent-runtime-java/service/agent-service-spec/
        src/main/java/com/openjiuwen/service/spec/spi/ServeOrchestrator.java` 的 `ServeOrchestrator`
        的 `QueryResponse query(ServeRequest request)`）与我方根设计
        （`internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-overview.md` 的 `§2.1` 把 `QueryResponse` 登记为 active、
        语义「非流式 query 聚合响应」；`:172` 把本类的契约登记为
        query/stream_query/cancel_active/reset_conversation）。

        **此前返回的是 `list[QueryChunk]`**——一串未聚合的结果块。后果是
        `QueryResponse` 这个领域对象在编排面上根本不存在，它只出现在处理器一侧；
        聚合责任被推给每个入站适配件各自实现。宿主若按上游语义嵌入本 SDK、
        调 `orchestrator.query()` 期望拿到 `{result, conversation_id}`，拿到的是一串块。

        **聚合规则不是在这里发明的**，直接用出站三个处理器共用的那一份
        （`domain/aggregate.py`）：终答优先，**没有终答就拼增量**。

        **后半句是补上的**：此前这里只取终答块，遇到「只发增量、不发终答」的
        框架就恒返回空答案——同一次执行里流式看得到全部内容、非流式拿到空串。
        这一族缺陷在入站聚合（`adapters/inbound/rest/router.py` 的 `_aggregate`）
        与出站三个处理器上都修过，**编排层这一处当时没跟上**；
        独立复核 2026-08-19 在新接入的框架适配件上实测到它。

        **走 `stream_query` 而不是直接调处理器的非流式方法**：编排层的委派拦截与
        远端回灌都在 `stream_query` 里，绕过它会让非流式路径拿到未经编排的结果。
        上游在其 A2A 版编排器里同样要在处理器返回之后再做一轮中断处置
        （`A2AEnabledServeOrchestrator.java` 的 `A2AEnabledServeOrchestrator`），两者要达成的是同一件事。
        """
        return QueryResponse(
            result=await aggregate_stream(self.stream_query(ctx)),
            conversation_id=ctx.conversation_id,
        )

    async def resume_query(
        self,
        ctx: ServeRequest,
        resume: ResumeInput,
        *,
        on_cooperative_cancel: Optional[Callable[[Optional[InterruptReason]], None]] = None,
    ) -> AsyncIterator[QueryChunk]:
        """续接路径（Feat-Func-008b §4.2）：**不新增 handler SPI**——把 ResumeInput 投影进续接
        ServeRequest（`for_resume`），重走 `stream_query`。

        **不读写任何存储**：可续接性已由调用方按 Task 状态判定，本方法只做纯投影再委派。
        **两条入站适配器现在都按 Task 状态判**——协议入口在
        `adapters/inbound/a2a/executor.py`，自定义 REST 入口在
        `adapters/inbound/rest/task_binding.py`，两侧读写同一个位置。

        上游 `A2AEnabledServeOrchestrator.tryResumePending` 对客户端交互续接同样
        直接把当前请求交回执行链路、不查等待点（`:195-198`）——**但它成立的前提是
        请求自带 `_interrupt` 标志**（`:553-558` 的 `isClientToolResume`）。
        在没有那个标志的 wire 上，「不查等待点」不能作为设计依据；
        此处此前引它时省略了这个前提。取消语义同 stream_query——
        **续接轮次同样会被关停掐断**，取消报告必须透传，否则续接那一轮仍会
        在入口层被结算成完成态。
        """
        resume_request = ctx.for_resume(
            user_supplement=resume.user_supplement,
            recovery_point_id=resume.recovery_point_id,
        )
        async for result in self.stream_query(
            resume_request, on_cooperative_cancel=on_cooperative_cancel
        ):
            yield result

    def mark_cancelled(
        self, conversation_id: str, *, reason: InterruptReason = InterruptReason.USER_REQUEST
    ) -> int:
        """只置位取消标志，不通知处理器。返回置位的流数。

        ## 这是对权威 SPI 面的方法级增补（R43）

        权威 SPI 附录只有 `void cancelActive(String conversationId)` 一个取消入口，
        **没有「只置位、不通知」这一档**；上游两个实现（默认编排器与 A2A 编排器）
        与之一致。本方法是我方新增。

        **为什么需要它**：上游的取消端点不落 Task 终态（那由取消链路自己推进），
        我方自定义 REST 入口须落（`FEAT-022` 要求复用标准取消语义），而落终态那步
        带 `await`——在那个让出点上在途轮次可能收到中断帧，却读不到取消标志。
        上游没有这个问题，所以不需要拆出置位这一档。

        **形态上是纯增补**：`cancel_active` 的签名与行为不变，本方法只是把它内部
        那一步同步置位单独暴露出来；按权威签名调用的代码照常工作。

        **同步方法，没有让出点**——这是它存在的全部理由。

        `cancel_active` 的两步里，置位是同步的、通知带 `await`。取消端点必须
        **先落 Task 终态、后取消在途流**（次序有实测依据，见其调用点说明），
        而落终态那一步本身带 `await`：在那个让出点上，在途轮次可能收到中断帧，
        此时取消标志尚未置位，入口层据它判断「本轮是否已被取消」就会判错，
        于是中断锚点照落——**取消之后会话仍显示要求输入态**。
        实测（真入口路径探针，九种让出时点）：一种命中。

        把置位提到端点最前面即可消掉这个窗口：置位是同步的，不改变
        「先落终态、后通知」这条次序，只是让在途轮次能立刻看到取消这件事。
        通知仍由随后的 `cancel_active` 发出（对已置位的流重复置位是幂等的）。
        """
        return self._streams.cancel(conversation_id, reason)

    async def cancel_active(
        self, conversation_id: str, *, reason: InterruptReason = InterruptReason.USER_REQUEST
    ) -> int:
        """协作式取消：停掉该会话下**全部**在途流，并尽力通知底层。返回置位的流数。

        ## 理由参数是对权威 SPI 面的增补（R43 标注）

        权威 SPI 附录的签名是 `void cancelActive(String conversationId)`，**无理由参数**；
        上游两个实现（默认编排器与 A2A 编排器）与之一致，理由走另一条接口——
        中断通知契约的 `interrupt(conversationId, reason)`，其关停中断件传
        `LIFECYCLE_INTERRUPT`。

        **我方为什么需要它**：那条接口通知的是**处理器**，不是入口层；而我方入口层
        要据理由决定落不落终态。上游不需要这个信息，是因为它的取消路径最终都落终态
        （实测：协作式取消经编排器折算成流的正常完成信号、落完成态；显式取消落取消态）；
        我方按上位 L1 物理视图对关停后在途 Task 的「未完成」定性**不落终态**，
        就必须能分辨是哪一种取消。

        **形态上是向后兼容的增补**：仅关键字、带默认值，按权威签名调用照常工作。


        **键必须是会话标识**（标准协议入口详设 §4.1 的约束）：入口若传任务标识，
        打到的是登记表里根本不存在的位置——取消调用成功返回而执行照跑，两端都不报错。

        两件事都要做，缺一条即为失效（权威 `CL-8617f01064a7`）：

        1. **停止消费**——按会话取出全部句柄置位，消费循环下一轮即停止迭代
        2. **尽力通知底层**——处理器若实现了中断通知契约，通知它去停底层执行；
           远端服务代理实现该契约即构成存量取消时的级联取消

        是否真能打断已进入底层模型或远端的阻塞调用，由适配器能力决定，
        **不夸大为强制中断**（权威 `:38`；强制取消属 OUT，见 `:169`）。
        """
        # **理由必须传下去**：句柄据它区分「调用方主动取消」与「进程关停」，
        # 而入口层据句柄的理由决定要不要落终态。上一版收了 `reason` 却不传，
        # 句柄拿到的永远是默认值——**本方法声明的那个参数在这条路上是空转**，
        # 生命周期中断经此入口时会被当成主动取消。独立复核实测点名。
        cancelled = self._streams.cancel(conversation_id, reason)
        # 通知即便在零在途时也发——执行可能刚结束但底层资源仍在释放中，
        # 且实现方被契约要求对未知会话做空操作。
        await notify_interrupt(self._notify_targets(), conversation_id, reason)
        return cancelled

    def _notify_targets(self) -> tuple:
        """可能实现中断通知契约的对象。

        当前只有处理器一个；远端代理经处理器链路间接接入。批次执行器不在其中——
        它在适配层，取消经端口调用链传导，本层只做句柄置位。
        """
        return (self._handler,)

    async def reset_conversation(self, conversation_id: str) -> None:
        """重置会话：取消该会话在途流 + 交处理器清理框架侧会话态（权威 SPI）。

        Task 态外置在任务状态缓存特性；框架侧会话由适配器自治清理。
        """
        await self.cancel_active(conversation_id, reason=InterruptReason.LIFECYCLE_INTERRUPT)
        await self._handler.clear_session(conversation_id)


def _lifecycle_chunk(
    ctx: ServeRequest, delegation: RemoteDelegation, frame: dict
) -> QueryChunk:
    """成员的生命周期边界帧（`node_start` / `node_end`）。

    **走与过程输出同一条帧类型**（`remote_agent_output`），两条入口各自决定投射：
    自定义 REST 的内层渲染对这两个事件名走「生命周期派」
    （`agent_runtime/adapters/inbound/rest/projection.py` 的 `render_sub_task_inner`，
    其中 `node_end` 再经 `project_end_frame` 投射成存量词表）；
    **标准 A2A 出口不发这一族**——上游 Java 两仓零此类帧，
    见 `agent_runtime/adapters/inbound/a2a/executor.py` 的 `_is_lifecycle`。

    ## 两个边界各由谁产出

    - `node_start`：**执行件**（`adapters/outbound/remote/batch_runner.py`），
      只有它知道谁真正被发起；载荷 `{"event": "node_start", "entity_name": …}`
      照存量 `.legacy-oracle/applications/a2a_service/orchestrator/
      handlers/remote_agent_handler.py` 的 `_get_sub_agent_client`。该义务已写进 `ports/remote_batch.py` 的契约。
    - `node_end`：**本层**，两个产出点——正常落定（据 `run_batch` 的结果字典）
      与批次异常（对已发起的成员各发一个）。两处都走 `_lifecycle_end_frame`，
      载荷是**领域落态**，对外词表由适配层投射。
      成员调用那一侧**不发边界帧**：协作式取消只归结落态，帧仍由本层统一产
      （上一版它在出站适配层另发一帧，那使同一族对外词表分居两个方向）。
    """
    return QueryChunk.of_remote_agent_output(
        "",
        batch_id=ctx.task_id,
        tool_call_id=delegation.tool_call_id,
        target=delegation.agent_id,
        source=frame,
        sub_task_path=delegation.sub_task_path,
    )


#: 会产出收敛边界的成员落态。**只判「发不发」，不定对外取值**——
#: `done`／`timeout`／`failed`／`cancelled` 这套词表是存量的**对外 wire 形态**，
#: 按根设计 `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-overview.md` 的 `§3.2` 的分层裁定（切口落在
#: 「碰不碰外部世界」），投射规则属适配层，本层不持有它。
#: 映射落在 `agent_runtime/adapters/inbound/rest/projection.py` 的 `_END_STATUS`。
#:
#: **两种取消分属两侧，只有一种进这张表**：
#:
#: - **宿主级撤销**（`asyncio.CancelledError`）不在这里：它是控制流信号，成员调用收到时
#:   原样抛出（吞掉会让宿主的 `wait_for`／`task.cancel()` 全部失效），根本走不到落态字典
#:   这一步，**且对外一帧不发**——取消同时拆掉了唯一的消费者，发出去的帧到不了。
#: - **协作式取消**（`cancel_signal` 置位）**就在这里**：流正常结束、消费侧仍在，
#:   成员调用把它归结为 `OUTCOME_CANCELED` 并返回，经本表判定发收敛边界，
#:   再由适配层投射成存量词表的 `cancelled`。
#:
#: 早先这里写「取消不在这里……收敛边界由 `member_caller.py` 直接送出」，
#: 那是把两种取消合成一件事的旧模型：出站适配层直接发帧那一处已被删除
#: （它把对外取值硬编码在出站侧，与其余三个落态分居两个方向），
#: 而协作式取消的链路当时尚未接通、`OUTCOME_CANCELED` 是死枚举值——
#: 那句话与紧邻的这行常量正面冲突，照它理解代码会得出相反的结论。
#:
#: **跳过与等待客户端输入不发帧**：前者存量把它拦在派发之外、一帧不发；
#: 后者是假收敛信号——该成员并没有收敛，而集成方正是靠这一帧判收敛。
_EMITS_END_FRAME = frozenset({OUTCOME_COMPLETED, OUTCOME_FAILED, OUTCOME_CANCELED})


def _lifecycle_end_frame(outcome: "MemberOutcome | None") -> "dict[str, Any] | None":
    """成员落定的边界帧体；该落态在存量上不发帧时返回 `None`。

    ## 哪些落态不发

    - **超限未发起**（`skipped`）：存量把它拦在派发之外，一帧都不发
      （`remote_agent_handler.py` 的 `_handle_delegate` 起的 `_handle_sub_agent_dispatch`，
      它与 `_handle_multi_delegate` 只把它记进跳过清单）。发了就等于凭空多出一对边界帧。
    - **等客户端输入**（`pending_input`）：存量无此落态。发 `node_end` 是
      **假收敛信号**——该成员并没有收敛，它在等输入，而集成方正是靠这一帧判收敛。
    - 拿不到落态（批次异常）：同理不发。

    ## 键集照存量

    `{event, status}` 加情境键：成功带 `content`、失败带 `error`。
    **不带 `entity_name`**——那是 `node_start` 才有的键（`:816`），
    存量的 `node_end` 一处都没有它。
    """
    if outcome is None or outcome.outcome not in _EMITS_END_FRAME:
        return None
    # **产领域落态与领域字段**，对外词表与情境键由适配层投射（见常量说明）。
    return {
        "event": "node_end",
        "outcome": outcome.outcome,
        "error_code": outcome.error_code,
        "content": outcome.content,
    }


def _remote_frame_of(chunk: QueryChunk) -> dict:
    """把远端成员的结果块还原成**远端原始帧**的形态。

    出站客户端收到远端 artifact 后拆成结果块
    （`agent_runtime/adapters/outbound/remote/client.py` 的
    `frame.pop("type"/"content"/"plugin")`，剩余键进 `data`）；本函数是那一步的逆。
    还原出的形态与存量逐帧转发的 `frame` 同构——存量把它整个作为
    `sub_task` 事件的内层载荷（`applications/a2a_service/orchestrator/handlers/
    remote_agent_handler.py` 的 `_run_one_sub_agent`），内层各键的读法见
    `.legacy-oracle/applications/a2a_service/channels/mobile_bank_channel.py` 的 `_extract_inner_meta`。

    **剩余键平铺回顶层，不留在 `data` 下**：存量的原始帧本就是平的，
    投射时 `payload.pop("type"/"content"/"plugin")` 之后剩下的才是内层 `data`。
    多包一层会让每个剩余键在对外报文里下沉一级。
    """
    payload = dict(chunk.data or {})
    nested = payload.pop("data", None)
    frame: dict = {
        "type": str(payload.pop("event_type", "") or ""),
        "content": chunk.content,
        "plugin": str(payload.pop("plugin", "") or ""),
    }
    payload.pop("content", None)
    frame.update(payload)
    if isinstance(nested, dict):
        frame.update(nested)
    return frame
