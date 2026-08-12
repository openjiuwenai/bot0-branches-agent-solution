"""Unit tests for call_versatile tool."""
from __future__ import annotations

import pytest

from EDPAgent.tool.call_versatile import call_versatile, call_versatile_tool


class TestCallVersatile:
    """Test call_versatile function."""

    @pytest.mark.asyncio
    async def test_call_versatile_basic(self):
        """Test basic call_versatile invocation."""
        result = await call_versatile(
            query_description="查询余额",
            query_intent="查询账户余额"
        )
        assert result == {}  # Function returns empty dict (Rail intercepts)

    @pytest.mark.asyncio
    async def test_call_versatile_with_all_params(self):
        """Test call_versatile with all parameters."""
        result = await call_versatile(
            query_description="购买理财产品",
            query_intent="理财选品购买",
            query_response_analysis_scripts="python scripts/run_fund_planning.py",
            response_template_keys='["success_key", "fail_key"]',
            notice_context='{"phase": "wealth", "buy_amount": 50000}',
            input_key="product_recommend_result"
        )
        assert result == {}

    @pytest.mark.asyncio
    async def test_call_versatile_empty_params(self):
        """Test call_versatile with empty parameters."""
        result = await call_versatile()
        assert result == {}

    @pytest.mark.asyncio
    async def test_call_versatile_with_session(self):
        """Test call_versatile with session parameter."""
        mock_session = {"session_id": "test-conv"}
        result = await call_versatile(
            query_description="test",
            query_intent="test",
            session=mock_session
        )
        assert result == {}


class TestCallVersatileTool:
    """Test call_versatile_tool definition."""

    @staticmethod
    def test_tool_card_properties():
        """Test tool card properties."""
        tool = call_versatile_tool

        assert tool.card.id == "call_versatile"
        assert tool.card.name == "call_versatile"
        assert "通用业务工作流调用工具" in tool.card.description

    @staticmethod
    def test_input_params_structure():
        """Test input parameters structure."""
        tool = call_versatile_tool
        params = tool.card.input_params

        assert "query_description" in params["properties"]
        assert "query_intent" in params["properties"]
        assert "query_response_analysis_scripts" in params["properties"]
        assert "response_template_keys" in params["properties"]
        assert "notice_context" in params["properties"]
        assert "input_key" in params["properties"]

        # Required fields
        assert params["required"] == ["query_description", "query_intent"]

    @staticmethod
    def test_input_key_description():
        """Test input_key parameter description."""
        tool = call_versatile_tool
        input_key_prop = tool.card.input_params["properties"]["input_key"]

        assert "跨工作流数据引用" in input_key_prop["description"]
        assert "input_data" in input_key_prop["description"]

    @staticmethod
    def test_query_intent_description():
        """Test query_intent parameter description."""
        tool = call_versatile_tool
        intent_prop = tool.card.input_params["properties"]["query_intent"]

        assert "业务意图分类" in intent_prop["description"]
        assert "理财推荐" in intent_prop["description"]
        assert "理财选品购买" in intent_prop["description"]
