# coding: utf-8

"""信封逐字段比较器。

对照 frozen_facts 冻结面检查信封的字段集合、顺序与条件字段出现规则，
返回违规清单（空清单 = 通过）。本模块是 T0-0 快照基线的判定核心，
T0-3 EDPAgent 迁移门（事件到定制信封逐字段一致）复用同一比较器。

判定语义：
- 字段**顺序**参与判定——存量客户端与逐字节比对依赖 json.dumps 的
  dict 插入序，顺序漂移视同兼容破坏；
- 条件字段（display / error_code）按"出现规则"双向判定：该出现时
  缺失、不该出现时出现，均为违规。
"""
from __future__ import annotations

import json
from typing import Any, List, Mapping

from oracle_support.regression_baseline import frozen_facts as ff


def _check_field_order(
    actual: Mapping[str, Any], expected: tuple, *, where: str
) -> List[str]:
    actual_keys = list(actual.keys())
    expected_keys = list(expected)
    if actual_keys != expected_keys:
        return [
            f"{where}: 字段集合或顺序偏离冻结面 actual={actual_keys} expected={expected_keys}"
        ]
    return []


def check_agent_event(
    envelope: Mapping[str, Any],
    *,
    event_type: str,
    display_expected: bool = False,
) -> List[str]:
    """agent event 信封（7 外层字段 + custom_rsp_data 6 字段 + 条件字段）。"""
    violations: List[str] = []

    expected_outer = list(ff.AGENT_EVENT_FIELDS)
    if event_type in ff.EVENTS_WITH_ERROR_CODE:
        expected_outer.append("error_code")
    violations += _check_field_order(envelope, tuple(expected_outer), where="agent 外层")

    if event_type in ff.EVENTS_WITH_ERROR_CODE and envelope.get("error_code") != "":
        violations.append(
            f"agent 外层: {event_type} 的 error_code 必须为空串，实际 {envelope.get('error_code')!r}"
        )

    crd = envelope.get("custom_rsp_data")
    if not isinstance(crd, Mapping):
        violations.append("agent 外层: custom_rsp_data 缺失或非对象")
        return violations

    expected_crd = list(ff.AGENT_CUSTOM_RSP_DATA_FIELDS)
    if display_expected:
        expected_crd.append(ff.AGENT_OPTIONAL_DISPLAY_FIELD)
    violations += _check_field_order(crd, tuple(expected_crd), where="agent custom_rsp_data")

    if crd.get("event") != event_type:
        violations.append(
            f"agent custom_rsp_data: event 位应为 {event_type!r}，实际 {crd.get('event')!r}"
        )
    return violations


def check_workflow_event(envelope: Mapping[str, Any]) -> List[str]:
    """workflow event 信封（5 字段，无 output / error / error_code）。"""
    violations = _check_field_order(
        envelope, ff.WORKFLOW_EVENT_FIELDS, where="workflow 外层"
    )
    for forbidden in ("output", "error", "error_code"):
        if forbidden in envelope:
            violations.append(f"workflow 外层: 禁止出现字段 {forbidden!r}")
    crd = envelope.get("custom_rsp_data")
    if isinstance(crd, Mapping):
        violations += _check_field_order(
            crd, ff.WORKFLOW_CUSTOM_RSP_DATA_FIELDS, where="workflow custom_rsp_data"
        )
    else:
        violations.append("workflow 外层: custom_rsp_data 缺失或非对象")
    return violations


def check_sub_task_event(envelope: Mapping[str, Any]) -> List[str]:
    """sub_task event 信封（7 字段 + 四键 custom_rsp_data）。"""
    violations = _check_field_order(
        envelope, ff.SUB_TASK_EVENT_FIELDS, where="sub_task 外层"
    )
    crd = envelope.get("custom_rsp_data")
    if not isinstance(crd, Mapping):
        violations.append("sub_task 外层: custom_rsp_data 缺失或非对象")
        return violations
    violations += _check_field_order(
        crd, ff.SUB_TASK_CUSTOM_RSP_DATA_FIELDS, where="sub_task custom_rsp_data"
    )
    if crd.get("event") != ff.SUB_TASK_EVENT_TYPE:
        violations.append(
            f"sub_task custom_rsp_data: event 位应为 {ff.SUB_TASK_EVENT_TYPE!r}，实际 {crd.get('event')!r}"
        )
    return violations


def check_error_envelope(envelope: Mapping[str, Any]) -> List[str]:
    """错误信封（6 字段，无 custom_rsp_data）。"""
    violations = _check_field_order(
        envelope, ff.ERROR_ENVELOPE_FIELDS, where="error 信封"
    )
    if "custom_rsp_data" in envelope:
        violations.append("error 信封: 禁止出现 custom_rsp_data")
    if envelope.get("success") is not False:
        violations.append("error 信封: success 必须为 False")
    return violations


def check_rate_limit_rejection(envelope: Mapping[str, Any]) -> List[str]:
    """429 拒绝体（独立 5 字段形态：error 非 error_msg、无 execution_time）。"""
    violations = _check_field_order(
        envelope, ff.RATE_LIMIT_REJECTION_FIELDS, where="429 拒绝体"
    )
    if envelope.get("error_code") != ff.ERROR_CODE_RATE_LIMIT:
        violations.append(
            f"429 拒绝体: error_code 应为 {ff.ERROR_CODE_RATE_LIMIT!r}，实际 {envelope.get('error_code')!r}"
        )
    return violations


def check_outbound_va_message(request: Any, *, conversation_id: str) -> List[str]:
    """S1 出向 A2A 报文（SendMessageRequest）逐字段判定（M0.11）。

    形态：message.context_id=conversation_id、task_id 空串、role=user、
    parts=[text(query), data(Struct)]，DataPart 键集合 {body, params, headers}。
    """
    from google.protobuf.json_format import MessageToDict

    violations: List[str] = []
    message = request.message
    if message.context_id != conversation_id:
        violations.append(
            f"S1 报文: context_id 应为会话 id {conversation_id!r}，实际 {message.context_id!r}"
        )
    if message.task_id != ff.OUTBOUND_TASK_ID:
        violations.append(f"S1 报文: task_id 应为空串，实际 {message.task_id!r}")
    if message.role != ff.OUTBOUND_ROLE_USER:
        violations.append(f"S1 报文: role 应为 user(1)，实际 {message.role!r}")
    if len(message.parts) != 2:
        violations.append(f"S1 报文: parts 应为 2 段，实际 {len(message.parts)}")
        return violations
    kinds = [part.WhichOneof("content") for part in message.parts]
    if kinds != ["text", "data"]:
        violations.append(f"S1 报文: parts 顺序应为 [text, data]，实际 {kinds}")
        return violations
    data_keys = frozenset(MessageToDict(message.parts[1].data).keys())
    if data_keys != ff.OUTBOUND_DATA_PART_KEYS:
        violations.append(
            f"S1 报文: DataPart 键集合偏离冻结面 actual={sorted(data_keys)} "
            f"expected={sorted(ff.OUTBOUND_DATA_PART_KEYS)}"
            + ("（headers 透传缺失=复发 issue 2026-04-28）" if "headers" not in data_keys else "")
        )
    return violations


def check_sse_frame(frame: str) -> List[str]:
    """标准 SSE 帧：``data: <JSON ensure_ascii=False>\\n\\n``。"""
    violations: List[str] = []
    if not frame.startswith(ff.SSE_FRAME_PREFIX):
        violations.append(f"SSE 帧: 前缀应为 {ff.SSE_FRAME_PREFIX!r}")
    if not frame.endswith(ff.SSE_FRAME_SUFFIX):
        violations.append("SSE 帧: 应以两个换行符终止")
    body = frame[len(ff.SSE_FRAME_PREFIX):].rstrip("\n")
    try:
        json.loads(body)
    except (ValueError, TypeError):
        violations.append("SSE 帧: 载荷不是合法 JSON")
    return violations
