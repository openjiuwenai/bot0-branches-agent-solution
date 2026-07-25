"""
主 Agent 并行调用子 Agent 工具。

当主 Agent 识别到用户请求涉及多个业务实体时，通过此工具并行调度子 Agent。
Rail 拦截后：
  MultiagentInterruptRail 拦截 → 查 sub_agents.yaml 根据 entity_type 映射子 Agent URL
  → 写入 pending_dispatch（每个 entity 包含 sub_agent_url）→ interrupt()
  → agent_stream() 流末检测 pending_dispatch → yield SubAgentDispatchRequest
  → Executor 并行调度子 Agent 执行
"""
from __future__ import annotations

from typing import Any, Dict, Optional

from loguru import logger
from openjiuwen.core.foundation.tool import LocalFunction, ToolCard


async def call_multiagent(
    entities: Optional[list[dict]] = None,
    session: Optional[Any] = None,
) -> Dict[str, Any]:
    """并行调用多个子 Agent。传入实体列表，系统自动并行执行各实体的分析流程。

    Args:
        entities: 多个子 Agent 的调用信息列表，每项包含：
            - entity_id: 业务实体唯一标识
            - entity_name: 中文实体名称
            - entity_type: 实体类型（用于映射子Agent，如 "ZDT"）
            - query: 针对该实体的子任务描述
    """
    if entities is None:
        entities = []
    logger.info(f"[call_multiagent] entities_count={len(entities)}")
    for i, e in enumerate(entities):
        logger.info(
            f"[call_multiagent] entity[{i}]: "
            f"id={e.get('entity_id')!r}, name={e.get('entity_name')!r}, "
            f"type={e.get('entity_type')!r}, query={e.get('query')!r:.80}"
        )
    return {}


call_multiagent_tool = LocalFunction(
    card=ToolCard(
        id="call_multiagent",
        name="call_multiagent",
        description=(
            "并行调用多个子 Agent。当用户请求涉及多个业务实体（如多家企业、多个账户）时，"
            "使用此工具将各实体的分析任务并行分发给子 Agent 执行。"
            "传入实体列表（含 entity_type 用于映射子Agent），系统自动并行执行各实体的分析流程。"
        ),
        input_params={
            "type": "object",
            "properties": {
                "entities": {
                    "type": "array",
                    "description": "多个子 Agent 的调用信息列表",
                    "items": {
                        "type": "object",
                        "properties": {
                            "entity_id": {
                                "type": "string",
                                "description": "业务实体唯一标识（格式：entity_001、entity_002...）",
                            },
                            "entity_name": {
                                "type": "string",
                                "description": "中文实体名称（如企业名称），用于前端展示",
                            },
                            "entity_type": {
                                "type": "string",
                                "description": "实体类型，用于映射子Agent（如 ZDT、RISK）",
                            },
                            "query": {
                                "type": "string",
                                "description": "针对该实体的子任务描述",
                            },
                        },
                        "required": ["entity_id", "entity_name", "entity_type", "query"],
                    },
                },
            },
            "required": ["entities"],
        },
    ),
    func=call_multiagent,
)
