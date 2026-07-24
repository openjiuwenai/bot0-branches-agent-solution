"""
子 Agent 并行调用多工作流工具。

当子 Agent 识别到需要调用多个 VersatileAdapter 工作流时，通过此工具并行调度。
Rail 拦截后：
  MultiversatileInterruptRail 拦截 → 传递 workflows 数组（含 query + query_intent）
  → 写入 pending_multi_delegate → interrupt()
  → agent_stream() 流末检测 pending_multi_delegate → yield MultiDelegateRequest
  → sidecar 模块映射 query_intent→workflow_id+URL → Executor 并行调度工作流执行

参数设计（与 call_versatile 对齐）：
  顶层参数（所有 workflow 共享）：
    - input_key → 跨工作流数据引用，从后台通道读取前序工作流输出
  per-workflow 参数（每个 workflow 独立）：
    - query → 自然语言任务描述
    - query_intent → 业务意图标识
    - query_response_analysis_scripts → 归一化脚本路径
    - response_template_keys → 操作结果话术 key
    - notice_context → 提示话术上下文
"""
from __future__ import annotations

from typing import Any, Dict, Optional

from loguru import logger
from openjiuwen.core.foundation.tool import LocalFunction, ToolCard


async def call_multiversatile(
    workflows: Optional[list[dict]] = None,
    session: Optional[Any] = None,
) -> Dict[str, Any]:
    """并行调用多个 VersatileAdapter 工作流。传入工作流列表，系统自动并行执行每个工作流。

    Args:
        workflows: 多个工作流的调用信息列表，每项包含：
            - query: 自然语言任务描述，传给工作流引擎
            - query_intent: 业务意图标识，用于工作流路由
            - input_key: 跨工作流数据引用，从前序工作流存储的业务数据 key（与 result_key 配对）
            - query_response_analysis_scripts: 归一化脚本路径（留空则透传原始数据）
            - response_template_keys: 话术 key 的 JSON 数组字符串
            - notice_context: 提示话术上下文的 JSON 对象字符串
    """
    if workflows is None:
        workflows = []
    logger.info(
        f"[call_multiversatile] workflows_count={len(workflows)}"
    )
    for i, w in enumerate(workflows):
        logger.info(
            f"[call_multiversatile] workflow[{i}]: "
            f"intent={w.get('query_intent')!r}, query={w.get('query')!r:.80}, "
            f"input_key={w.get('input_key')!r}, "
            f"script={w.get('query_response_analysis_scripts')!r:.60}, "
            f"response_template_keys={w.get('response_template_keys')!r:.60}, "
            f"notice_context={w.get('notice_context')!r:.60}"
        )
    return {}


call_multiversatile_tool = LocalFunction(
    card=ToolCard(
        id="call_multiversatile",
        name="call_multiversatile",
        description=(
            "并行调用多个 VersatileAdapter 工作流。当需要同时执行多个不同意图的工作流时，"
            "使用此工具将各工作流任务并行分发。"
            "每个工作流使用 query + query_intent 机制，与 call_versatile 保持一致。"
            "query_intent 到 workflow_id + URL 的映射由 sidecar 模块负责。"
        ),
        input_params={
            "type": "object",
            "properties": {
                "workflows": {
                    "type": "array",
                    "description": "多个工作流的调用信息列表，每个工作流使用 query + query_intent 机制",
                    "items": {
                        "type": "object",
                        "properties": {
                            "query": {
                                "type": "string",
                                "description": "自然语言任务描述，传给工作流引擎",
                            },
                            "query_intent": {
                                "type": "string",
                                "description": "业务意图标识，用于工作流路由（如'信贷综合金融'、'信贷综合分析'、'尽调要点'）",
                            },
                            "input_key": {
                                "type": "string",
                                "description": (
                                    "【跨工作流数据引用】前序工作流存储的业务数据 key。"
                                    "用于从后台通道读取前序工作流的输出数据，注入到当前工作流的 SKILL_INPUT['input_data']。"
                                    "⚠️ 必须与前序脚本输出的 result_key 严格匹配（大小写敏感）。"
                                    "示例：前序脚本输出 result_key='baseInfo'，则 input_key='baseInfo'。"
                                    "未匹配时仅 warning，脚本应做好 input_data 为空的降级处理。"
                                ),
                            },
                            "query_response_analysis_scripts": {
                                "type": "string",
                                "description": (
                                    "归一化脚本运行命令（工作目录为 skills/），用于对工作流返回数据做结构化处理。"
                                    "留空则透传原始工作流返回数据。"
                                ),
                            },
                            "response_template_keys": {
                                "type": "string",
                                "description": (
                                    "操作结果话术 key 的 JSON 数组字符串，格式：'[成功话术key, 失败话术key]'。"
                                    "key 对应 AgentRule.md 中 scripts 配置。留空则不输出话术。"
                                ),
                            },
                            "notice_context": {
                                "type": "string",
                                "description": (
                                    "提示话术上下文的 JSON 对象字符串，仅用于未中断场景下的 "
                                    "tool_end / todo_end 话术提示。留空则不触发话术。"
                                ),
                            },
                        },
                        "required": ["query", "query_intent"],
                    },
                },
            },
            "required": ["workflows"],
        },
    ),
    func=call_multiversatile,
)
