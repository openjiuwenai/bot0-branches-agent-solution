"""轨迹获取抽象层 —— log (读归档) / standard (读 DB) 双模式, 配置驱动 (设计文档 §5)。

两类 TraceSource 产出同一种 record 格式 (spans_to_records 转换), 下游
trace_assembler / trace_cleaner 零改动 → 三个轨迹 API 契约不变。
"""

from agent_adapter.trace_source.base import TraceSource

__all__ = ["TraceSource"]
