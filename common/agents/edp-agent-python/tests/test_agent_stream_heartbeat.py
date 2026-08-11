"""agent_stream 心跳发送点单元测试。

被测对象：EDPAgent.agent._agent_event_stream
对齐技术方案：§4.3 修改点 2-6
  - 修改点 2: cascade 续轮入口发 end(completed)
  - 修改点 3: 单工作流委托前发 initial(processing)
  - 修改点 4: 并行子 Agent 派发前发 initial(processing)
  - 修改点 5: 并行多工作流委托前发 initial(processing)
  - 修改点 6: is_sub_agent 从 context 提取并透传给 _emit_heartbeat

测试策略：mock _emit_heartbeat 为 spy，mock 外部依赖（session/agent/checkpointer），
驱动 _agent_event_stream 到心跳发送点，验证 spy 的调用参数。
"""
# pylint: disable=protected-access

from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest

from EDPAgent import agent as agent_mod
from EDPAgent.events import HeartbeatEvent


# ── fixtures ─────────────────────────────────────────────────────────────────


@pytest.fixture
def mock_dependencies(monkeypatch, fake_session, fake_agent):
    """mock _agent_event_stream 的外部依赖，返回 fake_session 供测试设置 state。

    create_agent_session / CheckpointerFactory 在 _agent_event_stream 函数内部
    通过 from ... import 导入，必须 mock 源模块而非 agent_mod。
    """
    # spy _emit_heartbeat（wraps 真实函数，记录调用且仍返回真实 HeartbeatEvent）
    spy = MagicMock(wraps=agent_mod._emit_heartbeat)
    monkeypatch.setattr(agent_mod, "_emit_heartbeat", spy)

    # mock create_agent_session（函数内部 from openjiuwen.core.session.agent import）
    import openjiuwen.core.session.agent as session_mod
    monkeypatch.setattr(session_mod, "create_agent_session", lambda **kw: fake_session)

    # mock _get_agent 返回 fake_agent
    monkeypatch.setattr(agent_mod, "_get_agent", lambda: fake_agent)

    # mock CheckpointerFactory（函数内部 from ...checkpointer import）
    import openjiuwen.core.session.checkpointer.checkpointer as checkpointer_mod
    fake_checkpointer = MagicMock()
    fake_checkpointer.session_exists = AsyncMock(return_value=True)
    fake_factory = MagicMock()
    fake_factory.get_checkpointer.return_value = fake_checkpointer
    monkeypatch.setattr(checkpointer_mod, "CheckpointerFactory", fake_factory)

    # mock get_settings（memory_enabled=False 简化 user_id 提取）
    fake_settings = SimpleNamespace(memory_enabled=False, dpa_agent_id="test-agent")
    monkeypatch.setattr(agent_mod, "get_settings", lambda: fake_settings)

    return SimpleNamespace(spy=spy, session=fake_session)


async def _collect_events(coroutine):
    """安全收集 _agent_event_stream 的事件，忽略后续异常。

    心跳发送点在 _agent_event_stream 前段（cascade 入口 / 委托判断点），
    后段（agent.stream 循环、processor.finalize 等）可能因 mock 不完整抛异常，
    但只要心跳已发送，spy 就能记录到调用。
    """
    events = []
    try:
        async for evt in coroutine:
            events.append(evt)
    except Exception:
        # expected: post-heartbeat stages may raise with incomplete mocks
        pass
    return events


# ── 测试用例 ─────────────────────────────────────────────────────────────────


class TestCascadeEntryEndHeartbeat:
    """修改点 2: cascade 续轮入口发 end(completed) 心跳。"""

    @pytest.mark.asyncio
    async def test_cascade_entry_calls_emit_end_heartbeat(self, mock_dependencies):
        """TC-AS-001: cascade_result is not None 时调用 _emit_heartbeat("end", "completed")。"""
        await _collect_events(agent_mod._agent_event_stream(
            query="continue",
            conv_id="conv-1",
            cascade_result={"result": "ok"},
            context={"is_sub_agent": False},
        ))

        mock_dependencies.spy.assert_any_call(
            "conv-1", "end", "completed", is_sub_agent=False
        )

    @pytest.mark.asyncio
    async def test_cascade_entry_yields_end_heartbeat_event(self, mock_dependencies):
        """TC-AS-001b: cascade 续轮入口 yield HeartbeatEvent(type=end) 事件。"""
        events = await _collect_events(agent_mod._agent_event_stream(
            query="continue",
            conv_id="conv-1",
            cascade_result={"result": "ok"},
            context={"is_sub_agent": False},
        ))

        hb_events = [e for e in events if getattr(e, "type", None) == "heartbeat"]
        assert len(hb_events) >= 1
        assert hb_events[0].data["heartbeat_type"] == "end"
        assert hb_events[0].data["status"] == "completed"

    @pytest.mark.asyncio
    async def test_cascade_entry_sub_agent_no_heartbeat(self, mock_dependencies):
        """TC-AS-005: 子 Agent cascade 续轮入口不 yield 心跳（is_sub_agent=True）。"""
        events = await _collect_events(agent_mod._agent_event_stream(
            query="continue",
            conv_id="conv-1",
            cascade_result={"result": "ok"},
            context={"is_sub_agent": True},
        ))

        # _emit_heartbeat 仍被调用（is_sub_agent=True），但返回 None
        mock_dependencies.spy.assert_any_call(
            "conv-1", "end", "completed", is_sub_agent=True
        )
        # 事件流中不应有心跳事件
        hb_events = [e for e in events if getattr(e, "type", None) == "heartbeat"]
        assert len(hb_events) == 0


class TestDelegateInitialHeartbeat:
    """修改点 3/4/5: 三处委托前发 initial(processing) 心跳。"""

    @pytest.mark.asyncio
    async def test_delegate_calls_emit_initial_heartbeat(self, mock_dependencies):
        """TC-AS-002: 单工作流委托前调用 _emit_heartbeat("initial", "processing")。"""
        # 设置 session state 触发 pending_delegate 分支
        mock_dependencies.session._state["pending_delegate"] = {
            "intent": "buy",
            "task_description": "test delegate",
        }

        await _collect_events(agent_mod._agent_event_stream(
            query="q",
            conv_id="conv-1",
            cascade_result=None,
            context={"is_sub_agent": False},
        ))

        mock_dependencies.spy.assert_any_call(
            "conv-1", "initial", "processing", is_sub_agent=False
        )

    @pytest.mark.asyncio
    async def test_sub_agent_dispatch_calls_emit_initial_heartbeat(self, mock_dependencies):
        """TC-AS-003: 并行子 Agent 派发前调用 _emit_heartbeat("initial", "processing")。"""
        mock_dependencies.session._state["pending_dispatch"] = [
            {
                "entity_id": "e1",
                "entity_name": "Entity1",
                "query": "inspect",
                "url": "http://agent.example/a2a/",
            }
        ]

        await _collect_events(agent_mod._agent_event_stream(
            query="q",
            conv_id="conv-1",
            cascade_result=None,
            context={"is_sub_agent": False},
        ))

        mock_dependencies.spy.assert_any_call(
            "conv-1", "initial", "processing", is_sub_agent=False
        )

    @pytest.mark.asyncio
    async def test_multi_delegate_calls_emit_initial_heartbeat(self, mock_dependencies):
        """TC-AS-004: 并行多工作流委托前调用 _emit_heartbeat("initial", "processing")。"""
        mock_dependencies.session._state["pending_multi_delegate"] = [
            {
                "workflow_id": "wf-1",
                "intent": "query_credit",
                "task_description": "multi delegate",
            }
        ]

        await _collect_events(agent_mod._agent_event_stream(
            query="q",
            conv_id="conv-1",
            cascade_result=None,
            context={"is_sub_agent": False},
        ))

        mock_dependencies.spy.assert_any_call(
            "conv-1", "initial", "processing", is_sub_agent=False
        )

    @pytest.mark.asyncio
    async def test_delegate_sub_agent_no_heartbeat_event(self, mock_dependencies):
        """TC-AS-006: 子 Agent 委托点不 yield 心跳事件（is_sub_agent=True）。"""
        mock_dependencies.session._state["pending_delegate"] = {
            "intent": "buy",
            "task_description": "test delegate",
        }

        events = await _collect_events(agent_mod._agent_event_stream(
            query="q",
            conv_id="conv-1",
            cascade_result=None,
            context={"is_sub_agent": True},
        ))

        # _emit_heartbeat 被调用时 is_sub_agent=True
        mock_dependencies.spy.assert_any_call(
            "conv-1", "initial", "processing", is_sub_agent=True
        )
        # 事件流中不应有心跳事件
        hb_events = [e for e in events if getattr(e, "type", None) == "heartbeat"]
        assert len(hb_events) == 0


class TestIsSubAgentExtraction:
    """修改点 6: is_sub_agent 标记从 context 提取。"""

    @pytest.mark.asyncio
    async def test_is_sub_agent_defaults_false(self, mock_dependencies):
        """TC-AS-007a: context 为 None 时 is_sub_agent 默认为 False。"""
        await _collect_events(agent_mod._agent_event_stream(
            query="continue",
            conv_id="conv-1",
            cascade_result={"result": "ok"},
            context=None,
        ))

        # _emit_heartbeat 被调用时 is_sub_agent=False
        mock_dependencies.spy.assert_any_call(
            "conv-1", "end", "completed", is_sub_agent=False
        )

    @pytest.mark.asyncio
    async def test_is_sub_agent_false_when_not_set(self, mock_dependencies):
        """TC-AS-007b: context 不含 is_sub_agent 时默认为 False。"""
        await _collect_events(agent_mod._agent_event_stream(
            query="continue",
            conv_id="conv-1",
            cascade_result={"result": "ok"},
            context={"other_key": "value"},
        ))

        mock_dependencies.spy.assert_any_call(
            "conv-1", "end", "completed", is_sub_agent=False
        )

    @pytest.mark.asyncio
    async def test_is_sub_agent_true_propagated(self, mock_dependencies):
        """TC-AS-007c: context={"is_sub_agent": True} 时透传给 _emit_heartbeat。"""
        await _collect_events(agent_mod._agent_event_stream(
            query="continue",
            conv_id="conv-1",
            cascade_result={"result": "ok"},
            context={"is_sub_agent": True},
        ))

        mock_dependencies.spy.assert_any_call(
            "conv-1", "end", "completed", is_sub_agent=True
        )
