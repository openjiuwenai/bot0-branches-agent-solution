"""配置加载与数据模型 — 基于 pydantic-settings 读取 YAML 并校验。"""

from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# 配置模型
# ---------------------------------------------------------------------------

class PoolConfig(BaseModel):
    max_pool_size: int = 10
    min_idle: int = 2
    connection_timeout_ms: int = 5000


class DataSourceConfig(BaseModel):
    type: str = "mysql"  # mysql | postgresql
    host: str = "localhost"
    port: int = 3306
    database: str = ""
    username: str = ""
    password: str = ""
    schema: str = "public"
    pool: PoolConfig = Field(default_factory=PoolConfig)


class VaultConfig(BaseModel):
    addr: str = ""
    mount: str = "database"
    path: str = ""
    role: str = ""


class CredentialConfig(BaseModel):
    provider: str = "env"  # env | vault
    vault: VaultConfig = Field(default_factory=VaultConfig)


class SchemaImportConfig(BaseModel):
    enabled: bool = True
    allowed_tables: list[str] = Field(default_factory=list)
    sensitive_columns: list[str] = Field(default_factory=list)
    snapshot_path: str = "./schema-snapshot.json"


class SecurityConfig(BaseModel):
    allowed_tables: list[str] = Field(default_factory=list)
    blocked_keywords: list[str] = Field(
        default_factory=lambda: [
            "DROP", "TRUNCATE", "SHUTDOWN", "GRANT", "REVOKE",
            "LOAD_FILE", "INTO OUTFILE", "xp_cmdshell",
        ]
    )
    max_rows: int = 10000
    query_timeout_ms: int = 30000
    allow_ddl: bool = False
    sql_template_whitelist: bool = False


class AuditConfig(BaseModel):
    enabled: bool = True
    sink: str = "file"  # file | logger | kafka
    path: str = "./logs/db-connector-audit.log"


class McpConfig(BaseModel):
    enabled: bool = True
    transport: str = "stdio"  # stdio | sse | streamable-http
    name: str = "db-connector"
    version: str = "0.1.0"


class DbConnectorConfig(BaseModel):
    enabled: bool = True
    mode: str = "readonly"  # readonly | readwrite | ddl
    data_source: DataSourceConfig = Field(default_factory=DataSourceConfig)
    credential: CredentialConfig = Field(default_factory=CredentialConfig)
    schema_import: SchemaImportConfig = Field(default_factory=SchemaImportConfig)
    security: SecurityConfig = Field(default_factory=SecurityConfig)
    audit: AuditConfig = Field(default_factory=AuditConfig)
    mcp: McpConfig = Field(default_factory=McpConfig)


# ---------------------------------------------------------------------------
# 占位符解析（env: / vault:）
# ---------------------------------------------------------------------------

_PLACEHOLDER_RE = re.compile(r"^(env|vault):(.+)$")


def resolve_placeholder(value: str) -> str:
    """解析 env:/vault: 前缀的占位符，返回实际值。

    - ``env:VAR_NAME``  → 读取环境变量
    - ``vault:path#key`` → 标记为 vault 引用（实际解密由 CredentialProvider 处理）
    - 其他原样返回
    """
    if not isinstance(value, str):
        return value
    m = _PLACEHOLDER_RE.match(value)
    if not m:
        return value
    prefix, ref = m.group(1), m.group(2)
    if prefix == "env":
        return os.environ.get(ref, "")
    # vault: 由 CredentialProvider 在运行期解析
    return value


def _resolve_dict(d: dict[str, Any]) -> dict[str, Any]:
    """递归解析 dict 中所有字符串值的占位符。"""
    resolved: dict[str, Any] = {}
    for k, v in d.items():
        if isinstance(v, str):
            resolved[k] = resolve_placeholder(v)
        elif isinstance(v, dict):
            resolved[k] = _resolve_dict(v)
        else:
            resolved[k] = v
    return resolved


# ---------------------------------------------------------------------------
# 加载入口
# ---------------------------------------------------------------------------

def load_config(path: str | Path | None = None) -> DbConnectorConfig:
    """从 YAML 文件加载配置；env: 占位符在加载时即解析为实际值。

    vault: 占位符保留，由 CredentialProvider 运行期解析。
    """
    if path is None:
        return DbConnectorConfig()

    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    # 定位到 openjiuwen.tools.db-connector
    node = raw
    for key in ("openjiuwen", "tools", "db-connector"):
        if not isinstance(node, dict) or key not in node:
            return DbConnectorConfig()
        node = node[key]

    resolved = _resolve_dict(node)
    return DbConnectorConfig(**resolved)
