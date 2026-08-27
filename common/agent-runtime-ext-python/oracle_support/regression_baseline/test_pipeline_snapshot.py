# coding: utf-8

"""事件管线快照（M0.6）：A2A protobuf 事件 → 归一化 → 通道格式化 → 信封。

覆盖 GAP §5.3 T0-1 回归门声明的全事件族：think(thought) / tool_* /
final_answer_chunk / interrupt_start（input_required 与 failed 两来源）/
heartbeat（seq 兜底递增）/ versatile workflow（message、end）/ sub_task，
以及"completed 状态事件不产帧"的既有事实。

管线入口取 api.dispatch._serialize_event——与生产 SSE 出流同一函数，
产出即 SSE 帧内的 JSON 载荷。
"""
from __future__ import annotations

import json
import time

from a2a.types.a2a_pb2 import (
    TASK_STATE_COMPLETED,
    TASK_STATE_FAILED,
    TASK_STATE_INPUT_REQUIRED,
    Artifact,
    Message,
    Part,
    TaskArtifactUpdateEvent,
    TaskStatus,
    TaskStatusUpdateEvent,
)
from api.dispatch import _serialize_event
from google.protobuf.struct_pb2 import Struct, Value
from oracle_support.regression_baseline import comparator
from oracle_support.regression_baseline import frozen_facts as ff

AGENT_ID = "agent-a"


def _data_part(data: dict) -> Part:
    struct = Struct()
    struct.update(data)
    value = Value()
    value.struct_value.CopyFrom(struct)
    part = Part()
    part.data.CopyFrom(value)
    return part


def _artifact_event(data: dict, text: str = "") -> TaskArtifactUpdateEvent:
    parts = []
    if text:
        parts.append(Part(text=text))
    parts.append(_data_part(data))
    return TaskArtifactUpdateEvent(
        task_id="t-1", artifact=Artifact(artifact_id="art-1", parts=parts)
    )


def _status_event(state, text: str = "") -> TaskStatusUpdateEvent:
    message = Message(parts=[Part(text=text)]) if text else None
    status = TaskStatus(state=state, message=message) if message else TaskStatus(state=state)
    return TaskStatusUpdateEvent(task_id="t-1", status=status)


def _serialize(event, conv_id: str) -> dict | None:
    payload = _serialize_event(
        event,
        agent_id=AGENT_ID,
        conversation_id=conv_id,
        start_time=time.monotonic(),
    )
    return None if payload is None else json.loads(payload)


def test_thought_event_projects_to_agent_envelope():
    envelope = _serialize(
        _artifact_event({"type": "thought", "content": "思考中"}), "conv-th"
    )
    assert envelope is not None
    assert comparator.check_agent_event(envelope, event_type="thought") == []
    assert envelope["custom_rsp_data"]["content"] == "思考中"


def test_tool_event_carries_plugin():
    envelope = _serialize(
        _artifact_event(
            {"type": "tool_start", "content": "调用工具", "plugin": "search"}
        ),
        "conv-tool",
    )
    assert envelope is not None
    assert comparator.check_agent_event(envelope, event_type="tool_start") == []
    assert envelope["custom_rsp_data"]["plugin"] == "search"


def test_final_answer_chunk_projects():
    envelope = _serialize(
        _artifact_event({"type": "final_answer_chunk", "content": "答案片段"}),
        "conv-fa",
    )
    assert envelope is not None
    assert comparator.check_agent_event(envelope, event_type="final_answer_chunk") == []


def test_input_required_projects_to_interrupt_start_success_true():
    envelope = _serialize(
        _status_event(TASK_STATE_INPUT_REQUIRED, "请补充信息"), "conv-ir"
    )
    assert envelope is not None
    assert (
        comparator.check_agent_event(envelope, event_type=ff.INTERRUPT_EVENT_TYPE) == []
    )
    assert envelope["success"] is True
    assert envelope["error"] == ""
    assert envelope["custom_rsp_data"]["content"] == "请补充信息"


def test_failed_projects_to_interrupt_start_success_false():
    envelope = _serialize(_status_event(TASK_STATE_FAILED, "执行失败"), "conv-fl")
    assert envelope is not None
    assert (
        comparator.check_agent_event(envelope, event_type=ff.INTERRUPT_EVENT_TYPE) == []
    )
    assert envelope["success"] is False
    assert envelope["error"] == "执行失败"


def test_completed_status_event_yields_no_frame():
    """completed 状态事件不产出 SSE 帧——既有事实，迁移后不得新增帧。"""
    assert _serialize(_status_event(TASK_STATE_COMPLETED), "conv-cp") is None


def test_heartbeat_seq_fallback_increments_per_conversation():
    conv = "conv-hb-uniq"
    first = _serialize(_artifact_event({"type": "heartbeat"}), conv)
    second = _serialize(_artifact_event({"type": "heartbeat"}), conv)
    assert first is not None and second is not None
    assert comparator.check_agent_event(first, event_type="heartbeat") == []
    assert first["custom_rsp_data"]["data"]["seq"] == 1
    assert second["custom_rsp_data"]["data"]["seq"] == 2


def test_versatile_workflow_message_and_end_project_to_workflow_envelope():
    for kind, data in (("message", {"text": "节点输出", "node_id": "n1"}), ("end", {})):
        envelope = _serialize(
            _artifact_event({"event": kind, "data": data}), f"conv-wf-{kind}"
        )
        assert envelope is not None, f"workflow {kind} 未产帧"
        assert comparator.check_workflow_event(envelope) == [], f"workflow {kind}"
        assert envelope["custom_rsp_data"]["event"] == kind


def test_sub_task_event_projects_with_nested_agent_payload():
    envelope = _serialize(
        _artifact_event(
            {
                "type": "sub_task",
                "sub_task_path": ["t-1"],
                "node_kind": "agent",
                "data": {"type": "thought", "content": "子任务思考"},
            }
        ),
        "conv-st",
    )
    assert envelope is not None
    assert comparator.check_sub_task_event(envelope) == []
    inner = envelope["custom_rsp_data"]["data"]
    assert tuple(inner.keys()) == ff.AGENT_CUSTOM_RSP_DATA_FIELDS
    assert inner["content"] == "子任务思考"


def test_untyped_artifact_falls_back_to_thought():
    """无 type 的 artifact 兜底为 thought——既有事实（normalizer.py 的 _normalize_artifact）。"""
    envelope = _serialize(_artifact_event({}, text="裸文本"), "conv-fb")
    assert envelope is not None
    assert envelope["custom_rsp_data"]["event"] == ff.FALLBACK_EVENT_TYPE
    assert envelope["custom_rsp_data"]["content"] == "裸文本"
