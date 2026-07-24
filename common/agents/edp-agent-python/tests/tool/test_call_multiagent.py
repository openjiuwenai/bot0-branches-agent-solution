"""Unit tests for call_multiagent tool (TC-01~TC-04)."""
from __future__ import annotations

import pytest

from EDPAgent.tool.call_multiagent import call_multiagent, call_multiagent_tool


class TestCallMultiagent:
    """Test call_multiagent function."""

    @pytest.mark.asyncio
    async def test_call_multiagent_basic(self):
        """TC-01: 基本调用返回空 dict（Rail 拦截，函数本身返回空 dict）"""
        result = await call_multiagent(
            entities=[
                {
                    "entity_id": "entity_001",
                    "entity_name": "企业A",
                    "entity_type": "ABC",
                    "query": "分析企业A的贷款风险",
                }
            ]
        )
        assert result == {}

    @pytest.mark.asyncio
    async def test_call_multiagent_empty_entities(self):
        """TC-03: 空 entities 数组调用（函数本身不校验，由 Rail 拦截时拒绝）"""
        result = await call_multiagent(entities=[])
        assert result == {}

    @pytest.mark.asyncio
    async def test_call_multiagent_single_entity(self):
        """TC-04: 单元素 entities 数组，退化为单子 Agent 调度"""
        result = await call_multiagent(
            entities=[
                {
                    "entity_id": "entity_001",
                    "entity_name": "企业A",
                    "entity_type": "ABC",
                    "query": "分析企业A",
                }
            ]
        )
        assert result == {}


class TestCallMultiagentToolCard:
    """Test call_multiagent_tool definition (TC-02)."""

    def test_tool_card_id(self):
        """TC-02: ToolCard.id == 'call_multiagent'"""
        assert call_multiagent_tool.card.id == "call_multiagent"

    def test_tool_card_name(self):
        assert call_multiagent_tool.card.name == "call_multiagent"

    def test_entities_is_array(self):
        """TC-02: entities 参数类型为 array"""
        params = call_multiagent_tool.card.input_params
        assert params["properties"]["entities"]["type"] == "array"

    def test_entities_items_required_fields(self):
        """TC-02: entities.items.required 包含 entity_id, entity_name, entity_type, query"""
        items = call_multiagent_tool.card.input_params["properties"]["entities"]["items"]
        required = items["required"]
        assert "entity_id" in required
        assert "entity_name" in required
        assert "entity_type" in required
        assert "query" in required

    def test_top_level_required(self):
        """TC-02: 顶层 required == ['entities']"""
        params = call_multiagent_tool.card.input_params
        assert params["required"] == ["entities"]

    def test_description_contains_parallel_keyword(self):
        """TC-02: description 包含'并行调用多个子 Agent'"""
        desc = call_multiagent_tool.card.description
        assert "并行调用多个子 Agent" in desc
