"""
memory_rail.py
--------------
本地实现的 MemoryRail，替代 openjiuwen SDK 内置版本。

优势：
- 接受外部已初始化的 LongTermMemory 实例（依赖注入），避免重复创建裸实例
- 可灵活定制 before_invoke / after_invoke 逻辑
- 与 agent.py 中的全局 memory engine 共享同一 store 连接

记忆注入机制：
  before_invoke 将召回结果写入 ctx.extra["memory_variables"]，
  SDK _inner_invoke 在调用 _build_rendered_system_prompt 时读取该字段：
    extra_render_fields=ctx.extra.get("memory_variables")
  并将其中的 sys_memory_variables / sys_long_term_memory 渲染进 system prompt 占位符。

完整 turn 消息捕获机制：
  SDK 在 before_invoke 触发后才设置 ctx.context，因此不能在 before_invoke 中记录水位线。
  after_invoke 中从 ctx.context.get_messages() 全量消息里从后往前找最后一条与当前 query
  匹配的 user 消息作为本轮起点，切出本轮新增消息（含 tool_call / tool_result / final answer），
  直接传给 LongTermMemory.add_messages()，无需类型转换。

批量写入机制（memory_write_interval）：
  after_invoke 不再每轮都触发记忆提取，而是累积 memory_write_interval 轮对话后
  一次性提交给 SDK。这样 MemoryAnalyzer 能看到更丰富的上下文，同时大幅减少
  LLM 调用次数。建议与滑窗轮数（default_window_round_num）保持一致。
  待提交的消息列表按 (user_id, session_id) 隔离，保存在进程内存中；
  服务重启后计数归零，最多丢失 interval-1 轮的提取，不影响已落库的记忆。

使用方式：
    from src.memory_rail import MemoryRail
    rail = MemoryRail(
        mem_scope_id="demo",
        agent_memory_config=AgentMemoryConfig(...),
        memory=get_memory_engine(),   # 传入已初始化的实例
        memory_write_interval=10,     # 每 10 轮触发一次记忆提取（默认 1，即每轮触发）
    )
    await agent.register_rail(rail)
"""
from __future__ import annotations

import asyncio
import datetime
from datetime import timezone
from typing import Any, List

from loguru import logger
from openjiuwen.core.common.security.json_utils import JsonUtils
from openjiuwen.core.foundation.llm import AssistantMessage, UserMessage
from openjiuwen.core.memory.config.config import AgentMemoryConfig
from openjiuwen.core.memory.long_term_memory import LongTermMemory
from openjiuwen.core.single_agent.rail.base import AgentCallbackContext, AgentRail

from ..memory_engine import get_memory_engine, init_memory_engine

# ════════════════════════════════════════════════════════════════════════
# Constants
# ════════════════════════════════════════════════════════════════════════

_MSG_CONTENT_MAX_CHARS = 3900

_MEMORY_PROMPT_SUFFIX = (
    "\n\n以下为历史记忆信息，仅作为辅助参考。"
    "若与本轮用户明确输入冲突，以本轮用户输入为准。"
    "仅在与当前问题直接相关时引用，不强行套用历史偏好。\n"
    "- 历史偏好与业务线索：{{sys_memory_variables}}\n"
    "- 相关历史对话片段：\n{{sys_long_term_memory}}"
)

# ════════════════════════════════════════════════════════════════════════
# Prompt Helpers
# ════════════════════════════════════════════════════════════════════════


def build_memory_prompt_suffix(enabled: bool) -> str:
    return _MEMORY_PROMPT_SUFFIX if enabled else ""


def _strip_memory_prompt(system_prompt: str) -> str:
    if system_prompt.endswith(_MEMORY_PROMPT_SUFFIX):
        return system_prompt[: -len(_MEMORY_PROMPT_SUFFIX)]
    return system_prompt.replace(_MEMORY_PROMPT_SUFFIX, "")


# ════════════════════════════════════════════════════════════════════════
# Message Helpers
# ════════════════════════════════════════════════════════════════════════


def _truncate_messages(messages: list, max_chars: int) -> list:
    result = []
    for msg in messages:
        content = getattr(msg, "content", None)
        if isinstance(content, str) and len(content) > max_chars:
            truncated = content[:max_chars]
            note = f"…[内容已截断，原始长度 {len(content)} 字符]"
            try:
                msg.content = truncated + note
            except AttributeError:
                role = getattr(msg, "role", "")
                if role == "user":
                    msg = UserMessage(content=truncated + note)
                else:
                    msg = AssistantMessage(content=truncated + note)
            logger.debug(
                "_truncate_messages: truncated {} message from {} to {} chars",
                getattr(msg, "role", "?"), len(content), len(truncated + note),
            )
        result.append(msg)
    return result


def _log_memory_task_exception(task: asyncio.Task) -> None:
    task_name = task.get_name()
    try:
        task.result()
        logger.info("memory rail task [{}] completed", task_name)
    except asyncio.CancelledError:
        logger.warning("memory rail task [{}] cancelled", task_name)
    except Exception as e:
        logger.exception("memory rail task [{}] failed: {}", task_name, e)


# ════════════════════════════════════════════════════════════════════════
# MemoryRail
# ════════════════════════════════════════════════════════════════════════


class MemoryRail(AgentRail):
    """本地 MemoryRail：将长期记忆能力嵌入 ReActAgent 生命周期。

    Hooks:
      before_invoke  — 从 LongTermMemory 加载记忆变量和历史记忆注入 ctx.extra
      after_invoke   — 异步将本轮对话写入 LongTermMemory
    """

    def __init__(
        self,
        mem_scope_id: str,
        agent_memory_config: AgentMemoryConfig,
        memory: LongTermMemory,
        memory_write_interval: int = 1,
        idle_flush_timeout: int = 600,
        pending_flush_chars_threshold: int = 1,
    ) -> None:
        super().__init__()
        self._mem_scope_id = mem_scope_id
        self._agent_memory_config = agent_memory_config
        self._memory = memory
        self._memory_write_interval = max(1, memory_write_interval)
        self._idle_flush_timeout = max(0, idle_flush_timeout)
        self._pending_flush_chars_threshold = max(1, pending_flush_chars_threshold)

        self._enable_long_term_mem = agent_memory_config.enable_long_term_mem
        self._enable_fragment_memory = (
            agent_memory_config.enable_user_profile
            or agent_memory_config.enable_semantic_memory
            or agent_memory_config.enable_episodic_memory
        )
        self._enable_summary_memory = agent_memory_config.enable_summary_memory
        self._enable_mem_variables = len(agent_memory_config.mem_variables) > 0
        self._mem_variables_config = agent_memory_config.mem_variables

        self._pending_messages: dict[str, list] = {}
        self._turn_counters: dict[str, int] = {}

        self._idle_timers: dict[str, asyncio.TimerHandle] = {}
        self._batch_meta: dict[str, tuple[str, str]] = {}

    async def before_invoke(self, ctx: AgentCallbackContext) -> None:
        user_id = ctx.extra.get("user_id", "")
        if not user_id:
            logger.info("MemoryRail.before_invoke: no user_id, skip memory load")
            return

        query = ctx.inputs.query if hasattr(ctx.inputs, "query") else ""
        logger.info("MemoryRail.before_invoke: user_id={} query={!r}", user_id, query[:80] if query else "")

        ctx.extra["_original_query"] = query

        result: dict = {}

        if self._enable_mem_variables:
            try:
                variables = await self._memory.get_variables(user_id=user_id, scope_id=self._mem_scope_id)
                if variables:
                    allowed = {v.name for v in self._mem_variables_config}
                    filtered = {k: v for k, v in variables.items() if k in allowed}
                    result["sys_memory_variables"] = JsonUtils.safe_json_dumps(
                        filtered, ensure_ascii=False
                    )
                    logger.info("MemoryRail.before_invoke: mem_variables loaded: {}", filtered)
                else:
                    result["sys_memory_variables"] = "{}"
                    logger.info("MemoryRail.before_invoke: mem_variables empty (no profile yet)")
            except Exception as e:
                logger.error("MemoryRail.before_invoke: get_variables failed: {}", e)
                result["sys_memory_variables"] = "{}"

        if self._enable_long_term_mem:
            typed_sections: dict[str, list[str]] = {}
            try:
                if self._enable_fragment_memory:
                    mems = await self._memory.search_user_mem(
                        user_id=user_id,
                        scope_id=self._mem_scope_id,
                        query=query,
                        num=10,
                    )
                    count = len(mems) if mems else 0
                    logger.info("MemoryRail.before_invoke: fragment memory recalled {} items", count)
                    for m in (mems or []):
                        raw_type = getattr(m.mem_info, "type", None)
                        mem_type = (
                            raw_type.value if hasattr(raw_type, "value") else str(raw_type)
                        ) if raw_type else "unknown"
                        content = m.mem_info.content
                        typed_sections.setdefault(mem_type, []).append(content)
                        logger.info("  [mem:{}] {}", mem_type, content[:120])

                if self._enable_summary_memory:
                    mems = await self._memory.search_user_history_summary(
                        user_id=user_id,
                        scope_id=self._mem_scope_id,
                        query=query,
                        num=5,
                    )
                    count = len(mems) if mems else 0
                    logger.info("MemoryRail.before_invoke: summary memory recalled {} items", count)
                    for m in (mems or []):
                        typed_sections.setdefault("summary", []).append(m.mem_info.content)

            except Exception as e:
                logger.error("MemoryRail.before_invoke: search memory failed: {}", e)

            ctx.extra["_memory_typed"] = typed_sections

            if typed_sections:
                _TYPE_LABELS = {  # pylint: disable=huawei-invalid-name
                    "user_profile": "用户画像",
                    "semantic_memory": "语义记忆",
                    "episodic_memory": "情景记忆",
                    "summary": "会话摘要",
                    "unknown": "其他记忆",
                }
                lines = []
                for t, items in typed_sections.items():
                    label = _TYPE_LABELS.get(t, t)
                    lines.append(f"[{label}]")
                    lines.extend(f"- {c}" for c in items)
                result["sys_long_term_memory"] = "\n".join(lines)
            else:
                result["sys_long_term_memory"] = ""
            logger.info(
                "MemoryRail.before_invoke: sys_long_term_memory built, {} typed sections",
                len(typed_sections),
            )
        else:
            ctx.extra["_memory_typed"] = {}

        ctx.extra["memory_variables"] = result

        logger.info(
            "MemoryRail.before_invoke: done, injected keys={}",
            list(result.keys()),
        )

    async def after_invoke(self, ctx: AgentCallbackContext) -> None:
        user_id = ctx.extra.get("user_id", "")
        if not user_id:
            return

        result = getattr(ctx.inputs, "result", None)
        if not isinstance(result, dict) or result.get("result_type") != "answer":
            logger.info(
                "MemoryRail.after_invoke: skip write (result_type={})",
                result.get("result_type") if isinstance(result, dict) else type(result).__name__,
            )
            return

        query = ctx.extra.get("_original_query", "")
        output = result.get("output", "")

        message_list: list = []
        if ctx.context:
            all_messages = ctx.context.get_messages()
            start_idx = len(all_messages)
            for i in range(len(all_messages) - 1, -1, -1):
                msg = all_messages[i]
                if getattr(msg, "role", "") == "user" and getattr(msg, "content", "") == query:
                    start_idx = i
                    break
            turn_messages = all_messages[start_idx:]
            message_list = [m for m in turn_messages if getattr(m, "role", "") != "system"]
            logger.info(
                "MemoryRail.after_invoke: captured {} turn msgs from ModelContext "
                "(start_idx={}, total={})",
                len(message_list), start_idx, len(all_messages),
            )

        if not message_list:
            logger.info("MemoryRail.after_invoke: ctx.context unavailable, fallback to query+output")
            if query:
                message_list.append(UserMessage(content=query))
            if output:
                message_list.append(AssistantMessage(content=output))

        if not message_list:
            return

        message_list = _truncate_messages(message_list, max_chars=_MSG_CONTENT_MAX_CHARS)

        conversation_id = (
            getattr(ctx.inputs, "conversation_id", None) or "default_session"
        )

        batch_key = f"{user_id}:{conversation_id}"
        pending = self._pending_messages.get(batch_key, [])
        pending.extend(message_list)
        self._pending_messages[batch_key] = pending
        self._batch_meta[batch_key] = (user_id, conversation_id)

        turn = self._turn_counters.get(batch_key, 0) + 1
        self._turn_counters[batch_key] = turn

        pending_chars = sum(len(getattr(m, "content", "") or "") for m in pending)
        should_flush_by_size = pending_chars >= self._pending_flush_chars_threshold

        logger.info(
            "MemoryRail.after_invoke: buffered turn={}/{} user_id={} session={} "
            "pending_msgs={} pending_chars={}",
            turn, self._memory_write_interval, user_id, conversation_id,
            len(pending), pending_chars,
        )

        self._schedule_idle_flush(batch_key)

        if turn < self._memory_write_interval and not should_flush_by_size:
            return

        if should_flush_by_size:
            logger.info(
                "MemoryRail.after_invoke: early flush triggered by size "
                "(pending_chars={} >= threshold={})",
                pending_chars, self._pending_flush_chars_threshold,
            )

        self._cancel_idle_timer(batch_key)
        self._turn_counters[batch_key] = 0
        flush_messages = self._pending_messages.pop(batch_key, [])

        logger.info(
            "MemoryRail.after_invoke: flushing add_messages user_id={} session={} total_msgs={}",
            user_id, conversation_id, len(flush_messages),
        )

        task = asyncio.create_task(
            self._memory.add_messages(
                user_id=user_id,
                scope_id=self._mem_scope_id,
                session_id=conversation_id,
                messages=flush_messages,
                timestamp=datetime.datetime.now(tz=timezone.utc).astimezone(),
                agent_config=self._agent_memory_config,
            )
        )
        task.set_name("memory_rail_add_messages")
        task.add_done_callback(_log_memory_task_exception)

    def _schedule_idle_flush(self, batch_key: str) -> None:
        if self._idle_flush_timeout <= 0:
            return
        self._cancel_idle_timer(batch_key)
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            logger.warning("MemoryRail: no running event loop, cannot schedule idle flush for {}", batch_key)
            return
        handle = loop.call_later(
            self._idle_flush_timeout,
            self._idle_flush_sync,
            batch_key,
        )
        self._idle_timers[batch_key] = handle
        logger.info(
            "MemoryRail: idle timer reset for {} ({:.0f}s)", batch_key, self._idle_flush_timeout
        )

    def _cancel_idle_timer(self, batch_key: str) -> None:
        handle = self._idle_timers.pop(batch_key, None)
        if handle is not None:
            handle.cancel()

    def _idle_flush_sync(self, batch_key: str) -> None:
        self._idle_timers.pop(batch_key, None)
        flush_messages = self._pending_messages.pop(batch_key, [])
        if not flush_messages:
            logger.info("MemoryRail: idle flush triggered for {} but no pending messages", batch_key)
            return
        user_id, conversation_id = self._batch_meta.get(batch_key, ("", ""))
        if not user_id:
            logger.warning("MemoryRail: idle flush for {}: missing user_id, skip", batch_key)
            return
        self._turn_counters.pop(batch_key, None)
        logger.info(
            "MemoryRail: idle flush triggered user_id={} session={} msgs={}",
            user_id, conversation_id, len(flush_messages),
        )
        try:
            loop = asyncio.get_running_loop()
            task = loop.create_task(
                self._memory.add_messages(
                    user_id=user_id,
                    scope_id=self._mem_scope_id,
                    session_id=conversation_id,
                    messages=flush_messages,
                    timestamp=datetime.datetime.now(tz=timezone.utc).astimezone(),
                    agent_config=self._agent_memory_config,
                )
            )
            task.set_name("memory_rail_idle_flush")
            task.add_done_callback(_log_memory_task_exception)
        except Exception as e:
            logger.error("MemoryRail: idle flush failed to create task: {}", e)


# ════════════════════════════════════════════════════════════════════════
# Public Registration Entry Point
# ════════════════════════════════════════════════════════════════════════


async def regist_memory_rail(agent: Any, config: Any, settings: Any, system_prompt: str) -> bool:
    if not settings.memory_enabled:
        return False

    try:
        from openjiuwen.core.common.schema.param import Param

        await init_memory_engine(settings)

        agent_memory_config = AgentMemoryConfig(
            enable_long_term_mem=True,
            enable_user_profile=settings.memory_enable_user_profile,
            enable_semantic_memory=settings.memory_enable_semantic_memory,
            enable_episodic_memory=settings.memory_enable_episodic_memory,
            enable_summary_memory=settings.memory_enable_summary_memory,
            mem_variables=[
                Param.string(
                    name=item.get("name", ""),
                    description=item.get("description", ""),
                    required=False,
                )
                for item in settings.memory_variables
                if item.get("name")
            ],
        )

        memory_rail = MemoryRail(
            mem_scope_id=settings.memory_scope_id,
            agent_memory_config=agent_memory_config,
            memory=get_memory_engine(),
            memory_write_interval=settings.memory_write_interval,
            idle_flush_timeout=settings.memory_idle_flush_timeout_second,
            pending_flush_chars_threshold=settings.memory_pending_flush_chars_threshold,
        )
        await agent.register_rail(memory_rail)
        logger.info("[DPA] MemoryRail 注册成功: scope_id={}", settings.memory_scope_id)
        return True
    except Exception as exc:
        logger.exception("[DPA] Memory 初始化失败，已降级为无记忆模式: {}", exc)
        config.configure_prompt_template(
            [{"role": "system", "content": _strip_memory_prompt(system_prompt)}]
        )
        agent.configure(config)
        return False
