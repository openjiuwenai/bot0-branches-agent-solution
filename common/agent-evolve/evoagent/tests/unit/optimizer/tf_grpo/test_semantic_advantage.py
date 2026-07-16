"""Unit tests for semantic advantage helpers."""

from __future__ import annotations

from types import SimpleNamespace

from evo_agent.optimizer.tf_grpo.experience_library import ExperienceLibrary
from evo_agent.optimizer.tf_grpo.semantic_advantage import (
    CaseOutcomeBrief,
    RolloutSummary,
    build_rollout_summary_prompt,
    case_outcome_briefs_from_evaluated,
    parse_library_operations,
    scores_have_variance,
)


def test_scores_have_variance_false_when_equal() -> None:
    rollouts = [
        RolloutSummary("a", "# a", 0.5),
        RolloutSummary("b", "# b", 0.5),
    ]
    assert scores_have_variance(rollouts) is False


def test_scores_have_variance_true_when_different() -> None:
    rollouts = [
        RolloutSummary("a", "# a", 0.2),
        RolloutSummary("b", "# b", 0.8),
    ]
    assert scores_have_variance(rollouts) is True


def test_parse_library_operations_from_fenced_json() -> None:
    raw = """
Here you go:
```json
[
  {"operation": "Add", "content": "Document failure modes"},
  {"operation": "Keep"}
]
```
"""
    ops = parse_library_operations(raw)
    assert ops[0].operation == "Add"
    assert ops[0].content == "Document failure modes"
    assert ops[1].operation == "Keep"


def test_parse_library_operations_invalid_returns_keep() -> None:
    ops = parse_library_operations("not json at all")
    assert len(ops) == 1
    assert ops[0].operation == "Keep"


def test_empty_library_prompt_still_builds() -> None:
    lib = ExperienceLibrary()
    assert lib.to_prompt_context() == ""


def test_case_outcome_briefs_from_evaluated() -> None:
    evaluated = [
        SimpleNamespace(
            case_id="case-1",
            score=1.0,
            answer={"answer": '{"responsibility": "有责"}'},
            reason="ok",
            case=SimpleNamespace(case_id="case-1", label={"expected_result": "有责"}),
        ),
        SimpleNamespace(
            case_id="case-2",
            score=0.0,
            answer={"answer": '{"responsibility": "无责"}'},
            reason="mismatch",
            case=SimpleNamespace(case_id="case-2", label={"expected_result": "有责"}),
        ),
    ]
    briefs = case_outcome_briefs_from_evaluated(evaluated)
    assert len(briefs) == 2
    assert briefs[0].expected == "有责"
    assert "有责" in briefs[0].prediction
    assert briefs[1].score == 0.0


def test_build_rollout_summary_prompt_includes_skill_and_cases() -> None:
    prompt = build_rollout_summary_prompt(
        variant_id="e1-g1",
        skill_content="# Skill\n\n## 输出契约\n",
        case_briefs=[
            CaseOutcomeBrief(
                case_id="case-1",
                score=1.0,
                expected="有责",
                prediction='{"responsibility":"有责"}',
            ),
            CaseOutcomeBrief(
                case_id="case-2",
                score=0.0,
                expected="无责",
                prediction='{"responsibility":"有责"}',
                reason="wrong responsibility",
            ),
        ],
        mean_score=0.5,
    )
    assert "e1-g1" in prompt
    assert "输出契约" in prompt
    assert "case-1" in prompt
    assert "case-2" in prompt
    assert "Likely skill gaps" in prompt
    assert "Mean score" in prompt
