# coding: utf-8

"""部署级 E2E 入口：本 runtime 托管一个**真实 AgentScope Agent**。

## 它验的是判据层够不着的那一段

`agent_runtime/tests/test_agentscope_adapter.py` 已经用真实框架对象驱动了
映射与处理器，但它跑在进程内：ASGI 传输、不经网络栈。本项目有多次实证，
wire 契约缺陷恰恰只在真 socket 下暴露（终答被完成信号吞掉、首帧即中断时
错误码不对、端侧工具投影丢空串——三例单测全绿）。

本入口把那条链路补齐：真 uvicorn、真端口、真 a2a-sdk，
被托管的是真实的 `agentscope.agent.Agent` 子类。

**两条对外面都挂**：标准 A2A（`/a2a`）与自定义 REST（`/custom/v1`）。

初版只挂 A2A，理由是「A2A 的 Task 状态机与 SSE 帧序才是对外承诺」。
那句话不错，但它掩盖了一件事：**两条面对同一个产出的处置规则是相反的**——
A2A 对每个内容帧（含终答帧）都出 artifact，自定义 REST 对终答帧抑制不出帧。
规则相反就不存在「验一条推另一条」，而本件在 REST 面上真出过线级缺陷
（事件名空串让整帧在存量出口被丢弃），出缺陷时 A2A 面全绿。

## 模型层仍是确定性桩

权威 `CL-e1bb88e16dc8` 要的是「包装宿主已构建的本地 Agent，把它的输出映射为
runtime query / stream / 失败语义」——**要验的是托管与映射，不是 LLM 会不会答对**。
真 LLM 那一维由 `run-questioner-llm.sh` 承担。

## 为什么这一条只在本机后端跑

`agentscope` 是**可选的框架适配依赖**，不在 `agent_runtime/requirements.txt` 里，
也就不在 E2E 镜像里。把它塞进镜像会让每条 E2E 都背上一整棵推理栈的依赖
（anthropic、openai、dashscope、mcp、numpy、tree_sitter……），
而其余 16 条脚本一个都用不到它。

代价如实登记：本条**不覆盖**「干净依赖环境」与「Dockerfile 启动命令」两维，
它们仍由其余脚本的容器后端承担。
"""
from __future__ import annotations

import os

from agentscope.agent import Agent
from agentscope.credential import CredentialBase
from agentscope.message import Msg, TextBlock, ThinkingBlock
from agentscope.model import ChatModelBase
from fastapi import FastAPI

from agent_runtime.adapters.outbound.framework.agentscope import (
    AgentScopeAgentHandler,
)
from agent_runtime.bootstrap.a2a_app import create_a2a_app
from agent_runtime.bootstrap.rest_app import create_rest_app


class _DeterministicModel(ChatModelBase):
    """确定性模型桩：不打外部服务，让本条 E2E 的读数只反映托管链路。"""

    def __init__(self) -> None:
        super().__init__(
            credential=CredentialBase(),  # type: ignore[arg-type]  # 框架类型名义不符，结构满足
            model="stub",
            parameters=None,  # type: ignore[arg-type]  # 框架类型名义不符，结构满足
            stream=False,
        )


#: 「多段」问法下产出的块正文。脚本的期望值从这里导出——
#: 部署级断言此前把 artifact 拼成一串判子串，对「发一遍/两遍/零遍」给同一个结果。
_MULTI_BLOCKS = ("第一段：账单共 12 笔。", "第二段：最近一笔 6312.58 元。")


class _BillingAgent(Agent):
    """**真实的 AgentScope Agent**——真基类、真消息类型，回答确定。"""

    async def reply(self, inputs: object = None, **_: object) -> Msg:  # type: ignore[override]  # 框架类型名义不符，结构满足
        text = ""
        if isinstance(inputs, Msg) and inputs.content:
            text = inputs.content[0].text  # type: ignore[union-attr]  # 框架类型名义不符，结构满足
        if "报错" in text:
            # 失败语义那一维（`CL-3e92767207ea`）：框架以异常表达执行失败。
            raise RuntimeError("模型服务不可达")
        if "多段" in text:
            # **多块产出**这一维。此前部署级只产单块，而本件在「多块怎么投影」
            # 上翻过三次车（丢内容 → 发两遍 → 发零遍），三次全在单块面上绿着。
            return Msg(
                name=self.name,
                role="assistant",
                content=[TextBlock(type="text", text=x) for x in _MULTI_BLOCKS],
            )
        if "只想" in text:
            # **零文本产出**这一维：框架只回思考块、没有任何业务文本
            # （`ReActAgent` 一类的常见形态）。权威明禁以空完成收尾，
            # 而这条路径在部署级此前零覆盖。
            return Msg(
                name=self.name,
                role="assistant",
                content=[ThinkingBlock(type="thinking", thinking="我在想")],
            )
        return Msg(
            name=self.name,
            role="assistant",
            content=[TextBlock(type="text", text=f"账单共 12 笔（{text}）")],
        )


_agent = _BillingAgent(  # type: ignore[abstract]  # 框架类型名义不符，结构满足
    name="billing",
    system_prompt="你是账单助手",
    model=_DeterministicModel(),  # type: ignore[abstract]  # 框架类型名义不符，结构满足
)
_handler = AgentScopeAgentHandler(_agent, agent_id="agentscope-e2e")

app: FastAPI = create_a2a_app(
    _handler,
    # **卡片上不写框架名**：脚本要断言「框架符号不出现在对外报文里」，
    # 而卡片的名字与描述是宿主自己配的装配参数——把框架名写进去，
    # 那条断言就分不清「泄漏」与「宿主自己配的」。
    # 被托管的是哪个框架由健康端点报（运维观测面，不在对外契约面上）。
    name="billing-agent",
    description="账单助手",
    # **配上技能位**：权威 `FEAT-001` 要求希望被其他 Agent 发现并作为工具调用的
    # Agent 必须能声明 skills。托管异构框架不该改变发现面——脚本据此断言卡片完整。
    skills=[{"id": "billing", "name": "账单查询", "description": "查询账单笔数"}],
)

# **同一个处理器再挂一条自定义 REST 面**。
#
# 原先只挂 A2A，理由是「要验的是标准面」。那条理由本身不错，但它把另一件事
# 一起挡在了外面：**两条对外面对同一个产出的处置规则是相反的**——
# A2A 对每个内容帧（含终答帧）都出 artifact，自定义 REST 对终答帧抑制不出帧
# （逐字节对齐存量 `format_event` 的 `completed → return None`）。
#
# 规则相反，就不存在「验了一条即可推另一条」。而本件在 REST 面上真出过线级缺陷
# （事件名空串让整帧在存量出口被丢弃），当时 A2A 面全绿。
_rest_app: FastAPI = create_rest_app(_handler)
app.mount("/custom", _rest_app)


@app.get("/health")
async def _health() -> dict[str, str]:
    return {
        "status": "ok",
        "runtime": "agent_runtime(onion)",
        "framework": "agentscope",
        "framework_version": _agentscope_version(),
        "port": os.environ.get("PORT", ""),
    }


def _agentscope_version() -> str:
    """把框架版本报出来。

    **不是装饰**：本条脚本断言的形态（`Agent.reply` 返回 `Msg`、
    内容是块序列）绑在 2.0 这一代的 API 上。版本变了而断言还绿，
    读的人要能一眼看出他测的是哪一版。
    """
    import agentscope

    return getattr(agentscope, "__version__", "unknown")
