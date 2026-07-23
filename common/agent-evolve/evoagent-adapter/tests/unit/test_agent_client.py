"""Unit tests for AgentClient — SSE consumer and answer assembly (Issue #3)."""

import json

import pytest

from agent_adapter.agent_client import AgentClient, _parse_sse_frame, _consume_sse_stream
from agent_adapter.schemas import AgentCallResponse, EventSummary


def _sse_frame(data: dict) -> str:
    """Build a single SSE data frame string."""
    return f"data: {json.dumps(data, ensure_ascii=False)}\n\n"


def _wrap_event(event_type: str, content: str = "", **extra) -> dict:
    """Build a wrapped SSE payload matching EDPAgent's north-bound format.

    EDPAgent SSE frames have the shape:
      {success: true, conversation_id: ..., agent_id: ..., custom_rsp_data: {event, content, data, ...}}
    """
    custom_rsp_data: dict = {"event": event_type, "content": content, "data": {}}
    custom_rsp_data.update(extra)
    return {
        "success": True,
        "conversation_id": "conv1",
        "agent_id": "edp_agent",
        "custom_rsp_data": custom_rsp_data,
    }


class TestSSEFrameParser:
    """_parse_sse_frame extracts data from SSE text lines."""

    def test_valid_data_line(self):
        payload = {"success": True, "custom_rsp_data": {"event": "summary", "content": "hi"}}
        line = f"data: {json.dumps(payload)}"
        result = _parse_sse_frame(line)
        assert result is not None
        assert result["custom_rsp_data"]["event"] == "summary"

    def test_done_signal_returns_none(self):
        result = _parse_sse_frame("data: [DONE]")
        assert result is None

    def test_empty_line_returns_none(self):
        result = _parse_sse_frame("")
        assert result is None

    def test_non_data_line_returns_none(self):
        result = _parse_sse_frame("event: message")
        assert result is None

    def test_invalid_json_returns_none(self):
        result = _parse_sse_frame("data: {invalid json}")
        assert result is None


class TestConsumeSSEStream:
    """_consume_sse_stream processes a sequence of parsed SSE events into AgentCallResponse."""

    def test_normal_answer_assembly(self):
        """final_answer_chunk is the authoritative full text; summary fragments are fallback."""
        events = [
            _wrap_event("summary", "Hello "),
            _wrap_event("summary", "world"),
            _wrap_event("final_answer_chunk", "Hello world"),
            _wrap_event("conversation_end"),
        ]
        response = _consume_sse_stream("conv1", events)
        assert response.success is True
        # final_answer_chunk takes priority over summary fragments
        assert response.answer == "Hello world"
        assert response.interrupted is False
        assert response.error is None

    def test_answer_from_final_answer_chunk_without_summary(self):
        """Regression: 只有 final_answer_chunk、无 summary 事件时，answer 必须取自
        final_answer_chunk（EDPAgent 实际行为：不发 summary，只发 final_answer_chunk）。

        此前 return 误用 ''.join(answer_parts)，而 answer_parts 仅由 summary 填充，
        导致 final_answer_chunk 的真实答案被丢弃、answer 恒为空。
        多个 final_answer_chunk 时取最后一个（每次都是完整全文，后者覆盖前者）。
        """
        events = [
            _wrap_event("conversation_start", "对话开始"),
            _wrap_event("think_start", "思考中"),
            _wrap_event("final_answer_chunk", "\n\n"),
            _wrap_event("final_answer_chunk", "账户余额为 125680.50 元"),
            _wrap_event("final_answer_end"),
        ]
        response = _consume_sse_stream("conv1", events)
        assert response.success is True
        assert response.answer == "账户余额为 125680.50 元"

    def test_answer_fallback_to_summary_when_no_chunk(self):
        """When no final_answer_chunk arrives, answer is assembled from summary fragments."""
        events = [
            _wrap_event("summary", "Hello "),
            _wrap_event("summary", "world"),
            _wrap_event("conversation_end"),
        ]
        response = _consume_sse_stream("conv1", events)
        assert response.answer == "Hello world"

    def test_interrupt_detection(self):
        """interrupt_start event sets interrupted=True with intent/description."""
        events = [
            _wrap_event("summary", "部分回答"),
            _wrap_event(
                "interrupt_start",
                "请确认您的选择",
                **{
                    "interrupt_intent": "理财推荐",
                    "interrupt_description": "推荐低风险理财产品",
                },
            ),
            _wrap_event("conversation_end"),
        ]
        response = _consume_sse_stream("conv1", events)
        assert response.interrupted is True
        assert response.interrupt_intent == "理财推荐"
        assert response.interrupt_description == "推荐低风险理财产品"

    def test_events_collection(self):
        """All events are collected into a simplified events list."""
        events = [
            _wrap_event("think_start", "思考中..."),
            _wrap_event("tool_start", "调用工具", **{"plugin": "call_versatile"}),
            _wrap_event("tool_end", "工具完成", **{"plugin": "call_versatile"}),
            _wrap_event("summary", "最终回答"),
            _wrap_event("conversation_end"),
        ]
        response = _consume_sse_stream("conv1", events)
        assert response.events is not None
        assert len(response.events) == 5
        assert response.events[0].type == "think_start"
        assert response.events[1].type == "tool_start"
        assert response.events[1].plugin == "call_versatile"
        assert response.events[2].type == "tool_end"
        assert response.events[3].type == "summary"

    def test_empty_stream(self):
        """Empty event list produces empty answer with no error."""
        response = _consume_sse_stream("conv1", [])
        assert response.success is True
        assert response.answer == ""
        assert response.interrupted is False

    def test_stream_without_conversation_end(self):
        """Stream ending without conversation_end still produces a valid response."""
        events = [
            _wrap_event("summary", "回答内容"),
        ]
        response = _consume_sse_stream("conv1", events)
        assert response.success is True
        assert response.answer == "回答内容"

    def test_multiple_interrupts_last_wins(self):
        """When multiple interrupt_start events appear, the last one's data is used."""
        events = [
            _wrap_event(
                "interrupt_start",
                "第一次中断",
                **{
                    "interrupt_intent": "意图1",
                    "interrupt_description": "描述1",
                },
            ),
            _wrap_event(
                "interrupt_start",
                "第二次中断",
                **{
                    "interrupt_intent": "意图2",
                    "interrupt_description": "描述2",
                },
            ),
        ]
        response = _consume_sse_stream("conv1", events)
        assert response.interrupted is True
        assert response.interrupt_intent == "意图2"

    def test_events_without_custom_rsp_data_skipped(self):
        """Events missing custom_rsp_data are skipped gracefully."""
        events = [
            {"success": True, "conversation_id": "conv1"},  # no custom_rsp_data
            _wrap_event("summary", "有效回答"),
            _wrap_event("conversation_end"),
        ]
        response = _consume_sse_stream("conv1", events)
        assert response.answer == "有效回答"
        assert response.events is not None
        # Only the summary and conversation_end events should appear
        assert len(response.events) == 2


class TestAgentClientBuildRequest:
    """AgentClient._build_url and request body assembly."""

    def test_build_url(self):
        client = AgentClient(
            agent_url="http://localhost:8090",
            project_id="proj_001",
            agent_id="edp_agent",
            timeout=300,
        )
        url = client._build_url("conv123")
        assert url == "http://localhost:8090/v1/proj_001/agents/edp_agent/conversations/conv123"

    def test_build_request_body(self):
        client = AgentClient(
            agent_url="http://localhost:8090",
            project_id="proj_001",
            agent_id="edp_agent",
            timeout=300,
        )
        body = client._build_request_body("你好", "conv123", extra_data={"UNION_NO": "12345"})
        assert body["agent_id"] == "edp_agent"
        assert body["input"]["query"] == "你好"
        assert body["conversation_id"] == "conv123"
        assert body["stream"] is True
        assert body["custom_data"]["inputs"]["query"] == "你好"
        assert body["custom_data"]["inputs"]["UNION_NO"] == "12345"

    def test_build_request_body_forwards_temperature_in_extra_data(self):
        """TF-GRPO may pass temperature via extra_data for rollout diversity."""
        client = AgentClient(
            agent_url="http://localhost:8090",
            project_id="proj_001",
            agent_id="edp_agent",
            timeout=300,
        )
        body = client._build_request_body(
            "你好",
            "conv123",
            extra_data={"temperature": 0.7, "run_id": "r1"},
        )
        assert body["custom_data"]["inputs"]["temperature"] == 0.7
        assert body["custom_data"]["inputs"]["run_id"] == "r1"

    def test_build_request_body_no_extra_data(self):
        client = AgentClient(
            agent_url="http://localhost:8090",
            project_id="proj_001",
            agent_id="edp_agent",
            timeout=300,
        )
        body = client._build_request_body("你好", "conv123")
        assert body["custom_data"]["inputs"] == {"query": "你好"}

    # ── request_template / extra_headers / url_query_params 透传 ──

    def test_build_url_with_query_params(self):
        client = AgentClient(
            agent_url="http://localhost:8090",
            project_id="proj_001",
            agent_id="edp_agent",
            timeout=300,
            url_query_params={"type": "controller", "workspace_id": "191"},
        )
        url = client._build_url("conv123")
        assert url == (
            "http://localhost:8090/v1/proj_001/agents/edp_agent"
            "/conversations/conv123?type=controller&workspace_id=191"
        )

    def test_build_url_without_query_params(self):
        """无 url_query_params 时 URL 不带 ?（向后兼容）。"""
        client = AgentClient(
            agent_url="http://localhost:8090",
            project_id="proj_001",
            agent_id="edp_agent",
            timeout=300,
        )
        url = client._build_url("conv123")
        assert "?" not in url

    def test_build_request_body_with_template_top_level_fields(self):
        """template 顶层字段（role_id/role_name/timeout）透传到最终 body。"""
        client = AgentClient(
            agent_url="http://localhost:8090",
            project_id="proj_001",
            agent_id="edp_agent",
            timeout=300,
            request_template={
                "timeout": 300,
                "role_id": "1",
                "role_name": "示例智能体",
                "custom_data": {
                    "user_profile": {"enable_extract": True, "enable_retrieve": True},
                },
            },
        )
        body = client._build_request_body("处理测试请求", "conv123")
        assert body["role_id"] == "1"
        assert body["role_name"] == "示例智能体"
        assert body["timeout"] == 300
        assert body["custom_data"]["user_profile"] == {
            "enable_extract": True,
            "enable_retrieve": True,
        }
        # adapter 补的必需字段
        assert body["agent_id"] == "edp_agent"
        assert body["conversation_id"] == "conv123"
        assert body["stream"] is True
        assert body["input"]["query"] == "处理测试请求"

    def test_build_request_body_template_user_profile_not_clobbered_by_extra_data(self):
        """extra_data 合并进 custom_data.inputs，不覆盖 template 的 custom_data.user_profile。"""
        client = AgentClient(
            agent_url="http://localhost:8090",
            project_id="proj_001",
            agent_id="edp_agent",
            timeout=300,
            request_template={
                "role_id": "1",
                "custom_data": {
                    "user_profile": {"enable_extract": True, "enable_retrieve": True},
                    "inputs": {"ZRTtype": "3"},  # template 预置稳定 inputs
                },
            },
        )
        body = client._build_request_body("query", "conv123", extra_data={"run_id": "r_001"})
        # user_profile 保留
        assert body["custom_data"]["user_profile"] == {
            "enable_extract": True,
            "enable_retrieve": True,
        }
        # inputs：template 的 ZRTtype + adapter 补的 query + extra_data 的 run_id
        assert body["custom_data"]["inputs"]["ZRTtype"] == "3"
        assert body["custom_data"]["inputs"]["run_id"] == "r_001"
        assert body["custom_data"]["inputs"]["query"] == "query"

    def test_build_request_body_template_not_mutated_across_calls(self):
        """深拷贝：多次调用不污染 template 原对象。"""
        template = {"role_id": "1", "custom_data": {"inputs": {"ZRTtype": "3"}}}
        client = AgentClient(
            agent_url="http://localhost:8090",
            project_id="proj_001",
            agent_id="edp_agent",
            timeout=300,
            request_template=template,
        )
        client._build_request_body("q1", "conv1", extra_data={"run_id": "r1"})
        client._build_request_body("q2", "conv2", extra_data={"run_id": "r2"})
        # template 原对象不被污染
        assert template["custom_data"]["inputs"] == {"ZRTtype": "3"}
        assert "run_id" not in template["custom_data"]["inputs"]
        assert "query" not in template["custom_data"]["inputs"]

    def test_build_headers_env_expansion(self, monkeypatch):
        """${ENV_VAR} 占位从环境变量读取，未设置则空串。"""
        monkeypatch.setenv("ADAPTER_AGENT_TOKEN", "tok_abc")
        client = AgentClient(
            agent_url="http://localhost:8090",
            project_id="proj_001",
            agent_id="edp_agent",
            timeout=300,
            extra_headers={
                "X-Invoke-Mode": "debug",
                "cftk": "${ADAPTER_AGENT_TOKEN}",
                "cf2-cftk": "${UNSET_VAR}",
            },
        )
        headers = client._build_headers()
        assert headers["X-Invoke-Mode"] == "debug"
        assert headers["cftk"] == "tok_abc"
        assert headers["cf2-cftk"] == ""

    def test_build_request_body_backward_compat_no_template(self):
        """无 request_template 时行为与历史一致（向后兼容）。"""
        client = AgentClient(
            agent_url="http://localhost:8090",
            project_id="proj_001",
            agent_id="edp_agent",
            timeout=300,
        )
        body = client._build_request_body("你好", "conv123", extra_data={"UNION_NO": "12345"})
        assert body == {
            "agent_id": "edp_agent",
            "input": {"query": "你好"},
            "conversation_id": "conv123",
            "stream": True,
            "custom_data": {"inputs": {"query": "你好", "UNION_NO": "12345"}},
        }


class _FakeStreamResponse:
    """Fake httpx streaming response for iter_sse_stream tests."""

    def __init__(self, lines: list[str] | None = None) -> None:
        self._lines = lines or []

    async def __aenter__(self) -> "_FakeStreamResponse":
        return self

    async def __aexit__(self, *_: object) -> bool:
        return False

    def raise_for_status(self) -> None:
        pass

    async def aiter_lines(self):
        for line in self._lines:
            yield line


class _FakeAsyncClient:
    """Fake httpx.AsyncClient — stream() returns _FakeStreamResponse or raises."""

    def __init__(
        self, lines: list[str] | None = None, stream_exc: BaseException | None = None
    ) -> None:
        self._lines = lines
        self._stream_exc = stream_exc

    async def __aenter__(self) -> "_FakeAsyncClient":
        return self

    async def __aexit__(self, *_: object) -> bool:
        return False

    def stream(self, *_: object, **__: object) -> _FakeStreamResponse:
        if self._stream_exc is not None:
            raise self._stream_exc
        return _FakeStreamResponse(self._lines)


class TestIterSSEStream:
    """iter_sse_stream 透传原始 SSE 行，异常转 error frame。"""

    def _make_client(self) -> AgentClient:
        return AgentClient(
            agent_url="http://edp:18001",
            project_id="proj",
            agent_id="agent1",
            timeout=300,
            request_template={"role_id": "1"},
            extra_headers={"X-Invoke-Mode": "debug"},
            url_query_params={"type": "controller"},
        )

    def _patch_httpx(
        self,
        monkeypatch,
        lines: list[str] | None = None,
        stream_exc: BaseException | None = None,
    ) -> None:
        monkeypatch.setattr(
            "agent_adapter.agent_client.httpx.AsyncClient",
            lambda **_: _FakeAsyncClient(lines=lines, stream_exc=stream_exc),
        )

    @pytest.mark.asyncio
    async def test_passthrough_lines_with_newline(self, monkeypatch):
        """正常流：每行末补 \\n 原样转发。"""
        lines = ['data: {"event":"start"}', "", 'data: {"event":"done"}', ""]
        self._patch_httpx(monkeypatch, lines=lines)
        client = self._make_client()
        out = [chunk async for chunk in client.iter_sse_stream("c1", "q")]
        assert out == [
            'data: {"event":"start"}\n',
            "\n",
            'data: {"event":"done"}\n',
            "\n",
        ]

    @pytest.mark.asyncio
    async def test_connect_error_yields_error_frame(self, monkeypatch):
        """ConnectError 不抛出，转成 SSE error 帧后关闭流。"""
        import httpx

        self._patch_httpx(monkeypatch, stream_exc=httpx.ConnectError("refused"))
        client = self._make_client()
        out = [chunk async for chunk in client.iter_sse_stream("c1", "q")]
        assert len(out) == 1
        assert out[0].startswith("data: ")
        assert out[0].endswith("\n\n")
        assert '"success": false' in out[0]
        assert "无法连接" in out[0]

    @pytest.mark.asyncio
    async def test_timeout_yields_error_frame(self, monkeypatch):
        """TimeoutException 不抛出，转成 SSE error 帧。"""
        import httpx

        self._patch_httpx(monkeypatch, stream_exc=httpx.TimeoutException("read timeout"))
        client = self._make_client()
        out = [chunk async for chunk in client.iter_sse_stream("c1", "q")]
        assert len(out) == 1
        assert "超时" in out[0]
        assert out[0].endswith("\n\n")

    @pytest.mark.asyncio
    async def test_uses_template_and_query_params(self, monkeypatch):
        """流式模式同样使用 request_template / url_query_params（建请求时复用 _build_*）。"""
        captured: dict = {}

        class _CapturingClient(_FakeAsyncClient):
            def stream(self, method: str, url: str, **kw: object) -> _FakeStreamResponse:
                captured["url"] = url
                captured["body"] = kw.get("json")
                captured["headers"] = kw.get("headers")
                return _FakeStreamResponse(['data: {"event":"done"}', ""])

        monkeypatch.setattr(
            "agent_adapter.agent_client.httpx.AsyncClient",
            lambda **_: _CapturingClient(),
        )
        client = self._make_client()
        _ = [c async for c in client.iter_sse_stream("c1", "q", extra_data={"ZRTtype": "3"})]

        assert "type=controller" in captured["url"]
        assert captured["body"]["role_id"] == "1"
        assert captured["body"]["custom_data"]["inputs"]["ZRTtype"] == "3"
        assert captured["headers"]["X-Invoke-Mode"] == "debug"
