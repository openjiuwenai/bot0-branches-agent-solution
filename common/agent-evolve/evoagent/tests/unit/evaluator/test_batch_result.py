"""逐 case 评估 outcome 的 identity 契约测试。"""

from unittest.mock import MagicMock, patch

from openjiuwen.agent_evolving.dataset import Case

from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.evaluators.llm import LLMEvaluator


def test_detailed_batch_keeps_middle_failure_in_input_order() -> None:
    """失败项不得压缩后与后续 case/trajectory 错位。"""
    with patch("evo_agent.evaluator.evaluators.llm.Model") as model_class:
        model_class.return_value = MagicMock()
        evaluator = LLMEvaluator(MagicMock(), MagicMock())
    cases = [
        Case(
            inputs={"trajectory": {"messages": [{"role": "assistant", "content": str(i)}]}},
            label={"expected_result": None},
        )
        for i in range(3)
    ]
    evaluated = [MagicMock(case=cases[0]), MagicMock(case=cases[2])]
    with patch.object(
        evaluator,
        "evaluate",
        side_effect=[
            evaluated[0],
            EvaluationError(category="json_parse_error", safe_message="invalid JSON"),
            evaluated[1],
        ],
    ):
        result = evaluator.batch_evaluate_detailed(cases, [{}, {}, {}], num_parallel=1)

    assert [outcome.index for outcome in result.outcomes] == [0, 1, 2]
    assert [outcome.case_id for outcome in result.outcomes] == [case.case_id for case in cases]
    assert result.outcomes[1].evaluated is None
    assert result.outcomes[1].failure is not None
    assert result.outcomes[1].failure.category == "json_parse_error"
    assert result.successes == tuple(evaluated)
