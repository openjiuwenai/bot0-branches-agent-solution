"""EvoAgent LLM provider 扩展 — 通过 registry 注册非内置 provider。

import 本包即触发 ``ICBCModelClient`` 注册（``BaseModelClient.__init_subclass__``
在类定义时自动调 ``register_class``）。此处额外做一次幂等显式注册作为防御，
保证 ``client_provider="ICBC"`` 在 ``ModelClientConfig`` 校验时 registry 已就绪。
"""

from __future__ import annotations

from openjiuwen.core.common.clients import get_client_registry

from evo_agent.llm.icbc_model_client import ICBCModelClient

__all__ = ["ICBCModelClient"]

_REGISTRY_KEY = "llm_ICBC"

# __init_subclass__ 已在类定义时注册；此处幂等兜底（重复 import 只跳过）。
if _REGISTRY_KEY not in get_client_registry().list_clients():
    get_client_registry().register_class(ICBCModelClient)
