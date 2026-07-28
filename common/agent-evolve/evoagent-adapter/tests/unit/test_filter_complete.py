"""Unit tests for _filter_complete — CD-001 修复：standard 模式按会话级信号整体过滤。

_filter_complete 是纯函数, 直接验证过滤语义, 不依赖 PG/kafka/HTTP。
覆盖:
- complete=None (不过滤)
- standard 模式 (complete_signal 显式传入): 按会话级信号整体保留/丢弃
- log 模式 (complete_signal 默认 None): 按 record 级 _incomplete 逐条过滤
"""

from __future__ import annotations

from agent_adapter.api.routes import _filter_complete


# ── 测试数据 ──────────────────────────────────────────────────────────
# standard 模式 records (无 _incomplete 标记, 来自 spans_to_records)
STD_RECORDS = [{"id": "r1"}, {"id": "r2"}, {"id": "r3"}]

# log 模式 records (带 _incomplete 标记, 来自 trace_assembler)
LOG_RECORDS = [
    {"id": "c1", "_incomplete": False},  # 完整
    {"id": "c2", "_incomplete": True},   # 不完整
    {"id": "c3"},                         # 无标记 → 视为完整
]


# ── complete=None: 不过滤 ─────────────────────────────────────────────
def test_no_filter_when_complete_none_standard():
    assert _filter_complete(STD_RECORDS, None, complete_signal=True) == STD_RECORDS


def test_no_filter_when_complete_none_log():
    assert _filter_complete(LOG_RECORDS, None) == LOG_RECORDS


# ── standard 模式: 按会话级 complete_signal 整体过滤 ──────────────────
def test_standard_complete_session_request_true_returns_all():
    """会话完整(signal=True) + ?complete=true → 信号匹配 → 返回全部。"""
    assert _filter_complete(STD_RECORDS, True, complete_signal=True) == STD_RECORDS


def test_standard_complete_session_request_false_returns_empty():
    """会话完整(signal=True) + ?complete=false → 信号不匹配 → 空。"""
    assert _filter_complete(STD_RECORDS, False, complete_signal=True) == []


def test_standard_incomplete_session_request_false_returns_all():
    """CD-001 核心: 会话未完成(signal=False) + ?complete=false → 信号匹配 → 返回全部。

    buggy 行为下此用例失败 (records 无 _incomplete → complete=false 返回空)。
    """
    assert _filter_complete(STD_RECORDS, False, complete_signal=False) == STD_RECORDS


def test_standard_incomplete_session_request_true_returns_empty():
    """CD-001 核心: 会话未完成(signal=False) + ?complete=true → 信号不匹配 → 空。

    buggy 行为下此用例失败 (records 无 _incomplete → complete=true 返回全部)。
    """
    assert _filter_complete(STD_RECORDS, True, complete_signal=False) == []


# ── log 模式: 按 record 级 _incomplete 逐条过滤 (行为不变) ───────────
def test_log_complete_true_keeps_non_incomplete_records():
    """?complete=true → 保留 _incomplete 为 False/缺失 的 records。"""
    out = _filter_complete(LOG_RECORDS, True)
    ids = [r["id"] for r in out]
    assert ids == ["c1", "c3"]


def test_log_complete_false_keeps_incomplete_records():
    """?complete=false → 保留 _incomplete 为 True 的 records。"""
    out = _filter_complete(LOG_RECORDS, False)
    ids = [r["id"] for r in out]
    assert ids == ["c2"]
