"""ICBCModelClient — 把 openjiuwen messages 协议翻译成 ICBC OpenAI 兼容流式端点。

ICBC 端点契约（见 docs/adr/0008-icbc-endpoint-openai-streaming.md）：

    POST <endpoint>  (例如 http://aigc.sdc.cs.icbc/mlpmodelservice/aigc/chat/completions)
    header: token: <JWT>   userId: <固定值>   Content-Type: application/json
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
from typing import Any

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


class ICBCTokenExpiredError(Exception):
    """ICBC token 过期/失效，需人工换 token。"""


class ICBCRequestError(Exception):
    """ICBC 端点返回失败（HTTP 错误、流内 error、空响应等，且非 token 过期）。"""


class ICBCModelClient(BaseModelClient):  # type: ignore[misc]
    """ICBC 内网大模型 client — 通过 registry 注册名 ``llm_ICBC``。

    注册由 ``BaseModelClient.__init_subclass__`` 在类定义（import）时自动完成，
    此处只声明 ``__client_name__`` / ``__client_type__``。
    """

    __client_name__ = "ICBC"
    __client_type__ = "llm"

    async def invoke(
        self,
        messages: str | list[BaseMessage] | list[dict[str, Any]],
        *,
        tools: list[ToolInfo] | list[dict[str, Any]] | None = None,  # noqa: ARG002
        temperature: float | None = None,  # noqa: ARG002 — 严格按 curl，不传
        top_p: float | None = None,  # noqa: ARG002
        model: str | None = None,  # noqa: ARG002 — ICBC 不消费 model_name
        max_tokens: int | None = None,  # noqa: ARG002
        stop: str | None = None,  # noqa: ARG002
        output_parser: Any | None = None,  # noqa: ARG002
        timeout: float | None = None,
        **kwargs: Any,  # noqa: ARG002
    ) -> AssistantMessage:
        """消费整个 SSE 流，累加 ``delta.content`` 装入 AssistantMessage。"""
        parts: list[str] = []
        async for chunk in self._iter_sse(messages=messages, timeout=timeout):
            content = self._extract_content(chunk)
            if content:
                parts.append(content)
        answer = "".join(parts)
        if not answer:
            raise ICBCRequestError("ICBC 流式响应为空：未收到任何 content")
        return AssistantMessage(content=answer)

    async def stream(
        self,
        messages: str | list[BaseMessage] | list[dict[str, Any]],
        *,
        tools: list[ToolInfo] | list[dict[str, Any]] | None = None,  # noqa: ARG002
        temperature: float | None = None,  # noqa: ARG002
        top_p: float | None = None,  # noqa: ARG002
        model: str | None = None,  # noqa: ARG002
        max_tokens: int | None = None,  # noqa: ARG002
        stop: str | None = None,  # noqa: ARG002
        output_parser: Any | None = None,  # noqa: ARG002
        timeout: float | None = None,
        **kwargs: Any,  # noqa: ARG002
    ) -> AsyncIterator[AssistantMessageChunk]:
        """逐 chunk yield ``delta.content`` 装入 AssistantMessageChunk。"""
        async for chunk in self._iter_sse(messages=messages, timeout=timeout):
            content = self._extract_content(chunk)
            if content:
                yield AssistantMessageChunk(content=content)

    # --- ICBC 协议无关的 abstract 方法：裸文本 LLM 不支持多模态生成 ---

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
            "ICBCModelClient is a text-only LLM endpoint, no image generation"
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
            "ICBCModelClient is a text-only LLM endpoint, no speech generation"
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
            "ICBCModelClient is a text-only LLM endpoint, no video generation"
        )

    # --- 内部 helpers ---

    async def _iter_sse(
        self,
        *,
        messages: str | list[BaseMessage] | list[dict[str, Any]],
        timeout: float | None,
    ) -> AsyncIterator[dict[str, Any]]:
        """发流式 POST，逐个 yield 解析后的 chunk dict。

        - HTTP 4xx/5xx → 读 body 包装成 ``ICBCRequestError``（token 过期信号分流为
          ``ICBCTokenExpiredError``）。
        - 按行 ``aiter_lines()``，只处理 ``data:`` 开头行；空行/心跳/``event:`` 跳过。
        - ``data:[DONE]`` → 立即 ``break``（显式结束信号，不与坏行混同）。
        - chunk JSON 含 ``error`` 字段 → 抛 ``ICBCRequestError``/``ICBCTokenExpiredError``。
        - 半截/坏 JSON 行 → 跳过不崩。
        """
        headers = self._build_headers()
        body = self._build_body(messages)
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
                        raise ICBCTokenExpiredError(msg)
                    raise ICBCRequestError(msg)
                async for line in resp.aiter_lines():
                    stripped = line.strip()
                    if not stripped.startswith("data:"):
                        continue
                    payload = stripped[len("data:") :].strip()
                    if payload == "[DONE]":
                        break
                    chunk = self._parse_chunk(payload)
                    if chunk is None:
                        continue
                    if "error" in chunk:
                        emsg = str(chunk["error"])
                        if self._is_token_expired(emsg):
                            raise ICBCTokenExpiredError(emsg)
                        raise ICBCRequestError(emsg)
                    yield chunk

    @staticmethod
    def _parse_chunk(payload: str) -> dict[str, Any] | None:
        """单行 ``data:`` payload → dict。半截/坏 JSON 返回 None（跳过不崩）。"""
        try:
            data = json.loads(payload)
        except json.JSONDecodeError:
            return None
        return data if isinstance(data, dict) else None

    @staticmethod
    def _extract_content(chunk: dict[str, Any]) -> str:
        """从 chunk dict 取 ``choices[0].delta.content``，类型安全。"""
        choices = chunk.get("choices")
        if not isinstance(choices, list) or not choices:
            return ""
        first = choices[0]
        if not isinstance(first, dict):
            return ""
        delta = first.get("delta")
        if not isinstance(delta, dict):
            return ""
        content = delta.get("content")
        return content if isinstance(content, str) else ""

    def _build_headers(self) -> dict[str, str]:
        """ICBC 鉴权 header：``token`` + ``userId``（非 Authorization Bearer）。"""
        return {
            "token": self.model_client_config.api_key,
            "userId": getattr(self.model_client_config, "user_id", ""),
            "Content-Type": "application/json",
        }

    def _build_body(
        self, messages: str | list[BaseMessage] | list[dict[str, Any]]
    ) -> dict[str, Any]:
        """请求体：``messages`` 数组 + ``stream:"true"``（字符串，按 curl 原样）。"""
        return {
            "messages": self._messages_to_openai_format(messages),
            "stream": "true",
        }

    def _messages_to_openai_format(
        self, messages: str | list[BaseMessage] | list[dict[str, Any]]
    ) -> list[dict[str, str]]:
        """把 openjiuwen messages 翻译成 ICBC ``messages`` 数组。

        - str → ``[{"role":"user","content":str}]``
        - list[BaseMessage] → ``[{"role":m.role,"content":<str>}]``
        - list[dict] → 透传 ``role`` / ``content``（content 归一为 str）
        - 空 messages / 全空 content → ValueError（不向端点发空 prompt）

        ``BaseMessage.content`` 是 ``Union[str, List]``，ICBC 纯文本场景归一为 str：
        list 时拼接其中的 str 元素，丢弃 dict（多模态）部分。
        """
        if isinstance(messages, str):
            if not messages:
                raise ValueError("ICBC invoke 收到空 messages，不向端点发空 prompt")
            return [{"role": "user", "content": messages}]
        if not isinstance(messages, list):  # pragma: no cover — 类型约束兜底
            raise TypeError(f"ICBC invoke 不支持的 messages 类型: {type(messages)!r}")
        if not messages:
            raise ValueError("ICBC invoke 收到空 messages，不向端点发空 prompt")
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
            raise ValueError("ICBC invoke 收到空 messages，不向端点发空 prompt")
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
