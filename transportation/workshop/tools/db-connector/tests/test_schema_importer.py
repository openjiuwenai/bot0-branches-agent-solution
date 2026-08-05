"""SchemaImporter 单元测试 — 表结构反射。"""

import json
from pathlib import Path

import pytest
from sqlalchemy import create_engine, Column, Integer, String, MetaData, Table

from openjiuwen.tools.db_connector.config import SchemaImportConfig
from openjiuwen.tools.db_connector.schema.schema_importer import (
    SchemaImporter,
    SchemaSnapshot,
)


@pytest.fixture
def sqlite_engine(tmp_path):
    """创建 SQLite 内存库并建表。"""
    engine = create_engine(f"sqlite:///{tmp_path}/test.db")
    metadata = MetaData()
    Table(
        "traffic_flow",
        metadata,
        Column("id", Integer, primary_key=True),
        Column("station_id", String(32), nullable=False),
        Column("flow", Integer),
        Column("phone", String(20)),
    )
    metadata.create_all(engine)
    return engine


class TestSchemaImporter:
    def test_import_schema(self, sqlite_engine, tmp_path):
        config = SchemaImportConfig(
            allowed_tables=["traffic_flow"],
            sensitive_columns=["phone"],
            snapshot_path=str(tmp_path / "snapshot.json"),
        )
        importer = SchemaImporter(sqlite_engine, config)
        snapshot = importer.import_schema()

        assert len(snapshot.tables) == 1
        table = snapshot.tables[0]
        assert table.name == "traffic_flow"
        assert len(table.columns) == 4

        # 检查主键标注
        id_col = next(c for c in table.columns if c.name == "id")
        assert id_col.primary_key is True

        # 检查敏感列标注
        phone_col = next(c for c in table.columns if c.name == "phone")
        assert phone_col.sensitive is True

    def test_snapshot_persisted(self, sqlite_engine, tmp_path):
        snapshot_path = tmp_path / "snapshot.json"
        config = SchemaImportConfig(
            allowed_tables=["traffic_flow"],
            snapshot_path=str(snapshot_path),
        )
        importer = SchemaImporter(sqlite_engine, config)
        importer.import_schema()

        assert snapshot_path.exists()
        data = json.loads(snapshot_path.read_text(encoding="utf-8"))
        assert "tables" in data

    def test_snapshot_from_json(self, tmp_path):
        snapshot_path = tmp_path / "snapshot.json"
        snapshot_path.write_text(
            json.dumps({
                "tables": [{
                    "name": "test_table",
                    "comment": "",
                    "columns": [{
                        "name": "id",
                        "type": "INTEGER",
                        "nullable": False,
                        "primary_key": True,
                        "comment": "",
                        "sensitive": False,
                    }],
                }]
            }),
            encoding="utf-8",
        )
        snapshot = SchemaSnapshot.from_json(snapshot_path)
        assert len(snapshot.tables) == 1
        assert snapshot.tables[0].name == "test_table"
        assert snapshot.tables[0].columns[0].primary_key is True

    def test_refresh_schema(self, sqlite_engine, tmp_path):
        config = SchemaImportConfig(
            allowed_tables=["traffic_flow"],
            snapshot_path=str(tmp_path / "snapshot.json"),
        )
        importer = SchemaImporter(sqlite_engine, config)
        importer.import_schema()
        refreshed = importer.refresh_schema()
        assert len(refreshed.tables) == 1
