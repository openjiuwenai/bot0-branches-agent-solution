# coding: utf-8
# pytest 类内判据保持实例方法：改 @staticmethod 后 coverage 的 test_function
# 动态上下文记不到它们——实测 35 条判据跑过且通过，覆盖数据里零上下文，
# 直接让 clause-evidence 上 76 条权威条款失去锚点。判据必须能被门禁看见。
# pylint: disable=no-self-use,add-staticmethod-or-classmethod-decorator


"""FEAT-017 用例层判据：消费一条事件的完整决策链。

## 这一层判什么

`BusConsumeUseCase.consume_once` 的**决策**：校验 → 准入 → 桥接 → 投影，
以及最关键的那一个返回值——**这条消息确认还是不确认**。

## 确认与否是这一层最贵的判断

权威 `Technical-AF/docs/develop/02-features/FEAT-017-bus-event-subscription-consumption.md:52`
把确认边界定在「已可靠接收并落入 Task 控制面」，不等 Agent 执行终态。而**确定性拒绝
也要确认**：校验失败是关于这条消息本身的判断，重投改变不了它，不确认只会让它反复
投递直到进死信。反过来「本 runtime 此刻处理不了」（存储抖动）必须不确认。

**把两者归成一类是这一层最贵的错**：全都确认，则存储抖动期间的事件永久丢失；
全都不确认，则一条格式错误的消息被反复投递。故本文件对两类各写判据，并有一条
把两类的读数摆在一起比对——单看每一条都可能是「碰巧落对了」。

## 替身只替端口，不替决策

四个替身都实现详设 §3.2 的端口方法面，**它们不含任何判断**：桥接替身按预设返回、
存储替身按字典存取、发布替身只记账。**决策全部在被测的用例里**——替身一旦开始判断，
判据测的就是替身而不是产品代码，本仓记录在案的第一种假绿形态。
"""
from __future__ import annotations

import asyncio
from typing import Any, Optional

import pytest

from agent_runtime.application.bus_consume import (
    BusConsumeOutcome,
    BusConsumeUseCase,
)
from agent_runtime.domain.bus.admission import (
    AdmissionKey,
    AdmissionOutcome,
    AdmissionRecord,
    AdmissionState,
)
from agent_runtime.domain.bus.event_types import EventFamily, ProjectionKind
from agent_runtime.domain.bus.projection import BusProjection

# `BridgeOutcome` 定义在端口这一侧——它是 `ControlPlaneBridge.dispatch` 的
# 返回类型，放在适配层会让端口反向依赖适配层。
from agent_runtime.ports.bus import BridgeOutcome

_NOW = 1_800_000_000.0
_SERVICE = "billing-runtime"


def _raw(**over) -> dict:
    base = {
        "schemaVersion": "1.0",
        "eventType": "CLIENT_INVOCATION_REQUESTED",
        "messageId": "msg-1",
        "tenantId": "t-1",
        "sourceServiceId": "gateway",
        "targetServiceId": _SERVICE,
        "correlationId": "corr-1",
        "traceId": "trace-1",
        "idempotencyKey": "idem-1",
        "deadline": _NOW + 60,
        "inlinePayload": '{"method":"SendMessage","params":{"message":{"parts":[]}}}',
    }
    base.update(over)
    return base


class _Bridge:
    """控制面桥替身。**只替「跑一次标准 A2A 请求」这一段**，不做任何判断。"""

    def __init__(
        self, *, task_id: str = "", raises: Optional[Exception] = None,
        #: **默认不带流引用**：带引用意味着「这条流已可订阅」，
        #: 于是创建类路径会多发一条 `*_STREAM_READY`（`FEAT-017:49`）。
        #: 默认带值会让每个非流式判据都变成流式，序列断言随之全错。
        #: 要测流式的判据显式传。
        stream_ref: str = "", payload: Optional[dict] = None,
    ) -> None:
        #: **默认空 = 原样用调用方给的标识**。桥接不自己生成 Task 标识——
        #: 那个标识在准入预留时就定下来了，两处各生成一个会让投影里的标识
        #: 与 Task 实际标识不同，调用方拿着投影里那个去查会查不到。
        self.task_id = task_id
        self.raises = raises
        #: 流准备投影**必须**带引用（`FEAT-017:49`／`:80`），故替身默认给一个。
        #: 桥接是引用的签发方——它知道这个 Task 的流可不可订阅。
        self.stream_ref = stream_ref
        #: **等待窗口内完成时带回的响应载荷**。阻塞调用在本次窗口内跑完就有它，
        #: 跑不完（进等待输入、或还在执行）则为 `None`——那时只发接受投影。
        self.payload = payload
        self.calls: list[tuple[str, str]] = []

    async def dispatch(self, envelope, *, task_id: str) -> BridgeOutcome:
        self.calls.append((envelope.event_type.value, task_id))
        if self.raises is not None:
            raise self.raises
        return BridgeOutcome(
            task_id=self.task_id or task_id, created=True, stream_ref=self.stream_ref,
            payload=self.payload,
        )


class _Admission:
    """准入存储替身：字典存取，不含判断。"""

    def __init__(self, *, raises: Optional[Exception] = None) -> None:
        self.rows: dict[AdmissionKey, AdmissionRecord] = {}
        self.raises = raises

    async def reserve(self, key, *, task_id, family, correlation_id, trace_id="", request_digest):
        if self.raises is not None:
            raise self.raises
        existing = self.rows.get(key)
        if existing is not None:
            return existing.outcome_for(request_digest), existing
        record = AdmissionRecord(
            key=key, task_id=task_id, family=family,
            correlation_id=correlation_id, trace_id=trace_id,
            request_digest=request_digest, created_at=_NOW,
        )
        self.rows[key] = record
        return AdmissionOutcome.CREATED, record

    async def admit(self, key, *, task_id: str = "") -> None:
        if key not in self.rows:
            return
        admitted = self.rows[key].admitted()
        if task_id and task_id != admitted.task_id:
            admitted = admitted.with_task_id(task_id)
        self.rows[key] = admitted

    async def reject(self, key, *, reason: str) -> None:
        if key in self.rows:
            self.rows[key] = self.rows[key].rejected(reason)

    async def get(self, key):
        return self.rows.get(key)


class _Projections:
    """投影发布幂等的记录替身。"""

    def __init__(self) -> None:
        self.seen: set[tuple[str, str]] = set()

    async def mark_published(self, tenant_id: str, event_id: str) -> bool:
        key = (tenant_id, event_id)
        if key in self.seen:
            return False
        self.seen.add(key)
        return True


class _Publisher:
    """发布替身：只记账。"""

    def __init__(self, *, raises: Optional[Exception] = None) -> None:
        self.sent: list[BusProjection] = []
        self.raises = raises

    async def publish(self, projection: BusProjection) -> None:
        if self.raises is not None:
            raise self.raises
        self.sent.append(projection)


def _use_case(*, tenant_id: str = "", **over) -> tuple[BusConsumeUseCase, dict[str, Any]]:
    """装一套用例层。

    `tenant_id` 单列而不混进 `over`：后者是替身注入面（键名即构件名），
    把配置项混进去会让「传了个租户」看起来像「替换了一个叫 tenant_id 的构件」。
    """
    parts: dict[str, Any] = {
        "bridge": _Bridge(),
        "admission": _Admission(),
        "projections": _Projections(),
        "publisher": _Publisher(),
    }
    parts.update(over)
    uc = BusConsumeUseCase(
        bridge=parts["bridge"],
        admission=parts["admission"],
        projections=parts["projections"],
        publisher=parts["publisher"],
        service_id=_SERVICE,
        tenant_id=tenant_id,
        schema_major=1,
        now=lambda: _NOW,
        # **不覆盖事件标识生成器**：默认实现按「消息标识 + 投影类别」派生，
        # 而发布幂等正是靠它。固定成常量会让所有投影共用一个标识，
        # 第二条起全被幂等挡下——那测的是替身，不是产品代码。
    )
    return uc, parts


def _run(uc: BusConsumeUseCase, raw: dict) -> BusConsumeOutcome:
    return asyncio.run(uc.consume_once(raw))


def _kinds(pub: _Publisher) -> list[str]:
    return [p.kind.value for p in pub.sent]


class TestHappyPath:
    """创建类调用的主流程。"""

    def test_a_create_event_builds_a_task_and_projects_accepted(self) -> None:
        """TC-017-001 创建类调用建 Task、发接受投影、确认消费。

        锚三条：`FEAT-017:39`（边界内订阅消费的落地）、`FEAT-017:45`（创建或复用
        Task 后必须发接受投影并携带任务标识与 correlation）、`FEAT-017:67`
        （`CLIENT_INVOCATION_REQUESTED` 映射到标准发消息语义）。
        """
        uc, parts = _use_case()
        outcome = _run(uc, _raw())
        assert outcome.acknowledge is True
        assert _kinds(parts["publisher"]) == ["ACCEPTED"]
        sent = parts["publisher"].sent[0]
        # 标识由准入预留时确定性派生（租户 + 幂等键），**不是替身编的**
        assert sent.task_id.startswith("bus-"), f"任务标识形态不对：{sent.task_id}"
        assert sent.event_type == "INVOCATION_ACCEPTED"
        assert sent.correlation_id == "corr-1"

    def test_the_a2a_family_projects_with_its_own_names(self) -> None:
        """TC-017-005 服务间来源发 `A2A_CALL_ACCEPTED`，不是 `INVOCATION_ACCEPTED`。"""
        uc, parts = _use_case()
        _run(uc, _raw(eventType="A2A_CALL_REQUESTED"))
        assert parts["publisher"].sent[0].event_type == "A2A_CALL_ACCEPTED"

    def test_the_bridge_receives_the_reserved_task_id(self) -> None:
        """桥接拿到的是**准入预留的那个标识**，不是它自己另生成一个。

        两处各生成一个时，投影里的标识与 Task 实际标识不同——调用方拿着投影里
        那个去查，查不到。
        """
        uc, parts = _use_case()
        _run(uc, _raw())
        assert len(parts["bridge"].calls) == 1
        _, dispatched_task_id = parts["bridge"].calls[0]
        assert dispatched_task_id == parts["publisher"].sent[0].task_id


class TestBlockingResponseProjection:
    """等待窗口内完成的一次性调用要补发响应投影（`FEAT-017:47`）。

    上游同法：`RuntimeBusEventConsumer.admitAndProject` 在 `result.response() != null`
    时于接受投影之后补一条 `projectResponse`
    （`openJiuwen/agent-solution/common/agent-runtime-ext-java/agent-service-bus-consumer/`
    `src/main/java/com/openjiuwen/service/bus/consumer/RuntimeBusEventConsumer.java`）。
    """

    def test_a_call_that_finishes_in_the_window_also_projects_a_response(self) -> None:
        """TC-017-036 窗口内跑完 → 接受投影之后再发一条响应投影。

        **只发接受投影是不够的**：接受只说「收到了」，调用方拿不到结果，
        只能去轮询 Task——而 `FEAT-017:47` 要的正是把结果随响应事件带回去。
        """
        uc, parts = _use_case(bridge=_Bridge(payload={"result": {"id": "task-1"}}))
        outcome = _run(uc, _raw())

        assert outcome.acknowledge is True
        assert _kinds(parts["publisher"]) == ["ACCEPTED", "RESPONSE"], (
            "窗口内完成的调用没补发响应投影"
        )
        response = parts["publisher"].sent[1]
        assert response.event_type == "INVOCATION_RESPONSE"
        assert response.task_id == parts["publisher"].sent[0].task_id, (
            "响应投影与接受投影的任务标识不一致"
        )
        assert response.inline_payload == {"result": {"id": "task-1"}}

    def test_the_a2a_family_projects_its_own_response_name(self) -> None:
        """服务间来源发 `A2A_CALL_RESPONSE`，不是 `INVOCATION_RESPONSE`。"""
        uc, parts = _use_case(bridge=_Bridge(payload={"result": {}}))
        _run(uc, _raw(eventType="A2A_CALL_REQUESTED"))
        assert parts["publisher"].sent[1].event_type == "A2A_CALL_RESPONSE"

    def test_a_call_that_does_not_finish_in_the_window_projects_only_accepted(self) -> None:
        """**窗口内没跑完就不发响应投影**——发一条空的比不发更坏。

        调用方按响应事件收结果；发一条没有结果的响应事件，它会把「还没跑完」
        当成「跑完了但什么都没有」。上游的条件同样是响应非空才发。
        """
        uc, parts = _use_case(bridge=_Bridge(payload=None))
        _run(uc, _raw())
        assert _kinds(parts["publisher"]) == ["ACCEPTED"]

    def test_a_replay_does_not_project_a_second_response(self) -> None:
        """重投只补发等价的接受投影，**不重跑调用、也不再发响应投影**。

        重投时根本没有再跑一次调用，手上没有任何响应可发——
        发一条会让调用方收到两份结果，而它们可能不同。
        """
        uc, parts = _use_case(bridge=_Bridge(payload={"result": {}}))
        _run(uc, _raw())
        parts["publisher"].sent.clear()
        _run(uc, _raw(messageId="msg-2"))
        assert _kinds(parts["publisher"]) == ["ACCEPTED"]

    def test_stream_content_in_the_response_payload_is_refused(self) -> None:
        """响应载荷里混进流内容键时不发布（`FEAT-017:57`）。

        **这条守的是新开的口子**：本轮之前载荷字段零填充，逐字流块无从经此泄漏；
        接上之后它就是一条真实通路。领域层的守卫在这里生效，而生效与否需要有判据。
        """
        uc, parts = _use_case(bridge=_Bridge(payload={"chunks": ["逐", "字"]}))
        outcome = _run(uc, _raw())
        assert _kinds(parts["publisher"]) == ["ACCEPTED"], "带流内容的响应投影被发了出去"
        assert outcome.acknowledge is True, "构造不出响应投影不该让整条事件退回"


class TestAcknowledgeBoundary:
    """确认边界：本层最贵的判断（`FEAT-017:52`、详设 §4.1）。"""

    def test_a_deterministic_rejection_is_still_acknowledged(self) -> None:
        """TC-017-010 确定性拒绝**也确认**——重投改变不了这条消息本身。

        不确认只会让它反复投递直到进死信队列。

        锚 `FEAT-017:46`（未创建 Task 的明确拒绝必须发 `*_REJECTED` 并携带
        可编程拒绝原因）与 `FEAT-017:52`（确认收敛到接收边界）。
        """
        uc, parts = _use_case()
        outcome = _run(uc, _raw(schemaVersion="2.0"))
        assert outcome.acknowledge is True
        assert _kinds(parts["publisher"]) == ["REJECTED"]

    def test_a_transient_store_failure_is_not_acknowledged(self) -> None:
        """TC-017-011 存储此刻不可用时**不确认**，让 broker 重投。

        它不是关于这条消息的判断，是本 runtime 的暂时状态。
        """
        uc, parts = _use_case(admission=_Admission(raises=RuntimeError("存储抖动")))
        outcome = _run(uc, _raw())
        assert outcome.acknowledge is False
        assert parts["publisher"].sent == [], "暂时不可用时不该发任何投影"

    def test_the_two_failure_classes_land_differently(self) -> None:
        """**两类失败落到两个不同的确认结论**——单看每一条都可能是碰巧落对。

        全都确认 → 存储抖动期间的事件永久丢失；
        全都不确认 → 一条格式错误的消息被反复投递直到死信。
        """
        uc_a, _ = _use_case()
        deterministic = _run(uc_a, _raw(deadline=_NOW - 1)).acknowledge
        uc_b, _ = _use_case(admission=_Admission(raises=RuntimeError("抖动")))
        transient = _run(uc_b, _raw()).acknowledge
        assert (deterministic, transient) == (True, False), (
            f"两类失败没有分开：确定性={deterministic} 暂时性={transient}"
        )

    def test_acknowledgement_happens_before_execution_completes(self) -> None:
        """TC-017-009 确认发生在接受投影之后，**不等执行终态**。

        桥接替身在返回时 Task 才刚建好、远未终态；用例此刻已给出确认结论。

        锚 `FEAT-017:52`：确认语义收敛到「已可靠接收并落入 Task 控制面」，
        不得等待 Agent 执行终态。
        """
        uc, parts = _use_case()
        outcome = _run(uc, _raw())
        assert outcome.acknowledge is True
        assert parts["publisher"].sent[-1].kind is ProjectionKind.ACCEPTED, (
            "确认时最后一条投影不是接受——说明它等到了更后面的阶段"
        )


class TestIdempotency:
    """三层幂等键（`FEAT-017:53`／`:54`／`:55`）。"""

    def test_a_replay_reuses_the_same_task(self) -> None:
        """TC-017-012 同租户同幂等键同摘要重投，**不建第二个 Task**。

        锚 `FEAT-017:54`（创建类调用以租户加幂等键约束 Task 创建，重复投递
        不得创建多个 Task）。
        """
        uc, parts = _use_case()
        _run(uc, _raw())
        _run(uc, _raw(messageId="msg-2"))   # 新消息标识、同幂等键
        assert len(parts["admission"].rows) == 1
        assert len(parts["bridge"].calls) == 1, "重投又跑了一次桥接——Task 会被建两次"
        assert _kinds(parts["publisher"]) == ["ACCEPTED", "ACCEPTED"], (
            "重投须补发等价接受投影（FEAT-017:55）"
        )
        assert parts["publisher"].sent[0].task_id == parts["publisher"].sent[1].task_id

    def test_a_different_digest_under_the_same_key_is_a_conflict(self) -> None:
        """TC-017-013 同键异摘要即冲突：发拒绝、不建 Task。"""
        uc, parts = _use_case()
        _run(uc, _raw())
        outcome = _run(uc, _raw(messageId="msg-2", inlinePayload='{"method":"GetTask"}'))
        assert outcome.acknowledge is True
        assert _kinds(parts["publisher"]) == ["ACCEPTED", "REJECTED"]
        assert len(parts["bridge"].calls) == 1

    def test_the_admission_key_excludes_message_id(self) -> None:
        """准入键**不含消息标识**（`FEAT-017:53`）。

        混进去会让重试（新消息标识、同幂等键）建出第二个 Task。

        锚 `FEAT-017:129`：创建类调用以租户加幂等键为 Task 创建幂等键，
        重复投递不得创建多个 Task。
        """
        uc, parts = _use_case()
        _run(uc, _raw(messageId="msg-a"))
        _run(uc, _raw(messageId="msg-b"))
        assert list(parts["admission"].rows) == [
            AdmissionKey(tenant_id="t-1", idempotency_key="idem-1")
        ]

    def test_a_republished_projection_is_suppressed(self) -> None:
        """同一「租户 + 事件标识」不重复发布（`FEAT-017:55`）。

        锚 `FEAT-017:199`：幂等实现必须区分投递去重键与 Task 创建幂等键，
        不得把消息标识、幂等键、网关调用标识与任务标识混成一个语义——
        本条验的是**第三层**（投影发布幂等，键是「租户 + 事件标识」），
        它与前两层各用各的键，混用即失效。
        """
        uc, parts = _use_case()
        _run(uc, _raw())
        _run(uc, _raw())          # **同一条消息**重投：消息标识相同
        assert len(parts["projections"].seen) == 1, "同一条消息的接受投影发了两次"
        assert len(parts["publisher"].sent) == 1


class TestNonCreatingEvents:
    """查询、取消、订阅三类不建 Task（`FEAT-017:155`／`:157`）。"""

    @pytest.mark.parametrize(
        "event_type, kind",
        [
            ("CLIENT_INVOCATION_QUERY_REQUESTED", "RESPONSE"),
            ("A2A_CALL_QUERY_REQUESTED", "RESPONSE"),
            ("CLIENT_INVOCATION_CANCEL_REQUESTED", "TERMINAL"),
            ("A2A_CALL_CANCEL_REQUESTED", "TERMINAL"),
            ("CLIENT_STREAM_SUBSCRIBE_REQUESTED", "STREAM_READY"),
            ("A2A_STREAM_SUBSCRIBE_REQUESTED", "STREAM_READY"),
        ],
    )
    def test_these_never_create_an_admission_record(self, event_type, kind) -> None:
        """TC-017-002/003/004/006/007/008 六类非创建事件都不留准入记录。

        **取消那两类在本篇内**——上游裁剪它们的条件（FEAT-001 未交付取消）
        在本实现不成立，详设 §1.4。

        锚三条：`FEAT-017:68`（查询映射到标准 Task 查询、通常基于任务标识）、
        `FEAT-017:69`（取消映射到标准取消语义，不得只在总线侧标记）、
        `FEAT-017:70`（订阅基于任务标识准备流订阅，不得隐式创建新 Task）。
        """
        # **显式传流引用**：本参数化用例含订阅类事件，它们的投影正是
        # `*_STREAM_READY`；替身默认不带引用（默认带值会让每个非流式判据
        # 都变成流式）。
        uc, parts = _use_case(bridge=_Bridge(stream_ref="ref-opaque"))
        outcome = _run(uc, _raw(
            eventType=event_type,
            inlinePayload='{"method":"GetTask","params":{"id":"task-9"}}',
        ))
        assert outcome.acknowledge is True
        assert parts["admission"].rows == {}, "非创建事件建了准入记录"
        assert _kinds(parts["publisher"]) == [kind]

    def test_a_query_without_a_task_id_fails_rather_than_creating_one(self) -> None:
        """TC-017-026 查询不带任务标识时确定失败，**不隐式建 Task**（`FEAT-017:186`）。"""
        uc, parts = _use_case()
        outcome = _run(uc, _raw(eventType="CLIENT_INVOCATION_QUERY_REQUESTED"))
        assert outcome.acknowledge is True
        assert _kinds(parts["publisher"]) == ["FAILED"]
        assert parts["bridge"].calls == []


class TestPayloadHandling:
    """载荷处置。"""

    def test_a_ref_only_payload_fails_rather_than_being_parsed(self) -> None:
        """TC-017-027 只带引用 → 确定失败（`*_FAILED`），不是信封非法。

        两者的投影族不同：`*_REJECTED` 表达「请求不合格」，`*_FAILED` 表达
        「处理中确定失败」。判错族会让调用方走错处置路径。
        """
        raw = _raw(payloadRef="ref-1")
        raw.pop("inlinePayload")
        uc, parts = _use_case()
        outcome = _run(uc, raw)
        assert outcome.acknowledge is True
        assert _kinds(parts["publisher"]) == ["FAILED"]

    def test_a_lowercase_method_name_is_refused(self) -> None:
        """方法名用小写点分形式即拒（详设 §4.3）。

        `SendMessage` 是当前入口的名称；`message/send` 一类不接受。
        """
        uc, parts = _use_case()
        outcome = _run(uc, _raw(inlinePayload='{"method":"message/send"}'))
        assert outcome.acknowledge is True
        assert _kinds(parts["publisher"]) == ["FAILED"]

    def test_an_unparseable_payload_fails(self) -> None:
        """载荷不是合法 JSON → 确定失败（`FEAT-017:165`）。"""
        uc, parts = _use_case()
        outcome = _run(uc, _raw(inlinePayload="{ 这不是 JSON"))
        assert outcome.acknowledge is True
        assert _kinds(parts["publisher"]) == ["FAILED"]


class TestFailureAfterTaskExists:
    """Task 已建之后的失败（`FEAT-017:131`／`:167`）。"""

    def test_a_publish_failure_does_not_roll_back_the_task(self) -> None:
        """TC-017-025 发布失败**不回滚 Task**，保留可恢复状态。

        Task 已经建了，回滚它会让一个正在执行的 Agent 失去归属。

        锚两条：`FEAT-017:131`（Task 已创建但响应发布失败时，须允许通过后续
        状态投影、终态投影或查询恢复，不得依赖原始消费消息长期不确认）、
        `FEAT-017:48`（Task 进入等待输入态时必须发投影——它正是「后续状态投影」
        的一种，本条锁住 Task 不被回滚，那些投影才有对象）。
        """
        uc, parts = _use_case(publisher=_Publisher(raises=RuntimeError("发布通道断")))
        outcome = _run(uc, _raw())
        assert outcome.acknowledge is False, "发布失败属暂时不可用，应交给重投"
        assert len(parts["admission"].rows) == 1, "Task 的准入记录被回滚了"
        assert parts["admission"].rows[
            AdmissionKey(tenant_id="t-1", idempotency_key="idem-1")
        ].state is AdmissionState.ADMITTED

    def test_a_bridge_failure_after_reservation_lands_as_failed(self) -> None:
        """桥接抛出 → 该次调用失败，投影带任务标识（`FEAT-017:167`）。

        锚两条：`FEAT-017:169`（Task 进入等待输入时必须发投影并保留 correlation
        与 trace）、`FEAT-017:171`（Task 终态失败时必须发终态投影或 A2A 失败 Task
        语义并携带 correlation 与 trace）——本条钉住的是「失败也带标识与关联」，
        那是上述两条投影能被调用方对上号的前提。
        """
        uc, parts = _use_case(bridge=_Bridge(raises=RuntimeError("控制面拒绝")))
        outcome = _run(uc, _raw())
        assert outcome.acknowledge is True
        assert _kinds(parts["publisher"]) == ["FAILED"]
        assert parts["publisher"].sent[0].task_id, "Task 已预留，失败投影须带标识"


class TestTenantScopeViolationIsSilent:
    """租户越界时**只确认、不发任何投影**（`FEAT-017:56`／`:164`）。

    上游同法且逐字如此：其 `RuntimeBusEventConsumer.invalidEnvelope` 对
    `TENANT_SCOPE_VIOLATION` 直接 `BusConsumptionDecision.rejected(reason)` 返回，
    **不走 `rejected(envelope, reason)` 那条发投影的路**
    （`openJiuwen/agent-solution/common/agent-runtime-ext-java/agent-service-bus-consumer/`
    `src/main/java/com/openjiuwen/service/bus/consumer/RuntimeBusEventConsumer.java`）。

    **为什么这一类必须静默**：拒绝投影本身带着事件里那个租户标识发出去，
    而那正是一次跨租户写。「拒绝跨租户」的同时往对方租户发一条投影，
    等于用违规的方式宣告拒绝违规。其余各类信封非法不受此限——
    它们的租户是本 runtime 自己的，发投影不越界。

    这条是部署级 E2E 抓出来的：进程内判据只看「有没有拒绝」，
    而拒绝是以什么形态发生的要到真进程里读投影序列才看得见。
    """

    def test_a_foreign_tenant_event_produces_no_projection(self) -> None:
        """别的租户的事件：确认，且一条投影都不发。"""
        uc, parts = _use_case(tenant_id="t-mine")
        outcome = _run(uc, _raw(tenantId="t-someone-else"))

        assert outcome.acknowledge is True, "越界事件不确认会让 broker 一直重投"
        assert parts["publisher"].sent == [], (
            "往别的租户发了投影——拒绝跨租户的同时做了一次跨租户写："
            f"{[(p.event_type, p.tenant_id) for p in parts['publisher'].sent]}"
        )

    def test_other_envelope_faults_still_project(self) -> None:
        """**其余信封非法照常发拒绝投影**——静默只针对租户越界这一类。

        不加这条会有过度收敛：把「信封非法一律不发投影」当成修法时，
        调用方对自己发错的报文再也得不到任何反馈，而那些报文的租户
        本来就是它自己的，发投影不越界。
        """
        uc, parts = _use_case(tenant_id="t-mine")
        outcome = _run(uc, _raw(tenantId="t-mine", schemaVersion="9.9"))

        assert outcome.acknowledge is True
        assert [p.kind for p in parts["publisher"].sent] == [ProjectionKind.REJECTED]
        assert parts["publisher"].sent[0].tenant_id == "t-mine"


class TestTenantIsolation:
    """租户隔离（`FEAT-017:56`／`:164`）。"""

    def test_a_blank_tenant_is_refused(self) -> None:
        uc, parts = _use_case()
        outcome = _run(uc, _raw(tenantId=""))
        assert outcome.acknowledge is True
        assert _kinds(parts["publisher"]) == ["REJECTED"]

    def test_two_tenants_do_not_share_an_admission_record(self) -> None:
        """TC-017-019 同幂等键、不同租户 → 两条独立记录，**不跨租户复用**。"""
        uc, parts = _use_case()
        _run(uc, _raw(tenantId="t-1"))
        _run(uc, _raw(tenantId="t-2"))
        assert len(parts["admission"].rows) == 2
        assert len(parts["bridge"].calls) == 2, "跨租户复用了同一个 Task"


class TestSourceFamilyIsRestoredFromAdmission:
    """TC-017-014 投影族按**准入记录**恢复，不按当前消费的这条事件（`FEAT-017:195`）。"""

    def test_a_client_query_on_an_a2a_task_keeps_the_a2a_family(self) -> None:
        """服务间创建的 Task 被客户端事件查询时，投影仍是服务间族。

        **这是本条的全部意义**：按当前事件猜族，会让同一个 Task 的事件序列在
        两族之间跳，而调用方是按族订阅的，跳过去的那些它根本收不到。
        """
        uc, parts = _use_case()
        _run(uc, _raw(eventType="A2A_CALL_REQUESTED"))
        assert parts["publisher"].sent[0].family is EventFamily.A2A_CALL

        # 客户端族的事件带同一个幂等键再来——族要按准入记录里的来
        _run(uc, _raw(eventType="CLIENT_INVOCATION_REQUESTED", messageId="msg-2"))
        assert parts["publisher"].sent[-1].family is EventFamily.A2A_CALL, (
            f"族按当前事件猜了：{parts['publisher'].sent[-1].event_type}"
        )

    def test_a_client_query_on_an_a2a_task_also_keeps_the_family(self) -> None:
        """**控制类事件（查询）同样按准入记录恢复族**。

        上一条走的是创建类路径；本条走控制类路径——两条路径**各有一份族的取法**，
        独立复核实测：上一条对控制类那一半恒不发红（它的函数体发的是创建事件）。

        **这条能失败**：把控制类的族改回 `family_of(envelope.event_type)` 即转红。
        """
        uc, parts = _use_case()
        _run(uc, _raw(eventType="A2A_CALL_REQUESTED"))
        assert parts["publisher"].sent[0].family is EventFamily.A2A_CALL

        # 客户端族的**查询**事件，带同一个幂等键
        _run(uc, _raw(
            eventType="CLIENT_INVOCATION_QUERY_REQUESTED",
            messageId="msg-q",
            inlinePayload='{"method":"GetTask","params":{"id":"task-1"}}',
        ))
        last = parts["publisher"].sent[-1]
        assert last.kind is ProjectionKind.RESPONSE, f"不是响应投影：{last.kind}"
        assert last.family is EventFamily.A2A_CALL, (
            f"控制类事件的族按当前事件猜了：{last.event_type}"
        )

    def test_without_an_admission_record_it_falls_back(self) -> None:
        """查不到准入记录时回落到当前事件的族——**不是失败**。

        查询一个不是本 runtime 经总线建的 Task（例如 HTTP 直连建的）时没有记录可查，
        此时按来源族发是唯一选择：它至少让调用方在自己订阅的那一族里收得到回应。
        """
        uc, parts = _use_case()
        _run(uc, _raw(
            eventType="CLIENT_INVOCATION_QUERY_REQUESTED",
            inlinePayload='{"method":"GetTask","params":{"id":"task-unknown"}}',
        ))
        assert parts["publisher"].sent[-1].family is EventFamily.INVOCATION
