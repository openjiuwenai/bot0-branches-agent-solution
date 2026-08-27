"""
MultiagentInterruptRail：拦截 call_multiagent 工具调用。

拦截流程：
  1. 解析 entities 数组（entity_id / entity_name / entity_type / query）
  2. 查 sub_agents.yaml 根据 entity_type 映射子 Agent URL
  3. 为每个 entity 填充 sub_agent_url
  4. 写入 pending_dispatch → interrupt()
  5. agent_stream() 流末检测 pending_dispatch → yield SubAgentDispatchRequest
  6. Executor 并行调度子 Agent 执行

设计原则：
  - 零 A2A 依赖：不引用 EventQueue、A2AClient 等任何 A2A 对象
  - entity_type → sub_agent_url 映射在本 Rail 完成（查 sub_agents.yaml）
  - 不主动调用外部 Agent：只记录委托意图，由 Executor 执行
"""
from __future__ import annotations

import json
from typing import Any, Optional

from loguru import logger
from openjiuwen.core.single_agent.interrupt.response import InterruptRequest
from openjiuwen.harness.rails.interrupt.interrupt_base import BaseInterruptRail

from opentelemetry.trace import SpanKind

from common.logger import Extra, Level, Tag, to_logger
from ..config import load_sub_agents_config
from ..otel_span_helper import get_tracer


class MultiagentInterruptRail(BaseInterruptRail):
    """
    拦截 call_multiagent 工具调用的 Rail。

    查 sub_agents.yaml 根据 entity_type 映射子 Agent URL，
    为每个 entity 填充 sub_agent_url，
    由 agent_stream() 流末检测并 yield SubAgentDispatchRequest。
    """

    def __init__(self) -> None:
        super().__init__(tool_names=["call_multiagent"])
        # 初始化时加载子 Agent 路由配置（启动时校验，提前发现配置问题）
        self._sub_agents_config = load_sub_agents_config()
        # 构建 entity_type → (url, name) 映射表
        self._type_to_agent = {
            entry.entity_type: (entry.url, entry.name)
            for entry in self._sub_agents_config.sub_agents
        }

    @staticmethod
    def _create_intercepted_tool_span(ctx, tool_name: str, tool_args: dict, result: Any = None) -> None:
        """创建被拦截 tool 的 OTel span（SDK 不会触发 on_plugin_start，需手动创建）。"""
        tracer = get_tracer()
        if not tracer:
            return
        conv_id = ctx.session.get_session_id()
        session_id = getattr(ctx.session, "otel_session_id", None) or conv_id
        metric_data = {
            "gen_ai.tool.name": tool_name,
            "openjiuwen.agent.inputs": json.dumps(tool_args, ensure_ascii=False),
            "openjiuwen.tool.status_message": 0,
        }
        if result is not None:
            _tr = getattr(result, "tool_result", None)
            output_data = _tr if _tr is not None else result
            metric_data["openjiuwen.agent.outputs"] = json.dumps(output_data, ensure_ascii=False, default=str)
        with tracer.start_as_current_span(
            f"tool.{tool_name}", kind=SpanKind.INTERNAL
        ) as span:
            for key, value in metric_data.items():
                span.set_attribute(key, value)
            span.set_attribute("session.id", session_id)
            to_logger(
                level=Level.INFO,
                message=metric_data,
                extra=Extra(tag=Tag.TAG_TOOL_EXECUTE_END),
            )

    async def resolve_interrupt(
        self,
        ctx,
        tool_call,
        user_input,
        auto_confirm_config=None,
    ):
        # 优先处理 cascade 续轮（子 Agent 返回结果）
        cascade_result = ctx.session.get_state("cascade_result")
        if cascade_result is not None:
            logger.info("[MultiagentInterruptRail] 检测到 cascade 续轮，处理子 Agent 并行执行结果")
            ctx.session.update_state({"cascade_result": None})
            result = await self._handle_cascade_resume(ctx, cascade_result)
            tool_context = ctx.session.get_state("pending_tool_context") or {}
            self._create_intercepted_tool_span(
                ctx, tool_context.get("tool_name", ""),
                tool_context.get("tool_args", {}), result,
            )
            return result

        # 检查是否已派发过，防止重复调用
        if ctx.session.get_state().get("multiagent_dispatched"):
            logger.info("[MultiagentInterruptRail] 重复调用 call_multiagent，拒绝并令其汇总")
            result = self.reject(tool_result={
                "status": "already_dispatched",
                "message": "子 Agent 已派发并返回结果，请直接基于已有结果汇总输出，禁止再次调用 call_multiagent",
            })
            tool_args = ctx.inputs.tool_args or {}
            tool_name = tool_call.name if hasattr(tool_call, "name") else None
            self._create_intercepted_tool_span(ctx, tool_name or "", tool_args, result)
            return result

        tool_args = ctx.inputs.tool_args or {}
        tool_name = tool_call.name if hasattr(tool_call, "name") else None
        tool_args = self._normalize_tool_args(tool_args, tool_name)

        entities = tool_args.get("entities", [])
        if not entities:
            logger.warning("[MultiagentInterruptRail] entities 为空，拒绝调用")
            result = self.reject(tool_result={"status": "failed", "message": "entities 不能为空"})
            self._create_intercepted_tool_span(ctx, tool_name or "", tool_args, result)
            return result

        # 为每个 entity 根据 entity_type 映射 sub_agent_url
        enriched_entities = []
        for entity in entities:
            entity_type = entity.get("entity_type", "ABC")
            url, name = self._type_to_agent.get(entity_type, ("", ""))
            
            if not url:
                logger.warning(
                    f"[MultiagentInterruptRail] entity_type={entity_type} 未找到映射的子Agent，"
                    f"使用默认配置"
                )
                # 回退到第一个配置的子Agent
                if self._sub_agents_config.sub_agents:
                    url = self._sub_agents_config.sub_agents[0].url
                    name = self._sub_agents_config.sub_agents[0].name
            
            enriched_entity = {
                **entity,
                "sub_agent_url": url,
            }
            enriched_entities.append(enriched_entity)
            
            logger.info(
                f"[MultiagentInterruptRail] entity={entity.get('entity_id')!r}, "
                f"type={entity_type!r} → sub_agent={name!r}, url={url!r:.60}"
            )

        logger.info(
            f"[MultiagentInterruptRail] 拦截 call_multiagent："
            f"entities_count={len(enriched_entities)}, "
            f"entity_ids={[e.get('entity_id') for e in enriched_entities]}"
        )

        # 写入 session state（每个 entity 包含自己的 sub_agent_url）
        ctx.session.update_state({
            "pending_dispatch": enriched_entities,
            "pending_tool_context": {
                "tool_name": tool_name,
                "tool_args": tool_args,
            },
            "multiagent_dispatched": True,  # 标记已派发，防止重复调用
        })

        self._create_intercepted_tool_span(ctx, tool_name, tool_args)
        return self.interrupt(
            InterruptRequest(message="等待子 Agent 并行执行完成")
        )

    async def _handle_cascade_resume(self, ctx, cascade_result):
        """处理 cascade 续轮，提取子 Agent 并行执行结果"""
        tool_context = ctx.session.get_state("pending_tool_context") or {}

        # 提取子 Agent 结果（通常放在 sub_agent_results 或 workflow_result 字段）
        business_data = self._extract_business_data(cascade_result)

        # ── 检测全部 cancelled（用户取消操作）──────────────────────────
        sub_results = business_data.get("sub_agent_results", [])
        all_cancelled = (
            bool(sub_results)
            and all(r.get("status") == "cancelled" for r in sub_results if isinstance(r, dict))
        )

        if all_cancelled:
            # 不清除 multiagent_dispatched，禁止重试
            ctx.session.update_state({"pending_tool_context": None, "pending_dispatch": None})
            logger.info(
                f"[MultiagentInterruptRail] cascade 续轮：全部子Agent已取消"
            )
            return self.reject(tool_result={
                "status": "cancelled",
                "message": "用户已取消操作，所有子Agent已停止执行。禁止重新调用call_multiagent，请直接输出取消提示。",
                "data": business_data,
            })

        # 正常路径：清除防重入标记
        ctx.session.update_state({
            "pending_tool_context": None,
            "pending_dispatch": None,
            "multiagent_dispatched": None,
        })

        # 构造 message：有截断时明确告知 LLM
        skipped = business_data.get("skipped_entities", [])
        concurrency_limit = business_data.get("concurrency_limit")
        if skipped:
            skipped_details = []
            for e in skipped:
                name = e.get("entity_name", e.get("entity_id", "未知"))
                reason = e.get("reason", "未知原因")
                skipped_details.append(f"{name}（原因：{reason}）")
            message = (
                f"子 Agent 并行执行完成，以下实体未执行：{', '.join(skipped_details)}。"
                f"请在报告中说明，并提示用户可分批重试或调整请求。"
            )
        else:
            message = "子 Agent 并行执行完成"
        
        logger.info(
            f"[MultiagentInterruptRail] cascade 续轮处理完成："
            f"sub_agent_results_count={len(sub_results)}, "
            f"skipped_count={len(skipped)}"
        )

        return self.reject(tool_result={
            "status": "success" if not skipped else "partial_success",
            "message": message,
            "data": business_data,
        })

    @staticmethod
    def _extract_business_data(cascade_result) -> dict:
        """从 cascade_result 中提取子 Agent 执行结果"""
        if not isinstance(cascade_result, dict):
            return {}

        # 子 Agent 结果通常放在 sub_agent_results 字段
        sub_agent_results = cascade_result.get("sub_agent_results")
        if sub_agent_results is not None:
            data = {"sub_agent_results": sub_agent_results}
            # 透传截断/跳过信息，供汇总轮 LLM 告知用户"X 因并发上限未处理"
            if cascade_result.get("skipped_entities"):
                data["skipped_entities"] = cascade_result["skipped_entities"]
                data["concurrency_limit"] = cascade_result.get("concurrency_limit")
            return data

        # 兼容 workflow_result 字段（通用格式）
        workflow_result = cascade_result.get("workflow_result")
        if workflow_result is not None:
            if isinstance(workflow_result, dict):
                return {"sub_agent_results": [workflow_result]}
            elif isinstance(workflow_result, list):
                return {"sub_agent_results": workflow_result}

        # 如果都没有，返回原始数据（过滤掉内部字段）
        return {
            key: value
            for key, value in cascade_result.items()
            if key not in ("node_type", "node_name")
        }

    @staticmethod
    def _normalize_tool_args(tool_args, tool_name) -> dict:
        if not isinstance(tool_args, (dict, str)):
            return {}
        if isinstance(tool_args, dict):
            return tool_args
        try:
            parsed = json.loads(tool_args)
        except Exception:
            return {}
        return parsed if isinstance(parsed, dict) else {}
