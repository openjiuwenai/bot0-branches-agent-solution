# coding: utf-8

"""容器级部署 E2E 入口：在 Docker 里跑**我的 agent_runtime**，接**真实 agent-core**。

镜像底座对齐 agent-store@EDP-agent 部署（openjiuwen==0.1.16 + a2a-sdk），但服务面是我的
runtime（create_rest_app + WorkflowAgentHandler），驱动一个真实 agent-core workflow。
证明"我的 runtime 塞进真实部署容器、经 HTTP 端到端驱动真实 agent-core"。

默认无 LLM（确定性快速门禁）；后续可叠 questioner+真 LLM 变体。uvicorn 启于 8090。
"""
from __future__ import annotations

from fastapi import FastAPI
from openjiuwen.core.runner import Runner
from openjiuwen.core.workflow import End, Start, Workflow, WorkflowCard, WorkflowComponent

from agent_runtime.adapters.outbound.agentcore.handler import WorkflowAgentHandler
from agent_runtime.bootstrap.rest_app import create_rest_app

WORKFLOW_ID = "edp_e2e_wf"


class _StartNode(Start):
    def __init__(self, node_id):
        super().__init__()

    async def invoke(self, inputs, session, context):
        return inputs


class _PassNode(WorkflowComponent):
    def __init__(self, node_id):
        super().__init__()
        self.node_id = node_id

    async def invoke(self, inputs, session, context):
        return inputs


class _EndNode(End):
    def __init__(self, node_id):
        super().__init__()

    async def invoke(self, inputs, session, context):
        return inputs


def _register_workflow():
    # 在 event loop 内调用（Vertex 用 asyncio.Future）——放 startup 钩子。
    card = WorkflowCard(id=WORKFLOW_ID, name=WORKFLOW_ID, version="1")
    flow = Workflow(card=card)
    flow.set_start_comp("start", _StartNode("start"), inputs_schema={"query": "${query}"})
    flow.add_workflow_comp("node_a", _PassNode("node_a"), inputs_schema={"output": "${start.query}"})
    flow.set_end_comp("end", _EndNode("end"), inputs_schema={"result": "${node_a.output}"})
    flow.add_connection("start", "node_a")
    flow.add_connection("node_a", "end")
    Runner.resource_mgr.add_workflow(flow.card, lambda: flow)


async def _init_workflow() -> None:
    """初始化钩子：在处理器启动之前注册工作流。

    **不能用 on_event("startup")**：组合根已把 runtime 的生命周期挂成 ASGI lifespan，
    而框架的 lifespan 与 on_event 互斥——用 on_event 注册会静默不执行，运行期取到
    空的工作流实例才暴露。组合根为此提供 init_hooks 参数。
    """
    _register_workflow()


# 我的 runtime 服务面：真实 Runner 注入 handler → create_rest_app
_handler = WorkflowAgentHandler(WORKFLOW_ID, Runner)
app: FastAPI = create_rest_app(_handler, init_hooks=(_init_workflow,))


@app.get("/health")
async def _health():
    return {"status": "ok", "runtime": "agent_runtime(onion)", "workflow": WORKFLOW_ID}
