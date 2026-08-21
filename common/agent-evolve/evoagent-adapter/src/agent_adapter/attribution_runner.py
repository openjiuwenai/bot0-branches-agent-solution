"""AttributionRunner —— standard 模式后台轮询, trace 完整后异步算 skill 归属写回。

归属是 per-span + trace 级上下文 (L1/L2 需 parent 链与时间前缀 spans), 不能逐 span 入库时算;
故由本 runner 在 trace 完整后 (traces.end_time 已设) 整条算一次, UPDATE 写回 spans.attribution。
生命周期随 FastAPI app (startup 起 / shutdown 停), 仿 ``TraceConsumer``。

提交策略 (对齐 consumer):
- sweep 查询失败 / 单 trace 算失败 → ``logger.exception`` + 跳过, 不阻塞循环;
- ``attribution_runner_enabled=False`` → 不起 task;
- 找不到 agent 配置 (service_name 未在 config.agents) → 跳过该 trace (告警)。
"""

from __future__ import annotations

import asyncio
from contextlib import suppress
from typing import Any

import structlog

from agent_adapter.config import AdapterConfig, AttributionConfig
from agent_adapter.repository.base import TraceRepository
from agent_adapter.skill_store import SkillStoreProtocol
from agent_adapter.trace_attribution import SkillAttributionMapper, SkillToolTable

logger = structlog.get_logger(__name__)


class AttributionRunner:
    """周期扫描已完成但未归属的 trace, 算归属写回 spans.attribution 列。"""

    def __init__(
        self,
        repo: TraceRepository,
        skill_store: SkillStoreProtocol,
        config: AdapterConfig,
    ) -> None:
        self._repo = repo
        self._skill_store = skill_store
        self._config = config
        self._poll_interval = config.attribution_poll_interval
        self._task: asyncio.Task[None] | None = None
        # per-agent skill 文档缓存 (agent_name -> (skill_names, skill_docs)); 避免每 trace 重读。
        # v1 无失效, runner 重启刷新; skill 热更新后重启 runner 即可。
        self._skill_cache: dict[str, tuple[set[str], dict[str, str]]] = {}

    async def start(self) -> None:
        """起后台轮询 task。"""
        self._task = asyncio.create_task(self._run_loop())
        logger.info(
            "attribution_runner_started",
            poll_interval=self._poll_interval,
        )

    async def stop(self) -> None:
        """停轮询 task (随 app shutdown)。"""
        if self._task is not None:
            self._task.cancel()
            with suppress(asyncio.CancelledError):
                await self._task
            self._task = None
        logger.info("attribution_runner_stopped")

    async def _run_loop(self) -> None:
        """主循环: sweep + 逐 trace 算归属; 异常退避重试不静默挂 (仿 consumer _consume_loop)。"""
        while True:
            try:
                await self._process_pending()
            except Exception:
                logger.exception("attribution_runner_loop_error")
            await asyncio.sleep(self._poll_interval)

    async def _process_pending(self) -> None:
        """扫已完成未归属的 trace, 逐条算归属写回。"""
        pending = await self._repo.list_unattributed_completed_traces()
        if not pending:
            return
        for trace in pending:
            try:
                await self.attribute_trace(trace)
            except Exception:
                logger.exception(
                    "attribution_trace_failed",
                    trace_id=trace.get("trace_id"),
                    service_name=trace.get("service_name"),
                )

    async def attribute_trace(self, trace: dict[str, Any]) -> None:
        """单 trace: 取 spans (+ session 上下文) → 建 mapper → 算归属 → UPDATE 写回。

        session 级激活携带: 取同 session 前序 trace 的 spans (start_time <= 本 trace 末
        span 的 end_time) 参与建激活时间线 —— EDPAgent 每轮 HTTP 请求开新 trace, skill
        激活发生在第一轮, 不携带的话后几轮编排/汇总 span 全落 residual。只写回本 trace。
        """
        trace_id = trace["trace_id"]
        agent_name = trace.get("service_name") or ""
        attr_cfg = self._resolve_attr_config(agent_name)
        if attr_cfg is None or not attr_cfg.enabled:
            return
        skill_names, skill_docs = await self._load_skills(agent_name)
        spans = await self._repo.get_spans_by_trace(trace_id)
        if not spans:
            return
        context_spans = await self._session_context(trace, spans)
        tool_universe = AttributionRunner._collect_tool_names([*spans, *context_spans])
        skill_table = SkillToolTable.build(skill_docs, tool_universe, attr_cfg)
        mapper = SkillAttributionMapper(attr_cfg, skill_names, skill_table)
        result = mapper.infer(spans, context_spans=context_spans)
        if not result:
            return
        attributions = {sid: attr.to_dict() for sid, attr in result.items()}
        await self._repo.update_span_attribution(trace_id, attributions)
        logger.info(
            "attribution_trace_done",
            trace_id=trace_id,
            agent_name=agent_name,
            spans=len(attributions),
        )

    async def _session_context(
        self, trace: dict[str, Any], own_spans: list[dict[str, Any]]
    ) -> list[dict[str, Any]]:
        """取同 session 前序 trace 的 spans 作激活上下文 (按本 trace 结束时间截断)。

        本 trace 结束时间 = 自身 spans 的最大 end_time (sweep 保证 trace 已完整)。
        取交集失败/无 session_id 时返回空 (退化为 per-trace 语义, 不影响主流程)。
        """
        session_id = trace.get("session_id") or ""
        if not session_id:
            return []
        cutoff = max((s.get("end_time") or "" for s in own_spans), default="")
        if not cutoff:
            return []
        own_ids = {s.get("span_id") for s in own_spans}
        try:
            session_spans = await self._repo.get_spans_by_session(session_id)
        except Exception:
            logger.exception("attribution_session_context_failed", trace_id=trace.get("trace_id"))
            return []
        return [
            s
            for s in session_spans
            if s.get("span_id") not in own_ids and (s.get("start_time") or "") <= cutoff
        ]

    def _resolve_attr_config(self, agent_name: str) -> AttributionConfig | None:
        """按 service_name 找 agent 配置, 取其 attribution 块 (缺则默认 AttributionConfig)。"""
        for agent_cfg in self._config.agents:
            if agent_cfg.name == agent_name:
                return agent_cfg.attribution or AttributionConfig()
        logger.warning("attribution_agent_not_configured", agent_name=agent_name)
        return None

    async def _load_skills(self, agent_name: str) -> tuple[set[str], dict[str, str]]:
        """取该 agent 的 skill 名集 + 正文 (per-agent 缓存, sync SkillStore 包 to_thread)。"""
        cached = self._skill_cache.get(agent_name)
        if cached is not None:
            return cached
        skill_names, skill_docs = await asyncio.to_thread(
            AttributionRunner._load_skills_sync, self._skill_store, agent_name
        )
        self._skill_cache[agent_name] = (skill_names, skill_docs)
        return skill_names, skill_docs

    @staticmethod
    def _load_skills_sync(
        skill_store: SkillStoreProtocol,
        agent_name: str,
    ) -> tuple[set[str], dict[str, str]]:
        """同步: list_skills + 逐个 read_skill; 单个读取失败跳过不影响其余。"""
        try:
            summaries = skill_store.list_skills(agent_name)
        except Exception:
            logger.exception("attribution_list_skills_failed", agent_name=agent_name)
            return set(), {}
        skill_names: set[str] = set()
        skill_docs: dict[str, str] = {}
        for summary in summaries:
            try:
                content = skill_store.read_skill(agent_name, summary.name).content
            except Exception:
                logger.exception(
                    "attribution_read_skill_failed",
                    agent_name=agent_name,
                    skill=summary.name,
                )
                continue
            skill_names.add(summary.name)
            skill_docs[summary.name] = content
        return skill_names, skill_docs

    @staticmethod
    def _collect_tool_names(spans: list[dict[str, Any]]) -> list[str]:
        """从 trace 的 TOOL span 收集工具名 (strip ``tool.`` 前缀, 去重保序)。"""
        names: list[str] = []
        seen: set[str] = set()
        for span in spans:
            name = span.get("name") or ""
            if not name.startswith("tool."):
                continue
            tool = name.removeprefix("tool.")
            if tool and tool not in seen:
                seen.add(tool)
                names.append(tool)
        return names
