"""方言抽象基类。"""

from __future__ import annotations

from abc import ABC, abstractmethod


class Dialect(ABC):
    """数据库方言接口 — 封装驱动差异与 SQL 占位符风格。"""

    @property
    @abstractmethod
    def placeholder(self) -> str:
        """参数占位符样式（如 %s 或 ?）。"""
        ...

    @property
    @abstractmethod
    def driver_name(self) -> str:
        """SQLAlchemy 驱动方言名（如 mysql / postgresql）。"""
        ...

    @abstractmethod
    def build_url(self, host: str, port: int, database: str, username: str, password: str) -> str:
        """构建 SQLAlchemy 连接 URL。"""
        ...

    @abstractmethod
    def quote_identifier(self, identifier: str) -> str:
        """引用标识符（如表名/列名）。"""
        ...
