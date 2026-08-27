# coding: utf-8

"""容器级部署 E2E 入口（questioner + 真实 LLM 中断续接）。

在 Docker 里跑我的 agent_runtime，接真实 agent-core questioner workflow + 真实 LLM，经 HTTP
端到端验 FEAT-008：请求1 → questioner 就缺失字段中断 → interrupt_start 帧；请求2（同会话）→
REST 续接路由 → 续跑 → 完成答案。

LLM 由环境变量配置（OpenAI 兼容）：LLM_BASE/LLM_MODEL/LLM_API_KEY（+ 采样 LLM_TEMPERATURE/
LLM_TOP_P）。端点由验收方自备，**无默认值、不硬编码**——
容器 -e 注入。
"""
from __future__ import annotations

import os

from fastapi import FastAPI
from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.runner import Runner
from openjiuwen.core.workflow import (
    End,
    FieldInfo,
    QuestionerComponent,
    QuestionerConfig,
    Start,
    Workflow,
    WorkflowCard,
)

from agent_runtime.adapters.outbound.agentcore.handler import WorkflowAgentHandler
from agent_runtime.bootstrap.rest_app import create_rest_app

WORKFLOW_ID = "edp_questioner_e2e"
_LLM_BASE = os.environ.get("LLM_BASE", "")
_LLM_KEY = os.environ.get("LLM_API_KEY", "")
_LLM_MODEL = os.environ.get("LLM_MODEL", "")
_LLM_TEMP = float(os.environ.get("LLM_TEMPERATURE", "0.2"))
_LLM_TOP_P = float(os.environ.get("LLM_TOP_P", "0.9"))


def _register_questioner():
    card = WorkflowCard(id=WORKFLOW_ID, name=WORKFLOW_ID, version="1")
    flow = Workflow(card=card)
    q_config = QuestionerConfig(
        # 采样由 env 配（gemma 用 0.2/0.9；Kimi K3 需固定 1/0.95）。
        model_config=ModelRequestConfig(model=_LLM_MODEL, temperature=_LLM_TEMP, top_p=_LLM_TOP_P, max_tokens=2048),
        model_client_config=ModelClientConfig(
            client_provider="OpenAI", api_key=_LLM_KEY, api_base=_LLM_BASE, timeout=60, verify_ssl=False
        ),
        question_content="",
        extract_fields_from_response=True,
        field_names=[FieldInfo(field_name="account_id", description="银行账号", required=True)],
        with_chat_history=False,
    )
    flow.set_start_comp("s", Start(), inputs_schema={"query": "${query}"})
    flow.add_workflow_comp(
        "questioner", QuestionerComponent(questioner_comp_config=q_config),
        inputs_schema={"query": "${s.query}"},
    )
    flow.set_end_comp(
        "e", End({"responseTemplate": "账号 {{account_id}} 余额查询已受理"}),
        inputs_schema={"account_id": "${questioner.account_id}"},
    )
    flow.add_connection("s", "questioner")
    flow.add_connection("questioner", "e")
    Runner.resource_mgr.add_workflow(flow.card, lambda: flow)


_handler = WorkflowAgentHandler(WORKFLOW_ID, Runner)


async def _init() -> None:
    """初始化钩子。**不能用 on_event**：组合根已挂 ASGI lifespan，二者互斥，
    用 on_event 注册会静默不执行。"""
    _register_questioner()


app: FastAPI = create_rest_app(_handler, init_hooks=(_init,))


@app.get("/health")
async def _health():
    return {"status": "ok", "mode": "questioner-llm", "model": _LLM_MODEL, "has_key": bool(_LLM_KEY)}
