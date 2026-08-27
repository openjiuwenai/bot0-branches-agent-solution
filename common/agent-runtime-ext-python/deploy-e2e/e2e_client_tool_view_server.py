# coding: utf-8
# 参考宿主与部署级 E2E 装置：SPI 实现方法必须是实例方法；
# 按场景直接构造 runtime 内部状态是这一层的职责，不是越界访问。
# pylint: disable=protected-access


"""部署级 E2E 变体：**端侧工具面承接**（`CL-653e3ecd9129`／`:135`／`:80`）挂标准 A2A 面。

## 为什么单开一个变体

既有的 `e2e_client_tool_server` 打的是自定义 REST 面上的**投影与续接**（组件调
`session.interact` 挂起、客户端提交结果续接）。那条链路不经过工具面承接——
`clientTools` 在自定义 REST 入口**显式不承接**（详设 §2.2）。
本能力只在标准协议入口可达，故必须另挂一个 A2A 变体才打得到。

## 确定性从哪来

要让模型「自己选中」一个端侧工具需要真 LLM，那会把协议断言变成随模型波动的偶发红。
本变体的做法是：**轨、回调分发、工具卡片、投影与归一全是真的**，只有「模型这一轮
决定调哪个工具」这一步由 handler 确定性地驱动——它直接触发框架的两个回调事件，
把真实结果写进对外答案。

于是对外报文里的工具名、次序、投影内容**都是真往返产出的**，不是脚本里拼出来的字面量。

## 覆盖面

| 面 | 怎么打 |
|---|---|
| 工具面到达与次序 | 带 `metadata.clientTools` 调用，答案里回真实合并后的工具名序列 |
| 调用被投影而非服务端执行 | 驱动工具调用回调，答案里回真实的投影报文与短路后的工具结果 |
| 能力管理器零污染 | 答案里回该实例能力表的内容——`CL-156d64d312ce` 要的就是它始终为空 |
| 形态不合法即拒 | 由脚本直接发畸形报文，断言 JSON-RPC 参数错误码 |
"""
from __future__ import annotations

import json
import os

from fastapi import FastAPI

from agent_runtime.adapters.outbound.agentcore.client_tool import bind_client_tool_view
from agent_runtime.bootstrap.a2a_app import create_a2a_app
from agent_runtime.domain.client_tool_view import parse_client_tool_view
from agent_runtime.domain.result import QueryChunk

_AGENT_NAME = os.getenv("E2E_CLIENT_TOOL_VIEW_AGENT_NAME", "edp-client-tool-view-e2e")

#: 部署方装的服务端工具。次序断言要的正是「它在前、客户端声明追加在后」。
_SERVER_TOOL = "serverSearch"


def _agent():
    from openjiuwen.core.foundation.tool import ToolCard
    from openjiuwen.core.single_agent.agents.react_agent import ReActAgent
    from openjiuwen.core.single_agent.schema.agent_card import AgentCard

    agent = ReActAgent(AgentCard(id="e2e-ct-view", name="A", description="d"))
    # **服务端工具只作为本次模型调用的入参给出**，不装进能力管理器——
    # 这样「能力管理器始终为空」这条断言才有意义。
    agent._e2e_server_tool = ToolCard(  # type: ignore[attr-defined]
        id=f"server-{_SERVER_TOOL}", name=_SERVER_TOOL, description="服务端工具",
        input_params={"type": "object"},
    )
    return agent


class _Session:
    """会话替身：客户端在本次 E2E 里的角色——收投影、回结果。"""

    def __init__(self, session_id):
        # **会话标识用方法暴露，与框架一致**：框架侧
        # `openjiuwen/core/session/agent.py` 的 `Session.get_session_id()` 才是取值入口。
        # 替身写成属性会让这一层测的是替身自己——那正是本能力已经踩过一次的坑。
        self._session_id = session_id
        self.projected = []

    def get_session_id(self):
        return self._session_id

    async def interact(self, projection):
        self.projected.append(projection)
        return {"status": "success", "value": "账单：本月消费 1280.00 元"}


class _ToolViewHandler:
    """确定性 handler：把真实的承接结果作为答案返回。

    **不模拟任何一环**：轨是真的、回调分发是框架的、工具卡片由产品代码构造、
    投影与归一走领域值对象。确定性只体现在「这一轮驱动哪个回调」。
    """

    agent_id = "e2e-client-tool-view"
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    async def start() -> None:
        return None

    @staticmethod
    async def stop() -> None:
        return None

    @staticmethod
    async def clear_session(conversation_id: str) -> None:
        return None

    @staticmethod
    async def query(request):
        return None

    async def stream_query(self, request):
        from openjiuwen.core.single_agent.rail.base import (
            AgentCallbackEvent,
            ModelCallInputs,
            ToolCallInputs,
        )

        agent = _agent()
        conversation_id = request.conversation_id
        view = parse_client_tool_view(request)
        binding = await bind_client_tool_view(agent, view, conversation_id)
        session = _Session(conversation_id)
        report = {}
        try:
            # 面一：本次模型调用看得见哪些工具（服务端那个由入参给出）
            class _ModelCtx:
                def __init__(self):
                    self.inputs = ModelCallInputs(tools=[agent._e2e_server_tool])
                    self.session = session

            model_ctx = _ModelCtx()
            await agent.agent_callback_manager.execute(
                AgentCallbackEvent.BEFORE_MODEL_CALL, model_ctx
            )
            report["tool_face"] = [
                getattr(t, "name", t) for t in (model_ctx.inputs.tools or [])
            ]

            # 面二：模型选中端侧工具时，调用被拦下并投影给客户端
            first_client_tool = next(iter(view.names()), "")
            if first_client_tool:
                class _ToolCall:
                    id = "call-e2e-1"
                    name = first_client_tool

                class _ToolCtx:
                    def __init__(self):
                        self.inputs = ToolCallInputs(
                            tool_call=_ToolCall(), tool_name=first_client_tool,
                            tool_args={"selector": "#bill"},
                        )
                        self.session = session

                tool_ctx = _ToolCtx()
                await agent.agent_callback_manager.execute(
                    AgentCallbackEvent.BEFORE_TOOL_CALL, tool_ctx
                )
                report["projected"] = session.projected
                report["tool_result"] = tool_ctx.inputs.tool_result

            # 面三：能力管理器全程未被碰过（`CL-156d64d312ce`）
            report["ability_manager"] = [a.name for a in agent.ability_manager.list()]
        finally:
            await binding.close()
            # 注销之后再驱动一次，证明轨确实摘掉了（请求级隔离）

            class _AfterCtx:
                def __init__(self):
                    self.inputs = ModelCallInputs(tools=[agent._e2e_server_tool])
                    self.session = session

            after_ctx = _AfterCtx()
            await agent.agent_callback_manager.execute(
                AgentCallbackEvent.BEFORE_MODEL_CALL, after_ctx
            )
            report["tool_face_after_close"] = [
                getattr(t, "name", t) for t in (after_ctx.inputs.tools or [])
            ]

        yield QueryChunk.of_final_answer(json.dumps(report, ensure_ascii=False, sort_keys=True))


app: FastAPI = create_a2a_app(_ToolViewHandler(), name=_AGENT_NAME)


@app.get("/health")
async def _health():
    """就绪探针。**`create_a2a_app` 不自带它**——标准协议面只挂协议路由，
    健康探针属部署关切，由挂载方提供（既有的 A2A E2E 变体同法）。"""
    return {"status": "ok"}
