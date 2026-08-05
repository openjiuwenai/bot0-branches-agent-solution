"""Unit tests for call_multiversatile tool (TC-05~TC-08, TC-40~TC-43)."""
from __future__ import annotations

import pytest

from EDPAgent.tool.call_multiversatile import call_multiversatile, call_multiversatile_tool


class TestCallMultiversatile:
    """Test call_multiversatile function."""

    @pytest.mark.asyncio
    async def test_call_multiversatile_basic(self):
        """TC-05: 基本调用返回空 dict（Rail 拦截，函数本身返回空 dict）"""
        result = await call_multiversatile(
            workflows=[
                {
                    "query": "查询企业A信息",
                    "query_intent": "企业信息查询",
                }
            ]
        )
        assert result == {}

    @pytest.mark.asyncio
    async def test_call_multiversatile_empty_workflows(self):
        """TC-07: 空 workflows 数组调用（函数本身不校验，由 Rail 拦截时拒绝）"""
        result = await call_multiversatile(workflows=[])
        assert result == {}

    @pytest.mark.asyncio
    async def test_call_multiversatile_single_workflow(self):
        """TC-08: 单元素 workflows 数组，退化为单工作流调度"""
        result = await call_multiversatile(
            workflows=[
                {
                    "query": "查询企业A信息",
                    "query_intent": "企业信息查询",
                }
            ]
        )
        assert result == {}


class TestCallMultiversatileToolCard:
    """Test call_multiversatile_tool definition (TC-06)."""

    def test_tool_card_id(self):
        """TC-06: ToolCard.id == 'call_multiversatile'"""
        assert call_multiversatile_tool.card.id == "call_multiversatile"

    def test_tool_card_name(self):
        assert call_multiversatile_tool.card.name == "call_multiversatile"

    def test_workflows_is_array(self):
        """TC-06: workflows 参数类型为 array"""
        params = call_multiversatile_tool.card.input_params
        assert params["properties"]["workflows"]["type"] == "array"

    def test_workflows_items_required_fields(self):
        """TC-06: workflows.items.required 包含 query, query_intent"""
        items = call_multiversatile_tool.card.input_params["properties"]["workflows"]["items"]
        required = items["required"]
        assert "query" in required
        assert "query_intent" in required

    def test_top_level_required(self):
        """TC-06: 顶层 required == ['workflows']"""
        params = call_multiversatile_tool.card.input_params
        assert params["required"] == ["workflows"]

    def test_description_contains_parallel_keyword(self):
        """TC-06: description 包含'并行调用多个 VersatileAdapter 工作流'"""
        desc = call_multiversatile_tool.card.description
        assert "并行调用多个 VersatileAdapter 工作流" in desc


class TestCallMultiversatilePerWorkflowFields:
    """Test per-workflow item fields (TC-40~TC-43)."""

    def _get_items_properties(self):
        """获取 workflows.items.properties"""
        return call_multiversatile_tool.card.input_params["properties"]["workflows"]["items"]["properties"]

    def test_input_key_in_workflow_items(self):
        """TC-40: input_key 在 per-workflow items 中"""
        props = self._get_items_properties()
        assert "input_key" in props
        assert props["input_key"]["type"] == "string"
        assert "跨工作流数据引用" in props["input_key"].get("description", "")

    def test_scripts_in_workflow_items(self):
        """TC-41: query_response_analysis_scripts 在 per-workflow items 中"""
        props = self._get_items_properties()
        assert "query_response_analysis_scripts" in props
        assert props["query_response_analysis_scripts"]["type"] == "string"

    def test_response_template_keys_in_workflow_items(self):
        """TC-42: response_template_keys 在 per-workflow items 中"""
        props = self._get_items_properties()
        assert "response_template_keys" in props
        assert props["response_template_keys"]["type"] == "string"

    def test_notice_context_in_workflow_items(self):
        """TC-43: notice_context 在 per-workflow items 中"""
        props = self._get_items_properties()
        assert "notice_context" in props
        assert props["notice_context"]["type"] == "string"
