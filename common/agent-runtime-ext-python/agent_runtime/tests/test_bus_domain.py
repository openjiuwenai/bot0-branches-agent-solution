# coding: utf-8
# 超长行全在注释与文档串：权威路径必须连写才可复制跳转，Markdown 表格断行即损坏。
# 对齐上游 checkstyle 对 Javadoc 续行的同类排除；代码行宽由 ruff formatter 保证。
# pytest 类内判据保持实例方法：改 @staticmethod 后 coverage 的 test_function
# 动态上下文记不到它们——实测 35 条判据跑过且通过，覆盖数据里零上下文，
# 直接让 clause-evidence 上 76 条权威条款失去锚点。判据必须能被门禁看见。
# pylint: disable=line-too-long,no-self-use,add-staticmethod-or-classmethod-decorator


"""FEAT-017 领域层判据：事件信封、事件族、准入记录、投影事件。

## 这一层判什么

四类值对象的**构造期校验**与**取值语义**。它们是纯值对象——没有 IO、没有框架、
没有 broker 概念，判据因此可以直接构造并断言，不需要任何替身。

## 为什么领域层零依赖这件事要单独判

权威 `Technical-AF/docs/develop/02-features/FEAT-017-bus-event-subscription-consumption.md:58`
是 MUST：「runtime 特性文档、接口和业务 handler 不得依赖 RocketMQ、Kafka、topic、
offset、consumer group、broker retry 或 outbox 表」。这条约束**在类型上看不出来**——
一个 `dict` 字段里装 broker 句柄，静态检查一句话都不会说。故用 AST 判导入面，
并对字段名做黑名单。

## 每条判据怎样会失败

- 信封的必填校验被拿掉 → 构造期校验那一组转红
- 事件族按当前消费者猜而不是按准入记录 → `family_of` 那一组转红
- 准入状态机允许非法转移 → 状态机那一组转红
- 领域层引入任何第三方依赖 → AST 判据转红
"""
from __future__ import annotations

import ast
import pathlib
from dataclasses import FrozenInstanceError

import pytest

from agent_runtime.domain.bus.admission import (
    AdmissionKey,
    AdmissionOutcome,
    AdmissionRecord,
    AdmissionState,
)
from agent_runtime.domain.bus.envelope import (
    EnvelopeInvalid,
    parse_envelope,
)
from agent_runtime.domain.bus.event_types import (
    EventFamily,
    InboundEventType,
    ProjectionKind,
    family_of,
    projection_name,
)
from agent_runtime.domain.bus.projection import BusProjection

_NOW = 1_800_000_000.0


def _envelope(**over) -> dict:
    """一份合法的信封原始映射。判据按需覆盖单个字段。"""
    base = {
        "schemaVersion": "1.0",
        "eventType": "CLIENT_INVOCATION_REQUESTED",
        "messageId": "msg-1",
        "tenantId": "t-1",
        "sourceServiceId": "gateway",
        "targetServiceId": "billing-runtime",
        "correlationId": "corr-1",
        "traceId": "trace-1",
        "idempotencyKey": "idem-1",
        "deadline": _NOW + 60,
        "payloadContentType": "application/json",
        "inlinePayload": '{"method":"SendMessage"}',
    }
    base.update(over)
    return base


class TestEnvelopeValidation:
    """信封的构造期校验。**顺序即语义**（详设 §4.2）。"""

    def test_a_well_formed_envelope_parses(self) -> None:
        """TC-017-030 合法信封解析成功，字段逐项对上。

        锚 `FEAT-017:66`（信封须读出事件类型、消息标识、租户、来源、目标、
        correlation、trace、幂等、截止与载荷描述）。
        """
        env = parse_envelope(_envelope(), now=_NOW)
        assert env.tenant_id == "t-1"
        assert env.event_type is InboundEventType.CLIENT_INVOCATION_REQUESTED
        assert env.idempotency_key == "idem-1"
        assert env.inline_payload == '{"method":"SendMessage"}'

    @pytest.mark.parametrize(
        "field",
        ["schemaVersion", "messageId", "tenantId", "sourceServiceId",
         "targetServiceId", "correlationId", "traceId", "deadline"],
    )
    def test_every_required_field_is_enforced(self, field: str) -> None:
        """八个必填字段**逐个**验，不合并成一条。

        合并时任一校验失效都会被其他字段的命中掩盖，读数仍是「拒绝了」。
        """
        raw = _envelope()
        raw.pop(field)
        with pytest.raises(EnvelopeInvalid):
            parse_envelope(raw, now=_NOW)

    def test_tenant_is_checked_before_target(self) -> None:
        """租户校验排在目标匹配**之前**（`FEAT-017:109`、详设 §4.2）。

        **为什么顺序要判**：租户一旦缺失，目标匹配之后的每一次存储读写都失去
        隔离维度。两项同时不合格时，报出的必须是租户那一条——它是先失守的。

        **必须传 `service_id`**：不传时目标匹配那一步整个不执行
        （领域层不知道自己部署成谁），于是无论租户校验排在哪，最后抛出的
        都是租户那一条——**判据恒过**。变异实测确认过这个假锚点：
        把租户校验挪到目标匹配之后，不传 `service_id` 的那一版纹丝不动。
        """
        raw = _envelope(tenantId="", targetServiceId="someone-else")
        with pytest.raises(EnvelopeInvalid) as exc:
            parse_envelope(raw, now=_NOW, service_id="billing-runtime")
        assert "租户" in str(exc.value), f"先报的不是租户：{exc.value}"

    @pytest.mark.parametrize(
        "event_type",
        [
            "CLIENT_INVOCATION_REQUESTED",
            "CLIENT_INVOCATION_QUERY_REQUESTED",
            "CLIENT_INVOCATION_CANCEL_REQUESTED",
            "CLIENT_STREAM_SUBSCRIBE_REQUESTED",
        ],
    )
    def test_every_event_type_needs_an_idempotency_key(self, event_type) -> None:
        """幂等键**对全部事件类型必填**（`FEAT-017:54`／`:129`）。

        上游同法：其 `BusEnvelopeValidator.requiredFields` 把 `idempotencyKey()`
        与消息标识、租户、来源、目标、correlation、trace 并列，缺任一即 `INVALID_ENVELOPE`
        （`openJiuwen/agent-solution/common/agent-runtime-ext-java/agent-service-bus-consumer/`
        `openJiuwen/agent-solution/common/agent-runtime-ext-java/agent-service-bus-consumer/src/main/java/com/openjiuwen/service/bus/consumer/validation/BusEnvelopeValidator.java`）。

        **不只创建类**：创建类缺键会建出重复 Task（`:54` 直指这条）；
        控制类缺键则查不到那次创建的准入记录，投影族只能按当前事件猜——
        同一个 Task 的事件序列会在两族之间跳，而调用方是按族订阅的。

        **四类逐个验**：只验创建类时，控制类那三条路径缺键照样放行。
        """
        raw = _envelope(eventType=event_type, idempotencyKey="")
        with pytest.raises(EnvelopeInvalid) as exc:
            parse_envelope(raw, now=_NOW)
        assert "idempotencyKey" in str(exc.value)

    def test_an_event_for_another_tenant_is_refused(self) -> None:
        """**信封租户必须是本 runtime 的租户**（`FEAT-017:56`／`:164`）。

        上游同法且更早：其 `BusEnvelopeValidator` 在校验租户时逐字比对配置值
        （`openJiuwen/agent-solution/common/agent-runtime-ext-java/agent-service-bus-consumer/`
        `openJiuwen/agent-solution/common/agent-runtime-ext-java/agent-service-bus-consumer/src/main/java/com/openjiuwen/service/bus/consumer/validation/BusEnvelopeValidator.java`
        的 `validate`：租户为空**或与配置值不等**即不合格）。
        一个 runtime 实例只服务一个租户。

        **只判非空是不够的**：任何租户的事件投进本 runtime 的队列都会被消费、
        建出 Task，而 `:104` 逐字写「tenant 不匹配：runtime 必须拒绝消费」。
        对外表现是「一切正常」——别人的调用在这里被正常执行了。
        """
        raw = _envelope(tenantId="t-other")
        with pytest.raises(EnvelopeInvalid) as exc:
            parse_envelope(raw, now=_NOW, service_id=None, tenant_id="t-1")
        assert "租户" in str(exc.value), f"报的不是租户：{exc.value}"

    def test_the_matching_tenant_passes(self) -> None:
        """租户相符时照常受理——判的是不匹配，不是把消费关掉。"""
        env = parse_envelope(_envelope(tenantId="t-1"), now=_NOW, tenant_id="t-1")
        assert env.tenant_id == "t-1"

    def test_without_a_configured_tenant_the_check_is_skipped(self) -> None:
        """不配租户时这一步不执行——领域层不知道自己部署给了谁。

        与目标匹配同形（`service_id` 为 `None` 时跳过）。**这不是放宽**：
        装配层把配置值传下来，不传是判据与嵌入式用法的形态。
        """
        env = parse_envelope(_envelope(tenantId="t-anything"), now=_NOW)
        assert env.tenant_id == "t-anything"

    def test_a_mismatched_target_is_refused(self) -> None:
        """目标不是本 runtime 即拒——**不是「转发给别人」**。

        这一条与上一条配对：上一条判顺序，本条判目标匹配这一步真的在做事。
        少了它，把目标匹配整个删掉时上一条照样绿。
        """
        with pytest.raises(EnvelopeInvalid, match="不是本 runtime"):
            parse_envelope(
                _envelope(targetServiceId="someone-else"),
                now=_NOW, service_id="billing-runtime",
            )

    def test_an_expired_deadline_is_refused(self) -> None:
        """截止时刻已过即拒（`FEAT-017:43` 的字段约束）。"""
        with pytest.raises(EnvelopeInvalid, match="截止"):
            parse_envelope(_envelope(deadline=_NOW - 1), now=_NOW)

    def test_payload_must_be_exactly_one_of_the_two(self) -> None:
        """内联载荷与引用**二选一**：同时存在或同时缺失均拒。"""
        with pytest.raises(EnvelopeInvalid, match="二选一"):
            parse_envelope(_envelope(payloadRef="ref-1"), now=_NOW)
        raw = _envelope()
        raw.pop("inlinePayload")
        with pytest.raises(EnvelopeInvalid, match="二选一"):
            parse_envelope(raw, now=_NOW)

    def test_only_a_ref_parses_but_carries_no_payload(self) -> None:
        """只带引用时**解析得过**，但载荷为空——它是确定失败，不是信封非法。

        详设 §2.2：仅有引用的事件确定性返回空载荷、不进控制面桥，投射失败事件。
        把它判成信封非法会让对外投影错族（`*_REJECTED` 而不是 `*_FAILED`）。
        """
        raw = _envelope(payloadRef="ref-1")
        raw.pop("inlinePayload")
        env = parse_envelope(raw, now=_NOW)
        assert env.payload_ref == "ref-1"
        assert env.inline_payload == ""
        assert not env.has_usable_payload

    def test_an_unsupported_major_version_is_refused(self) -> None:
        """主版本不受支持即拒，不尝试兼容解析。"""
        with pytest.raises(EnvelopeInvalid, match="版本"):
            parse_envelope(_envelope(schemaVersion="2.0"), now=_NOW, schema_major=1)

    def test_an_unknown_event_type_is_refused(self) -> None:
        """事件类型不在八类之内即拒。"""
        with pytest.raises(EnvelopeInvalid, match="事件类型"):
            parse_envelope(_envelope(eventType="SOMETHING_ELSE"), now=_NOW)

    def test_route_handle_is_carried_but_not_parsed(self) -> None:
        """路由句柄原样携带，**不解析为端点**（`FEAT-017:43`）。"""
        env = parse_envelope(_envelope(routeHandle="opaque-xyz"), now=_NOW)
        assert env.route_handle == "opaque-xyz"

    def test_the_envelope_is_immutable(self) -> None:
        """信封是值对象，构造后不可改——每请求独立、不共享可变状态。

        **收窄到 `FrozenInstanceError`，不用裸 `Exception`**：后者会被任何异常满足，
        包括属性名拼错时的 `AttributeError`——那时判据通过，而不可变性根本没被验。
        """
        env = parse_envelope(_envelope(), now=_NOW)
        with pytest.raises(FrozenInstanceError):
            env.tenant_id = "t-2"  # type: ignore[misc]


class TestEventFamily:
    """事件族：客户端来源与服务间来源两族。"""

    @pytest.mark.parametrize(
        "event_type, family",
        [
            (InboundEventType.CLIENT_INVOCATION_REQUESTED, EventFamily.INVOCATION),
            (InboundEventType.CLIENT_INVOCATION_QUERY_REQUESTED, EventFamily.INVOCATION),
            (InboundEventType.CLIENT_INVOCATION_CANCEL_REQUESTED, EventFamily.INVOCATION),
            (InboundEventType.CLIENT_STREAM_SUBSCRIBE_REQUESTED, EventFamily.INVOCATION),
            (InboundEventType.A2A_CALL_REQUESTED, EventFamily.A2A_CALL),
            (InboundEventType.A2A_CALL_QUERY_REQUESTED, EventFamily.A2A_CALL),
            (InboundEventType.A2A_CALL_CANCEL_REQUESTED, EventFamily.A2A_CALL),
            (InboundEventType.A2A_STREAM_SUBSCRIBE_REQUESTED, EventFamily.A2A_CALL),
        ],
    )
    def test_every_inbound_type_maps_to_a_family(self, event_type, family) -> None:
        """TC-017-031 八类入站事件**逐个**归族。

        **八类全在**：权威 `FEAT-017:40`（客户端四类）与 `FEAT-017:41`（服务间四类）。
        取消那两类在本篇内——详设 §1.4：上游的裁剪自带条件，本实现不满足该条件。

        另锚 `FEAT-017:65`：订阅能力的存在性由本组的八类覆盖表达。
        """
        assert family_of(event_type) is family

    @pytest.mark.parametrize(
        "kind, family, expected",
        [
            (ProjectionKind.ACCEPTED, EventFamily.INVOCATION, "INVOCATION_ACCEPTED"),
            (ProjectionKind.ACCEPTED, EventFamily.A2A_CALL, "A2A_CALL_ACCEPTED"),
            (ProjectionKind.REJECTED, EventFamily.INVOCATION, "INVOCATION_REJECTED"),
            (ProjectionKind.REJECTED, EventFamily.A2A_CALL, "A2A_CALL_REJECTED"),
            (ProjectionKind.FAILED, EventFamily.A2A_CALL, "A2A_CALL_FAILED"),
            (ProjectionKind.RESPONSE, EventFamily.INVOCATION, "INVOCATION_RESPONSE"),
            (ProjectionKind.INPUT_REQUIRED, EventFamily.A2A_CALL, "A2A_CALL_INPUT_REQUIRED"),
            (ProjectionKind.TERMINAL, EventFamily.INVOCATION, "INVOCATION_TERMINAL"),
            (ProjectionKind.TERMINAL, EventFamily.A2A_CALL, "A2A_CALL_TERMINAL"),
        ],
    )
    def test_projection_names_follow_the_family(self, kind, family, expected) -> None:
        """投影事件名按族拼。

        锚三条：`FEAT-017:40`（客户端族四类事件的消费）、`FEAT-017:41`
        （服务间族四类事件的消费）、`FEAT-017:65`（订阅消费能力本身，
        具体的 topic 与 broker 客户端由本层之外固化）——三条都表现为
        「两族各自成套、名称不混」，本组按族逐项比对。
        """
        assert projection_name(kind, family) == expected

    def test_stream_ready_is_the_one_asymmetric_name(self) -> None:
        """**流准备是唯一不对称的一对**：客户端族是 `INVOCATION_STREAM_READY`，
        服务间族是 `A2A_STREAM_READY`——**不是** `A2A_CALL_STREAM_READY`。

        这条单独写是因为它是唯一的例外，按规律拼会拼错。
        权威 `FEAT-017:80` 逐字给的就是这两个名字。
        """
        assert projection_name(ProjectionKind.STREAM_READY, EventFamily.INVOCATION) == "INVOCATION_STREAM_READY"
        assert projection_name(ProjectionKind.STREAM_READY, EventFamily.A2A_CALL) == "A2A_STREAM_READY"


class TestAdmission:
    """准入记录与状态机（详设 §4.2）。"""

    @staticmethod
    def _record(**over) -> AdmissionRecord:
        base = dict(
            key=AdmissionKey(tenant_id="t-1", idempotency_key="idem-1"),
            task_id="task-1",
            family=EventFamily.INVOCATION,
            correlation_id="corr-1",
            trace_id="trace-1",
            request_digest="dig-1",
            created_at=_NOW,
        )
        base.update(over)
        return AdmissionRecord(**base)  # type: ignore[arg-type]

    def test_a_fresh_record_starts_reserved(self) -> None:
        """新建记录起始于「已预留」。"""
        assert self._record().state is AdmissionState.RESERVED

    def test_reserved_can_be_admitted(self) -> None:
        assert self._record().admitted().state is AdmissionState.ADMITTED

    def test_reserved_can_be_rejected(self) -> None:
        assert self._record().rejected("坏载荷").state is AdmissionState.REJECTED

    def test_admitted_cannot_go_back_to_rejected(self) -> None:
        """**已受理不能退回已拒绝**（详设 §4.4）。

        「未创建 Task 的拒绝」与「已创建后的终态拒绝」是两件事，调用方据它们
        区分「请求没被受理」与「受理了但结果是拒绝」。允许倒退会让后者被当前者重试。
        """
        with pytest.raises(ValueError, match="不能"):
            self._record().admitted().rejected("晚到的拒绝")

    def test_rejected_cannot_be_admitted(self) -> None:
        """已拒绝不能再受理——拒绝是终局。"""
        with pytest.raises(ValueError, match="不能"):
            self._record().rejected("坏载荷").admitted()

    def test_the_key_is_tenant_plus_idempotency_key(self) -> None:
        """TC-017-032 准入键是「租户 + 幂等键」，**不含消息标识**（`FEAT-017:53`）。

        混进消息标识会让重试（新消息标识、同幂等键）建出第二个 Task——
        那正是 `FEAT-017:54` 要防的事。
        """
        k1 = AdmissionKey(tenant_id="t-1", idempotency_key="idem-1")
        k2 = AdmissionKey(tenant_id="t-1", idempotency_key="idem-1")
        assert k1 == k2 and hash(k1) == hash(k2)
        assert k1 != AdmissionKey(tenant_id="t-2", idempotency_key="idem-1")

    def test_a_same_digest_replay_is_a_reuse_not_a_conflict(self) -> None:
        """同键同摘要 → 复用；同键异摘要 → 冲突。"""
        rec = self._record()
        assert rec.outcome_for("dig-1") is AdmissionOutcome.REUSED
        assert rec.outcome_for("dig-2") is AdmissionOutcome.CONFLICT


class TestProjection:
    """投影事件值对象。"""

    @staticmethod
    def _proj(**over) -> BusProjection:
        base = dict(
            kind=ProjectionKind.ACCEPTED,
            family=EventFamily.INVOCATION,
            tenant_id="t-1",
            event_id="evt-1",
            causation_message_id="msg-1",
            correlation_id="corr-1",
            trace_id="trace-1",
            task_id="task-1",
            occurred_at=_NOW,
        )
        base.update(over)
        return BusProjection(**base)  # type: ignore[arg-type]

    def test_the_wire_name_comes_from_kind_and_family(self) -> None:
        """投影名由类别与来源族拼出。

        锚三条：`FEAT-017:75`（接受投影表达 Task 已创建或复用）、
        `FEAT-017:76`（拒绝投影表达明确拒绝且未创建 Task）、
        `FEAT-017:77`（失败投影表达消费或处理时的确定失败）。
        """
        assert self._proj().event_type == "INVOCATION_ACCEPTED"
        assert self._proj(family=EventFamily.A2A_CALL).event_type == "A2A_CALL_ACCEPTED"

    def test_rejected_must_not_carry_a_task_id(self) -> None:
        """TC-017-033 拒绝投影**不得伪造任务标识**（上游 §3.3 的字段表）。

        它表达「未创建 Task」，带上标识会让调用方去查一个不存在的 Task。
        """
        with pytest.raises(ValueError, match="任务标识"):
            self._proj(kind=ProjectionKind.REJECTED, task_id="task-1")

    @pytest.mark.parametrize(
        "kind",
        [ProjectionKind.ACCEPTED, ProjectionKind.RESPONSE,
         ProjectionKind.INPUT_REQUIRED, ProjectionKind.STREAM_READY,
         ProjectionKind.TERMINAL],
    )
    def test_these_kinds_require_a_task_id(self, kind) -> None:
        """五类投影**必须**带任务标识（上游 §3.3 的字段表逐条）。

        锚两条：`FEAT-017:79`（等待输入投影须携带任务标识、输入需求描述、
        correlation 与可恢复上下文引用）、`FEAT-017:51`（终态投影用于调用方
        收尾、审计与恢复）。
        """
        with pytest.raises(ValueError, match="任务标识"):
            self._proj(kind=kind, task_id="")

    def test_stream_ready_requires_a_stream_ref(self) -> None:
        """流准备必须带流引用（`FEAT-017:49`、`FEAT-017:80`）。

        另锚 `FEAT-017:82`：流引用是内部可解析引用、不是公开物理端点，
        调用方必须与任务标识一起使用——本条钉住「没有引用就构造不出流准备投影」
        这一半，形态那一半由流引用服务的判据守。
        """
        with pytest.raises(ValueError, match="流引用"):
            self._proj(kind=ProjectionKind.STREAM_READY, stream_ref="")

    def test_no_projection_carries_stream_frames(self) -> None:
        """TC-017-034 投影载荷里**不得有流块或 SSE 帧**（`FEAT-017:57`）。

        实时流内容由 A2A SSE 承载，总线上只有「流可订阅了」这个事实。
        """
        with pytest.raises(ValueError, match="流"):
            self._proj(inline_payload={"chunks": ["a", "b"], "event": "message"})

    def test_the_projection_is_immutable(self) -> None:
        """投影是值对象。**收窄到 `FrozenInstanceError`**，理由同信封那一条。"""
        with pytest.raises(FrozenInstanceError):
            self._proj().task_id = "task-2"  # type: ignore[misc]


class TestDomainPurity:
    """TC-017-020 领域层**与端口层**零 broker 依赖（`FEAT-017:58`）。

    **判 AST 的导入面，不全文搜字符串**——注释里出现 `kafka` 不构成依赖，
    而搜字符串会把说明文字判成违规，那种噪声会让人把整道判据静音。
    """

    _BANNED_MODULES = (
        "rocketmq", "kafka", "confluent_kafka", "pika", "aiokafka",
        "redis", "httpx", "fastapi", "a2a",
    )
    #: 字段名黑名单：类型上看不出来的那一类泄漏。
    _BANNED_FIELDS = ("offset", "topic", "partition", "consumer_group", "broker", "ack_handle")

    @staticmethod
    def _domain_modules() -> list[pathlib.Path]:
        """考核面 = 领域层**加端口层**。

        **权威逐字点名「接口」**：`FEAT-017:58` 写的是「特性文档、接口和业务 handler
        不得依赖 RocketMQ、Kafka、topic、offset、consumer group、broker retry 或
        outbox/inbox 等具体实现细节」。只扫领域层时，往端口协议上加一个 `topic`
        字段不会有任何东西红——而端口正是权威点名的那个「接口」，
        它一旦带上 broker 概念，每个实现方都被迫认那个概念，换 broker 就要改端口。
        实测过：只扫领域层时，往 `BusDeliveryPort` 加 `broker` 字段，本文件全绿。
        """
        base = pathlib.Path(__file__).resolve().parents[1]
        roots = [base / "domain" / "bus", base / "ports"]
        mods: list[pathlib.Path] = []
        for root in roots:
            # **目录缺失即红，不静默跳过**：`continue` 会让考核面在改名时悄悄缩小。
            assert root.is_dir(), f"考核面目录不存在：{root}"
            if root.name == "ports":
                # 端口层只取本特性的那一份：其余端口属别的特性，
                # 它们的依赖面由各自的判据管，在这里连坐会产出跨特性的噪声。
                found = sorted(root.glob("bus.py"))
                assert found, f"端口层没有 bus.py——考核面缺一半（{root}）"
            else:
                found = sorted(root.glob("*.py"))
            assert found, f"{root} 一个模块都没有——考核面为空时本判据恒过"
            mods.extend(found)
        return mods

    def test_no_third_party_imports(self) -> None:
        for path in self._domain_modules():
            tree = ast.parse(path.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                names: list[str] = []
                if isinstance(node, ast.Import):
                    names = [a.name for a in node.names]
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names = [node.module]
                for name in names:
                    head = name.split(".")[0]
                    assert head not in self._BANNED_MODULES, (
                        f"{path.name} 引入了 {name}——领域层须零外部依赖（FEAT-017:58）"
                    )

    def test_no_broker_flavoured_field_names(self) -> None:
        """字段名里不出现 broker 概念。

        **类型上看不出来**：一个 `str` 字段叫 `topic`，静态检查一句话都不会说，
        而它一旦被读，换 broker 就要改领域代码。
        """
        for path in self._domain_modules():
            tree = ast.parse(path.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
                    field = node.target.id.lower()
                    for banned in self._BANNED_FIELDS:
                        assert banned not in field, (
                            f"{path.name} 的字段 `{node.target.id}` 带 broker 概念（FEAT-017:58）"
                        )
