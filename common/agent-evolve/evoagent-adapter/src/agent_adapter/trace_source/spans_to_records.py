"""OTel span → log-mode archive record 转换器 (纯函数, 无 I/O)。

standard 模式 (DbTraceSource) 从 PG 取 spans 后用本模块转成 record, 使下游
trace_cleaner.clean_traces 零改动 —— 三个轨迹 API 契约不变 (设计文档 §5/§6)。

映射 (设计文档 §6, 对齐 EDPAgent 真实上报格式):
- llm.* span → GENERATION record
    input.messages = extract(parse(gen_ai.prompt).messages)   # V3 用 messages 键; 值是 Python repr
    output         = extract(parse(gen_ai.completion).outputs) # V3 用 outputs 键; 值是 Python repr
- tool.* span     → TOOL record
- http.request SERVER (根 span) → TRACE record (无 type 字段, id=trace_id, 对齐 trace_assembler)
- 其余 span (chain.EDPAgent / service.* / INTERNAL) → 跳过 (避免噪声, clean_traces 也用不到)

需求 4 兼容: gen_ai.prompt/completion 的值是 EDPAgent 对象 repr (SystemMessage(...) /
ToolCall(...) 等), 非 JSON。repr_extract.parse_repr 把其规整成原生 JSON 结构; 不可解析
(如 <object at 0x...>) 按决策 E 保留原字符串塞入。
"""

from __future__ import annotations

import json
from typing import Any

from agent_adapter.trace_source.repr_extract import extract_value


def _attrs(span: dict[str, Any]) -> dict[str, Any]:
    return span.get("attributes") or {}


def _parse_json(value: Any) -> Any:
    """字符串 → json.loads; 已是 dict/list 原样返回; 解析失败返回 None。"""
    if isinstance(value, str):
        try:
            return json.loads(value)
        except (json.JSONDecodeError, TypeError):
            return None
    return value


def _llm_to_generation(span: dict[str, Any]) -> dict[str, Any] | None:
    """llm.* span → GENERATION record; 缺 prompt/completion 返回 None (跳过)。

    决策D 统一一种: prompt 取 messages 键, completion 取 outputs 键 (对齐 V3 真实上报);
    值若为 repr 字符串经 extract_value 规整成 JSON 结构, 不可解析保留原串 (决策E)。
    """
    a = _attrs(span)
    prompt = _parse_json(a.get("gen_ai.prompt"))
    completion = _parse_json(a.get("gen_ai.completion"))
    if not isinstance(prompt, dict) or not isinstance(completion, dict):
        return None
    messages = extract_value(prompt.get("messages", []))
    output = extract_value(completion.get("outputs"))
    if not isinstance(messages, list):
        messages = [messages] if messages is not None else []
    return {
        "type": "GENERATION",
        "id": span.get("span_id"),
        "trace_id": span.get("trace_id"),
        "session_id": span.get("session_id"),
        "start_time": span.get("start_time"),
        "end_time": span.get("end_time"),
        "input": {"messages": messages},
        "output": output,
    }


def _tool_to_tool_record(span: dict[str, Any]) -> dict[str, Any]:
    """tool.* span → TOOL record (best-effort, 供 /traces raw 端点)。"""
    return {
        "type": "TOOL",
        "id": span.get("span_id"),
        "trace_id": span.get("trace_id"),
        "session_id": span.get("session_id"),
        "name": span.get("name"),
        "start_time": span.get("start_time"),
        "end_time": span.get("end_time"),
    }


def _http_to_trace_record(span: dict[str, Any]) -> dict[str, Any]:
    """http.request SERVER 根 span → TRACE record (无 type 字段, id=trace_id)。"""
    return {
        "id": span.get("trace_id"),
        "trace_id": span.get("trace_id"),
        "session_id": span.get("session_id"),
        "timestamp": span.get("start_time"),
        "start_time": span.get("start_time"),
        "end_time": span.get("end_time"),
    }


def spans_to_records(spans: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """扁平 spans → log-mode archive records (按 span start_time 升序)。

    clean_traces 只消费 GENERATION; TOOL/TRACE 仅为 /traces raw 端点保真, 不影响 cleaned。
    llm.* 按前缀匹配 (span 名形如 llm.model-sample)。
    """
    records: list[dict[str, Any]] = []
    for span in sorted(spans, key=lambda s: s.get("start_time") or ""):
        name = span.get("name", "")
        kind = span.get("kind", "")
        if name.startswith("llm."):
            rec = _llm_to_generation(span)
            if rec is not None:
                records.append(rec)
        elif name.startswith("tool."):
            records.append(_tool_to_tool_record(span))
        elif name == "http.request" and kind == "SERVER":
            records.append(_http_to_trace_record(span))
        # 其余 span 跳过
    return records
