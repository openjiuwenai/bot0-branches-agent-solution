"""
MultiversatileInterruptRail：拦截 call_multiversatile 工具调用。

拦截流程：
  1. 解析 workflows 数组（query_intent / query）
  2. 字段映射：query_intent → intent, query → task_description
  3. 传递 workflows 数组（含 intent），不做映射（映射由 sidecar 模块负责）
  4. 写入 pending_multi_delegate → interrupt()
  5. agent_stream() 流末检测 pending_multi_delegate → yield MultiDelegateRequest
  6. sidecar 模块映射 intent→workflow_id+URL → Executor 并行调度工作流执行

Cascade 续轮处理（与 VersatileInterruptRail 对齐）：
  - input_key 数据注入：从 ToolDataChannel 读取前序工作流结果
  - 归一化脚本执行：对每个 workflow 结果分别执行 query_response_analysis_scripts
  - result_key 缓存：从脚本输出提取 result_key → 存入 ToolDataChannel
  - result_message 路由：区分"给模型的"和"给缓存的"
  - response_template_keys 话术处理
  - notice_context / ui_notice 话术提示

设计原则：
  - 零 A2A 依赖：不引用 EventQueue、A2AClient 等任何 A2A 对象
  - 不做 intent→workflow_id 映射：映射由 sidecar 模块负责
  - 不主动调用外部工作流：只记录委托意图，由 Executor 执行
"""
from __future__ import annotations

import ast
import json
from pathlib import Path
from typing import Any, Optional

from loguru import logger
from openjiuwen.core.session.stream import OutputSchema

from openjiuwen.core.single_agent.interrupt.response import InterruptRequest
from openjiuwen.harness.rails.interrupt.interrupt_base import BaseInterruptRail

from opentelemetry.trace import SpanKind

from common.logger import Extra, Level, Tag, to_logger
from ..agent_rule import ScriptsConfig
from ..config import get_settings
from ..otel_span_helper import get_tracer
from .tool_data_channel import ToolDataChannel
from ._command_security import validate_script_command

_SANDBOX_TIMEOUT = 60


def _get_skills_dir() -> Path:
    settings = get_settings()
    sandbox_url = (settings.sandbox_url or "").strip()
    if sandbox_url:
        target = (settings.skill_target_path or "/tmp").strip() or "/tmp"
        return Path(target) / "skills"
    return Path(__file__).resolve().parent.parent / "skills"


class MultiversatileInterruptRail(BaseInterruptRail):
    """
    拦截 call_multiversatile 工具调用的 Rail。

    将 workflows 数组（含 intent）写入 pending_multi_delegate，
    由 agent_stream() 流末检测并 yield MultiDelegateRequest。
    intent 到 workflow_id + URL 的映射由 sidecar 模块负责。

    字段映射（与 VersatileInterruptRail 保持一致）：
      - query_intent → intent
      - query → task_description
      - 自动生成 workflow_id

    Cascade 续轮处理（与 VersatileInterruptRail 对齐）：
      - input_key 数据注入
      - 归一化脚本执行
      - result_key 缓存 + result_message 路由
      - response_template_keys 话术
      - notice_context / ui_notice 话术提示
    """

    def __init__(self, sys_operation_id: Optional[str] = None, scripts_config: Optional[ScriptsConfig] = None) -> None:
        super().__init__(tool_names=["call_multiversatile"])
        self._sys_operation_id = sys_operation_id
        self._scripts_config = scripts_config

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
        # 检查是否为 cascade 续轮（Executor 返回结果）
        cascade_result = ctx.session.get_state("cascade_result")
        if cascade_result is not None:
            logger.info("[MultiversatileInterruptRail] 检测到 cascade 续轮，处理并行工作流结果")
            ctx.session.update_state({"cascade_result": None})
            result = await self._handle_cascade_resume(ctx, cascade_result)
            tool_context = ctx.session.get_state("pending_tool_context") or {}
            self._create_intercepted_tool_span(
                ctx, tool_context.get("tool_name", ""),
                tool_context.get("tool_args", {}), result,
            )
            return result

        tool_args = ctx.inputs.tool_args or {}
        tool_name = tool_call.name if hasattr(tool_call, "name") else None
        tool_args = self._normalize_tool_args(tool_args, tool_name)

        workflows = tool_args.get("workflows", [])
        if not workflows:
            logger.warning("[MultiversatileInterruptRail] workflows 为空，拒绝调用")
            result = self.reject(tool_result={"status": "failed", "message": "workflows 不能为空"})
            self._create_intercepted_tool_span(ctx, tool_name or "", tool_args, result)
            return result

        # 字段映射：将 LLM 传入的字段映射为 WorkflowSpec 所需字段（与 VersatileInterruptRail 保持一致）
        mapped_workflows = self._map_workflow_fields(workflows)

        logger.info(
            f"[MultiversatileInterruptRail] 拦截 call_multiversatile："
            f"workflows_count={len(mapped_workflows)}, "
            f"intents={[w.get('intent') for w in mapped_workflows]}"
        )

        # 写入 session state（已映射后的字段）
        ctx.session.update_state({
            "pending_multi_delegate": mapped_workflows,
            "pending_tool_context": {
                "tool_name": tool_name,
                "tool_args": tool_args,
            },
        })

        self._create_intercepted_tool_span(ctx, tool_name, tool_args)
        return self.interrupt(
            InterruptRequest(message="等待并行工作流执行完成")
        )

    def _map_workflow_fields(self, workflows):
        """将 LLM 传入的字段映射为 WorkflowSpec 所需字段（与 VersatileInterruptRail 保持一致）

        映射规则：
          - query_intent → intent
          - query → task_description
          - 自动生成 workflow_id（格式：wf_001, wf_002, ...）
          - target_agent 透传（如果存在）
        """
        mapped_workflows = []
        for idx, w in enumerate(workflows):
            if not isinstance(w, dict):
                continue

            mapped = {
                "workflow_id": w.get("workflow_id", f"wf_{idx+1:03d}"),
                "intent": w.get("query_intent", w.get("intent", "")),
                "task_description": w.get("query", w.get("task_description", w.get("query_description", ""))),
                "target_agent": w.get("target_agent", ""),
            }
            mapped_workflows.append(mapped)
        return mapped_workflows

    @staticmethod
    def _normalize_tool_args(tool_args, tool_name) -> dict:
        if isinstance(tool_args, dict):
            return tool_args
        if isinstance(tool_args, str):
            try:
                parsed = json.loads(tool_args)
                if isinstance(parsed, dict):
                    return parsed
            except Exception:
                return {}
        return {}

    async def _handle_cascade_resume(self, ctx, cascade_result):
        """处理 cascade 续轮，提取并行工作流执行结果

        与 VersatileInterruptRail 对齐：
        1. input_key 数据注入
        2. 对每个 workflow 结果分别执行归一化脚本
        3. result_key 缓存 + result_message 路由
        4. response_template_keys 话术
        5. notice_context / ui_notice 话术提示
        """
        tool_context = ctx.session.get_state("pending_tool_context") or {}
        ctx.session.update_state({"pending_tool_context": None, "pending_multi_delegate": None})

        tool_args = tool_context.get("tool_args", {})
        original_workflows = tool_args.get("workflows", [])

        # 提取业务数据（并行工作流的结果数组）
        business_data = self._extract_business_data(cascade_result)
        workflows_results = business_data.get("workflows", [])

        # ── 对每个 workflow 结果分别处理 ──────────────────────────────────
        processed_results = []
        channel = ToolDataChannel(ctx.session)
        for idx, wf_result in enumerate(workflows_results):
            # 获取对应的原始 workflow 参数（归一化脚本、话术等）
            wf_args = original_workflows[idx] if idx < len(original_workflows) else {}
            command = wf_args.get("query_response_analysis_scripts", "")
            input_key = wf_args.get("input_key", "")

            # 构造 skill_input
            skill_input = self._build_skill_input(wf_args, wf_result)

            # ── input_key 数据注入（per-workflow）──────────────────────
            if input_key:
                input_data = channel.get(input_key)
                if input_data:
                    skill_input["input_data"] = input_data
                    logger.info(
                        f"[MultiversatileInterruptRail] workflow[{idx}] input_key 数据注入："
                        f"input_key={input_key!r}, "
                        f"data_keys={list(input_data.keys()) if isinstance(input_data, dict) else type(input_data)}"
                    )
                else:
                    logger.warning(
                        f"[MultiversatileInterruptRail] workflow[{idx}] input_key 未命中："
                        f"input_key={input_key!r}, available_keys={channel.get_keys()}"
                    )

            # 注入持久化字段
            skill_input["history_info"] = ctx.session.get_state("history_info") or []

            # 执行归一化脚本
            status, normalized = await self._sandbox_normalize(command, skill_input, wf_result, ctx)

            # ── response_template_keys 话术处理 ──────────────────────
            response_template_keys_str = wf_args.get("response_template_keys", "")
            has_ui_notice = isinstance(normalized, dict) and isinstance(normalized.get("ui_notice"), dict)
            has_template_config = response_template_keys_str and status and self._scripts_config
            if has_template_config and not has_ui_notice:
                self._apply_response_template(ctx, response_template_keys_str, status)

            # ── ui_notice 话术提示 ──────────────────────────────────
            if isinstance(normalized, dict) and "ui_notice" in normalized:
                ui_notice = normalized.pop("ui_notice", None)
                if isinstance(ui_notice, dict) and self._scripts_config is not None:
                    await self._handle_ui_notice(ctx, ui_notice, "call_multiversatile")

            # ── history_info 持久化 ──────────────────────────────────
            if isinstance(normalized, dict) and "history_info" in normalized:
                ctx.session.update_state({"history_info": normalized["history_info"]})

            # ── result_key + result_message 路由决策 ──────────────────
            result_key = ""
            result_message = ""
            if isinstance(normalized, dict):
                result_key = normalized.pop("result_key", None) or ""
                result_message = normalized.pop("result_message", None) or ""
                normalized.pop("history_info", None)

            # result_key 缓存到 ToolDataChannel
            if result_key:
                self._route_to_channel(ctx, result_key, normalized)
                logger.info(
                    f"[MultiversatileInterruptRail] workflow[{idx}] result_key={result_key!r} 已缓存"
                )

            processed_results.append({
                "workflow_index": idx,
                "intent": wf_args.get("query_intent", ""),
                "status": status or "success",
                "result_key": result_key,
                "result_message": result_message,
                "data": result_message or normalized,
            })

        # ── 汇总返回 ──────────────────────────────────────────────────
        cached_keys = [r["result_key"] for r in processed_results if r["result_key"]]
        combined_message = "; ".join(str(r["result_message"]) for r in processed_results if r["result_message"])
        logger.info(
            f"[MultiversatileInterruptRail] cascade 续轮处理完成："
            f"workflows_count={len(processed_results)}, "
            f"cached_keys={cached_keys}, "
            f"has_messages={bool(combined_message)}"
        )

        return self.reject(tool_result={
            "status": "success",
            "data": {
                "workflows": processed_results,
            },
            "message": combined_message,
            "hint": "部分业务数据已存储到后台通道" if cached_keys else None,
        })

    @staticmethod
    def _build_skill_input(wf_args: dict, business_data) -> dict:
        """构造传给沙箱脚本的 SKILL_INPUT JSON（与 VersatileInterruptRail._build_skill_input 对齐）"""
        base = {
            "query_intent": wf_args.get("query_intent", ""),
            "query_description": wf_args.get(
                "query", wf_args.get("query_description", wf_args.get("task_description", ""))
            ),
            "business_data": business_data,
        }

        notice_context_str = wf_args.get("notice_context", "")
        if notice_context_str:
            base["notice_context"] = notice_context_str

        return base

    def _apply_response_template(self, ctx, response_template_keys_str: str, status: str) -> None:
        """处理 response_template_keys 话术（与 VersatileInterruptRail 对齐）"""
        try:
            response_template_keys = (
                json.loads(response_template_keys_str)
                if isinstance(response_template_keys_str, str)
                else response_template_keys_str
            )
            if isinstance(response_template_keys, list):
                key_index = 0 if status == "success" else 1
                if key_index < len(response_template_keys):
                    template_key = response_template_keys[key_index]
                    template_text = self._scripts_config.get_response_template(template_key)
                    if template_text:
                        ctx.session.update_state({"response_template": template_text})
                    else:
                        logger.warning(
                            "[MultiversatileInterruptRail] response_template 处理异常：key=%r, status=%r",
                            template_key, status,
                        )
        except ValueError:
            logger.warning(
                "[MultiversatileInterruptRail] response_template 处理异常：keys=%r",
                response_template_keys_str[:120],
            )

    async def _handle_ui_notice(self, ctx, ui_notice: dict, tool_name: str) -> None:
        """处理 ui_notice 话术提示（与 VersatileInterruptRail 对齐）"""
        notice_event = str(ui_notice.get("event", "")).strip()
        notice_key = str(ui_notice.get("key", "")).strip()
        notice_text = self._scripts_config.get_response_template(notice_key) if notice_key else ""

        if notice_event and notice_text:
            if notice_event == "interrupt_start":
                ctx.session.update_state({"response_template": notice_text})
                logger.info(
                    f"[MultiversatileInterruptRail] ui_notice 中断话术：event={notice_event!r}, key={notice_key!r}"
                )
            else:
                try:
                    await ctx.session.write_stream(OutputSchema(
                        type=notice_event,
                        index=0,
                        payload={
                            "content": notice_text,
                            "plugin": tool_name,
                            "data": {},
                        },
                    ))
                    logger.info(
                        f"[MultiversatileInterruptRail] ui_notice 直发：event={notice_event!r}, key={notice_key!r}"
                    )
                except Exception as exc:
                    logger.warning(
                        f"[MultiversatileInterruptRail] ui_notice write_stream 失败：{exc!r}"
                    )
        else:
            logger.warning(
                f"[MultiversatileInterruptRail] ui_notice 丢弃："
                f"event={notice_event!r}, key={notice_key!r}, has_text={bool(notice_text)}"
            )

    async def _sandbox_normalize(self, command: str, skill_input: dict, fallback, ctx=None):
        """执行沙箱归一化脚本（与 VersatileInterruptRail._sandbox_normalize 对齐）"""
        if not command:
            return None, fallback

        # 安全校验：拒绝含 shell 元字符或不符合白名单格式的 command，防止命令注入
        if not validate_script_command(command, tag="query_response_analysis_scripts"):
            logger.error(
                "[MultiversatileInterruptRail] query_response_analysis_scripts "
                "未通过安全校验，拒绝执行沙箱命令：%r",
                command,
            )
            return None, fallback

        if not self._sys_operation_id:
            logger.warning(
                "[MultiversatileInterruptRail] 未配置 sys_operation_id，跳过沙箱归一化：command={!r}",
                command,
            )
            return None, fallback

        from openjiuwen.core.runner import Runner

        sys_op = Runner.resource_mgr.get_sys_operation(self._sys_operation_id)
        if sys_op is None:
            logger.warning(
                "[MultiversatileInterruptRail] 未找到 SysOperationCard，跳过沙箱归一化：sys_operation_id={!r}",
                self._sys_operation_id,
            )
            return None, fallback

        skills_dir = _get_skills_dir()

        exec_result = await sys_op.shell().execute_cmd(
            command=f'cd "{skills_dir}" && {command}',
            timeout=_SANDBOX_TIMEOUT,
            environment={"SKILL_INPUT": json.dumps(skill_input, ensure_ascii=False)},
        )

        stdout = getattr(getattr(exec_result, "data", None), "stdout", "") or ""
        stderr = getattr(getattr(exec_result, "data", None), "stderr", "") or ""
        exit_code = getattr(getattr(exec_result, "data", None), "exit_code", None)

        try:
            parsed = json.loads(stdout.strip())
            if isinstance(parsed, list) and len(parsed) == 2 and isinstance(parsed[1], dict):
                return str(parsed[0]), parsed[1]
            if isinstance(parsed, dict):
                return None, parsed
            return None, fallback
        except (json.JSONDecodeError, AttributeError) as e:
            logger.error(
                "[MultiversatileInterruptRail] 沙箱脚本输出解析失败：{}，exit_code={}, stdout={!r}, stderr={!r}",
                e, exit_code, stdout[:500], stderr[:500],
            )
            return None, fallback

    @staticmethod
    def _extract_business_data(cascade_result) -> dict:
        """从 cascade_result 中提取业务数据"""
        if not isinstance(cascade_result, dict):
            return {}

        # 并行工作流结果通常放在 workflows 或 workflow_result 字段
        workflows_result = cascade_result.get("workflows")
        if workflows_result is not None:
            return {"workflows": workflows_result}

        workflow_result = cascade_result.get("workflow_result")
        if workflow_result is not None:
            if isinstance(workflow_result, dict):
                return {"workflows": [workflow_result]}
            elif isinstance(workflow_result, list):
                return {"workflows": workflow_result}

        # 如果都没有，返回原始数据（过滤掉内部字段）
        return {
            key: value
            for key, value in cascade_result.items()
            if key not in ("node_type", "node_name")
        }

    @staticmethod
    def _route_to_channel(ctx, result_key: str, data: dict) -> None:
        """将数据路由到 tool_data_channel（与 VersatileInterruptRail._route_to_channel 对齐）"""
        channel = ToolDataChannel(ctx.session)
        channel.store(result_key, data)
