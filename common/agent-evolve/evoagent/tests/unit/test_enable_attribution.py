"""B2 (#15b): validation 默认 enable_attribution=False — 单元测试。"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

from openjiuwen.agent_evolving.dataset import Case

from evo_agent.evaluator.evaluators.llm import LLMEvaluator
from evo_agent.evaluator.prompts.formatter import (
    build_evaluation_prompt,
    format_evaluation_prompt,
)
from evo_agent.evaluator.prompts.policy_v1 import DEFAULT_PROMPT_TEMPLATE

_MODEL_PATCH = "evo_agent.evaluator.evaluators.llm.Model"

# 无 attributed_skill 字段的 LLM 响应（模拟关闭归因时 LLM 的输出）
_RESP_NO_ATTR = (
    '{"task_completion":0.8,"trajectory_quality":0.8,"safety":1.0,"is_pass":true,"score":0.8}'
)
_RESP_FULL_PASS = (
    '{"task_completion":1.0,"trajectory_quality":1.0,"safety":1.0,"is_pass":true,"score":1.0}'
)


def _make_evaluator() -> LLMEvaluator:
    with patch(_MODEL_PATCH) as mock_model_cls:
        mock_model_cls.return_value = MagicMock()
        return LLMEvaluator(MagicMock(), MagicMock())


def _make_case() -> Case:
    return Case(
        inputs={
            "trajectory": {"messages": [{"role": "assistant", "content": "answer"}]},
            "skill_names": ["product_recommend_skill"],
        },
        label={"expected_result": None},
    )


def _run_evaluate_capture_prompt(
    evaluator: LLMEvaluator, llm_response: str, *, enable_attribution: bool = True
) -> str:
    mock_response = type("Response", (), {"content": llm_response})()
    with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.return_value = mock_response
        evaluator.evaluate(_make_case(), {"answer": "42"}, enable_attribution=enable_attribution)
    return mock_invoke.await_args.args[0][0].content


# ── prompt 层 ──


def test_format_prompt_with_attribution_default() -> None:
    out = format_evaluation_prompt(
        DEFAULT_PROMPT_TEMPLATE,
        trajectory="traj",
        skill_names=["s1"],
    )
    assert "Skill 归因规则" in out
    assert "attributed_skill" in out


def test_format_prompt_without_attribution() -> None:
    out = format_evaluation_prompt(
        DEFAULT_PROMPT_TEMPLATE,
        trajectory="traj",
        skill_names=["s1"],
        enable_attribution=False,
    )
    assert "Skill 归因规则" not in out
    assert "attributed_skill" not in out


def test_build_evaluation_prompt_without_attribution() -> None:
    out = build_evaluation_prompt(trajectory="traj", skill_names=["s1"], enable_attribution=False)
    assert "Skill 归因规则" not in out
    assert "attributed_skill" not in out


def test_prompt_without_attribution_smaller() -> None:
    full = build_evaluation_prompt(trajectory="traj", skill_names=["s1"])
    slim = build_evaluation_prompt(trajectory="traj", skill_names=["s1"], enable_attribution=False)
    assert len(slim) < len(full)


def test_strip_attribution_raises_on_template_drift() -> None:
    """Fail-fast: if the attribution section survives stripping, raise.

    Simulates a template change (section number 六→七) that makes
    _ATTRIBUTION_SECTION_RE miss — the sentinel ``归因规则`` is still present,
    so _strip_attribution must raise rather than silently leave the section in
    the validation prompt.
    """
    from evo_agent.evaluator.prompts.formatter import _strip_attribution

    drifted = (
        "## 五、前置\nrules\n---\n\n"
        "## 七、Skill 归因规则\nattribution rules\n---\n\n"
        '{"attributed_skill": ""}\n'
    )
    import pytest

    with pytest.raises(ValueError, match="attribution section survived"):
        _strip_attribution(drifted)


def test_strip_attribution_no_section_is_noop() -> None:
    """A template without the attribution section strips cleanly (no raise)."""
    from evo_agent.evaluator.prompts.formatter import _strip_attribution

    plain = "## 一、任务\nno attribution here\n---\n\n"
    assert _strip_attribution(plain) == plain


# ── evaluator 层 ──


def test_evaluate_no_attribution_omits_section_in_prompt() -> None:
    evaluator = _make_evaluator()
    prompt = _run_evaluate_capture_prompt(
        evaluator,
        _RESP_FULL_PASS,
        enable_attribution=False,
    )
    assert "Skill 归因规则" not in prompt
    assert "attributed_skill" not in prompt


def test_evaluate_no_attribution_parses_without_field() -> None:
    """LLM 不返回 attributed_skill 时，evaluate 仍正常解析，attributed_skill 为空。"""
    evaluator = _make_evaluator()
    mock_response = type("Response", (), {"content": _RESP_NO_ATTR})()
    with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.return_value = mock_response
        result = evaluator.evaluate(_make_case(), {"answer": "x"}, enable_attribution=False)
    assert result.score == 0.8


def test_pass_fail_consistency_regardless_of_attribution() -> None:
    """同一 LLM 评分输出，开/关归因的 score 判定一致（pass/fail 由 score 反映）。"""
    response = (
        '{"task_completion":0.5,"trajectory_quality":0.5,"safety":1.0,'
        '"is_pass":false,"score":0.4,"attributed_skill":"product_recommend_skill"}'
    )
    evaluator = _make_evaluator()
    mock_response = type("Response", (), {"content": response})()
    with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.return_value = mock_response
        r_on = evaluator.evaluate(_make_case(), {"answer": "x"}, enable_attribution=True)
    mock_response2 = type("Response", (), {"content": response})()
    with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.return_value = mock_response2
        r_off = evaluator.evaluate(_make_case(), {"answer": "x"}, enable_attribution=False)
    assert r_on.score == r_off.score == 0.4


def test_batch_evaluate_threads_enable_attribution() -> None:
    """batch_evaluate(enable_attribution=False) 把开关透传到每个 case 的 prompt。"""
    evaluator = _make_evaluator()
    response = type("Response", (), {"content": _RESP_FULL_PASS})()
    with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.return_value = response
        evaluator.batch_evaluate(
            [_make_case()], [{"answer": "x"}], num_parallel=1, enable_attribution=False
        )
    prompt = mock_invoke.await_args.args[0][0].content
    assert "Skill 归因规则" not in prompt
