# coding: utf-8

"""委派中断与影子任务（FEAT-004 §4.2/§4.4，§8 待补 ②③；纯 domain，零框架依赖）。

**委派中断** `RemoteDelegation`：LLM 调远端工具占位 → 中断轨拦截 → 转为委派中断，携
`tool_call_id` / `agent_id` / `tool_name` / `arguments`。真正的 A2A 调用由 runtime 编排层
承接，**不**在 agent-core 工具线程内同步阻塞（§4.2，对齐 Feat-Func-026 §1.1）。

**影子任务不在本层**：它是任务存储里的一条 Task 记录（根设计 §3 裁定），
其载体是协议库的任务对象加元数据快照，不是领域对象。曾有过一个同名领域类，
产品代码零使用、连它作为成员结果字段的那个位置也从未被读写；
它持有远端任务标识与等待目标——**正是「领域对象不承载需持久化状态」要排除的那类**。
远端返回等待输入时保存
`waiting_target=REMOTE_AGENT` + `remote_task_id` + `remote_context_id`（按 `tool_call_id`），
供续轮定向、查询、取消与诊断（§4.4）。父 Task **不新增状态**——影子任务是关联事实，不是状态机。

**两套标识的桥接（关键）**：runtime 的关联身份是 `tool_call_id`（贯穿中断/远端状态/结果/
用户输入/回灌，§2.1）；而 agent-core 的回灌按 **node_id** 定位（`InteractiveInput.user_inputs`
按 node_id 逐项分发进 NodeSession）。二者不是同一个标识，故 `RemoteDelegation` 同时持有
`node_id`——runtime 内部一律用 `tool_call_id` 关联，只在回灌那一刻翻译为 node_id。
把二者混为一谈会导致回灌落错节点。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Optional

#: 影子任务的等待目标：远端 Agent（区别于等待本地用户输入）
WAITING_TARGET_REMOTE_AGENT = "REMOTE_AGENT"


@dataclass(frozen=True)
class RemoteDelegation:
    """一次远端委派（中断轨拦截 ToolCall 的产物）。"""

    tool_call_id: str
    agent_id: str
    tool_name: str = ""
    arguments: dict[str, Any] = field(default_factory=dict)
    node_id: str = ""  # agent-core 回灌定位用；runtime 内部关联一律用 tool_call_id
    #: 本节点的入站层级路径（Feat-Func-004b §6.3.1.1）。**南向发出时在其后追加本次目标标识**——
    #: 不追加则下游算出的深度恒为 1，整条链路的深度收敛全部失效且不报错。
    parent_path: tuple[str, ...] = ()

    @property
    def sub_task_path(self) -> tuple[str, ...]:
        """南向要写出的层级路径：父路径 + 本次目标标识。

        存量的合成规则同此（`openJiuwen/agent-runtime/applications/a2a_service/
        orchestrator/handlers/remote_agent_handler.py` 的 `_run_one_sub_agent`：
        `path = 父路径 + [本次标识]`）。元素取**被调方**的标识——
        存量智能体族追加实体标识，本版的委派以智能体标识为目标身份，语义位置相同。
        """
        return (*self.parent_path, self.agent_id)

    @property
    def query(self) -> str:
        """取委派的查询文本——占位工具的入参约定（§4.1 占位工具 schema 自 query 抽取）。"""
        value = self.arguments.get("query", "")
        return value if isinstance(value, str) else str(value)


#: 成员结果的类别——屏障判定用（§4.3：稳定态 = COMPLETED 或结构化失败）
OUTCOME_COMPLETED = "completed"
OUTCOME_FAILED = "failed"
OUTCOME_PENDING_INPUT = "pending_input"
#: 被**协作式取消**的成员。**与失败分开、也与宿主级撤销分开**：
#: 存量对协作式取消发的收敛落态是 `cancelled`（`.legacy-oracle/applications/
#: a2a_service/orchestrator/handlers/remote_agent_handler.py` 的 `_drive_sub_agent`，
#: 落帧后返回结果让批次继续），与 `failed`／`timeout` 并列。
#: 宿主级撤销（`asyncio.CancelledError`）不走这条——那时整条流被拆掉，
#: 帧到不了对外，成员调用对它只抛不发。
OUTCOME_CANCELED = "cancelled"

#: 超限未发起。**不是失败**——没有发起过，也就没有失败可言；
#: 它对外表现为跳过清单里的一项，不进结果集（Feat-Func-004b §4.5）。
OUTCOME_SKIPPED = "skipped"


#: 远端未给出任何终止信号时的类别。**不与超时混用**——超时是本地判定的，
#: 流静默结束是远端行为，两者的排查方向完全不同。
#:
#: **与 `OUTCOME_*` 同族、同处**：它们都是成员结果的对外取值。此前这两个常量
#: 定义在适配层，而归结规则搬到应用层后就够不着了（应用层不得反向依赖适配层）。
CATEGORY_PROTOCOL_ERROR = "REMOTE_PROTOCOL_ERROR"

#: 兜底类别：既非超时、限流、拒绝，也不是业务失败。
CATEGORY_UNAVAILABLE = "REMOTE_UNAVAILABLE"
#: 超时类别。**与兜底类别分开**：存量对成员超时报的落态是 `timeout`，与 `failed` 是两个值
#: （`.legacy-oracle/applications/a2a_service/orchestrator/handlers/
#: remote_agent_handler.py` 的 `_drive_workflow_va` 对超时与对其他异常两处分别发不同的 `node_end`）。
#: 归到一个类别里，对外的收敛边界就只剩一种失败，按存量词表分流的集成方分不出这两种。
CATEGORY_TIMEOUT = "REMOTE_TIMEOUT"
#: 本地配置错误类别：**与远端故障可分辨**（`CL-73a637c85d35`）。
#:
#: 用于「本次委派没有配置 `agentName`」这一类——权威末句禁止用 tool name
#: 回退填补 `source.agentId`，故配置缺失时不生成 delegation、不注入 outbound、
#: 不向父流投射。
#:
#: **不能归进 `CATEGORY_UNAVAILABLE`**：那是远端不可用，运维会去查远端；
#: 而这里远端好好的，坏的是本地配置。两者归一类时，排查方向从第一步就错了。
CATEGORY_LOCAL_CONFIG_ERROR = "LOCAL_CONFIG_ERROR"
#: 跳过原因：并发与队列都满。存量对超限项用的就是这个值。
SKIP_REASON_CONCURRENCY = "concurrency_limit"
#: 跳过原因：调用深度超限。
SKIP_REASON_MAX_DEPTH = "max_call_depth"

#: 跳过原因的可读文案，用于回灌给父智能体的交代。
#:
#: **原因值本身不改**——它是存量跳过清单里 `reason` 键的取值，属对外形态
#: （`.legacy-oracle/applications/a2a_service/orchestrator/handlers/remote_agent_handler.py` 的 `_handle_multi_delegate`
#: 与 `_handle_delegate`）。此处只是给它配一句父智能体读得懂的话，不动原值。
_SKIP_REASON_TEXT = {
    SKIP_REASON_CONCURRENCY: "并发与队列预算已满，本次委派未发起",
    SKIP_REASON_MAX_DEPTH: "调用深度已达上限，本次委派未发起",
}


@dataclass(frozen=True)
class MemberOutcome:
    """批次成员的结果（按 tool_call_id 归档，并发完成任意顺序）。"""

    tool_call_id: str
    outcome: str
    content: str = ""
    error_code: str = ""
    #: 跳过原因。仅 `skipped` 态携带，其余态恒空串。
    skip_reason: str = ""
    #: 本成员的耗时（毫秒）。**未发起过的恒 0**——0 表达「没跑过，所以没耗时」
    #: （Feat-Func-004b §8.2.1，取存量对未执行项的表示）。
    elapsed_ms: int = 0

    @property
    def is_settled(self) -> bool:
        """是否达稳定态——`pending_input` 不满足屏障（父 Task 挂起等客户端输入，§4.3）。

        **跳过态算稳定**：它已有终局交代（不会再发起），屏障不必等它。
        不算稳定会让一个从未发起的成员把整个批次挂住。
        """
        return self.outcome in (
            OUTCOME_COMPLETED, OUTCOME_FAILED, OUTCOME_SKIPPED, OUTCOME_CANCELED
        )

    @property
    def is_skipped(self) -> bool:
        """是否为超限未发起项——对外进跳过清单，不进结果集。"""
        return self.outcome == OUTCOME_SKIPPED

    def to_backfill_value(self) -> "str | dict[str, Any]":
        """回灌给 agent-core 的值。

        **返回类型显式写成两支的联合，不用裸 `Any`**：成功项回远端内容本身（字符串），
        非成功项回结构化结果（映射）。写 `Any` 会把「调用方要分辨这两支」这件事抹掉，
        而分辨它正是父 Agent 该做的第一件事。架构守门器 `tools/arch_guard.py` 拦过一次。

        失败**作为工具结果回灌**而非抛出——异常 outcome 由 Agent 判断如何应对，
        父 Task 不因单成员失败自动失败（§4.5 失败二分）。

        ## 非成功项回**结构化结果**，不回人类可读字符串

        权威 `Technical-AF/docs/develop/02-features/`CL-1a1ec6f08169``
        是 MUST，逐字：「同批次中部分下游调用失败、拒绝或超时时，runtime 必须保留成功项
        和失败项，并把它们作为对应 `toolCallId` 的**结构化结果**回填」。

        修前回的是 `[远端调用失败] 超时` 这类句子。**父 Agent 拿到一句话无法按错误码
        分类处理**——它只能做字符串匹配，而文案一改匹配就失效，且失效时没有任何信号。

        字段名取自上游同一位置：
        `openJiuwen/agent-runtime-java/service/agent-service-app/src/main/java/
        com/openjiuwen/service/app/orchestrator/RemoteInvocationBatchMapper.java` 的 `interruptItems`
        的 `{ok:false, code, message, remoteAgentId}`；同文的 `interruptItems` 成功时原样返回远端结果，
        本方法同形（`return self.content`）。

        **`remoteAgentId` 暂缺**：本值对象不持有智能体标识，补它要加领域字段并改构造点，
        属另一次改动，已在 `internal/ledger/ISSUE-LEDGER.md` 的 L-6 条目登记为遗留。

        **框架侧无阻碍**：`openjiuwen/core/session/interaction/interactive_input.py` 的 `update`
        的 `update(node_id: str, value: Any)` 接受任意值，`user_inputs` 是 `Dict[str, Any]`。
        """
        if self.outcome == OUTCOME_FAILED:
            return {
                "ok": False,
                "code": self.error_code or "REMOTE_FAILED",
                "message": self.content or "Remote invocation failed",
            }
        if self.outcome == OUTCOME_SKIPPED:
            # **不能回空串**。空串与「远端确实回了空」在父智能体看来一模一样，
            # 而这两件事的应对完全不同——一个该重试或降级，一个该照结果继续。
            #
            # 权威 `Technical-AF/docs/develop/02-features/`CL-c243bd034a8f``
            #「并发预算不足时，runtime 可以排队部分调用，但不得丢弃委托或**静默改写结果**」；
            # 同文的 `CL-1a1ec6f08169`（MUST）要求部分成员失败、拒绝或超时时，把成功项与失败项
            # 作为对应关联键的**结构化结果**回填。回一个空串两条都不满足。
            #
            # 上游对超限成员给的是带错误码的失败：
            # `openJiuwen/agent-runtime-java/service/agent-service-app/src/main/java/
            # com/openjiuwen/service/app/orchestrator/RemoteInvocationCoordinatorState.java`
            # 的 `RemoteInvocationCoordinatorState`
            # 的 `fail(FAILED, "REMOTE_OVERLOADED", "Remote invocation queue is full")`，
            # 队列等待超时同样如此（`:154-155`）。**我方保留跳过态、只补交代**——
            # 跳过态是存量语义（`applications/a2a_service/orchestrator/handlers/
            # remote_agent_handler.py` 的 `skipped_entities` 带 `reason`），
            # 改成失败态会让存量的跳过清单无处安放。
            #
            # **跳过态同样结构化**，且 `code` 保留跳过原因而非并入失败码——
            # 上条裁定的「保留跳过态」正是靠这个码位存活的。
            reason = _SKIP_REASON_TEXT.get(self.skip_reason, self.skip_reason)
            return {
                "ok": False,
                "code": self.skip_reason or "REMOTE_SKIPPED",
                "message": reason or "远端调用未发起",
            }
        return self.content


#: 批次快照里成员条目的五个字段名。**字段名对齐上游的快照结构**，
#: 故用驼峰而非本仓其余处的下划线——它们是落盘形态的一部分，改名即改存储形态。
MEMBER_TOOL_CALL_ID_FIELD = "toolCallId"
MEMBER_REMOTE_TASK_ID_FIELD = "remoteTaskId"
MEMBER_SETTLED_FIELD = "settled"
MEMBER_CALLBACK_FIELD = "callback"


@dataclass(frozen=True)
class BatchMemberEntry:
    """批次快照里的一条成员条目。

    ## 它为什么在领域层

    这条结构此前是裸 dict，字段名散成五处字符串字面量（其中 `settled`、`callback`、
    `parentTaskId` 连常量都没有），读侧全靠 `isinstance(m, dict)` 加 `.get()`——
    **字段名写错要到运行期才现形**，而现形的方式是「认领恒假」「回调丢失」
    这类不产生错误信号的失效。

    放领域层而不是适配层：`pyproject.toml` 的 mypy overrides 只对
    `agent_runtime.domain.*` 与 `agent_runtime.ports.*` 开 `disallow_untyped_defs`，
    **类型定义落在领域层才会被强制标注管住**；落在适配层等于没做。

    ## 它不改变落盘形态

    `to_dict` 产出的键与顺序与此前逐字一致，`from_dict` 容忍任何缺字段与错类型
    （落盘的旧数据、别的写入方留下的条目都要读得回来）。**类型化只改内部表示。**
    """

    tool_call_id: str
    remote_task_id: str = ""
    settled: bool = False
    #: 已落定成员的回调摘要。`None` 表示「没有」，与「有一个空载荷」不同——
    #: 后者在落盘形态里是 `callback: null`，读回时仍要能分辨。
    #:
    #: **标 `object` 不标 `Any`**：写侧产出的是摘要字典
    #: （`adapters/outbound/remote/batch_runner.py` 的 `_payload_digest`），
    #: 但读侧要容纳落盘里的任意形态（旧版本、别的写入方）。`object` 表达
    #: 「不由本层解释」的同时仍禁止随意调用它的方法，`Any` 会把那道检查也抹掉。
    callback: object = None
    has_callback: bool = False

    @classmethod
    def from_dict(cls, raw: object) -> Optional["BatchMemberEntry"]:
        """从落盘的条目还原；形状不符返回 `None`。

        **不抛**：快照里可能混有别的写入方留下的东西，或旧版本的形态。
        一条读不懂的条目应当被跳过，而不是让整次认领失败。
        """
        if not isinstance(raw, dict):
            return None
        tool_call_id = raw.get(MEMBER_TOOL_CALL_ID_FIELD)
        if not isinstance(tool_call_id, str) or not tool_call_id:
            return None
        remote_task_id = raw.get(MEMBER_REMOTE_TASK_ID_FIELD)
        return cls(
            tool_call_id=tool_call_id,
            remote_task_id=str(remote_task_id) if remote_task_id is not None else "",
            settled=bool(raw.get(MEMBER_SETTLED_FIELD)),
            callback=raw.get(MEMBER_CALLBACK_FIELD),
            has_callback=MEMBER_CALLBACK_FIELD in raw,
        )

    def to_dict(self) -> dict[str, object]:
        """产出落盘形态。**键与顺序与类型化之前逐字一致。**

        `callback` 只在原本就有时才写——此前的实现也是条件写入
        （`if "callback" in previous`），无条件写会给从来没有回调的成员
        凭空加一个 `callback: null` 字段。
        """
        out: dict[str, object] = {
            MEMBER_TOOL_CALL_ID_FIELD: self.tool_call_id,
            MEMBER_REMOTE_TASK_ID_FIELD: self.remote_task_id,
            MEMBER_SETTLED_FIELD: self.settled,
        }
        if self.has_callback:
            out[MEMBER_CALLBACK_FIELD] = self.callback
        return out

    def matches_remote_task(self, remote_task_id: str) -> bool:
        """本条目是否对应给定的远端任务标识。

        **空标识不匹配任何东西**：尚未拿到远端标识的成员其字段是空串，
        用空串去匹配会认领到一个还没发出去的成员。
        """
        return bool(self.remote_task_id) and self.remote_task_id == remote_task_id
