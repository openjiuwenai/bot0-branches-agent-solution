# coding: utf-8

"""远端编排 SPI（FEAT-004 §2.3，§8 待补 ⑥；命名对齐在途 Java #110）。

两个端口把**具体客户端绑定**挡在 application 之外：

- `RemoteAgentCaller`：以委派驱动一次远程 A2A 调用/续接，产 `QueryChunk` 流——
  隔离 a2a-sdk / A2A 客户端实现；
- `RemoteAgentCardResolver`：拉 Card + URL 归一 + 提取工具描述——隔离 httpx。

依赖倒置的实际收益：批次协调器（application）只认这两个 Protocol，可在裸环境用桩测试；
换 A2A 客户端实现或 HTTP 库不触碰编排逻辑（P5 领域核心内聚·依赖倒置）。

以 `Protocol` 定义而非 ABC：adapter 可显式继承拿编译期校验，也允许鸭型满足——
后者是给宿主注入自有实现留的口子，与 `AgentHandler`/`RuntimeRedisClient` 同构。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import (
    Any,
    AsyncIterator,
    Callable,
    Mapping,
    Optional,
    Protocol,
    Sequence,
    runtime_checkable,
)

from agent_runtime.domain.result import QueryChunk


@runtime_checkable
class CancellationSignal(Protocol):
    """协作式取消标志：本次调用是否已被要求停止。

    权威 FEAT-002「协作式取消」MUST 条款规定取消「通过 `QueryStreamObserver.isCancelled()`
    轮询实现，adapter 在流式循环中定期检查该标志」——**标志的载体是随调用传入的参数**。
    上游 `RemoteAgentCaller.callOutcome(RemoteCall, QueryStreamObserver, Consumer<String>)`
    的第二参正是该 observer；本仓流式 idiom 取 `AsyncIterator`（决策 A，不照搬 observer），
    `isCancelled()` 因此失去落点，本协议把它放回参数位。

    **只读，不提供置位方法**：置位是取消发起方的权力（编排层的在途流登记表），
    被调用方只查不改。权威同条亦明确 Handler 无独立 `cancel()` 入口。

    承诺强度按权威同条：**至少阻止 runtime 继续消费本次执行结果**；能否立即中断底层
    远端执行由实现能力与轮询窗口决定，不夸大为强制中断。
    """

    @property
    def cancelled(self) -> bool:
        """本次调用是否已被要求停止。

        **声明成只读 property 而非可变属性**：可变属性要求满足方同样可写，
        编排层的在途流句柄（`application/active_streams.py` 的 `StreamHandle`）
        把它做成只读 property 正是为了「置位是发起方的权力」，
        声明成可变会把那个句柄判为不满足本协议——而它恰是生产上唯一的实参。
        只读声明两种形态都收：普通属性满足只读要求，只读 property 也满足。
        """
        ...


@dataclass(frozen=True)
class RemoteArtifact:
    """下游送来的一帧产物。

    **只取投射需要的四样**，不含传输层类型——它是端口契约的一部分，
    让它认识协议库的形态会把 wire 结构渗进内层。
    """

    task_id: str
    artifact_id: str
    #: 业务正文。**标 `object` 而不是 `Any`**：它确实不透明（下游可以放文本、
    #: 结构化数据或工具输出），但不透明不等于放弃类型检查——`Any` 会让检查器
    #: 对后续每一次使用都放行，`object` 则要求先窄化才能当具体类型用。
    content: object = ""
    metadata: Mapping[str, Any] = field(default_factory=dict)


@runtime_checkable
class RemoteEventProjector(Protocol):
    """远端事件的投射器（FEAT-027）。

    实现件在适配层（`adapters/outbound/remote/agent_event_rail.py`），
    端口这一层只声明调用方需要的方法面——**不写 `Any`**：用 `Any` 声明协作者，
    等于内层对它没有任何要求，而依赖倒置要的正是「内层规定契约、外层来满足」。
    """

    def observe_remote_task(self, remote_task_id: str) -> list[QueryChunk]:
        """捕获下游任务标识，必要时产出委派边。"""
        ...

    def project_artifact(self, artifact: RemoteArtifact) -> Optional[QueryChunk]:
        """投射一帧下游产物；被终态保护挡下时返回 None。"""
        ...

    def observe_status(
        self, task_id: str, raw_state: str, *, parts: Sequence[str] = ()
    ) -> list[QueryChunk]:
        """投射一帧下游状态；被去重或终态保护挡下时返回空列表。"""
        ...


@runtime_checkable
class RemoteAgentCaller(Protocol):
    """远端调用 SPI：一次委派 → 一条远端结果流。"""

    def call(
        self,
        agent_id: str,
        message: str,
        *,
        context_id: str = "",
        metadata: Optional[dict] = None,
        session_context: Optional[dict] = None,
        on_remote_task_id: Optional[Callable[[str], None]] = None,
        cancel_signal: Optional[CancellationSignal] = None,
        event_rail: Optional[RemoteEventProjector] = None,
    ) -> AsyncIterator[QueryChunk]:
        """发起一次远端调用，产结果流。

        参数 event_rail：本次调用的事件投射轨（FEAT-027），由编排层按委派建后传入。
        类型是本模块声明的协议——**内层规定契约、外层来满足**，不是反过来。
        传 None 时实现方的行为与该特性引入前逐帧一致。

        **参数是展开的调用坐标，不是委派对象**：与上游的调用坐标记录
        （`openJiuwen/agent-runtime-java` 的 `RemoteCall`）字段一一对应——
        目标标识、消息、上下文标识、元数据。委派对象是编排层的概念，
        端口这一层收的是调用要素本身。

        **流式而非单值**：远端的进展与产物需投影为父任务的产物（§2.1），
        聚合成单值会把中间进展丢掉。

        参数 session_context：会话上下文五字段，原样透传给传输件构造数据片段。
        本端口不规定它从哪来——取数在更上层、构造在传输层。

        参数 on_remote_task_id：远端任务标识的观察者。**实现方须在标识一知道时就通知它**，
        以便批次协调件把它持久化下来供续接与回调认领使用。

        对齐上游同名接口：其方法第三个参数即为该观察者，其 MUST 条款写明
        「一知道远端任务标识就通知观察者，以便批次协调件为续接持久化它」
        （`openJiuwen/agent-runtime-java` 的 `RemoteAgentCaller.callOutcome`）。

        **为什么是回调参数而不是调用后读属性**：读属性要求实现方把标识存成自己的状态，
        并发调用时后一次会覆盖前一次——本仓实测过这个竞态（两个父任务并发跑批次时，
        成员被登记进对方的影子任务）。回调随调用发生，天然无此问题。

        参数 cancel_signal：本次调用的协作式取消标志（`CancellationSignal`）。
        **实现方须在流式循环里定期检查它**，置位后停止继续消费远端结果并结束本条流；
        级联通知远端（如发 CancelTask）属尽力而为。不传即本次调用不可协作式取消。

        **同一个理由，同一个形态**：取消标志与上面的标识观察者一样按调用传递，而非挂在
        实现件的实例属性上。挂实例属性时并发调用相互覆盖（本仓的标识竞态即此形态），
        且**它不在本端口的方法面上**——按本端口写出来的实现不会有那个属性，
        消费方对它做属性探测就会恒取默认值，把「取消」静默读成「正常结束」。
        """
        ...

    # **不设 `resume` 与 `cancel`**——此前有过，是本实现自造的契约面，上游没有。
    #
    # 定向续接：上游经调用坐标携带任务标识走**同一个入口**
    # （`openJiuwen/agent-runtime-java` 的 `RemoteCall` 记录含 `taskId` 字段），
    # 不另设方法。另立一个续接方法等于在权威之外多一条契约，而它无人实现——
    # 端口空挂正是这么来的。
    #
    # 取消：**不设发起方法，但取消状态是契约的一部分**（`call` 的 `cancel_signal` 参数）。
    # 两种取消分属两处，此前被合成一句「本就不需要端口方法」，漏掉了后者：
    #
    # - **宿主级撤销**——消费侧停止迭代（`asyncio.CancelledError`）。它是控制流信号，
    #   由语言运行时传导，确实不需要任何端口面。上游对应物是取消返回的 future
    #   （同仓 `A2ARemoteAgentClient` 的 `submitted.cancel`）。
    # - **协作式取消**——流**正常结束**、消费侧仍活着，被调用方要据标志把这条流
    #   与「远端静默结束」区分开。上游对应物是 `callOutcome` 的第二参
    #   `QueryStreamObserver`，权威 FEAT-002「协作式取消」MUST 写明取消经其
    #   `isCancelled()` 轮询实现。本仓取 `AsyncIterator` 换掉了 observer，
    #   该标志因此改由 `cancel_signal` 参数承载。
    #
    # 发起取消不在本端口：置位方是编排层的在途流登记表，被调用方只查不改。


@runtime_checkable
class RemoteAgentCardResolver(Protocol):
    """Card 解析 SPI：拉取 + URL 归一 + 提取描述面。"""

    async def fetch(self, base_url: str) -> Optional[dict]:
        """拉远端 Card，返回 ``{name, description, skills:[...]}``；不可达/失败 → ``None``。

        返回 ``None`` 而非抛出：Card 初次解析失败时 URL 保持 pending、不注入工具，
        **本地 Agent 正常启动**（§4.5）——远端不可达不该拖垮本地。
        """
        ...


def normalize_card_url(base_url: str) -> str:
    """URL 归一（§2.3 Card 解析职责的一部分）：去尾斜杠，保证拼接不产生双斜杠。

    放在 ports 模块是因为它是**契约的一部分**——不同 resolver 实现必须归一到同一形式，
    否则同一远端会因写法差异（有无尾斜杠）在目录里占两个位置。
    """
    return (base_url or "").rstrip("/")
