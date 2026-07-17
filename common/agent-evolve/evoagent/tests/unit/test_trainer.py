"""EvoTrainer 单元测试 — sidecar-aware validation with trajectory injection."""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest
from openjiuwen.agent_evolving.trainer.progress import Callbacks

from evo_agent.conversation import ConversationIdFactory
from evo_agent.trainer import EvoTrainer


def _make_case(case_id: str, question: str = "Q") -> Any:
    from openjiuwen.agent_evolving.dataset import Case

    return Case(inputs={"question": question}, label={"answer": "A"}, case_id=case_id)


def _make_trainer(
    *,
    adapter_client: Any = None,
    conversation_id_factory: Any = None,
    updater: Any = None,
    evaluator: Any = None,
    trace_max_retries: int = 1,
    trace_retry_backoff: float = 0.0,
    empty_extract_max_attempts: int = 3,
    empty_extract_retry_backoff: float = 0.0,
    num_parallel: int = 2,
) -> EvoTrainer:
    """Build an EvoTrainer with sensible defaults for tests."""
    return EvoTrainer(
        adapter_client=adapter_client or AsyncMock(),
        conversation_id_factory=conversation_id_factory or ConversationIdFactory(run_id="test"),
        updater=updater or MagicMock(),
        evaluator=evaluator or MagicMock(),
        num_parallel=num_parallel,
        trace_max_retries=trace_max_retries,
        trace_retry_backoff=trace_retry_backoff,
        empty_extract_max_attempts=empty_extract_max_attempts,
        empty_extract_retry_backoff=empty_extract_retry_backoff,
    )


def test_evaluate_empty_cases_returns_zero() -> None:
    """空 cases 返回 (0.0, [])。"""
    trainer = _make_trainer()
    mock_cases = MagicMock()
    mock_cases.get_cases.return_value = []
    score, evaluated = trainer.evaluate(MagicMock(), mock_cases)
    assert score == 0.0
    assert evaluated == []


def test_evaluate_none_cases_returns_zero() -> None:
    """None cases 返回 (0.0, [])。"""
    trainer = _make_trainer()
    score, evaluated = trainer.evaluate(MagicMock(), None)
    assert score == 0.0
    assert evaluated == []


def test_evaluate_retries_when_extract_field_empty() -> None:
    """配置了 extract 时，字段为空会换 conversation_id 重试 invoke。"""
    from evo_agent.evaluator.factory import create_evaluator

    calls: list[str] = []

    async def mock_invoke(payload: dict[str, Any], **kwargs: Any) -> dict[str, str]:
        calls.append(payload["conversation_id"])
        if len(calls) == 1:
            return {"answer": "incomplete"}
        return {"answer": '<answer>{"responsibility": "无责"}</answer>'}

    agent = AsyncMock()
    agent.invoke = AsyncMock(side_effect=mock_invoke)

    messages = [{"role": "user", "content": "hi"}]
    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": messages, "summary": "s"})

    evaluator = create_evaluator(
        {
            "type": "metric",
            "metric": "exact_match",
            "extract": {
                "strategy": "answer_tag_json_field",
                "fields": ["responsibility"],
                "prefer_values": ["无责", "有责"],
            },
        }
    )
    evaluator.batch_evaluate = MagicMock(return_value=[])  # type: ignore[method-assign]

    trainer = _make_trainer(
        adapter_client=adapter,
        evaluator=evaluator,
        empty_extract_max_attempts=3,
    )
    mock_cases = MagicMock()
    mock_cases.get_cases.return_value = [_make_case("c1")]
    trainer.evaluate(agent, mock_cases)

    assert len(calls) == 2
    assert calls[0] != calls[1]
    # traces should be fetched for the successful conversation
    adapter.get_traces.assert_awaited()
    assert adapter.get_traces.await_args.kwargs["case_id"] == calls[-1]


def test_evaluate_uses_unique_conversation_ids() -> None:
    """evaluate() 为每个 case 使用唯一 conversation_id。"""
    invoked_ids: list[str] = []

    async def mock_invoke(payload: dict[str, Any], **kwargs: Any) -> dict[str, str]:
        invoked_ids.append(payload["conversation_id"])
        return {"answer": "ok"}

    agent = AsyncMock()
    agent.invoke = AsyncMock(side_effect=mock_invoke)

    messages = [{"role": "user", "content": "hi"}]
    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": messages, "summary": "s"})

    evaluator = MagicMock()
    evaluator.batch_evaluate = MagicMock(return_value=[])

    factory = ConversationIdFactory(run_id="run1")
    trainer = _make_trainer(
        adapter_client=adapter,
        conversation_id_factory=factory,
        evaluator=evaluator,
    )

    mock_cases = MagicMock()
    mock_cases.get_cases.return_value = [_make_case("c1"), _make_case("c2"), _make_case("c3")]

    # Use asyncio.run to drive the sync evaluate() which internally uses asyncio.run()
    # Since we're not in an event loop, this works
    trainer.evaluate(agent, mock_cases)

    # Each case should have a unique conversation_id
    assert len(invoked_ids) == 3
    assert len(set(invoked_ids)) == 3  # All unique
    # Format: run1:val:{n}:{case_id}
    for cid in invoked_ids:
        assert cid.startswith("run1:val:")


def test_evaluate_injects_trajectory() -> None:
    """evaluate() 构造带 trajectory 的 eval case copy。"""
    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "ok"})

    messages = [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}]
    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": messages, "summary": "test summary"})

    evaluator = MagicMock()
    evaluator.batch_evaluate = MagicMock(return_value=[])

    trainer = _make_trainer(adapter_client=adapter, evaluator=evaluator)

    mock_cases = MagicMock()
    mock_cases.get_cases.return_value = [_make_case("c1")]

    trainer.evaluate(agent, mock_cases)

    # batch_evaluate 收到的 cases 包含 trajectory
    call_args = evaluator.batch_evaluate.call_args
    eval_cases = call_args[0][0]  # First positional arg
    assert len(eval_cases) == 1
    assert "trajectory" in eval_cases[0].inputs
    # summary is now a TrajectorySummary dict (not a plain string)
    assert isinstance(eval_cases[0].inputs["trajectory"]["summary"], dict)
    assert eval_cases[0].inputs["trajectory"]["summary"]["summary"] == "test summary"
    assert isinstance(eval_cases[0].inputs["trajectory"]["messages"], list)


def test_evaluate_trajectory_missing_tolerated() -> None:
    """轨迹缺失时容忍缺失（使用空 trajectory），评估仍继续。"""
    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "ok"})

    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": []})  # Always empty

    evaluator = MagicMock()
    evaluator.batch_evaluate = MagicMock(return_value=[])

    trainer = _make_trainer(adapter_client=adapter, evaluator=evaluator, trace_max_retries=2)

    mock_cases = MagicMock()
    mock_cases.get_cases.return_value = [_make_case("c1")]

    # Should NOT raise; should complete evaluation with empty trajectory
    score, evaluated = trainer.evaluate(agent, mock_cases)
    assert isinstance(score, float)


def test_evaluate_concurrent_rollout() -> None:
    """并发执行 rollout，受 num_parallel 限制。"""
    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "ok"})

    messages = [{"role": "user", "content": "hi"}]
    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": messages, "summary": "s"})

    evaluator = MagicMock()
    evaluator.batch_evaluate = MagicMock(return_value=[])

    trainer = _make_trainer(
        adapter_client=adapter,
        evaluator=evaluator,
        num_parallel=2,
    )

    mock_cases = MagicMock()
    mock_cases.get_cases.return_value = [_make_case(f"c{i}") for i in range(5)]

    trainer.evaluate(agent, mock_cases)

    # All 5 cases were processed
    assert agent.invoke.call_count == 5
    # batch_evaluate received 5 cases
    call_args = evaluator.batch_evaluate.call_args
    eval_cases = call_args[0][0]
    assert len(eval_cases) == 5


def test_evaluate_trajectory_format_symmetric_with_rollout() -> None:
    """trajectory 注入格式与 _rollout() 对称: {"summary": ..., "messages": ...}。"""
    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "ok"})

    messages = [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "ok"}]
    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": messages, "summary": "sym test"})

    evaluator = MagicMock()
    evaluator.batch_evaluate = MagicMock(return_value=[])

    trainer = _make_trainer(adapter_client=adapter, evaluator=evaluator)

    mock_cases = MagicMock()
    mock_cases.get_cases.return_value = [_make_case("c1")]

    trainer.evaluate(agent, mock_cases)

    eval_cases = evaluator.batch_evaluate.call_args[0][0]
    traj = eval_cases[0].inputs["trajectory"]
    assert "summary" in traj
    assert "messages" in traj
    # summary is now a TrajectorySummary dict (not a plain string)
    assert isinstance(traj["summary"], dict)
    assert traj["summary"]["summary"] == "sym test"
    assert traj["messages"] == messages


def test_evaluate_original_case_unchanged() -> None:
    """evaluate() 不修改原始 case.inputs。"""
    agent = AsyncMock()
    agent.invoke = AsyncMock(return_value={"answer": "ok"})

    messages = [{"role": "user", "content": "hi"}]
    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": messages, "summary": "s"})

    evaluator = MagicMock()
    evaluator.batch_evaluate = MagicMock(return_value=[])

    trainer = _make_trainer(adapter_client=adapter, evaluator=evaluator)

    original_case = _make_case("c1")
    mock_cases = MagicMock()
    mock_cases.get_cases.return_value = [original_case]

    trainer.evaluate(agent, mock_cases)

    # 原始 case.inputs 不包含 "trajectory"
    assert "trajectory" not in original_case.inputs


def test_evaluate_reraises_cancelled_error() -> None:
    """asyncio.CancelledError 应被重新抛出，而不是静默丢弃结果。"""
    import asyncio

    agent = AsyncMock()
    call_count = 0

    async def mock_invoke(payload: dict[str, Any], **kwargs: Any) -> dict[str, str]:
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            raise asyncio.CancelledError()
        return {"answer": "ok"}

    agent.invoke = AsyncMock(side_effect=mock_invoke)

    messages = [{"role": "user", "content": "hi"}]
    adapter = AsyncMock()
    adapter.get_traces = AsyncMock(return_value={"messages": messages, "summary": "s"})

    evaluator = MagicMock()
    evaluator.batch_evaluate = MagicMock(return_value=[])

    trainer = _make_trainer(adapter_client=adapter, evaluator=evaluator)

    mock_cases = MagicMock()
    mock_cases.get_cases.return_value = [_make_case("c1"), _make_case("c2")]

    with pytest.raises(asyncio.CancelledError):
        trainer.evaluate(agent, mock_cases)


# ── Gate score capture (_select_best_candidate_on_val) ──


def _make_evaluated_case(case_id: str, score: float = 0.5) -> Any:
    from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

    case = Case(inputs={"question": "Q"}, label={"answer": "A"}, case_id=case_id)
    return EvaluatedCase(case=case, score=score, reason="test")


def _make_mock_operator() -> MagicMock:
    """Build a mock operator that supports get_state/load_state/set_parameter."""
    op = MagicMock()
    op.get_state.return_value = {"skill_content": "original"}
    return op


def test_select_best_candidate_records_both_scores() -> None:
    """2 candidates → _gate_epoch_scores has [{base_score, candidate_score}]."""
    trainer = _make_trainer()

    base_eval = _make_evaluated_case("c1", 0.3)
    cand_eval = _make_evaluated_case("c1", 0.7)

    # evaluate() returns different scores for each call
    trainer.evaluate = MagicMock(side_effect=[(0.3, [base_eval]), (0.7, [cand_eval])])  # type: ignore[method-assign]

    operators = {"skill_a": _make_mock_operator()}
    # apply_updates expects {(operator_id, target): value}
    candidates = [
        {("skill_a", "skill_content"): "base"},
        {("skill_a", "skill_content"): "candidate"},
    ]

    trainer._select_best_candidate_on_val(
        agent=MagicMock(),
        operators=operators,
        candidates=candidates,
        val_cases=MagicMock(),
    )

    assert len(trainer._gate_epoch_scores) == 1
    assert trainer._gate_epoch_scores[0] == {"base_score": 0.3, "candidate_score": 0.7}


def test_select_best_candidate_uses_cached_baseline_for_base_candidate() -> None:
    """Seeded baseline → base candidate is not re-evaluated."""
    trainer = _make_trainer()

    base_eval = _make_evaluated_case("c1", 0.6)
    cand_eval = _make_evaluated_case("c1", 0.8)
    trainer.record_validation_baseline(0.6, [base_eval])
    trainer.evaluate = MagicMock(return_value=(0.8, [cand_eval]))  # type: ignore[method-assign]

    operators = {"skill_a": _make_mock_operator()}
    candidates = [
        {("skill_a", "skill_content"): "base"},
        {("skill_a", "skill_content"): "candidate"},
    ]

    score, evaluated = trainer._select_best_candidate_on_val(
        agent=MagicMock(),
        operators=operators,
        candidates=candidates,
        val_cases=MagicMock(),
    )

    trainer.evaluate.assert_called_once()
    assert score == 0.8
    assert evaluated == [cand_eval]
    assert trainer._gate_epoch_scores == [{"base_score": 0.6, "candidate_score": 0.8}]


def test_select_best_candidate_picks_higher_score() -> None:
    """Candidate with higher val score wins and is restored."""
    trainer = _make_trainer()

    base_eval = _make_evaluated_case("c1", 0.8)
    cand_eval = _make_evaluated_case("c1", 0.4)

    # Base (0.8) beats candidate (0.4)
    trainer.evaluate = MagicMock(side_effect=[(0.8, [base_eval]), (0.4, [cand_eval])])  # type: ignore[method-assign]

    operators = {"skill_a": _make_mock_operator()}
    candidates = [
        {("skill_a", "skill_content"): "base"},
        {("skill_a", "skill_content"): "candidate"},
    ]

    score, evaluated = trainer._select_best_candidate_on_val(
        agent=MagicMock(),
        operators=operators,
        candidates=candidates,
        val_cases=MagicMock(),
    )

    assert score == 0.8
    assert evaluated == [base_eval]


def test_select_best_candidate_single_candidate_no_record() -> None:
    """1 candidate → no entry in _gate_epoch_scores (no comparison to make)."""
    trainer = _make_trainer()

    single_eval = _make_evaluated_case("c1", 0.5)
    trainer.evaluate = MagicMock(return_value=(0.5, [single_eval]))  # type: ignore[method-assign]

    operators = {"skill_a": _make_mock_operator()}
    candidates = [{("skill_a", "skill_content"): "only"}]

    trainer._select_best_candidate_on_val(
        agent=MagicMock(),
        operators=operators,
        candidates=candidates,
        val_cases=MagicMock(),
    )

    assert len(trainer._gate_epoch_scores) == 0


def test_select_best_candidate_empty_returns_evaluate() -> None:
    """0 candidates → delegates to self.evaluate(), no recording."""
    trainer = _make_trainer()

    eval_result = _make_evaluated_case("c1", 0.6)
    trainer.evaluate = MagicMock(return_value=(0.6, [eval_result]))  # type: ignore[method-assign]

    score, evaluated = trainer._select_best_candidate_on_val(
        agent=MagicMock(),
        operators={"skill_a": _make_mock_operator()},
        candidates=[],
        val_cases=MagicMock(),
    )

    assert score == 0.6
    assert evaluated == [eval_result]
    assert len(trainer._gate_epoch_scores) == 0


def test_multiple_epochs_accumulate_scores() -> None:
    """Second epoch reuses previous winner as the base score."""
    trainer = _make_trainer()

    # Epoch 1: base=0.3, candidate=0.7
    trainer.evaluate = MagicMock(side_effect=[(0.3, []), (0.7, [])])  # type: ignore[method-assign]
    trainer._select_best_candidate_on_val(
        agent=MagicMock(),
        operators={"s": _make_mock_operator()},
        candidates=[{("s", "skill_content"): "b"}, {("s", "skill_content"): "c"}],
        val_cases=MagicMock(),
    )

    # Epoch 2: base reuses previous winner=0.7, candidate=0.4
    trainer.evaluate = MagicMock(return_value=(0.4, []))  # type: ignore[method-assign]
    trainer._select_best_candidate_on_val(
        agent=MagicMock(),
        operators={"s": _make_mock_operator()},
        candidates=[{("s", "skill_content"): "b2"}, {("s", "skill_content"): "c2"}],
        val_cases=MagicMock(),
    )

    assert len(trainer._gate_epoch_scores) == 2
    assert trainer._gate_epoch_scores[0] == {"base_score": 0.3, "candidate_score": 0.7}
    assert trainer._gate_epoch_scores[1] == {"base_score": 0.7, "candidate_score": 0.4}


def test_gate_epoch_scores_property_returns_copy() -> None:
    """gate_epoch_scores property returns a copy, not the internal list."""
    trainer = _make_trainer()
    trainer._gate_epoch_scores.append({"base_score": 0.1, "candidate_score": 0.9})

    scores = trainer.gate_epoch_scores
    scores.clear()

    # Internal list is unaffected
    assert len(trainer._gate_epoch_scores) == 1


# ── Tie re-eval (1 chance, denoised via mean) ──


class _GateRecorder(Callbacks):  # type: ignore[misc]
    """Recording callback for on_gate_scored (non-vendor hook)."""

    def __init__(self) -> None:
        self.payloads: list[dict[str, Any]] = []

    def on_gate_scored(self, payload: dict[str, Any]) -> None:
        self.payloads.append(dict(payload))


def _make_trainer_with_recorder() -> tuple[EvoTrainer, _GateRecorder]:
    from evo_agent.callbacks import ComposedCallbacks

    recorder = _GateRecorder()
    trainer = EvoTrainer(
        adapter_client=AsyncMock(),
        conversation_id_factory=ConversationIdFactory(run_id="test"),
        updater=MagicMock(),
        evaluator=MagicMock(),
        num_parallel=2,
        trace_max_retries=1,
        trace_retry_backoff=0.0,
        callbacks=ComposedCallbacks(recorder),
    )
    return trainer, recorder


def _two_candidates() -> list[dict[tuple[str, str], str]]:
    return [
        {("skill_a", "skill_content"): "base"},
        {("skill_a", "skill_content"): "candidate"},
    ]


def test_tie_reval_candidate_wins_on_denoised_mean() -> None:
    """平局（cand==base）→ 重 eval 候选 1 次，均值 > base → 候选赢。"""
    trainer, recorder = _make_trainer_with_recorder()

    base_eval = _make_evaluated_case("c1", 0.6)
    cand_first = _make_evaluated_case("c1", 0.6)
    cand_reval = _make_evaluated_case("c1", 0.7)
    trainer.record_validation_baseline(0.6, [base_eval])
    # base 走缓存；候选 first=0.6（平局），reval=0.7 → 均值 0.65
    trainer.evaluate = MagicMock(  # type: ignore[method-assign]
        side_effect=[(0.6, [cand_first]), (0.7, [cand_reval])]
    )

    score, evaluated = trainer._select_best_candidate_on_val(
        agent=MagicMock(),
        operators={"skill_a": _make_mock_operator()},
        candidates=_two_candidates(),
        val_cases=MagicMock(),
    )

    assert trainer.evaluate.call_count == 2  # 候选 first + reval；base 走缓存
    assert score == pytest.approx(0.65)
    assert evaluated == [cand_reval]
    assert trainer._gate_epoch_scores == [
        {"base_score": 0.6, "candidate_score": pytest.approx(0.65)}
    ]
    assert recorder.payloads and recorder.payloads[0]["tie_revalued"] is True
    assert recorder.payloads[0]["candidate_score"] == pytest.approx(0.65)
    assert recorder.payloads[0]["candidate_score_first"] == pytest.approx(0.6)
    assert recorder.payloads[0]["candidate_score_reval"] == pytest.approx(0.7)


def test_tie_reval_base_wins_when_mean_below_base() -> None:
    """平局 → 重 eval 候选 1 次，均值 < base → base 赢（回退缓存 base）。"""
    trainer, _recorder = _make_trainer_with_recorder()

    base_eval = _make_evaluated_case("c1", 0.6)
    cand_first = _make_evaluated_case("c1", 0.6)
    cand_reval = _make_evaluated_case("c1", 0.5)
    trainer.record_validation_baseline(0.6, [base_eval])
    # 候选 first=0.6（平局），reval=0.5 → 均值 0.55 < 0.6
    trainer.evaluate = MagicMock(  # type: ignore[method-assign]
        side_effect=[(0.6, [cand_first]), (0.5, [cand_reval])]
    )

    score, evaluated = trainer._select_best_candidate_on_val(
        agent=MagicMock(),
        operators={"skill_a": _make_mock_operator()},
        candidates=_two_candidates(),
        val_cases=MagicMock(),
    )

    assert trainer.evaluate.call_count == 2
    assert score == 0.6  # base 赢，返回缓存 base 分
    assert evaluated == [base_eval]  # 回退到缓存 base evaluated
    assert trainer._gate_epoch_scores == [
        {"base_score": 0.6, "candidate_score": pytest.approx(0.55)}
    ]


def test_tie_reval_not_triggered_on_clear_win() -> None:
    """非平局（cand 严格高于 base）→ 不重 eval，evaluate 只调 1 次。"""
    trainer, recorder = _make_trainer_with_recorder()

    base_eval = _make_evaluated_case("c1", 0.6)
    cand_eval = _make_evaluated_case("c1", 0.8)
    trainer.record_validation_baseline(0.6, [base_eval])
    trainer.evaluate = MagicMock(return_value=(0.8, [cand_eval]))  # type: ignore[method-assign]

    score, evaluated = trainer._select_best_candidate_on_val(
        agent=MagicMock(),
        operators={"skill_a": _make_mock_operator()},
        candidates=_two_candidates(),
        val_cases=MagicMock(),
    )

    assert trainer.evaluate.call_count == 1  # 仅候选 first；无 reval
    assert score == 0.8
    assert evaluated == [cand_eval]
    assert recorder.payloads and recorder.payloads[0]["tie_revalued"] is False
    assert recorder.payloads[0]["candidate_score"] == pytest.approx(0.8)


def test_tie_reval_mean_equal_base_still_base_wins() -> None:
    """平局 → 重 eval 后均值仍 == base → 不严格大于 → base 赢（无递归）。"""
    trainer, _recorder = _make_trainer_with_recorder()

    base_eval = _make_evaluated_case("c1", 0.6)
    cand_first = _make_evaluated_case("c1", 0.6)
    cand_reval = _make_evaluated_case("c1", 0.6)
    trainer.record_validation_baseline(0.6, [base_eval])
    trainer.evaluate = MagicMock(  # type: ignore[method-assign]
        side_effect=[(0.6, [cand_first]), (0.6, [cand_reval])]
    )

    score, evaluated = trainer._select_best_candidate_on_val(
        agent=MagicMock(),
        operators={"skill_a": _make_mock_operator()},
        candidates=_two_candidates(),
        val_cases=MagicMock(),
    )

    # 均值 0.6 == base 0.6，不严格大于 → base 赢；且只重 eval 1 次（无递归）
    assert trainer.evaluate.call_count == 2
    assert score == 0.6
    assert evaluated == [base_eval]
