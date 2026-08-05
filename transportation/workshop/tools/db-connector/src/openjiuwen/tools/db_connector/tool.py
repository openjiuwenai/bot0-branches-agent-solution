"""DbConnectorTool — 数据库连接 Agent 工具核心实现。

所有 SQL 执行均走参数化（cursor.execute(sql, params)），禁止字符串拼接。
"""

from __future__ import annotations

import time
import uuid
from datetime import datetime, timezone
from typing import Any, Protocol

from sqlalchemy import text

from .config import DbConnectorConfig
from .core.connection_manager import ConnectionManager
from .core.result_mapper import QueryResult, WriteResult, HealthStatus
from .schema.schema_importer import SchemaImporter, SchemaSnapshot
from .security.sql_guard import SqlGuard
from .security.sql_sanitizer import SqlSanitizer
from .security.statement_normalizer import StatementNormalizer
from .audit.audit_logger import AuditLogger, AuditEntry


class QueryOptions:
    """查询选项。"""
    def __init__(self, max_rows: int | None = None, timeout_ms: int | None = None) -> None:
        self.max_rows = max_rows
        self.timeout_ms = timeout_ms


class DbConnectorTool(Protocol):
    """数据库连接 Agent 工具接口。"""

    def query(self, sql_template: str, params: list | None = None,
              options: QueryOptions | None = None) -> QueryResult: ...

    def insert(self, sql_template: str, params: list | None = None) -> WriteResult: ...

    def update(self, sql_template: str, params: list | None = None) -> WriteResult: ...

    def delete(self, sql_template: str, params: list | None = None) -> WriteResult: ...

    def ping(self) -> HealthStatus: ...

    def import_schema(self, tables: list[str] | None = None) -> SchemaSnapshot: ...

    def refresh_schema(self) -> SchemaSnapshot: ...


class DefaultDbConnectorTool:
    """DbConnectorTool 默认实现。

    安全流程：
    1. SqlGuard 拦截危险语句（黑名单、无 WHERE、堆叠注入等）
    2. SqlSanitizer 校验标识符（表名/列名白名单）
    3. 参数化执行（cursor.execute(sql, params)）
    4. AuditLogger 记录审计日志
    """

    def __init__(self, config: DbConnectorConfig) -> None:
        self._config = config
        self._conn_mgr = ConnectionManager(config.data_source, config.credential)
        self._guard = SqlGuard(config.security)
        self._sanitizer = SqlSanitizer(config.security.allowed_tables)
        self._audit = AuditLogger(config.audit)
        self._schema_importer = SchemaImporter(
            self._conn_mgr.engine, config.schema_import
        )

    # ------------------------------------------------------------------
    # 内部方法
    # ------------------------------------------------------------------

    def _check_guard(self, sql_template: str) -> None:
        """执行前安全检查，不通过则抛出 ValueError。"""
        result = self._guard.check(sql_template, self._config.mode)
        if not result.allowed:
            raise ValueError(f"SQL 被安全守卫拦截: {result.reason}")

    def _make_audit_entry(
        self, sql_template: str, params: list | None,
        affected_rows: int, duration_ms: int, status: str,
    ) -> AuditEntry:
        return AuditEntry(
            timestamp=datetime.now(timezone.utc).isoformat(),
            agent_id="",
            principal="",
            mode=self._config.mode,
            sql_template_hash=StatementNormalizer.fingerprint(sql_template),
            params_summary=self._audit.mask_params(params or []),
            affected_rows=affected_rows,
            duration_ms=duration_ms,
            result_status=status,
        )

    def _gen_audit_id(self) -> str:
        return f"aud-{datetime.now().strftime('%Y%m%d')}-{uuid.uuid4().hex[:8]}"

    # ------------------------------------------------------------------
    # 查询
    # ------------------------------------------------------------------

    def query(
        self,
        sql_template: str,
        params: list | None = None,
        options: QueryOptions | None = None,
    ) -> QueryResult:
        """执行参数化查询，返回结构化结果集。"""
        params = params or []
        self._check_guard(sql_template)

        max_rows = (options.max_rows if options and options.max_rows
                     else self._config.security.max_rows)
        audit_id = self._gen_audit_id()
        start = time.perf_counter()

        try:
            with self._conn_mgr.engine.connect() as conn:
                stmt = text(sql_template)
                result = conn.execute(stmt, params)
                columns = list(result.keys())
                rows = [list(row) for row in result.fetchmany(max_rows)]
                truncated = len(rows) >= max_rows

            duration_ms = int((time.perf_counter() - start) * 1000)
            qr = QueryResult(
                columns=columns,
                rows=rows,
                row_count=len(rows),
                truncated=truncated,
                duration_ms=duration_ms,
                audit_id=audit_id,
            )
            self._audit.log(self._make_audit_entry(
                sql_template, params, len(rows), duration_ms, "ok"
            ))
            return qr

        except Exception as e:
            duration_ms = int((time.perf_counter() - start) * 1000)
            self._audit.log(self._make_audit_entry(
                sql_template, params, 0, duration_ms, f"error: {e}"
            ))
            raise

    # ------------------------------------------------------------------
    # 写操作
    # ------------------------------------------------------------------

    def _execute_write(
        self, sql_template: str, params: list | None, op_name: str,
    ) -> WriteResult:
        params = params or []
        self._check_guard(sql_template)
        audit_id = self._gen_audit_id()
        start = time.perf_counter()

        try:
            with self._conn_mgr.engine.begin() as conn:
                stmt = text(sql_template)
                result = conn.execute(stmt, params)
                affected = result.rowcount
                last_id = result.lastrowid if hasattr(result, "lastrowid") else None

            duration_ms = int((time.perf_counter() - start) * 1000)
            wr = WriteResult(
                affected_rows=affected,
                last_insert_id=last_id,
                duration_ms=duration_ms,
                audit_id=audit_id,
            )
            self._audit.log(self._make_audit_entry(
                sql_template, params, affected, duration_ms, "ok"
            ))
            return wr

        except Exception as e:
            duration_ms = int((time.perf_counter() - start) * 1000)
            self._audit.log(self._make_audit_entry(
                sql_template, params, 0, duration_ms, f"error: {e}"
            ))
            raise

    def insert(self, sql_template: str, params: list | None = None) -> WriteResult:
        return self._execute_write(sql_template, params, "INSERT")

    def update(self, sql_template: str, params: list | None = None) -> WriteResult:
        return self._execute_write(sql_template, params, "UPDATE")

    def delete(self, sql_template: str, params: list | None = None) -> WriteResult:
        return self._execute_write(sql_template, params, "DELETE")

    # ------------------------------------------------------------------
    # 健康检查
    # ------------------------------------------------------------------

    def ping(self) -> HealthStatus:
        start = time.perf_counter()
        ok = self._conn_mgr.ping()
        latency = int((time.perf_counter() - start) * 1000)
        return HealthStatus(
            status="ok" if ok else "error",
            database=self._config.data_source.database,
            latency_ms=latency,
        )

    # ------------------------------------------------------------------
    # 表结构导入
    # ------------------------------------------------------------------

    def import_schema(self, tables: list[str] | None = None) -> SchemaSnapshot:
        return self._schema_importer.import_schema(tables)

    def refresh_schema(self) -> SchemaSnapshot:
        return self._schema_importer.refresh_schema()

    # ------------------------------------------------------------------
    # 资源清理
    # ------------------------------------------------------------------

    def dispose(self) -> None:
        self._conn_mgr.dispose()
