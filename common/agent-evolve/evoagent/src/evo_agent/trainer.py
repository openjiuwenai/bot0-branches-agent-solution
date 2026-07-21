"""EvoTrainer — sidecar-aware Trainer subclass with validation trajectory injection.

Overrides ``evaluate()`` to:
1. Use ConversationIdFactory for unique conversation_id per case
2. Execute async rollout (agent.invoke + adapter.get_traces)
3. Inject trajectory into case copy for batch_evaluate
4. Raise TrajectoryUnavailableError if traces are missing (no silent skip)
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Literal

from openjiuwen.agent_evolving.dataset import CaseLoader, EvaluatedCase
from openjiuwen.agent_evolving.trainer.trainer import Trainer

from evo_agent.cancellation import CancellationToken
from evo_agent.dataset.case import merge_extra_data
from evo_agent.errors import ValidationCoverageError
from evo_agent.evaluator.batch_result import EvaluationBatchResult, EvaluationOutcome
from evo_agent.evaluator.metrics.extract import extract_config_from_evaluator
from evo_agent.evaluator.trajectory.normalize import normalize_trace_to_trajectory
from evo_agent.optimizer.skill_document.types import (
    GateEpochArtifactInput,
    GateEvaluationRecord,
    ValidationCoverageFailureInput,
)
from evo_agent.rollout_invoke import invoke_with_empty_extract_retry
from evo_agent.types import TrajectoryUnavailableError

# Type aliases for _select_best_candidate_on_val override
_Operator = Any
_Updates = dict[str, Any]

logger = logging.getLogger(__name__)


def _require_messages(trace_data: dict[str, Any]) -> list[dict[str, Any]]:
    """Extract messages from trace_data, raising TrajectoryUnavailableError if empty."""
    messages: list[dict[str, Any]] = trace_data.get("messages", [])
    if not messages:
        raise TrajectoryUnavailableError("trace_data has no messages")
    return messages


class EvoTrainer(Trainer):  # type: ignore[misc]
    """Sidecar-aware Trainer subclass.

    Overrides ``evaluate()`` to execute conversations through Adapter sidecar,
    collect trajectories, and inject them into eval cases before batch evaluation.

    Parameters
    ----------
    adapter_client:
        AdapterClient instance for fetching cleaned traces.
    conversation_id_factory:
        ConversationIdFactory instance for generating unique conversation_ids.
    trace_max_retries:
        Maximum retry attempts for fetching valid trace data.
    trace_retry_backoff:
        Backoff interval (seconds) between retries.
    """

    def __init__(
        self,
        *,
        adapter_client: Any,
        conversation_id_factory: Any,
        skill_names: list[str] | None = None,
        rollout_extra_data: dict[str, Any] | None = None,
        trace_max_retries: int = 3,
        trace_retry_backoff: float = 1.0,
        empty_extract_max_attempts: int = 3,
        empty_extract_retry_backoff: float = 1.0,
        tie_reval_eps: float = 0.0,
        validation_max_case_attempts: int = 2,
        validation_min_success_ratio: float = 1.0,
        validation_require_same_case_set: bool = True,
        cancellation_token: CancellationToken | None = None,
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self._adapter_client = adapter_client
        self._conversation_id_factory = conversation_id_factory
        self._skill_names = skill_names or []
        self._rollout_extra_data = rollout_extra_data or {}
        self._trace_max_retries = trace_max_retries
        self._trace_retry_backoff = trace_retry_backoff
        self._empty_extract_max_attempts = max(int(empty_extract_max_attempts), 1)
        self._empty_extract_retry_backoff = float(empty_extract_retry_backoff)
        # 平局重 eval 阈值：|cand - base| <= eps 触发 1 次候选重 eval（均值降噪）。
        # 默认 0.0 = 仅精确平局触发；负值可禁用。
        self._tie_reval_eps = tie_reval_eps
        self._validation_max_case_attempts = validation_max_case_attempts
        self._validation_min_success_ratio = validation_min_success_ratio
        self._validation_require_same_case_set = validation_require_same_case_set
        self._cancellation_token = cancellation_token or CancellationToken()

        # Gate score capture: records {base_score, candidate_score} per epoch
        # where 2 candidates were evaluated. Index i → Trainer epoch i+1.
        self._gate_epoch_scores: list[dict[str, float]] = []
        self._cached_base_val_score: float | None = None
        self._cached_base_val_evaluated: list[EvaluatedCase] | None = None
        self._cached_base_batch: EvaluationBatchResult | None = None
        self._last_validation_batch: EvaluationBatchResult | None = None
        self._managed_doc_candidates: dict[str, list[str]] = {}

    @property
    def gate_epoch_scores(self) -> list[dict[str, float]]:
        """Per-epoch gate scores: [{base_score, candidate_score}, ...].

        Index i corresponds to Trainer epoch i+1 (artifact dir ``epoch_{i+1}``).
        Only populated when exactly 2 candidates were evaluated (base + candidate).
        """
        return list(self._gate_epoch_scores)

    def managed_doc_epoch_contents(self, operator_id: str) -> tuple[str, ...]:
        """Return immutable candidate contents captured before validation."""
        return tuple(self._managed_doc_candidates.get(operator_id, ()))

    def record_validation_baseline(
        self,
        score: float,
        evaluated: list[EvaluatedCase],
    ) -> None:
        """Seed the validation gate cache with an already evaluated base state."""
        self._cached_base_val_score = score
        self._cached_base_val_evaluated = list(evaluated)
        if self._last_validation_batch is not None and {
            outcome.case_id for outcome in self._last_validation_batch.outcomes
        } == {_evaluated_case_id(item) for item in evaluated}:
            self._cached_base_batch = self._last_validation_batch

    def _select_best_candidate_on_val(
        self,
        *,
        agent: Any,
        operators: dict[str, _Operator],
        candidates: list[_Updates],
        val_cases: CaseLoader,
    ) -> tuple[float, list[EvaluatedCase]]:
        """Candidate evaluation with per-candidate score recording.

        Replicates the parent's gate logic to capture both base and candidate
        val scores. The parent only returns the winner's score, losing the
        other — this override records all scores in ``_gate_epoch_scores``.
        """
        if not candidates:
            self._cancellation_token.raise_if_requested()
            return self.evaluate(agent, val_cases)

        base_state = self._snapshot_operators_state(operators)
        val_case_count = len(val_cases.get_cases()) if val_cases is not None else 0

        best_score = float("-inf")
        best_evaluated: list[EvaluatedCase] = []
        best_state: dict[str, dict[str, Any]] | None = None

        scores: list[float] = []
        evaluated_by_candidate: list[list[EvaluatedCase]] = []
        states_by_candidate: list[dict[str, dict[str, Any]]] = []
        batches_by_candidate: list[EvaluationBatchResult] = []
        for candidate_index, cand_updates in enumerate(candidates):
            self._cancellation_token.raise_if_requested()
            self._restore_operators_state(operators, base_state)
            self.apply_updates(operators, cand_updates or {})
            self._cancellation_token.raise_if_requested()
            # Capture exactly one evaluated state per epoch: the optimized
            # candidate for [base, candidate], or the unchanged state when the
            # optimizer produced only one candidate. Capture happens before
            # validation can restore a winner, preserving rejected rounds.
            if candidate_index == len(candidates) - 1:
                for operator_id, operator in operators.items():
                    if not operator_id.startswith("managed_doc:"):
                        continue
                    content = operator.get_state().get("skill_content")
                    if isinstance(content, str):
                        self._managed_doc_candidates.setdefault(operator_id, []).append(content)

            cached_base_score = self._cached_base_val_score
            cached_base_evaluated = self._cached_base_val_evaluated
            use_cached_base = (
                candidate_index == 0
                and len(candidates) == 2
                and cached_base_score is not None
                and cached_base_evaluated is not None
            )
            started = time.perf_counter()
            try:
                if (
                    use_cached_base
                    and cached_base_score is not None
                    and cached_base_evaluated is not None
                ):
                    cand_score = cached_base_score
                    cand_evaluated = list(cached_base_evaluated)
                else:
                    cand_score, cand_evaluated = self.evaluate(agent, val_cases)
            except ValidationCoverageError as error:
                failed_batch = self._last_validation_batch or EvaluationBatchResult(())
                self._restore_operators_state(operators, base_state)
                if batches_by_candidate:
                    base_batch = batches_by_candidate[0]
                    candidate_batches = tuple(batches_by_candidate[1:] + [failed_batch])
                else:
                    base_batch = failed_batch
                    candidate_batches = ()
                self._notify_validation_coverage_failed(
                    ValidationCoverageFailureInput(
                        base_batch=base_batch,
                        candidate_batches=candidate_batches,
                        error=error,
                    )
                )
                raise
            elapsed = time.perf_counter() - started
            scores.append(cand_score)
            evaluated_by_candidate.append(cand_evaluated)
            states_by_candidate.append(self._snapshot_operators_state(operators))
            if use_cached_base and self._cached_base_batch is not None:
                batches_by_candidate.append(self._cached_base_batch)
            else:
                batches_by_candidate.append(
                    _matching_or_synthetic_batch(self._last_validation_batch, cand_evaluated)
                )
            logger.info(
                "[timing] validation.candidate index=%d source=%s cases=%d "
                "num_parallel=%d score=%.4f elapsed=%.3fs",
                candidate_index,
                "cache" if use_cached_base else "evaluate",
                val_case_count,
                self._num_parallel,
                cand_score,
                elapsed,
            )

            if cand_score > best_score:
                best_score = cand_score
                best_evaluated = cand_evaluated
                best_state = self._snapshot_operators_state(operators)

        # Gate scoring with tie re-eval (1 chance, denoised via mean).
        # Only the 2-candidate (base + optimized) path produces a gate record;
        # base runs from cache, candidate is freshly evaluated. On exact tie
        # (|cand - base| <= eps), re-evaluate the candidate ONCE and compare
        # the denoised mean against base — no recursion.
        if len(scores) == 2:
            try:
                self._require_comparable_validation(
                    evaluated_by_candidate[0],
                    evaluated_by_candidate[1],
                    attempted_count=val_case_count,
                )
            except ValidationCoverageError as error:
                retained_state = states_by_candidate[0] if states_by_candidate else base_state
                self._restore_operators_state(operators, retained_state)
                self._notify_validation_coverage_failed(
                    ValidationCoverageFailureInput(
                        base_batch=batches_by_candidate[0],
                        candidate_batches=tuple(batches_by_candidate[1:]),
                        error=error,
                    )
                )
                raise
            base_score = scores[0]
            cand_score = scores[1]
            gate_payload: dict[str, Any] = {
                "base_score": base_score,
                "candidate_score": cand_score,
                "tie_revalued": False,
            }
            if abs(cand_score - base_score) <= self._tie_reval_eps:
                # Candidate is currently applied (last loop iteration left it
                # in place); re-evaluate once without re-applying.
                reval_started = time.perf_counter()
                self._cancellation_token.raise_if_requested()
                cand2_score, cand2_evaluated = self.evaluate(agent, val_cases)
                logger.info(
                    "[timing] validation.candidate index=1 source=reval "
                    "cases=%d num_parallel=%d score=%.4f elapsed=%.3fs",
                    val_case_count,
                    self._num_parallel,
                    cand2_score,
                    time.perf_counter() - reval_started,
                )
                cand_mean = (cand_score + cand2_score) / 2
                reval_batch = _matching_or_synthetic_batch(
                    self._last_validation_batch, cand2_evaluated
                )
                gate_payload = {
                    "base_score": base_score,
                    "candidate_score": cand_mean,
                    "candidate_score_first": cand_score,
                    "candidate_score_reval": cand2_score,
                    "tie_revalued": True,
                }
                if cand_mean > base_score:
                    # Candidate wins on the denoised mean.
                    best_score = cand_mean
                    best_evaluated = cand2_evaluated
                    best_state = self._snapshot_operators_state(operators)
                    batches_by_candidate[1] = reval_batch
                else:
                    # The first candidate sample may have provisionally won.
                    # Re-evaluation is authoritative for the gate, so restore
                    # every selected value to the base when its mean loses.
                    best_score = base_score
                    best_evaluated = evaluated_by_candidate[0]
                    best_state = states_by_candidate[0]
            # Keep the canonical pair for reporting; candidate_score is the
            # denoised mean when tie re-evaluation ran.
            self._gate_epoch_scores.append(
                {
                    "base_score": base_score,
                    "candidate_score": gate_payload["candidate_score"],
                }
            )
            self._notify_gate_scored(gate_payload)
            decision: Literal["base", "candidate"] = (
                "candidate" if gate_payload["candidate_score"] > base_score else "base"
            )
            gate_record = GateEvaluationRecord(
                base_score=base_score,
                candidate_score=gate_payload["candidate_score"],
                decision=decision,
                tie_revalued=bool(gate_payload["tie_revalued"]),
                candidate_score_first=gate_payload.get("candidate_score_first"),
                candidate_score_reval=gate_payload.get("candidate_score_reval"),
            )
            selected_batch = batches_by_candidate[1 if decision == "candidate" else 0]
            self._last_validation_batch = selected_batch
            self._notify_gate_artifact_ready(
                GateEpochArtifactInput(
                    gate=gate_record,
                    base_batch=batches_by_candidate[0],
                    candidate_batches=tuple(batches_by_candidate[1:]),
                    selected_batch=selected_batch,
                )
            )

        if best_state is not None:
            self._restore_operators_state(operators, best_state)
            self.record_validation_baseline(best_score, best_evaluated)
            return best_score, best_evaluated

        self._restore_operators_state(operators, base_state)
        return self.evaluate(agent, val_cases)

    def _notify_gate_scored(self, payload: dict[str, Any]) -> None:
        """Push gate scores (base/candidate/tie info) to callbacks for SSE.

        Uses getattr so plain vendor ``Callbacks`` (without the hook) are
        tolerated; ``ComposedCallbacks`` forwards to subscribers that
        implement ``on_gate_scored`` (e.g. ``ProgressCallback``).
        """
        notify = getattr(self._callbacks, "on_gate_scored", None)
        if not callable(notify):
            return
        try:
            notify(payload)
        except Exception:
            logger.warning("on_gate_scored callback failed", exc_info=True)

    def _notify_validation_coverage_failed(self, failure: ValidationCoverageFailureInput) -> None:
        notify = getattr(self._callbacks, "on_validation_coverage_failed", None)
        if callable(notify):
            try:
                notify(failure)
            except Exception:
                logger.error(
                    "validation coverage diagnostics failed; preserving original error",
                    exc_info=True,
                )

    def _notify_gate_artifact_ready(self, artifact_input: GateEpochArtifactInput) -> None:
        notify = getattr(self._callbacks, "on_gate_artifact_ready", None)
        if callable(notify):
            notify(artifact_input)

    def _require_comparable_validation(
        self,
        base: list[EvaluatedCase],
        candidate: list[EvaluatedCase],
        *,
        attempted_count: int,
    ) -> None:
        denominator = attempted_count or max(len(base), len(candidate))
        for label, evaluated in (("base", base), ("candidate", candidate)):
            coverage = len(evaluated) / denominator if denominator else 1.0
            if coverage < self._validation_min_success_ratio:
                raise ValidationCoverageError(
                    attempted_count=denominator,
                    evaluated_count=len(evaluated),
                    min_success_ratio=self._validation_min_success_ratio,
                    reason=f"{label}_coverage_below_minimum",
                )
        if self._validation_require_same_case_set:
            base_ids = {_evaluated_case_id(item) for item in base}
            candidate_ids = {_evaluated_case_id(item) for item in candidate}
            if base_ids != candidate_ids:
                raise ValidationCoverageError(
                    attempted_count=denominator,
                    evaluated_count=min(len(base), len(candidate)),
                    min_success_ratio=self._validation_min_success_ratio,
                    reason="case_set_mismatch",
                )

    async def _get_required_trace(self, conversation_id: str) -> dict[str, Any]:
        """Fetch trace data with retry + backoff.

        Raises TrajectoryUnavailableError if no valid traces after max retries.
        """
        for attempt in range(self._trace_max_retries):
            self._cancellation_token.raise_if_requested()
            trace_data: dict[str, Any] = await self._adapter_client.get_traces(
                case_id=conversation_id,
            )
            try:
                _require_messages(trace_data)
                return trace_data
            except TrajectoryUnavailableError:
                if attempt < self._trace_max_retries - 1:
                    await asyncio.sleep(self._trace_retry_backoff)
        raise TrajectoryUnavailableError(
            f"No valid traces after {self._trace_max_retries} attempts "
            f"for conversation_id={conversation_id}"
        )

    async def _predict_and_build_eval_cases(
        self, agent: Any, cases: list[Any]
    ) -> tuple[list[dict[str, Any]], list[Any]]:
        """Execute rollout concurrently and build eval cases with trajectory injected.

        Returns (predictions, eval_cases_with_trajectory).
        """
        sem = asyncio.Semaphore(min(self._num_parallel, len(cases)))

        extract_cfg = extract_config_from_evaluator(self._evaluator)

        async def _rollout_one(case: Any) -> tuple[dict[str, Any], Any]:
            async with sem:
                self._cancellation_token.raise_if_requested()
                case_extra = case.inputs.get("extra_data", {})
                extra = merge_extra_data(self._rollout_extra_data, case_extra)

                async def _invoke_once(conversation_id: str) -> dict[str, Any]:
                    try:
                        result = await agent.invoke(
                            {
                                **case.inputs,
                                "conversation_id": conversation_id,
                                "extra_data": extra,
                            },
                        )
                    except Exception as exc:
                        logger.warning(
                            "Validation invoke failed for case=%s conversation_id=%s: %s",
                            case.case_id,
                            conversation_id,
                            exc,
                        )
                        return {"answer": "", "error": str(exc)}
                    return result if isinstance(result, dict) else {"answer": str(result)}

                answer, conversation_id = await invoke_with_empty_extract_retry(
                    invoke_once=_invoke_once,
                    new_conversation_id=lambda: self._conversation_id_factory.new(
                        phase="val", case_id=case.case_id
                    ),
                    extract_cfg=extract_cfg,
                    max_attempts=self._empty_extract_max_attempts,
                    backoff_secs=self._empty_extract_retry_backoff,
                    case_id=case.case_id,
                    phase="val",
                )

                # Do not interrupt an already-started Agent call. Once it has
                # reached a terminal response, stop before scheduling trace work.
                self._cancellation_token.raise_if_requested()

                # Fetch trace with retry — 容忍缺失，给空 trajectory
                try:
                    trace_data = await self._get_required_trace(conversation_id)
                    trajectory_dict = normalize_trace_to_trajectory(trace_data)
                except TrajectoryUnavailableError as exc:
                    logger.warning("Validation trace unavailable for %s: %s", case.case_id, exc)
                    trajectory_dict = {"messages": [], "summary": {}}

                # Build case copy with normalized trajectory + skill_names.
                # deep=False：inputs 是新建 dict（{**case.inputs, ...}），已隔离；
                # 避免对大 case 集做深拷贝。
                eval_case = case.model_copy(
                    update={
                        "inputs": {
                            **case.inputs,
                            "trajectory": trajectory_dict,
                            "skill_names": self._skill_names,
                        }
                    },
                    deep=False,
                )

                return answer, eval_case

        results = await asyncio.gather(*[_rollout_one(c) for c in cases], return_exceptions=True)
        # Re-raise cancellations immediately (CancelledError is BaseException, not Exception)
        for r in results:
            if isinstance(r, asyncio.CancelledError):
                raise r
        # Re-raise the first exception (preserves "validation fails on missing trace" semantics)
        for r in results:
            if isinstance(r, Exception):
                raise r
        # After re-raising, filter out any remaining BaseException (SystemExit, etc.)
        successful = [r for r in results if not isinstance(r, BaseException)]
        predicts = [r[0] for r in successful]
        eval_cases = [r[1] for r in successful]
        return predicts, eval_cases

    def evaluate(self, agent: Any, cases: CaseLoader | None) -> tuple[float, list[EvaluatedCase]]:
        """Run inference via sidecar, inject trajectories, and evaluate.

        Unlike the base Trainer, this uses ConversationIdFactory for unique
        conversation_ids and injects trajectory data from Adapter sidecar.

        Raises TrajectoryUnavailableError if traces are missing (no silent skip
        to avoid selection bias in validation).
        """
        if cases is None or not cases.get_cases():
            return 0.0, []

        self._cancellation_token.raise_if_requested()

        case_list = cases.get_cases()
        total_started = time.perf_counter()

        async def _run() -> tuple[float, list[EvaluatedCase]]:
            rollout_started = time.perf_counter()
            predicts, eval_cases = await self._predict_and_build_eval_cases(agent, case_list)
            self._cancellation_token.raise_if_requested()
            rollout_elapsed = time.perf_counter() - rollout_started
            eval_started = time.perf_counter()
            detailed_evaluate = vars(self._evaluator).get("batch_evaluate_detailed")
            if detailed_evaluate is None:
                detailed_method = getattr(type(self._evaluator), "batch_evaluate_detailed", None)
                if detailed_method is not None:
                    detailed_evaluate = detailed_method.__get__(self._evaluator)
            if callable(detailed_evaluate):
                batch = self._evaluate_detailed_with_retries(
                    detailed_evaluate,
                    eval_cases,
                    predicts,
                )
                self._last_validation_batch = batch
                if batch.coverage < self._validation_min_success_ratio:
                    raise ValidationCoverageError(
                        attempted_count=batch.attempted_count,
                        evaluated_count=batch.evaluated_count,
                        min_success_ratio=self._validation_min_success_ratio,
                        reason="validation_coverage_below_minimum",
                    )
                evaluated = list(batch.successes)
            else:
                evaluated = self._evaluator.batch_evaluate(
                    eval_cases,
                    predicts,
                    num_parallel=self._num_parallel,
                    enable_attribution=False,  # B2: validation 不做归因，缩小 prompt
                )
            eval_elapsed = time.perf_counter() - eval_started
            # _mean_score (mean) remains the default validation score. When the
            # evaluator has batch aggregation configured (batch_score set to a
            # non-empty str), use the coexisting aggregate_score (micro F1/ACC/...)
            # instead — a user-selectable extension, not a replacement of
            # _mean_score. The isinstance guard keeps mock / non-metric evaluators
            # on the mean path.
            batch_score = getattr(self._evaluator, "batch_score", "")
            if isinstance(batch_score, str) and batch_score:
                score = self._evaluator.aggregate_score(evaluated)
            else:
                score = self._mean_score(evaluated)
            logger.info(
                "[timing] validation.evaluate cases=%d num_parallel=%d rollout=%.3fs "
                "batch_evaluate=%.3fs score=%.4f",
                len(case_list),
                self._num_parallel,
                rollout_elapsed,
                eval_elapsed,
                score,
            )
            return score, evaluated

        # Reset async HTTP client bound to a (now-closed) event loop.
        # Each asyncio.run() creates a new loop; httpx.AsyncClient.is_closed
        # stays False after the loop closes, so the stale client gets reused
        # and raises "Event loop is closed".
        self._adapter_client._async_http = None
        self._adapter_client._async_http_loop = None

        try:
            return asyncio.run(_run())
        finally:
            logger.info(
                "[timing] validation.evaluate.total cases=%d num_parallel=%d elapsed=%.3fs",
                len(case_list),
                self._num_parallel,
                time.perf_counter() - total_started,
            )

    def _evaluate_detailed_with_retries(
        self,
        detailed_evaluate: Any,
        eval_cases: list[Any],
        predicts: list[dict[str, Any]],
    ) -> EvaluationBatchResult:
        pending_indexes = list(range(len(eval_cases)))
        completed: dict[int, EvaluationOutcome] = {}
        for _attempt in range(self._validation_max_case_attempts):
            self._cancellation_token.raise_if_requested()
            if not pending_indexes:
                break
            attempt_cases = [eval_cases[index] for index in pending_indexes]
            attempt_predicts = [predicts[index] for index in pending_indexes]
            attempt_batch = detailed_evaluate(
                attempt_cases,
                attempt_predicts,
                num_parallel=self._num_parallel,
                enable_attribution=False,
            )
            next_pending: list[int] = []
            for local_outcome in attempt_batch.outcomes:
                original_index = pending_indexes[local_outcome.index]
                completed[original_index] = EvaluationOutcome(
                    index=original_index,
                    case_id=local_outcome.case_id,
                    case=local_outcome.case,
                    trajectory=local_outcome.trajectory,
                    evaluated=local_outcome.evaluated,
                    failure=local_outcome.failure,
                )
                if local_outcome.evaluated is None:
                    next_pending.append(original_index)
            pending_indexes = next_pending
        return EvaluationBatchResult(tuple(completed[index] for index in sorted(completed)))


def _evaluated_case_id(evaluated: EvaluatedCase) -> str:
    case = getattr(evaluated, "case", None)
    return str(getattr(case, "case_id", getattr(evaluated, "case_id", "")))


def _matching_or_synthetic_batch(
    batch: EvaluationBatchResult | None,
    evaluated: list[EvaluatedCase],
) -> EvaluationBatchResult:
    ids = {_evaluated_case_id(item) for item in evaluated}
    if batch is not None and {outcome.case_id for outcome in batch.outcomes} == ids:
        return batch
    outcomes = []
    for index, item in enumerate(evaluated):
        case = item.case
        trajectory = case.inputs.get("trajectory", {"messages": []})
        outcomes.append(
            EvaluationOutcome(
                index=index,
                case_id=case.case_id,
                case=case,
                trajectory=trajectory,
                evaluated=item,
                failure=None,
            )
        )
    return EvaluationBatchResult(tuple(outcomes))
