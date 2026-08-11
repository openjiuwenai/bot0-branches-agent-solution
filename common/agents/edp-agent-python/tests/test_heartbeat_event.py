"""HeartbeatEvent 事件模型单元测试。

被测对象：EDPAgent.events.HeartbeatEvent、AgentEvent Union、EVENT_TYPE_MAP
对齐技术方案：§4.2 EDPA 与 Runtime 共有修改（事件定义模块）
契约版本：HB-CONTRACT-1.0
"""
from __future__ import annotations

from typing import get_args, get_origin, Union

import pytest

from EDPAgent.events import (
    AgentEvent,
    EVENT_TYPE_MAP,
    HeartbeatEvent,
)


class TestHeartbeatEventModel:
    """HeartbeatEvent 类定义测试（对齐 §4.2 修改点1）。"""

    @staticmethod
    def test_heartbeat_type_default():
        """TC-EV-001: HeartbeatEvent.type 默认为 "heartbeat"。"""
        ev = HeartbeatEvent(data={})
        assert ev.type == "heartbeat"

    @staticmethod
    def test_heartbeat_content_default_empty():
        """TC-EV-002: HeartbeatEvent.content 默认为空字符串（心跳无文本内容）。

        依据：技术方案 §3.4 —— "content 放文本内容（心跳无文本内容，留空）"。
        """
        ev = HeartbeatEvent(data={})
        assert ev.content == ""

    @staticmethod
    def test_heartbeat_data_accepts_contract_fields():
        """TC-EV-003: HeartbeatEvent.data 可接收契约要求的所有字段。"""
        payload = {
            "contract_version": "HB-CONTRACT-1.0",
            "request_id": "conv-001",
            "heartbeat_type": "initial",
            "status": "processing",
            "timestamp": "2026-07-12T10:00:00Z",
            "source": "edp_agent",
        }
        ev = HeartbeatEvent(data=payload)
        assert ev.data == payload
        assert ev.data["contract_version"] == "HB-CONTRACT-1.0"
        assert ev.data["source"] == "edp_agent"


class TestHeartbeatEventRegistration:
    """HeartbeatEvent 在 Union 与 EVENT_TYPE_MAP 中的注册（对齐 §4.2 修改点2/3）。"""

    @staticmethod
    def test_heartbeat_in_agent_event_union():
        """TC-EV-004: HeartbeatEvent 纳入 AgentEvent Union。

        依据：技术方案 §4.2 修改点2 —— "将 HeartbeatEvent 纳入 AgentEvent Union"。
        """
        union_args = get_args(AgentEvent)
        # AgentEvent 可能是 Union[...] 或 X | Y 形式
        if get_origin(AgentEvent) is Union or get_origin(AgentEvent) is not None:
            members = set(union_args)
        else:
            members = {union_args}
        assert HeartbeatEvent in members, "HeartbeatEvent 必须在 AgentEvent Union 中"

    @staticmethod
    def test_heartbeat_in_event_type_map():
        """TC-EV-005: EVENT_TYPE_MAP["heartbeat"] = HeartbeatEvent。

        依据：技术方案 §4.2 修改点3 —— "将 HeartbeatEvent 纳入 EVENT_TYPE_MAP"。
        """
        assert "heartbeat" in EVENT_TYPE_MAP
        assert EVENT_TYPE_MAP["heartbeat"] is HeartbeatEvent

    @staticmethod
    def test_event_type_map_roundtrip():
        """TC-EV-006: HeartbeatEvent.type 与 EVENT_TYPE_MAP 互为反查。"""
        ev = HeartbeatEvent(data={})
        cls = EVENT_TYPE_MAP.get(ev.type)
        assert cls is HeartbeatEvent
