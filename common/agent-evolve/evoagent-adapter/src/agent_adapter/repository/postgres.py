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
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import asyncpg

from agent_adapter.repository.aggregation import (
    backfill_session_id,
    build_trace_tree,
    compute_trace_summary,
)

# spans 表列序 (与 _SPAN_SQL 对齐) —— 18 列, 对齐 schema/postgres.sql (无 duration_ns, session_id)
_SPAN_COLUMNS = (
    "trace_id", "span_id", "parent_span_id", "trace_state", "name", "kind",
    "start_time", "end_time", "service_name", "scope_name", "scope_version",
    "status_code", "status_message", "attributes",
    "resource_attributes", "events", "links", "session_id",
)

# $7/$8 timestamptz, $14-17 jsonb
_SPAN_PLACEHOLDERS = (
    "$1", "$2", "$3", "$4", "$5", "$6",
    "$7::timestamptz", "$8::timestamptz", "$9", "$10", "$11",
    "$12", "$13",
    "$14::jsonb", "$15::jsonb", "$16::jsonb", "$17::jsonb", "$18",
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
    service_name       = EXCLUDED.service_name,
    scope_name         = EXCLUDED.scope_name,
    scope_version      = EXCLUDED.scope_version,
    status_code        = EXCLUDED.status_code,
    status_message     = EXCLUDED.status_message,
    attributes         = EXCLUDED.attributes,
    resource_attributes = EXCLUDED.resource_attributes,
    events             = EXCLUDED.events,
    links              = EXCLUDED.links,
    session_id         = EXCLUDED.session_id
"""

_TRACE_SQL = """
INSERT INTO traces (trace_id, session_id, root_span_id, service_name,
    start_time, end_time, span_count, status, request_summary, response_summary)
VALUES ($1, $2, $3, $4, $5::timestamptz, $6::timestamptz, $7, $8, $9::jsonb, $10::jsonb)
ON CONFLICT (trace_id) DO UPDATE SET
    session_id      = EXCLUDED.session_id,
    root_span_id    = EXCLUDED.root_span_id,
    service_name    = EXCLUDED.service_name,
    start_time      = EXCLUDED.start_time,
    end_time        = EXCLUDED.end_time,
    span_count      = EXCLUDED.span_count,
    status          = EXCLUDED.status,
    request_summary = EXCLUDED.request_summary,
    response_summary = EXCLUDED.response_summary,
    updated_at      = now()
"""

_SCHEMA_SQL = Path(__file__).resolve().parent.parent / "schema" / "postgres.sql"


def _null_text(v: Any) -> str | None:
    """空串文本 → NULL (parent_span_id/trace_state/scope_version/status_message)。"""
    return None if v in (None, "") else v


def _nano_to_dt(nano: int) -> datetime:
    """unix 纳秒 → timezone-aware datetime (UTC)。"""
    seconds = nano // 1_000_000_000
    micros = (nano % 1_000_000_000) // 1000
    return datetime.fromtimestamp(seconds, tz=timezone.utc).replace(microsecond=micros)


def _to_dt(v: Any) -> datetime | None:
    """入表时间格式校验并转换 → timezone-aware datetime (asyncpg timestamptz 参数要求 datetime 对象)。

    接受: ISO 8601 字符串 / unix 纳秒 (int 或纯数字字符串) / datetime 对象。
    空值返回 None; 无法识别的格式抛 ValueError (脏数据不静默入库)。
    """
    if v is None or v == "":
        return None
    if isinstance(v, datetime):
        return v
    if isinstance(v, (int, float)):
        return _nano_to_dt(int(v))
    if isinstance(v, str):
        s = v.strip()
        if s.isdigit():  # 纯数字串 → unix 纳秒
            return _nano_to_dt(int(s))
        return datetime.fromisoformat(s)
    raise ValueError(f"_to_dt: 无法识别的时间格式 {v!r}")


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
        span.get("service_name"),
        span.get("scope_name"),
        _null_text(span.get("scope_version")),
        span.get("status_code"),
        _null_text(span.get("status_message")),
        json.dumps(span.get("attributes") or {}, ensure_ascii=False),
        json.dumps(span.get("resource_attributes") or {}, ensure_ascii=False),
        json.dumps(span.get("events") or [], ensure_ascii=False),
        json.dumps(span.get("links") or [], ensure_ascii=False),
        span.get("session_id"),
    )


def _trace_params(trace: dict[str, Any]) -> tuple:
    """trace 汇总 dict → traces UPSERT 参数 (对齐 _TRACE_SQL)。summary 字段 json.dumps。"""
    return (
        trace.get("trace_id"),
        trace.get("session_id"),
        trace.get("root_span_id"),
        trace.get("service_name"),
        _to_dt(trace.get("start_time")),
        _to_dt(trace.get("end_time")),
        trace.get("span_count"),
        trace.get("status"),
        json.dumps(trace.get("request_summary"), ensure_ascii=False) if trace.get("request_summary") is not None else None,
        json.dumps(trace.get("response_summary"), ensure_ascii=False) if trace.get("response_summary") is not None else None,
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
        "service_name": row["service_name"],
        "scope_name": row["scope_name"],
        "scope_version": row["scope_version"] or "",
        "status_code": row["status_code"],
        "status_message": row["status_message"] or "",
        "attributes": json.loads(row["attributes"]) if row["attributes"] else {},
        "resource_attributes": json.loads(row["resource_attributes"]) if row["resource_attributes"] else {},
        "events": json.loads(row["events"]) if row["events"] else [],
        "links": json.loads(row["links"]) if row["links"] else [],
        "session_id": row["session_id"],
    }


def _trace_row_to_dict(row: asyncpg.Record) -> dict[str, Any]:
    """traces 行 → 汇总 dict (时间 ISO, summary JSONB 解码)。"""
    return {
        "trace_id": row["trace_id"],
        "session_id": row["session_id"],
        "root_span_id": row["root_span_id"],
        "service_name": row["service_name"],
        "start_time": row["start_time"].isoformat() if row["start_time"] else None,
        "end_time": row["end_time"].isoformat() if row["end_time"] else None,
        "span_count": row["span_count"],
        "status": row["status"],
        "request_summary": json.loads(row["request_summary"]) if row["request_summary"] else None,
        "response_summary": json.loads(row["response_summary"]) if row["response_summary"] else None,
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
        """批量插 spans + 按涉及的 trace_id 各重算汇总 (一个事务)。

        session_id 回填两段式:
          A. 批内: backfill_session_id (aggregation 纯函数) 用本批兄弟的非空 session 就地回填;
          B. 跨批: _backfill_session_id_for_trace 用 DB 内同 trace 的 session 回填本批晚到空 span
                 (session 兄弟可能在前一批已入库, 本批 in-memory 看不到)。
        B 在 _reupsert_trace 之前跑, 使 traces 汇总读到回填后的 session_id 列。
        """
        assert self.pool is not None, "start() 未调用"
        if not spans:
            return
        spans = backfill_session_id(spans)  # A: 批内回填 (非原地, 返回新列表)
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                await conn.executemany(_SPAN_SQL, [_span_params(s) for s in spans])
                for trace_id in {s["trace_id"] for s in spans}:
                    await self._backfill_session_id_for_trace(conn, trace_id)  # B: 跨批兜底
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

    async def _backfill_session_id_for_trace(
        self, conn: asyncpg.Connection, trace_id: str
    ) -> None:
        """同 trace 内用首个非空 session_id 回填空 session_id 行 (跨批兜底, B 段, 幂等)。

        晚到 span 的 session 兄弟可能在前一批已入库 → 批内回填 (A) 看不到, 在此用 DB
        现有行的 session 补齐。孤儿 trace (DB 内无任何 session) → src.sid 为 NULL,
        `AND src.sid IS NOT NULL` 守门, 不更新, 保留空。
        """
        await conn.execute(
            """
            UPDATE spans
            SET session_id = src.sid
            FROM (SELECT MIN(session_id) AS sid
                  FROM spans
                  WHERE trace_id = $1 AND session_id IS NOT NULL) src
            WHERE trace_id = $1 AND session_id IS NULL AND src.sid IS NOT NULL
            """,
            trace_id,
        )

    async def backfill_session_id(self) -> int:
        """全表回填空 session_id (按 trace 取首个非空 session 补齐; 孤儿不动)。返回受影响行数。

        一次性维护用: 部署本回填逻辑后, 对历史已入库的空 session span 做一次性补齐。
        常规摄取路径无需调用 (bulk_insert_spans 已含 per-trace 兜底)。
        """
        assert self.pool is not None, "start() 未调用"
        async with self.pool.acquire() as conn:
            return await conn.fetchval(
                """
                WITH filled AS (
                    UPDATE spans s
                    SET session_id = sub.sid
                    FROM (SELECT trace_id, MIN(session_id) AS sid
                          FROM spans
                          WHERE session_id IS NOT NULL
                          GROUP BY trace_id) sub
                    WHERE s.trace_id = sub.trace_id AND s.session_id IS NULL
                    RETURNING 1
                )
                SELECT count(*) FROM filled
                """
            ) or 0

    # ---- 读 ----

    async def get_spans_by_trace(self, trace_id: str) -> list[dict[str, Any]]:
        assert self.pool is not None, "start() 未调用"
        rows = await self.pool.fetch(
            "SELECT * FROM spans WHERE trace_id=$1 ORDER BY start_time", trace_id
        )
        return [_row_to_span(r) for r in rows]

    async def get_spans_by_session(self, session_id: str) -> list[dict[str, Any]]:
        assert self.pool is not None, "start() 未调用"
        rows = await self.pool.fetch(
            "SELECT * FROM spans WHERE session_id=$1 ORDER BY start_time", session_id
        )
        return [_row_to_span(r) for r in rows]

    async def get_trace_tree(self, trace_id: str) -> dict[str, Any] | None:
        spans = await self.get_spans_by_trace(trace_id)
        return build_trace_tree(spans)

    async def get_root_span(self, session_id: str) -> dict[str, Any] | None:
        """会话根 span: kind=SERVER 且 parent 为空 (镜像 aggregation.is_root_span)。"""
        assert self.pool is not None, "start() 未调用"
        row = await self.pool.fetchrow(
            "SELECT * FROM spans WHERE session_id=$1 AND kind='SERVER' "
            "AND (parent_span_id IS NULL OR parent_span_id='') ORDER BY start_time LIMIT 1",
            session_id,
        )
        return _row_to_span(row) if row else None

    async def list_sessions(self, agent_name: str | None = None) -> list[dict[str, Any]]:
        assert self.pool is not None, "start() 未调用"
        if agent_name is not None:
            rows = await self.pool.fetch(
                "SELECT * FROM traces WHERE service_name=$1 ORDER BY start_time DESC", agent_name
            )
        else:
            rows = await self.pool.fetch("SELECT * FROM traces ORDER BY start_time DESC")
        return [_trace_row_to_dict(r) for r in rows]
