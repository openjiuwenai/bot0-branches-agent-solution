# coding: utf-8

"""容器级部署 E2E 入口：自定义 REST 同步信封的状态位（Feat-Func-022b §4.7，方案 B1）。

装配 `MobileBankChannel(sync_state_fields=True)` 的参考通道，宿主 Agent 按 query 文本产三种终局：
含「中断」→ `input_required` 事件；含「失败」→ `failed` 事件；其余 → `final_answer_chunk` 回显 query。
期望值由请求推出，不依赖模型。默认通道（两键）由 `run-parity.sh` 的双侧逐字节比对覆盖，本入口只验开关开的一侧。
"""
from __future__ import annotations

from fastapi import FastAPI

from agent_runtime.adapters.inbound.rest.mobile_bank import MobileBankChannel
from agent_runtime.adapters.outbound.hostagent.dict_event_handler import DictEventStreamHandler
from agent_runtime.bootstrap.rest_app import create_rest_app

AGENT_ID = "edp_sync_state_e2e"


async def _events(query: str, conversation_id: str, metadata):  # noqa: ARG001
    yield {"type": "thought", "data": {"content": "想一想"}}
    if "中断" in query:
        yield {"type": "input_required", "data": {"content": "请输入验证码"}}
        return
    if "失败" in query:
        yield {"type": "failed", "data": {"content": "下游不可用"}}
        return
    yield {"type": "final_answer_chunk", "data": {"content": f"回显:{query}"}}


_handler = DictEventStreamHandler(agent_id=AGENT_ID, stream_factory=_events)
app: FastAPI = create_rest_app(_handler, channel=MobileBankChannel(sync_state_fields=True))


@app.get("/health")
async def _health():
    return {"status": "ok", "mode": "rest-sync-state", "agent": AGENT_ID}
