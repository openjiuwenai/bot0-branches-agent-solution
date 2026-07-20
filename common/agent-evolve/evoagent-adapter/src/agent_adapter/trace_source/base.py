"""TraceSource 抽象 —— 轨迹获取双模式契约 (log/standard, 设计文档 §5)。

routes 注入 TraceSource, 不再直接读归档; 两子类产出同一种 record 格式
(LogTraceSource 读 JSONL 归档; DbTraceSource 读 PG 经 spans_to_records 转换),
下游 trace_assembler / trace_cleaner 零改动 → 三个轨迹 API 契约不变。
"""

from __future__ import annotations

from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class TraceSource(Protocol):
    """轨迹获取契约。record 格式见 trace_source.spans_to_records / 现 archive。"""

    async def list_conversations(self, agent_name: str | None = None) -> list[str]:
        """列出会话 ID (agent_name=None 列全部; log 模式按 output_dir, standard 按 traces 表)。"""
        ...

    async def get_records(self, agent_name: str | None, conversation_id: str) -> list[dict[str, Any]]:
        """取一个会话的全部 records (供 /traces 原样返回 + /cleaned-traces 经 clean_traces)。"""
        ...
