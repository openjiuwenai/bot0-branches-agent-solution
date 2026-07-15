"""F1 (ADR-0009): 基类 ``SkillDocumentOptimizer._attribute`` 单 op short-circuit
按 ``attributed_skill`` 过滤 badcase 的单元测试。

修订 WAVE_9 AC1：单 operator 不再无条件全量归属。badcase 读 ``attributed_skill``：
填了且匹配唯一 op_id → 进 failure 反思；留空（非 skill 失败）或归因到不存在的 skill → 丢。
goodcase 全量进 success 反思（不变）。
"""

from __future__ import annotations

import json
from typing import Any

import pytest
from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase
from openjiuwen.agent_evolving.trajectory import Trajectory

from evo_agent.optimizer.skill_document import SkillDocumentOptimizer


def _traj(case_id: str) -> Trajectory:
    """单 op short-circuit 不读 trajectory，空 steps 即可。"""
    return Trajectory(execution_id=case_id, steps=[], case_id=case_id)


def _eval_case(case_id: str, score: float, attributed_skill: str | None) -> EvaluatedCase:
    case = Case(case_id=case_id, inputs={"q": "x"}, label={"expected": "ok"})
    reason = json.dumps({"attributed_skill": attributed_skill or "", "is_pass": score >= 0.75})
    return EvaluatedCase(case=case, answer={}, score=score, reason=reason)


def _make_optimizer(operators: list[str]) -> SkillDocumentOptimizer:
    """绕过 __init__，仅装 _operators，用于隔离测 _attribute。"""
    optimizer = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)
    optimizer._operators = {op_id: Any for op_id in operators}  # type: ignore[assignment]
    return optimizer


# ── AC1: 单 skill + badcase 归因到该 skill → 进 failure 反思 ──


@pytest.mark.asyncio
async def test_single_skill_badcase_attributed_kept() -> None:
    optimizer = _make_optimizer(["skill_a"])
    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = _eval_case("c1", score=0.3, attributed_skill="skill_a")
    item = (_traj("c1"), eval_case, case)

    result = await optimizer._attribute(
        failure_batch=[item],
        success_batch=[],
        skill_contents={"skill_a": "doc"},
    )

    assert "skill_a" in result
    assert len(result["skill_a"].failures) == 1
    assert result["skill_a"].failures[0][1].case.case_id == "c1"


# ── AC2: 单 skill + badcase 归因留空（非 skill 失败）→ 丢 ──


@pytest.mark.asyncio
async def test_single_skill_badcase_no_attribution_dropped() -> None:
    optimizer = _make_optimizer(["skill_a"])
    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = _eval_case("c1", score=0.3, attributed_skill="")
    item = (_traj("c1"), eval_case, case)

    result = await optimizer._attribute(
        failure_batch=[item],
        success_batch=[],
        skill_contents={"skill_a": "doc"},
    )

    # 留空 → 丢，不进 failure 反思
    assert result == {}


# ── AC2 扩展：单 skill + badcase 归因到不存在的 skill → 丢（保守） ──


@pytest.mark.asyncio
async def test_single_skill_badcase_attribution_to_unknown_dropped() -> None:
    optimizer = _make_optimizer(["skill_a"])
    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = _eval_case("c1", score=0.3, attributed_skill="nonexistent_skill")
    item = (_traj("c1"), eval_case, case)

    result = await optimizer._attribute(
        failure_batch=[item],
        success_batch=[],
        skill_contents={"skill_a": "doc"},
    )

    # 归因到不存在的 skill → 视为归因失败 → 丢
    assert result == {}


# ── AC3: 单 skill + goodcase → 全量进 success 反思（不变） ──


@pytest.mark.asyncio
async def test_single_skill_goodcase_all_kept() -> None:
    optimizer = _make_optimizer(["skill_a"])
    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    # goodcase 不归因（评估 prompt 设计如此）
    eval_case = _eval_case("c1", score=0.9, attributed_skill="")
    item = (_traj("c1"), eval_case, case)

    result = await optimizer._attribute(
        failure_batch=[],
        success_batch=[item],
        skill_contents={"skill_a": "doc"},
    )

    assert "skill_a" in result
    assert len(result["skill_a"].successes) == 1
    assert len(result["skill_a"].failures) == 0


# ── 混合：单 skill 下 badcase 保留/丢弃 + goodcase 保留 ──


@pytest.mark.asyncio
async def test_single_skill_mixed_batch() -> None:
    optimizer = _make_optimizer(["skill_a"])

    # c1: badcase 归因到 skill_a → 保留
    c1 = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    ec1 = _eval_case("c1", 0.3, "skill_a")
    # c2: badcase 非 skill 失败（留空）→ 丢
    c2 = Case(case_id="c2", inputs={"q": "x"}, label={"expected": "ok"})
    ec2 = _eval_case("c2", 0.4, "")
    # c3: goodcase → 保留
    c3 = Case(case_id="c3", inputs={"q": "x"}, label={"expected": "ok"})
    ec3 = _eval_case("c3", 0.9, "")

    result = await optimizer._attribute(
        failure_batch=[(_traj("c1"), ec1, c1), (_traj("c2"), ec2, c2)],
        success_batch=[(_traj("c3"), ec3, c3)],
        skill_contents={"skill_a": "doc"},
    )

    assert "skill_a" in result
    failure_ids = [f[1].case.case_id for f in result["skill_a"].failures]
    success_ids = [s[1].case.case_id for s in result["skill_a"].successes]
    assert failure_ids == ["c1"]  # c2 丢弃
    assert success_ids == ["c3"]


# ── AC9: JSON 解析失败/字段缺失 → graceful，不抛异常 ──


@pytest.mark.asyncio
async def test_single_skill_badcase_malformed_reason_dropped() -> None:
    optimizer = _make_optimizer(["skill_a"])
    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = EvaluatedCase(case=case, answer={}, score=0.3, reason="{broken json")
    item = (_traj("c1"), eval_case, case)

    result = await optimizer._attribute(
        failure_batch=[item],
        success_batch=[],
        skill_contents={"skill_a": "doc"},
    )

    # 解析失败 → 无归因 → 丢，不抛
    assert result == {}


@pytest.mark.asyncio
async def test_single_skill_badcase_missing_attribution_field_dropped() -> None:
    """reason 是合法 JSON 但无 attributed_skill 字段 → 视为留空 → 丢。"""
    optimizer = _make_optimizer(["skill_a"])
    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = EvaluatedCase(
        case=case, answer={}, score=0.3, reason=json.dumps({"reason": "no attribution"})
    )
    item = (_traj("c1"), eval_case, case)

    result = await optimizer._attribute(
        failure_batch=[item],
        success_batch=[],
        skill_contents={"skill_a": "doc"},
    )

    assert result == {}


@pytest.mark.asyncio
async def test_single_skill_badcase_null_attribution_dropped() -> None:
    """reason 里 attributed_skill 显式为 null → 非字符串 → 视为无归因 → 丢。"""
    optimizer = _make_optimizer(["skill_a"])
    case = Case(case_id="c1", inputs={"q": "x"}, label={"expected": "ok"})
    eval_case = EvaluatedCase(
        case=case,
        answer={},
        score=0.3,
        reason=json.dumps({"attributed_skill": None, "is_pass": False}),
    )
    item = (_traj("c1"), eval_case, case)

    result = await optimizer._attribute(
        failure_batch=[item],
        success_batch=[],
        skill_contents={"skill_a": "doc"},
    )

    assert result == {}
