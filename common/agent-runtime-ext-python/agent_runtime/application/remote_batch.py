# coding: utf-8

"""远端委派批次的结算规则（Feat-Func-004b §4.6，application 层）。

## 本模块只做判定，不碰 I/O

屏障是否达成、哪些成员还在等、回灌怎么组装——这些是**用例编排规则**，不需要知道成员是
怎么跑的、结果从哪来。批次的执行（并发预算、成员派发、远端会话标识派生、跨轮快照）在
适配层，见 `adapters/outbound/remote/batch_runner.py`。

切分依据是碰不碰外部世界。总体设计的领域层边界表就落位单列了裁定：批次协调组件落
`adapters/outbound/remote/`，application 只留不碰 I/O 的结算规则。

**本模块不得依赖任何适配层类型**——依赖守门器已开穷尽模式，会实际阻断。

## 「汇聚完成」与「可回填恢复」是两个判定

不可合并：成功、失败、拒绝、超时都是结果性终态，**要求输入也算已落定**（成员不再推进，
在等客户端），故汇聚可以完成；但只要还有成员在等输入，就不可回填——半个批次去恢复
上游智能体，它拿到的是残缺结果集。

## 单委托 = size-1 批次

单调用不走另一条代码路径，规范化为 size-1 批次由同一套规则结算。两条路径会导致
单／批语义漂移。

**当前上游前置未满足（诚实记录，不是本层缺陷）**：Python agent-core 的任务管理器在首个
中断出现后取消其余并发轨且只上抛第一个中断，因此同轮不会产出多于一个委派——批次实际
恒为 size-1。按 N 实现、按 1 运行：上游具备批量中断聚合后无需改本层结构即可放开扇出。
在那之前**不得对外声称并发批次已生效**。
"""
from __future__ import annotations

import logging
from typing import Any, Iterable, Optional

from agent_runtime.domain.remote.delegation import (
    CATEGORY_PROTOCOL_ERROR,
    CATEGORY_UNAVAILABLE,
    OUTCOME_COMPLETED,
    OUTCOME_FAILED,
    OUTCOME_PENDING_INPUT,
    OUTCOME_SKIPPED,
    SKIP_REASON_CONCURRENCY,
    SKIP_REASON_MAX_DEPTH,
    MemberOutcome,
    RemoteDelegation,
)
from agent_runtime.domain.result import QueryChunk

_logger = logging.getLogger(__name__)


#: 成员会话标识的中缀，**逐字取存量**（`.legacy-oracle/applications/a2a_service/orchestrator/
#: handlers/remote_agent_handler.py` 的 `_run_one_sub_agent` 与 `_drive_sub_agent`：
#: `f"{conv_id}-sub-{entity_id}"`）。
#:
#: **不含冒号**——冒号是协议库任务标识的分段符，用它拼会让派生出来的会话标识
#: 在解析处被切错段。存量这个中缀本就不含冒号，兼容与安全在此不冲突。
MEMBER_CONTEXT_INFIX = "-sub-"


class RemoteBatchAdmission:
    """批次的**准入规则**：哪些委托被受理、被跳过的原因是什么、成员会话怎么派生。

    ## 为什么在 application 而不在适配层

    总体设计 `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-overview.md` 的 `§3.2` 裁定「批次协调组件本身的落位 =
    `adapters/outbound/remote/`；application 只留不碰 I/O 的结算规则」，
    理由逐字是「**切口落在「碰不碰外部世界」这条线上，与洋葱的分层依据同源**」。

    这三条规则**不碰外部世界**——一次整数比较、一次列表切片、一次字符串拼接，零 I/O。
    按同一条标准它们就该在这一层。此前它们在 `adapters/outbound/remote/batch_runner.py`
    里，形成的分工是：**领域层定义词汇、适配层做判断、application 只做结算**——
    中间那一步跳过了内层。**导入方向全对**，依赖守门器与 import-linter 都不会响，
    正因如此它比反向导入更难被发现。

    搬迁不需要新增任何依赖：用到的符号（`OUTCOME_SKIPPED`、两个 `SKIP_REASON_*`、
    `MemberOutcome`、`RemoteDelegation`）全在领域层，本模块本来就看得见。
    """

    @staticmethod
    def depth_exceeded(call_depth: int, *, max_call_depth: int) -> bool:
        """调用深度是否已达上限。

        **深度收敛先于并发判定**：超深的委托根本不该进入调度。反过来先判并发会让
        超深项占用预算，且产生生命周期事件——存量对超深项不产事件。
        """
        return call_depth >= max_call_depth

    @staticmethod
    def _skipped(
        delegations: Iterable[RemoteDelegation], reason: str
    ) -> dict[str, MemberOutcome]:
        """逐项归档为跳过态并带上原因。

        **跳过不是失败**——没发起过就没有失败可言。对外由投射层分流进跳过清单、
        不进结果集（Feat-Func-004b §4.5）。
        """
        return {
            delegation.tool_call_id: MemberOutcome(
                tool_call_id=delegation.tool_call_id,
                outcome=OUTCOME_SKIPPED,
                skip_reason=reason,
            )
            for delegation in delegations
        }

    @classmethod
    def skipped_for_depth(
        cls, delegations: Iterable[RemoteDelegation]
    ) -> dict[str, MemberOutcome]:
        """调用深度达限时整批的归档结果。"""
        return cls._skipped(delegations, SKIP_REASON_MAX_DEPTH)

    @classmethod
    def skipped_for_budget(
        cls, delegations: Iterable[RemoteDelegation]
    ) -> dict[str, MemberOutcome]:
        """并发与队列预算都满时，超出部分的归档结果。

        **原因的选择也在这一层**：把它做成调用方传 `reason` 的形参，等于把
        「哪种情形对应哪个原因」这条纯判定留在适配层——那正是本次搬迁要消除的形态。
        搬完之后适配层的 `OUTCOME_SKIPPED` 导入随即变成未使用，
        是这条没搬干净的直接读数。
        """
        return cls._skipped(delegations, SKIP_REASON_CONCURRENCY)

    @staticmethod
    def admit(
        members: list, *, budget: int, queue_limit: int
    ) -> tuple[list, list]:
        """三级准入判定（Feat-Func-004b §4.5），逐级下落：

        1. 并发上限内 → 立即发起
        2. 队列未满 → 排队等待
        3. 队列已满 → 进跳过清单，不发起、不产生命周期事件

        可同时在途的上限 = 并发预算 + 队列上限；超出者截断。
        返回 `(受理, 跳过)` 两段。
        """
        admitted = budget + queue_limit
        return members[:admitted], members[admitted:]

    @staticmethod
    def member_context_id(parent_context_id: str, agent_id: str) -> str:
        """派生成员调用远端时使用的会话标识——**形态逐字取存量**：`父会话-sub-目标标识`。

        ## 为什么取存量而不是对齐上游

        这是**对外可观察的 wire 值**（南向报文的 `context_id`），落在设计原则 P1
        「对外与存量逐字节等价」上。上游的形态是「单成员直传父会话、多成员三段拼接」，
        两者不同；根设计 §1.1 对这类冲突的裁定是**一律取兼容存量并登记冲突台账**
        （用户 2026-08-27 就本条另有明示裁定）。

        ## 与需求所指病灶的关系

        技术债需求六把「以主会话标识拼接实体标识作子代理会话键」列为病灶，要的是
        **按次独立的记忆命名空间**。那个能力被权威划到后续特性（`FEAT-002` 明写当前
        版本 `ServeRequest` 不承载 `stateKey`／`memoryScope`，派生规则由后续特性补齐），
        本版给不出。**存量形态与此前的形态都不满足它**——而存量形态比此前的更接近：
        此前单成员批次原样返回父会话标识（零隔离），存量形态至少按目标标识分开。

        ## 残余风险（登记，不在本版消除）

        同一批次里两个成员指向**同一个目标**时，二者派生出同一个会话标识，在远端落进
        同一个会话——这正是存量的行为，也是需求六病灶的一部分。此前的三段形态没有这个
        问题，但它是一处无裁定的对外偏离。消除它要等承载独立记忆作用域的那个特性。
        """
        return f"{parent_context_id}{MEMBER_CONTEXT_INFIX}{agent_id}"


class RemoteBatchSettlement:
    """批次结算规则。全部为静态判定，不持有状态、不做 I/O。"""

    @staticmethod
    def barrier_satisfied(outcomes: dict[str, MemberOutcome]) -> bool:
        """屏障：全部成员达稳定态（完成 / 结构化失败）才可恢复上游智能体。

        成员处于要求输入态时屏障不满足——父任务挂起等客户端输入（§4.4），
        而不是带着半个批次去恢复。

        空结果集判**未达成**：没有成员意味着批次尚未产出任何结果，
        此时回填等于拿空结果集去恢复。
        """
        return bool(outcomes) and all(o.is_settled for o in outcomes.values())

    @staticmethod
    def pending_members(outcomes: dict[str, MemberOutcome]) -> list[MemberOutcome]:
        """仍在等待客户端输入的成员。

        屏障未达成时**并列暴露全部**，不只报第一个——客户端需要一次看到所有待补充项，
        逐个暴露会让它以为补完一个就完了。
        """
        return [o for o in outcomes.values() if o.outcome == OUTCOME_PENDING_INPUT]

    @staticmethod
    def build_backfill(
        outcomes: dict[str, MemberOutcome], delegations: Iterable[RemoteDelegation]
    ) -> "dict[str, str | dict[str, Any]]":
        """构造**单次**回灌映射：上游智能体的定位键 → 成员结果值。

        **值的类型是两支的联合**：成功项是远端内容本身、非成功项是结构化结果
        （权威 `CL-1a1ec6f08169` 的 MUST，见 `MemberOutcome.to_backfill_value`）。
        此前标成 `dict[str, str]`——那是回填值还是句子时的形态。

        键做一次翻译：runtime 内部按委托关联键关联，上游智能体按**节点标识**回灌
        （逐项分发进各自的节点会话）。无节点标识的委派回退用委托关联键作键——
        回退是为不丢结果，但会落错节点，故同时告警。
        """
        by_call = {d.tool_call_id: d for d in delegations}
        backfill: "dict[str, str | dict[str, Any]]" = {}
        for tool_call_id, outcome in outcomes.items():
            delegation = by_call.get(tool_call_id)
            key = (delegation.node_id if delegation else "") or tool_call_id
            if delegation is not None and not delegation.node_id:
                _logger.warning(
                    "委派缺 node_id，回灌回退用 tool_call_id 作键：tool_call_id=%s agent=%s",
                    tool_call_id, delegation.agent_id,
                )
            backfill[key] = outcome.to_backfill_value()
        return backfill


class RemoteMemberOutcomeRules:
    """单个远端成员的结果归结规则——**纯判定，无 I/O**。

    ## 为什么在 application 而不在适配层

    根设计 `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-overview.md` 的 `§3.2` 的切口标准逐字：
    「批次协调组件本身的落位 = `adapters/outbound/remote/`；
    **application 只留不碰 I/O 的结算规则**……切口落在「碰不碰外部世界」这条线上」。

    本规则的输入是**已经消费完的结果块**，输出是一个值对象，全程不碰外部世界——
    按那条切口它属 application。此前它是 `RemoteMemberCaller._to_outcome`，
    与同族的三条准入判定（见 ISSUE-LEDGER 的 D13）一起落在适配层，
    **D13 搬走了那三条、漏了这一条**。
    """
    @staticmethod
    def classify(
        delegation: RemoteDelegation,
        texts: list[str],
        error: Optional[QueryChunk],
        interrupt: Optional[QueryChunk],
        final_text: str = "",
        *,
        terminal_seen: bool = False,
    ) -> MemberOutcome:
        """把消费到的结果块归结为一个成员结果。

        **判定顺序即优先级**，不可调换：

        1. **错误优先于中断**——远端先要求输入、随后失败时，最终事实是失败
        2. **中断优先于完成**——有中断说明远端在等输入，此时的文本是提示不是结果
        3. **无终止信号判协议错误**——流静默结束，既没错误也没中断也没内容，
           说明远端没按契约给出终态。**不判完成**：把「什么都没说」当作成功，
           会让父智能体拿到一个空结果继续推理

        完成时的内容**取终答，不拼增量**（§8.2.0）——终答帧在本版是双写的
        （既作 artifact、又随终态），一起拼会让同一段文本出现两次。
        远端未给终答时回落为增量拼接：那说明流以别的方式结束，
        增量是唯一可得的内容，取它比交空手好。
        """
        if error is not None:
            return MemberOutcome(
                tool_call_id=delegation.tool_call_id,
                outcome=OUTCOME_FAILED,
                content=error.message or "",
                error_code=error.code or CATEGORY_UNAVAILABLE,
            )
        if interrupt is not None:
            return MemberOutcome(
                tool_call_id=delegation.tool_call_id,
                outcome=OUTCOME_PENDING_INPUT,
                content=interrupt.content or "",
                error_code="INPUT_REQUIRED",
            )
        if not final_text and not texts:
            if terminal_seen:
                # **收到终态、正文为空 = 完成**，不是协议错误（Eco-r9-A，2026-08-25）。
                #
                # 上游 `A2ARemoteAgentClient` 的 `handleOutcomeEvent` 逐字如此：
                # 终态是 `TASK_STATE_COMPLETED` 时 `taskText.isBlank() ? statusText : taskText`，
                # 而**状态原样带出**（`resultCategory(COMPLETED)` 返回 `"COMPLETED"`），
                # 回落后仍为空也不改判。存量同法：COMPLETED 恒正常返回。
                #
                # 上一版把「没有终态」与「有终态但正文空」压进同一个分支一并判失败，
                # 是**本版比存量与 Java 都严**的反向偏离——按对外兼容优先，改为对齐两者。
                return MemberOutcome(
                    tool_call_id=delegation.tool_call_id,
                    outcome=OUTCOME_COMPLETED,
                    content="",
                )
            return MemberOutcome(
                tool_call_id=delegation.tool_call_id,
                outcome=OUTCOME_FAILED,
                content="远端流结束但未产出任何结果",
                error_code=CATEGORY_PROTOCOL_ERROR,
            )
        return MemberOutcome(
            tool_call_id=delegation.tool_call_id,
            outcome=OUTCOME_COMPLETED,
            content=final_text or "".join(texts),
        )
