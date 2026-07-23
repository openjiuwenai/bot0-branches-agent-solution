"""OTLP JSON 信封 → 扁平 per-span dict 解析器。

kafka 上的 otlp_traces topic 承载的是 OTLP JSON 信封:
    resourceSpans[].scopeSpans[].spans[]
span.attributes / resource.attributes 是 anyvalue 数组, 时间是 unix nano, kind 是 int 枚举。

本模块摊平成 adapter 内部统一形状（由 synthetic span fixtures 验证）:
    trace_id/span_id/parent_span_id/trace_state/name/kind(字符串)/start_time(ISO)/end_time(ISO)/
    duration_ns/service_name/scope_name/scope_version/status_code(字符串)/status_message/
    attributes(dict)/resource_attributes(dict)/events(list)/links(list)/conversation_id

纯函数, 无 I/O, 便于单测 (用 jsonl 反构 OTLP 往返验证)。
参考: 原 trace-pg-sink compose/collector/sink/consumer.py 的 spans_from/scalar/attrs_to_dict。
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

# OTLP Span.kind 枚举 (数字 → 名), 见 OTLP 规范
_KIND_BY_INT = {
    0: "UNSPECIFIED",
    1: "INTERNAL",
    2: "SERVER",
    3: "CLIENT",
    4: "PRODUCER",
    5: "CONSUMER",
}

# OTLP Status.code 枚举 (数字 → 名)
_STATUS_BY_INT = {0: "UNSET", 1: "OK", 2: "ERROR"}


def scalar(value: Any) -> Any:
    """从 OTLP anyvalue 里取出标量; 数组/kvlist 递归取, 取不到原样返回。"""
    if not isinstance(value, dict):
        return value
    for k in ("stringValue", "boolValue", "doubleValue"):
        if k in value:
            return value[k]
    if "intValue" in value:  # OTLP JSON 里 intValue 是字符串
        try:
            return int(value["intValue"])
        except (TypeError, ValueError):
            return value["intValue"]
    if "arrayValue" in value:
        return [scalar(v) for v in value["arrayValue"].get("values", [])]
    if "kvlistValue" in value:
        return {kv.get("key"): scalar(kv.get("value", {})) for kv in value["kvlistValue"].get("values", [])}
    return value


def attrs_to_dict(attrs: list[dict] | None) -> dict[str, Any]:
    """OTLP attributes 数组 [{key, value: anyvalue}] → {key: scalar}。"""
    out: dict[str, Any] = {}
    for a in attrs or []:
        k = a.get("key")
        if k is not None:
            out[k] = scalar(a.get("value", {}))
    return out


def _to_int(v: Any) -> int | None:
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def _nano_to_iso(nano: int | None) -> str | None:
    """unix 纳秒 → ISO 8601 (UTC, 带 +00:00)。None → None。"""
    if nano is None:
        return None
    seconds = nano // 1_000_000_000
    micros = (nano % 1_000_000_000) // 1000
    dt = datetime.fromtimestamp(seconds, tz=timezone.utc).replace(microsecond=micros)
    return dt.isoformat()


def _events_to_list(events: list[dict] | None) -> list[dict[str, Any]]:
    """OTLP Event[] → 简化 dict 列表 (time/name/attributes)。"""
    out: list[dict[str, Any]] = []
    for ev in events or []:
        out.append({
            "time": _nano_to_iso(_to_int(ev.get("timeUnixNano"))),
            "name": ev.get("name", ""),
            "attributes": attrs_to_dict(ev.get("attributes")),
        })
    return out


def _links_to_list(links: list[dict] | None) -> list[dict[str, Any]]:
    """OTLP Link[] → 简化 dict 列表。"""
    out: list[dict[str, Any]] = []
    for ln in links or []:
        out.append({
            "trace_id": ln.get("traceId", ""),
            "span_id": ln.get("spanId", ""),
            "attributes": attrs_to_dict(ln.get("attributes")),
        })
    return out


def parse_span(
    span: dict,
    *,
    service_name: str | None,
    resource_attrs: dict[str, Any],
    scope_name: str | None,
    scope_version: str | None,
) -> dict[str, Any]:
    """单个 OTLP span dict → 扁平 span dict (adapter 内部统一形状)。"""
    attrs = attrs_to_dict(span.get("attributes"))
    status = span.get("status") or {}
    start_nano = _to_int(span.get("startTimeUnixNano"))
    end_nano = _to_int(span.get("endTimeUnixNano"))
    duration = (end_nano - start_nano) if (start_nano is not None and end_nano is not None) else None
    kind_int = _to_int(span.get("kind"))
    status_int = _to_int(status.get("code"))
    return {
        "trace_id": span.get("traceId", ""),
        "span_id": span.get("spanId", ""),
        "parent_span_id": span.get("parentSpanId", "") or "",
        "trace_state": span.get("traceState", "") or "",
        "name": span.get("name", ""),
        "kind": _KIND_BY_INT.get(kind_int, "UNSPECIFIED"),
        "start_time": _nano_to_iso(start_nano),
        "end_time": _nano_to_iso(end_nano),
        "duration_ns": duration,
        "service_name": service_name,
        "scope_name": scope_name,
        "scope_version": scope_version,
        "status_code": _STATUS_BY_INT.get(status_int, "UNSET"),
        "status_message": status.get("message", "") or "",
        "attributes": attrs,
        "resource_attributes": resource_attrs,
        "events": _events_to_list(span.get("events")),
        "links": _links_to_list(span.get("links")),
        # 提升字段: 供轨迹 API 查询/根 span 判定
        "conversation_id": attrs.get("session.id"),
    }


def parse_otlp_envelope(message: bytes | str) -> list[dict[str, Any]]:
    """OTLP JSON 信封 (kafka 消息) → 扁平 span dict 列表。

    信封结构: {"resourceSpans": [{"resource": {"attributes": [...]}, "scopeSpans": [{"scope": {...}, "spans": [...]}]}]}
    """
    if isinstance(message, (bytes, bytearray)):
        message = message.decode("utf-8")
    data = json.loads(message)
    rows: list[dict[str, Any]] = []
    for rs in data.get("resourceSpans", []):
        resource_attrs = attrs_to_dict((rs.get("resource") or {}).get("attributes"))
        service_name = resource_attrs.get("service.name")
        for ss in rs.get("scopeSpans", []):
            scope = ss.get("scope") or {}
            scope_name = scope.get("name")
            scope_version = scope.get("version", "") or ""
            for span in ss.get("spans", []):
                rows.append(parse_span(
                    span,
                    service_name=service_name,
                    resource_attrs=resource_attrs,
                    scope_name=scope_name,
                    scope_version=scope_version,
                ))
    return rows
