"""GEPA adapter — evaluation + trajectory extraction abstraction.

Implements the ``GEPAAdapter`` protocol from the open-source GEPA:
- ``evaluate``: run a candidate prompt on a batch of cases, return scores + traces
- ``make_reflective_dataset``: build per-component reflection input from traces

For EvoAgent integration, the adapter wraps the vision model invocation and
metric evaluator, producing the structured data that the reflection engine
consumes.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

from evo_agent.optimizer.gepa.utils import build_multimodal_content as _build_multimodal_content

logger = logging.getLogger(__name__)


class EvaluationBatch:
    """Results of evaluating a candidate on a minibatch."""

    def __init__(
        self,
        *,
        outputs: list[dict[str, Any]],
        scores: list[float],
        trajectories: list[dict[str, Any]] | None = None,
        case_ids: list[str] | None = None,
    ) -> None:
        self.outputs = outputs
        self.scores = scores
        self.trajectories = trajectories or []
        self.case_ids = case_ids or []


class GEPAAdapter:
    """Vision-model adapter for GEPA.

    Bridges GEPA's evaluate/make_reflective_dataset protocol to EvoAgent's
    vision model invocation + metric evaluator.

    Parameters
    ----------
    vision_model:
        The multimodal ``Model`` instance (e.g. GPT-4o) for rollout.
    evaluator:
        The metric evaluator for scoring model output vs ground truth.
    num_parallel:
        Max concurrent vision model calls.
    component_name:
        Name of the text component being optimized (default ``"system_prompt"``).
    """

    def __init__(
        self,
        *,
        vision_model: Any,
        evaluator: Any,
        num_parallel: int = 4,
        component_name: str = "system_prompt",
    ) -> None:
        self._vision_model = vision_model
        self._evaluator = evaluator
        self._semaphore = asyncio.Semaphore(num_parallel)
        self._component_name = component_name

    async def evaluate(
        self,
        candidate: dict[str, str],
        cases: list[Case],
        *,
        capture_traces: bool = False,
    ) -> EvaluationBatch:
        """Evaluate a candidate prompt on a minibatch of cases.

        Returns per-case ``scores`` and, if ``capture_traces=True``, also
        returns ``trajectories`` for the reflection engine.
        """
        prompt = candidate.get(self._component_name, "")

        async def _rollout_one(case: Case) -> dict[str, Any]:
            async with self._semaphore:
                return await self._invoke_vision_model(prompt, case)

        results = await asyncio.gather(*[_rollout_one(c) for c in cases])

        # Build eval cases + answers
        eval_cases: list[Case] = []
        answers: list[dict[str, Any]] = []
        for case, result in zip(cases, results):
            case_for_eval = case.model_copy(
                update={"inputs": {**case.inputs, "skill_names": [self._component_name]}},
                deep=False,
            )
            eval_cases.append(case_for_eval)
            answers.append(
                result if isinstance(result, dict) else {"answer": str(result)}
            )

        # Evaluate
        evaluated = self._batch_evaluate(eval_cases, answers)
        scores = [float(ec.score) for ec in evaluated]

        # Build trajectories for reflection
        trajectories: list[dict[str, Any]] = []
        if capture_traces:
            for case, result, eval_case in zip(cases, results, evaluated):
                expected = ""
                if hasattr(case, "label") and isinstance(case.label, dict):
                    expected = str(case.label.get("expected_result", ""))
                answer_text = ""
                if isinstance(result, dict):
                    answer_text = str(result.get("answer", ""))
                trajectories.append(
                    {
                        "case_id": case.case_id,
                        "inputs": {
                            "query": case.inputs.get("query", ""),
                            "images": case.inputs.get("images", []),
                        },
                        "expected_result": expected,
                        "model_output": answer_text,
                        "score": float(eval_case.score),
                        "feedback": _build_feedback_text(expected, answer_text, eval_case),
                    }
                )

        return EvaluationBatch(
            outputs=results,
            scores=scores,
            trajectories=trajectories if capture_traces else None,
            case_ids=[c.case_id for c in cases],
        )

    def make_reflective_dataset(
        self,
        candidate: dict[str, str],
        eval_batch: EvaluationBatch,
        components_to_update: list[str],
    ) -> dict[str, list[dict[str, Any]]]:
        """Build per-component reflection dataset from trajectories.

        Returns ``{component_name: [record1, record2, ...]}`` where each
        record follows the GEPA schema:
        ``{"Inputs": ..., "Generated Outputs": ..., "Feedback": ...}``
        """
        prompt = candidate.get(self._component_name, "")
        dataset: dict[str, list[dict[str, Any]]] = {}

        for comp in components_to_update:
            records: list[dict[str, Any]] = []
            for traj in eval_batch.trajectories or []:
                records.append(
                    {
                        "Inputs": {
                            "query": traj["inputs"]["query"],
                            "images": traj["inputs"].get("images", []),
                        },
                        "Generated Outputs": traj["model_output"],
                        "Expected": traj["expected_result"],
                        "Feedback": traj["feedback"],
                        "Score": traj["score"],
                    }
                )
            dataset[comp] = records
        return dataset

    async def _invoke_vision_model(
        self, prompt: str, case: Case
    ) -> dict[str, Any]:
        """Call vision model with prompt + images from case."""
        if self._vision_model is None:
            return {"answer": "", "error": "vision_model not configured"}

        query = str(case.inputs.get("query", ""))
        images = case.inputs.get("images", [])
        if not isinstance(images, list):
            images = [images] if images else []

        content = _build_multimodal_content(query, images)
        if not content:
            return {"answer": "", "error": "no text or images in case inputs"}

        from openjiuwen.core.foundation.llm.schema.message import (
            SystemMessage,
            UserMessage,
        )

        messages: list[Any] = []
        if prompt:
            messages.append(SystemMessage(content=prompt))
        messages.append(UserMessage(content=content))  # type: ignore[arg-type]

        try:
            from evo_agent.llm.invocation import _get_invocation_loop

            invocation_loop = _get_invocation_loop()
            future = invocation_loop.submit(self._vision_model.invoke(messages))
            response = await asyncio.wrap_future(future)
            answer_text = ""
            if hasattr(response, "content"):
                answer_text = str(response.content)
            elif isinstance(response, str):
                answer_text = response
            return {"answer": answer_text}
        except Exception as exc:
            logger.warning(
                "[gepa] vision model invoke failed case=%s: %s",
                case.case_id,
                exc,
            )
            return {"answer": "", "error": str(exc)}

    def _batch_evaluate(
        self,
        eval_cases: list[Case],
        answers: list[dict[str, Any]],
    ) -> list[EvaluatedCase]:
        if not eval_cases:
            return []
        try:
            return self._evaluator.batch_evaluate(
                eval_cases,
                answers,
                enable_attribution=False,
            )
        except TypeError:
            return self._evaluator.batch_evaluate(eval_cases, answers)


def _build_feedback_text(
    expected: str, actual: str, eval_case: EvaluatedCase
) -> str:
    """Build natural-language feedback for the reflection engine."""
    score = float(eval_case.score)
    if score >= 1.0:
        return "Correct. The model output matches the expected result."
    if not actual:
        return "The model produced no output. Expected: " + expected
    return (
        f"Incorrect (score={score:.2f}). "
        f"Expected: '{expected}'. "
        f"Got: '{actual}'."
    )
