"""Deterministic evaluator for the managed-document E2E boundary."""

from __future__ import annotations

import json
from typing import Any

from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase
from openjiuwen.agent_evolving.evaluator import BaseEvaluator


class RuleAnswerEvaluator(BaseEvaluator):
    """Score whether the live Agent answers with the rule version in the label."""

    def __init__(self, **_runtime: Any) -> None:
        # The manifest loader injects the run's model configuration into custom
        # evaluators.  This deterministic boundary intentionally does not use it.
        pass

    def evaluate(
        self,
        case: Case,
        predict: dict[str, Any],
        *,
        enable_attribution: bool = True,
    ) -> EvaluatedCase:
        expected = str(case.label["expected_result"])
        actual = str(predict.get("answer", ""))
        score = 1.0 if actual == expected else 0.0
        reason: dict[str, str] = {"comparison": f"expected={expected}, actual={actual}"}
        if enable_attribution and score < 1.0:
            reason["attributed_skill"] = "managed_doc:agent_rule"
        return EvaluatedCase(
            case=case,
            answer=predict,
            score=score,
            reason=json.dumps(reason, ensure_ascii=False),
        )

    def batch_evaluate(
        self,
        cases: list[Case] | Any,
        predicts: list[dict[str, Any]],
        num_parallel: int = 1,
        *,
        enable_attribution: bool = True,
    ) -> list[EvaluatedCase]:
        del num_parallel
        if len(cases) != len(predicts):
            raise ValueError("cases and predicts must have the same length")
        return [
            self.evaluate(case, predict, enable_attribution=enable_attribution)
            for case, predict in zip(cases, predicts, strict=True)
        ]
