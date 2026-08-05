"""CustomSSEModelClient — 把 openjiuwen messages 协议翻译成自定义 SSE 端点。

自定义 SSE 端点契约：

    POST <endpoint>  (例如 https://llm-gateway.example.com/v1/chat/completions)
    header: token: <TOKEN>   userId: <USER_ID>   Content-Type: application/json
    body:   { "messages": [{"role": "...", "content": "..."}], "stream": "true" }

    resp:   SSE 流，每行 ``data:{...}``（``data:`` 后无空格），``data:[DONE]`` 结束
            chunk 形如 {"choices":[{"delta":{"content":"..."},"index":0}], ...}

- 端点**只能流式**：``invoke`` 消费整个 SSE 流累加 ``delta.content``，``stream`` 逐 chunk yield。
- 无状态：上下文靠 ``messages`` 数组携带，不传 sessionId。
- 鉴权用自定义 ``token`` / ``userId`` header（非 ``Authorization: Bearer``），故不复用 openai SDK。
- ``stream`` 字段按 curl 原样发字符串 ``"true"``；不传 ``model``（服务端自决）。
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any, Literal

import httpx
from openjiuwen.core.foundation.llm.model_clients.base_model_client import BaseModelClient
from openjiuwen.core.foundation.llm.schema.generation_response import (
    AudioGenerationResponse,
    ImageGenerationResponse,
    VideoGenerationResponse,
)
from openjiuwen.core.foundation.llm.schema.message import (
    AssistantMessage,
    BaseMessage,
    UserMessage,
)
from openjiuwen.core.foundation.llm.schema.message_chunk import AssistantMessageChunk
from openjiuwen.core.foundation.tool import ToolInfo


class EndpointCredentialExpiredError(Exception):
    """端点凭证过期或失效，需要更新凭证。"""


class SSERequestError(Exception):
    """自定义 SSE 端点返回失败（HTTP 错误、流内 error、空响应等）。"""

    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
        retryable: bool = False,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.retryable = retryable


class SSEStreamIntegrityError(SSERequestError):
    """A declared SSE data chunk was malformed; partial text is unusable."""

    category = "transport_incomplete"

    def __init__(self, *, chunk_index: int, raw_payload: str) -> None:
        super().__init__(f"CustomSSE SSE data chunk {chunk_index} is malformed", retryable=True)
        self.chunk_index = chunk_index
        self.raw_payload = raw_payload


@dataclass(frozen=True)
class SSEProtocolProfile:
    """Declarative contract for one verified custom SSE deployment protocol."""

    context_window_tokens: int = 32768
    output_reserve_tokens: int = 2048
    chars_per_token: float = 2.0
    stream_value: str | bool = "true"
    done_sentinel: str = "[DONE]"
    max_output_tokens_field: str | None = None
    content_paths: tuple[str, ...] = ("choices.0.delta.content",)
    finish_reason_paths: tuple[str, ...] = ("choices.0.finish_reason",)
    usage_path: str | None = None
    supports_usage: bool = False
    supports_finish_reason: bool = False
    completion_signal: Literal["done", "eof", "either"] = "done"


class CustomSSEModelClient(BaseModelClient):  # type: ignore[misc]
    """自定义 SSE 文本模型 client — 通过 registry 注册名 ``llm_CustomSSE``。

    注册由 ``BaseModelClient.__init_subclass__`` 在类定义（import）时自动完成，
    此处只声明 ``__client_name__`` / ``__client_type__``。
    """

    __client_name__ = "CustomSSE"
    __client_type__ = "llm"

    def __init__(
        self,
        model_config: Any,
        model_client_config: Any,
        *,
        profile: SSEProtocolProfile | None = None,
    ) -> None:
        super().__init__(model_config, model_client_config)
        self._profile = profile or SSEProtocolProfile(
            context_window_tokens=getattr(model_client_config, "context_window_tokens", 32768),
            output_reserve_tokens=getattr(model_client_config, "output_reserve_tokens", 2048),
            chars_per_token=getattr(model_client_config, "chars_per_token", 2.0),
            completion_signal=getattr(model_client_config, "completion_signal", "done"),
            max_output_tokens_field=getattr(model_client_config, "max_output_tokens_field", None),
        )

    async def invoke(
        self,
        messages: str | list[BaseMessage] | list[dict[str, Any]],
        *,
        tools: list[ToolInfo] | list[dict[str, Any]] | None = None,  # noqa: ARG002
        temperature: float | None = None,  # noqa: ARG002 — 严格按 curl，不传
        top_p: float | None = None,  # noqa: ARG002
        model: str | None = None,  # noqa: ARG002 — 端点不消费 model_name
        max_tokens: int | None = None,
        stop: str | None = None,  # noqa: ARG002
        output_parser: Any | None = None,  # noqa: ARG002
        timeout: float | None = None,
        **kwargs: Any,  # noqa: ARG002
    ) -> AssistantMessage:
        """消费整个 SSE 流，累加 ``delta.content`` 装入 AssistantMessage。"""
        parts: list[str] = []
        finish_reason: str | None = None
        usage: dict[str, Any] | None = None
        stream_metadata: dict[str, Any] = {}
        async for chunk in self._iter_sse(
            messages=messages, timeout=timeout, max_output_tokens=max_tokens
        ):
            if "__evo_stream_metadata__" in chunk:
                stream_metadata = dict(chunk["__evo_stream_metadata__"])
                continue
            content = self._extract_content(chunk, self._profile.content_paths)
            if content:
                parts.append(content)
            finish_reason = (
                self._extract_finish_reason(
                    chunk,
                    self._profile.finish_reason_paths,
                    enabled=self._profile.supports_finish_reason,
                )
                or finish_reason
            )
            raw_usage = (
                _value_at_path(chunk, self._profile.usage_path)
                if self._profile.supports_usage and self._profile.usage_path
                else None
            )
            if isinstance(raw_usage, dict):
                usage = raw_usage
        answer = "".join(parts)
        if not answer:
            raise SSERequestError("CustomSSE 流式响应为空：未收到任何 content")
        usage_metadata = None
        if usage is not None:
            usage_metadata = {
                "input_tokens": usage.get("prompt_tokens", usage.get("input_tokens", 0)),
                "output_tokens": usage.get("completion_tokens", usage.get("output_tokens", 0)),
            }
        return AssistantMessage(
            content=answer,
            finish_reason=finish_reason or "",
            usage_metadata=usage_metadata,
            metadata={"provider": "CustomSSE", **stream_metadata},
        )

    async def stream(
        self,
        messages: str | list[BaseMessage] | list[dict[str, Any]],
        *,
        tools: list[ToolInfo] | list[dict[str, Any]] | None = None,  # noqa: ARG002
        temperature: float | None = None,  # noqa: ARG002
        top_p: float | None = None,  # noqa: ARG002
        model: str | None = None,  # noqa: ARG002
        max_tokens: int | None = None,
        stop: str | None = None,  # noqa: ARG002
        output_parser: Any | None = None,  # noqa: ARG002
        timeout: float | None = None,
        **kwargs: Any,  # noqa: ARG002
    ) -> AsyncIterator[AssistantMessageChunk]:
        """逐 chunk yield ``delta.content`` 装入 AssistantMessageChunk。"""
        async for chunk in self._iter_sse(
            messages=messages, timeout=timeout, max_output_tokens=max_tokens
        ):
            if "__evo_stream_metadata__" in chunk:
                continue
            content = self._extract_content(chunk, self._profile.content_paths)
            if content:
                yield AssistantMessageChunk(content=content)

    # --- 自定义 SSE 协议无关的 abstract 方法：裸文本 LLM 不支持多模态生成 ---

    async def generate_image(
        self,
        messages: list[UserMessage],
        *,
        model: str | None = None,
        size: str | None = "1664*928",
        negative_prompt: str | None = None,
        n: int | None = 1,
        prompt_extend: bool = True,
        watermark: bool = False,
        seed: int = 0,
        **kwargs: Any,
    ) -> ImageGenerationResponse:
        raise NotImplementedError(
            "CustomSSEModelClient is a text-only LLM endpoint, no image generation"
        )

    async def generate_speech(
        self,
        messages: list[UserMessage],
        *,
        model: str | None = None,
        voice: str | None = "Cherry",
        language_type: str | None = "Auto",
        **kwargs: Any,
    ) -> AudioGenerationResponse:
        raise NotImplementedError(
            "CustomSSEModelClient is a text-only LLM endpoint, no speech generation"
        )

    async def generate_video(
        self,
        messages: list[UserMessage],
        *,
        img_url: str | None = None,
        audio_url: str | None = None,
        model: str | None = None,
        size: str | None = None,
        resolution: str | None = None,
        duration: int | None = 5,
        prompt_extend: bool = True,
        watermark: bool = False,
        negative_prompt: str | None = None,
        seed: int | None = None,
        **kwargs: Any,
    ) -> VideoGenerationResponse:
        raise NotImplementedError(
            "CustomSSEModelClient is a text-only LLM endpoint, no video generation"
        )

    # --- 内部 helpers ---

    async def _iter_sse(
        self,
        *,
        messages: str | list[BaseMessage] | list[dict[str, Any]],
        timeout: float | None,
        max_output_tokens: int | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        """发流式 POST，逐个 yield 解析后的 chunk dict。

        - HTTP 4xx/5xx → 读 body 包装成 ``SSERequestError``（token 过期信号分流为
          ``EndpointCredentialExpiredError``）。
        - 按行 ``aiter_lines()``，只处理 ``data:`` 开头行；空行/心跳/``event:`` 跳过。
        - ``data:[DONE]`` → 立即 ``break``（显式结束信号，不与坏行混同）。
        - chunk JSON 含 ``error`` 字段 → 抛 ``SSERequestError``/``EndpointCredentialExpiredError``。
        - 半截/坏 JSON 行 → 跳过不崩。
        """
        headers = self._build_headers()
        body = self._build_body(messages, max_output_tokens=max_output_tokens)
        read_to = timeout if timeout is not None else self.model_client_config.timeout
        timeout_conf = httpx.Timeout(connect=10.0, read=read_to, write=10.0, pool=10.0)
        async with httpx.AsyncClient(timeout=timeout_conf) as http:
            async with http.stream(
                "POST", self.model_client_config.api_base, headers=headers, json=body
            ) as resp:
                if resp.status_code >= 400:
                    raw = (await resp.aread()).decode(errors="replace")[:200]
                    msg = f"HTTP {resp.status_code}: {raw}"
                    if self._is_token_expired(msg):
                        raise EndpointCredentialExpiredError(msg)
                    raise SSERequestError(
                        msg,
                        status_code=resp.status_code,
                        retryable=resp.status_code == 429 or resp.status_code >= 500,
                    )
                chunk_index = 0
                done_received = False
                async for line in resp.aiter_lines():
                    stripped = line.strip()
                    if not stripped.startswith("data:"):
                        continue
                    chunk_index += 1
                    payload = stripped[len("data:") :].strip()
                    if payload == self._profile.done_sentinel:
                        done_received = True
                        break
                    chunk = self._parse_chunk(payload)
                    if chunk is None:
                        raise SSEStreamIntegrityError(
                            chunk_index=chunk_index,
                            raw_payload=json.dumps(payload, ensure_ascii=False),
                        )
                    if "error" in chunk:
                        emsg = str(chunk["error"])
                        if self._is_token_expired(emsg):
                            raise EndpointCredentialExpiredError(emsg)
                        raise SSERequestError(emsg)
                    yield chunk
                if not done_received and self._profile.completion_signal == "done":
                    raise SSEStreamIntegrityError(
                        chunk_index=chunk_index + 1,
                        raw_payload=json.dumps("<EOF>", ensure_ascii=False),
                    )
                yield {
                    "__evo_stream_metadata__": {
                        "done_received": done_received,
                        "completion_signal": "done" if done_received else "eof",
                        "chunk_count": chunk_index - int(done_received),
                        "invalid_chunk_count": 0,
                        "transport_complete": done_received
                        or self._profile.completion_signal in {"eof", "either"},
                    }
                }

    @staticmethod
    def _parse_chunk(payload: str) -> dict[str, Any] | None:
        """单行 ``data:`` payload → dict。半截/坏 JSON 返回 None（跳过不崩）。"""
        try:
            data = json.loads(payload)
        except json.JSONDecodeError:
            return None
        return data if isinstance(data, dict) else None

    @staticmethod
    def _extract_content(
        chunk: dict[str, Any],
        paths: tuple[str, ...] = ("choices.0.delta.content",),
    ) -> str:
        """Read content only from the selected deployment Profile paths."""
        for path in paths:
            value = _value_at_path(chunk, path)
            if isinstance(value, str):
                return value
        return ""

    @staticmethod
    def _extract_finish_reason(
        chunk: dict[str, Any],
        paths: tuple[str, ...] = ("choices.0.finish_reason",),
        *,
        enabled: bool = True,
    ) -> str | None:
        if not enabled:
            return None
        for path in paths:
            value = _value_at_path(chunk, path)
            if isinstance(value, str):
                return value
        return None

    def _build_headers(self) -> dict[str, str]:
        """端点鉴权 header：``token`` + ``userId``（非 Authorization Bearer）。"""
        return {
            "token": self.model_client_config.api_key,
            "userId": getattr(self.model_client_config, "user_id", ""),
            "Content-Type": "application/json",
        }

    def _build_body(
        self,
        messages: str | list[BaseMessage] | list[dict[str, Any]],
        *,
        max_output_tokens: int | None = None,
    ) -> dict[str, Any]:
        """请求体：``messages`` 数组 + ``stream:"true"``（字符串，按 curl 原样）。"""
        body: dict[str, Any] = {
            "messages": self._messages_to_openai_format(messages),
            "stream": self._profile.stream_value,
        }
        if self._profile.max_output_tokens_field and max_output_tokens is not None:
            body[self._profile.max_output_tokens_field] = max_output_tokens
        return body

    def _messages_to_openai_format(
        self, messages: str | list[BaseMessage] | list[dict[str, Any]]
    ) -> list[dict[str, str]]:
        """把 openjiuwen messages 翻译成端点的 ``messages`` 数组。

        - str → ``[{"role":"user","content":str}]``
        - list[BaseMessage] → ``[{"role":m.role,"content":<str>}]``
        - list[dict] → 透传 ``role`` / ``content``（content 归一为 str）
        - 空 messages / 全空 content → ValueError（不向端点发空 prompt）

        ``BaseMessage.content`` 是 ``Union[str, List]``，CustomSSE 纯文本场景归一为 str：
        list 时拼接其中的 str 元素，丢弃 dict（多模态）部分。
        """
        if isinstance(messages, str):
            if not messages:
                raise ValueError("CustomSSE invoke 收到空 messages，不向端点发空 prompt")
            return [{"role": "user", "content": messages}]
        if not isinstance(messages, list):  # pragma: no cover — 类型约束兜底
            raise TypeError(f"CustomSSE invoke 不支持的 messages 类型: {type(messages)!r}")
        if not messages:
            raise ValueError("CustomSSE invoke 收到空 messages，不向端点发空 prompt")
        result: list[dict[str, str]] = []
        for m in messages:
            if isinstance(m, dict):
                role = str(m.get("role", "user"))
                content = self._content_to_str(m.get("content", ""))
            else:
                role = str(getattr(m, "role", "user"))
                content = self._content_to_str(getattr(m, "content", ""))
            if not content:
                continue
            result.append({"role": role, "content": content})
        if not result:
            raise ValueError("CustomSSE invoke 收到空 messages，不向端点发空 prompt")
        return result

    @staticmethod
    def _content_to_str(content: Any) -> str:
        """``BaseMessage.content``（str | list）归一为 str。"""
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            return "".join(p for p in content if isinstance(p, str))
        return str(content) if content else ""

    @staticmethod
    def _is_token_expired(msg: str) -> bool:
        """msg 含 token 过期信号 → 视为 token 过期。

        收紧匹配：要求 ``token expired`` / ``unauthorized`` / ``401`` / ``re-login``
        等强信号，避免 ``token count exceeded`` 等无关错误被误判触发人工换 token。
        """
        low = msg.lower()
        return any(
            k in low for k in ("token expired", "unauthorized", "401", "re-login", "relogin")
        )


def _value_at_path(value: Any, path: str) -> Any:
    """Resolve one declarative dotted dict/list path without guessing alternatives."""
    current = value
    for part in path.split("."):
        if isinstance(current, dict):
            if part not in current:
                return None
            current = current[part]
        elif isinstance(current, list) and part.isdigit():
            index = int(part)
            if index >= len(current):
                return None
            current = current[index]
        else:
            return None
    return current
