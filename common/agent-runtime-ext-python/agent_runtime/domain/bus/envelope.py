# coding: utf-8

"""事件信封：runtime 中立的事件外层结构。

**它不规定总线的线上序列化格式**——适配层负责把线上契约映射成它
（权威 `Technical-AF/docs/develop/02-features/`CL-d99eb92a6b6e``：
「A2A JSON-RPC 只能作为载荷」，外层字段由本结构承载）。

## 校验顺序即语义

权威 `CL-7dfb2059624d` 逐字要求「消费事件时必须**先**按信封校验租户、目标、schema、
截止、载荷描述和 correlation，**再**进入 Task 控制面」。本模块的 `parse_envelope`
按详设 §4.2 的六步顺序执行，**顺序不可调换**：

租户排在目标匹配之前，是因为它一旦缺失，目标匹配之后的每一次存储读写都失去隔离维度。
把它放后面，等于让一条无租户的事件先走完目标匹配与截止判断——那两步都会去读带租户
前缀的键。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Optional

from agent_runtime.domain.bus.event_types import InboundEventType


class EnvelopeInvalid(ValueError):
    """信封校验不通过。

    字段 tenant_scope_violation：**这一类是不是「租户越界」**。
    调用方据它决定要不要发拒绝投影——往越界事件里那个租户发投影
    本身就是一次跨租户写（`CL-080a2ff2b87f`／`:164`）。
    **分类放在抛出点而不是靠解析错误文案**：按文案判会在改一句提示语时
    静默失效，而失效的表现是「拒绝照常发生」，没有任何东西会红。
    """

    def __init__(self, message: str, *, tenant_scope_violation: bool = False) -> None:
        super().__init__(message)
        self.tenant_scope_violation = tenant_scope_violation


@dataclass(frozen=True)
class BusEventEnvelope:
    """一条入站事件的中立结构。

    **不可变**：每请求独立、不共享可变状态（根设计 §9.2 的横切并发不变量）。
    """

    schema_version: str
    event_type: InboundEventType
    message_id: str
    tenant_id: str
    source_service_id: str
    target_service_id: str
    correlation_id: str
    trace_id: str
    deadline: float
    idempotency_key: str = ""
    payload_content_type: str = ""
    inline_payload: str = ""
    payload_ref: str = ""
    #: 路由句柄。**只用于目标核验与审计，不解析为公开物理端点**（`CL-d99eb92a6b6e`）。
    route_handle: str = ""
    metadata: Mapping[str, str] = None  # type: ignore[assignment]

    def __post_init__(self) -> None:
        if self.metadata is None:
            object.__setattr__(self, "metadata", {})

    @property
    def has_usable_payload(self) -> bool:
        """本事件带得出可用载荷吗。

        **仅有引用时为假**：引用协议尚未定义，把引用串当 A2A JSON 解析会产出
        无法诊断的解析错误。此时确定性返回空载荷、投射 `*_FAILED`
        （详设 §2.2）——它是**确定失败**，不是信封非法，两者的投影族不同。
        """
        return bool(self.inline_payload)


def _text(raw: Mapping[str, Any], key: str) -> str:
    value = raw.get(key)
    return str(value).strip() if value is not None else ""


def parse_envelope(
    raw: Mapping[str, Any],
    *,
    now: float,
    schema_major: int = 1,
    service_id: Optional[str] = None,
    tenant_id: Optional[str] = None,
) -> BusEventEnvelope:
    """把线上映射解析成中立信封，按详设 §4.2 的六步顺序校验。

    参数 service_id：本 runtime 的服务身份。**给了才校验目标匹配**——
    领域层不知道自己部署成谁，那是装配期的事实。不给时跳过第三步，
    由调用方另行核（用例层持有它）。

    参数 now：当前时刻。**由调用方给，不在这里读时钟**——领域层读时钟会让
    判据无法构造「截止刚好过期」这类边界。
    """
    # 第一步：schema 版本。版本不认得时，后面每个字段的解释都不可信。
    version = _text(raw, "schemaVersion")
    if not version:
        raise EnvelopeInvalid("信封缺 schemaVersion")
    major = version.split(".")[0]
    if not major.isdigit() or int(major) != schema_major:
        raise EnvelopeInvalid(
            f"schema 主版本 {version!r} 不受支持（本 runtime 支持 {schema_major}）"
        )

    # 第二步：租户。**排在目标匹配之前**，理由见模块文档。
    envelope_tenant = _text(raw, "tenantId")
    if not envelope_tenant:
        raise EnvelopeInvalid("信封缺租户标识——它是后续每一次读写的隔离维度")
    # **必须是本 runtime 的租户**（`CL-080a2ff2b87f`／`:164`「tenant 不匹配：
    # runtime 必须拒绝消费」）。上游同法：其信封校验逐字比对配置值。
    #
    # 只判非空是不够的：任何租户的事件投进本队列都会被消费、建出 Task，
    # 而对外表现是「一切正常」——别人的调用在这里被正常执行了。
    #
    # 未配租户时跳过，与目标匹配同形：领域层不知道自己部署给了谁，
    # 配置值由装配层传下来。
    if tenant_id is not None and envelope_tenant != tenant_id:
        raise EnvelopeInvalid(
            f"事件租户 {envelope_tenant!r} 不是本 runtime 的租户（{tenant_id!r}）",
            tenant_scope_violation=True,
        )

    # 第三步：目标匹配本 runtime。不匹配即拒，不是「转发给别人」。
    target = _text(raw, "targetServiceId")
    if not target:
        raise EnvelopeInvalid("信封缺 targetServiceId")
    if service_id is not None and target != service_id:
        raise EnvelopeInvalid(
            f"事件目标 {target!r} 不是本 runtime（{service_id!r}）"
        )

    # 幂等键与其余关联字段同档必填（`CL-1497315dc055`／`:129`）。上游同法：
    # 其信封校验把它与消息标识、租户、来源、目标、correlation、trace 并列。
    #
    # **不只创建类需要它**：创建类缺键会建出重复 Task；控制类缺键则查不到
    # 那次创建的准入记录，投影族只能按当前事件猜——同一个 Task 的事件序列
    # 会在两族之间跳，而调用方是按族订阅的，跳过去的那些它根本收不到。
    for field, label in (
        ("messageId", "messageId"),
        ("sourceServiceId", "sourceServiceId"),
        ("correlationId", "correlationId"),
        ("traceId", "traceId"),
        ("idempotencyKey", "idempotencyKey"),
    ):
        if not _text(raw, field):
            raise EnvelopeInvalid(f"信封缺 {label}")

    event_type_raw = _text(raw, "eventType")
    try:
        event_type = InboundEventType(event_type_raw)
    except ValueError as exc:
        raise EnvelopeInvalid(f"事件类型 {event_type_raw!r} 不在受支持的八类之内") from exc

    # 第四步：截止时刻。接收时已过期不得创建或修改 Task。
    deadline_raw = raw.get("deadline")
    if deadline_raw is None:
        raise EnvelopeInvalid("信封缺 deadline")
    try:
        deadline = float(deadline_raw)
    except (TypeError, ValueError) as exc:
        raise EnvelopeInvalid(f"deadline 不是时刻值：{deadline_raw!r}") from exc
    if deadline <= now:
        raise EnvelopeInvalid(f"事件截止时刻已过（{deadline} <= {now}）")

    # 第五步：载荷二选一。同时存在或同时缺失均拒。
    inline = _text(raw, "inlinePayload")
    ref = _text(raw, "payloadRef")
    if bool(inline) == bool(ref):
        raise EnvelopeInvalid(
            "内联载荷与载荷引用须二选一（同时存在或同时缺失均不合法）"
        )

    return BusEventEnvelope(
        schema_version=version,
        event_type=event_type,
        message_id=_text(raw, "messageId"),
        tenant_id=envelope_tenant,
        source_service_id=_text(raw, "sourceServiceId"),
        target_service_id=target,
        correlation_id=_text(raw, "correlationId"),
        trace_id=_text(raw, "traceId"),
        deadline=deadline,
        idempotency_key=_text(raw, "idempotencyKey"),
        payload_content_type=_text(raw, "payloadContentType"),
        inline_payload=inline,
        payload_ref=ref,
        route_handle=_text(raw, "routeHandle"),
        metadata=dict(raw.get("metadata") or {}),
    )
