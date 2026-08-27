# coding: utf-8

"""四种信封形态对现役 wrap_* 的逐字段快照（M0.2–M0.5）。

与 tests/common/test_response_wrapper.py（行为单测）不同，本套件只做一件事：
把现役产出锚死在 frozen_facts 冻结面上——字段集合、顺序、条件字段出现规则。
任何使这些断言失败的改动都构成对外兼容面变更，必须走产品决策而非顺手合入。
"""
from __future__ import annotations

from oracle_support.regression_baseline import frozen_facts as ff

from common.response_wrapper import (
    wrap_agent_event,
    wrap_error,
    wrap_sub_task_event,
    wrap_workflow_event,
)

AGENT_ID = "agent-a"
CONV_ID = "conv-1"


def _agent(event_type: str = "thought", **kwargs):
    return wrap_agent_event(
        event_type=event_type,
        content=kwargs.pop("content", "x"),
        data=kwargs.pop("data", {}),
        agent_id=AGENT_ID,
        conversation_id=CONV_ID,
        elapsed=0.25,
        **kwargs,
    )


def test_agent_envelope_field_order_matches_frozen():
    envelope = _agent()
    assert tuple(envelope.keys()) == ff.AGENT_EVENT_FIELDS
    assert tuple(envelope["custom_rsp_data"].keys()) == ff.AGENT_CUSTOM_RSP_DATA_FIELDS


def test_agent_envelope_display_appended_after_plugin():
    envelope = _agent(display=True)
    assert tuple(envelope["custom_rsp_data"].keys()) == (
        ff.AGENT_CUSTOM_RSP_DATA_FIELDS + (ff.AGENT_OPTIONAL_DISPLAY_FIELD,)
    )


def test_agent_envelope_error_code_only_for_planning_execution_process():
    with_code = _agent("planning_execution_process")
    assert tuple(with_code.keys()) == ff.AGENT_EVENT_FIELDS + ("error_code",)
    assert with_code["error_code"] == ""

    for event_type in ("thought", "tool_start", "final_answer_chunk", "interrupt_start"):
        envelope = _agent(event_type)
        assert "error_code" not in envelope, f"{event_type} 不得携带 error_code"


def test_workflow_envelope_field_order_matches_frozen():
    for kind in ff.WORKFLOW_EVENT_KINDS:
        envelope = wrap_workflow_event(
            event_kind=kind,
            data={"text": "节点输出"} if kind == "message" else {},
            agent_id=AGENT_ID,
            conversation_id=CONV_ID,
            elapsed=0.25,
        )
        assert tuple(envelope.keys()) == ff.WORKFLOW_EVENT_FIELDS
        assert tuple(envelope["custom_rsp_data"].keys()) == ff.WORKFLOW_CUSTOM_RSP_DATA_FIELDS


def test_sub_task_envelope_three_inner_kinds():
    """sub_task 的 data 位按 inner kind（agent / workflow / lifecycle）分派。"""
    cases = {
        "agent": {"kind": "agent", "type": "thought", "content": "inner", "data": {}},
        "workflow": {"kind": "workflow", "type": "message", "data": {"text": "t"}},
        "lifecycle": {"kind": "lifecycle", "data": {"event": "node_start"}},
    }
    for kind, inner_meta in cases.items():
        envelope = wrap_sub_task_event(
            sub_task_path=["t-1"],
            node_kind="agent" if kind != "workflow" else "workflow",
            inner_meta=inner_meta,
            agent_id=AGENT_ID,
            conversation_id=CONV_ID,
            elapsed=0.25,
        )
        assert tuple(envelope.keys()) == ff.SUB_TASK_EVENT_FIELDS, f"inner={kind}"
        crd = envelope["custom_rsp_data"]
        assert tuple(crd.keys()) == ff.SUB_TASK_CUSTOM_RSP_DATA_FIELDS, f"inner={kind}"
        assert crd["event"] == ff.SUB_TASK_EVENT_TYPE
        assert isinstance(crd["data"], dict)

    # inner kind=agent 时嵌套 data 为 agent custom_rsp_data 六字段形态
    envelope = wrap_sub_task_event(
        sub_task_path=["t-1"],
        node_kind="agent",
        inner_meta=cases["agent"],
        agent_id=AGENT_ID,
        conversation_id=CONV_ID,
        elapsed=0.25,
    )
    inner = envelope["custom_rsp_data"]["data"]
    assert tuple(inner.keys()) == ff.AGENT_CUSTOM_RSP_DATA_FIELDS


def test_error_envelope_field_order_matches_frozen():
    envelope = wrap_error(
        agent_id=AGENT_ID,
        conversation_id=CONV_ID,
        elapsed=0.25,
        error_code=ff.ERROR_CODE_RATE_LIMIT,
        error_msg=ff.ERROR_MSG_RATE_LIMIT,
    )
    assert tuple(envelope.keys()) == ff.ERROR_ENVELOPE_FIELDS
    assert "custom_rsp_data" not in envelope
    assert envelope["success"] is False


def test_frozen_facts_decoupled_from_implementation():
    """M0.1 冻结夹具不得 import 实现模块——夹具随实现漂移即基线失效。"""
    import inspect

    from oracle_support.regression_baseline import frozen_facts

    src = inspect.getsource(frozen_facts)
    for forbidden in ("from common", "import common", "from channels",
                      "import channels", "from api", "import api",
                      "from orchestrator", "import orchestrator"):
        assert forbidden not in src, f"frozen_facts 与实现耦合：{forbidden}"
