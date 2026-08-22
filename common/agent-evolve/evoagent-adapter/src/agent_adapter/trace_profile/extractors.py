"""字段提取 —— 单字段提取 + 多字段拼接，支持 json/python_repr 两种格式。

设计约束（grill-with-docs 确认）：
- extract_response 产出的 assistant message 需兼容 trace_cleaner：
  output 必须是 {"role":"assistant","content":"..."} 格式的 dict，
  role=assistant 在 trace_cleaner 的保留列表中，额外字段（toolCalls/reasoning）被保留。
"""

from __future__ import annotations

import json
from typing import Any

from agent_adapter.trace_profile.models import FieldExtraction, MultiFieldExtraction
from agent_adapter.trace_source.repr_extract import extract_value


def extract_field(attrs: dict[str, Any], spec: FieldExtraction) -> Any:
    """从 span attributes 提取单个字段。

    1. 取 attrs[spec.attr]（字符串或已结构化）
    2. 如果是字符串：json 格式 → json.loads；python_repr 格式 → repr_extract
    3. 如果有 sub_key：取结果的 [sub_key]
    """
    raw = attrs.get(spec.attr)
    if raw is None:
        return None
    if isinstance(raw, str):
        if spec.format == "json":
            try:
                raw = json.loads(raw)
            except json.JSONDecodeError:
                return raw
        elif spec.format == "python_repr":
            raw = extract_value(raw)
    if spec.sub_key and isinstance(raw, dict):
        return raw.get(spec.sub_key)
    return raw


def extract_response(span: dict[str, Any], spec: MultiFieldExtraction) -> dict[str, Any] | None:
    """多字段拼接提取 response → assistant message。

    单字段：value 已是 message dict (如 EDPAgent 的 outputs) → 原样返回 (不二次包裹, 与 legacy 一致);
            value 是裸文本 (如单 text 字段) → 包成 {"role":"assistant","content":value}
    多字段：拼成 {"role": "assistant", "content": text, "toolCalls": [...], "reasoning": "..."}

    输出格式兼容 trace_cleaner：role=assistant 在保留列表中，
    额外字段（toolCalls、reasoning）被保留不影响清洗逻辑。
    """
    attrs = span.get("attributes") or {}
    if len(spec.fields) == 1:
        value = extract_field(attrs, spec.fields[0])
        if value is None:
            return None
        # value 已是 message dict → 原样返回 (避免二次包裹成 {role,content:{role,content:...}});
        # 裸文本 → 包成 assistant message (兼容 trace_cleaner)。
        if isinstance(value, dict):
            return value
        return {"role": "assistant", "content": value}
    parts: dict[str, Any] = {}
    for f in spec.fields:
        value = extract_field(attrs, f)
        if value is not None:
            parts[f.sub_key or f.attr] = value
    if not parts:
        return None
    content = parts.pop("text", "")
    return {"role": "assistant", "content": content, **parts}