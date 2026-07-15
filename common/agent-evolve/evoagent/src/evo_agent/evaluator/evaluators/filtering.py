"""Evaluator decorator that short-circuits deterministic bad cases."""

from __future__ import annotations

import concurrent.futures
import json
import logging
from typing import Any

from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase
from openjiuwen.agent_evolving.evaluator.evaluator import BaseEvaluator

from evo_agent.evaluator.adapters.openjiuwen import CONVERSATION_PREDICTION
from evo_agent.evaluator.domain.models import StandardTrajectory
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.evaluators.base import EvaluateInputMixin
from evo_agent.evaluator.filters.base import TrajectoryFilter
from evo_agent.evaluator.filters.models import FilterMatch

logger = logging.getLogger(__name__)


class FilteringEvaluator(EvaluateInputMixin, BaseEvaluator):  # type: ignore[misc]
    """Wraps an existing evaluator to short-circuit known bad cases.

    Filters inspect the trajectory deterministically before the delegate is
    called. Matched cases return a standard zero-score bad-case result; unmatched
    cases are delegated to the downstream evaluator unchanged.
    """

    def __init__(self, delegate: BaseEvaluator, filters: list[TrajectoryFilter]) -> None:
        if not filters:
            raise ValueError("FilteringEvaluator requires at least one filter")
        self._delegate = delegate
        self._filters = list(filters)

    def evaluate(self, case: Case, predict: dict[str, Any]) -> EvaluatedCase:
        """Evaluate a single case, short-circuiting if filters match."""
        trajectory = _trajectory_from_case(case)
        matches = self._inspect(trajectory, case)
        if matches:
            return _build_filtered_case(case, matches)
        return self._delegate.evaluate(case, predict)

    def batch_evaluate(
        self,
        cases: list[Case] | Any,
        predicts: list[dict[str, Any]],
        num_parallel: int = 1,
    ) -> list[EvaluatedCase]:
        """Evaluate a batch of cases in parallel, retaining filtered results in order.

        Delegate evaluation errors are logged and their cases are dropped, but
        filtered cases are always retained.
        """
        if len(cases) != len(predicts):
            raise ValueError(
                f"length of cases: {len(cases)} does not equal length of predicts: {len(predicts)}"
            )
        if not cases:
            return []

        workers = min(max(num_parallel, 1), len(cases))
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
            futures = [
                executor.submit(self.evaluate, case, predict)
                for case, predict in zip(cases, predicts)
            ]
            evaluated: list[EvaluatedCase] = []
            for future, case in zip(futures, cases):
                try:
                    evaluated.append(future.result())
                except EvaluationError as exc:
                    logger.warning("Evaluation skipped for case %s: %s", case.case_id, exc)
            return evaluated

    def _inspect(self, trajectory: StandardTrajectory, case: Case) -> list[FilterMatch]:
        """Run all filters and collect matches, wrapping filter errors."""
        matches: list[FilterMatch] = []
        for filter_ in self._filters:
            try:
                matches.extend(filter_.inspect(trajectory))
            except Exception as exc:
                raise EvaluationError(
                    f"Filter {filter_.name!r} failed for case {case.case_id}: {exc}"
                ) from exc
        return matches


def _trajectory_from_case(case: Case) -> StandardTrajectory:
    """Extract and validate the trajectory from case inputs."""
    trajectory_data = case.inputs.get("trajectory") if isinstance(case.inputs, dict) else None
    if trajectory_data is None:
        raise ValueError("FilteringEvaluator requires a trajectory in case.inputs.")
    return StandardTrajectory.model_validate(trajectory_data)


def _build_filtered_case(case: Case, matches: list[FilterMatch]) -> EvaluatedCase:
    """Construct a zero-score bad-case EvaluatedCase from filter matches."""
    evaluated = EvaluatedCase(case=case, answer=dict(CONVERSATION_PREDICTION))
    evaluated.score = 0.0
    evaluated.per_metric = {"filter_failure": 0.0}
    evaluated.reason = json.dumps(
        {
            "reason": "Trajectory matched pre-evaluation filter rules.",
            "status": "filtered",
            "is_pass": False,
            "attributed_skill": "",
            "filter_matches": [match.model_dump() for match in matches],
        },
        ensure_ascii=False,
    )
    return evaluated
