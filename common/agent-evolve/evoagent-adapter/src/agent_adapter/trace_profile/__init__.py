"""trace_profile 模块 —— 多 Agent 轨迹配置化处理。

导出公共 API：
- 模型：TraceProfile, SpanFilter, FieldExtraction, MultiFieldExtraction
- 过滤：span_matches_filter, filter_spans_by_service
- 提取：extract_field, extract_response
- 加载：ProfileRegistry, load_profiles
"""

from agent_adapter.trace_profile.filters import filter_spans_by_service, span_matches_filter
from agent_adapter.trace_profile.extractors import extract_field, extract_response
from agent_adapter.trace_profile.loader import ProfileRegistry, load_profiles
from agent_adapter.trace_profile.models import (
    FieldExtraction,
    MultiFieldExtraction,
    SpanFilter,
    TraceProfile,
)

__all__ = [
    "FieldExtraction",
    "MultiFieldExtraction",
    "ProfileRegistry",
    "SpanFilter",
    "TraceProfile",
    "extract_field",
    "extract_response",
    "filter_spans_by_service",
    "load_profiles",
    "span_matches_filter",
]