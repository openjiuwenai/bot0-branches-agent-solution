"""dialect 子包 — 数据库方言抽象。"""

from .base import Dialect
from .mysql import MySqlDialect
from .postgresql import PostgreSqlDialect

__all__ = ["Dialect", "MySqlDialect", "PostgreSqlDialect", "create_dialect"]


def create_dialect(db_type: str) -> Dialect:
    """根据数据库类型创建方言实例。"""
    dialects = {
        "mysql": MySqlDialect,
        "postgresql": PostgreSqlDialect,
    }
    cls = dialects.get(db_type.lower())
    if cls is None:
        raise ValueError(f"不支持的数据库类型: {db_type}")
    return cls()
