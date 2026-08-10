"""PostgreSQL 方言。"""

from __future__ import annotations

from .base import Dialect


class PostgreSqlDialect(Dialect):
    """PostgreSQL 方言（psycopg 驱动）。"""

    @property
    def placeholder(self) -> str:
        return "%s"

    @property
    def driver_name(self) -> str:
        return "postgresql"

    def build_url(self, host: str, port: int, database: str, username: str, password: str) -> str:
        return f"postgresql+psycopg://{username}:{password}@{host}:{port}/{database}"

    def quote_identifier(self, identifier: str) -> str:
        return f'"{identifier}"'
