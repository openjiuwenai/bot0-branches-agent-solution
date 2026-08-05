"""连接池管理 — 基于 SQLAlchemy QueuePool。"""

from __future__ import annotations

import logging
from typing import Any

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine
from sqlalchemy.pool import QueuePool

from ..config import DataSourceConfig
from ..dialect import create_dialect
from ..security.credential_provider import CredentialProvider, create_credential_provider
from ..config import CredentialConfig

logger = logging.getLogger(__name__)


class ConnectionManager:
    """管理数据库连接池与健康检查。"""

    def __init__(
        self,
        ds_config: DataSourceConfig,
        cred_config: CredentialConfig,
    ) -> None:
        self._ds = ds_config
        self._dialect = create_dialect(ds_config.type)
        self._cred_provider: CredentialProvider = create_credential_provider(cred_config)
        self._engine: Engine | None = None

    @property
    def dialect(self):
        return self._dialect

    @property
    def engine(self) -> Engine:
        """延迟初始化引擎。"""
        if self._engine is None:
            self._engine = self._create_engine()
        return self._engine

    def _resolve_credential(self, value: str) -> str:
        """通过凭证提供者解析占位符。"""
        return self._cred_provider.resolve(value)

    def _create_engine(self) -> Engine:
        host = self._resolve_credential(self._ds.host)
        password = self._resolve_credential(self._ds.password)
        username = self._resolve_credential(self._ds.username)
        database = self._resolve_credential(self._ds.database)

        url = self._dialect.build_url(host, self._ds.port, database, username, password)
        logger.info("创建连接池: %s@%s:%s/%s", username, host, self._ds.port, database)

        return create_engine(
            url,
            poolclass=QueuePool,
            pool_size=self._ds.pool.max_pool_size,
            pool_pre_ping=True,
            pool_recycle=3600,
            connect_args={"connect_timeout": self._ds.pool.connection_timeout_ms // 1000},
        )

    def ping(self) -> bool:
        """健康检查。"""
        try:
            with self.engine.connect() as conn:
                conn.execute(text("SELECT 1"))
            return True
        except Exception as e:
            logger.warning("健康检查失败: %s", e)
            return False

    def dispose(self) -> None:
        """释放连接池。"""
        if self._engine is not None:
            self._engine.dispose()
            self._engine = None
