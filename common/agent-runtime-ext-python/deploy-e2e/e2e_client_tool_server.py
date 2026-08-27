# coding: utf-8

"""容器级部署 E2E 入口（FEAT-009 端侧工具 wire 契约，确定性无 LLM）。

在 Docker 里跑我的 agent_runtime（create_rest_app + WorkflowAgentHandler），驱动一个真实
agent-core workflow：其组件投影端侧工具请求 → 挂起 → 经 REST 续接收 client outcome → 恢复。经 HTTP
两请求端到端验：
  请求1（流式）→ 组件 session.interact(投影) 挂起 → interrupt 帧携带 {client_tool,args,call_id}
                （client 据此知道要执行哪个工具）
  请求2（同会话）→ client 把工具执行结果作为 outcome 提交 → REST 续接 → from_raw 归一 → 完成

端侧工具在真实部署由 ReAct/DeepAgent 作工具调用（get_current_session().interact）；此处用 workflow
组件 node session 直接投影以确定性驱动**同一 wire 契约**（投影上线 + outcome 回灌 + 归一），不依赖
LLM tool-choice。ReAct 绑定 + LLM 调用属 agent-core。
"""
from __future__ import annotations

from fastapi import FastAPI
from openjiuwen.core.runner import Runner
from openjiuwen.core.workflow import End, Start, Workflow, WorkflowCard, WorkflowComponent

from agent_runtime.adapters.outbound.agentcore.handler import WorkflowAgentHandler
from agent_runtime.bootstrap.rest_app import create_rest_app
from agent_runtime.domain.client_tool import ClientToolOutcome, ClientToolRequest

WF = "edp_client_tool_e2e"


class _Start(Start):
    def __init__(self, node_id):
        super().__init__()

    async def invoke(self, inputs, session, context):
        return inputs


class _ClientToolNode(WorkflowComponent):
    """投影端侧工具请求（node session.interact）：挂起 → 收 client outcome → 归一为工具结果。"""

    def __init__(self, node_id):
        super().__init__()

    async def invoke(self, inputs, session, context):
        request = ClientToolRequest(tool_name="read_file", args={"path": "/bill.txt"}, call_id="c1")
        raw = await session.interact(request.to_projection())  # 首次挂起；resume 返回 client outcome
        return {"tool_result": ClientToolOutcome.from_raw(raw).as_tool_result()}


class _End(End):
    def __init__(self, node_id):
        super().__init__()

    async def invoke(self, inputs, session, context):
        return inputs


def _register():
    card = WorkflowCard(id=WF, name=WF, version="1")
    flow = Workflow(card=card)
    flow.set_start_comp("start", _Start("start"), inputs_schema={"query": "${query}"})
    flow.add_workflow_comp("tool", _ClientToolNode("tool"), inputs_schema={"q": "${start.query}"})
    flow.set_end_comp("end", _End("end"), inputs_schema={"answer": "${tool.tool_result}"})
    flow.add_connection("start", "tool")
    flow.add_connection("tool", "end")
    Runner.resource_mgr.add_workflow(flow.card, lambda: flow)


_handler = WorkflowAgentHandler(WF, Runner)


async def _init() -> None:
    """初始化钩子。**不能用 on_event**：组合根已挂 ASGI lifespan，二者互斥，
    用 on_event 注册会静默不执行。"""
    _register()


app: FastAPI = create_rest_app(_handler, init_hooks=(_init,))


@app.get("/health")
async def _health():
    return {"status": "ok", "mode": "client-tool", "workflow": WF}
