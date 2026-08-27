# coding: utf-8

"""容器级部署 E2E 入口（FEAT-002 Versatile 远端服务代理，确定性无 LLM）。

同一容器内一个 app 同时承载两方：

  - ``/mock-versatile/{conversation_id}``  被代理方：一个 mock 的 Versatile 平台，
    按**真实 wire 形态**回帧——HTTP POST 收 JSON、响应按行分帧、每行一个完整 JSON、
    带 ``data:`` 前缀，中间夹杂空行与 SSE 字段行（``event:`` / ``id:``）。
  - ``/v1/...``（由 create_rest_app 挂载）  主调方：我的 runtime 装
    ``VersatileAgentHandler``，其 url-template 指向上面那个 mock 端点。

于是一次 HTTP 调用会走完整条链路：

    调用方 → 我的 runtime REST 入口 → ServeOrchestrator → VersatileAgentHandler
          → 真 socket 的 HTTP POST → mock Versatile 平台 → 行帧流
          → 帧翻译 → QueryChunk → 对外信封

**为什么值得起一个 mock 而不是直接单测**：单测里的替身按「我以为的形态」喂数据，
而这里的帧真的经过 uvicorn 的分块传输、httpx 的 ``aiter_lines`` 切分、TCP 的任意
分包——曾有三处 wire 缺陷正是单测全绿而容器往返才暴露的。

uvicorn 启于 8090，与其余四个变体一致。
"""
from __future__ import annotations

import json
import os

from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse

from agent_runtime.adapters.outbound.versatile import (
    ResultExtraction,
    VersatileAgentHandler,
    VersatileConfig,
)
from agent_runtime.bootstrap.rest_app import create_rest_app

# mock 平台监听在自身，容器内经回环地址访问——真 socket、真 HTTP，不是进程内直调。
# 自指端口从环境读：容器里服务固定监听 8090，本机进程后端则用别的端口，
# 写死 8090 会让被代理方连回一个没人监听的端口。
_PORT = int(os.environ.get("PORT", "8090"))
_MOCK_URL_TEMPLATE = f"http://127.0.0.1:{_PORT}/mock-versatile/{{conversation_id}}"

# 断言用的确定性答案。取一个带中文与小数的串，能同时验编码与原样透传。
EXPECTED_ANSWER = "您的账户余额为 6312.58 元"

_config = VersatileConfig(
    url_template=_MOCK_URL_TEMPLATE,
    timeout_s=30.0,
    result_extraction=ResultExtraction(node_name="answer", node_type="QA"),
    # mock 端是明文 HTTP，证书校验在此无从生效；生产默认仍是开启。
    verify_tls=True,
)

app: FastAPI = create_rest_app(VersatileAgentHandler(_config, agent_id="versatile-e2e"))


@app.post("/mock-versatile/{conversation_id}")
async def _mock_versatile(conversation_id: str, request: Request):
    """mock 的 Versatile 平台：回一段真实形态的行帧流。

    刻意混入四类干扰行，逐条对应一条 wire 规则：
      1. SSE 字段行 ``event: message`` / ``id: 1``  —— 必须被丢弃，不得当数据帧
      2. 空行                                       —— 不作事件边界，不聚合前后帧
      3. 裸 JSON 行（无 ``data:`` 前缀）            —— 必须照常解析
      4. 结尾的 ``node_type: End``                  —— 业务终态，完成由流结束表达
    """
    # 本探针只验帧解析，不看请求体——读它只会留下一个没人用的变量。
    async def _frames():
        yield b"event: message\n"          # SSE 字段行 —— 该被丢弃
        yield b"id: 1\n"                    # 同上
        yield b"\n"                         # 空行 —— 不作边界
        yield ('data: ' + json.dumps(
            {"node_name": "think", "node_type": "Think", "data": "正在查询"},
            ensure_ascii=False,
        ) + "\n").encode("utf-8")
        yield b"\n"
        # 裸 JSON 行（无 data: 前缀），验宽容分帧
        yield (json.dumps(
            {"node_name": "answer", "node_type": "QA", "data": EXPECTED_ANSWER},
            ensure_ascii=False,
        ) + "\n").encode("utf-8")
        # 业务终态
        yield b'data: {"node_type":"End"}\n'

    # 媒体类型声明为 event-stream，但内容是行分帧 JSON —— 这正是四方共同的真实形态。
    return StreamingResponse(_frames(), media_type="text/event-stream")


@app.get("/health")
async def _health():
    return {
        "status": "ok",
        "runtime": "agent_runtime(onion)",
        "adapter": "versatile",
        "upstream": _MOCK_URL_TEMPLATE,
    }
