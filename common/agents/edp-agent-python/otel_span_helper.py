"""OTel Span 创建工具模块。

功能：封装 EDPAgent 中 SDK 不感知的应用层事件 span 创建逻辑，
通过 context manager 保证 span 闭环（start/end 配对）。

tracer 来源：由 _register_otel_tracer() 调用 _set_tracer() 注入 SDK 创建的
tracer 引用，保证与 SDK 的 OtelRail 创建的 span 在同一个 TracerProvider 下，
parent-child 关系自动建立。
"""
from __future__ import annotations

from contextlib import contextmanager
from typing import Optional

_tracer = None        # 初始为 None，由 _register_otel_tracer() 注入
_OTEL_AVAILABLE = False  # 初始为 False，注入后设为 True


def _set_tracer(tracer) -> None:
    """由 _register_otel_tracer() 调用，注入 SDK 创建的 tracer 引用。"""
    global _tracer, _OTEL_AVAILABLE
    _tracer = tracer
    _OTEL_AVAILABLE = tracer is not None


def get_tracer():
    """获取已注入的 tracer 引用，未注入时返回 None。"""
    return _tracer if _OTEL_AVAILABLE else None


@contextmanager
def start_http_request_span(method: str, route: str, session_id: str,
                            trace_id: str = "", agent_id: str = ""):
    """创建 http.request span（SERVER），记录 HTTP 请求入口。

    在 dispatch.py 的 TAG_HTTP_REQUEST_START 处调用，
    所有 return 路径（正常/异常/流式/非流式）都会通过 with 语句自动关闭 span。
    """
    if not _OTEL_AVAILABLE:
        yield None
        return
    try:
        from opentelemetry.trace import SpanKind
    except ImportError:
        yield None
        return
    with _tracer.start_as_current_span(
        "http.request", kind=SpanKind.SERVER
    ) as span:
        span.set_attribute("http.request.method", method)
        span.set_attribute("http.route", route)
        span.set_attribute("session.id", session_id)
        if trace_id:
            span.set_attribute("openjiuwen.trace.id", trace_id)
        if agent_id:
            span.set_attribute("openjiuwen.agent.name", agent_id)
        yield span


@contextmanager
def start_versatile_adapter_span(
    query_intent: str,
    query_description: str,
    session_id: str,
    dispatch_mode: str = "single",
    workflow_id: str = "",
    target_agent: str = "",
    sub_task_path: str = "",
):
    """创建 service.versatile_adapter span（CLIENT），记录 VA 调用。

    单次调用（_call_versatile_adapter）和工作流并行调度（_drive_workflow_va）
    统一使用此函数，通过 dispatch_mode 区分：
    - dispatch_mode="single"：单次 VA 调用
    - dispatch_mode="parallel"：工作流并行调度
    """
    if not _OTEL_AVAILABLE:
        yield None
        return
    try:
        from opentelemetry.trace import SpanKind
    except ImportError:
        yield None
        return
    with _tracer.start_as_current_span(
        "service.versatile_adapter", kind=SpanKind.CLIENT
    ) as span:
        span.set_attribute("openjiuwen.va.dispatch_mode", dispatch_mode)
        span.set_attribute("openjiuwen.va.query_intent", query_intent)
        span.set_attribute("openjiuwen.va.query_description", query_description)
        span.set_attribute("session.id", session_id)
        if workflow_id:
            span.set_attribute("openjiuwen.va.workflow_id", workflow_id)
        if target_agent:
            span.set_attribute("openjiuwen.va.target_agent", target_agent)
        if sub_task_path:
            span.set_attribute("openjiuwen.va.sub_task_path", sub_task_path)
        yield span


@contextmanager
def start_sub_agent_dispatch_span(
    entity_id: str,
    entity_name: str,
    query: str,
    sub_agent_url: str,
    sub_task_path: str,
    context_id: str,
    session_id: str,
):
    """创建 sub_agent.dispatch span（CLIENT），记录子 Agent 调用。

    在 remote_agent_handler.py 的 _drive_sub_agent 入口处调用，
    并行调度时每个子 Agent 独立创建 span（asyncio.gather 内调用）。
    """
    if not _OTEL_AVAILABLE:
        yield None
        return
    try:
        from opentelemetry.trace import SpanKind
    except ImportError:
        yield None
        return
    with _tracer.start_as_current_span(
        "sub_agent.dispatch", kind=SpanKind.CLIENT
    ) as span:
        span.set_attribute("openjiuwen.subagent.entity_id", entity_id)
        span.set_attribute("openjiuwen.subagent.entity_name", entity_name)
        span.set_attribute("openjiuwen.subagent.query", query)
        span.set_attribute("openjiuwen.subagent.sub_agent_url", sub_agent_url)
        span.set_attribute("openjiuwen.subagent.sub_task_path", sub_task_path)
        span.set_attribute("openjiuwen.subagent.context_id", context_id)
        span.set_attribute("session.id", session_id)
        yield span
