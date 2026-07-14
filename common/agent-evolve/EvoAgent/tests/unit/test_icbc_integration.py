"""ICBC provider 端到端集成单测（T5） — mock SSE 端点，走完整链路。

链路：EvolveConfig → _build_model_client_config → create_model_client →
ICBCModelClient → mock SSE 流 → invoke → AssistantMessage。

覆盖：
- ICBC 完整链路 mock SSE 跑通 → AssistantMessage(content="hello")
- OpenAI 路径同样跑通（默认 config → OpenAI client，mock 不触网）
- ICBC 流内 error → ICBCRequestError
"""

from __future__ import annotations

import json

import pytest
from openjiuwen.core.foundation.llm.model_clients import create_model_client
from openjiuwen.core.foundation.llm.model_clients.openai_model_client import OpenAIModelClient
from openjiuwen.core.foundation.llm.schema.config import ModelRequestConfig
from openjiuwen.core.foundation.llm.schema.message import UserMessage

import evo_agent  # noqa: F401 — 触发 registry 注册 llm_ICBC
from evo_agent.config import EvolveConfig
from evo_agent.llm.icbc_model_client import ICBCModelClient, ICBCRequestError
from evo_agent.optimizer_runner import _build_model_client_config


def _sse(*chunks: str, done: bool = True) -> bytes:
    """拼 SSE 字节流（``data:{...}\\n\\n``，末尾可选 ``data:[DONE]``）。"""
    body = b"".join(f"data:{c}\n\n".encode() for c in chunks)
    if done:
        body += b"data:[DONE]\n\n"
    return body


def _chunk(content: str = "") -> str:
    return json.dumps(
        {"choices": [{"index": 0, "delta": {"content": content}}]},
        ensure_ascii=False,
    )


def _icbc_config() -> EvolveConfig:
    return EvolveConfig(
        _env_file=None,
        llm_provider="ICBC",
        icbc_token="icbc-jwt",
        icbc_user_id="icbc-user",
        icbc_endpoint="http://mock-icbc/mlpmodelservice/aigc/chat/completions",
        optimizer_model="icbc-deepseek",
    )


def _openai_config() -> EvolveConfig:
    return EvolveConfig(
        _env_file=None,
        llm_provider="OpenAI",
        llm_api_key="sk-test",
        llm_base_url="http://mock-openai/v1",
        optimizer_model="gpt-4o",
    )


class TestICBCIntegration:
    @pytest.mark.asyncio
    async def test_icbc_full_chain_invoke(self, httpx_mock) -> None:
        """ICBC: config → helper → create_model_client → invoke → AssistantMessage。"""
        endpoint = "http://mock-icbc/mlpmodelservice/aigc/chat/completions"
        httpx_mock.add_response(
            url=endpoint,
            content=_sse(_chunk("hello")),
        )
        config = _icbc_config()

        client = create_model_client(
            _build_model_client_config(config),
            ModelRequestConfig(model_name=config.optimizer_model),
        )
        assert isinstance(client, ICBCModelClient)
        msg = await client.invoke([UserMessage(content="hi")])
        assert msg.content == "hello"

    @pytest.mark.asyncio
    async def test_icbc_full_chain_chunk_error_raises(self, httpx_mock) -> None:
        """ICBC 完整链路：流内 error chunk → ICBCRequestError。"""
        endpoint = "http://mock-icbc/mlpmodelservice/aigc/chat/completions"
        httpx_mock.add_response(
            url=endpoint,
            content=_sse(json.dumps({"error": "internal error"})),
        )
        config = _icbc_config()

        client = create_model_client(
            _build_model_client_config(config),
            ModelRequestConfig(model_name=config.optimizer_model),
        )
        with pytest.raises(ICBCRequestError):
            await client.invoke([UserMessage(content="hi")])

    @pytest.mark.asyncio
    async def test_openai_path_constructs_openai_client(self, httpx_mock) -> None:
        """OpenAI 默认路径：config → helper → create_model_client → OpenAIModelClient。"""
        # mock openai chat completions 端点（openai SDK 底层走 httpx）
        httpx_mock.add_response(
            url="http://mock-openai/v1/chat/completions",
            json={
                "id": "chatcmpl-mock",
                "object": "chat.completion",
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": "openai-ok"},
                        "finish_reason": "stop",
                    }
                ],
            },
        )
        config = _openai_config()

        client = create_model_client(
            _build_model_client_config(config),
            ModelRequestConfig(model_name=config.optimizer_model),
        )
        assert isinstance(client, OpenAIModelClient)
        msg = await client.invoke([UserMessage(content="hi")])
        assert msg.content == "openai-ok"

    def test_helper_and_create_model_client_provider_consistent(self) -> None:
        """helper 产出的 config 喂给 create_model_client，provider 类型一致。"""

        icbc = create_model_client(
            _build_model_client_config(_icbc_config()),
            ModelRequestConfig(model_name="icbc-deepseek"),
        )
        assert isinstance(icbc, ICBCModelClient)

        openai = create_model_client(
            _build_model_client_config(_openai_config()),
            ModelRequestConfig(model_name="gpt-4o"),
        )
        assert isinstance(openai, OpenAIModelClient)
