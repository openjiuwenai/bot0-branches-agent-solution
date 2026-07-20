"""LLMEvaluator 单元测试。"""

from __future__ import annotations

import asyncio
import json
import logging
from types import MappingProxyType
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase
from openjiuwen.agent_evolving.evaluator.evaluator import BaseEvaluator

from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.evaluators.llm import (
    LLMEvaluator,
    _validate_attributed_skill,
)
from evo_agent.evaluator.json_util import extract_json_data as _extract_json
from evo_agent.evaluator.prompts.formatter import (
    build_dimension_keys,
    build_evaluation_prompt,
    format_evaluation_prompt,
)
from evo_agent.evaluator.prompts.policy_v1 import (
    DEFAULT_PROMPT_TEMPLATE,
)
from evo_agent.llm.invocation import (
    LLMInvocation,
    LLMInvocationResult,
    LLMProviderCapabilities,
)

_MODEL_PATCH = "evo_agent.evaluator.evaluators.llm.Model"

_DEFAULT_TRAJECTORY: dict[str, Any] = {
    "messages": [{"role": "assistant", "content": "answer"}],
}


def _make_evaluator(
    prompt_template: str | None = None,
    aggregate: str = "mean",
) -> LLMEvaluator:
    """创建测试用 LLMEvaluator，mock Model 避免真实 LLM 客户端初始化。"""
    with patch(_MODEL_PATCH) as mock_model_cls:
        mock_model_cls.return_value = MagicMock()
        model_config = MagicMock()
        model_client_config = MagicMock()
        return LLMEvaluator(
            model_config,
            model_client_config,
            aggregate=aggregate,
            prompt_template=prompt_template,
        )


_USE_DEFAULT = object()


def _make_case(
    trajectory: Any = _USE_DEFAULT,
    expected_result: Any = None,
    skill_names: list[str] | None = None,
) -> Case:
    """构造测试用 Case。默认包含 trajectory（评估器要求）和 skill_names（必填）。"""
    if skill_names is None:
        skill_names = ["product_recommend_skill"]
    inputs: dict[str, Any] = {}
    traj = _DEFAULT_TRAJECTORY if trajectory is _USE_DEFAULT else trajectory
    if traj is not None:
        inputs["trajectory"] = traj
    if skill_names:
        inputs["skill_names"] = skill_names
    label: dict[str, Any] = {"expected_result": expected_result}
    return Case(inputs=inputs, label=label)


def _mock_llm_response(
    reason: str,
    *,
    task_completion: float = 1.0,
    trajectory_quality: float = 1.0,
    safety: float = 1.0,
    is_pass: bool = True,
    score: float = 1.0,
    attributed_skill: str = "",
) -> str:
    """构造 LLM 扁平 JSON 响应文本。"""
    data: dict[str, Any] = {
        "task_completion": task_completion,
        "trajectory_quality": trajectory_quality,
        "safety": safety,
        "is_pass": is_pass,
        "score": score,
        "attributed_skill": attributed_skill,
        "reason": reason,
    }
    return f"```json\n{json.dumps(data)}\n```"


def _run_evaluate(
    evaluator: LLMEvaluator,
    predict: dict[str, Any] | None = None,
    case: Case | None = None,
    llm_response: str = "",
) -> EvaluatedCase:
    """运行 evaluate 并 mock LLM 调用。"""
    if case is None:
        case = _make_case()
    if predict is None:
        predict = {"answer": "42"}
    mock_response = type("Response", (), {"content": llm_response})()
    with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
        mock_invoke.return_value = mock_response
        return evaluator.evaluate(case, predict)


class TestLLMEvaluatorInheritance:
    """验证继承关系。"""

    def test_inherits_base_evaluator(self) -> None:
        evaluator = _make_evaluator()
        assert isinstance(evaluator, BaseEvaluator)


class TestDimensionDetermination:
    """维度确定测试 — build_dimension_keys。"""

    def test_includes_three_dimensions(self) -> None:
        """包含全部三个评估维度。"""
        keys = build_dimension_keys()
        assert keys == [
            "task_completion",
            "trajectory_quality",
            "safety",
        ]


class TestEvaluateWithExpected:
    """有 expected_result 的评估。"""

    def test_expected_result_in_label(self) -> None:
        evaluator = _make_evaluator()
        response = _mock_llm_response(
            "partial match",
            task_completion=0.8,
            trajectory_quality=0.75,
            safety=0.9,
            is_pass=True,
            score=0.8,
        )
        case = _make_case(
            expected_result={"answer": "43"},
        )
        result = _run_evaluate(
            evaluator,
            predict={"answer": "42"},
            case=case,
            llm_response=response,
        )
        assert set(result.per_metric) == {
            "task_completion",
            "trajectory_quality",
            "safety",
        }

    def test_single_missing_comma_is_repaired_with_provenance(self) -> None:
        evaluator = _make_evaluator()
        response = (
            '{"task_completion":1,"trajectory_quality":1,"safety":1,'
            '"is_pass":true,"score":0.8 "attributed_skill":"",'
            '"reason":"ok"}'
        )

        evaluated = _run_evaluate(evaluator, llm_response=response)

        reason = json.loads(evaluated.reason)
        assert reason["repaired"] is True
        assert reason["parse_mode"] == "deterministic_comma_repair"
        assert reason["repair_operations"][0]["op"] == "insert_comma"
        assert reason["repair_operations"][0]["next_key"] == "attributed_skill"

    def test_successful_repair_logs_original_and_repaired_response(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        """恢复成功也要保留现场证据，且日志保持单行。"""
        evaluator = _make_evaluator()
        response = '{"is_pass":true,"score":0.8 "attributed_skill":"","reason":"line 1\\nline 2"}'

        with caplog.at_level(logging.WARNING):
            _run_evaluate(evaluator, llm_response=response)

        messages = [
            record.getMessage()
            for record in caplog.records
            if "JSON repaired" in record.getMessage()
        ]
        assert len(messages) == 1
        message = messages[0]
        assert "parse_mode=deterministic_comma_repair" in message
        assert "insert_comma" in message
        assert 'original_response="' in message
        assert 'repaired_response="' in message
        assert "\\n" in message


class TestEvaluateWithTrajectory:
    """有轨迹的评估。"""

    def test_includes_trajectory_quality(self) -> None:
        evaluator = _make_evaluator()
        response = _mock_llm_response(
            "good trajectory",
            task_completion=1.0,
            trajectory_quality=0.75,
            safety=0.9,
            is_pass=True,
            score=0.85,
        )
        trajectory = {
            "messages": [{"role": "assistant", "content": "done"}],
        }
        case = _make_case(trajectory=trajectory)
        result = _run_evaluate(
            evaluator,
            predict={"answer": "found"},
            case=case,
            llm_response=response,
        )
        assert result.per_metric is not None
        assert "trajectory_quality" in result.per_metric
        assert result.per_metric["trajectory_quality"] == 0.75

    def test_long_trajectory_uses_shared_compactor_then_scores_once(self) -> None:
        """单条长轨迹压缩后仍是一次完整评分，tool arguments 保真。"""
        provider = MagicMock()
        provider.invoke = AsyncMock(
            return_value=type(
                "Response",
                (),
                {"content": _mock_llm_response("ok", score=0.8), "metadata": {}},
            )()
        )
        invocation = LLMInvocation(
            provider,
            capabilities=LLMProviderCapabilities(
                context_window_tokens=8000,
                supports_max_output_tokens=False,
                supports_finish_reason=False,
                supports_usage=False,
                supports_json_mode=False,
                completion_signal="either",
            ),
            parallelism=1,
            safety_margin_tokens=512,
            chars_per_token=2.0,
            default_output_reserve_tokens=1200,
        )
        evaluator = _make_evaluator()
        evaluator._invocation = invocation
        arguments = '{"path":"/customer/exact.json","limit":42}'
        case = _make_case(
            trajectory={
                "messages": [
                    {
                        "role": "assistant",
                        "tool_calls": [
                            {
                                "id": "call-1",
                                "function": {"name": "read_file", "arguments": arguments},
                            }
                        ],
                    },
                    {
                        "role": "tool",
                        "tool_call_id": "call-1",
                        "content": "head" + "x" * 20_000 + "tail",
                    },
                    {"role": "assistant", "content": "final answer"},
                ]
            }
        )

        evaluated = evaluator.evaluate(case, {"answer": "ignored"})

        assert evaluated.score == pytest.approx(0.8)
        provider.invoke.assert_awaited_once()
        sent_prompt = provider.invoke.await_args.args[0][0].content
        assert "TOOL_RESULT_TRUNCATED" in sent_prompt
        trajectory_text = sent_prompt.split("### 3. 轨迹消息\n", 1)[1].split("\n\n---", 1)[0]
        sent_trajectory = json.loads(trajectory_text)
        assert sent_trajectory["messages"][0]["tool_calls"][0]["function"]["arguments"] == arguments
        assert "final answer" in sent_prompt

    def test_sends_simplified_trajectory_to_model_without_mutating_case(self) -> None:
        evaluator = _make_evaluator()
        response = _mock_llm_response("good", score=1.0)
        trajectory = {
            "messages": [
                {
                    "role": "assistant",
                    "content": "",
                    "reasoning_content": "private reasoning",
                    "metadata": {"message_id": "secret"},
                    "tool_calls": [
                        {
                            "id": "read-1",
                            "function": {
                                "name": "read_file",
                                "arguments": (
                                    '{"path": "/skills/product_recommend_skill/SKILL.md"}'
                                ),
                            },
                        }
                    ],
                },
                {
                    "role": "tool",
                    "tool_call_id": "read-1",
                    "content": "code=0 message='success' data=FULL SKILL DOCUMENT",
                },
            ],
        }
        case = _make_case(trajectory=trajectory)
        mock_response = type("Response", (), {"content": response})()

        with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.return_value = mock_response
            evaluator.evaluate(case, {"answer": "done"})

        messages = mock_invoke.await_args.args[0]
        prompt = messages[0].content
        assert "product_recommend_skill" in prompt
        assert "read_file" in prompt
        assert "private reasoning" not in prompt
        assert "message_id" not in prompt
        assert case.inputs["trajectory"] == trajectory

    def test_detected_skills_not_in_prompt_skills_section(self) -> None:
        """prompt 中不再有「已识别 Skill 列表」段落。"""
        evaluator = _make_evaluator()
        response = _mock_llm_response("ok", score=1.0)
        trajectory = {
            "messages": [
                {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [
                        {
                            "id": "read-1",
                            "function": {
                                "name": "read_file",
                                "arguments": ('{"path": "/skills/my_skill/SKILL.md"}'),
                            },
                        }
                    ],
                },
            ],
        }
        case = _make_case(trajectory=trajectory)
        mock_response = type("Response", (), {"content": response})()

        with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.return_value = mock_response
            evaluator.evaluate(case, {"answer": "done"})

        prompt = mock_invoke.await_args.args[0][0].content
        assert "已识别 Skill 列表" not in prompt
        assert '"skill_name": "my_skill"' not in prompt


class TestExplicitSkillName:
    """显式传入 skill_name 的评估测试。"""

    def test_skill_name_used_for_validation_and_prompt(self) -> None:
        """skill_name 用于归因校验和 prompt 中的 Skill 列表。"""
        evaluator = _make_evaluator()
        response = _mock_llm_response("ok", score=1.0)
        trajectory = {
            "messages": [
                {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [
                        {
                            "id": "read-1",
                            "function": {
                                "name": "read_file",
                                "arguments": ('{"path": "/skills/detected_skill/SKILL.md"}'),
                            },
                        }
                    ],
                },
            ],
        }
        case = _make_case(
            trajectory=trajectory,
            skill_names=["explicit_skill"],
        )
        mock_response = type("Response", (), {"content": response})()

        with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.return_value = mock_response
            evaluator.evaluate(case, {"answer": "done"})

        prompt = mock_invoke.await_args.args[0][0].content
        assert "已识别 Skill 列表" not in prompt


class TestSafetyParticipation:
    """Safety 评分参与测试。"""

    def test_safety_in_per_metric(self) -> None:
        """safety 在 per_metric 中。"""
        evaluator = _make_evaluator()
        response = _mock_llm_response(
            "ok",
            task_completion=0.8,
            trajectory_quality=0.75,
            safety=0.25,
            score=0.6,
            is_pass=False,
        )
        case = _make_case()
        result = _run_evaluate(
            evaluator, predict={"answer": "42"}, case=case, llm_response=response
        )
        assert result.per_metric is not None
        assert result.per_metric["safety"] == 0.25
        assert result.per_metric["task_completion"] == 0.8

    def test_score_from_llm_not_mean(self) -> None:
        """score 来自 LLM 直接输出，不是维度均值。"""
        evaluator = _make_evaluator()
        response = _mock_llm_response(
            "ok",
            task_completion=0.8,
            trajectory_quality=0.6,
            safety=0.75,
            score=0.73,
            is_pass=True,
        )
        case = _make_case()
        result = _run_evaluate(
            evaluator, predict={"answer": "42"}, case=case, llm_response=response
        )
        # score = 0.73 from LLM, NOT mean(0.8, 0.6, 0.75) ≈ 0.717
        assert result.score == pytest.approx(0.73)


class TestLLMErrorHandling:
    """LLM 异常处理测试 — 区分 evaluation_failed 与真实评分 0.0。"""

    def test_llm_exception_raises_evaluation_error(self) -> None:
        evaluator = _make_evaluator()
        case = _make_case()
        with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.side_effect = RuntimeError("LLM unavailable")
            with pytest.raises(EvaluationError, match="LLM evaluation failed"):
                evaluator.evaluate(case, {"answer": "42"})

    def test_invalid_json_raises_evaluation_error(self) -> None:
        evaluator = _make_evaluator()
        with pytest.raises(EvaluationError, match="Failed to extract JSON"):
            _run_evaluate(
                evaluator,
                predict={"answer": "42"},
                llm_response="this is not json at all",
            )

    def test_complete_invalid_json_uses_format_retry(self) -> None:
        """完整但不可修复的 JSON 必须触发一次格式重试。"""
        evaluator = _make_evaluator()
        valid_response = _mock_llm_response("ok after retry", score=0.9)
        responses = [
            type("Response", (), {"content": "{not valid json}", "metadata": {}})(),
            type("Response", (), {"content": valid_response, "metadata": {}})(),
        ]

        with (
            patch.object(
                evaluator._model,
                "invoke",
                new_callable=AsyncMock,
                side_effect=responses,
            ) as mock_invoke,
            patch(
                "evo_agent.llm.invocation.asyncio.sleep",
                new_callable=AsyncMock,
            ),
        ):
            evaluated = evaluator.evaluate(_make_case(), {"answer": "42"})

        assert evaluated.score == pytest.approx(0.9)
        assert mock_invoke.await_count == 2
        retry_prompt = mock_invoke.await_args_list[1].args[0][0].content
        assert "格式重试" in retry_prompt

    def test_parse_failure_log_contains_complete_invocation_diagnostics(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        """单 case 入口也要把完整单行 raw 与调用诊断消费到唯一 WARNING。"""
        evaluator = _make_evaluator()
        invocation_result = LLMInvocationResult(
            invocation_id="inv-123",
            text="bad\nresponse",
            finish_reason=None,
            input_tokens=None,
            output_tokens=None,
            transport_complete=True,
            metadata=MappingProxyType(
                {
                    "provider": "ICBC",
                    "estimated_input_tokens": 321,
                    "output_reserve_tokens": 1200,
                    "compacted": True,
                    "completion_signal": "done",
                    "chunk_count": 4,
                }
            ),
        )
        evaluator._invocation.invoke_sync = MagicMock(return_value=invocation_result)

        with caplog.at_level(logging.WARNING), pytest.raises(EvaluationError):
            evaluator.evaluate(_make_case(), {"answer": "42"})

        messages = [
            record.getMessage()
            for record in caplog.records
            if "Evaluation skipped" in record.getMessage()
        ]
        assert len(messages) == 1
        message = messages[0]
        assert 'raw_response="bad\\nresponse"' in message
        for expected in (
            "invocation_id=inv-123",
            "stage=evaluator",
            "provider=ICBC",
            "prompt_estimated_tokens=321",
            "output_reserve_tokens=1200",
            "compacted=True",
            "finish_reason=unknown",
            "completion_signal=done",
            "chunk_count=4",
        ):
            assert expected in message

    def test_missing_score_raises_evaluation_error(self) -> None:
        """缺少 score 字段必须报错。"""
        evaluator = _make_evaluator()
        data = {"reason": "no score", "is_pass": True, "attributed_skill": ""}
        response = f"```json\n{json.dumps(data)}\n```"
        with pytest.raises(EvaluationError, match="missing valid 'score'"):
            _run_evaluate(
                evaluator,
                predict={"answer": "42"},
                llm_response=response,
            )

    def test_missing_is_pass_raises_evaluation_error(self) -> None:
        """缺少 is_pass 字段必须报错。"""
        evaluator = _make_evaluator()
        data = {"reason": "no is_pass", "score": 0.75, "attributed_skill": ""}
        response = f"```json\n{json.dumps(data)}\n```"
        with pytest.raises(EvaluationError, match="missing valid 'is_pass'"):
            _run_evaluate(
                evaluator,
                predict={"answer": "42"},
                llm_response=response,
            )

    def test_missing_reason_is_rejected_before_schema_defaults(self) -> None:
        """本次输出 schema 的必填字段不能由 Pydantic/default 静默补齐。"""
        evaluator = _make_evaluator()
        response = json.dumps({"score": 0.75, "is_pass": True, "attributed_skill": ""})

        with pytest.raises(EvaluationError, match="missing required keys"):
            _run_evaluate(evaluator, llm_response=response)

    def test_non_numeric_score_raises_evaluation_error(self) -> None:
        """非数值 score 必须报错。"""
        evaluator = _make_evaluator()
        data = {
            "reason": "bad score",
            "score": "high",
            "is_pass": True,
            "attributed_skill": "",
        }
        response = f"```json\n{json.dumps(data)}\n```"
        with pytest.raises(EvaluationError, match="missing valid 'score'"):
            _run_evaluate(
                evaluator,
                predict={"answer": "42"},
                llm_response=response,
            )

    def test_non_boolean_is_pass_raises_evaluation_error(self) -> None:
        """非布尔 is_pass 必须报错。"""
        evaluator = _make_evaluator()
        data = {
            "reason": "bad is_pass",
            "score": 0.75,
            "is_pass": "yes",
            "attributed_skill": "",
        }
        response = f"```json\n{json.dumps(data)}\n```"
        with pytest.raises(EvaluationError, match="missing valid 'is_pass'"):
            _run_evaluate(
                evaluator,
                predict={"answer": "42"},
                llm_response=response,
            )

    def test_missing_dimensions_still_passes(self) -> None:
        """缺少维度分数时不报错——维度是 best-effort。"""
        evaluator = _make_evaluator()
        data = {
            "reason": "no dims",
            "score": 0.75,
            "is_pass": True,
            "attributed_skill": "",
        }
        response = f"```json\n{json.dumps(data)}\n```"
        case = _make_case()
        result = _run_evaluate(
            evaluator,
            predict={"answer": "42"},
            case=case,
            llm_response=response,
        )
        assert result.score == pytest.approx(0.75)
        # per_metric may be empty or None since no dimensions were provided
        assert result.per_metric is None or result.per_metric == {}

    def test_invalid_dimension_values_are_rejected(self) -> None:
        """已出现的非法维度值触发 schema retry，耗尽后拒绝整份响应。"""
        evaluator = _make_evaluator()
        data = {
            "task_completion": "high",
            "trajectory_quality": 0.8,
            "safety": 0.5,
            "score": 0.75,
            "is_pass": True,
            "attributed_skill": "",
            "reason": "some dims invalid",
        }
        response = f"```json\n{json.dumps(data)}\n```"
        with pytest.raises(EvaluationError, match="finite number"):
            _run_evaluate(
                evaluator,
                predict={"answer": "42"},
                case=_make_case(),
                llm_response=response,
            )


class TestBatchEvaluate:
    """batch_evaluate — 单条失败不拖垮整批，失败 case 从结果中排除。"""

    def test_batch_evaluate_excludes_failed_cases(self) -> None:
        evaluator = _make_evaluator()
        good_case = _make_case()
        bad_case = _make_case(trajectory={"messages": [{"role": "user", "content": "bad request"}]})
        good_predict = {"answer": "ok"}
        bad_predict = {"answer": "fail"}

        good_response = _mock_llm_response(
            "ok",
            task_completion=0.8,
            trajectory_quality=1.0,
            safety=1.0,
            score=0.85,
            is_pass=True,
        )

        def side_effect(messages: Any) -> Any:
            prompt = messages[0].content
            if "bad request" in prompt:
                raise RuntimeError("LLM unavailable")
            return type("Response", (), {"content": good_response})()

        with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.side_effect = side_effect
            results = evaluator.batch_evaluate([good_case, bad_case], [good_predict, bad_predict])

        assert len(results) == 1
        assert results[0].case.case_id == good_case.case_id
        assert results[0].score == pytest.approx(0.85)

    def test_batch_evaluate_all_failures_returns_empty(self) -> None:
        evaluator = _make_evaluator()
        case = _make_case()
        predict = {"answer": "42"}

        with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.side_effect = RuntimeError("LLM down")
            results = evaluator.batch_evaluate([case], [predict])

        assert results == []

    def test_batch_evaluate_empty_cases(self) -> None:
        evaluator = _make_evaluator()
        results = evaluator.batch_evaluate([], [])
        assert results == []

    def test_batch_failure_artifact_does_not_expose_invalid_field_value(self) -> None:
        evaluator = _make_evaluator()
        secret = "sensitive-model-field-value"
        response = json.dumps(
            {
                "score": secret,
                "is_pass": True,
                "reason": "invalid score type",
                "attributed_skill": "",
            }
        )
        mock_response = type("Response", (), {"content": response, "metadata": {}})()

        with (
            patch.object(
                evaluator._model,
                "invoke",
                new_callable=AsyncMock,
                return_value=mock_response,
            ),
            patch("evo_agent.llm.invocation.asyncio.sleep", new_callable=AsyncMock),
        ):
            result = evaluator.batch_evaluate_detailed([_make_case()], [{"answer": "42"}])

        failure = result.outcomes[0].failure
        assert failure is not None
        assert failure.category == "field_type"
        assert secret not in failure.safe_message

    def test_mean_score_filters_nan_safety_net(self) -> None:
        """_mean_score 防御性过滤 NaN。"""
        evaluator = _make_evaluator()
        case_a = _make_case()
        case_b = _make_case()

        ec_a = EvaluatedCase(case=case_a, answer={"answer": "ok"}, score=0.8)
        ec_b = EvaluatedCase(case=case_b, answer={"answer": "ok"}, score=0.6)
        object.__setattr__(ec_b, "score", float("nan"))

        assert evaluator._mean_score([ec_a, ec_b]) == pytest.approx(0.8)

    def test_mean_score_all_nan_returns_zero(self) -> None:
        evaluator = _make_evaluator()
        case = _make_case()
        ec = EvaluatedCase(case=case, answer={"answer": "fail"}, score=0.5)
        object.__setattr__(ec, "score", float("nan"))
        assert evaluator._mean_score([ec]) == 0.0

    def test_mean_score_normal_scores_unchanged(self) -> None:
        evaluator = _make_evaluator()
        case_a = _make_case()
        case_b = _make_case()
        evaluated = [
            EvaluatedCase(case=case_a, answer={"answer": "ok"}, score=0.8),
            EvaluatedCase(case=case_b, answer={"answer": "ok"}, score=0.6),
        ]
        assert evaluator._mean_score(evaluated) == pytest.approx(0.7)


class TestScoreFromLLM:
    """分数来源测试 — score 直接来自 LLM 输出。"""

    def test_score_from_llm_output(self) -> None:
        """score 取自 LLM 的 score 字段，非维度均值。"""
        evaluator = _make_evaluator()
        response = _mock_llm_response(
            "test",
            task_completion=0.6,
            trajectory_quality=0.8,
            safety=1.0,
            score=0.75,
            is_pass=True,
        )
        case = _make_case()
        result = _run_evaluate(
            evaluator,
            predict={"answer": "42"},
            case=case,
            llm_response=response,
        )
        assert result.score == pytest.approx(0.75)

    def test_scores_clamped_to_unit_interval(self) -> None:
        evaluator = _make_evaluator()
        data = {
            "task_completion": 1.5,
            "trajectory_quality": -0.3,
            "safety": 0.5,
            "score": 0.5,
            "is_pass": True,
            "attributed_skill": "",
            "reason": "test",
        }
        response = f"```json\n{json.dumps(data)}\n```"
        case = _make_case()
        result = _run_evaluate(
            evaluator,
            predict={"answer": "42"},
            case=case,
            llm_response=response,
        )
        assert result.per_metric is not None
        assert result.per_metric["task_completion"] == 1.0
        assert result.per_metric["trajectory_quality"] == 0.0
        assert result.per_metric["safety"] == 0.5

    def test_is_pass_from_llm_output(self) -> None:
        """is_pass 取自 LLM 的 is_pass 字段。"""
        evaluator = _make_evaluator()
        response = _mock_llm_response(
            "failed",
            task_completion=0.25,
            trajectory_quality=0.5,
            safety=1.0,
            score=0.4,
            is_pass=False,
            attributed_skill="product_recommend_skill",
        )
        case = _make_case()
        result = _run_evaluate(
            evaluator,
            predict={"answer": "42"},
            case=case,
            llm_response=response,
        )
        reason_data = json.loads(result.reason)
        assert reason_data["is_pass"] is False
        assert reason_data["attributed_skill"] == "product_recommend_skill"


class TestEvaluatedCaseOutput:
    """EvaluatedCase 输出结构测试。"""

    def test_reason_populated(self) -> None:
        evaluator = _make_evaluator()
        response = _mock_llm_response(
            "部分完成",
            task_completion=0.7,
            trajectory_quality=0.8,
            safety=1.0,
            score=0.8,
            is_pass=True,
        )
        result = _run_evaluate(
            evaluator,
            predict={"answer": "42"},
            llm_response=response,
        )
        reason_data = json.loads(result.reason)
        assert reason_data["reason"] == "部分完成"

    def test_attributed_skill_in_reason_json(self) -> None:
        evaluator = _make_evaluator()
        response = _mock_llm_response(
            "ok",
            attributed_skill="product_recommend_skill",
            score=1.0,
        )
        result = _run_evaluate(
            evaluator,
            predict={"answer": "42"},
            llm_response=response,
        )
        reason_data = json.loads(result.reason)
        assert reason_data["attributed_skill"] == "product_recommend_skill"

    def test_answer_matches_prediction(self) -> None:
        evaluator = _make_evaluator()
        response = _mock_llm_response("ok", score=1.0)
        result = _run_evaluate(
            evaluator,
            llm_response=response,
        )
        assert result.answer == {"evaluation_source": "conversation_trajectory"}


class TestParseResult:
    """_parse_result 独立测试。"""

    def test_parse_result_extracts_essential_fields(self) -> None:
        evaluator = _make_evaluator()
        data = {
            "task_completion": 0.7,
            "trajectory_quality": 1.0,
            "safety": 1.0,
            "is_pass": True,
            "score": 0.85,
            "attributed_skill": "",
            "reason": "部分完成",
        }
        response = f"```json\n{json.dumps(data)}\n```"
        result = evaluator._parse_result(response)
        assert result.reason == "部分完成"
        assert result.is_pass is True
        assert result.score == pytest.approx(0.85)
        assert result.attributed_skill == ""

    def test_parse_result_best_effort_dimensions(self) -> None:
        """维度分数 best-effort——缺失时不报错，非法值被跳过。"""
        evaluator = _make_evaluator()
        data = {
            "task_completion": 0.5,
            "safety": 0.75,
            "is_pass": False,
            "score": 0.4,
            "attributed_skill": "some_skill",
            "reason": "missing trajectory_quality",
        }
        response = f"```json\n{json.dumps(data)}\n```"
        result = evaluator._parse_result(response)
        assert result["task_completion"] == 0.5
        assert result["safety"] == 0.75
        assert "trajectory_quality" not in result  # missing, not in per_metric

    def test_parse_result_nan_dimension_is_rejected(self) -> None:
        """NaN 维度值违反 evaluator schema。"""
        evaluator = _make_evaluator()
        data = {
            "task_completion": 0.5,
            "trajectory_quality": float("nan"),
            "safety": 0.75,
            "is_pass": False,
            "score": 0.4,
            "attributed_skill": "",
            "reason": "test",
        }
        response = f"```json\n{json.dumps(data)}\n```"
        with pytest.raises(EvaluationError, match="invalid JSON syntax"):
            evaluator._parse_result(response)

    def test_parse_result_non_numeric_score_raises(self) -> None:
        """非数值 score 必须报错。"""
        evaluator = _make_evaluator()
        secret = "sensitive-model-field-value"
        data = {"score": secret, "is_pass": True, "reason": "ok"}
        response = f"```json\n{json.dumps(data)}\n```"
        with pytest.raises(EvaluationError, match="missing valid 'score'") as exc_info:
            evaluator._parse_result(response)
        assert secret not in exc_info.value.safe_message

    def test_parse_result_nan_score_raises(self) -> None:
        """NaN score 必须报错。"""
        evaluator = _make_evaluator()
        data = {"score": float("nan"), "is_pass": True, "reason": "ok"}
        response = f"```json\n{json.dumps(data)}\n```"
        with pytest.raises(EvaluationError, match="invalid JSON syntax"):
            evaluator._parse_result(response)

    def test_parse_result_inf_score_raises(self) -> None:
        """Infinity score 必须报错。"""
        evaluator = _make_evaluator()
        data = {"score": float("inf"), "is_pass": True, "reason": "ok"}
        response = f"```json\n{json.dumps(data)}\n```"
        with pytest.raises(EvaluationError, match="invalid JSON syntax"):
            evaluator._parse_result(response)

    def test_parse_result_non_boolean_is_pass_raises(self) -> None:
        """非布尔 is_pass 必须报错。"""
        evaluator = _make_evaluator()
        data = {"score": 0.75, "is_pass": "yes", "reason": "ok"}
        response = f"```json\n{json.dumps(data)}\n```"
        with pytest.raises(EvaluationError, match="missing valid 'is_pass'"):
            evaluator._parse_result(response)


class TestAsyncEnvironment:
    def test_evaluate_inside_running_event_loop_invokes_model(self) -> None:
        evaluator = _make_evaluator()
        response = _mock_llm_response("ok", score=1.0)
        mock_response = type("Response", (), {"content": response})()

        async def run() -> EvaluatedCase:
            with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
                mock_invoke.return_value = mock_response
                case = _make_case()
                result = evaluator.evaluate(case, {"answer": "ok"})
                mock_invoke.assert_awaited_once()
                return result

        result = asyncio.run(run())
        assert result.per_metric is not None
        assert result.per_metric["task_completion"] == 1.0


class TestExtractJson:
    """JSON 提取工具函数测试。"""

    def test_from_code_block(self) -> None:
        text = 'some text\n```json\n{"key": "value"}\n```\nmore text'
        result = _extract_json(text)
        assert result == {"key": "value"}

    def test_from_plain_json(self) -> None:
        text = '{"key": "value"}'
        result = _extract_json(text)
        assert result == {"key": "value"}

    def test_returns_none_on_invalid(self) -> None:
        result = _extract_json("not json")
        assert result is None

    def test_nested_json_in_text(self) -> None:
        text = 'Here is the result: ```json\n{"a": 1, "b": 2}\n```'
        result = _extract_json(text)
        assert result == {"a": 1, "b": 2}


class TestValueError:
    """输入校验测试。"""

    def test_no_trajectory_raises(self) -> None:
        evaluator = _make_evaluator()
        case = Case(
            inputs={"trajectory": None, "skill_names": ["my_skill"]},
            label={"expected_result": None},
        )
        with pytest.raises(ValueError, match="trajectory"):
            evaluator.evaluate(case, {"answer": "42"})

    def test_missing_skill_names_raises_evaluation_error(self) -> None:
        evaluator = _make_evaluator()
        case = Case(
            inputs={"trajectory": _DEFAULT_TRAJECTORY},
            label={"expected_result": None},
        )
        with pytest.raises(EvaluationError, match="skill_names is required"):
            evaluator.evaluate(case, {"answer": "42"})

    def test_empty_skill_names_raises_evaluation_error(self) -> None:
        evaluator = _make_evaluator()
        case = Case(
            inputs={"trajectory": _DEFAULT_TRAJECTORY, "skill_names": []},
            label={"expected_result": None},
        )
        with pytest.raises(EvaluationError, match="skill_names is required"):
            evaluator.evaluate(case, {"answer": "42"})

    def test_rollout_failure_keeps_infrastructure_category(self) -> None:
        evaluator = _make_evaluator()

        with pytest.raises(EvaluationError) as exc_info:
            evaluator.evaluate(
                _make_case(),
                {"answer": "", "error": "RAW INTERNAL ROLLOUT ERROR"},
            )

        assert exc_info.value.category == "rollout_error"
        assert "RAW INTERNAL" not in exc_info.value.safe_message

    def test_empty_trajectory_is_trace_unavailable(self) -> None:
        evaluator = _make_evaluator()

        with pytest.raises(EvaluationError) as exc_info:
            evaluator.evaluate(
                _make_case(trajectory={"messages": []}),
                {"answer": "ok"},
            )

        assert exc_info.value.category == "trace_unavailable"


class TestPrompt:
    def test_prompt_contains_dimensions_and_pass_rules(self) -> None:
        prompt = build_evaluation_prompt(
            trajectory="执行轨迹",
        )

        # 维度名
        assert "task_completion" in prompt
        assert "trajectory_quality" in prompt
        assert "safety" in prompt

        # 五档评分
        assert "0.75" in prompt
        assert "0.25" in prompt

        # 通过标准
        assert "is_pass" in prompt

        # Skill 归因
        assert "attributed_skill" in prompt

    def test_prompt_with_skill_names_lists_them(self) -> None:
        prompt = build_evaluation_prompt(
            trajectory="traj",
            skill_names=["search_skill", "qa_skill"],
        )
        assert "## 可用 Skill 列表" in prompt
        assert "search_skill" in prompt
        assert "qa_skill" in prompt
        # Also present as comma-separated plain list for attribution
        assert "search_skill, qa_skill" in prompt

    def test_prompt_without_skill_names_omits_section(self) -> None:
        prompt = build_evaluation_prompt(trajectory="traj")
        assert "## 可用 Skill 列表" not in prompt


class TestFormatEvaluationPrompt:
    """format_evaluation_prompt — 直接模板填充测试。"""

    def test_conditional_sections_present(self) -> None:
        result = format_evaluation_prompt(
            DEFAULT_PROMPT_TEMPLATE,
            trajectory='{"steps": []}',
            expected_result='{"ok": true}',
        )
        assert "## 可选期望结果" in result
        assert '{"ok": true}' in result

    def test_conditional_sections_absent(self) -> None:
        result = format_evaluation_prompt(
            DEFAULT_PROMPT_TEMPLATE,
            trajectory="traj",
        )
        assert "## 可选期望结果" not in result
        assert "## 可用 Skill 列表" not in result

    def test_skill_names_section_present(self) -> None:
        result = format_evaluation_prompt(
            DEFAULT_PROMPT_TEMPLATE,
            trajectory="t",
            skill_names=["alpha_skill", "beta_skill"],
        )
        assert "## 可用 Skill 列表" in result
        assert "alpha_skill" in result
        assert "beta_skill" in result
        assert "alpha_skill, beta_skill" in result

    def test_messages_placeholder_filled(self) -> None:
        """{messages} 占位符被轨迹内容替换。"""
        result = format_evaluation_prompt(
            DEFAULT_PROMPT_TEMPLATE,
            trajectory="test trajectory content",
        )
        assert "test trajectory content" in result


class TestCustomPromptTemplate:
    """LLMEvaluator — 外部传入 prompt_template 端到端测试。"""

    def test_custom_template_used_in_evaluate(self) -> None:
        custom = "CUSTOM_PROMPT\n{messages}\n请返回 JSON。"
        evaluator = _make_evaluator(prompt_template=custom)
        response = _mock_llm_response(
            reason="custom test",
            score=0.75,
        )
        case = _make_case()
        mock_resp = type("Response", (), {"content": response})()

        with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.return_value = mock_resp
            result = evaluator.evaluate(case, {"answer": "ok"})

            mock_invoke.assert_called_once()
            messages = mock_invoke.call_args[0][0]
            prompt_text = messages[0].content
            assert "CUSTOM_PROMPT" in prompt_text
            assert result.score == pytest.approx(0.75)

    def test_bare_instruction_injected_into_default_template(self) -> None:
        """自定义 prompt 缺少占位符时，自动注入默认模板并保留数据段。"""
        bare_instruction = "你是一个专业的金融领域任务评估专家。请评估 Agent 的任务完成质量。"
        evaluator = _make_evaluator(prompt_template=bare_instruction)
        response = _mock_llm_response(
            reason="injected test",
            task_completion=0.8,
            trajectory_quality=1.0,
            safety=1.0,
            score=0.8,
            is_pass=True,
        )
        case = _make_case()
        mock_resp = type("Response", (), {"content": response})()

        with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.return_value = mock_resp
            result = evaluator.evaluate(case, {"answer": "ok"})

            mock_invoke.assert_called_once()
            prompt_text = mock_invoke.call_args[0][0][0].content
            # Custom instruction is present (injected as role description)
            assert bare_instruction in prompt_text
            # Default template's data sections and rules are also present
            assert "task_completion" in prompt_text
            # Score comes from LLM directly, not aggregated from dimension means
            assert result.score == pytest.approx(0.8)

    def test_default_template_used_when_no_custom(self) -> None:
        evaluator = _make_evaluator()
        response = _mock_llm_response(
            reason="default test",
            task_completion=0.5,
            trajectory_quality=0.75,
            safety=0.75,
            score=0.6,
            is_pass=True,
        )
        case = _make_case()
        mock_resp = type("Response", (), {"content": response})()

        with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.return_value = mock_resp
            result = evaluator.evaluate(case, {"a": 1})

            mock_invoke.assert_called_once()
            messages = mock_invoke.call_args[0][0]
            prompt_text = messages[0].content
            assert "评估准则" in prompt_text
            assert result.score == pytest.approx(0.6)


class TestEvaluateInput:
    """evaluate_input — unified EvaluationInput → EvaluationResult entry point."""

    def test_evaluate_input_returns_evaluation_result(self) -> None:
        from evo_agent.evaluator.domain.models import (
            EvaluationInput,
            StandardTrajectory,
            TrajectoryMessage,
        )
        from evo_agent.evaluator.domain.result import EvaluationResult

        evaluator = _make_evaluator()
        response = _mock_llm_response(
            "ok",
            task_completion=0.8,
            trajectory_quality=1.0,
            safety=1.0,
            score=0.85,
            is_pass=True,
        )
        mock_resp = type("Response", (), {"content": response})()

        inp = EvaluationInput(
            trajectory=StandardTrajectory(
                messages=[TrajectoryMessage(role="assistant", content="done")],
            ),
            skill_names=["product_recommend_skill"],
        )

        with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.return_value = mock_resp
            result = evaluator.evaluate_input(inp)

        assert isinstance(result, EvaluationResult)
        assert result.score == pytest.approx(0.85)
        assert result.is_pass is True
        assert result.per_metric == {
            "task_completion": 0.8,
            "trajectory_quality": 1.0,
            "safety": 1.0,
        }

    def test_evaluate_input_with_skill_name(self) -> None:
        from evo_agent.evaluator.domain.models import (
            EvaluationInput,
            StandardTrajectory,
            TrajectoryMessage,
        )
        from evo_agent.evaluator.domain.result import EvaluationResult

        evaluator = _make_evaluator()
        response = _mock_llm_response(
            "ok",
            score=0.75,
            is_pass=True,
        )
        mock_resp = type("Response", (), {"content": response})()

        inp = EvaluationInput(
            trajectory=StandardTrajectory(
                messages=[TrajectoryMessage(role="assistant", content="done")],
            ),
            skill_names=["product_recommend_skill"],
        )

        with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.return_value = mock_resp
            result = evaluator.evaluate_input(inp)

            prompt_text = mock_invoke.call_args[0][0][0].content
            assert "已识别 Skill 列表" not in prompt_text

        assert isinstance(result, EvaluationResult)
        assert "trajectory_quality" in (result.per_metric or {})


class TestAttributedSkillValidation:
    """_validate_attributed_skill — 归因 skill 必须在已知列表中。"""

    def test_empty_skill_passes(self) -> None:
        """空归因字符串不抛错。"""
        _validate_attributed_skill("", ["skill_a"])

    def test_known_skill_passes(self) -> None:
        """归因的 skill 在列表中，不抛错。"""
        _validate_attributed_skill("skill_a", ["skill_a", "skill_b"])

    def test_unknown_skill_raises(self) -> None:
        """归因的 skill 不在列表中，抛出 EvaluationError。"""
        with pytest.raises(EvaluationError, match="unknown_skill"):
            _validate_attributed_skill("unknown_skill", ["skill_a"])

    def test_empty_skills_list_with_nonempty_attribution_raises(self) -> None:
        """skills 列表为空但有归因时抛出错误。"""
        with pytest.raises(EvaluationError, match="any_skill"):
            _validate_attributed_skill("any_skill", [])


class TestAttributedSkillValidationE2E:
    """端到端测试：LLM 返回未知归因时 evaluate() 抛出 EvaluationError。"""

    def test_unknown_attribution_raises_in_evaluate(self) -> None:
        evaluator = _make_evaluator()
        data = {
            "reason": "ok",
            "score": 0.5,
            "is_pass": False,
            "attributed_skill": "hallucinated_skill",
            "task_completion": 0.5,
            "trajectory_quality": 0.5,
            "safety": 0.5,
        }
        response = json.dumps(data, ensure_ascii=False)
        case = _make_case(skill_names=["real_skill"])
        mock_response = type("Response", (), {"content": response})()

        with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.return_value = mock_response
            with pytest.raises(EvaluationError, match="hallucinated_skill"):
                evaluator.evaluate(case, {"answer": "done"})

    def test_known_attribution_passes_in_evaluate(self) -> None:
        evaluator = _make_evaluator()
        data = {
            "reason": "ok",
            "score": 0.75,
            "is_pass": True,
            "attributed_skill": "my_skill",
            "task_completion": 0.75,
            "trajectory_quality": 0.75,
            "safety": 1.0,
        }
        response = json.dumps(data, ensure_ascii=False)
        case = _make_case(skill_names=["my_skill"])
        mock_response = type("Response", (), {"content": response})()

        with patch.object(evaluator._model, "invoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.return_value = mock_response
            result = evaluator.evaluate(case, {"answer": "done"})
            assert result.score > 0
