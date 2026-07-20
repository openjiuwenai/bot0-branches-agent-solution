"""trace_source.factory 单测 —— TRACE_SOURCE 路由。"""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from agent_adapter.trace_source.db_source import DbTraceSource
from agent_adapter.trace_source.factory import make_trace_source
from agent_adapter.trace_source.log_source import LogTraceSource


class _FakeRepo:
    """占位 repo (DbTraceSource 构造时不调用, 仅校验路由)。"""


def test_make_trace_source_log():
    src = make_trace_source(SimpleNamespace(trace_source="log"))
    assert isinstance(src, LogTraceSource)


def test_make_trace_source_standard_with_repo():
    src = make_trace_source(SimpleNamespace(trace_source="standard"), repo=_FakeRepo())
    assert isinstance(src, DbTraceSource)


def test_make_trace_source_standard_without_repo_raises():
    with pytest.raises(ValueError, match="repo"):
        make_trace_source(SimpleNamespace(trace_source="standard"))


def test_make_trace_source_unknown_raises():
    with pytest.raises(ValueError, match="TRACE_SOURCE"):
        make_trace_source(SimpleNamespace(trace_source="redis"))


def test_make_trace_source_defaults_to_log(monkeypatch):
    monkeypatch.delenv("ADAPTER_TRACE_SOURCE", raising=False)
    src = make_trace_source(SimpleNamespace())  # 无 trace_source 字段
    assert isinstance(src, LogTraceSource)
