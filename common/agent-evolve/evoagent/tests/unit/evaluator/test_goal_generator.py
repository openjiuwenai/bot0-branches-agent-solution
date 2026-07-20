"""Trajectory goal generator unit tests."""

from __future__ import annotations

import json
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from evo_agent.evaluator.domain.models import (
    GoalGenerationInput,
    StandardTrajectory,
    TrajectoryMessage,
)
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.goal_generator import TrajectoryGoalGenerator
from evo_agent.evaluator.prompts.goal_generation import GOAL_GENERATION_PROMPT_TEMPLATE

_MODEL_PATCH = "evo_agent.evaluator.goal_generator.Model"
_BUSINESS_TERMS = [
    "余额",
    "银行卡",
    "卡号",
    "理财",
    "产品",
    "金额",
    "转账",
    "账户",
]


def _make_generator() -> TrajectoryGoalGenerator:
    with patch(_MODEL_PATCH) as mock_model_cls:
        mock_model_cls.return_value = MagicMock()
        return TrajectoryGoalGenerator(MagicMock(), MagicMock())


def _response(data: dict[str, Any] | str) -> str:
    if isinstance(data, str):
        return data
    return json.dumps(data, ensure_ascii=False)


def _input_with_messages(messages: list[TrajectoryMessage]) -> GoalGenerationInput:
    return GoalGenerationInput(trajectory=StandardTrajectory(messages=messages))


def test_generate_parses_goal_and_metadata() -> None:
    generator = _make_generator()
    value = _input_with_messages(
        [
            TrajectoryMessage(role="user", content="查询余额。"),
            TrajectoryMessage(role="user", content="不对，你要先问我选择哪个卡号再查询。"),
        ]
    )
    mock_response = type(
        "Response",
        (),
        {
            "content": _response(
                {
                    "goal": "用户期望查询银行卡余额前先确认具体卡号。",
                    "reason": "用户纠正了查询前应先选卡。",
                    "confidence": 0.9,
                }
            )
        },
    )()

    with patch.object(generator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.return_value = mock_response
        result = generator.generate(value)

    assert result.goal == "用户期望查询银行卡余额前先确认具体卡号。"
    assert result.metadata == {
        "reason": "用户纠正了查询前应先选卡。",
        "confidence": 0.9,
    }


def test_goal_prompt_is_domain_agnostic_and_requests_expected_outcome() -> None:
    for term in _BUSINESS_TERMS:
        assert term not in GOAL_GENERATION_PROMPT_TEMPLATE

    assert "自然" in GOAL_GENERATION_PROMPT_TEMPLATE
    assert "最终希望获得的结果" in GOAL_GENERATION_PROMPT_TEMPLATE
    assert "必要达成条件" in GOAL_GENERATION_PROMPT_TEMPLATE
    assert "条件" in GOAL_GENERATION_PROMPT_TEMPLATE
    assert "偏好" in GOAL_GENERATION_PROMPT_TEMPLATE
    assert "限制" in GOAL_GENERATION_PROMPT_TEMPLATE
    assert "不要机械使用" in GOAL_GENERATION_PROMPT_TEMPLATE
    assert "操作步骤" in GOAL_GENERATION_PROMPT_TEMPLATE


def test_prompt_excludes_metadata_and_reasoning_content() -> None:
    generator = _make_generator()
    value = _input_with_messages(
        [
            TrajectoryMessage(
                role="assistant",
                content="我将查询余额。",
                reasoning_content="hidden chain",
                metadata={"secret": "message-id"},
            )
        ]
    )
    mock_response = type(
        "Response",
        (),
        {"content": _response({"goal": "用户期望查询余额。", "reason": "", "confidence": 0.8})},
    )()

    with patch.object(generator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.return_value = mock_response
        generator.generate(value)

    messages = mock_invoke.await_args.args[0]
    prompt = messages[0].content
    assert "我将查询余额" in prompt
    assert "hidden chain" not in prompt
    assert "message-id" not in prompt


def test_read_file_tool_result_is_truncated() -> None:
    generator = _make_generator()
    long_skill = "FULL_SKILL_DOCUMENT_" * 200
    value = _input_with_messages(
        [
            TrajectoryMessage(
                role="assistant",
                content="读取技能文档。",
                tool_calls=[
                    {
                        "id": "read-1",
                        "function": {
                            "name": "read_file",
                            "arguments": '{"path": "/skills/fund_planning_skill/SKILL.md"}',
                        },
                    }
                ],
            ),
            TrajectoryMessage(role="tool", tool_call_id="read-1", content=long_skill),
        ]
    )
    mock_response = type(
        "Response",
        (),
        {"content": _response({"goal": "用户期望查询余额。", "reason": "", "confidence": 0.8})},
    )()

    with patch.object(generator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.return_value = mock_response
        generator.generate(value)

    prompt = mock_invoke.await_args.args[0][0].content
    assert "read_file" in prompt
    assert len(prompt) < len(long_skill)
    assert "TOOL_RESULT_TRUNCATED" in prompt
    trajectory = json.loads(prompt.split("轨迹：\n", 1)[1])
    assert (
        trajectory["messages"][0]["tool_calls"][0]["function"]["arguments"]
        == '{"path": "/skills/fund_planning_skill/SKILL.md"}'
    )


def test_empty_trajectory_raises_evaluation_error() -> None:
    generator = _make_generator()
    value = GoalGenerationInput(trajectory=StandardTrajectory())

    with pytest.raises(EvaluationError, match="Empty trajectory"):
        generator.generate(value)


def test_non_json_response_raises_evaluation_error() -> None:
    generator = _make_generator()
    value = _input_with_messages([TrajectoryMessage(role="user", content="查询余额。")])
    mock_response = type("Response", (), {"content": "用户期望查询余额。"})()

    with patch.object(generator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.return_value = mock_response
        with pytest.raises(EvaluationError, match="Failed to extract JSON"):
            generator.generate(value)


def test_missing_goal_raises_evaluation_error() -> None:
    generator = _make_generator()
    value = _input_with_messages([TrajectoryMessage(role="user", content="查询余额。")])
    mock_response = type("Response", (), {"content": _response({"reason": "no goal"})})()

    with patch.object(generator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.return_value = mock_response
        with pytest.raises(EvaluationError, match="missing valid 'goal'"):
            generator.generate(value)


def test_generate_repairs_one_schema_allowed_missing_comma_without_retry() -> None:
    generator = _make_generator()
    value = _input_with_messages([TrajectoryMessage(role="user", content="查询余额。")])
    mock_response = type(
        "Response",
        (),
        {"content": '{"goal": "查询余额" "confidence": 0.8}'},
    )()

    with patch.object(generator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.return_value = mock_response
        result = generator.generate(value)

    assert result.goal == "查询余额"
    assert result.metadata["confidence"] == 0.8
    assert mock_invoke.await_count == 1


def test_generate_retries_invalid_confidence_and_consumes_second_response() -> None:
    generator = _make_generator()
    value = _input_with_messages([TrajectoryMessage(role="user", content="查询余额。")])
    responses = [
        type("Response", (), {"content": '{"goal": "wrong", "confidence": "high"}'})(),
        type("Response", (), {"content": '{"goal": "right", "confidence": 0.7}'})(),
    ]

    with patch.object(generator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.side_effect = responses
        result = generator.generate(value)

    assert result.goal == "right"
    assert result.metadata["confidence"] == 0.7
    assert mock_invoke.await_count == 2
    retry_prompt = mock_invoke.await_args_list[1].args[0][0].content
    assert "goal" in retry_prompt
    assert "查询余额。" in retry_prompt
    assert "非空字符串" not in retry_prompt
