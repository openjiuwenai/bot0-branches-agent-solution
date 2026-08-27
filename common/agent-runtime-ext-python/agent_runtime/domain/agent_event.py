# coding: utf-8

"""多跳调用链上的事件标签（`CL-33b1dbf1a506`–`CL-a0de59d11945`）。

## 这是什么

A → B → C 的调用链里，C 产出的一段输出经 B、A 两跳到达客户端。**客户端要知道这段话是谁说的**——
否则并发的 B1、B2、C1 的输出交织到达时，它无法把每一帧归回调用树的正确节点。

本模块定义那个「谁说的」标签：三类事件（委派、输出、状态）共用一个结构，挂在标准 A2A
`Artifact.metadata` 的 `agentEvent` 键下，由 `type` 区分。**不新增 A2A 顶层事件类型**
（`CL-760ce1477f37` 禁止）。

## 为什么放在领域层

标签是一个**值**——一次事件的来源事实。它不知道自己会被写进 protobuf 的 Struct，也不知道
是谁在投射它；构造它的是出站投射轨，序列化它的是入站适配层，两者互不相识，只通过领域帧交换。
本模块因此只依赖标准库。

## 校验为什么在构造期

`CL-d80e43209be5` 要求生成时即满足最小公共契约。放在投射期校验**已经太晚**——那时帧已在流上，
只能丢或改，两者都是对客户端可见的异常。构造不出不合法的值，投射链路上就不必再判一次。

## wire 形态取自上游

键名、字段适用性、状态归一规则逐项对齐 `openJiuwen/agent-runtime-java` 的
`service/agent-service-app/src/main/java/com/openjiuwen/service/app/orchestrator/
RemoteInvocationBatchCoordinator.java`（`delegationMetadata` / `outputMetadata` /
`statusMetadata` / `agentRef` / `normalizeState`）。**沿用上游键名不是随大流**——
按 Java runtime 写的客户端已经按这些键在读，换个名字等于让它静默读不到标签。
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Mapping

#: 标签在 Artifact 元数据里的键（`CL-760ce1477f37`）。取自上游 `RemoteAgentCaller.AGENT_EVENT_METADATA`。
AGENT_EVENT_KEY = "agentEvent"

#: 上游状态枚举名的前缀，归一时剥掉（上游 `normalizeState`）。
_STATE_PREFIX = "TASK_STATE_"


class AgentEventType(str, Enum):
    """事件的三分。

    **这是分派维度，不是描述性标签**——`CL-16cf4a37c313` 明确禁止客户端仅依赖
    Artifact 文本内容推断事件类型，故它必须是结构化的一个字段。
    """

    DELEGATION = "delegation"
    OUTPUT = "output"
    STATUS = "status"


class AgentEventMalformed(ValueError):
    """标签不满足最小公共契约（`CL-d80e43209be5`）。

    与传输错误分开是有意的：这一类是**本地构造错误或下游协议错误**，
    须让调用方按远端协议错误处理，而不是当成网络抖动去重试。
    """


@dataclass(frozen=True)
class AgentRef:
    """调用树上的一个节点：某个 Agent 的某个 Task。

    **只有两个键**，取自上游 `agentRef`。不得增键——多出的键在按 Java runtime
    写的客户端上是未定义行为。
    """

    agent_id: str
    task_id: str

    def to_wire(self) -> dict[str, str]:
        return {"agentId": self.agent_id, "taskId": self.task_id}


@dataclass(frozen=True)
class AgentEvent:
    """一帧事件的来源标签。

    不可变：投射链路上多处读它，可变会让上游跳的标签被下游跳就地改掉，
    而「经任意中间 Runtime 不得覆盖 source」正是 `CL-017dd64585ae` 要保的。
    """

    type: AgentEventType
    source: AgentRef
    target: AgentRef | None = None
    state: str = ""

    def __post_init__(self) -> None:
        if not self.source.agent_id or not self.source.task_id:
            # 依据 `CL-d80e43209be5`。**条款号只写在注释里**：调用方拿不到那份文档，
            # 面向它的文案要说清哪个字段不对、要求是什么。
            raise AgentEventMalformed(
                f"agentEvent.source 的 agentId 与 taskId 均必填且不得为空，"
                f"实为 agentId={self.source.agent_id!r} taskId={self.source.task_id!r}"
            )
        if self.type is AgentEventType.DELEGATION:
            tgt = self.target
            if tgt is None or not tgt.agent_id or not tgt.task_id:
                # 依据 `CL-a9a2cc584ed4`。
                raise AgentEventMalformed(
                    "delegation 的 target 必填，且 agentId 与 taskId 均不得为空——"
                    "缺了它客户端无法在调用树上连出这条边"
                )
        if self.type is AgentEventType.STATUS and not self.state:
            # 依据 `CL-9d15caed46fe`。
            raise AgentEventMalformed("status 事件的 state 必填，不得为空")

    def to_wire(self) -> dict[str, object]:
        """按 type 分三档产出键集（`CL-a9a2cc584ed4`–`CL-9d15caed46fe` 的字段适用性表）。

        **「不使用」的字段不出现，而非出现为空值**——上游三个 metadata 工厂
        各自只放适用的键，客户端按键在与否分派。
        """
        wire: dict[str, object] = {
            "type": self.type.value,
            "source": self.source.to_wire(),
        }
        if self.type is AgentEventType.DELEGATION and self.target is not None:
            wire["target"] = self.target.to_wire()
        if self.type is AgentEventType.STATUS:
            wire["state"] = self.state
        return wire


def normalize_state(raw: str) -> str:
    """状态名归一：去 `TASK_STATE_` 前缀后转小写（上游 `normalizeState`）。

    本仓的 a2a-sdk 枚举名与 Java 侧同源（同一份 proto），故同一套规则成立。
    已归一的值再归一不变——透传下游已归一的 state 时会走到这条。
    """
    # **先取下标再切**：`raw[len(X) :]` 里冒号前那个空格是格式化工具对复杂切片的
    # 产物，手删会被下一次格式化加回来。取出下标后切片变简单，两边不再有分歧。
    offset = len(_STATE_PREFIX) if raw.startswith(_STATE_PREFIX) else 0
    name = raw[offset:]
    return name.lower()


def has_agent_event(metadata: Mapping[str, object] | None) -> bool:
    """这帧有没有标签。

    **判的是键在不在，不是内容对不对。** 按内容判会让多跳链路上 C1 的标签
    被 B1 判成不完整而覆盖，`CL-a0de59d11945` 的「C1 产出的 Artifact 经 B1 透传、
    经 A 透传后客户端仍看到 C1」就不成立。
    """
    return bool(metadata) and AGENT_EVENT_KEY in metadata  # type: ignore[operator]


def _ref_of(raw: object, *, field: str) -> AgentRef | None:
    if not isinstance(raw, Mapping):
        return None
    agent_id = raw.get("agentId")
    task_id = raw.get("taskId")
    if not isinstance(agent_id, str) or not isinstance(task_id, str):
        raise AgentEventMalformed(f"agentEvent.{field} 的 agentId 与 taskId 必须是字符串")
    return AgentRef(agent_id=agent_id, task_id=task_id)


def parse_agent_event(raw: object) -> AgentEvent | None:
    """解析一个 wire 形态的标签。

    返回 None 有两种情形，**都表示「本层不解析它」而非「它不合法」**：
    载荷不是映射，或 `type` 是未知类型。后者是有意的——`CL-51c7c26b17fa` 只约束已知类型，
    拒绝未知类型会让本 runtime 成为链路上的降级点（未知类型可能来自更新版本的下游）。

    抛 `AgentEventMalformed`：`type` 是已知类型但必填字段缺失（`CL-51c7c26b17fa`）。
    """
    if not isinstance(raw, Mapping):
        return None
    kind = raw.get("type")
    if not isinstance(kind, str):
        return None
    try:
        event_type = AgentEventType(kind)
    except ValueError:
        return None

    source = _ref_of(raw.get("source"), field="source")
    if source is None:
        # 依据 `CL-51c7c26b17fa`。
        raise AgentEventMalformed(
            f"agentEvent.type={kind} 缺少必填的 source（须含 agentId 与 taskId 两键）"
        )
    target = _ref_of(raw.get("target"), field="target")
    state_raw = raw.get("state")
    state = state_raw if isinstance(state_raw, str) else ""
    return AgentEvent(type=event_type, source=source, target=target, state=state)
