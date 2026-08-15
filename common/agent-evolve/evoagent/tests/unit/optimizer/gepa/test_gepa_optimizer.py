"""ReflectionEngine and GepaOptimizer tests."""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from evo_agent.optimizer.gepa.reflection_engine import (
    ReflectionEngine,
    _extract_from_code_blocks,
)
from evo_agent.optimizer.gepa.pareto_frontier import ParetoFrontier
from evo_agent.optimizer.gepa.gepa_adapter import EvaluationBatch


# ── _extract_from_code_blocks ──────────────────────────────────────────


def test_extract_from_code_blocks_basic() -> None:
    """Extract text between ``` blocks."""
    text = "Here is the new instruction:\n```\nDo X then Y.\n```\nDone."
    result = _extract_from_code_blocks(text)
    assert result == "Do X then Y."


def test_extract_from_code_blocks_no_blocks() -> None:
    """No code blocks → return full text."""
    result = _extract_from_code_blocks("Just a plain instruction.")
    assert result == "Just a plain instruction."


def test_extract_from_code_blocks_lang_tag() -> None:
    """Strip language tag from first line."""
    text = "```\n```python\nimport os\n```\n"
    result = _extract_from_code_blocks(text)
    assert "import os" in result


# ── ReflectionEngine ────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_reflection_engine_propose() -> None:
    """ReflectionEngine proposes new text via LLM."""
    llm_inv = MagicMock()
    engine = ReflectionEngine(llm_inv, "gpt-4o")

    records = [
        {
            "Inputs": {"query": "识别图片", "images": ["img1.jpg"]},
            "Generated Outputs": "cat",
            "Expected": "cat, dog",
            "Feedback": "Missing 'dog'",
            "Score": 0.5,
        }
    ]

    with patch(
        "evo_agent.optimizer.gepa.reflection_engine.invoke_text_with_retry",
        new_callable=AsyncMock,
        return_value="```\nImproved prompt: identify all objects.\n```",
    ):
        result = await engine.propose(
            current_prompt="Original prompt",
            reflective_dataset={"system_prompt": records},
            components_to_update=["system_prompt"],
            iteration=0,
        )

    assert "system_prompt" in result
    assert "Improved prompt" in result["system_prompt"]


@pytest.mark.asyncio
async def test_reflection_engine_fallback_on_failure() -> None:
    """Returns current prompt on LLM failure."""
    llm_inv = MagicMock()
    engine = ReflectionEngine(llm_inv, "gpt-4o")

    with patch(
        "evo_agent.optimizer.gepa.reflection_engine.invoke_text_with_retry",
        new_callable=AsyncMock,
        side_effect=Exception("LLM failed"),
    ):
        result = await engine.propose(
            current_prompt="Original",
            reflective_dataset={"system_prompt": [{"Inputs": {}, "Generated Outputs": "", "Expected": "", "Feedback": "", "Score": 0}]},
            components_to_update=["system_prompt"],
            iteration=0,
        )

    assert result["system_prompt"] == "Original"


# ── GepaOptimizer ────────────────────────────────────────────────────────


def _make_cases(n: int = 4) -> list:
    from openjiuwen.agent_evolving.dataset import Case
    return [
        Case(
            case_id=f"case-{i}",
            inputs={"query": f"识别图片 {i}", "images": [f"/data/img{i}.jpg"]},
            label={"expected_result": f"object_{i}"},
        )
        for i in range(n)
    ]


@pytest.mark.asyncio
async def test_gepa_optimizer_initialization() -> None:
    """GEPA initializes seed candidate and Pareto frontier."""
    from evo_agent.optimizer.gepa.gepa_optimizer import GepaOptimizer

    opt = GepaOptimizer(
        vision_model=MagicMock(),
        evaluator=MagicMock(),
        train_cases=_make_cases(4),
        val_cases=_make_cases(4),
        llm_invocation=MagicMock(),
        model_name="gpt-4o",
    )

    # Mock adapter evaluate
    mock_batch = EvaluationBatch(
        outputs=[{"answer": "test"}] * 4,
        scores=[0.5, 0.6, 0.7, 0.8],
        case_ids=["case-0", "case-1", "case-2", "case-3"],
    )
    with patch.object(GEPAAdapter, "evaluate", new_callable=AsyncMock, return_value=mock_batch):
        await opt.initialize("seed prompt")

    assert len(opt._candidates) == 1
    assert opt._candidates[0]["system_prompt"] == "seed prompt"
    assert opt._pareto_frontier is not None
    assert opt._pareto_frontier.n_candidates == 1


@pytest.mark.asyncio
async def test_gepa_optimizer_run_optimization() -> None:
    """Full GEPA optimization loop with mocked adapter."""
    from evo_agent.optimizer.gepa.gepa_optimizer import GepaOptimizer

    opt = GepaOptimizer(
        vision_model=MagicMock(),
        evaluator=MagicMock(),
        train_cases=_make_cases(4),
        val_cases=_make_cases(4),
        llm_invocation=MagicMock(),
        model_name="gpt-4o",
        max_iterations=2,
        enable_merge=False,  # disable merge to simplify test
    )

    # Track call count for different evaluate calls
    call_count = 0

    async def _mock_evaluate(candidate, cases, *, capture_traces=False):
        nonlocal call_count
        call_count += 1
        n = len(cases)
        # First call (seed): low scores
        if call_count == 1:
            scores = [0.3] * n
        # Parent eval on minibatch: medium scores
        elif capture_traces:
            scores = [0.5] * n
        # Child eval on minibatch: higher scores → accepted
        elif len(cases) <= 5:
            scores = [0.8] * n
        # Full val eval: medium scores
        else:
            scores = [0.7] * n
        return EvaluationBatch(
            outputs=[{"answer": "test"}] * n,
            scores=scores,
            case_ids=[c.case_id for c in cases],
            trajectories=[{"case_id": c.case_id, "inputs": {}, "expected_result": "", "model_output": "", "score": s, "feedback": ""} for c, s in zip(cases, scores)] if capture_traces else None,
        )

    # Mock reflection engine to return a new prompt
    with patch.object(GEPAAdapter, "evaluate", new=_mock_evaluate), \
         patch.object(ReflectionEngine, "propose", new_callable=AsyncMock, return_value={"system_prompt": "Improved prompt"}):
        result = await opt.run_optimization("seed prompt")

    assert result["n_candidates"] >= 1
    assert "best_prompt" in result
    assert "best_score" in result


@pytest.mark.asyncio
async def test_gepa_optimizer_rejects_non_improvement() -> None:
    """Child with lower score than parent is not accepted."""
    from evo_agent.optimizer.gepa.gepa_optimizer import GepaOptimizer

    opt = GepaOptimizer(
        vision_model=MagicMock(),
        evaluator=MagicMock(),
        train_cases=_make_cases(4),
        val_cases=_make_cases(4),
        llm_invocation=MagicMock(),
        model_name="gpt-4o",
        max_iterations=1,
        enable_merge=False,
    )

    call_count = 0

    async def _mock_evaluate(candidate, cases, *, capture_traces=False):
        nonlocal call_count
        call_count += 1
        n = len(cases)
        if call_count == 1:
            scores = [0.5] * n  # seed
        elif capture_traces:
            scores = [0.8] * n  # parent on minibatch
        else:
            scores = [0.3] * n  # child worse than parent
        return EvaluationBatch(
            outputs=[{"answer": "test"}] * n,
            scores=scores,
            case_ids=[c.case_id for c in cases],
            trajectories=[{"case_id": c.case_id, "inputs": {}, "expected_result": "", "model_output": "", "score": s, "feedback": ""} for c, s in zip(cases, scores)] if capture_traces else None,
        )

    with patch.object(GEPAAdapter, "evaluate", new=_mock_evaluate), \
         patch.object(ReflectionEngine, "propose", new_callable=AsyncMock, return_value={"system_prompt": "Worse prompt"}):
        result = await opt.run_optimization("seed prompt")

    # Child was worse → not accepted → only seed candidate
    assert result["n_candidates"] == 1
