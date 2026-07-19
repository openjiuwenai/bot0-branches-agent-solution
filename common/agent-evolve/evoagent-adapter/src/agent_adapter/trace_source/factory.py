"""TraceSource 工厂 —— 按 config.TRACE_SOURCE 选 log/standard 子类 (设计文档 §5)。

config 属性 > ADAPTER_* env > 默认 (log, 保持现行为)。
- log:      LogTraceSource (output_dirs 由调用方/task8 从 pipelines 构建)
- standard: DbTraceSource (需传入已启动的 TraceRepository)
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from agent_adapter.repository.base import TraceRepository
from agent_adapter.trace_source.base import TraceSource
from agent_adapter.trace_source.db_source import DbTraceSource
from agent_adapter.trace_source.log_source import LogTraceSource


def _cfg(config: Any, attr: str, env: str, default: str) -> str:
    val = getattr(config, attr, None)
    if val not in (None, ""):
        return str(val)
    env_val = os.environ.get(env)
    if env_val not in (None, ""):
        return env_val
    return default


def make_trace_source(
    config: Any = None,
    *,
    output_dirs: dict[str, Path] | None = None,
    repo: TraceRepository | None = None,
) -> TraceSource:
    """按 TRACE_SOURCE 选子类。

    Args:
        config: 带 ``trace_source`` 属性的对象 (如 AdapterConfig); None 走环境变量。
        output_dirs: log 模式所需 agent_name → output_dir 映射。
        repo: standard 模式所需 TraceRepository (须已 start)。
    """
    mode = _cfg(config, "trace_source", "ADAPTER_TRACE_SOURCE", "log")
    if mode == "standard":
        if repo is None:
            raise ValueError("standard 模式需传入 repo (TraceRepository)")
        return DbTraceSource(repo)
    if mode == "log":
        return LogTraceSource(output_dirs)
    raise ValueError(f"不支持的 TRACE_SOURCE={mode!r} (支持 log|standard)")
