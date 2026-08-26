"""Unit tests for VersatileInterruptRail."""
from __future__ import annotations

import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from EDPAgent.rail.versatile_interrupt_rail import VersatileInterruptRail


class MockContext:
    """Mock context for testing."""
    
    def __init__(self):
        self.session = MockSession()
        self.inputs = MockInputs()


class MockSession:
    """Mock session for testing."""
    
    def __init__(self):
        self._state = {}
    
    def get_state(self, key):
        return self._state.get(key)
    
    def update_state(self, state_dict):
        self._state.update(state_dict)
    
    async def write_stream(self, data):
        pass


class MockInputs:
    """Mock inputs for testing."""
    
    def __init__(self):
        self.tool_args = {}


class MockToolCall:
    """Mock tool call for testing."""
    
    def __init__(self, name="call_versatile"):
        self.name = name


class MockScriptsConfig:
    """Mock scripts config for testing."""
    
    @staticmethod
    def get_response_template(key):
        templates = {
            "success_key": "操作成功",
            "fail_key": "操作失败"
        }
        return templates.get(key, "")


class TestVersatileInterruptRail:
    """Test VersatileInterruptRail functionality."""
    
    @pytest.fixture
    def rail(self):
        return VersatileInterruptRail(
            sys_operation_id="test_op_id",
            scripts_config=MockScriptsConfig()
        )
    
    @pytest.fixture
    def ctx(self):
        ctx = MockContext()
        # Pre-set lite_todolist so the rail's precondition (_has_lite_todolist)
        # passes and the first-intercept path writes pending_delegate as expected.
        ctx.session._state["lite_todolist"] = [{"step_id": 1, "content": "test"}]
        return ctx
    
    @pytest.fixture
    def tool_call(self):
        return MockToolCall()
    
    @pytest.mark.asyncio
    async def test_first_intercept(self, rail, ctx, tool_call):
        """Test first intercept path - writes pending_delegate and interrupts."""
        ctx.inputs.tool_args = {
            "query_intent": "理财推荐",
            "query_description": "推荐理财产品"
        }
        
        result = await rail.resolve_interrupt(ctx, tool_call, "")
        
        assert result is not None
        
        # Check interrupt attribute (using hasattr or check type)
        assert hasattr(result, '__class__')
        
        pending_delegate = ctx.session.get_state("pending_delegate")
        assert pending_delegate == {
            "intent": "理财推荐",
            "task_description": "推荐理财产品"
        }
        
        pending_tool_context = ctx.session.get_state("pending_tool_context")
        assert pending_tool_context["tool_name"] == "call_versatile"
    
    @pytest.mark.asyncio
    async def test_cascade_resume_scenario_a(self, rail, ctx, tool_call):
        """Test cascade resume with scenario A - background + model aware."""
        ctx.session.update_state({
            "cascade_result": {"workflow_result": {"products": ["WM001"]}},
            "pending_tool_context": {"tool_args": {
                "query_intent": "理财推荐",
                "query_description": "推荐理财产品",
                "query_response_analysis_scripts": ""
            }}
        })
        
        # Mock sandbox_normalize to return normalized data with result_key and result_message
        with patch.object(rail, '_sandbox_normalize', return_value=("success", {
            "result_key": "product_recommend_result",
            "result_message": "已为您推荐3款理财产品",
            "products": ["WM001", "WM002"],
            "bankCardNumber": "6605"
        })):
            result = await rail.resolve_interrupt(ctx, tool_call, "")
        
        assert result is not None
        
        # Verify data was stored to channel
        channel_data = ctx.session.get_state("tool_data_channel")
        assert "product_recommend_result" in channel_data
        assert channel_data["product_recommend_result"]["products"] == ["WM001", "WM002"]
    
    @pytest.mark.asyncio
    async def test_cascade_resume_scenario_b(self, rail, ctx, tool_call):
        """Test cascade resume with scenario B - model aware only."""
        ctx.session.update_state({
            "cascade_result": {"workflow_result": {"balance": 1000.0}},
            "pending_tool_context": {"tool_args": {
                "query_intent": "查询账户余额",
                "query_description": "查询余额",
                "query_response_analysis_scripts": ""
            }}
        })
        
        with patch.object(rail, '_sandbox_normalize', return_value=("success", {
            "result_message": "尾号6605余额1000.00元",
            "balance": 1000.0
        })):
            result = await rail.resolve_interrupt(ctx, tool_call, "")
        
        assert result is not None
        
        # Verify data was NOT stored to channel
        channel_data = ctx.session.get_state("tool_data_channel")
        assert channel_data is None or "product_recommend_result" not in channel_data
    
    @pytest.mark.asyncio
    async def test_cascade_resume_default_scenario(self, rail, ctx, tool_call):
        """Test cascade resume with default scenario - full data return."""
        ctx.session.update_state({
            "cascade_result": {"workflow_result": {"some_data": "value"}},
            "pending_tool_context": {"tool_args": {
                "query_intent": "some_intent",
                "query_description": "some desc",
                "query_response_analysis_scripts": ""
            }}
        })
        
        with patch.object(rail, '_sandbox_normalize', return_value=("success", {
            "data": "full_data",
            "message": "complete message"
        })):
            result = await rail.resolve_interrupt(ctx, tool_call, "")
        
        assert result is not None
    
    @pytest.mark.asyncio
    async def test_input_key_injection_success(self, rail, ctx, tool_call):
        """Test input_key data injection when key exists."""
        # Pre-populate channel with data
        ctx.session.update_state({
            "tool_data_channel": {
                "product_recommend_result": {
                    "products": ["WM001", "WM002"],
                    "bankCardNumber": "6605"
                }
            },
            "cascade_result": {"workflow_result": {}},
            "pending_tool_context": {"tool_args": {
                "query_intent": "理财选品购买",
                "query_description": "购买第2款",
                "query_response_analysis_scripts": "",
                "input_key": "product_recommend_result"
            }}
        })
        
        with patch.object(rail, '_sandbox_normalize', return_value=("success", {})) as mock_sandbox:
            result = await rail.resolve_interrupt(ctx, tool_call, "")
            # Verify skill_input contained input_data by checking call_args
            assert mock_sandbox.called, "_sandbox_normalize was not called"
            # Get the first positional argument (skill_input is passed as first arg)
            call_args = mock_sandbox.call_args
            skill_input = call_args[0][1]  # First positional arg is the second parameter
            assert "input_data" in skill_input
            assert skill_input["input_data"]["products"] == ["WM001", "WM002"]
    
    @pytest.mark.asyncio
    async def test_input_key_injection_failure(self, rail, ctx, tool_call):
        """Test input_key data injection when key does NOT exist."""
        ctx.session.update_state({
            "tool_data_channel": {
                "other_key": {"data": "value"}
            },
            "cascade_result": {"workflow_result": {}},
            "pending_tool_context": {"tool_args": {
                "query_intent": "理财选品购买",
                "query_description": "购买第2款",
                "query_response_analysis_scripts": "",
                "input_key": "non_existent_key"
            }}
        })
        
        with patch.object(rail, '_sandbox_normalize', return_value=("success", {})) as mock_sandbox:
            result = await rail.resolve_interrupt(ctx, tool_call, "")
            # Should not raise, just pass empty input_data
            assert mock_sandbox.called, "_sandbox_normalize was not called"
            call_args = mock_sandbox.call_args
            skill_input = call_args[0][1]  # First positional arg is the second parameter
            # input_data should not be set when key not found
            assert "input_data" not in skill_input or skill_input.get("input_data") is None
    
    @pytest.mark.asyncio
    async def test_query_description_injection(self, rail, ctx, tool_call):
        """Test query_description injection from session cache."""
        ctx.session.update_state({
            "mcp_to_versatile_information": "cached query description"
        })
        ctx.inputs.tool_args = {
            "query_intent": "test",
            "query_description": ""  # Empty, should be injected
        }
        
        result = await rail.resolve_interrupt(ctx, tool_call, "")
        
        # Verify cache was consumed
        assert ctx.session.get_state("mcp_to_versatile_information") is None
        
        # Verify pending_delegate has the injected description
        pending_delegate = ctx.session.get_state("pending_delegate")
        assert pending_delegate["task_description"] == "cached query description"
    
    @staticmethod
    def test_normalize_tool_args(rail):
        """Test tool args normalization."""
        # Test dict input
        args = rail._normalize_tool_args({"key": "value"}, "test_tool")
        assert args == {"key": "value"}
        
        # Test JSON string input
        args = rail._normalize_tool_args('{"key": "value"}', "test_tool")
        assert args == {"key": "value"}
        
        # Test invalid JSON string
        args = rail._normalize_tool_args('invalid json', "test_tool")
        assert args == {}
        
        # Test None input
        args = rail._normalize_tool_args(None, "test_tool")
        assert args == {}
    
    @staticmethod
    def test_extract_business_data():
        """Test business data extraction."""
        # Test workflow_result as dict
        cascade_result = {"workflow_result": {"products": ["WM001"]}}
        data = VersatileInterruptRail._extract_business_data(cascade_result)
        assert data == {"products": ["WM001"]}
        
        # Test workflow_result as JSON string
        cascade_result = {"workflow_result": '{"products": ["WM001"]}'}
        data = VersatileInterruptRail._extract_business_data(cascade_result)
        assert data == {"products": ["WM001"]}
        
        # Test no workflow_result
        cascade_result = {"node_type": "End", "custom_data": "value"}
        data = VersatileInterruptRail._extract_business_data(cascade_result)
        assert data == {"custom_data": "value"}
        
        # Test non-dict cascade_result
        data = VersatileInterruptRail._extract_business_data("not a dict")
        assert data == {}
    
    @staticmethod
    def test_build_delegate():
        """Test delegate building."""
        tool_args = {
            "query_intent": "test_intent",
            "query_description": "test description"
        }
        delegate = VersatileInterruptRail._build_delegate("call_versatile", tool_args)
        
        assert delegate == {
            "intent": "test_intent",
            "task_description": "test description"
        }
    
    @staticmethod
    def test_build_skill_input():
        """Test skill input building."""
        tool_args = {
            "query_intent": "test_intent",
            "query_description": "test desc",
            "notice_context": '{"phase": "test"}'
        }
        business_data = {"products": ["WM001"]}
        
        skill_input = VersatileInterruptRail._build_skill_input(tool_args, business_data)
        
        assert skill_input["query_intent"] == "test_intent"
        assert skill_input["query_description"] == "test desc"
        assert skill_input["business_data"] == {"products": ["WM001"]}
        assert skill_input["notice_context"] == '{"phase": "test"}'