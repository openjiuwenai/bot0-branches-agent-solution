"""DbTraceSource —— standard 模式: 从 PG 读 spans, 经 spans_to_records 转 records。

依赖 TraceRepository (注入); 不见 SQL, 只调 repo。产出与 LogTraceSource 同一种
record 格式, 下游 clean_traces 零改动。
"""

from __future__ import annotations

from typing import Any

from agent_adapter.repository.base import TraceRepository
from agent_adapter.trace_source.base import TraceSource
from agent_adapter.trace_source.spans_to_records import spans_to_records
from agent_adapter.trace_profile.loader import ProfileRegistry
from agent_adapter.trace_profile.filters import filter_spans_by_service


class DbTraceSource:
    """standard 模式 TraceSource: PG spans → records。"""

    def __init__(
        self,
        repo: TraceRepository,
        registry: ProfileRegistry | None = None,
        agents: list | None = None,
    ) -> None:
        self._repo = repo
        self._registry = registry
        self._agents = agents or []

    async def list_conversations(self, agent_name: str | None = None) -> list[str]:
        rows = await self._repo.list_sessions(agent_name=agent_name)
        seen: set[str] = set()
        ids: list[str] = []
        for row in rows:
            cid = row.get("session_id")
            if cid and cid not in seen:
                seen.add(cid)
                ids.append(cid)
        return ids

    async def get_records(self, agent_name: str | None, conversation_id: str) -> list[dict[str, Any]]:
        spans = await self._repo.get_spans_by_session(conversation_id)
        profile = self._resolve_profile(agent_name)
        if profile is not None and self._registry is not None:
            spans = filter_spans_by_service(spans, self._registry, "query")
        return spans_to_records(spans, profile)

    def _resolve_profile(self, agent_name: str | None):
        if self._registry is None or agent_name is None:
            return None
        return self._registry.get_by_agent_name(agent_name, self._agents)
