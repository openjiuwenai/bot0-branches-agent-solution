"""Unit tests for MultiagentInterruptRail (TC-09~TC-13, TC-28~TC-31)."""
from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from EDPAgent.rail.multiagent_interrupt_rail import MultiagentInterruptRail
from EDPAgent.config import SubAgentsConfig, SubAgentEntry


# ── Mock helpers ────────────────────────────────────────────────────────────

def _make_sub_agents_config():
    """创建测试用的 sub_agents 配置"""
    return SubAgentsConfig(
        sub_agents=[
            SubAgentEntry(entity_type="ABC", url="https://abc-agent:8080", name="SubEDPAgent"),
        ]
    )


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
    def __init__(self, name="call_multiagent"):
        self.name = name


# ── Fixtures ────────────────────────────────────────────────────────────────

@pytest.fixture
def rail():
    """创建 MultiagentInterruptRail 实例（mock sub_agents.yaml 加载）"""
    with patch(
        "EDPAgent.rail.multiagent_interrupt_rail.load_sub_agents_config",
        return_value=_make_sub_agents_config(),
    ):
        return MultiagentInterruptRail()


@pytest.fixture
def ctx():
    return MockContext()


@pytest.fixture
def tool_call():
    return MockToolCall()


# ── Tests ───────────────────────────────────────────────────────────────────

class TestMultiagentInterruptRail:
    """Test MultiagentInterruptRail (TC-09~TC-13)."""

    @pytest.mark.asyncio
    async def test_first_intercept(self, rail, ctx, tool_call):
        """TC-09: 首次拦截 - 写 pending_dispatch、映射 sub_agent_url、返回 interrupt"""
        ctx.inputs.tool_args = {
            "entities": [
                {
                    "entity_id": "entity_001",
                    "entity_name": "企业A",
                    "entity_type": "ABC",
                    "query": "分析贷款风险",
                }
            ]
        }

        result = await rail.resolve_interrupt(ctx, tool_call, "")

        # 验证返回 InterruptResult（Rail 拦截返回类型）
        assert result is not None
        assert hasattr(result, "__class__")
        assert result.__class__.__name__ == "InterruptResult"

        # 验证 pending_dispatch 写入成功
        pending_dispatch = ctx.session.get_state("pending_dispatch")
        assert pending_dispatch is not None
        assert len(pending_dispatch) == 1

        # 验证 sub_agent_url 已映射
        assert pending_dispatch[0]["sub_agent_url"] == "https://abc-agent:8080"
        assert pending_dispatch[0]["entity_id"] == "entity_001"

        # 验证防重入标记
        assert ctx.session.get_state("multiagent_dispatched") is True

    @pytest.mark.asyncio
    async def test_cascade_resume(self, rail, ctx, tool_call):
        """TC-10: Cascade 续轮 - 提取 sub_agent_results、返回 reject"""
        # 模拟 cascade_result
        ctx.session.update_state({
            "cascade_result": {
                "sub_agent_results": [
                    {"entity_id": "entity_001", "status": "done", "content": "分析结果"}
                ]
            },
            "pending_tool_context": {"tool_name": "call_multiagent", "tool_args": {}},
            "multiagent_dispatched": True,
        })

        result = await rail.resolve_interrupt(ctx, tool_call, "")

        # 验证返回 reject（tool_result 包含 data）
        assert result is not None
        tool_result = result.tool_result
        assert tool_result["status"] == "success"
        assert "sub_agent_results" in tool_result["data"]
        assert len(tool_result["data"]["sub_agent_results"]) == 1

        # 验证 cascade_result 被清空
        assert ctx.session.get_state("cascade_result") is None

        # 验证 pending_dispatch 和 multiagent_dispatched 被清空
        assert ctx.session.get_state("pending_dispatch") is None
        assert ctx.session.get_state("multiagent_dispatched") is None

    @pytest.mark.asyncio
    async def test_skipped_entities_passthrough(self, rail, ctx, tool_call):
        """TC-11: skipped_entities / concurrency_limit 透传"""
        ctx.session.update_state({
            "cascade_result": {
                "sub_agent_results": [
                    {"entity_id": "entity_001", "status": "done", "content": "结果1"},
                    {"entity_id": "entity_002", "status": "done", "content": "结果2"},
                ],
                "skipped_entities": [
                    {"entity_id": "entity_003", "entity_name": "企业C", "reason": "concurrency_limit"}
                ],
                "concurrency_limit": 2,
            },
            "pending_tool_context": {"tool_name": "call_multiagent", "tool_args": {}},
        })

        result = await rail.resolve_interrupt(ctx, tool_call, "")
        data = result.tool_result["data"]

        # 验证三个字段都存在
        assert "sub_agent_results" in data
        assert "skipped_entities" in data
        assert "concurrency_limit" in data

        # 验证 skipped_entities 内容
        assert len(data["skipped_entities"]) == 1
        assert data["skipped_entities"][0]["entity_name"] == "企业C"
        assert data["skipped_entities"][0]["reason"] == "concurrency_limit"

        # 验证 concurrency_limit
        assert data["concurrency_limit"] == 2

    @pytest.mark.asyncio
    async def test_reject_empty_entities(self, rail, ctx, tool_call):
        """TC-12: 空 entities 拒绝"""
        ctx.inputs.tool_args = {"entities": []}

        result = await rail.resolve_interrupt(ctx, tool_call, "")

        assert result is not None
        tool_result = result.tool_result
        assert tool_result["status"] == "failed"
        assert "entities 不能为空" in tool_result["message"]

        # 验证 pending_dispatch 未写入
        assert ctx.session.get_state("pending_dispatch") is None

    @pytest.mark.asyncio
    async def test_reject_duplicate_dispatch(self, rail, ctx, tool_call):
        """TC-13: 防重入 - multiagent_dispatched=True 且无 cascade_result"""
        ctx.session.update_state({"multiagent_dispatched": True})
        ctx.inputs.tool_args = {
            "entities": [
                {"entity_id": "entity_001", "entity_name": "企业A", "entity_type": "ABC", "query": "test"}
            ]
        }

        result = await rail.resolve_interrupt(ctx, tool_call, "")

        assert result is not None
        tool_result = result.tool_result
        assert tool_result["status"] == "already_dispatched"

        # 验证 pending_dispatch 未被覆盖（仍为 None）
        assert ctx.session.get_state("pending_dispatch") is None


class TestExtractBusinessData:
    """Test _extract_business_data static method."""

    @staticmethod
    def test_sub_agent_results_only():
        """仅 sub_agent_results"""
        cascade = {
            "sub_agent_results": [{"entity_id": "A", "status": "done"}]
        }
        data = MultiagentInterruptRail._extract_business_data(cascade)
        assert "sub_agent_results" in data
        assert len(data["sub_agent_results"]) == 1
        assert "skipped_entities" not in data

    def test_sub_agent_results_with_skipped(self):
        """sub_agent_results + skipped_entities + concurrency_limit"""
        cascade = {
            "sub_agent_results": [{"entity_id": "A", "status": "done"}],
            "skipped_entities": [{"entity_id": "B", "reason": "concurrency_limit"}],
            "concurrency_limit": 1,
        }
        data = MultiagentInterruptRail._extract_business_data(cascade)
        assert "sub_agent_results" in data
        assert "skipped_entities" in data
        assert "concurrency_limit" in data
        assert data["concurrency_limit"] == 1

    @staticmethod
    def test_workflow_result_dict():
        """兼容 workflow_result（dict）"""
        cascade = {"workflow_result": {"products": ["WM001"]}}
        data = MultiagentInterruptRail._extract_business_data(cascade)
        assert "sub_agent_results" in data
        assert len(data["sub_agent_results"]) == 1

    @staticmethod
    def test_workflow_result_list():
        """兼容 workflow_result（list）"""
        cascade = {"workflow_result": [{"a": 1}, {"b": 2}]}
        data = MultiagentInterruptRail._extract_business_data(cascade)
        assert "sub_agent_results" in data
        assert len(data["sub_agent_results"]) == 2

    def test_non_dict_input(self):
        """非 dict 输入返回空 dict"""
        data = MultiagentInterruptRail._extract_business_data("not a dict")
        assert data == {}

    @staticmethod
    def test_no_known_fields():
        """无已知字段时返回原始数据（过滤内部字段）"""
        cascade = {"custom_data": "value", "node_type": "End", "node_name": "test"}
        data = MultiagentInterruptRail._extract_business_data(cascade)
        assert "custom_data" in data
        assert "node_type" not in data
        assert "node_name" not in data


class TestCancelledAndSkipped:
    """Test cancelled detection and skipped_entities with reason (TC-28~TC-31)."""

    @pytest.mark.asyncio
    async def test_all_cancelled(self, rail, ctx, tool_call):
        """TC-28: 全部 cancelled 检测 - status=cancelled、防重入标记不清除"""
        ctx.session.update_state({
            "cascade_result": {
                "sub_agent_results": [
                    {"entity_id": "entity_001", "status": "cancelled"},
                    {"entity_id": "entity_002", "status": "cancelled"},
                ]
            },
            "pending_tool_context": {"tool_name": "call_multiagent", "tool_args": {}},
            "multiagent_dispatched": True,
        })

        result = await rail.resolve_interrupt(ctx, tool_call, "")
        tool_result = result.tool_result

        # 验证 status=cancelled
        assert tool_result["status"] == "cancelled"

        # 验证 message 包含取消提示
        assert "用户已取消" in tool_result["message"]
        assert "禁止" in tool_result["message"]

        # 验证 multiagent_dispatched 保持为 True（未被清除）
        assert ctx.session.get_state("multiagent_dispatched") is True

        # 验证 pending_dispatch 被清除
        assert ctx.session.get_state("pending_dispatch") is None

    @pytest.mark.asyncio
    async def test_cancelled_prevents_redispatch(self, rail, ctx, tool_call):
        """TC-29: cancelled 禁止重试 - multiagent_dispatched 保持 True"""
        # 模拟 cancelled 后的状态
        ctx.session.update_state({"multiagent_dispatched": True})
        ctx.inputs.tool_args = {
            "entities": [
                {"entity_id": "entity_001", "entity_name": "企业A", "entity_type": "ABC", "query": "test"}
            ]
        }

        result = await rail.resolve_interrupt(ctx, tool_call, "")
        tool_result = result.tool_result

        # 验证返回 already_dispatched
        assert tool_result["status"] == "already_dispatched"

    @pytest.mark.asyncio
    async def test_skipped_entities_with_reason(self, rail, ctx, tool_call):
        """TC-30: skipped_entities 含 reason 字段 - message 包含具体原因"""
        ctx.session.update_state({
            "cascade_result": {
                "sub_agent_results": [
                    {"entity_id": "entity_001", "status": "done", "content": "结果1"},
                ],
                "skipped_entities": [
                    {"entity_id": "entity_003", "entity_name": "大疆", "reason": "max_call_depth"}
                ],
                "concurrency_limit": 3,
            },
            "pending_tool_context": {"tool_name": "call_multiagent", "tool_args": {}},
        })

        result = await rail.resolve_interrupt(ctx, tool_call, "")
        tool_result = result.tool_result

        # 验证 status=partial_success
        assert tool_result["status"] == "partial_success"

        # 验证 message 包含实体名称和 reason
        assert "大疆" in tool_result["message"]
        assert "max_call_depth" in tool_result["message"]

        # 验证 data 包含 skipped_entities
        assert "skipped_entities" in tool_result["data"]
        assert tool_result["data"]["skipped_entities"][0]["reason"] == "max_call_depth"

    @pytest.mark.asyncio
    async def test_all_skipped_entities(self, rail, ctx, tool_call):
        """TC-31: 全部 skipped（sub_agent_results 为空）- status=partial_success"""
        ctx.session.update_state({
            "cascade_result": {
                "sub_agent_results": [],
                "skipped_entities": [
                    {"entity_id": "entity_001", "entity_name": "小米", "reason": "max_call_depth"},
                    {"entity_id": "entity_002", "entity_name": "华为", "reason": "max_call_depth"},
                ],
                "concurrency_limit": 3,
            },
            "pending_tool_context": {"tool_name": "call_multiagent", "tool_args": {}},
        })

        result = await rail.resolve_interrupt(ctx, tool_call, "")
        tool_result = result.tool_result

        # 验证 status=partial_success（有 skipped 就不是 success）
        assert tool_result["status"] == "partial_success"

        # 验证 message 包含所有被跳过实体
        assert "小米" in tool_result["message"]
        assert "华为" in tool_result["message"]
        assert "max_call_depth" in tool_result["message"]
