"""表结构导入/反射 — 通过 SQLAlchemy inspect() 读取已有表结构。"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from sqlalchemy import inspect
from sqlalchemy.engine import Engine

from ..config import SchemaImportConfig


@dataclass
class ColumnInfo:
    name: str
    type: str
    nullable: bool = True
    primary_key: bool = False
    comment: str = ""
    sensitive: bool = False  # 是否为敏感列（需脱敏）


@dataclass
class TableInfo:
    name: str
    columns: list[ColumnInfo] = field(default_factory=list)
    comment: str = ""


@dataclass
class SchemaSnapshot:
    """表结构快照。"""
    tables: list[TableInfo] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "tables": [
                {
                    "name": t.name,
                    "comment": t.comment,
                    "columns": [
                        {
                            "name": c.name,
                            "type": c.type,
                            "nullable": c.nullable,
                            "primary_key": c.primary_key,
                            "comment": c.comment,
                            "sensitive": c.sensitive,
                        }
                        for c in t.columns
                    ],
                }
                for t in self.tables
            ]
        }

    def to_json(self, path: str | Path) -> None:
        """保存快照到 JSON 文件。"""
        Path(path).write_text(
            json.dumps(self.to_dict(), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    @classmethod
    def from_json(cls, path: str | Path) -> "SchemaSnapshot":
        """从 JSON 文件加载快照。"""
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        tables = []
        for t in data.get("tables", []):
            cols = [
                ColumnInfo(
                    name=c["name"],
                    type=c["type"],
                    nullable=c.get("nullable", True),
                    primary_key=c.get("primary_key", False),
                    comment=c.get("comment", ""),
                    sensitive=c.get("sensitive", False),
                )
                for c in t.get("columns", [])
            ]
            tables.append(TableInfo(name=t["name"], columns=cols, comment=t.get("comment", "")))
        return cls(tables=tables)


class SchemaImporter:
    """表结构导入器 — 反射已有表并生成快照。"""

    def __init__(self, engine: Engine, config: SchemaImportConfig) -> None:
        self._engine = engine
        self._config = config
        self._snapshot: SchemaSnapshot | None = None

    def import_schema(self, tables: list[str] | None = None) -> SchemaSnapshot:
        """导入表结构。

        Args:
            tables: 指定表名列表；为 None 时使用配置中的 allowed_tables。
        """
        target_tables = tables or self._config.allowed_tables
        inspector = inspect(self._engine)

        result = SchemaSnapshot()
        all_tables = set(inspector.get_table_names())

        for table_name in target_tables:
            if table_name not in all_tables:
                continue

            cols = inspector.get_columns(table_name)
            pk = set(inspector.get_pk_constraint(table_name).get("constrained_columns", []))

            col_infos = []
            for col in cols:
                col_infos.append(
                    ColumnInfo(
                        name=col["name"],
                        type=str(col["type"]),
                        nullable=col.get("nullable", True),
                        primary_key=col["name"] in pk,
                        comment=col.get("comment", "") or "",
                        sensitive=col["name"] in self._config.sensitive_columns,
                    )
                )

            result.tables.append(TableInfo(name=table_name, columns=col_infos))

        # 缓存
        self._snapshot = result

        # 持久化快照
        if self._config.snapshot_path:
            result.to_json(self._config.snapshot_path)

        return result

    def refresh_schema(self) -> SchemaSnapshot:
        """刷新表结构缓存。"""
        return self.import_schema()

    @property
    def snapshot(self) -> SchemaSnapshot | None:
        return self._snapshot
