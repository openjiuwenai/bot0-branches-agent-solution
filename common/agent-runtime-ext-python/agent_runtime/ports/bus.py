# coding: utf-8

"""FEAT-017 的四个端口。

## 这一层的唯一约束：broker 概念挡在外面

权威 `Technical-AF/docs/develop/02-features/`CL-fb7d2e87b0a9``
是 MUST——「runtime 特性文档、**接口**和业务 handler 不得依赖 RocketMQ、Kafka、topic、
offset、consumer group、broker retry 或 outbox 表」。**「接口」逐字在列**，所以这份文件
是那条 MUST 的主要落地位置。

具体做法：端口方法收**中立事件信封**，不收 broker 的消息对象；确认动作经
`acknowledge(message_id, tenant_id)` 表达，不暴露 offset 或 ack 句柄。依赖一旦从这里
漏进来，换 broker 就要改领域代码——而那正是这条 MUST 要防的事。

判据 `agent_runtime/tests/test_bus_ports.py` 的 `TestPortsHaveNoBrokerConcepts`
按 AST 扫本文件的参数名与类型标注。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import (
    Any,
    AsyncIterator,
    Mapping,
    Optional,
    Protocol,
    Tuple,
    runtime_checkable,
)

from agent_runtime.domain.bus.admission import (
    AdmissionKey,
    AdmissionOutcome,
    AdmissionRecord,
)
from agent_runtime.domain.bus.envelope import BusEventEnvelope
from agent_runtime.domain.bus.event_types import EventFamily
from agent_runtime.domain.bus.projection import BusProjection


@runtime_checkable
class BusDeliveryPort(Protocol):
    """总线投递侧：取事件、确认、拒收。"""

    def poll(self, *, max_items: int) -> AsyncIterator[Mapping[str, Any]]:
        """取待处理事件的**原始映射**。

        **返回原始映射而不是已解析的信封**：解析要按本 runtime 的 schema 版本与
        服务身份来判，那两项是装配期的事实，投递侧不知道。让它解析等于把校验规则
        复制到适配层，而校验顺序是有语义的（`CL-7dfb2059624d`）。

        上限由调用方给，不在端口内部决定——它是背压闸的一部分。
        """
        ...

    async def acknowledge(self, message_id: str, *, tenant_id: str) -> None:
        """确认这条投递已被本 runtime 收下。

        **收下不等于做完**（`CL-212432c8c82b`）。带租户是因为投递去重键含它
        （`租户 + 消息标识 + 消费方服务标识`），实现侧据此定位。

        **签名里没有 offset、没有 ack 句柄、没有 broker 客户端**——见模块文档。
        """
        ...

    async def reject(self, message_id: str, *, tenant_id: str, requeue: bool) -> None:
        """不确认。`requeue=True` 表示「本 runtime 此刻处理不了，请重投」。

        **确定性拒绝走 `acknowledge` 不走这里**：那种消息重投多少次结果都一样，
        不确认只会让它反复投递直到进死信队列（详设 §4.1）。
        """
        ...


@dataclass(frozen=True)
class BridgeOutcome:
    """控制面桥的返回。

    **定义在端口这一侧**：它是 `ControlPlaneBridge.dispatch` 的返回类型，
    放在适配层会让端口反向依赖适配层——那正是依赖倒置要防的方向。

    字段 stream_ref：**只有订阅类事件带它**。创建类事件带上会让调用方以为流已就绪，
        而那时 Task 可能刚建、还没产出任何东西——`CL-de9a9ccdceb6` 明写接受与流准备
        必须分离。
    """

    task_id: str
    created: bool
    stream_ref: str = ""
    payload: Optional[Mapping[str, Any]] = None


@runtime_checkable
class ControlPlaneBridge(Protocol):
    """事件 → 标准 A2A 请求 → 标准入口。

    **用例层只认这一个方法**：它不知道背后是 a2a-sdk 还是别的什么，
    那是适配层的事。此前这个参数标的是裸 `Any`——静态检查看不见这条依赖边，
    换实现时方法名写错要到运行期才知道（同仓 `ports/session.py` 因同样理由
    把会话存储收成协议）。
    """

    async def dispatch(
        self, envelope: BusEventEnvelope, *, task_id: str
    ) -> "BridgeOutcome":
        """把一条事件送进标准入口，返回任务标识与流引用。"""
        ...


@runtime_checkable
class BusResponsePublisher(Protocol):
    """投影发布侧。"""

    async def publish(self, projection: BusProjection) -> None:
        """发布一条投影事件。

        **幂等由调用方经 `ProjectionStore` 保证，不在这里**：本端口的实现是
        一次网络写，它无从知道「这条发过没有」。把幂等塞进实现会让每个实现
        各写一份判定，而判定的键（`租户 + 事件标识`）是领域事实。
        """
        ...


@runtime_checkable
class StreamReferenceResolving(Protocol):
    """流引用解析。

    **只声明一个动作**：标准订阅路径上的闸门要问「这个引用配不配这个 Task」，
    它不需要知道引用怎么签发、存在哪、怎么过期。

    **抽成端口是为了不让两个适配件互相依赖**：签发方在总线的进站适配件里，
    闸门在标准入口的进站适配件里——同层横向依赖会让两侧耦合在一个
    谁也不拥有的约定上（`tools/layer_lateral_guard.py` 阻断的正是这个）。
    """

    def resolve(self, ref: str, *, tenant_id: str, task_id: str) -> bool:
        """这个引用是不是签发给「该租户的该 Task」的，且尚未过期。

        **返回否而不是抛**：不存在、绑到别的 Task、过期三种情形对调用方是同一件事——
        分开告知等于把引用表的内容一点点漏出去。
        """
        ...


@runtime_checkable
class AdmissionStore(Protocol):
    """准入记录存储。四个方法覆盖状态机的全部转移（详设 §4.2）。"""

    async def reserve(
        self,
        key: AdmissionKey,
        *,
        task_id: str,
        family: EventFamily,
        correlation_id: str,
        request_digest: str,
        trace_id: str = "",
    ) -> Tuple[AdmissionOutcome, AdmissionRecord]:
        """按「租户 + 幂等键」预留，返回 (结果, 记录)。

        结果三分：新建、复用（摘要相同）、冲突（摘要不同）。

        **冲突不抛异常**：它是一种正常结果，要走投影发布（发 `*_REJECTED`），
        不是错误路径。抛异常会让调用方在 `except` 里判业务分支。
        """
        ...

    async def admit(self, key: AdmissionKey, *, task_id: str = "") -> None:
        """转「已受理」，并可校正任务标识。

        **为什么要能校正**：预留时用的是确定性派生值（租户 + 幂等键），
        而标准入口是 Task 的所有者、可能给出另一个。不校正时重投复用预留值，
        两次投影里的任务标识不同——调用方正是据它关联同一次调用。
        """
        ...

    async def reject(self, key: AdmissionKey, *, reason: str) -> None:
        """转「已拒绝」。**只用于准入前的确定性拒绝**——已有 Task 之后的失败
        由 Task 终态承载，不回写这里（详设 §4.4）。
        """
        ...

    async def get(self, key: AdmissionKey) -> Optional[AdmissionRecord]:
        """读记录。投影时据它恢复来源族（`CL-e75f0f550445`）。"""
        ...

    async def find_by_task_id(
        self, tenant_id: str, task_id: str
    ) -> Optional[AdmissionRecord]:
        """按「租户 + 任务标识」反查。

        **为什么需要第二条查询路径**：状态投影是从 Task 那一侧来的——观察到某个
        Task 进了等待输入，要回答「它属于哪个租户、哪一族、哪个 correlation」。
        那时手上只有任务标识，没有幂等键。上游同法（`InMemoryBusTaskAdmissionStore`
        的 `findByTaskId`）。

        **租户在参数里，不是查完再比**：按任务标识全表扫再过滤租户，等于跨租户
        读了一次（`CL-080a2ff2b87f` 禁止的正是这个），且实现侧的键面本就带租户。
        """
        ...


class ObservedTaskStatus(Protocol):
    """被观察 Task 的状态面。

    `timestamp` 的类型随实现而异——标准入口下是协议库的时间戳对象，
    进程内替身下可能是浮点秒。投影器按类型分派，端口只承诺「有这个字段」。
    """

    @property
    def state(self) -> object:
        ...

    @property
    def timestamp(self) -> object:
        ...

    @property
    def message(self) -> object:
        """等待输入时的提示消息（正文与续接锚点都在它上面）。

        **权威 `CL-a3a2249a79cf` 要求等待输入投影带四项**：任务标识、
        输入需求描述、correlation、可恢复上下文引用。前者与 correlation
        从准入记录取，后两项只有这里有——处理器发出的问题正文与交互标识
        由标准入口写进 Task 的状态消息。

        **不声明它的后果**：投影发得出去，但载荷是空的。调用方收到
        「它在等输入」，却不知道等的是什么、也拿不到续接锚点，
        只能再去查一次 Task——而那正是 `CL-61188e9f805a` 禁止的形态，
        只是换了个位置发生。独立复核 2026-08-19（017b 的 I-01 字段面）实测。

        **字段名取协议库的真名**（`TaskStatus.message`）——先前按判据夹具里
        自己起的名字写成 `update`，进程内判据全绿而真链路上取不到任何东西：
        替身有那个属性、真 Task 没有。**替身的字段名要照着真类型抄。**

        非等待输入的状态上没有这一项，实现可返回空。
        """
        ...


class ObservedTask(Protocol):
    """被观察的 Task：**只声明投影真正读的那三个面**。

    **不标协议库的 Task 类型**：端口是内层，标了它就把协议库的类型钉进内层，
    与依赖倒置相反。**也不留裸 `Any`**——那样静态检查看不见这条依赖边，
    传一个完全无关的对象进来同样不报。

    投影器实现侧仍用 `getattr` 逐个取：协议库的 Task 是生成类，
    缺字段时抛的是属性错误而不是投影跳过，而**投影跳过是本特性要的行为**
    （`CL-3a3e359ed7d7`／`:51` 只在能判定状态时发投影）。
    """

    @property
    def id(self) -> str:
        ...

    @property
    def status(self) -> ObservedTaskStatus:
        ...

    @property
    def metadata(self) -> object:
        ...


@runtime_checkable
class TaskStateProjecting(Protocol):
    """Task 状态观察与投影。

    **用例层只认这三个方法**：它不知道投影器背后怎么读 Task、怎么派生 revision，
    那是适配层的事。此前这个参数标的是裸 `Any`——静态检查看不见这条依赖边。
    """

    def remember_tenant(self, task_id: str, tenant_id: str) -> None:
        """受理时记下这个 Task 属于哪个租户。

        **标准入口建的 Task 没有租户字段**（`FEAT-001` 的 Task 表面只有 id 与
        contextId），而投影要按租户反查准入记录——不记则那个 Task 的状态投影
        一条都发不出。
        """
        ...

    async def observe(self, task: ObservedTask) -> None:
        """观察一次 Task 状态；是投影时机就发投影。"""
        ...

    async def project_current(self, tenant_id: str, task_id: str) -> None:
        """按当前状态补投一次。

        执行期的落盘可能早于「记下租户」那一步，受理完成后回读一次把窗口补上。
        """
        ...


@runtime_checkable
class ProjectionStore(Protocol):
    """投影发布幂等的记录。

    **与准入记录分开**：一个 Task 会有多条投影，合并会让「这个 Task 受理过吗」
    与「这条投影发过吗」共用一条记录，而它们的生命周期不同。
    """

    async def mark_published(self, tenant_id: str, event_id: str) -> bool:
        """标记已发布。返回 `False` 表示此前已发过——调用方据此跳过重复发布。"""
        ...
