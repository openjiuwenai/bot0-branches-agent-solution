# coding: utf-8

"""容器级部署 E2E 入口：确定性 Workflow 的自定义 REST 流式出口（社区 issue #151）。

## 它要暴露的缺陷

确定性 Workflow **没有中间事件**——不调模型、不产 llm_output——唯一的输出就是
端节点的终答。本版把 Workflow 终答映射成 `of_final_answer`，而自定义 REST 的
投影层把该标记当成「完成信号」一律不出帧，于是整条 SSE 流是 0 字节：
HTTP 200、Content-Type 正确、一个 `data:` 帧都没有。

ReAct 形态看不出来，因为 llm_output 流式增量垫着；这里刻意不接模型，
让终答成为唯一输出，缺陷就无处可藏。

## 为什么不接模型

不是省事。接了模型就有 llm_output 帧，断言「至少一帧」会被它买通，
测的就不再是终答那一帧。**零中间事件是本场景的判据前提**，不是简化。

## 工作流形态

Start → 直通节点 → End，端节点原样返回输入的 query。期望值因此可从请求推出：
终答帧的正文必须含本次的 query 文本——不依赖任何模型输出。
节点定义与 `agent_runtime/tests/test_bootstrap_real_workflow_e2e.py` 同构。
"""
from __future__ import annotations

from fastapi import FastAPI
from openjiuwen.core.runner import Runner
from openjiuwen.core.workflow import End, Start, Workflow, WorkflowCard, WorkflowComponent

from agent_runtime.adapters.outbound.agentcore.handler import WorkflowAgentHandler
from agent_runtime.bootstrap.rest_app import create_rest_app

WORKFLOW_ID = "edp_workflow_stream_e2e"


class _StartNode(Start):
    async def invoke(self, inputs, session, context):
        return inputs


class _PassNode(WorkflowComponent):
    async def invoke(self, inputs, session, context):
        return inputs


class _EndNode(End):
    async def invoke(self, inputs, session, context):
        return inputs


def _register_workflow() -> None:
    # 工作流构造内部用 asyncio.Future()，必须在运行中的 event loop 里调——
    # 故由 async 的 init 钩子调用，不在模块导入期。
    card = WorkflowCard(id=WORKFLOW_ID, name=WORKFLOW_ID, version="1")
    flow = Workflow(card=card)
    flow.set_start_comp("start", _StartNode(), inputs_schema={"query": "${query}"})
    flow.add_workflow_comp("pass", _PassNode(), inputs_schema={"output": "${start.query}"})
    flow.set_end_comp("end", _EndNode(), inputs_schema={"result": "${pass.output}"})
    flow.add_connection("start", "pass")
    flow.add_connection("pass", "end")
    Runner.resource_mgr.add_workflow(flow.card, lambda: flow)


_handler = WorkflowAgentHandler(WORKFLOW_ID, Runner)


async def _init() -> None:
    """初始化钩子。**不能用 on_event**：组合根已挂 ASGI lifespan，二者互斥，
    用 on_event 注册会静默不执行。
    """
    _register_workflow()


app: FastAPI = create_rest_app(_handler, init_hooks=(_init,))


@app.get("/health")
async def _health():
    return {"status": "ok", "mode": "workflow-stream", "workflow": WORKFLOW_ID}
