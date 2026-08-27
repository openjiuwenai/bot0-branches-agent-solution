# coding: utf-8

"""消费一条总线事件的用例：校验 → 准入 → 桥接 → 投影 → 给出确认结论。

## 本件的核心判断只有一个：这条消息确认还是不确认

权威 `Technical-AF/docs/develop/02-features/`CL-212432c8c82b``
把确认边界定在「已可靠接收并落入 Task 控制面」，不等 Agent 执行终态。在这条边界之上
还有一个更细的分野，权威没有明写、由第一性推导得出（详设附录 A.2 已登记为非权威结论）：

- **确定性拒绝也确认**——校验失败是关于这条消息**本身**的判断，重投改变不了它。
  不确认只会让它反复投递直到进死信队列。
- **本 runtime 此刻处理不了**（存储抖动、发布通道断）**不确认**——它不是关于消息的
  判断，是本进程的暂时状态，重投是有意义的。

**把两者归成一类是这一层最贵的错**：全都确认，则存储抖动期间的事件永久丢失；
全都不确认，则一条格式错误的消息被反复投递。

## 它只认端口

四个协作方全部是 `agent_runtime/ports/bus.py` 里的协议。本件不 import 任何适配层，
也不 import 任何 broker 或框架类型——依赖方向一律向内（详设 §3.4）。
"""
from __future__ import annotations

import hashlib
import inspect
import json
import logging
import time
from dataclasses import dataclass
from typing import Any, Callable, Mapping, Optional

from agent_runtime.domain.bus.admission import AdmissionKey, AdmissionOutcome
from agent_runtime.domain.bus.envelope import (
    BusEventEnvelope,
    EnvelopeInvalid,
    parse_envelope,
)
from agent_runtime.domain.bus.event_types import (
    CREATING_EVENTS,
    EventFamily,
    InboundEventType,
    ProjectionKind,
    family_of,
)
from agent_runtime.domain.bus.projection import BusProjection
from agent_runtime.ports.bus import (
    AdmissionStore,
    BusResponsePublisher,
    ControlPlaneBridge,
    ProjectionStore,
    TaskStateProjecting,
)

_logger = logging.getLogger(__name__)

#: 服务间事件族的名称前缀。**取自领域层的枚举而不是字面量**——
#: 字面量里出现外层模块名会让分层检查判为跨层泄漏，而那条检查是对的：
#: 前缀是领域概念，它的取值该由领域层给。
_SERVICE_FAMILY_PREFIX = EventFamily.A2A_CALL.value + "_"

#: 载荷里可接受的 A2A 方法名。**PascalCase，逐字取自当前入口**（详设 §4.3）。
#: 小写点分形式（`message/send`、`tasks/get`、`tasks/cancel`、`tasks/resubscribe`）
#: 不接受——它们是另一套命名，混用会让两条入口对同一个词的解释不同。
_ACCEPTED_METHODS = frozenset(
    {"SendMessage", "SendStreamingMessage", "GetTask", "SubscribeToTask", "CancelTask"}
)

#: 非创建类事件各自的成功投影类别（详设 §4.3 的入站矩阵）。
_PROJECTION_BY_EVENT = {
    InboundEventType.CLIENT_INVOCATION_QUERY_REQUESTED: ProjectionKind.RESPONSE,
    InboundEventType.A2A_CALL_QUERY_REQUESTED: ProjectionKind.RESPONSE,
    InboundEventType.CLIENT_INVOCATION_CANCEL_REQUESTED: ProjectionKind.TERMINAL,
    InboundEventType.A2A_CALL_CANCEL_REQUESTED: ProjectionKind.TERMINAL,
    InboundEventType.CLIENT_STREAM_SUBSCRIBE_REQUESTED: ProjectionKind.STREAM_READY,
    InboundEventType.A2A_STREAM_SUBSCRIBE_REQUESTED: ProjectionKind.STREAM_READY,
}


@dataclass(frozen=True)
class BusConsumeOutcome:
    """消费一条事件的结论。

    字段 acknowledge：**这条消息确不确认**。见模块文档的两类分野。
    字段 reason：不确认时的原因，进日志——投递侧据它决定重投策略。
    """

    acknowledge: bool
    reason: str = ""

    @staticmethod
    def acknowledged(reason: str = "") -> "BusConsumeOutcome":
        return BusConsumeOutcome(acknowledge=True, reason=reason)

    @staticmethod
    def retry(reason: str) -> "BusConsumeOutcome":
        return BusConsumeOutcome(acknowledge=False, reason=reason)


class _TransientUnavailable(RuntimeError):
    """本 runtime 此刻处理不了。**不确认，交给重投。**"""


class BusConsumeUseCase:
    """把一条总线事件走完整条链路。

    构造参数 bridge：控制面桥。**只认它的 `dispatch`**——本件不知道它背后是
        a2a-sdk 还是别的什么，那是适配层的事。
    构造参数 now：取当前时刻的函数。**注入而不是直接读时钟**——判据要构造
        「截止刚好过期」这类边界，读死时钟就构造不出来。
    构造参数 event_id_of：投影事件标识的构造函数，收 (消息标识, 投影类别)。

        **不能是无参随机生成器**：`CL-8e50b0a83c6a` 要求「重复消费同一事件时不得重复
        产生可见副作用」，而幂等键是「租户 + 事件标识」。每次随机生成时，同一条
        消息的两次消费各得一个标识，投影记录看不出它们是同一条——那条幂等整个不成立。

        默认实现由消息标识与投影类别派生：同一条消息的接受投影**总是**同一个标识，
        而接受与终态是两个标识（同一条消息本就会发多类投影）。
    """

    def __init__(
        self,
        *,
        bridge: ControlPlaneBridge,
        admission: AdmissionStore,
        projections: ProjectionStore,
        publisher: BusResponsePublisher,
        service_id: str,
        tenant_id: str = "",
        schema_major: int = 1,
        #: 受理回调：`*_ACCEPTED` 投影发出之后立即调，用于**在受理点确认消费**。
        #:
        #: 权威 `CL-212432c8c82b` 逐字「不得等待 Agent 执行终态后才确认消费」。
        #: 而阻塞不是包装层造成的——A2A 阻塞式发送语义本身就要等执行完才返回，
        #: 换掉包装层实测读数一模一样（处理器 sleep 0.8s，本方法耗时 0.803s）。
        #:
        #: 真正的根因在结构：消费者先 `await consume_once` 再确认，
        #: 确认被绑在用例返回之后。有了这个回调，确认能在受理点就发出，
        #: 执行继续在同一个协程里跑完——长任务不再占住消费者，
        #: 总线那一侧也不会因迟迟不确认而重投。
        #:
        #: 不传时行为与此前一致（确认仍在用例返回后由消费者发出）。
        on_admitted: Optional[Callable[[], Any]] = None,
        now: Optional[Callable[[], float]] = None,
        event_id_of: Optional[Callable[[str, str], str]] = None,
    ) -> None:
        if not service_id:
            raise ValueError(
                "总线消费未配置本 runtime 的服务身份——空身份等于接受所有目标事件，"
                "会消费掉发给别人的事件，而两侧都看不出异常"
            )
        self._bridge = bridge
        self._admission = admission
        self._projections = projections
        self._publisher = publisher
        self._service_id = service_id
        #: 本 runtime 在总线上的租户。**空 = 不校验**，与服务身份不同档：
        #: 服务身份为空是配置错误（会消费掉发给别人的事件），
        #: 而租户为空是嵌入式与判据里的常见形态，装配层负责传进来。
        self._tenant_id = tenant_id
        self._schema_major = schema_major
        self._on_admitted = on_admitted
        self._now = now or time.time
        self._event_id_of = event_id_of or _default_event_id
        #: 状态投影器。装配期接上；**不接也能跑**——那种部署形态下没有主动投影，
        #: 调用方只能轮询查询（详设 §13 已登记）。
        self._projector: Optional[TaskStateProjecting] = None

    def attach_projector(self, projector: TaskStateProjecting) -> None:
        """接上状态投影器。装配期调用一次。"""
        self._projector = projector

    async def consume_once(
        self,
        raw: Mapping[str, Any],
        *,
        on_admitted: Optional[Callable[[], Any]] = None,
    ) -> BusConsumeOutcome:
        """消费一条事件，返回确认结论。

        **本方法不自己调确认**：确认是投递侧的动作，由消费循环拿到本返回值后做。
        把它写进用例层会让用例依赖投递端口的生命周期语义。
        """
        # 第一步：信封校验。失败即确定性拒绝——**确认掉**，重投改变不了它。
        try:
            envelope = parse_envelope(
                raw,
                now=self._now(),
                schema_major=self._schema_major,
                service_id=self._service_id,
                tenant_id=self._tenant_id or None,
            )
        except EnvelopeInvalid as exc:
            return await self._project_envelope_rejection(raw, exc)

        try:
            if envelope.event_type in CREATING_EVENTS:
                return await self._consume_creating(envelope, on_admitted=on_admitted)
            return await self._consume_control(envelope, on_admitted=on_admitted)
        except _TransientUnavailable as exc:
            _logger.warning(
                "总线事件暂时处理不了，不确认交给重投：消息=%s 租户=%s（%s）",
                envelope.message_id, envelope.tenant_id, exc,
            )
            return BusConsumeOutcome.retry(str(exc))

    # ── 创建类 ────────────────────────────────────────────────

    async def _consume_creating(
        self,
        envelope: BusEventEnvelope,
        *,
        on_admitted: Optional[Callable[[], Any]] = None,
    ) -> BusConsumeOutcome:
        family = family_of(envelope.event_type)
        payload, failure = self._parse_payload(envelope)
        if failure is not None:
            await self._publish(envelope, family, ProjectionKind.FAILED,
                                error_code=failure)
            return BusConsumeOutcome.acknowledged(failure)

        key = AdmissionKey(
            tenant_id=envelope.tenant_id, idempotency_key=envelope.idempotency_key
        )
        digest = _digest(payload)
        # **任务标识在预留时就定下来**，桥接用它——两处各生成一个时，投影里的标识
        # 与 Task 实际标识不同，调用方拿着投影里那个去查会查不到。
        task_id = _new_task_id(envelope)
        try:
            outcome, record = await self._admission.reserve(
                key,
                task_id=task_id,
                family=family,
                correlation_id=envelope.correlation_id,
                trace_id=envelope.trace_id,
                request_digest=digest,
            )
        except Exception as exc:  # noqa: BLE001  见 `_TransientUnavailable` 的说明
            raise _TransientUnavailable(f"准入存储不可用：{exc}") from exc

        if outcome is AdmissionOutcome.CONFLICT:
            # 同一幂等键、载荷不同 —— 确定性拒绝。**不建 Task。**
            await self._publish(
                envelope, record.family, ProjectionKind.REJECTED,
                error_code="IDEMPOTENCY_CONFLICT",
            )
            return BusConsumeOutcome.acknowledged("幂等键冲突")

        if outcome is AdmissionOutcome.REUSED:
            # 重复投递：**复用同一任务标识、补发等价接受投影**（`CL-8e50b0a83c6a`）。
            # 族从记录里取，不按当前事件猜（`CL-e75f0f550445`）。
            await self._publish(
                envelope, record.family, ProjectionKind.ACCEPTED,
                task_id=record.task_id, idempotency_result="REUSED",
            )
            return BusConsumeOutcome.acknowledged("重复投递，已复用")

        # **在桥接之前记租户**：桥接内部同步跑完整个执行，Task 的终态落盘发生在
        # `dispatch` 返回之前——那时投影器若还不知道这个 Task 属于哪个租户，
        # 就会跳过它（`CL-080a2ff2b87f` 要求缺租户不投影）。实测过：终态投影一条不发，
        # 而日志只有一句「缺租户，跳过」。
        #
        # 用预留的标识记一次，桥接返回后若标识被校正再记一次——两次都记不冗余：
        # 第一次覆盖执行期，第二次覆盖执行之后。
        if self._projector is not None:
            self._projector.remember_tenant(record.task_id, envelope.tenant_id)

        # **受理即确认，在桥接之前**（`CL-212432c8c82b`）：权威逐字「确认语义必须
        # 收敛到 runtime **已可靠接收事件并落入 Task 控制面**；不得等待 Agent
        # 执行终态后才确认消费」。到这一行为止，事件已通过校验、幂等键已预留、
        # Task 标识已定、租户已记——控制面该知道的都知道了。
        #
        # **放在 `dispatch` 之后等于没放**：非流式 `on_message_send` 是阻塞语义，
        # 桥接内部同步跑完整个执行才返回。独立复核 2026-08-18 用真用例实测：
        # 处理器 sleep 0.8 秒，确认发生在 0.801 秒——与改前逐字节相同。
        # 此前那条判据用的是替身用例，替身在回调后才阻塞，于是它一直是绿的。
        #
        # **提前确认不改变失败语义**：桥接抛错时下面本来就走
        # `acknowledged("控制面桥接失败")`——那条路径也是确认，不是退回重投。
        await _fire_admitted(on_admitted or self._on_admitted)

        try:
            bridge_outcome = await self._bridge.dispatch(envelope, task_id=record.task_id)
        except Exception as exc:  # noqa: BLE001  桥接失败是这一次调用的确定失败
            _logger.error(
                "控制面桥接失败：消息=%s 任务=%s（%s）",
                envelope.message_id, record.task_id, exc,
            )
            await self._publish(
                envelope, record.family, ProjectionKind.FAILED,
                task_id=record.task_id, error_code="BRIDGE_FAILED",
            )
            return BusConsumeOutcome.acknowledged("控制面桥接失败")

        try:
            # **把标准入口给的标识写回记录**：预留时用的是确定性派生值，
            # 而标准入口可能给出另一个（它是 Task 的所有者）。不写回时，
            # 重投复用的是预留值，两次投影里的任务标识不同——
            # 而调用方正是据它关联同一次调用。
            await self._admission.admit(key, task_id=bridge_outcome.task_id)
        except Exception as exc:  # noqa: BLE001
            raise _TransientUnavailable(f"准入状态写入失败：{exc}") from exc

        # 标识被标准入口校正时补记一次，并**补投一次当前状态**——
        # 执行期的落盘用的是预留标识、准入记录里也还是它，投影器那时查得到；
        # 但校正之后的标识是另一个，那之后的落盘要能对上。上游同法
        # （`TaskStoreProjectionPostProcessor.projectCurrent`）。
        await self._publish(
            envelope, record.family, ProjectionKind.ACCEPTED,
            task_id=bridge_outcome.task_id, idempotency_result="CREATED",
        )

        # **窗口内跑完了就把结果随响应事件带回去**（`CL-c084b6545bc2`）。
        #
        # 只发接受投影时，调用方只知道「收到了」，拿不到结果，只能去轮询 Task——
        # 而这条条款要的正是省掉那一轮。上游同条件同位置：
        # `RuntimeBusEventConsumer.admitAndProject` 在接受投影之后判 `result.response() != null`
        # 再补一条 `projectResponse`。
        #
        # **载荷为空就不发**：发一条没有结果的响应事件比不发更坏——
        # 调用方会把「还没跑完」读成「跑完了但什么都没有」。
        #
        # **只在本次真的跑过调用的路径上发**：重投走的是复用分支，
        # 那条路径根本没再跑一次调用，手上没有任何响应可发。
        if bridge_outcome.payload:
            try:
                await self._publish(
                    envelope, record.family, ProjectionKind.RESPONSE,
                    task_id=bridge_outcome.task_id, payload=bridge_outcome.payload,
                )
            except ValueError:
                # **构造不出响应投影不牵连整条事件**：Task 已经建好、接受投影已经发出，
                # 退回会让这条事件被重投，而重投只会再走一次复用分支——
                # 响应投影仍然发不出去，代价是白跑一轮。
                #
                # 触发这一支的是领域层的投影守卫（例如载荷里混进了流内容键，
                # `CL-81c3af3d3494`）。**必须留下可观察事实**：不记则对外表现是
                # 「一切正常，只是调用方永远收不到结果」，而排查时线索为零。
                _logger.warning(
                    "响应投影构造失败，已跳过：任务=%s 载荷键=%s",
                    bridge_outcome.task_id, sorted(bridge_outcome.payload),
                    exc_info=True,
                )

        # **流可订阅时发流准备投影**（`CL-b3f7b4e953ec` 是 MUST）。
        #
        # 调用方据它知道「现在可以去订阅那条流了」——不发的话它只能轮询，
        # 或者干脆错过整段流式输出。
        #
        # 判据是桥接带回的流引用：有引用即意味着那条流已经可订阅。
        # 上游同法（`RuntimeBusEventConsumer.java` 的 `admitAndProject`）：
        # `if (result.streamReady()) { projectStreamReady(envelope, taskId); }`。
        #
        # 此前只有订阅类事件会发它（`_PROJECTION_BY_EVENT` 的两行映射），
        # 创建类路径零发布——而流引用明明已经在手上。
        if bridge_outcome.stream_ref:
            await self._publish(
                envelope, record.family, ProjectionKind.STREAM_READY,
                task_id=bridge_outcome.task_id or task_id,
                stream_ref=bridge_outcome.stream_ref,
            )
        # **终态投影排在最后**（详设 §4.4：终态一定是该 correlation 下的最后一条）。
        #
        # 排在接受之前时，订阅方先看到「这个 Task 结束了」、再看到
        # 「这个 Task 被接受了」——按事件顺序建模的订阅方会把后到的接受
        # 当成一次**新的调用**。实测序列曾是 TERMINAL → ACCEPTED → RESPONSE。
        #
        # 上游同序（`RuntimeBusEventConsumer.java` 的 `admitAndProject`）：
        # projectAccepted → projectResponse → projectStreamReady → projectCurrent。
        if self._projector is not None and bridge_outcome.task_id:
            self._projector.remember_tenant(bridge_outcome.task_id, envelope.tenant_id)
            await self._projector.project_current(
                envelope.tenant_id, bridge_outcome.task_id
            )

        return BusConsumeOutcome.acknowledged()

    # ── 查询 / 取消 / 订阅 ────────────────────────────────────

    async def _consume_control(
        self,
        envelope: BusEventEnvelope,
        *,
        on_admitted: Optional[Callable[[], Any]] = None,
    ) -> BusConsumeOutcome:
        """三类不建 Task 的事件（`CL-c58099f7bf0a`／`:156`／`:157`）。

        **族按准入记录恢复，不按当前事件猜**（`CL-e75f0f550445`）：同一个 Task 会被
        不同族的事件触及——客户端去查一个服务间调用产生的 Task 是常态。按当前事件
        猜族，会让该 Task 的事件序列在两族之间跳，而调用方是按族订阅的，
        跳过去的那些它根本收不到。

        **取不到记录时才回落到当前事件的族**：查询一个不是本 runtime 经总线建的
        Task（例如 HTTP 直连建的）时没有准入记录可查，此时按来源族发是唯一选择——
        它至少让调用方在自己订阅的那一族里收得到回应。
        """
        payload, failure = self._parse_payload(envelope)
        family = await self._family_for_control(envelope, payload)
        if failure is not None:
            await self._publish(envelope, family, ProjectionKind.FAILED,
                                error_code=failure)
            return BusConsumeOutcome.acknowledged(failure)
        # **任务标识在载荷里，不在信封**：权威的入站矩阵逐字写「必要 payload：
        # `taskId` + query params」。信封承载的是路由与幂等这类外层事实，
        # 业务参数一律走载荷（`CL-d99eb92a6b6e`：A2A JSON-RPC 只能作为载荷）。
        task_id = _task_id_from(payload)
        if not task_id:
            # **不隐式建 Task**（`CL-0079122b13f1`）：找不到目标就是确定失败。
            await self._publish(envelope, family, ProjectionKind.FAILED,
                                error_code="TASK_ID_REQUIRED")
            return BusConsumeOutcome.acknowledged("控制类事件缺任务标识")

        # **受理即确认，在桥接之前**（`CL-212432c8c82b`）：权威逐字「确认语义必须
        # 收敛到 runtime **已可靠接收事件并落入 Task 控制面**；不得等待 Agent
        # 执行终态后才确认消费」。到这一行为止，事件已通过校验、幂等键已预留、
        # Task 标识已定、租户已记——控制面该知道的都知道了。
        #
        # **放在 `dispatch` 之后等于没放**：非流式 `on_message_send` 是阻塞语义，
        # 桥接内部同步跑完整个执行才返回。独立复核 2026-08-18 用真用例实测：
        # 处理器 sleep 0.8 秒，确认发生在 0.801 秒——与改前逐字节相同。
        # 此前那条判据用的是替身用例，替身在回调后才阻塞，于是它一直是绿的。
        #
        # **提前确认不改变失败语义**：桥接抛错时下面本来就走
        # `acknowledged("控制面桥接失败")`——那条路径也是确认，不是退回重投。
        await _fire_admitted(on_admitted or self._on_admitted)

        try:
            bridge_outcome = await self._bridge.dispatch(envelope, task_id=task_id)
        except Exception as exc:  # noqa: BLE001
            _logger.error("控制类事件桥接失败：任务=%s（%s）", task_id, exc)
            await self._publish(envelope, family, ProjectionKind.FAILED,
                                task_id=task_id, error_code="BRIDGE_FAILED")
            return BusConsumeOutcome.acknowledged("控制面桥接失败")

        kind = _PROJECTION_BY_EVENT[envelope.event_type]

        # **带上桥接返回的载荷**（`CL-c94928db9151`：查询响应必须返回
        # A2A 兼容的 Task 快照或错误投影）。不带时订阅方收到一条
        # 「查到了」而里面什么都没有，只能再发一次 A2A 查询。
        await self._publish(
            envelope, family, kind,
            task_id=bridge_outcome.task_id or task_id,
            stream_ref=bridge_outcome.stream_ref,
            payload=bridge_outcome.payload,
        )
        return BusConsumeOutcome.acknowledged()

    async def _family_for_control(
        self, envelope: BusEventEnvelope, payload: Mapping[str, Any]
    ) -> EventFamily:
        """控制类事件的投影族：先查准入记录，查不到再回落。

        **查的键是「租户 + 幂等键」**。幂等键在信封校验里已是必填
        （对齐上游的必填清单），故这里不再判它空不空——那一支在校验之后
        永远走不到，留着会让读者以为存在「不带键的控制事件」这种形态。

        **查不到记录时回落到当前事件的族**：控制类事件的幂等键指向的是它自己
        那一次投递，而准入记录是按创建那一次的键存的——两者本就不必相同。
        查一个不是本 runtime 经总线建的 Task（例如客户端直连建的）同样查不到。
        此时按来源族发是唯一选择：它至少让调用方在自己订阅的那一族里收得到回应。
        """
        fallback = family_of(envelope.event_type)
        try:
            record = await self._admission.get(
                AdmissionKey(
                    tenant_id=envelope.tenant_id,
                    idempotency_key=envelope.idempotency_key,
                )
            )
        except Exception as exc:  # noqa: BLE001  查不到族不该让整条事件失败
            _logger.warning(
                "查准入记录以恢复投影族时失败，回落到当前事件的族：%s", exc
            )
            return fallback
        return record.family if record is not None else fallback

    # ── 投影发布 ──────────────────────────────────────────────

    async def _project_envelope_rejection(
        self, raw: Mapping[str, Any], exc: EnvelopeInvalid
    ) -> BusConsumeOutcome:
        """信封非法 → `*_REJECTED`，**并确认**。

        族只能从原始事件类型猜——此刻信封还没解析成功，没有准入记录可查。
        猜不出来时按客户端族发：它是两族里更常见的那个，且拒绝投影不带任务标识，
        错族的后果限于「调用方在另一个族的订阅上收不到这条拒绝」。
        """
        # **租户越界这一类只确认、不发投影**（`CL-080a2ff2b87f`／`:164`）。
        #
        # 拒绝投影本身带着事件里那个租户标识发出去，而那正是一次跨租户写——
        # 「拒绝跨租户」的同时用违规的方式宣告拒绝违规。上游同法且逐字如此：
        # 其 `invalidEnvelope` 对 `TENANT_SCOPE_VIOLATION` 直接返回 rejected 决定，
        # 不走发投影那条路。
        #
        # **其余信封非法照常发**：它们的租户是本 runtime 自己的，发投影不越界，
        # 而调用方需要那条反馈才知道自己的报文错在哪。
        #
        # 这条是部署级 E2E 抓出来的：进程内只看「有没有拒绝」，
        # 拒绝以什么形态发生要到真进程里读投影序列才看得见。
        if exc.tenant_scope_violation:
            _logger.warning(
                "总线事件租户越界，确认并静默丢弃（不向该租户发投影）：%s", exc
            )
            return BusConsumeOutcome.acknowledged("租户越界")

        family = EventFamily.INVOCATION
        event_type_raw = str(raw.get("eventType") or "")
        if event_type_raw.startswith(_SERVICE_FAMILY_PREFIX):
            family = EventFamily.A2A_CALL
        _logger.warning("总线事件信封非法，拒绝并确认：%s", exc)
        projection = BusProjection(
            kind=ProjectionKind.REJECTED,
            family=family,
            tenant_id=str(raw.get("tenantId") or ""),
            event_id=self._event_id_of(str(raw.get("messageId") or ""), ProjectionKind.REJECTED.value),
            causation_message_id=str(raw.get("messageId") or ""),
            correlation_id=str(raw.get("correlationId") or ""),
            trace_id=str(raw.get("traceId") or ""),
            occurred_at=self._now(),
            error_code="ENVELOPE_INVALID",
        )
        try:
            await self._emit(projection)
        except _TransientUnavailable as inner:
            return BusConsumeOutcome.retry(str(inner))
        return BusConsumeOutcome.acknowledged(str(exc))

    async def _publish(
        self,
        envelope: BusEventEnvelope,
        family: EventFamily,
        kind: ProjectionKind,
        *,
        task_id: str = "",
        stream_ref: str = "",
        error_code: str = "",
        idempotency_result: str = "",
        payload: Optional[Mapping[str, Any]] = None,
    ) -> None:
        # **两个来源二选一，不合并**：幂等结果是本层产生的元事实，
        # 响应载荷是标准入口的产出，混在一个映射里会让调用方分不清哪半是谁的。
        if payload is None and idempotency_result:
            payload = {"idempotencyResult": idempotency_result}
        await self._emit(
            BusProjection(
                kind=kind,
                family=family,
                tenant_id=envelope.tenant_id,
                event_id=self._event_id_of(envelope.message_id, kind.value),
                causation_message_id=envelope.message_id,
                correlation_id=envelope.correlation_id,
                trace_id=envelope.trace_id,
                occurred_at=self._now(),
                task_id=task_id,
                stream_ref=stream_ref,
                error_code=error_code,
                inline_payload=payload,
            )
        )

    async def _emit(self, projection: BusProjection) -> None:
        """发布幂等 + 发布。

        **发布失败归「暂时不可用」**：Task 已经建了，回滚它会让一个正在执行的
        Agent 失去归属（`CL-19931501239d` 明写不回滚）。交给重投，重投时准入记录
        会把它判成复用、补发等价投影。
        """
        try:
            fresh = await self._projections.mark_published(
                projection.tenant_id, projection.event_id
            )
        except Exception as exc:  # noqa: BLE001
            raise _TransientUnavailable(f"投影记录不可用：{exc}") from exc
        if not fresh:
            return
        try:
            await self._publisher.publish(projection)
        except Exception as exc:  # noqa: BLE001
            raise _TransientUnavailable(f"投影发布失败：{exc}") from exc

    # ── 载荷 ──────────────────────────────────────────────────

    @staticmethod
    def _parse_payload(envelope: BusEventEnvelope) -> tuple[Mapping[str, Any], Optional[str]]:
        """解析载荷。返回 (载荷, 失败码)；失败码非空即确定失败。

        **仅有引用时确定失败，不是信封非法**：引用协议尚未定义，把引用串当
        A2A JSON 解析会产出无法诊断的错误。两者的投影族不同（详设 §2.2）。
        """
        if not envelope.has_usable_payload:
            return {}, "PAYLOAD_EMPTY"
        try:
            payload = json.loads(envelope.inline_payload)
        except (ValueError, TypeError):
            return {}, "PAYLOAD_UNPARSEABLE"
        if not isinstance(payload, dict):
            return {}, "PAYLOAD_UNPARSEABLE"
        method = str(payload.get("method") or "")
        if method and method not in _ACCEPTED_METHODS:
            # 小写点分形式落在这里。**不做大小写归一**——它们是另一套命名，
            # 归一等于默认接受两套，而那会让两条入口对同一个词的解释不同。
            return {}, "METHOD_NOT_SUPPORTED"
        return payload, None


def _digest(payload: Mapping[str, Any]) -> str:
    """载荷的稳定摘要。**键排序后再算**——字典序不同不构成不同的请求。"""
    canonical = json.dumps(payload, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:32]


def _new_task_id(envelope: BusEventEnvelope) -> str:
    """由租户与幂等键推出稳定的任务标识。

    **必须是确定性的**：重复投递在准入记录丢失（进程重启）后仍要落到同一个标识上，
    否则重启前后各建一个 Task。这是详设 §13 登记的那条限制的**部分**缓解——
    它挡不住两个副本同时首次收到同一事件的情形，那需要存储侧的原子原语。
    """
    seed = f"{envelope.tenant_id}:{envelope.idempotency_key}"
    return "bus-" + hashlib.sha256(seed.encode("utf-8")).hexdigest()[:24]


def _task_id_from(payload: Mapping[str, Any]) -> str:
    """从载荷里取任务标识。

    **三种位置都认**：`taskId` 顶层、`params.id`（A2A `GetTask` 与
    `SubscribeToTask` 的标准形态）、`params.taskId`。权威的入站矩阵只说
    「必要 payload 是 taskId」，没有规定它在哪一层；而 A2A 的标准请求把它放
    `params.id`，直接透传过来的载荷就是那个形态。

    **不认信封里的**：信封承载路由与幂等这类外层事实，业务参数走载荷
    （`CL-d99eb92a6b6e`）。两处都认会让同一条事件有两个可能的目标。
    """
    direct = payload.get("taskId")
    if direct:
        return str(direct)
    params = payload.get("params")
    if isinstance(params, Mapping):
        for key in ("id", "taskId"):
            value = params.get(key)
            if value:
                return str(value)
    return ""


def _default_event_id(message_id: str, kind: str) -> str:
    """由消息标识与投影类别派生投影事件标识。

    **确定性的**：同一条消息的同一类投影总是同一个标识，这是发布幂等
    （`CL-8e50b0a83c6a`）的前提。随机值会让那条幂等整个不成立。

    **带类别**：同一条消息本就会发多类投影（接受 → 响应 → 终态），
    不带类别会让第二类被第一类的记录挡下。
    """
    seed = f"{message_id}:{kind}"
    return "evt-" + hashlib.sha256(seed.encode("utf-8")).hexdigest()[:24]


async def _fire_admitted(callback: Optional[Callable[[], Any]]) -> None:
    """调受理回调，**同步与异步两种都认**。

    **回调失败不牵连消费**：确认发不出去时总线会重投，而重投会撞上幂等表——
    那是可恢复的；反过来让整条事件因为确认失败而被拒绝，则要重跑整个执行。
    故这里只记录，不抛。
    """
    if callback is None:
        return
    try:
        result = callback()
        if inspect.isawaitable(result):
            await result
    except Exception:  # noqa: BLE001  见函数文档：确认失败不牵连消费
        _logger.warning("受理确认回调失败，消费继续（总线会重投并撞上幂等表）", exc_info=True)
