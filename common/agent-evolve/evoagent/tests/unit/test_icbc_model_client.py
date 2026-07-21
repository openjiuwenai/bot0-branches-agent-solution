"""ICBCModelClient 单元测试 — mock HTTP SSE 流，不触真实端点。"""

from __future__ import annotations

import json
from typing import Any

import pytest
from openjiuwen.core.foundation.llm.schema.config import (
    ModelClientConfig,
    ModelRequestConfig,
)
from openjiuwen.core.foundation.llm.schema.message import AssistantMessage, UserMessage

# 必须先 import evo_agent 触发 registry 注册（client_provider="ICBC" 校验依赖）
import evo_agent  # noqa: F401
from evo_agent.llm.icbc_model_client import (
    ICBCModelClient,
    ICBCProtocolProfile,
    ICBCRequestError,
    ICBCStreamIntegrityError,
    ICBCTokenExpiredError,
)


def _chunk(content: str = "", **extra: Any) -> str:
    """构造一个 SSE chunk JSON 串（``data:`` 前缀由 _sse_bytes 加）。"""
    obj: dict[str, Any] = {
        "choices": [{"index": 0, "delta": {"content": content}}],
        "object": "chat.completion.chunk",
    }
    obj.update(extra)
    return json.dumps(obj, ensure_ascii=False)


def _sse_bytes(*chunks: str, done: bool = True) -> bytes:
    """把若干 chunk 串拼成 SSE 字节流（``data:{...}\\n\\n``，末尾可选 ``data:[DONE]``）。

    ``data:`` 后无空格，严格按 ICBC 实际格式。
    """
    body = b"".join(f"data:{c}\n\n".encode() for c in chunks)
    if done:
        body += b"data:[DONE]\n\n"
    return body


def _icbc_client(profile: ICBCProtocolProfile | None = None) -> ICBCModelClient:
    """构造一个 mock 用 ICBCModelClient（端点指向 http://mock-icbc）。"""
    return ICBCModelClient(
        model_config=ModelRequestConfig(model_name="icbc-deepseek"),
        model_client_config=ModelClientConfig(
            client_provider="ICBC",
            api_key="test-token",
            api_base="http://mock-icbc/mlpmodelservice/aigc/chat/completions",
            user_id="test-user",
            verify_ssl=False,
        ),
        profile=profile or ICBCProtocolProfile(context_window_tokens=32768),
    )


@pytest.fixture
def icbc_endpoint() -> str:
    return "http://mock-icbc/mlpmodelservice/aigc/chat/completions"


@pytest.fixture
def client() -> ICBCModelClient:
    return _icbc_client()


class TestICBCInvoke:
    @pytest.mark.asyncio
    async def test_invoke_multi_chunk_accumulates_content(
        self, httpx_mock, client, icbc_endpoint
    ) -> None:
        """多 chunk delta.content 累加 → AssistantMessage.content == 拼接。"""
        httpx_mock.add_response(
            url=icbc_endpoint,
            content=_sse_bytes(_chunk("西游"), _chunk("记"), _chunk("作者")),
        )
        msg = await client.invoke([UserMessage(content="hi")])
        assert msg.content == "西游记作者"

    @pytest.mark.asyncio
    async def test_invoke_uses_only_declared_profile_paths(self, httpx_mock, icbc_endpoint) -> None:
        """现场字段路径由 Profile 声明，Adapter 不猜 OpenAI 固定结构。"""
        profile = ICBCProtocolProfile(
            context_window_tokens=4096,
            output_reserve_tokens=512,
            chars_per_token=2.0,
            content_paths=("payload.text",),
            finish_reason_paths=("payload.finish",),
            usage_path="stats",
            supports_usage=True,
            supports_finish_reason=True,
        )
        client = _icbc_client(profile)
        chunk = json.dumps(
            {
                "payload": {"text": "profile-content", "finish": "stop"},
                "stats": {"prompt_tokens": 7, "completion_tokens": 3},
                "choices": [{"delta": {"content": "must-not-be-used"}}],
            }
        )
        httpx_mock.add_response(url=icbc_endpoint, content=_sse_bytes(chunk))

        message = await client.invoke([UserMessage(content="hi")])

        assert message.content == "profile-content"
        assert message.finish_reason == "stop"
        assert message.usage_metadata.input_tokens == 7
        assert message.usage_metadata.output_tokens == 3

    @pytest.mark.asyncio
    async def test_invoke_done_terminates_stream(self, httpx_mock, client, icbc_endpoint) -> None:
        """``data:[DONE]`` 后续 chunk 不再被读（显式 break）。"""
        sse = (
            _sse_bytes(_chunk("ok"), done=False)
            + b"data:[DONE]\n\n"
            + _sse_bytes(_chunk("should-not-appear"))
        )
        httpx_mock.add_response(url=icbc_endpoint, content=sse)
        msg = await client.invoke([UserMessage(content="hi")])
        assert msg.content == "ok"

    @pytest.mark.asyncio
    async def test_invoke_no_done_is_incomplete_by_default(
        self, httpx_mock, client, icbc_endpoint
    ) -> None:
        """默认 Profile 要求 ``[DONE]``，EOF 不得伪装成完整响应。"""
        httpx_mock.add_response(
            url=icbc_endpoint, content=_sse_bytes(_chunk("a"), _chunk("b"), done=False)
        )
        with pytest.raises(ICBCStreamIntegrityError):
            await client.invoke([UserMessage(content="hi")])

    @pytest.mark.asyncio
    async def test_invoke_empty_stream_raises(self, httpx_mock, client, icbc_endpoint) -> None:
        """只有 ``[DONE]``、无任何 content → ICBCRequestError（空答案契约）。"""
        httpx_mock.add_response(url=icbc_endpoint, content=_sse_bytes(done=True))
        with pytest.raises(ICBCRequestError) as exc_info:
            await client.invoke([UserMessage(content="hi")])
        assert "空" in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_invoke_only_empty_delta_chunks_raises(
        self, httpx_mock, client, icbc_endpoint
    ) -> None:
        """chunk 有但 delta 无 content（首 chunk 常见）→ 累加为空 → ICBCRequestError。"""
        httpx_mock.add_response(url=icbc_endpoint, content=_sse_bytes(_chunk(""), done=True))
        with pytest.raises(ICBCRequestError):
            await client.invoke([UserMessage(content="hi")])

    @pytest.mark.asyncio
    async def test_invoke_http_5xx_raises_request_error(
        self, httpx_mock, client, icbc_endpoint
    ) -> None:
        """HTTP 500 → ICBCRequestError，不冒泡裸 httpx 异常。"""
        httpx_mock.add_response(url=icbc_endpoint, status_code=500, text="internal server error")
        with pytest.raises(ICBCRequestError) as exc_info:
            await client.invoke([UserMessage(content="hi")])
        assert "500" in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_invoke_http_401_unauthorized_raises_token_expired(
        self, httpx_mock, client, icbc_endpoint
    ) -> None:
        """HTTP 401 + body 含 unauthorized → ICBCTokenExpiredError。"""
        httpx_mock.add_response(url=icbc_endpoint, status_code=401, text="unauthorized")
        with pytest.raises(ICBCTokenExpiredError):
            await client.invoke([UserMessage(content="hi")])

    @pytest.mark.asyncio
    async def test_invoke_chunk_error_raises_request_error(
        self, httpx_mock, client, icbc_endpoint
    ) -> None:
        """chunk 内 ``error`` 字段 → ICBCRequestError。"""
        httpx_mock.add_response(
            url=icbc_endpoint,
            content=_sse_bytes(json.dumps({"error": "internal error"}), done=True),
        )
        with pytest.raises(ICBCRequestError) as exc_info:
            await client.invoke([UserMessage(content="hi")])
        assert "internal error" in str(exc_info.value)

    @pytest.mark.asyncio
    @pytest.mark.parametrize(
        "msg",
        [
            "token expired, please re-login",
            "unauthorized access",
            "401 unauthorized",
            "token expired",
            "please re-login",
        ],
    )
    async def test_invoke_chunk_error_token_keywords_trigger_token_error(
        self, httpx_mock, client, icbc_endpoint, msg: str
    ) -> None:
        """chunk error 含 token 过期强信号 → ICBCTokenExpiredError。"""
        httpx_mock.add_response(
            url=icbc_endpoint,
            content=_sse_bytes(json.dumps({"error": msg}), done=True),
        )
        with pytest.raises(ICBCTokenExpiredError):
            await client.invoke([UserMessage(content="hi")])

    @pytest.mark.asyncio
    async def test_invoke_chunk_error_token_count_exceeded_not_token_expired(
        self, httpx_mock, client, icbc_endpoint
    ) -> None:
        """``token count exceeded`` 不误判为 token 过期（收紧匹配验证）。"""
        httpx_mock.add_response(
            url=icbc_endpoint,
            content=_sse_bytes(json.dumps({"error": "token count exceeded"}), done=True),
        )
        with pytest.raises(ICBCRequestError):
            await client.invoke([UserMessage(content="hi")])

    @pytest.mark.asyncio
    async def test_invoke_bad_json_line_rejects_partial_response(
        self, httpx_mock, client, icbc_endpoint
    ) -> None:
        """任何 malformed data payload 都使整次响应失败。"""
        sse = (
            b"data:{bad json}\n\n"
            + _sse_bytes(_chunk("good"), done=False)
            + b"data:also broken\n\n"
        ) + _sse_bytes(done=True)
        httpx_mock.add_response(url=icbc_endpoint, content=sse)
        with pytest.raises(ICBCStreamIntegrityError) as exc_info:
            await client.invoke([UserMessage(content="hi")])
        assert exc_info.value.chunk_index == 1

    @pytest.mark.asyncio
    async def test_invoke_sends_token_and_userid_headers(
        self, httpx_mock, client, icbc_endpoint
    ) -> None:
        """请求 header 带 token/userId/Content-Type（非 Authorization Bearer）。"""
        httpx_mock.add_response(url=icbc_endpoint, content=_sse_bytes(_chunk("ok"), done=True))
        await client.invoke([UserMessage(content="hi")])
        req = httpx_mock.get_requests()[-1]
        assert req.headers["token"] == "test-token"
        assert req.headers["userId"] == "test-user"
        assert req.headers["Content-Type"] == "application/json"
        assert "authorization" not in req.headers

    @pytest.mark.asyncio
    async def test_invoke_body_messages_and_stream_string(
        self, httpx_mock, client, icbc_endpoint
    ) -> None:
        """body 含 messages 数组（role/content）+ stream 字符串 "true"。"""
        httpx_mock.add_response(url=icbc_endpoint, content=_sse_bytes(_chunk("ok"), done=True))
        await client.invoke([UserMessage(content="the question")])
        req = httpx_mock.get_requests()[-1]
        body = json.loads(req.content)
        assert body["stream"] == "true"
        assert body["messages"] == [{"role": "user", "content": "the question"}]
        assert "sessionId" not in body
        assert "model" not in body

    @pytest.mark.asyncio
    async def test_invoke_returns_assistant_message(
        self, httpx_mock, client, icbc_endpoint
    ) -> None:
        """invoke 返回类型为 AssistantMessage。"""
        httpx_mock.add_response(url=icbc_endpoint, content=_sse_bytes(_chunk("hello"), done=True))
        msg = await client.invoke([UserMessage(content="hi")])
        assert isinstance(msg, AssistantMessage)
        assert msg.content == "hello"


class TestICBCStream:
    @pytest.mark.asyncio
    async def test_stream_yields_chunks_in_order(self, httpx_mock, client, icbc_endpoint) -> None:
        """多 chunk 逐个 yield，顺序与 content 保持。"""
        httpx_mock.add_response(
            url=icbc_endpoint,
            content=_sse_bytes(_chunk("a"), _chunk("b"), _chunk("c")),
        )
        chunks = []
        async for chunk in client.stream([UserMessage(content="hi")]):
            chunks.append(chunk)
        assert [c.content for c in chunks] == ["a", "b", "c"]

    @pytest.mark.asyncio
    async def test_stream_skips_empty_content_chunks(
        self, httpx_mock, client, icbc_endpoint
    ) -> None:
        """delta 无 content 的 chunk（首 chunk 常见）跳过不 yield。"""
        httpx_mock.add_response(
            url=icbc_endpoint,
            content=_sse_bytes(_chunk(""), _chunk("real"), _chunk("")),
        )
        chunks = []
        async for chunk in client.stream([UserMessage(content="hi")]):
            chunks.append(chunk)
        assert [c.content for c in chunks] == ["real"]

    @pytest.mark.asyncio
    async def test_stream_done_terminates(self, httpx_mock, client, icbc_endpoint) -> None:
        """``[DONE]`` 后停止 yield。"""
        sse = _sse_bytes(_chunk("ok"), done=False) + b"data:[DONE]\n\n" + _sse_bytes(_chunk("late"))
        httpx_mock.add_response(url=icbc_endpoint, content=sse)
        chunks = []
        async for chunk in client.stream([UserMessage(content="hi")]):
            chunks.append(chunk)
        assert [c.content for c in chunks] == ["ok"]


class TestMessagesToOpenAIFormat:
    def test_str_wraps_as_user_message(self, client) -> None:
        """str → [{"role":"user","content":str}]。"""
        assert client._messages_to_openai_format("abc") == [{"role": "user", "content": "abc"}]

    def test_list_base_message_maps_role_and_content(self, client) -> None:
        """list[BaseMessage] → [{"role":m.role,"content":m.content}]。"""
        msgs = [UserMessage(content="hello")]
        result = client._messages_to_openai_format(msgs)
        assert result == [{"role": "user", "content": "hello"}]

    def test_list_dict_passthrough(self, client) -> None:
        """list[dict] 透传 role/content。"""
        msgs = [
            {"role": "system", "content": "sys"},
            {"role": "user", "content": "q"},
        ]
        assert client._messages_to_openai_format(msgs) == [
            {"role": "system", "content": "sys"},
            {"role": "user", "content": "q"},
        ]

    def test_multimodal_content_flattened_to_str(self, client) -> None:
        """content 是 list（多模态）时拼 str 元素，丢弃 dict 部分。"""
        msgs = [{"role": "user", "content": ["text", {"image": "x"}, "more"]}]
        result = client._messages_to_openai_format(msgs)
        assert result == [{"role": "user", "content": "textmore"}]

    def test_empty_str_raises(self, client) -> None:
        with pytest.raises(ValueError):
            client._messages_to_openai_format("")

    def test_empty_list_raises(self, client) -> None:
        with pytest.raises(ValueError):
            client._messages_to_openai_format([])

    def test_all_empty_content_raises(self, client) -> None:
        """全部消息 content 为空 → ValueError（不向端点发空 prompt）。"""
        with pytest.raises(ValueError):
            client._messages_to_openai_format(
                [{"role": "user", "content": ""}, {"role": "user", "content": ""}]
            )


class TestParseChunk:
    def test_valid_json(self) -> None:
        assert ICBCModelClient._parse_chunk('{"a": 1}') == {"a": 1}

    def test_bad_json_returns_none(self) -> None:
        assert ICBCModelClient._parse_chunk("{bad json}") is None

    def test_non_dict_returns_none(self) -> None:
        """JSON 解析成非 dict（list/int）→ None。"""
        assert ICBCModelClient._parse_chunk("[1, 2]") is None
        assert ICBCModelClient._parse_chunk("123") is None


class TestExtractContent:
    def test_normal(self) -> None:
        chunk = {"choices": [{"delta": {"content": "hi"}}]}
        assert ICBCModelClient._extract_content(chunk) == "hi"

    def test_missing_choices(self) -> None:
        assert ICBCModelClient._extract_content({}) == ""

    def test_empty_choices(self) -> None:
        assert ICBCModelClient._extract_content({"choices": []}) == ""

    def test_missing_delta(self) -> None:
        assert ICBCModelClient._extract_content({"choices": [{}]}) == ""

    def test_empty_content(self) -> None:
        chunk = {"choices": [{"delta": {}}]}
        assert ICBCModelClient._extract_content(chunk) == ""

    def test_non_string_content(self) -> None:
        """content 非 str（数字等）→ 返回空。"""
        chunk = {"choices": [{"delta": {"content": 123}}]}
        assert ICBCModelClient._extract_content(chunk) == ""


class TestTokenExpiredDetection:
    def test_is_token_expired_keywords(self, client) -> None:
        assert client._is_token_expired("Token expired")
        assert client._is_token_expired("something 401 unauthorized")
        assert client._is_token_expired("token expired, please re-login")

    def test_is_token_expired_plain_msg_false(self, client) -> None:
        assert not client._is_token_expired("internal error")
        assert not client._is_token_expired("")
        # 收紧后：``token count exceeded`` 不再误判为 token 过期
        assert not client._is_token_expired("token count exceeded")
        assert not client._is_token_expired("the token is invalid")
