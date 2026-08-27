"""span 过滤 —— ingest 粗过滤 + query 细过滤 + service.name 自动路由。"""

from __future__ import annotations

import logging
from typing import Any, Literal

from agent_adapter.trace_profile.models import SpanFilter, TraceProfile

if __debug__:
    from agent_adapter.trace_profile.loader import ProfileRegistry

logger = logging.getLogger(__name__)


def span_matches_filter(span: dict[str, Any], flt: SpanFilter) -> bool:
    """判断一个 span 是否通过过滤规则。

    黑名单优先：exclude 先于 include 判断。
    有白名单且未命中 → 拒绝；无白名单 → 默认放行。
    """
    name = span.get("name", "")
    for prefix in flt.exclude_prefixes:
        if name.startswith(prefix):
            return False
    for exact in flt.exclude_names:
        if name == exact:
            return False
    for prefix in flt.include_prefixes:
        if name.startswith(prefix):
            return True
    for exact in flt.include_names:
        if name == exact:
            return True
    if flt.include_prefixes or flt.include_names:
        return False
    return True


def filter_spans_by_service(
    spans: list[dict[str, Any]],
    registry: ProfileRegistry,
    layer: Literal["ingest", "query"],
) -> list[dict[str, Any]]:
    """按 service.name 自动路由到对应 profile，执行过滤。

    一条 Kafka 消息可能含多 Agent span：
    - 有匹配 profile 的 span：按对应 profile 的 filter 过滤（被 exclude 命中才丢）。
    - 无匹配 profile 的 span（未知 agent / 未配 profile / 语言不匹配）：fail-open 保留
      (ingest 也不丢，避免升级后静默丢未知 agent 的数据；query 同样保留)，仅记 warning。
    """
    result: list[dict[str, Any]] = []
    warned: set[str] = set()
    for span in spans:
        service_name = span.get("service_name", "")
        # telemetry.sdk.language（扁平化后在 resource_attributes）做 Python/Java 消歧；
        # 缺省（无 resource_attributes / 无该键）→ language=None，走 service.name 单候选回退。
        language = (span.get("resource_attributes") or {}).get("telemetry.sdk.language")
        profile = registry.get_by_service_name(service_name, language=language or None)
        if profile is None:
            # fail-open：无匹配 profile 的 span 保留（ingest 也不丢），避免升级后静默丢未知 agent；
            # 仅"有 profile 但被 exclude 规则过滤"的 span 才丢。空 service_name 不告警（避免噪声）。
            if layer == "ingest" and service_name and service_name not in warned:
                warned.add(service_name)
                logger.warning(
                    "ingest_span_no_profile_kept_failopen service_name=%s", service_name
                )
            result.append(span)
            continue
        flt = profile.ingest_filter if layer == "ingest" else profile.query_filter
        if span_matches_filter(span, flt):
            result.append(span)
    return result