# coding: utf-8

"""CB-T0-0 比较器自证（反例基线，判据先于实现）。

双向自检：
- 假阴性面（Red）：五类被禁形态桩必须被比较器判不通过——若某桩通过，
  说明判据无证伪能力，比较器必须收紧（修判据而非改桩）；
- 假阳性面：现役实现的合规产出必须零违规通过——若被误伤，说明判据
  过严偏离事实。

被禁形态的选取对应 T0-3 迁移期最可能发生的破坏：顺序漂移、错误面
形态污染、条件字段规则破坏、三形态边界破坏、历史预留字段被"清理"。
"""
from __future__ import annotations

from oracle_support.regression_baseline import comparator

from common.response_wrapper import wrap_agent_event, wrap_error, wrap_workflow_event

AGENT_ID = "agent-a"
CONV_ID = "conv-1"


# ════════════════════════════════════════════════════════════════════
# 假阴性面：被禁形态桩必须被判不通过（Red 记录）
# ════════════════════════════════════════════════════════════════════


def test_cb_a_field_order_drift_must_fail():
    """CB-a 外层字段顺序漂移（custom_rsp_data 前移）→ 必须不通过。"""
    stub = {
        "success": True,
        "agent_id": AGENT_ID,
        "conversation_id": CONV_ID,
        "custom_rsp_data": {
            "data": {},
            "event": "thought",
            "content": "x",
            "createdTime": 1,
            "latency": "",
            "plugin": "",
        },
        "output": "",
        "error": "",
        "execution_time": 0.1,
    }
    violations = comparator.check_agent_event(stub, event_type="thought")
    assert violations, "CB-a 未被判不通过：字段顺序漂移未被比较器捕捉"


def test_cb_b_error_envelope_with_custom_rsp_data_must_fail():
    """CB-b 错误信封混入 custom_rsp_data → 必须不通过。"""
    stub = {
        "success": False,
        "agent_id": AGENT_ID,
        "conversation_id": CONV_ID,
        "execution_time": 0.1,
        "error_code": "100001",
        "error_msg": "系统超负载，请在稍后重试",
        "custom_rsp_data": {},
    }
    violations = comparator.check_error_envelope(stub)
    assert violations, "CB-b 未被判不通过：错误信封混入 custom_rsp_data 未被捕捉"


def test_cb_c_unconditional_error_code_must_fail():
    """CB-c 非 planning_execution_process 事件携带 error_code → 必须不通过。"""
    stub = wrap_agent_event(
        event_type="thought",
        content="x",
        data={},
        agent_id=AGENT_ID,
        conversation_id=CONV_ID,
        elapsed=0.1,
    )
    stub["error_code"] = ""
    violations = comparator.check_agent_event(stub, event_type="thought")
    assert violations, "CB-c 未被判不通过：条件字段规则破坏未被捕捉"


def test_cb_d_workflow_with_output_error_must_fail():
    """CB-d workflow 信封携带 output / error → 必须不通过。"""
    stub = {
        "success": True,
        "agent_id": AGENT_ID,
        "conversation_id": CONV_ID,
        "output": "",
        "error": "",
        "execution_time": 0.1,
        "custom_rsp_data": {"event": "message", "data": {}},
    }
    violations = comparator.check_workflow_event(stub)
    assert violations, "CB-d 未被判不通过：workflow 信封三形态边界破坏未被捕捉"


def test_cb_e_dropped_latency_field_must_fail():
    """CB-e custom_rsp_data 缺 latency（历史预留字段被"清理"）→ 必须不通过。"""
    stub = wrap_agent_event(
        event_type="thought",
        content="x",
        data={},
        agent_id=AGENT_ID,
        conversation_id=CONV_ID,
        elapsed=0.1,
    )
    del stub["custom_rsp_data"]["latency"]
    violations = comparator.check_agent_event(stub, event_type="thought")
    assert violations, "CB-e 未被判不通过：latency 字段被清理未被捕捉"


def test_cb_frame_without_terminator_must_fail():
    """SSE 帧无换行终止（对应 429 字面 \\n\\n 怪异形态）→ 必须不通过。"""
    frame = 'data: {"success": false}\\n\\n'
    violations = comparator.check_sse_frame(frame)
    assert violations, "SSE 帧终止符缺失未被捕捉"


# ════════════════════════════════════════════════════════════════════
# 假阳性面：现役实现合规产出必须零违规通过
# ════════════════════════════════════════════════════════════════════


def test_compliant_agent_event_passes():
    envelope = wrap_agent_event(
        event_type="thought",
        content="思考中",
        data={"step": 1},
        agent_id=AGENT_ID,
        conversation_id=CONV_ID,
        elapsed=0.25,
    )
    assert comparator.check_agent_event(envelope, event_type="thought") == []


def test_compliant_agent_event_with_display_passes():
    envelope = wrap_agent_event(
        event_type="tool_start",
        content="",
        data={},
        agent_id=AGENT_ID,
        conversation_id=CONV_ID,
        elapsed=0.25,
        plugin="search",
        display=False,
    )
    assert (
        comparator.check_agent_event(
            envelope, event_type="tool_start", display_expected=True
        )
        == []
    )


def test_compliant_planning_execution_process_passes():
    envelope = wrap_agent_event(
        event_type="planning_execution_process",
        content="",
        data={},
        agent_id=AGENT_ID,
        conversation_id=CONV_ID,
        elapsed=0.25,
    )
    assert (
        comparator.check_agent_event(
            envelope, event_type="planning_execution_process"
        )
        == []
    )


def test_compliant_workflow_event_passes():
    envelope = wrap_workflow_event(
        event_kind="message",
        data={"text": "节点输出", "node_id": "n1"},
        agent_id=AGENT_ID,
        conversation_id=CONV_ID,
        elapsed=0.25,
    )
    assert comparator.check_workflow_event(envelope) == []


def test_compliant_error_envelope_passes():
    envelope = wrap_error(
        agent_id=AGENT_ID,
        conversation_id=CONV_ID,
        elapsed=0.25,
        error_code="100001",
        error_msg="系统超负载，请在稍后重试",
    )
    assert comparator.check_error_envelope(envelope) == []


def test_compliant_sse_frame_passes():
    frame = 'data: {"success": true}\n\n'
    assert comparator.check_sse_frame(frame) == []
