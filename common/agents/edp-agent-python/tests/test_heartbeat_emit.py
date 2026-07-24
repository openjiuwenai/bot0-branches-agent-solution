"""_emit_heartbeat 函数单元测试。

被测对象：EDPAgent.agent._emit_heartbeat
心跳协议契约：HB-CONTRACT-1.0
"""
from __future__ import annotations

from datetime import datetime, timezone

import pytest

from EDPAgent import agent as agent_mod
from EDPAgent.events import HeartbeatEvent


class TestEmitHeartbeat:
    """_emit_heartbeat 函数单元测试。"""

    def test_emit_initial_heartbeat(self):
        """TC-HB-001: 主 Agent initial 心跳——返回 HeartbeatEvent，字段正确。"""
        hb = agent_mod._emit_heartbeat(
            "conv-001", "initial", "processing", is_sub_agent=False
        )
        assert hb is not None
        assert isinstance(hb, HeartbeatEvent)
        assert hb.type == "heartbeat"
        assert hb.data["heartbeat_type"] == "initial"
        assert hb.data["status"] == "processing"
        assert hb.data["request_id"] == "conv-001"

    def test_emit_end_heartbeat(self):
        """TC-HB-002: 主 Agent end 心跳——heartbeat_type=end, status=completed。"""
        hb = agent_mod._emit_heartbeat(
            "conv-001", "end", "completed", is_sub_agent=False
        )
        assert hb is not None
        assert isinstance(hb, HeartbeatEvent)
        assert hb.data["heartbeat_type"] == "end"
        assert hb.data["status"] == "completed"

    def test_emit_heartbeat_sub_agent_returns_none(self):
        """TC-HB-003: 子 Agent is_sub_agent=True——返回 None，不发送心跳。"""
        for hb_type in ("initial", "end", "normal"):
            hb = agent_mod._emit_heartbeat(
                "conv-001", hb_type, "processing", is_sub_agent=True
            )
            assert hb is None, f"is_sub_agent=True 时 heartbeat_type={hb_type} 应返回 None"

    def test_heartbeat_contract_version(self):
        """TC-HB-004: contract_version 字段固定为 HB-CONTRACT-1.0。"""
        hb = agent_mod._emit_heartbeat(
            "conv-001", "initial", "processing", is_sub_agent=False
        )
        assert hb is not None
        assert hb.data["contract_version"] == "HB-CONTRACT-1.0"

    def test_heartbeat_source_field(self):
        """TC-HB-005: source 字段固定为 edp_agent。"""
        hb = agent_mod._emit_heartbeat(
            "conv-001", "initial", "processing", is_sub_agent=False
        )
        assert hb is not None
        assert hb.data["source"] == "edp_agent"

    def test_heartbeat_timestamp_format(self):
        """TC-HB-006: timestamp 为 UTC ISO8601 格式（YYYY-MM-DDTHH:MM:SSZ）。"""
        hb = agent_mod._emit_heartbeat(
            "conv-001", "initial", "processing", is_sub_agent=False
        )
        assert hb is not None
        ts = hb.data["timestamp"]
        # 格式校验：以 Z 结尾
        assert ts.endswith("Z")
        # 可被 strptime 解析
        parsed = datetime.strptime(ts, "%Y-%m-%dT%H:%M:%SZ")
        # UTC 时间与当前时间差距不超过 5 秒
        now_utc = datetime.now(timezone.utc).replace(tzinfo=None)
        assert abs((now_utc - parsed).total_seconds()) < 5

    def test_heartbeat_data_excludes_seq(self):
        """TC-HB-007: data 中不包含 seq 字段（seq 由 Runtime 出流层统一注入）。

        依据：技术方案 §3.3 —— "EDPAgent 侧构造 HeartbeatEvent 时不设 seq
        （data 中无 seq 字段）；Runtime 侧优先在心跳运行时注入 seq"。
        """
        hb = agent_mod._emit_heartbeat(
            "conv-001", "initial", "processing", is_sub_agent=False
        )
        assert hb is not None
        assert "seq" not in hb.data

    def test_heartbeat_content_is_empty(self):
        """TC-HB-008: content 固定为空字符串（心跳无文本内容）。"""
        hb = agent_mod._emit_heartbeat(
            "conv-001", "initial", "processing", is_sub_agent=False
        )
        assert hb is not None
        assert hb.content == ""

    def test_heartbeat_data_field_set(self):
        """TC-HB-009: data 字段恰好包含契约要求的 6 个字段（无 seq）。"""
        hb = agent_mod._emit_heartbeat(
            "conv-001", "initial", "processing", is_sub_agent=False
        )
        assert hb is not None
        expected_keys = {
            "contract_version", "request_id", "heartbeat_type",
            "status", "timestamp", "source",
        }
        assert set(hb.data.keys()) == expected_keys
