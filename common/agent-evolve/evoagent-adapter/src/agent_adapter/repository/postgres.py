"""PostgresTraceRepository —— asyncpg 接入层 (TraceRepository 的 PG 实现)。

只做 asyncpg I/O + 方言适配 (JSONB 序列化 / timestamptz 解析); 聚合规则 (traces 汇总、
span 树、根 span 判定) 全部委托给 repository.aggregation 纯函数, 此处为零业务逻辑。

生命周期随 FastAPI app: startup 调 ``start()`` (建连接池), shutdown 调 ``stop()``。
``init_schema()`` 读 schema/postgres.sql 建表, 启动时调一次。

JSONB 处理 (无全局 codec, 显式可控): 写入用 ``json.dumps(x)`` + ``$n::jsonb``;
读出 asyncpg 返回 text, 由 _row_to_span / _trace_row_to_dict 做 json.loads。
"""

from __future__ import annotations

import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any

import asyncpg

from agent_adapter.repository.aggregation import build_trace_tree, compute_trace_summary

# spans 表列序 (与 _SPAN_SQL 对齐) —— 19 列, 对齐 schema/postgres.sql
_SPAN_COLUMNS = (
    "trace_id", "span_id", "parent_span_id", "trace_state", "name", "kind",
    "start_time", "end_time", "duration_ns", "service_name", "scope_name",
    "scope_version", "status_code", "status_message", "attributes",
    "resource_attributes", "events", "links", "conversation_id",
)

# $7/$8 timestamptz, $15-18 jsonb
_SPAN_PLACEHOLDERS = (
    "$1", "$2", "$3", "$4", "$5", "$6",
    "$7::timestamptz", "$8::timestamptz", "$9", "$10", "$11",
    "$12", "$13", "$14",
    "$15::jsonb", "$16::jsonb", "$17::jsonb", "$18::jsonb", "$19",
)

_SPAN_SQL = f"""
INSERT INTO spans ({", ".join(_SPAN_COLUMNS)})
VALUES ({", ".join(_SPAN_PLACEHOLDERS)})
ON CONFLICT (trace_id, span_id) DO UPDATE SET
    parent_span_id     = EXCLUDED.parent_span_id,
    trace_state        = EXCLUDED.trace_state,
    name               = EXCLUDED.name,
    kind               = EXCLUDED.kind,
    start_time         = EXCLUDED.start_time,
    end_time           = EXCLUDED.end_time,
    duration_ns        = EXCLUDED.duration_ns,
    service_name       = EXCLUDED.service_name,
    scope_name         = EXCLUDED.scope_name,
    scope_version      = EXCLUDED.scope_version,
    status_code        = EXCLUDED.status_code,
    status_message     = EXCLUDED.status_message,
    attributes         = EXCLUDED.attributes,
    resource_attributes = EXCLUDED.resource_attributes,
    events             = EXCLUDED.events,
    links              = EXCLUDED.links,
    conversation_id    = EXCLUDED.conversation_id
"""

_TRACE_SQL = """
INSERT INTO traces (trace_id, conversation_id, user_id, root_span_id, service_name,
    start_time, end_time, span_count, status, request_summary, response_summary, openjiuwen_trace_id)
VALUES ($1, $2, $3, $4, $5, $6::timestamptz, $7::timestamptz, $8, $9, $10::jsonb, $11::jsonb, $12)
ON CONFLICT (trace_id) DO UPDATE SET
    conversation_id     = EXCLUDED.conversation_id,
    user_id             = EXCLUDED.user_id,
    root_span_id        = EXCLUDED.root_span_id,
    service_name        = EXCLUDED.service_name,
    start_time          = EXCLUDED.start_time,
    end_time            = EXCLUDED.end_time,
    span_count          = EXCLUDED.span_count,
    status              = EXCLUDED.status,
    request_summary     = EXCLUDED.request_summary,
    response_summary    = EXCLUDED.response_summary,
    openjiuwen_trace_id = EXCLUDED.openjiuwen_trace_id,
    updated_at          = now()
"""

_SCHEMA_SQL = Path(__file__).resolve().parent.parent / "schema" / "postgres.sql"


def _null_text(v: Any) -> str | None:
    """空串文本 → NULL (parent_span_id/trace_state/scope_version/status_message)。"""
    return None if v in (None, "") else v


def _to_dt(v: Any) -> datetime | None:
    """ISO 字符串 → timezone-aware datetime (asyncpg 的 timestamptz 参数要求 datetime 对象)。

    parse_span/compute_trace_summary 产出 ISO 字符串; 此处还原为 datetime 交给 asyncpg。
    """
    if v is None or v == "":
        return None
    if isinstance(v, datetime):
        return v
    return datetime.fromisoformat(v)


def _span_params(span: dict[str, Any]) -> tuple:
    """span dict → spans INSERT 参数元组 (对齐 _SPAN_COLUMNS)。JSONB 字段 json.dumps。"""
    return (
        span.get("trace_id"),
        span.get("span_id"),
        _null_text(span.get("parent_span_id")),
        _null_text(span.get("trace_state")),
        span.get("name"),
        span.get("kind"),
        _to_dt(span.get("start_time")),
        _to_dt(span.get("end_time")),
        span.get("duration_ns"),
        span.get("service_name"),
        span.get("scope_name"),
        _null_text(span.get("scope_version")),
        span.get("status_code"),
        _null_text(span.get("status_message")),
        json.dumps(span.get("attributes") or {}, ensure_ascii=False),
        json.dumps(span.get("resource_attributes") or {}, ensure_ascii=False),
        json.dumps(span.get("events") or [], ensure_ascii=False),
        json.dumps(span.get("links") or [], ensure_ascii=False),
        span.get("conversation_id"),
    )


def _trace_params(trace: dict[str, Any]) -> tuple:
    """trace 汇总 dict → traces UPSERT 参数 (对齐 _TRACE_SQL)。summary 字段 json.dumps。"""
    return (
        trace.get("trace_id"),
        trace.get("conversation_id"),
        trace.get("user_id"),
        trace.get("root_span_id"),
        trace.get("service_name"),
        _to_dt(trace.get("start_time")),
        _to_dt(trace.get("end_time")),
        trace.get("span_count"),
        trace.get("status"),
        json.dumps(trace.get("request_summary"), ensure_ascii=False) if trace.get("request_summary") is not None else None,
        json.dumps(trace.get("response_summary"), ensure_ascii=False) if trace.get("response_summary") is not None else None,
        trace.get("openjiuwen_trace_id"),
    )


def _row_to_span(row: asyncpg.Record) -> dict[str, Any]:
    """spans 行 → 扁平 span dict (对齐 parse_span 输出形状, JSONB 解码, 空值归一)。"""
    return {
        "trace_id": row["trace_id"],
        "span_id": row["span_id"],
        "parent_span_id": row["parent_span_id"] or "",
        "trace_state": row["trace_state"] or "",
        "name": row["name"],
        "kind": row["kind"],
        "start_time": row["start_time"].isoformat() if row["start_time"] else None,
        "end_time": row["end_time"].isoformat() if row["end_time"] else None,
        "duration_ns": row["duration_ns"],
        "service_name": row["service_name"],
        "scope_name": row["scope_name"],
        "scope_version": row["scope_version"] or "",
        "status_code": row["status_code"],
        "status_message": row["status_message"] or "",
        "attributes": json.loads(row["attributes"]) if row["attributes"] else {},
        "resource_attributes": json.loads(row["resource_attributes"]) if row["resource_attributes"] else {},
        "events": json.loads(row["events"]) if row["events"] else [],
        "links": json.loads(row["links"]) if row["links"] else [],
        "conversation_id": row["conversation_id"],
    }


def _trace_row_to_dict(row: asyncpg.Record) -> dict[str, Any]:
    """traces 行 → 汇总 dict (时间 ISO, summary JSONB 解码)。"""
    return {
        "trace_id": row["trace_id"],
        "conversation_id": row["conversation_id"],
        "user_id": row["user_id"],
        "root_span_id": row["root_span_id"],
        "service_name": row["service_name"],
        "start_time": row["start_time"].isoformat() if row["start_time"] else None,
        "end_time": row["end_time"].isoformat() if row["end_time"] else None,
        "span_count": row["span_count"],
        "status": row["status"],
        "request_summary": json.loads(row["request_summary"]) if row["request_summary"] else None,
        "response_summary": json.loads(row["response_summary"]) if row["response_summary"] else None,
        "openjiuwen_trace_id": row["openjiuwen_trace_id"],
    }


class PostgresTraceRepository:
    """TraceRepository 的 PostgreSQL 实现 (asyncpg)。

    使用: ``repo = PostgresTraceRepository(dsn); await repo.start(); await repo.init_schema()``
    生命周期随 app; 用完 ``await repo.stop()``。
    """

    def __init__(self, dsn: str, *, min_size: int = 1, max_size: int = 10) -> None:
        self.dsn = dsn
        self._min_size = min_size
        self._max_size = max_size
        self.pool: asyncpg.Pool | None = None

    async def start(self) -> None:
        """建连接池。"""
        self.pool = await asyncpg.create_pool(
            dsn=self.dsn, min_size=self._min_size, max_size=self._max_size,
        )

    async def stop(self) -> None:
        """关连接池。"""
        if self.pool is not None:
            await self.pool.close()
            self.pool = None

    async def init_schema(self) -> None:
        """读 schema/postgres.sql 建表 (按 ';' 拆分逐条执行, IF NOT EXISTS 幂等)。"""
        assert self.pool is not None, "start() 未调用"
        sql = _SCHEMA_SQL.read_text(encoding="utf-8")
        # 先剔除 -- 行注释 (含句内分号的注释会让 split(';') 误切断), 再按 ';' 拆分。
        sql_no_comments = re.sub(r"--[^\n]*", "", sql)
        statements = [s.strip() for s in sql_no_comments.split(";") if s.strip()]
        async with self.pool.acquire() as conn:
            for stmt in statements:
                await conn.execute(stmt)

    # ---- 写 ----

    async def insert_span(self, span: dict[str, Any]) -> None:
        """插一条 span + 重算并 upsert 其 trace 汇总 (一个事务, 幂等)。"""
        assert self.pool is not None, "start() 未调用"
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(_SPAN_SQL, *_span_params(span))
                await self._reupsert_trace(conn, span["trace_id"])

    async def bulk_insert_spans(self, spans: list[dict[str, Any]]) -> None:
        """批量插 spans + 按涉及的 trace_id 各重算汇总 (一个事务)。"""
        assert self.pool is not None, "start() 未调用"
        if not spans:
            return
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                await conn.executemany(_SPAN_SQL, [_span_params(s) for s in spans])
                for trace_id in {s["trace_id"] for s in spans}:
                    await self._reupsert_trace(conn, trace_id)

    async def upsert_trace(self, trace: dict[str, Any]) -> None:
        """显式 upsert 一条 traces 汇总 (调用方提供完整 summary)。"""
        assert self.pool is not None, "start() 未调用"
        async with self.pool.acquire() as conn:
            await conn.execute(_TRACE_SQL, *_trace_params(trace))

    async def _reupsert_trace(self, conn: asyncpg.Connection, trace_id: str) -> None:
        """从 DB 现有 spans 重算 trace 汇总并 upsert (insert_span/bulk 用, 单源真相)。"""
        rows = await conn.fetch(
            "SELECT * FROM spans WHERE trace_id=$1 ORDER BY start_time", trace_id
        )
        if not rows:
            return
        summary = compute_trace_summary(trace_id, [_row_to_span(r) for r in rows])
        await conn.execute(_TRACE_SQL, *_trace_params(summary))

    # ---- 读 ----

    async def get_spans_by_trace(self, trace_id: str) -> list[dict[str, Any]]:
        assert self.pool is not None, "start() 未调用"
        rows = await self.pool.fetch(
            "SELECT * FROM spans WHERE trace_id=$1 ORDER BY start_time", trace_id
        )
        return [_row_to_span(r) for r in rows]

    async def get_spans_by_conversation(self, conversation_id: str) -> list[dict[str, Any]]:
        assert self.pool is not None, "start() 未调用"
        rows = await self.pool.fetch(
            "SELECT * FROM spans WHERE conversation_id=$1 ORDER BY start_time", conversation_id
        )
        return [_row_to_span(r) for r in rows]

    async def get_trace_tree(self, trace_id: str) -> dict[str, Any] | None:
        spans = await self.get_spans_by_trace(trace_id)
        return build_trace_tree(spans)

    async def get_root_span(self, conversation_id: str) -> dict[str, Any] | None:
        """会话根 span: kind=SERVER 且 parent 为空 (镜像 aggregation.is_root_span)。"""
        assert self.pool is not None, "start() 未调用"
        row = await self.pool.fetchrow(
            "SELECT * FROM spans WHERE conversation_id=$1 AND kind='SERVER' "
            "AND (parent_span_id IS NULL OR parent_span_id='') ORDER BY start_time LIMIT 1",
            conversation_id,
        )
        return _row_to_span(row) if row else None

    async def list_conversations(self, agent_name: str | None = None) -> list[dict[str, Any]]:
        assert self.pool is not None, "start() 未调用"
        if agent_name is not None:
            rows = await self.pool.fetch(
                "SELECT * FROM traces WHERE service_name=$1 ORDER BY start_time DESC", agent_name
            )
        else:
            rows = await self.pool.fetch("SELECT * FROM traces ORDER BY start_time DESC")
        return [_trace_row_to_dict(r) for r in rows]
