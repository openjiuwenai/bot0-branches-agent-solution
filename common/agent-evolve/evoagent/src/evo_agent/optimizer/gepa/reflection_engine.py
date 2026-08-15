"""Reflection engine — LLM-driven reflective prompt mutation.

Implements GEPA's core principle: instead of blind/random mutation, the LLM
reflects on full execution traces (inputs, model outputs, expected results,
feedback) to propose targeted textual improvements.

Uses the ``InstructionProposalSignature`` prompt template from the open-source
GEPA, adapted for EvoAgent's two-level template lookup.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from evo_agent.llm.invocation import LLMInvocation
from evo_agent.optimizer.gepa.prompts import (
    INSTRUCTION_PROPOSAL,
    load_gepa_prompt,
    render_prompt,
)
from evo_agent.optimizer.llm_resilience import LLMInvokePolicy, invoke_text_with_retry

logger = logging.getLogger(__name__)

# Max records to include in the reflection prompt (avoid token blowup).
_MAX_REFLECTION_RECORDS = 10
# Max chars per record field.
_MAX_FIELD_CHARS = 1000
# Max current prompt chars.
_MAX_PROMPT_CHARS = 8000


class ReflectionEngine:
    """LLM-based reflective mutation proposer.

    Given the current candidate prompt and a reflective dataset (execution
    traces + feedback), the LLM proposes a new improved prompt.

    Parameters
    ----------
    llm_invocation:
        Run-scoped ``LLMInvocation`` for budget/retry/concurrency control.
    model_name:
        Model name string for the reflection LLM calls.
    llm_policy:
        Retry/timeout policy.
    scenario_name / scenarios_dir:
        For two-level prompt template lookup.
    """

    def __init__(
        self,
        llm_invocation: LLMInvocation,
        model_name: str,
        *,
        llm_policy: LLMInvokePolicy | None = None,
        scenario_name: str | None = None,
        scenarios_dir: Path | str | None = None,
    ) -> None:
        self._llm = llm_invocation
        self._model = model_name
        self._llm_policy = llm_policy or LLMInvokePolicy(
            attempt_timeout_secs=300.0,
            total_budget_secs=900.0,
            max_attempts=3,
            backoff_base_secs=1.0,
        )
        self._scenario_name = scenario_name
        self._scenarios_dir = scenarios_dir

    def _load_kwargs(self) -> dict[str, Any]:
        return {
            "scenario_name": self._scenario_name,
            "scenarios_dir": self._scenarios_dir,
        }

    @staticmethod
    def _render_reflective_dataset(
        records: list[dict[str, Any]]
    ) -> str:
        """Render the reflective dataset as markdown for the LLM prompt.

        Each record follows the GEPA schema:
        ``{"Inputs": ..., "Generated Outputs": ..., "Expected": ..., "Feedback": ...}``
        """
        parts: list[str] = []
        for i, record in enumerate(records[:_MAX_REFLECTION_RECORDS], 1):
            inputs = record.get("Inputs", {})
            if isinstance(inputs, dict):
                input_text = str(inputs.get("query", ""))
                images = inputs.get("images", [])
                if images:
                    input_text += f" (images: {len(images)} images)"
            else:
                input_text = str(inputs)

            generated = str(record.get("Generated Outputs", ""))[:_MAX_FIELD_CHARS]
            expected = str(record.get("Expected", ""))[:_MAX_FIELD_CHARS]
            feedback = str(record.get("Feedback", ""))[:_MAX_FIELD_CHARS]
            score = record.get("Score", 0.0)

            parts.append(
                f"# Example {i}\n"
                f"## Inputs\n{input_text}\n"
                f"## Generated Outputs\n{generated}\n"
                f"## Expected\n{expected}\n"
                f"## Feedback\n{feedback}\n"
                f"## Score: {score}"
            )
        return "\n\n".join(parts) if parts else "(no examples)"

    async def propose(
        self,
        *,
        current_prompt: str,
        reflective_dataset: dict[str, list[dict[str, Any]]],
        components_to_update: list[str],
        iteration: int,
    ) -> dict[str, str]:
        """Propose new component texts via LLM reflection.

        Returns ``{component_name: new_text}``.
        """
        result: dict[str, str] = {}
        for comp in components_to_update:
            records = reflective_dataset.get(comp, [])
            if not records:
                result[comp] = current_prompt
                continue
            new_text = await self._propose_one_component(
                current_prompt=current_prompt,
                records=records,
                component=comp,
                iteration=iteration,
            )
            result[comp] = new_text
        return result

    async def _propose_one_component(
        self,
        *,
        current_prompt: str,
        records: list[dict[str, Any]],
        component: str,
        iteration: int,
    ) -> str:
        """Generate a single improved component via LLM reflection."""
        side_info = self._render_reflective_dataset(records)
        curr_param = current_prompt[:_MAX_PROMPT_CHARS]
        if len(current_prompt) > _MAX_PROMPT_CHARS:
            curr_param += "\n\n[... content truncated ...]"

        prompt = render_prompt(
            load_gepa_prompt(INSTRUCTION_PROPOSAL, **self._load_kwargs()),
            {
                "curr_param": curr_param,
                "side_info": side_info,
                "iteration": iteration,
            },
        )

        try:
            response = await invoke_text_with_retry(
                self._llm,
                self._model,
                prompt,
                policy=self._llm_policy,
                stage="reflect",
                temperature=0.7,
                is_result_usable=lambda text: bool((text or "").strip()),
            )
        except Exception:
            logger.exception(
                "[gepa] reflection failed component=%s iteration=%d",
                component,
                iteration,
            )
            return current_prompt

        # Extract new instruction from ``` blocks (GEPA convention)
        new_text = _extract_from_code_blocks(response or "")
        if not new_text.strip():
            return current_prompt
        return new_text.strip()


def _extract_from_code_blocks(text: str) -> str:
    """Extract text between first and last ``` backtick blocks.

    This follows the GEPA convention: the LLM is instructed to provide the
    new instruction within ``` blocks.
    """
    parts = text.split("```")
    if len(parts) < 3:
        # No code blocks found — return the full text
        return text.strip()
    # Take content between first ``` and last ```
    # parts[0] is before first ```, parts[-1] is after last ```
    inner = "```".join(parts[1:-1])
    # Remove potential language tag on first line
    lines = inner.split("\n")
    if lines and lines[0].strip() in ("", "text", "markdown", "python"):
        lines = lines[1:]
    return "\n".join(lines).strip()
