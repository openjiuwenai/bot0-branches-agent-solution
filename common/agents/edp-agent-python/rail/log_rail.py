"""log rail.

Hooks into ``before_model_call`` and ``after_model_call`` to log
LLM input and response.

Also includes a tool_call.arguments JSON auto-repair step in ``after_model_call``
to fix the common LLM streaming quirk where the trailing closing bracket is
missing (e.g. ``{"todos":[{...},{...}]`` without the outer ``}``).
"""

from __future__ import annotations
import json
import uuid
import time
from pathlib import Path
from datetime import datetime
from typing import Any

from loguru import logger
from openjiuwen.core.foundation.tool import Tool
from openjiuwen.core.single_agent.rail.base import (
    AgentCallbackContext,
    AgentRail,
    ModelCallInputs,
)
from openjiuwen.core.single_agent.ability_manager import AbilityManager

from common.logger import Extra, Tag, to_logger, TagObservation, ObservationType, Level


class LogRail(AgentRail):
    """log model calls.

    This rail hooks into the SDK rail framework via
    ``before_model_call`` and ``after_model_call`` and log LLM input / response.

    """
    def __init__(self, model_name: str, tools: list[Tool]) -> None:
        self.model_name = model_name
        self.tools = tools

    priority = 1000  # low priority — run after other rails

    @staticmethod
    def _emit_metric(data: dict, session_id: str = "") -> None:
        """补充 OTel span 属性。

        TAG 日志由现有 LogRail 原有逻辑输出，此处只补充 OTel span attribute。
        data 中的 key 作为 OTel span 的 attribute name。
        """
        try:
            from opentelemetry.trace import get_current_span
            span = get_current_span()
            if span and span.is_recording():
                for key, value in data.items():
                    span.set_attribute(key, value)
                if session_id:
                    span.set_attribute("session.id", session_id)
        except ImportError:
            pass


    def init(self, agent):
        #此处无法记录tool加载的耗时。所以日志放在了agent.py中打印了。
        pass
        # now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
        # agent_init_toollist = TagObservation(
        #     id=str(int(time.time() * 1000)),
        #     type=ObservationType.SPAN,
        #     name="agent初始化",
        #     start_time=now,
        #     end_time=now,
        #     input={"tool_list": list(map(lambda tool: tool.card.tool_info().model_dump(
        #         exclude_none=True), self.tools))},
        # )
        #
        # trace_id = str(int(time.time() * 1000))
        # with logger.contextualize(trace_id=trace_id, agent_id="default_agent_id", conversation_id=trace_id):
        #     to_logger(
        #         "INFO", agent_init_toollist, Extra(tag=Tag.TAG_AGENT_INIT_TOOLLIST, cost=2)
        #     )

    async def after_invoke(self, ctx: AgentCallbackContext) -> None:
        """Agent invoke 结束后补充 OTel span 属性。"""
        # ── 补充 session.id 和 cascade.turn_index 到 SDK 创建的 chain.EDPAgent span ──
        # Rail 执行顺序：LogRail(1000) after_invoke 先执行，此时 OtelRail(0) 创建的 chain span 已存在且未 end
        conv_id = ctx.session.get_session_id()
        session_id = getattr(ctx.session, "otel_session_id", None) or conv_id
        self._invoke_count = getattr(self, "_invoke_count", 0) + 1
        self._emit_metric(data={
            "openjiuwen.cascade.turn_index": self._invoke_count,
        }, session_id=session_id)

    def _extract_model_text(
        self,
        inputs: ModelCallInputs,
    ) -> list[dict]:
        """Extract text from model inputs."""
        parts: list[dict] = []
        for msg in inputs.messages:
            parts.append(msg.model_dump())
        return parts

    async def before_model_call(self, ctx: AgentCallbackContext) -> None:
        """log model input."""
        call_id = str(uuid.uuid4())
        ctx.extra["_log_rail_call_id"] = call_id
        start_time = time.time()
        ctx.extra["_log_rail_start_time"] = start_time
        llm_call_start = TagObservation(
            id=call_id,
            type=ObservationType.GENERATION,
            name=self.model_name,
            start_time=datetime.fromtimestamp(start_time).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3],
            input={"messages": self._extract_model_text(ctx.inputs)},
        )
        to_logger(level=Level.INFO, message=llm_call_start, extra=Extra(tag=Tag.TAG_LLM_CALL_START, cost=0))

    async def after_model_call(self, ctx: AgentCallbackContext) -> None:
        """log model response."""

        response = getattr(ctx.inputs, "response", None)
        if response is None:
            return

        # ── tool_call.arguments JSON 自动修复 ────────────────────────────
        # LLM streaming 偶发会少生成 args 末尾的闭合 ``}``（reasoning 模型常见 quirk），
        # agent-core 在执行 tool 时通过 _repair_tool_arguments_json 自动补全栈底未闭合的
        # `{` `[`，但**不**在持久化 AssistantMessage 之前修复——这导致：
        #   1. 工具能正常执行
        #   2. 但 history 里存的还是坏 JSON
        #   3. 下一轮 LLM 调用 messages 带着坏 args → 严格网关 400 / 模型困惑返空
        #
        # 这里在 after_model_call 钩子里 in-place 修复 ai_message.tool_calls，
        # 由于 react_agent.py:1290 的 add_messages(...) 用的就是这个 ai_message
        # 引用，修好的 args 会被持久化进 Redis，根治 sticky 失败链。
        try:
            self._repair_tool_call_arguments(response)
        except Exception as e:
            logger.warning(f"[LogRail] _repair_tool_call_arguments raised, skipping: {e!r}")

        call_id = ctx.extra.get("_log_rail_call_id", "")
        start_time = ctx.extra.get("_log_rail_start_time", time.time())
        end_time = time.time()
        duration_ms = int((end_time - start_time) * 1000)
        llm_call_end = TagObservation(
            id=call_id,
            type=ObservationType.GENERATION,
            name=self.model_name,
            end_time=datetime.fromtimestamp(end_time).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3],
            output=response.model_dump(),
            status_message=0,
            # cost_details={"first_token": 1}, 这里取不到值，在agent.py流式那里取
            total_cost=duration_ms,
        )

        to_logger(level=Level.INFO, message=llm_call_end, extra=Extra(tag=Tag.TAG_LLM_CALL_END, cost=duration_ms))

        # ── 补充 OTel span 属性（LLM 场景）───────────────────────────────────
        # Rail 执行顺序：LogRail(1000) after_model_call 先执行，此时 OtelRail(0) 创建的 llm span 已存在且未 end
        conv_id = ctx.session.get_session_id()
        session_id = getattr(ctx.session, "otel_session_id", None) or conv_id
        usage = getattr(response, "usage_metadata", None)
        metric_data = {
            "openjiuwen.llm.finish_reason": str(getattr(response, "finish_reason", "")),
            "openjiuwen.cost.total": duration_ms,
        }
        if usage:
            metric_data["gen_ai.usage.prompt_tokens"] = usage.input_tokens
            metric_data["gen_ai.usage.completion_tokens"] = usage.output_tokens
        # TTFT：从 session state 读取 _StreamProcessor 计算好的值
        ttft_ms = ctx.session.get_state("llm_ttft_ms") or 0
        if ttft_ms:
            metric_data["openjiuwen.llm.ttft_ms"] = ttft_ms
        reasoning = getattr(response, "reasoning_content", None)
        if reasoning:
            metric_data["openjiuwen.llm.reasoning_content"] = reasoning
        self._emit_metric(data=metric_data, session_id=session_id)

    @staticmethod
    def _repair_tool_call_arguments(response: Any) -> None:
        """对 response.tool_calls 里 arguments 不合法的 JSON 做 in-place 自动修复。

        复用 agent-core 自带的 ``AbilityManager._repair_tool_arguments_json``——
        遍历未闭合的 ``{`` / ``[`` 栈底，按相反顺序补齐 ``}`` / ``]``。
        这个修复对"丢末尾 `}`"这种最常见的 LLM streaming quirk 100% 生效。

        命中时打 WARNING；修复后仍非法（极罕见）时打 ERROR。
        """
        tool_calls = getattr(response, "tool_calls", None) or []
        if not tool_calls:
            return

        for tc in tool_calls:
            args = getattr(tc, "arguments", "") or ""
            if not isinstance(args, str) or not args.strip():
                continue

            # 合法直接跳过
            try:
                json.loads(args)
                continue
            except ValueError:
                pass

            # 调 agent-core 修复函数
            logger.info(
                f"[LogRail] before repair | name={getattr(tc, 'name', '?')} | "
                f"args_len={len(args)} | args={args!r}"
            )
            repaired = AbilityManager._repair_tool_arguments_json(args)
            if not repaired or repaired == args:
                logger.error(
                    f"[LogRail] FAILED to repair malformed tool_call arguments | "
                    f"name={getattr(tc, 'name', '?')} | "
                    f"args_len={len(args)} | "
                    f"args_tail={args[-60:]!r}"
                )
                continue

            try:
                json.loads(repaired)
            except ValueError as e:
                logger.error(
                    f"[LogRail] repaired args still invalid | "
                    f"name={getattr(tc, 'name', '?')} | err={e} | "
                    f"repaired_tail={repaired[-60:]!r}"
                )
                continue

            tc.arguments = repaired
            logger.info(
                f"[LogRail] after repair | name={getattr(tc, 'name', '?')} | "
                f"repaired_len={len(repaired)} | repaired={repaired!r}"
            )
            logger.warning(
                f"[LogRail] auto-repaired malformed tool_call.arguments | "
                f"name={getattr(tc, 'name', '?')} | "
                f"diff={len(repaired) - len(args)} chars added | "
                f"original_tail={args[-40:]!r} | "
                f"repaired_tail={repaired[-40:]!r}"
            )

    async def before_tool_call(self, ctx: AgentCallbackContext) -> None:
        tool_name = getattr(ctx.inputs, "tool_name", "") or ""
        tool_args = getattr(ctx.inputs, "tool_args", {}) or {}
        start_time = time.time()
        call_id = str(uuid.uuid4())
        ctx.session.update_state({f"_tool_call_id_{tool_name}": call_id})
        ctx.session.update_state({f"_tool_start_time_{tool_name}": start_time})
        # 兼容字符串形式的 tool_args
        if isinstance(tool_args, str):
            try:
                tool_args = json.loads(tool_args) if tool_args else {}
            except json.JSONDecodeError as e:
                logger.warning(
                    f"[ExecutionLimitRail] tool_args JSON 解析失败 "
                    f"tool={tool_name}, err={e}, raw={tool_args!r:.120}"
                )
                tool_args = {}

        to_logger(
            level=Level.INFO,
            message=json.dumps({
                "id": call_id,
                "type": "TOOL",
                "start_time": datetime.fromtimestamp(start_time).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3],
                "name": tool_name if tool_name is not None else "",
                "input": tool_args if tool_args is not None else {},
            }, ensure_ascii=False),
            extra=Extra(tag=Tag.TAG_TOOL_EXECUTE_START, cost=0),
        )
        if tool_name in ("read_file"):
            # 只有read_file的tool才记录skill的调用。因为skill都是同构readfile实现的
            self._log_skill_call_start(ctx, tool_name, tool_args)

    async def after_tool_call(self, ctx: AgentCallbackContext) -> None:
        tool_name = getattr(ctx.inputs, "tool_name", "") or ""
        tool_result = getattr(ctx.inputs, "tool_result", None)
        start_time = (
                ctx.session.get_state(f"_tool_start_time_{tool_name}")
                or time.time()
        )
        call_id = (ctx.session.get_state(f"_tool_call_id_{tool_name}") or "")
        end_time = time.time()
        duration_ms = int((end_time - start_time) * 1000) if start_time else -1
        if tool_name in ("read_file"):
            # 只有read_file的tool才记录skill的调用。因为skill都是同构readfile实现的
            self._log_skill_call_end(ctx, tool_name)
        to_logger(
                level=Level.INFO,
                message=json.dumps({
                    "id": call_id,
                    "type": "TOOL",
                    "end_time": datetime.fromtimestamp(end_time).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3],
                    "name": tool_name if tool_name is not None else "",
                    "output": getattr(
                        tool_result, 'model_dump',
                        lambda: getattr(tool_result, '__dict__', tool_result),
                    )() if tool_result is not None else {},
                    "status_message": 0,
                    "metadata": {},
                    "total_cost": duration_ms
                }, ensure_ascii=False),
                extra=Extra(tag=Tag.TAG_TOOL_EXECUTE_END, cost=duration_ms),
            )

        # ── 补充 OTel span 属性（普通工具场景，SDK 已创建 tool span）──────────
        # TAG 日志由上方原有逻辑输出，此处只补充 OTel span 属性，不重复打 TAG 日志
        try:
            from opentelemetry.trace import get_current_span
            span = get_current_span()
            if span and span.is_recording():
                conv_id = ctx.session.get_session_id()
                session_id = getattr(ctx.session, "otel_session_id", None) or conv_id
                span.set_attribute("openjiuwen.tool.status_message", 0)
                span.set_attribute("openjiuwen.cost.total", duration_ms)
                span.set_attribute("session.id", session_id)
                if tool_result is not None:
                    output_data = getattr(tool_result, "data", None) or tool_result
                    span.set_attribute(
                        "openjiuwen.agent.outputs",
                        json.dumps(output_data, ensure_ascii=False, default=str),
                    )
        except ImportError:
            pass

    # ── Skill 调用日志相关常量 ─────────────────────────────────────
    SKILL_STATE_KEY = "_current_skill"
    TIMING_KEY = "_tool_start_time"

    def _log_skill_call_start(self, ctx, tool_name: str, tool_args: dict) -> None:
        """记录工具调用开始：检测 Skill 切换并输出 EXEC_LOG START 日志。"""
        conv_id = ctx.session.get_session_id()
        skill_name = ctx.session.get_state(self.SKILL_STATE_KEY)

        # 设置初始化时间,打印SKILL执行日志
        start_time = time.time()
        ctx.session.update_state({f"_skill_start_time_{tool_name}": start_time})
        call_id = str(uuid.uuid4())
        ctx.session.update_state({f"_skill_call_id_{tool_name}": call_id})
        if tool_name == "read_file":
            detected = self._extract_skill_from_path(
                tool_args.get("file_path", "")
            )
            if detected:
                skill_name = detected
                ctx.session.update_state({self.SKILL_STATE_KEY: detected})

        to_logger(
            level=Level.INFO,
            message=json.dumps({
                "id": call_id,
                "start_time": datetime.fromtimestamp(start_time).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3],
                "type": "SKILL",
                "tool_name": tool_name if tool_name is not None else "",
                "name": skill_name if skill_name is not None else "",
                "input": tool_args if tool_args is not None else {},
                "metadata": {}
            }, ensure_ascii=False),
            extra=Extra(tag=Tag.TAG_SKILL_EXECUTE_START, cost=0),
        )

        ctx.session.update_state({self.TIMING_KEY: time.time()})

    def _log_skill_call_end(self, ctx, tool_name: str) -> None:
        """记录工具调用结束：计算耗时并输出 EXEC_LOG END 日志。"""
        conv_id = ctx.session.get_session_id()
        skill_name = ctx.session.get_state(self.SKILL_STATE_KEY)
        start_time = (
                ctx.session.get_state(f"_skill_start_time_{tool_name}")
                or time.time()
        )
        call_id = ctx.session.get_state(f"_skill_call_id_{tool_name}")
        end_time = time.time()
        duration_ms = int((end_time - start_time) * 1000) if start_time else -1
        ctx.session.update_state({self.TIMING_KEY: None})

        # 提取 result_keys（仅 dict 类型）
        tool_result = getattr(ctx.inputs, "tool_result", None)
        result_keys = list(tool_result.keys()) if isinstance(tool_result, dict) else None
        to_logger(
            level=Level.INFO,
            message=json.dumps({
                "id": call_id,
                "end_time": datetime.fromtimestamp(end_time).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3],
                "type": "SKILL",
                "tool_name": tool_name if tool_name is not None else "",
                "name": skill_name if skill_name is not None else "",
                "output": result_keys if result_keys is not None else {},
                "status_message": 0,
                "metadata": {},
                "total_cost": duration_ms
            }, ensure_ascii=False),
            extra=Extra(tag=Tag.TAG_SKILL_EXECUTE_END, cost=duration_ms),
        )

    @staticmethod
    def _extract_skill_from_path(file_path: str) -> str | None:
        """检测 read_file 是否读取 SKILL.md，是则返回所在 Skill 目录名。"""
        if not file_path or "SKILL.md" not in file_path:
            return None
        parts = Path(file_path).parts
        for i, part in enumerate(parts):
            if part == "SKILL.md" and i > 0:
                return parts[i - 1]
        return None