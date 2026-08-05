"""core 子包 — 连接池管理与结果映射。"""

from .connection_manager import ConnectionManager
from .result_mapper import QueryResult, WriteResult, HealthStatus

__all__ = ["ConnectionManager", "QueryResult", "WriteResult", "HealthStatus"]
