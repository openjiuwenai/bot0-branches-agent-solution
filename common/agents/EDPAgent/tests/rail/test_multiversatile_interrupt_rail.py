"""Unit tests for MultiversatileInterruptRail (TC-14~TC-17, TC-32~TC-39)."""
from __future__ import annotations

from unittest.mock import MagicMock, AsyncMock, patch

import pytest

from EDPAgent.rail.multiversatile_interrupt_rail import MultiversatileInterruptRail


# ── Mock helpers ────────────────────────────────────────────────────────────

class MockSession:
    def __init__(self):
        self._state = {}

    def get_state(self, key=None):
        if key is None:
            return self._state
        return self._state.get(key)

    def update_state(self, state_dict):
        self._state.update(state_dict)


class MockInputs:
    def __init__(self, tool_args=None):
        self.tool_args = tool_args or {}


class MockContext:
    def __init__(self, tool_args=None):
        self.session = MockSession()
        self.inputs = MockInputs(tool_args=tool_args)


class MockToolCall:
    def __init__(self, name="call_multiversatile"):
        self.name = name


# ── Fixtures ────────────────────────────────────────────────────────────────

@pytest.fixture
def rail():
    return MultiversatileInterruptRail()


@pytest.fixture
def ctx():
    return MockContext()


@pytest.fixture
def tool_call():
    return MockToolCall()


# ── Tests ───────────────────────────────────────────────────────────────────

class TestMultiversatileInterruptRail:
    """Test MultiversatileInterruptRail (TC-14~TC-17)."""

    @pytest.mark.asyncio
    async def test_first_intercept(self, rail, ctx, tool_call):
        """TC-14: 首次拦截 - 写 pending_multi_delegate、字段映射、返回 interrupt"""
        ctx.inputs.tool_args = {
            "workflows": [
                {
                    "query": "查询企业A信息",
                    "query_intent": "企业信息查询",
                }
            ]
        }

        result = await rail.resolve_interrupt(ctx, tool_call, "")

        # 验证返回 InterruptResult（Rail 拦截返回类型）
        assert result is not None
        assert result.__class__.__name__ == "InterruptResult"

        # 验证 pending_multi_delegate 写入成功
        pending = ctx.session.get_state("pending_multi_delegate")
        assert pending is not None
        assert len(pending) == 1

        # 验证字段映射：query_intent → intent, query → task_description
        assert pending[0]["intent"] == "企业信息查询"
        assert pending[0]["task_description"] == "查询企业A信息"

        # 验证自动生成 workflow_id
        assert pending[0]["workflow_id"] == "wf_001"

    @pytest.mark.asyncio
    async def test_cascade_resume(self, rail, ctx, tool_call):
        """TC-15: Cascade 续轮 - 提取 workflows 结果、返回 reject"""
        ctx.session.update_state({
            "cascade_result": {
                "workflows": [
                    {"workflow_id": "wf_001", "status": "done", "result": {"data": "value1"}},
                    {"workflow_id": "wf_002", "status": "done", "result": {"data": "value2"}},
                ]
            },
            "pending_tool_context": {"tool_name": "call_multiversatile", "tool_args": {}},
        })

        result = await rail.resolve_interrupt(ctx, tool_call, "")

        # 验证返回 reject
        assert result is not None
        tool_result = result.tool_result
        assert tool_result["status"] == "success"
        assert "workflows" in tool_result["data"]
        assert len(tool_result["data"]["workflows"]) == 2

        # 验证 cascade_result 被清空
        assert ctx.session.get_state("cascade_result") is None

        # 验证 pending_multi_delegate 被清空
        assert ctx.session.get_state("pending_multi_delegate") is None

    @pytest.mark.asyncio
    async def test_field_mapping(self, rail, ctx, tool_call):
        """TC-16: 字段映射 - query_intent→intent, query→task_description, 自动生成 workflow_id"""
        ctx.inputs.tool_args = {
            "workflows": [
                {"query": "查询企业A信息", "query_intent": "企业信息查询"},
                {"query": "生成尽调报告", "query_intent": "尽调报告生成"},
            ]
        }

        await rail.resolve_interrupt(ctx, tool_call, "")
        pending = ctx.session.get_state("pending_multi_delegate")

        # 第一个 workflow
        assert pending[0]["intent"] == "企业信息查询"
        assert pending[0]["task_description"] == "查询企业A信息"
        assert pending[0]["workflow_id"] == "wf_001"

        # 第二个 workflow
        assert pending[1]["intent"] == "尽调报告生成"
        assert pending[1]["task_description"] == "生成尽调报告"
        assert pending[1]["workflow_id"] == "wf_002"

    @pytest.mark.asyncio
    async def test_reject_empty_workflows(self, rail, ctx, tool_call):
        """TC-17: 空 workflows 拒绝"""
        ctx.inputs.tool_args = {"workflows": []}

        result = await rail.resolve_interrupt(ctx, tool_call, "")

        assert result is not None
        tool_result = result.tool_result
        assert tool_result["status"] == "failed"
        assert "workflows 不能为空" in tool_result["message"]

        # 验证 pending_multi_delegate 未写入
        assert ctx.session.get_state("pending_multi_delegate") is None


class TestMapWorkflowFields:
    """Test _map_workflow_fields method."""

    def test_basic_mapping(self):
        rail = MultiversatileInterruptRail()
        workflows = [
            {"query": "查询信息", "query_intent": "信息查询"},
            {"query": "生成报告", "query_intent": "报告生成"},
        ]
        result = rail._map_workflow_fields(workflows)

        assert len(result) == 2
        assert result[0]["intent"] == "信息查询"
        assert result[0]["task_description"] == "查询信息"
        assert result[0]["workflow_id"] == "wf_001"
        assert result[1]["workflow_id"] == "wf_002"

    def test_custom_workflow_id(self):
        """如果 workflow 已提供 workflow_id，则保留"""
        rail = MultiversatileInterruptRail()
        workflows = [
            {"query": "test", "query_intent": "test", "workflow_id": "custom_001"},
        ]
        result = rail._map_workflow_fields(workflows)
        assert result[0]["workflow_id"] == "custom_001"

    def test_fallback_to_intent_key(self):
        """如果无 query_intent 但有 intent，使用 intent"""
        rail = MultiversatileInterruptRail()
        workflows = [
            {"task_description": "test desc", "intent": "test_intent"},
        ]
        result = rail._map_workflow_fields(workflows)
        assert result[0]["intent"] == "test_intent"
        assert result[0]["task_description"] == "test desc"

    def test_non_dict_items_skipped(self):
        """非 dict 元素被跳过"""
        rail = MultiversatileInterruptRail()
        workflows = [
            {"query": "valid", "query_intent": "valid_intent"},
            "not a dict",
            42,
        ]
        result = rail._map_workflow_fields(workflows)
        assert len(result) == 1


class TestExtractBusinessData:
    """Test _extract_business_data static method."""

    def test_workflows_field(self):
        cascade = {"workflows": [{"workflow_id": "wf_001", "status": "done"}]}
        data = MultiversatileInterruptRail._extract_business_data(cascade)
        assert "workflows" in data
        assert len(data["workflows"]) == 1

    def test_workflow_result_dict(self):
        cascade = {"workflow_result": {"workflow_id": "wf_001", "status": "done"}}
        data = MultiversatileInterruptRail._extract_business_data(cascade)
        assert "workflows" in data
        assert len(data["workflows"]) == 1

    def test_workflow_result_list(self):
        cascade = {"workflow_result": [{"a": 1}, {"b": 2}]}
        data = MultiversatileInterruptRail._extract_business_data(cascade)
        assert "workflows" in data
        assert len(data["workflows"]) == 2

    def test_non_dict_input(self):
        data = MultiversatileInterruptRail._extract_business_data("not a dict")
        assert data == {}

    def test_no_known_fields(self):
        cascade = {"custom_data": "value", "node_type": "End"}
        data = MultiversatileInterruptRail._extract_business_data(cascade)
        assert "custom_data" in data
        assert "node_type" not in data


class TestCascadeResumeWithDataChannel:
    """Test cascade resume with input_key/result_key/result_message (TC-32~TC-39)."""

    def _setup_cascade_resume(self, ctx, workflows_args, cascade_workflows):
        """构造 cascade 续轮的 session state"""
        ctx.session.update_state({
            "cascade_result": {
                "workflows": cascade_workflows,
            },
            "pending_tool_context": {
                "tool_name": "call_multiversatile",
                "tool_args": {
                    "workflows": workflows_args,
                },
            },
        })

    @pytest.mark.asyncio
    async def test_input_key_data_injection(self, ctx, tool_call):
        """TC-32: input_key 数据注入 - ToolDataChannel 读取并注入 skill_input"""
        rail = MultiversatileInterruptRail()
        workflows_args = [
            {"query_intent": "信贷综合金融", "query": "test", "input_key": "baseInfo"},
        ]
        cascade_workflows = [{"status": "done", "result": {"data": "result1"}}]

        self._setup_cascade_resume(ctx, workflows_args, cascade_workflows)

        # 在 ToolDataChannel 中存储 baseInfo
        ctx.session.update_state({"tool_data_channel": {"baseInfo": {"company": "小米"}}})

        # Mock _sandbox_normalize 返回原始数据
        with patch.object(rail, '_sandbox_normalize', new_callable=AsyncMock) as mock_normalize:
            mock_normalize.return_value = (None, {"data": "result1"})
            result = await rail.resolve_interrupt(ctx, tool_call, "")

        # 验证 _sandbox_normalize 被调用，且 skill_input 包含 input_data
        call_args = mock_normalize.call_args
        skill_input = call_args[0][1]  # 第二个位置参数
        assert "input_data" in skill_input
        assert skill_input["input_data"]["company"] == "小米"

    @pytest.mark.asyncio
    async def test_input_key_miss(self, ctx, tool_call):
        """TC-33: input_key 未命中 - 不中断流程，仅 warning"""
        rail = MultiversatileInterruptRail()
        workflows_args = [
            {"query_intent": "信贷综合金融", "query": "test", "input_key": "nonexistent_key"},
        ]
        cascade_workflows = [{"status": "done", "result": {"data": "result1"}}]

        self._setup_cascade_resume(ctx, workflows_args, cascade_workflows)

        with patch.object(rail, '_sandbox_normalize', new_callable=AsyncMock) as mock_normalize:
            mock_normalize.return_value = (None, {"data": "result1"})
            result = await rail.resolve_interrupt(ctx, tool_call, "")

        # 验证流程不中断
        assert result is not None
        tool_result = result.tool_result
        assert tool_result["status"] == "success"

        # 验证 skill_input 不含 input_data
        call_args = mock_normalize.call_args
        skill_input = call_args[0][1]
        assert "input_data" not in skill_input

    @pytest.mark.asyncio
    async def test_sandbox_normalize_called(self, ctx, tool_call):
        """TC-34: 归一化脚本执行 - _sandbox_normalize 被调用"""
        rail = MultiversatileInterruptRail(sys_operation_id="test-op-id")
        workflows_args = [
            {"query_intent": "信贷综合金融", "query": "test", "query_response_analysis_scripts": "python run.py"},
        ]
        cascade_workflows = [{"status": "done", "result": {"raw": "data"}}]

        self._setup_cascade_resume(ctx, workflows_args, cascade_workflows)

        with patch.object(rail, '_sandbox_normalize', new_callable=AsyncMock) as mock_normalize:
            mock_normalize.return_value = ("success", {"normalized": True})
            result = await rail.resolve_interrupt(ctx, tool_call, "")

        # 验证 _sandbox_normalize 被调用
        mock_normalize.assert_called_once()
        # 验证 command 参数
        call_args = mock_normalize.call_args
        assert call_args[0][0] == "python run.py"

    @pytest.mark.asyncio
    async def test_result_key_cached(self, ctx, tool_call):
        """TC-35: result_key 缓存 - ToolDataChannel.store 被调用"""
        rail = MultiversatileInterruptRail()
        workflows_args = [
            {"query_intent": "信贷综合金融", "query": "test"},
        ]
        cascade_workflows = [{"status": "done", "result": {"raw": "data"}}]

        self._setup_cascade_resume(ctx, workflows_args, cascade_workflows)

        with patch.object(rail, '_sandbox_normalize', new_callable=AsyncMock) as mock_normalize:
            mock_normalize.return_value = ("success", {"result_key": "credit_result", "data": {"score": 750}})
            with patch.object(rail, '_route_to_channel') as mock_route:
                result = await rail.resolve_interrupt(ctx, tool_call, "")

        # 验证 _route_to_channel 被调用
        mock_route.assert_called_once()
        call_args = mock_route.call_args
        assert call_args[0][1] == "credit_result"  # result_key

    @pytest.mark.asyncio
    async def test_result_message_routing(self, ctx, tool_call):
        """TC-36: result_message 路由 - combined_message 包含各 workflow 的 result_message"""
        rail = MultiversatileInterruptRail()
        workflows_args = [
            {"query_intent": "信贷综合金融", "query": "test"},
            {"query_intent": "信贷综合分析", "query": "test"},
        ]
        cascade_workflows = [
            {"status": "done", "result": {"raw": "data1"}},
            {"status": "done", "result": {"raw": "data2"}},
        ]

        self._setup_cascade_resume(ctx, workflows_args, cascade_workflows)

        with patch.object(rail, '_sandbox_normalize', new_callable=AsyncMock) as mock_normalize:
            mock_normalize.side_effect = [
                ("success", {"result_key": "r1", "result_message": "金融分析完成", "data": {}}),
                ("success", {"result_key": "r2", "result_message": "综合分析完成", "data": {}}),
            ]
            with patch.object(rail, '_route_to_channel'):
                result = await rail.resolve_interrupt(ctx, tool_call, "")

        tool_result = result.tool_result
        # 验证每个 workflow 的 data 包含 result_message
        wf_results = tool_result["data"]["workflows"]
        assert "金融分析完成" in wf_results[0]["data"]
        assert "综合分析完成" in wf_results[1]["data"]

    @pytest.mark.asyncio
    async def test_response_template_keys(self, ctx, tool_call):
        """TC-37: response_template_keys 话术 - session 写入 response_template"""
        mock_scripts_config = MagicMock()
        mock_scripts_config.get_response_template.return_value = "操作成功话术"

        rail = MultiversatileInterruptRail(scripts_config=mock_scripts_config)
        workflows_args = [
            {"query_intent": "信贷综合金融", "query": "test", "response_template_keys": '["success_key", "fail_key"]'},
        ]
        cascade_workflows = [{"status": "done", "result": {"raw": "data"}}]

        self._setup_cascade_resume(ctx, workflows_args, cascade_workflows)

        with patch.object(rail, '_sandbox_normalize', new_callable=AsyncMock) as mock_normalize:
            mock_normalize.return_value = ("success", {"data": {}})
            result = await rail.resolve_interrupt(ctx, tool_call, "")

        # 验证 scripts_config.get_response_template 被调用
        mock_scripts_config.get_response_template.assert_called_with("success_key")

        # 验证 session 写入 response_template
        assert ctx.session.get_state("response_template") == "操作成功话术"

    @pytest.mark.asyncio
    async def test_notice_context_passthrough(self, ctx, tool_call):
        """TC-38: notice_context 透传 - skill_input 包含 notice_context"""
        rail = MultiversatileInterruptRail()
        workflows_args = [
            {"query_intent": "信贷综合金融", "query": "test", "notice_context": '{"phase":"credit"}'},
        ]
        cascade_workflows = [{"status": "done", "result": {"raw": "data"}}]

        self._setup_cascade_resume(ctx, workflows_args, cascade_workflows)

        with patch.object(rail, '_sandbox_normalize', new_callable=AsyncMock) as mock_normalize:
            mock_normalize.return_value = (None, {"data": {}})
            result = await rail.resolve_interrupt(ctx, tool_call, "")

        # 验证 _build_skill_input 包含 notice_context
        call_args = mock_normalize.call_args
        skill_input = call_args[0][1]
        assert "notice_context" in skill_input
        assert skill_input["notice_context"] == '{"phase":"credit"}'

    @pytest.mark.asyncio
    async def test_ui_notice_handling(self, ctx, tool_call):
        """TC-39: ui_notice 处理 - write_stream 被调用，ui_notice 从 normalized 中 pop"""
        mock_scripts_config = MagicMock()
        mock_scripts_config.get_response_template.return_value = "提示话术"

        rail = MultiversatileInterruptRail(scripts_config=mock_scripts_config)
        workflows_args = [
            {"query_intent": "信贷综合金融", "query": "test"},
        ]
        cascade_workflows = [{"status": "done", "result": {"raw": "data"}}]

        self._setup_cascade_resume(ctx, workflows_args, cascade_workflows)

        with patch.object(rail, '_sandbox_normalize', new_callable=AsyncMock) as mock_normalize:
            mock_normalize.return_value = ("success", {"ui_notice": {"event": "tool_end", "key": "notice_key"}, "data": {}})
            # Mock write_stream
            ctx.session.write_stream = AsyncMock()
            result = await rail.resolve_interrupt(ctx, tool_call, "")

        # 验证 write_stream 被调用
        ctx.session.write_stream.assert_called_once()

        # 验证 ui_notice 已从结果中 pop（不传给 LLM）
        call_args = mock_normalize.call_args
        normalized = mock_normalize.return_value[1]
        # ui_notice 已被 pop，normalized 中不应再有
        assert "ui_notice" not in normalized or normalized.get("ui_notice") is None
