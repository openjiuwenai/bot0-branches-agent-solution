"""
EDPAgent — 企业动态规划智能体。

公开接口：
  from agents.EDPAgent import initialize, agent_stream

零 A2A 依赖：本模块不依赖 a2a-sdk，可在任意 Python 环境中使用。
"""
from __future__ import annotations

from typing import Any


async def initialize(*args: Any, **kwargs: Any) -> Any:
    from .agent import initialize_dpa

    return await initialize_dpa(*args, **kwargs)


async def agent_stream(*args: Any, **kwargs: Any):
    from .agent import agent_stream as _agent_stream

    async for event in _agent_stream(*args, **kwargs):
        yield event

__all__ = ["initialize", "agent_stream"]
