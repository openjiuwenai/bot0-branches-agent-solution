"""CustomSSE registry import-time 注册时序单测（T3）。

验证：
- import evo_agent 后 registry 含 ``llm_CustomSSE``
- ``create_model_client`` 对 ``client_provider="CustomSSE"`` 返回 CustomSSEModelClient 实例
- 重复 import 不抛（register_class 幂等）
- ``ModelClientConfig(client_provider="CustomSSE", ...)`` 的 validate_client_provider 通过
"""

from __future__ import annotations

import importlib

import pytest
from openjiuwen.core.common.clients import get_client_registry
from openjiuwen.core.foundation.llm.model_clients import create_model_client
from openjiuwen.core.foundation.llm.schema.config import (
    ModelClientConfig,
    ModelRequestConfig,
)


class TestCustomSSERegistry:
    def test_registry_contains_llm_custom_sse_after_import(self) -> None:
        """import evo_agent 后 registry 含 llm_CustomSSE。"""
        import evo_agent  # noqa: F401

        assert "llm_CustomSSE" in get_client_registry().list_clients()

    def test_create_model_client_returns_custom_sse_instance(self) -> None:
        """create_model_client 对 CustomSSE provider 返回 CustomSSEModelClient 实例。"""
        import evo_agent  # noqa: F401
        from evo_agent.llm.custom_sse_model_client import CustomSSEModelClient

        client = create_model_client(
            ModelClientConfig(
                client_provider="CustomSSE",
                api_key="t",
                api_base="https://llm-gateway.example.com/v1/chat/completions",
                user_id="u",
                verify_ssl=False,
            ),
            ModelRequestConfig(model_name="example-model"),
        )
        assert isinstance(client, CustomSSEModelClient)
        # 凭证映射：token→api_key, endpoint→api_base, userId→extra user_id
        assert client.model_client_config.api_key == "t"
        assert client.model_client_config.api_base == "https://llm-gateway.example.com/v1/chat/completions"
        assert getattr(client.model_client_config, "user_id") == "u"

    def test_repeated_import_does_not_raise(self) -> None:
        """重复 import evo_agent 不抛（register_class 幂等）。"""
        import evo_agent  # noqa: F401

        # 重新 import 模块不应抛
        importlib.reload(importlib.import_module("evo_agent.llm"))
        assert "llm_CustomSSE" in get_client_registry().list_clients()

    def test_model_client_config_custom_sse_provider_validates(self) -> None:
        """ModelClientConfig(client_provider="CustomSSE", ...) validate_client_provider 通过。"""
        import evo_agent  # noqa: F401 — 注册先于校验

        cfg = ModelClientConfig(
            client_provider="CustomSSE",
            api_key="t",
            api_base="https://llm-gateway.example.com/v1/chat/completions",
            verify_ssl=False,
        )
        # validate_client_provider 在 __init__ 后已执行；非内置 provider 通过即说明 registry 命中
        assert cfg.client_provider == "CustomSSE"

    def test_custom_sse_provider_not_registered_raises_before_import(self) -> None:
        """未注册 CustomSSE 时 ModelClientConfig 校验拒绝（验证 registry 时序必要性）。

        通过临时反注册 CustomSSE，验证 validate_client_provider 会拒绝未知 provider。
        """
        from openjiuwen.core.common.exception.errors import ValidationError

        import evo_agent  # noqa: F401

        # 反注册 CustomSSE
        get_client_registry().unregister("CustomSSE", "llm")
        assert "llm_CustomSSE" not in get_client_registry().list_clients()
        try:
            with pytest.raises(ValidationError):
                ModelClientConfig(
                    client_provider="CustomSSE",
                    api_key="t",
                    api_base="https://llm-gateway.example.com/v1/chat/completions",
                    verify_ssl=False,
                )
        finally:
            # 恢复注册（重新 import 模块触发 __init_subclass__ 已注册过，但反注册后需重注）
            # 重新 import custom_sse_model_client 模块不会重定义类；显式重注：
            from evo_agent.llm.custom_sse_model_client import CustomSSEModelClient

            if "llm_CustomSSE" not in get_client_registry().list_clients():
                get_client_registry().register_class(CustomSSEModelClient)
            assert "llm_CustomSSE" in get_client_registry().list_clients()

    @pytest.mark.asyncio
    async def test_end_to_end_via_create_model_client(self, httpx_mock) -> None:
        """create_model_client 产出的实例可 invoke（mock SSE 端点）。"""
        import json

        import evo_agent  # noqa: F401

        endpoint = "https://llm-gateway.example.com/v1/chat/completions"
        chunk = json.dumps({"choices": [{"index": 0, "delta": {"content": "e2e-answer"}}]})
        httpx_mock.add_response(
            url=endpoint,
            content=b"data:" + chunk.encode() + b"\n\ndata:[DONE]\n\n",
        )
        client = create_model_client(
            ModelClientConfig(
                client_provider="CustomSSE",
                api_key="e2e-token",
                api_base=endpoint,
                user_id="e2e-user",
                verify_ssl=False,
            ),
            ModelRequestConfig(model_name="example-model"),
        )
        from openjiuwen.core.foundation.llm.schema.message import UserMessage

        msg = await client.invoke([UserMessage(content="hi")])
        assert msg.content == "e2e-answer"
