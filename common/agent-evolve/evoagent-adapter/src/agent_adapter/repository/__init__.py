"""数据库操作层 (TraceRepository 契约) + 接入层 (per-DB adapter)。"""

from agent_adapter.repository.base import TraceRepository

__all__ = ["TraceRepository"]
