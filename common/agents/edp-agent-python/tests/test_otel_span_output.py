"""UT: 验证 EDPAgent OTel span 能正确生成。

测试场景：
  1. otel_span_helper 的 3 种 context manager 能创建 span 并设置属性
  2. LogRail._emit_metric 能补充 span 属性
  3. 被 Intercept Rail 的 _create_intercepted_tool_span 能创建 tool span
"""
from __future__ import annotations

import json
from typing import Any
from unittest.mock import MagicMock, patch

import pytest


# ── 期望的 OTel span 属性 ──────────────────────────────────────────────────
EXPECTED_HTTP_ATTRIBUTES = {
    "http.request.method",
    "http.route",
    "session.id",
}
EXPECTED_VA_ATTRIBUTES = {
    "openjiuwen.va.dispatch_mode",
    "openjiuwen.va.query_intent",
    "session.id",
}
EXPECTED_SUBAGENT_ATTRIBUTES = {
    "openjiuwen.subagent.entity_id",
    "openjiuwen.subagent.entity_name",
    "session.id",
}


class _FakeSpan:
    """模拟 OTel span，记录所有 set_attribute 调用。"""

    def __init__(self):
        self._attrs: dict[str, Any] = {}
        self._ended = False

    def set_attribute(self, key, value):
        self._attrs[key] = value

    def is_recording(self):
        return not self._ended

    def end(self):
        self._ended = True


class _FakeTracer:
    """模拟 OTel tracer，返回 _FakeSpan 并支持 context manager。"""

    @staticmethod
    def start_as_current_span(name, kind=None):
        span = _FakeSpan()

        class _CM:
            def __init__(self):
                self._span = span

            def __enter__(self):
                return self._span

            def __exit__(self, *args):
                self._span.end()
                return False

        return _CM()


# ── 测试 1: otel_span_helper 3 种 span 创建 ─────────────────────────────────
class TestOtelSpanHelper:
    """验证 otel_span_helper 的 3 种 context manager 能正确创建 span 并设置属性。"""

    @staticmethod
    def setup_method():
        """每个测试前注入 fake tracer。"""
        from agents.EDPAgent import otel_span_helper
        otel_span_helper._tracer = _FakeTracer()
        otel_span_helper._OTEL_AVAILABLE = True

    @staticmethod
    def teardown_method():
        """每个测试后清理 tracer。"""
        from agents.EDPAgent import otel_span_helper
        otel_span_helper._tracer = None
        otel_span_helper._OTEL_AVAILABLE = False

    @staticmethod
    def test_http_request_span():
        """http.request span 创建 + 属性设置。"""
        from agents.EDPAgent.otel_span_helper import start_http_request_span

        with start_http_request_span(
            method="POST",
            route="/v1/test/agents/edp_agent/conversations/conv-123",
            session_id="conv-123",
            trace_id="uuid-abc",
            agent_id="edp_agent",
        ) as span:
            assert span is not None
            assert span.is_recording()

        assert not span.is_recording()  # span 已 end
        attrs = span._attrs
        assert attrs["http.request.method"] == "POST"
        assert attrs["http.route"] == "/v1/test/agents/edp_agent/conversations/conv-123"
        assert attrs["session.id"] == "conv-123"
        assert attrs["openjiuwen.trace.id"] == "uuid-abc"
        assert attrs["openjiuwen.agent.name"] == "edp_agent"

    @staticmethod
    def test_versatile_adapter_span_single():
        """service.versatile_adapter span 创建（单次 VA 调用）。"""
        from agents.EDPAgent.otel_span_helper import (
            start_versatile_adapter_span,
            VersatileSpanAttrs,
        )

        with start_versatile_adapter_span(VersatileSpanAttrs(
            query_intent="理财推荐",
            query_description="推荐理财产品",
            session_id="conv-456",
            dispatch_mode="single",
        )) as span:
            assert span is not None

        attrs = span._attrs
        assert attrs["openjiuwen.va.dispatch_mode"] == "single"
        assert attrs["openjiuwen.va.query_intent"] == "理财推荐"
        assert attrs["openjiuwen.va.query_description"] == "推荐理财产品"
        assert attrs["session.id"] == "conv-456"

    @staticmethod
    def test_versatile_adapter_span_parallel():
        """service.versatile_adapter span 创建（工作流并行调度）。"""
        from agents.EDPAgent.otel_span_helper import (
            start_versatile_adapter_span,
            VersatileSpanAttrs,
        )

        with start_versatile_adapter_span(VersatileSpanAttrs(
            query_intent="理财推荐",
            query_description="推荐理财产品",
            session_id="conv-789",
            dispatch_mode="parallel",
            workflow_id="wf-xxx",
            target_agent="versatile_adapter",
            sub_task_path="['conv-789','wf-xxx']",
        )) as span:
            assert span is not None

        attrs = span._attrs
        assert attrs["openjiuwen.va.dispatch_mode"] == "parallel"
        assert attrs["openjiuwen.va.workflow_id"] == "wf-xxx"
        assert attrs["openjiuwen.va.target_agent"] == "versatile_adapter"
        assert attrs["openjiuwen.va.sub_task_path"] == "['conv-789','wf-xxx']"

    @staticmethod
    def test_sub_agent_dispatch_span():
        """sub_agent.dispatch span 创建。"""
        from agents.EDPAgent.otel_span_helper import (
            start_sub_agent_dispatch_span,
            SubAgentDispatchSpanAttrs,
        )

        with start_sub_agent_dispatch_span(SubAgentDispatchSpanAttrs(
            entity_id="fund_agent",
            entity_name="基金理财 Agent",
            query="推荐稳健型基金",
            sub_agent_url="http://xxx/a2a",
            sub_task_path="['conv-100','fund_agent']",
            context_id="conv-100-sub-fund_agent",
            session_id="conv-100",
        )) as span:
            assert span is not None

        attrs = span._attrs
        assert attrs["openjiuwen.subagent.entity_id"] == "fund_agent"
        assert attrs["openjiuwen.subagent.entity_name"] == "基金理财 Agent"
        assert attrs["openjiuwen.subagent.query"] == "推荐稳健型基金"
        assert attrs["openjiuwen.subagent.sub_agent_url"] == "http://xxx/a2a"
        assert attrs["openjiuwen.subagent.sub_task_path"] == "['conv-100','fund_agent']"
        assert attrs["openjiuwen.subagent.context_id"] == "conv-100-sub-fund_agent"
        assert attrs["session.id"] == "conv-100"

    @staticmethod
    def test_otel_disabled_yields_none():
        """OTEL_AVAILABLE=False 时 yield None，不创建 span。"""
        from agents.EDPAgent import otel_span_helper
        from agents.EDPAgent.otel_span_helper import start_http_request_span
        otel_span_helper._OTEL_AVAILABLE = False

        with start_http_request_span(
            method="GET", route="/test", session_id="s1"
        ) as span:
            assert span is None


# ── 测试 2: LogRail._emit_metric ────────────────────────────────────────────
class TestEmitMetric:
    """验证 _emit_metric 能补充 OTel span 属性。"""

    @staticmethod
    def test_emit_metric_sets_attributes():
        """_emit_metric 设置 span 属性。"""
        from agents.EDPAgent.rail.log_rail import LogRail

        fake_span = _FakeSpan()
        import sys
        # 临时注入 opentelemetry.trace 模块
        mock_otel_trace = MagicMock()

        def get_current_span():
            return fake_span
        mock_otel_trace.get_current_span = get_current_span
        sys.modules["opentelemetry"] = MagicMock()
        sys.modules["opentelemetry.trace"] = mock_otel_trace

        LogRail._emit_metric(
            data={"openjiuwen.cost.total": 100, "session.id": "conv-x"},
            session_id="conv-x",
        )

        assert fake_span._attrs["openjiuwen.cost.total"] == 100
        assert fake_span._attrs["session.id"] == "conv-x"

        # 清理
        del sys.modules["opentelemetry"]
        del sys.modules["opentelemetry.trace"]

    @staticmethod
    def test_emit_metric_no_span():
        """无活跃 span 时 _emit_metric 不报错。"""
        from agents.EDPAgent.rail.log_rail import LogRail

        import sys
        mock_otel_trace = MagicMock()

        def get_current_span():
            return None
        mock_otel_trace.get_current_span = get_current_span
        sys.modules["opentelemetry"] = MagicMock()
        sys.modules["opentelemetry.trace"] = mock_otel_trace

        # 不应抛异常
        LogRail._emit_metric(data={"key": "value"}, session_id="s1")

        del sys.modules["opentelemetry"]
        del sys.modules["opentelemetry.trace"]


# ── 测试 3: 被 Interrupt Rail 的 _create_intercepted_tool_span ──────────────
class TestInterceptedToolSpan:
    """验证被拦截工具的 tool span 创建。"""

    @staticmethod
    def setup_method():
        """每个测试前注入 fake tracer 和 opentelemetry mock。

        rail 模块在文件头即 `from opentelemetry.trace import SpanKind`，
        必须在导入 rail 模块之前注入 mock，否则 ImportError。
        """
        import sys
        from agents.EDPAgent import otel_span_helper

        # 先注入 opentelemetry mock，再注入 tracer
        mock_otel_trace = MagicMock()
        mock_otel_trace.SpanKind = MagicMock()
        sys.modules.setdefault("opentelemetry", MagicMock())
        sys.modules.setdefault("opentelemetry.trace", mock_otel_trace)

        otel_span_helper._tracer = _FakeTracer()
        otel_span_helper._OTEL_AVAILABLE = True

    @staticmethod
    def teardown_method():
        """每个测试后清理 tracer 和 opentelemetry mock。"""
        import sys
        from agents.EDPAgent import otel_span_helper
        otel_span_helper._tracer = None
        otel_span_helper._OTEL_AVAILABLE = False
        # 清理 mock 模块，避免影响其他测试
        sys.modules.pop("opentelemetry", None)
        sys.modules.pop("opentelemetry.trace", None)
        # 清理已导入的 rail 模块，确保下次导入重新执行文件头
        for m in list(sys.modules.keys()):
            if "interrupt_rail" in m:
                del sys.modules[m]

    @staticmethod
    def _make_ctx(session_id="conv-test"):
        """构建 fake ctx 对象。"""
        ctx = MagicMock()
        state: dict[str, Any] = {}

        def get_state(key):
            return state.get(key)

        def update_state(d):
            return state.update(d)
        ctx.session.get_state.side_effect = get_state
        ctx.session.update_state.side_effect = update_state
        ctx.session.session_id = session_id
        return ctx

    def test_versatile_interrupt_creates_span(self):
        """VersatileInterruptRail._create_intercepted_tool_span 创建 tool span。"""
        from agents.EDPAgent.rail.versatile_interrupt_rail import VersatileInterruptRail

        ctx = self._make_ctx()

        VersatileInterruptRail._create_intercepted_tool_span(
            ctx,
            tool_name="call_versatile",
            tool_args={"query_intent": "理财推荐"},
            result={"status": "success", "message": "推荐完成"},
        )

    def test_multiagent_interrupt_creates_span(self):
        """MultiagentInterruptRail._create_intercepted_tool_span 创建 tool span。"""
        from agents.EDPAgent.rail.multiagent_interrupt_rail import MultiagentInterruptRail

        ctx = self._make_ctx()

        MultiagentInterruptRail._create_intercepted_tool_span(
            ctx,
            tool_name="call_multiagent",
            tool_args={"entities": [{"entity_id": "fund_agent"}]},
        )

    def test_multiversatile_interrupt_creates_span(self):
        """MultiversatileInterruptRail._create_intercepted_tool_span 创建 tool span。"""
        from agents.EDPAgent.rail.multiversatile_interrupt_rail import MultiversatileInterruptRail

        ctx = self._make_ctx()

        MultiversatileInterruptRail._create_intercepted_tool_span(
            ctx,
            tool_name="call_multiversatile",
            tool_args={"workflows": [{"query_intent": "理财推荐"}]},
        )
