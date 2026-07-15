"""A9 (#18): read_file 工具结果截断 — 单元测试。"""

from __future__ import annotations

from evo_agent.evaluator.trajectory.simplify import (
    _READ_FILE_RESULT_LIMIT,
    _simplify_tool_result,
)

_MARKER = "... [truncated]"


def test_read_file_short_result_kept_in_full() -> None:
    """read_file 结果 ≤ 阈值时完整保留。"""
    content = "SKILL.md 全文（短）" * 10
    assert len(content) <= _READ_FILE_RESULT_LIMIT
    out = _simplify_tool_result("read_file", content)
    assert out == content


def test_read_file_long_result_truncated() -> None:
    """read_file 结果超阈值时截断到阈值并加 ... [truncated] 标识。"""
    content = "x" * (_READ_FILE_RESULT_LIMIT + 500)
    out = _simplify_tool_result("read_file", content)
    assert out.endswith(_MARKER)
    # 截断后 = 阈值长度 + 标识
    assert len(out) == _READ_FILE_RESULT_LIMIT + len(_MARKER)
    # 截断前的内容仍是原文前缀
    assert out.startswith(content[:_READ_FILE_RESULT_LIMIT])


def test_read_file_boundary_exact_limit_not_truncated() -> None:
    """正好等于阈值时不截断（无标识）。"""
    content = "y" * _READ_FILE_RESULT_LIMIT
    out = _simplify_tool_result("read_file", content)
    assert out == content
    assert _MARKER not in out


def test_other_tool_result_uses_default_limit() -> None:
    """非 read_file 工具结果走默认截断（… 标识，更短阈值）。"""
    content = "z" * 2000
    out = _simplify_tool_result("search", content)
    assert out.endswith("…")
    assert len(out) < 2000  # 默认 _TOOL_RESULT_LIMIT=1200 + 1


def test_unknown_tool_name_result_uses_default_limit() -> None:
    """tool_name=None 走默认截断。"""
    content = "z" * 2000
    out = _simplify_tool_result(None, content)
    assert out.endswith("…")
