"""SkillEvaluator mixin — shared ``evaluate_input()`` entry point."""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from evo_agent.evaluator.domain.models import EvaluationInput
    from evo_agent.evaluator.domain.result import EvaluationResult


class EvaluateInputMixin:
    """Mixin providing the unified ``evaluate_input()`` entry point.

    Any evaluator inheriting this mixin must implement
    ``evaluate(case, predict) -> EvaluatedCase`` (from ``BaseEvaluator``).
    """

    def evaluate_input(self, value: EvaluationInput) -> EvaluationResult:
        """Unified entry point: ``EvaluationInput`` -> ``EvaluationResult``.

        Converts the domain input to openjiuwen Case/predict, runs evaluation,
        and wraps the result in a framework-independent ``EvaluationResult``.
        """
        from evo_agent.evaluator.adapters.openjiuwen import to_case_and_placeholder
        from evo_agent.evaluator.domain.result import EvaluationResult

        case, placeholder = to_case_and_placeholder(value)
        evaluated = self.evaluate(case, placeholder)  # type: ignore[attr-defined]
        return EvaluationResult.from_evaluated_case(evaluated)
