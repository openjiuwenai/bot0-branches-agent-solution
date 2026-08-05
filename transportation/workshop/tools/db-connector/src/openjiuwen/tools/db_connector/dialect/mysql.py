"""MySQL 方言。"""

from __future__ import annotations

from .base import Dialect


class MySqlDialect(Dialect):
    """MySQL 方言（PyMySQL 驱动）。"""

    @property
    def placeholder(self) -> str:
        return "%s"

    @property
    def driver_name(self) -> str:
        return "mysql"

    def build_url(self, host: str, port: int, database: str, username: str, password: str) -> str:
        return f"mysql+pymysql://{username}:{password}@{host}:{port}/{database}"

    def quote_identifier(self, identifier: str) -> str:
        return f"`{identifier}`"
