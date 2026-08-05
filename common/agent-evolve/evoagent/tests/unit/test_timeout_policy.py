"""A8 (#17): attempt_timeout_secs 收紧 — 单元测试。

reflect/aggregate/slow_update 的单次 attempt 超时由 180s 收紧到 90s，
总预算相应由 600s 调整到 300s，使大 case 下 retry 更早触发。
"""

from __future__ import annotations

from evo_agent.optimizer.skill_document.meta_skill import _META_SKILL_POLICY
from evo_agent.optimizer.skill_document.skill_document_optimizer import (
    _AGGREGATE_POLICY,
    _REFLECT_POLICY,
)
from evo_agent.optimizer.skill_document.slow_update import _SLOW_UPDATE_POLICY


def test_reflect_policy_timeout_tightened() -> None:
    """reflect 单次 attempt ≤ 90s，总预算 ≤ 300s。"""
    assert _REFLECT_POLICY.attempt_timeout_secs <= 90
    assert _REFLECT_POLICY.total_budget_secs <= 300


def test_aggregate_policy_timeout_tightened() -> None:
    """aggregate 单次 attempt ≤ 90s，总预算 ≤ 300s。"""
    assert _AGGREGATE_POLICY.attempt_timeout_secs <= 90
    assert _AGGREGATE_POLICY.total_budget_secs <= 300


def test_slow_update_policy_timeout_tightened() -> None:
    """slow_update 单次 attempt ≤ 90s，总预算 ≤ 300s。"""
    assert _SLOW_UPDATE_POLICY.attempt_timeout_secs <= 90
    assert _SLOW_UPDATE_POLICY.total_budget_secs <= 300


def test_meta_skill_policy_timeout_tightened() -> None:
    """meta_skill 单次 attempt ≤ 90s，总预算 ≤ 300s。"""
    assert _META_SKILL_POLICY.attempt_timeout_secs <= 90
    assert _META_SKILL_POLICY.total_budget_secs <= 300


def test_max_attempts_unchanged() -> None:
    """收紧超时不改动 max_attempts（retry 次数不变）。"""
    assert _REFLECT_POLICY.max_attempts == 2
    assert _AGGREGATE_POLICY.max_attempts == 2
    assert _SLOW_UPDATE_POLICY.max_attempts == 2
