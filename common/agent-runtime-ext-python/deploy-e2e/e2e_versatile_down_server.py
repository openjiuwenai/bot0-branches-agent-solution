# coding: utf-8

"""容器级部署 E2E 入口（FEAT-008 远端不可达 → 失败终态，确定性无 LLM）。

与 ``e2e_versatile_server`` 同一装配，只改一处：``url_template`` 指向一个
**没有任何进程监听的端口**。于是链路在传输层就断：

    调用方 → 我的 runtime REST 入口 → ServeOrchestrator → VersatileAgentHandler
          → 真 socket 的 HTTP POST → 连接被拒 → 传输层异常
          → 错误块 + 异常传播 → 对外信封

**为什么要起一个部署级探针而不是单测**：单测里把替身配成抛异常，验的是
「异常抛出后我怎么处理」；这里验的是**连接真的被拒之后，对外那一层写出了什么**。
失败被包装成 completed 的形态只在对外信封上可见，单测看不到它。

被代理端口取 1（TCP 端口 1 属保留端口，常规部署不会有进程监听），
连回环地址上的它必得 ``ConnectionRefused``——不依赖外网、不依赖超时等待。
"""
from __future__ import annotations

import os

from fastapi import FastAPI

from agent_runtime.adapters.outbound.versatile import (
    ResultExtraction,
    VersatileAgentHandler,
    VersatileConfig,
)
from agent_runtime.bootstrap.rest_app import create_rest_app

_DEAD_URL_TEMPLATE = "http://127.0.0.1:1/mock-versatile/{conversation_id}"

_config = VersatileConfig(
    url_template=_DEAD_URL_TEMPLATE,
    timeout_s=5.0,
    result_extraction=ResultExtraction(node_name="answer", node_type="QA"),
    verify_tls=True,
)

# 智能体标识从环境读：部署级 E2E 用默认值，权威用例库的脚本按它自己的路径取值。
_AGENT_ID = os.environ.get("E2E_AGENT_ID", "versatile-down-e2e")

app: FastAPI = create_rest_app(VersatileAgentHandler(_config, agent_id=_AGENT_ID))


@app.get("/health")
async def _health():
    """就绪探针——与其余 E2E 变体同一约定。"""
    return {"status": "ok"}
