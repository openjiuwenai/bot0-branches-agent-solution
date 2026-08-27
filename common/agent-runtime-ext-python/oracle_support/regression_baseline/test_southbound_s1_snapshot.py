# coding: utf-8

"""南向 S1 出向面快照（M0.11/M0.12 + CB-f）。

S1 = a2a_service → versatile_adapter 的 A2A 报文（SendMessageRequest）。
TO-BE 中该跳被 VersatileAgentRuntimeHandler（FEAT-002 异构 Agent adapter）
的进程内调用取代（T2-4）；兼容期内独立应用形态继续有效，本面为存量参照。
"""
from __future__ import annotations

from api.dispatch import _build_request
from channels.base import ParsedRequest
from channels.mobile_bank_channel import MobileBankChannel
from google.protobuf.json_format import MessageToDict
from oracle_support.regression_baseline import comparator

CONV_ID = "conv-s1"
_BODY = {"custom_data": {"inputs": {"UNION_NO": "u-1"}}}
_HEADERS = {"cust-token": "tok", "x-user-id": "user-1"}
_PARAMS = {"stream": "true"}


def _via_channel():
    parsed = ParsedRequest(
        conversation_id=CONV_ID,
        agent_id="agent-a",
        query="查询余额",
        body=_BODY,
        headers=_HEADERS,
        params=_PARAMS,
    )
    return MobileBankChannel().build_message(parsed)


def _via_dispatch():
    return _build_request(
        CONV_ID, "查询余额", _BODY, params=_PARAMS, headers=_HEADERS
    )


# ── CB-f：被禁形态必须被判不通过 ────────────────────────────────────────


def test_cb_f_dropped_headers_key_must_fail():
    """CB-f：DataPart 丢 headers 键（透传被"清理"）→ 必须不通过。

    该形态即 issue 2026-04-28 的缺陷原貌：下游收不到 cust-token / x-user-id。
    """
    stub = _build_request(CONV_ID, "q", {"body": {}}, params=None, headers=None)
    # 重构一个只含 body/params 的 DataPart，模拟 headers 被清理
    from google.protobuf.struct_pb2 import Struct, Value

    stripped = Struct()
    stripped.update({"body": {}, "params": {}})
    value = Value()
    value.struct_value.CopyFrom(stripped)
    stub.message.parts[1].data.CopyFrom(value)

    violations = comparator.check_outbound_va_message(stub, conversation_id=CONV_ID)
    assert violations, "CB-f 未被判不通过：headers 透传缺失未被比较器捕捉"


def test_cb_f_wrong_parts_order_must_fail():
    """parts 顺序漂移（data 前置）→ 必须不通过。"""
    request = _via_dispatch()
    request.message.parts.reverse()
    violations = comparator.check_outbound_va_message(
        request, conversation_id=CONV_ID
    )
    assert violations


# ── 假阳性对照 + 快照 ───────────────────────────────────────────────────


def test_dispatch_source_passes_frozen_shape():
    assert (
        comparator.check_outbound_va_message(_via_dispatch(), conversation_id=CONV_ID)
        == []
    )


def test_channel_source_passes_frozen_shape():
    assert (
        comparator.check_outbound_va_message(_via_channel(), conversation_id=CONV_ID)
        == []
    )


def test_headers_content_preserved_in_data_part():
    """headers 内容逐键保真——透传是行为承诺，不止键存在。"""
    for request in (_via_dispatch(), _via_channel()):
        payload = MessageToDict(request.message.parts[1].data)
        assert payload["headers"] == _HEADERS
        assert payload["body"] == _BODY
        assert payload["params"] == _PARAMS


def test_m0_12_dual_sources_produce_equivalent_shape():
    """M0.12 双源同形态：除随机 message_id 外结构等价。"""
    a, b = _via_dispatch(), _via_channel()
    for request in (a, b):
        assert (
            comparator.check_outbound_va_message(request, conversation_id=CONV_ID)
            == []
        )
    assert a.message.parts[0].text == b.message.parts[0].text
    assert MessageToDict(a.message.parts[1].data) == MessageToDict(
        b.message.parts[1].data
    )
    assert (a.message.context_id, a.message.task_id, a.message.role) == (
        b.message.context_id,
        b.message.task_id,
        b.message.role,
    )
