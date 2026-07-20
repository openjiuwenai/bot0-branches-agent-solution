"""训练 rollout 对详细 evaluator outcome 的消费契约。"""

from unittest.mock import AsyncMock, MagicMock, patch

from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

from evo_agent.evaluator.batch_result import (
    EvaluationBatchResult,
    EvaluationFailure,
    EvaluationOutcome,
)
from evo_agent.optimizer.skill_document.skill_document_optimizer import (
    SkillDocumentOptimizer,
)


async def test_training_rollout_keeps_failures_and_returns_only_success_signals() -> None:
    """失败 outcome 保留诊断，但不得进入后续 reflect 成功列表。"""
    cases = [
        Case(inputs={"question": "one"}, label={"answer": "1"}, case_id="c1"),
        Case(inputs={"question": "two"}, label={"answer": "2"}, case_id="c2"),
    ]
    success = EvaluatedCase(case=cases[0], answer={"answer": "1"}, score=0.9)
    batch = EvaluationBatchResult(
        (
            EvaluationOutcome(0, "c1", cases[0], {"messages": []}, success, None),
            EvaluationOutcome(
                1,
                "c2",
                cases[1],
                {"messages": []},
                None,
                EvaluationFailure(
                    category="json_parse_error",
                    safe_message="invalid evaluator JSON",
                    invocation_id="inv-c2",
                    response_sha256="sha-c2",
                    response_chars=17,
                ),
            ),
        )
    )
    evaluator = MagicMock()
    evaluator.batch_evaluate_detailed = MagicMock(return_value=batch)
    optimizer = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)
    optimizer._agent = AsyncMock()
    optimizer._agent.invoke = AsyncMock(return_value={"answer": "ok"})
    optimizer._evaluator = evaluator
    optimizer._extractor = MagicMock()
    optimizer._extractor.extract.side_effect = lambda _session, case_id: MagicMock(case_id=case_id)
    optimizer._num_parallel = 2

    with patch(
        "openjiuwen.core.session.agent.create_agent_session",
        side_effect=lambda: MagicMock(),
    ):
        evaluated, trajectories = await optimizer._rollout(cases)

    assert evaluated == [success]
    assert len(trajectories) == 2
    assert optimizer._last_training_batch == batch
    evaluator.batch_evaluate_detailed.assert_called_once_with(
        cases,
        [{"answer": "ok"}, {"answer": "ok"}],
        num_parallel=2,
    )
    evaluator.batch_evaluate.assert_not_called()
