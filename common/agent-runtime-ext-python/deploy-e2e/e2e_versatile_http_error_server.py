# coding: utf-8

"""部署级探针：远端返回 4xx 时，**对外帧不得带远端地址**。

  - ``/mock-versatile-4xx/{topology}/{conversation_id}``  被代理方：一个必定回 422
    的 mock Versatile 平台。路径里嵌了一段可识别的「内网拓扑」字样、查询串里嵌了
    工作区标识——两者都是真实部署中会出现在 URL 里、又不该让调用方看见的东西。
  - ``/v1/proj/agents/{agent}/conversations/{conversation_id}``  我方自定义 REST 入口，
    背后是 ``VersatileAgentHandler``，其 url-template 指向上面那个必定失败的端点。

**为什么必须是部署级而不是单测**：这条缺陷（2026-08-24 的 Bug-002）就是在 wire 上
被抓到的——单测能证明处理器产的错误块不带 URL，证明不了那个块经投影、经 SSE 编码之后
到达调用方时也不带。本仓已有先例：容器 + 真 socket + 真协议库的往返 E2E 一次抓出三处
单测全绿的 wire 缺陷。

链路：POST 自定义 REST → 我方 runtime → VersatileAgentHandler → 真 socket HTTP POST
→ mock 平台回 422 → httpx 抛 HTTPStatusError（原生文案含完整 URL）→ 我方包装
→ 错误块 → 对外 SSE 帧。断言落在最后一环的字节上。
"""
from __future__ import annotations

import os

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from agent_runtime.adapters.outbound.versatile import (
    VersatileAgentHandler,
    VersatileConfig,
)
from agent_runtime.bootstrap.rest_app import create_rest_app

_PORT = int(os.environ.get("PORT", "8090"))

#: 这三段是「不该出现在对外帧里」的东西，断言逐个查。
#: 取值刻意做成一眼可辨的字面量——泄漏时报错信息里能直接看出漏的是哪一段。
TOPOLOGY_SEGMENT = "internal-topology-9x7"
WORKSPACE_QUERY = "workspace_id=42"
REMOTE_STATUS = 422

_MOCK_URL_TEMPLATE = (
    f"http://127.0.0.1:{_PORT}/mock-versatile-4xx/{TOPOLOGY_SEGMENT}/"
    "{conversation_id}?" + WORKSPACE_QUERY
)

_config = VersatileConfig(url_template=_MOCK_URL_TEMPLATE, timeout_s=30.0, verify_tls=True)

app: FastAPI = create_rest_app(
    VersatileAgentHandler(_config, agent_id="versatile-http-error-e2e")
)


@app.post("/mock-versatile-4xx/{topology}/{conversation_id}")
async def _mock_4xx(topology: str, conversation_id: str):
    """必定回 422 的被代理方。

    **回 422 而不是 500**：httpx 对 4xx 与 5xx 的原生文案分别是
    ``Client error '...' for url '...'`` 与 ``Server error '...' for url '...'``，
    两句都带 URL。选 4xx 这一族，是因为它更常见于「远端拒绝了这次请求」
    ——真实报告里抓到的两次正是 422 与 404。
    """
    return JSONResponse(status_code=REMOTE_STATUS, content={"detail": "远端拒绝了这次请求"})


@app.get("/health")
async def _health():
    return {
        "status": "ok",
        "mode": "versatile-http-error",
        "remote_status": REMOTE_STATUS,
        "topology_segment": TOPOLOGY_SEGMENT,
    }
