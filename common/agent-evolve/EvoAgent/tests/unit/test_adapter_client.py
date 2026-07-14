"""AdapterClient 单元测试 — HTTP 通信层（基于最新 API 契约）。"""

from __future__ import annotations

import json
from typing import Any

import httpx
import pytest

from evo_agent.adapter_client.client import AdapterClient, AdapterError

# ── Fixtures ──


def _make_mock_client(
    handler: httpx.MockTransport,
    *,
    agent_name: str = "test_agent",
) -> AdapterClient:
    """创建使用 MockTransport 的 AdapterClient。"""
    client = AdapterClient(
        "http://mock-adapter",
        agent_name=agent_name,
    )
    # 替换内部 clients 的 transport
    client._async_http = httpx.AsyncClient(transport=handler, base_url="http://mock-adapter")
    client._async_http_loop = None  # sentinel: prevents _async_client from overriding mock
    client._sync_http = httpx.Client(transport=handler, base_url="http://mock-adapter")
    return client


# ── Construction ──


class TestConstruction:
    def test_empty_agent_name_raises(self) -> None:
        """agent_name 为空字符串时抛出 ValueError。"""
        with pytest.raises(ValueError, match="agent_name"):
            AdapterClient("http://adapter", agent_name="")

    def test_default_agent_name_raises(self) -> None:
        """不传 agent_name（默认空字符串）时抛出 ValueError。"""
        with pytest.raises(ValueError, match="agent_name"):
            AdapterClient("http://adapter")


# ── invoke ──


class TestInvoke:
    @pytest.mark.asyncio
    async def test_invoke_success(self) -> None:
        """invoke 正确构造请求体，路径含 agent_name，返回 answer。"""
        received: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            received["method"] = request.method
            received["path"] = request.url.path
            received["body"] = json.loads(request.content)
            return httpx.Response(
                200,
                json={
                    "success": True,
                    "conversation_id": "case-001",
                    "answer": "推荐产品A",
                    "interrupted": False,
                    "interrupt_intent": None,
                    "interrupt_description": None,
                    "events": [{"type": "summary", "content": "推荐产品A", "plugin": None}],
                    "error": None,
                },
            )

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport, agent_name="edp_agent")

        result = await client.invoke(
            case_id="case-001",
            query="帮我推荐理财",
            run_id="run-123",
        )

        # 验证响应解析
        assert result["success"] is True
        assert result["answer"] == "推荐产品A"
        assert result["interrupted"] is False
        assert len(result["events"]) == 1

        # 验证请求路径包含 agent_name
        assert received["method"] == "POST"
        assert "/api/v1/agents/edp_agent/conversations/case-001" in received["path"]

        # 验证请求体
        assert received["body"]["query"] == "帮我推荐理财"

    @pytest.mark.asyncio
    async def test_invoke_with_extra_data(self) -> None:
        """extra_data 正确传递，run_id 放入 extra_data。"""
        received_body: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            received_body.update(json.loads(request.content))
            return httpx.Response(
                200,
                json={
                    "success": True,
                    "conversation_id": "case-001",
                    "answer": "ok",
                    "interrupted": False,
                    "events": [],
                    "error": None,
                },
            )

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport)

        await client.invoke(
            case_id="case-001",
            query="test",
            extra_data={"role_id": "1"},
            run_id="run-abc",
        )

        assert received_body["extra_data"]["role_id"] == "1"
        assert received_body["extra_data"]["run_id"] == "run-abc"

    @pytest.mark.asyncio
    async def test_invoke_adapter_error_404(self) -> None:
        """404 响应（detail 格式）抛出 AdapterError。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                404,
                json={"detail": "Agent 'nonexistent' 不存在"},
            )

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport)

        with pytest.raises(AdapterError, match="nonexistent") as exc_info:
            await client.invoke(case_id="c1", query="test", run_id="r")

        assert exc_info.value.status_code == 404

    @pytest.mark.asyncio
    async def test_invoke_business_failure_via_success_field(self) -> None:
        """业务 Agent 调用失败：HTTP 200 + success=false → 抛出 AdapterError。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={
                    "success": False,
                    "conversation_id": "case-001",
                    "answer": "",
                    "interrupted": False,
                    "events": None,
                    "error": "无法连接 Agent 服务: Connection refused",
                },
            )

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport)

        with pytest.raises(AdapterError, match="Connection refused"):
            await client.invoke(case_id="case-001", query="test", run_id="r")

    @pytest.mark.asyncio
    async def test_invoke_retry_on_502(self) -> None:
        """502 触发重试，最终成功。"""
        call_count = 0

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal call_count
            call_count += 1
            if call_count < 3:
                return httpx.Response(
                    502,
                    json={"detail": "Bad Gateway"},
                )
            return httpx.Response(
                200,
                json={
                    "success": True,
                    "conversation_id": "c1",
                    "answer": "ok",
                    "interrupted": False,
                    "events": [],
                    "error": None,
                },
            )

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport, agent_name="a")
        client._max_retries = 2

        result = await client.invoke(case_id="c1", query="q", run_id="r")
        assert result["success"] is True
        assert call_count == 3

    @pytest.mark.asyncio
    async def test_invoke_no_retry_on_400(self) -> None:
        """400 不重试，直接抛出。"""
        call_count = 0

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal call_count
            call_count += 1
            return httpx.Response(
                400,
                json={"detail": "Agent 'xxx' 未配置 agent_url"},
            )

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport)

        with pytest.raises(AdapterError):
            await client.invoke(case_id="c1", query="q", run_id="r")

        assert call_count == 1  # 仅一次调用，无重试


# ── get_traces ──


class TestGetTraces:
    @pytest.mark.asyncio
    async def test_get_traces_success(self) -> None:
        """返回完整 cleaned-traces dict（含 messages）。"""

        def handler(request: httpx.Request) -> httpx.Response:
            assert "/api/v1/agents/test_agent/cleaned-traces/case-001" in request.url.path
            return httpx.Response(
                200,
                json={
                    "session_id": "case-001",
                    "agent_name": "test_agent",
                    "task_input": "帮我推荐理财",
                    "trajectory": {
                        "total_messages": 4,
                        "tool_calls_used": ["search_products"],
                        "summary": "4 messages, 1 tools",
                    },
                    "messages": [
                        {"role": "user", "content": "帮我推荐理财"},
                        {"role": "assistant", "content": "推荐产品A"},
                    ],
                },
            )

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport, agent_name="test_agent")

        traces = await client.get_traces(case_id="case-001")

        assert traces["session_id"] == "case-001"
        assert len(traces["messages"]) == 2
        assert traces["trajectory"]["total_messages"] == 4

    @pytest.mark.asyncio
    async def test_get_traces_empty(self) -> None:
        """无轨迹数据时返回空 dict。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json={})

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport)

        traces = await client.get_traces(case_id="empty-case")
        assert traces == {}


# ── update_skill (sync) ──


class TestUpdateSkill:
    def test_update_skill_success(self) -> None:
        """同步请求，验证请求体包含 agent_name。"""
        received_body: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            received_body.update(json.loads(request.content))
            return httpx.Response(
                200,
                json={"success": True, "skill_name": "product_skill"},
            )

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport, agent_name="edp_agent")

        client.update_skill(
            skill_name="product_skill",
            skill_content="# Updated Skill",
        )

        assert received_body["agent_name"] == "edp_agent"
        assert received_body["action"] == "update_skill"
        assert received_body["skill_name"] == "product_skill"
        assert received_body["skill_content"] == "# Updated Skill"

    def test_update_skill_failure(self) -> None:
        """success=false 抛出 AdapterError，错误信息从 message 字段获取。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={
                    "success": False,
                    "skill_name": "missing",
                    "message": "Skill 'missing' not found",
                },
            )

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport)

        with pytest.raises(AdapterError, match="Skill 'missing' not found"):
            client.update_skill(skill_name="missing", skill_content="")

    def test_update_skill_retry_on_502(self) -> None:
        """502 触发重试，最终成功。"""
        call_count = 0

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal call_count
            call_count += 1
            if call_count < 3:
                return httpx.Response(502, json={"detail": "Bad Gateway"})
            return httpx.Response(200, json={"success": True, "skill_name": "s"})

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport)
        client._max_retries = 2

        client.update_skill(skill_name="s", skill_content="c")
        assert call_count == 3

    def test_update_skill_no_retry_on_400(self) -> None:
        """400 不重试。"""
        call_count = 0

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal call_count
            call_count += 1
            return httpx.Response(400, json={"detail": "bad request"})

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport)

        with pytest.raises(AdapterError):
            client.update_skill(skill_name="s", skill_content="c")
        assert call_count == 1

    def test_update_skill_retry_exhausted(self) -> None:
        """重试耗尽后抛出最后的 AdapterError。"""
        call_count = 0

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal call_count
            call_count += 1
            return httpx.Response(502, json={"detail": "Bad Gateway"})

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport)
        client._max_retries = 2

        with pytest.raises(AdapterError) as exc_info:
            client.update_skill(skill_name="s", skill_content="c")
        assert exc_info.value.status_code == 502
        assert call_count == 3  # 1 initial + 2 retries


# ── skill_list / skill_content ──


class TestSkillOperations:
    @pytest.mark.asyncio
    async def test_skill_list_success(self) -> None:
        """请求体含 agent_name，返回 skill 列表。"""
        received_body: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            received_body.update(json.loads(request.content))
            return httpx.Response(
                200,
                json={
                    "skills": [
                        {"name": "skill_a"},
                        {"name": "skill_b"},
                    ]
                },
            )

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport, agent_name="edp_agent")

        skills = await client.skill_list()
        assert len(skills) == 2
        assert skills[0]["name"] == "skill_a"
        assert received_body["agent_name"] == "edp_agent"
        assert received_body["action"] == "skill_list"

    @pytest.mark.asyncio
    async def test_skill_content_success(self) -> None:
        """请求体含 agent_name，返回 content 字符串。"""
        received_body: dict[str, Any] = {}

        def handler(request: httpx.Request) -> httpx.Response:
            received_body.update(json.loads(request.content))
            return httpx.Response(
                200,
                json={
                    "skill_name": "my_skill",
                    "content": "# My Skill\n\nSkill content here.",
                },
            )

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport, agent_name="edp_agent")

        content = await client.skill_content("my_skill")
        assert content == "# My Skill\n\nSkill content here."
        assert received_body["agent_name"] == "edp_agent"
        assert received_body["action"] == "skill_content"
        assert received_body["skill_name"] == "my_skill"


# ── Lifecycle ──


class TestLifecycle:
    @pytest.mark.asyncio
    async def test_close(self) -> None:
        """close() 关闭 async 和 sync 两个 client。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json={})

        transport = httpx.MockTransport(handler)
        client = _make_mock_client(transport)

        await client.close()

    @pytest.mark.asyncio
    async def test_context_manager(self) -> None:
        """async with 正常进出。"""

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json={})

        transport = httpx.MockTransport(handler)

        async with _make_mock_client(transport) as client:
            assert isinstance(client, AdapterClient)
