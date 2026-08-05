"""DbTraceSource —— standard 模式: 从 PG 读 spans, 经 spans_to_records 转 records。

依赖 TraceRepository (注入); 不见 SQL, 只调 repo。产出与 LogTraceSource 同一种
record 格式, 下游 clean_traces 零改动。
"""

from __future__ import annotations

from typing import Any

from agent_adapter.repository.base import TraceRepository
from agent_adapter.trace_source.base import TraceSource
from agent_adapter.trace_source.spans_to_records import spans_to_records


class DbTraceSource:
    """standard 模式 TraceSource: PG spans → records。"""

    def __init__(self, repo: TraceRepository) -> None:
        self._repo = repo

    async def list_conversations(self, agent_name: str | None = None) -> list[str]:
        rows = await self._repo.list_sessions(agent_name=agent_name)
        # repo 返回 trace 汇总 dict 列表; 取 session_id (去重保序)。
        # 桥接: TraceSource 接口层用 conversation_id 抽象 (值=session.id), DB 层用 session_id 列。
        seen: set[str] = set()
        ids: list[str] = []
        for row in rows:
            cid = row.get("session_id")
            if cid and cid not in seen:
                seen.add(cid)
                ids.append(cid)
        return ids

    async def get_records(self, agent_name: str | None, conversation_id: str) -> list[dict[str, Any]]:
        # conversation_id 即 session.id (TraceSource 抽象层); DB 层按 session_id 索引。
        # agent_name 在 DB 模式下不参与查询 (DB 按 session_id 索引)
        spans = await self._repo.get_spans_by_session(conversation_id)
        return spans_to_records(spans)
