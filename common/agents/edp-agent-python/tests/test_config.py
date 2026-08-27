"""Unit tests for sub_agents.yaml config loading (TC-26~TC-27)."""
from __future__ import annotations

from pathlib import Path

import pytest

from EDPAgent.config import SubAgentEntry, SubAgentsConfig, load_sub_agents_config


class TestSubAgentsConfig:
    """Test SubAgentsConfig model."""

    @staticmethod
    def test_default_values():
        config = SubAgentsConfig()
        assert config.sub_agents == []

    @staticmethod
    def test_with_entries():
        config = SubAgentsConfig(
            sub_agents=[
                SubAgentEntry(entity_type="ABC", url="https://abc-agent:8080", name="SubEDPAgent"),
            ]
        )
        assert len(config.sub_agents) == 1
        assert config.sub_agents[0].entity_type == "ABC"
        assert config.sub_agents[0].url == "https://abc-agent:8080"
        assert config.sub_agents[0].name == "SubEDPAgent"


class TestSubAgentEntry:
    """Test SubAgentEntry model."""

    @staticmethod
    def test_default_values():
        entry = SubAgentEntry()
        assert entry.entity_type == "default"
        assert entry.url == ""
        assert entry.name == "SubEDPAgent"


class TestLoadSubAgentsConfig:
    """Test load_sub_agents_config function."""

    @staticmethod
    def test_load_valid_yaml(tmp_path):
        """TC-26: sub_agents.yaml 加载与校验"""
        yaml_content = """
sub_agents:
  - entity_type: ABC
    url: https://abc-agent:8080
    name: SubEDPAgent
  - entity_type: FUND
    url: https://fund-agent:8080
    name: FundAgent
"""
        yaml_file = tmp_path / "sub_agents.yaml"
        yaml_file.write_text(yaml_content, encoding="utf-8")

        config = load_sub_agents_config(config_path=str(yaml_file))

        assert len(config.sub_agents) == 2
        assert config.sub_agents[0].entity_type == "ABC"
        assert config.sub_agents[0].url == "https://abc-agent:8080"
        assert config.sub_agents[0].name == "SubEDPAgent"
        assert config.sub_agents[1].entity_type == "FUND"
        assert config.sub_agents[1].url == "https://fund-agent:8080"

    @staticmethod
    def test_load_nonexistent_file(tmp_path):
        """文件不存在时返回空配置"""
        config = load_sub_agents_config(config_path=str(tmp_path / "nonexistent.yaml"))
        assert config.sub_agents == []

    @staticmethod
    def test_load_empty_yaml(tmp_path):
        """空 YAML 文件返回空配置"""
        yaml_file = tmp_path / "empty.yaml"
        yaml_file.write_text("", encoding="utf-8")

        config = load_sub_agents_config(config_path=str(yaml_file))
        assert config.sub_agents == []

    @staticmethod
    def test_load_invalid_yaml(tmp_path):
        """无效 YAML 内容返回空配置"""
        yaml_file = tmp_path / "invalid.yaml"
        yaml_file.write_text("not: valid: yaml: {{{", encoding="utf-8")

        config = load_sub_agents_config(config_path=str(yaml_file))
        # 加载失败时返回空配置
        assert isinstance(config, SubAgentsConfig)

    @staticmethod
    def test_sub_agent_not_load_sub_agents_yaml():
        """TC-27: 子 Agent 场景不加载 sub_agents.yaml

        验证逻辑：子 Agent 场景的 scenario_tools 不包含 'call_multiagent'，
        因此 MultiagentInterruptRail 不被注册，sub_agents.yaml 无需加载。
        """
        from EDPAgent.tool import build_tools
        from EDPAgent.rail import build_rails
        from EDPAgent.rail.multiagent_interrupt_rail import MultiagentInterruptRail
        from EDPAgent.rail.multiversatile_interrupt_rail import MultiversatileInterruptRail
        from unittest.mock import MagicMock

        # 子 Agent 场景
        scenario_tools = ["call_multiversatile"]

        # 验证工具注册
        tools = build_tools(scenario_tools=scenario_tools)
        tool_names = {t.card.name for t in tools}
        assert "call_multiagent" not in tool_names
        assert "call_multiversatile" in tool_names

        # 验证 Rail 注册
        rails = build_rails(
            scenario_tools=scenario_tools,
            agent_rule=MagicMock(),
            scripts_config=MagicMock(),
            sys_operation_id="test",
            model_name="test",
            tools=[],
        )
        rail_types = {type(r) for r in rails}
        assert MultiagentInterruptRail not in rail_types
        assert MultiversatileInterruptRail in rail_types
