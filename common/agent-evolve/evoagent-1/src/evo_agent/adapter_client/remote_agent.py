"""RemoteAgent — 继承 BaseAgent 的最小实现。

封装 AdapterClient，为 Trainer 提供标准 Agent 接口。
rollout 通过 AdapterClient 完成，不在本地执行 LLM。

设计决策：D1, D2, D3, D4, D8, D18
"""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from typing import Any

from openjiuwen.core.single_agent.base import BaseAgent

from evo_agent.adapter_client.client import AdapterClient


class RemoteAgent(BaseAgent):  # type: ignore[misc]
    """远程业务 Agent 的本地代理。

    Parameters
    ----------
    card:
        AgentCard（agent 元信息，BaseAgent 要求）。
    adapter_client:
        AdapterClient 实例，所有远程通信通过它完成。
    operators:
        SkillDocumentOperator 字典，由 build_skill_document_operator() 创建。
        Trainer 通过 get_operators() 获取用于参数更新。
    max_turns:
        多轮追问的最大轮数（兜底），默认 10。
    """

    def __init__(
        self,
        card: Any,
        adapter_client: AdapterClient,
        operators: dict[str, Any],
        *,
        max_turns: int = 10,
    ) -> None:
        super().__init__(card=card)
        self._adapter_client = adapter_client
        self._operators = operators
        self._max_turns = max_turns

    @property
    def adapter_client(self) -> AdapterClient:
        """暴露 AdapterClient，供场景 optimizer._rollout() 调用 get_traces()。"""
        return self._adapter_client

    async def invoke(
        self,
        inputs: dict[str, Any],
        session: Any | None = None,
    ) -> dict[str, Any]:
        """执行一个 case 的完整多轮对话，返回最终响应。

        兼容 Trainer/SkillDocumentOptimizer 的标准调用格式：

            ``{**case.inputs, "conversation_id": case.case_id}``

        其中 ``case.inputs`` 至少包含 ``query`` 字段。

        也支持 EvoAgent 自定义格式（含 ``queries`` 列表、``case_id``
        和 ``run_id``），用于多轮追问场景。

        Parameters
        ----------
        inputs:
            Trainer 标准格式：
            - ``query``: str — 用户问题（来自 case.inputs）
            - ``conversation_id``: str — 对话 ID（= case.case_id，框架注入）
            - 其他 key 作为 extra_data 透传

            EvoAgent 自定义格式（额外支持）：
            - ``queries``: list[str] — 多轮 query 列表
            - ``case_id``: str — 对话 ID（conversation_id 的别名）
            - ``run_id``: str — 运行 ID（缺失时自动生成）
            - ``extra_data``: dict — 显式透传数据
        session:
            传透不记录 tracer（D4）。

        Returns
        -------
        dict
            最后一轮的完整响应体（含 answer, interrupted, events 等）。
        """
        # conversation_id 是 Trainer 标准 key；case_id 为兼容别名
        case_id = inputs.get("conversation_id") or inputs.get("case_id", "")

        # query 解析：优先 queries 列表，回退到单个 query
        raw_queries = inputs.get("queries") or [inputs.get("query", "")]
        queries = [q for q in raw_queries if q]
        if not queries:
            raise ValueError("inputs must contain a non-empty 'queries' list or 'query' string")

        extra_data = inputs.get("extra_data", {})
        # run_id 缺失时自动生成（Trainer 格式不含 run_id）
        run_id = inputs.get("run_id") or str(uuid.uuid4())

        response: dict[str, Any] = {}
        for i, query in enumerate(queries):
            if i >= self._max_turns:
                break

            response = await self._adapter_client.invoke(
                case_id=case_id,
                query=query,
                extra_data=extra_data,
                run_id=run_id,
            )

        return response

    def get_operators(self) -> dict[str, Any]:
        """返回 SkillDocumentOperator 字典。

        Trainer 通过此方法获取 operators 用于参数更新。
        """
        return self._operators

    async def stream(
        self,
        inputs: Any,
        session: Any | None = None,
        stream_modes: Any | None = None,
    ) -> AsyncIterator[Any]:
        """最小 stream 实现 — 暂不用于训练。返回空 async iterator。"""
        return
        yield  # make this an async generator  # pragma: no cover

    def configure(self, config: Any) -> BaseAgent:
        """空实现 — RemoteAgent 不需要本地配置。"""
        return self
