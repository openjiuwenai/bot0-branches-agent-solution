"""结果映射 — 将数据库返回值映射为结构化结果。"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class QueryResult:
    """查询结果。"""
    status: str = "ok"
    columns: list[str] = field(default_factory=list)
    rows: list[list[Any]] = field(default_factory=list)
    row_count: int = 0
    truncated: bool = False
    duration_ms: int = 0
    audit_id: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "columns": self.columns,
            "rows": self.rows,
            "rowCount": self.row_count,
            "truncated": self.truncated,
            "durationMs": self.duration_ms,
            "auditId": self.audit_id,
        }


@dataclass
class WriteResult:
    """写入/更新/删除结果。"""
    status: str = "ok"
    affected_rows: int = 0
    last_insert_id: int | None = None
    duration_ms: int = 0
    audit_id: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "affectedRows": self.affected_rows,
            "lastInsertId": self.last_insert_id,
            "durationMs": self.duration_ms,
            "auditId": self.audit_id,
        }


@dataclass
class HealthStatus:
    """健康状态。"""
    status: str = "ok"
    database: str = ""
    latency_ms: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "database": self.database,
            "latencyMs": self.latency_ms,
        }
