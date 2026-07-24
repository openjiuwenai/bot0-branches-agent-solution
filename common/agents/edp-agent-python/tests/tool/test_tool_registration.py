"""TOOLS list registration: lite_todo replaces legacy 3-tool todolist.

Extended with parallel tool registration tests (TC-18~TC-22).
"""
from __future__ import annotations

import pytest


# ── Legacy tests ────────────────────────────────────────────────────────────

def test_lite_todo_tools_returns_single_tool():
    from EDPAgent.tool.lite_todo import lite_todo_tools

    tools = lite_todo_tools()
    assert len(tools) == 1
    assert tools[0].card.name == "lite_todo_write"


def test_TOOLS_contains_lite_todo_write():
    from EDPAgent.tool import TOOLS

    names = {t.card.name for t in TOOLS}
    assert "lite_todo_write" in names


def test_TOOLS_does_not_contain_legacy_todolist_tools():
    """Legacy 3 tools (todolist_create / todolist_modify / todolist_query)
    must be gone from registration after Phase 1.
    """
    from EDPAgent.tool import TOOLS

    names = {t.card.name for t in TOOLS}
    legacy_names = {"todolist_create", "todolist_modify", "todolist_query"}
    assert names.isdisjoint(legacy_names), (
        f"Legacy todolist tools still in TOOLS: {names & legacy_names}"
    )


def test_TOOLS_keeps_business_tools():
    """ask_user / call_mcp / call_versatile must still be registered."""
    from EDPAgent.tool import TOOLS

    names = {t.card.name for t in TOOLS}
    assert "ask_user" in names
    assert "call_mcp" in names
    assert "call_versatile" in names


# ── Parallel tool registration tests (TC-18~TC-22) ─────────────────────────

class TestBuildToolsScenarioRegistration:
    """Test build_tools() with scenario_tools parameter."""

    def test_main_agent_scenario(self):
        """TC-18: 主 Agent 场景注册 call_multiagent + 通用工具"""
        from EDPAgent.tool import build_tools

        tools = build_tools(scenario_tools=["call_multiagent"])
        names = {t.card.name for t in tools}

        # 专属工具
        assert "call_multiagent" in names
        # 通用工具
        assert "ask_user" in names
        assert "call_mcp" in names
        assert "call_versatile" in names
        assert "cancel_task" in names
        assert "lite_todo_write" in names
        # 不应包含子 Agent 专属工具
        assert "call_multiversatile" not in names

    def test_sub_agent_scenario(self):
        """TC-19: 子 Agent 场景注册 call_multiversatile + 通用工具"""
        from EDPAgent.tool import build_tools

        tools = build_tools(scenario_tools=["call_multiversatile"])
        names = {t.card.name for t in tools}

        # 专属工具
        assert "call_multiversatile" in names
        # 通用工具
        assert "ask_user" in names
        assert "call_mcp" in names
        assert "call_versatile" in names
        assert "cancel_task" in names
        assert "lite_todo_write" in names
        # 不应包含主 Agent 专属工具
        assert "call_multiagent" not in names

    def test_common_tools_always_registered(self):
        """TC-20: 通用工具在所有场景下始终注册"""
        from EDPAgent.tool import build_tools

        common_names = {"ask_user", "call_mcp", "call_versatile", "cancel_task", "lite_todo_write"}

        # 主 Agent 场景
        tools_main = build_tools(scenario_tools=["call_multiagent"])
        names_main = {t.card.name for t in tools_main}
        assert common_names.issubset(names_main)

        # 子 Agent 场景
        tools_sub = build_tools(scenario_tools=["call_multiversatile"])
        names_sub = {t.card.name for t in tools_sub}
        assert common_names.issubset(names_sub)

        # 无场景声明
        tools_none = build_tools(scenario_tools=None)
        names_none = {t.card.name for t in tools_none}
        assert common_names.issubset(names_none)

    def test_coexistence_multiagent(self):
        """TC-21: call_versatile 与 call_multiagent 共存"""
        from EDPAgent.tool import build_tools

        tools = build_tools(scenario_tools=["call_multiagent"])
        names = {t.card.name for t in tools}

        assert "call_versatile" in names
        assert "call_multiagent" in names
        # 两者 id 不同
        versatile_ids = {t.card.id for t in tools if t.card.name == "call_versatile"}
        multiagent_ids = {t.card.id for t in tools if t.card.name == "call_multiagent"}
        assert versatile_ids != multiagent_ids

    def test_coexistence_multiversatile(self):
        """TC-22: call_versatile 与 call_multiversatile 共存"""
        from EDPAgent.tool import build_tools

        tools = build_tools(scenario_tools=["call_multiversatile"])
        names = {t.card.name for t in tools}

        assert "call_versatile" in names
        assert "call_multiversatile" in names
        # 两者 id 不同
        versatile_ids = {t.card.id for t in tools if t.card.name == "call_versatile"}
        multiversatile_ids = {t.card.id for t in tools if t.card.name == "call_multiversatile"}
        assert versatile_ids != multiversatile_ids
