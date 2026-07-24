"""Unit tests for build_rails() (TC-23~TC-25)."""
from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from EDPAgent.rail import build_rails
from EDPAgent.rail.multiagent_interrupt_rail import MultiagentInterruptRail
from EDPAgent.rail.multiversatile_interrupt_rail import MultiversatileInterruptRail
from EDPAgent.rail.versatile_interrupt_rail import VersatileInterruptRail
from EDPAgent.rail.iteration_limit_rail import IterationLimitRail
from EDPAgent.rail.execution_limit_rail import ExecutionLimitRail
from EDPAgent.rail.cancel_rail import CancelRail
from EDPAgent.rail.mcp_interrupt_rail import MCPInterruptRail
from EDPAgent.rail.ask_user_rail import AskUserRail
from EDPAgent.rail.log_rail import LogRail


def _mock_agent_rule():
    """创建 mock AgentRule"""
    rule = MagicMock()
    rule.max_iterations = 100
    rule.task_limits = {}
    return rule


def _mock_scripts_config():
    """创建 mock ScriptsConfig"""
    return MagicMock()


class TestBuildRails:
    """Test build_rails() with scenario_tools parameter."""

    def test_main_agent_scenario(self):
        """TC-23: 主 Agent 场景配套 MultiagentInterruptRail"""
        rails = build_rails(
            scenario_tools=["call_multiagent"],
            agent_rule=_mock_agent_rule(),
            scripts_config=_mock_scripts_config(),
            sys_operation_id="test_op",
            model_name="test-model",
            tools=[],
        )
        rail_types = [type(r) for r in rails]

        # 专属 Rail
        assert MultiagentInterruptRail in rail_types
        # 不应包含子 Agent 专属 Rail
        assert MultiversatileInterruptRail not in rail_types
        # 通用 Rail
        assert VersatileInterruptRail in rail_types
        assert IterationLimitRail in rail_types
        assert ExecutionLimitRail in rail_types
        assert CancelRail in rail_types
        assert MCPInterruptRail in rail_types
        assert AskUserRail in rail_types
        assert LogRail in rail_types

    def test_sub_agent_scenario(self):
        """TC-24: 子 Agent 场景配套 MultiversatileInterruptRail"""
        rails = build_rails(
            scenario_tools=["call_multiversatile"],
            agent_rule=_mock_agent_rule(),
            scripts_config=_mock_scripts_config(),
            sys_operation_id="test_op",
            model_name="test-model",
            tools=[],
        )
        rail_types = [type(r) for r in rails]

        # 专属 Rail
        assert MultiversatileInterruptRail in rail_types
        # 不应包含主 Agent 专属 Rail
        assert MultiagentInterruptRail not in rail_types
        # 通用 Rail
        assert VersatileInterruptRail in rail_types
        assert IterationLimitRail in rail_types
        assert ExecutionLimitRail in rail_types
        assert CancelRail in rail_types
        assert MCPInterruptRail in rail_types
        assert AskUserRail in rail_types
        assert LogRail in rail_types

    def test_common_rails_always_registered(self):
        """TC-25: 通用 Rail 在所有场景下始终注册"""
        common_rail_types = {
            IterationLimitRail,
            ExecutionLimitRail,
            CancelRail,
            MCPInterruptRail,
            VersatileInterruptRail,
            AskUserRail,
            LogRail,
        }

        # 主 Agent 场景
        rails_main = build_rails(
            scenario_tools=["call_multiagent"],
            agent_rule=_mock_agent_rule(),
            scripts_config=_mock_scripts_config(),
            sys_operation_id="test_op",
            model_name="test-model",
            tools=[],
        )
        types_main = {type(r) for r in rails_main}
        assert common_rail_types.issubset(types_main)

        # 子 Agent 场景
        rails_sub = build_rails(
            scenario_tools=["call_multiversatile"],
            agent_rule=_mock_agent_rule(),
            scripts_config=_mock_scripts_config(),
            sys_operation_id="test_op",
            model_name="test-model",
            tools=[],
        )
        types_sub = {type(r) for r in rails_sub}
        assert common_rail_types.issubset(types_sub)

        # 无场景声明
        rails_none = build_rails(
            scenario_tools=None,
            agent_rule=_mock_agent_rule(),
            scripts_config=_mock_scripts_config(),
            sys_operation_id="test_op",
            model_name="test-model",
            tools=[],
        )
        types_none = {type(r) for r in rails_none}
        assert common_rail_types.issubset(types_none)
