"""Epoch-level callback bridging Trainer (sync) to SkillDocumentOptimizer (async).

Triggers slow_update + meta_skill at epoch boundaries via asyncio.run().
Assumes Trainer is sync (no running event loop when callbacks fire).
"""

from __future__ import annotations

import asyncio
from collections import deque
from typing import TYPE_CHECKING

from openjiuwen.agent_evolving.trainer.progress import Callbacks

from evo_agent.errors import ArtifactConsistencyError

if TYPE_CHECKING:
    from openjiuwen.agent_evolving.dataset import EvaluatedCase
    from openjiuwen.agent_evolving.trainer.progress import Progress
    from openjiuwen.core.single_agent import BaseAgent

    from evo_agent.optimizer.skill_document.skill_document_optimizer import (
        SkillDocumentOptimizer,
    )
    from evo_agent.optimizer.skill_document.types import (
        GateEpochArtifactInput,
        ValidationCoverageFailureInput,
    )


class SkillDocumentCallbacks(Callbacks):  # type: ignore[misc]
    """Epoch-level hooks for SkillDocumentOptimizer.

    Triggers slow_update + meta_skill at epoch boundaries.
    Uses asyncio.run() which assumes no running event loop (Trainer is sync).
    """

    def __init__(self, optimizer: SkillDocumentOptimizer) -> None:
        self._optimizer = optimizer
        self._gate_inputs: deque[GateEpochArtifactInput] = deque()

    def on_gate_artifact_ready(self, artifact_input: GateEpochArtifactInput) -> None:
        self._gate_inputs.append(artifact_input)

    def on_validation_coverage_failed(self, failure: ValidationCoverageFailureInput) -> None:
        """Durably export invalid gate diagnostics before the original error escapes."""
        self._optimizer.export_validation_failure(failure)

    def on_train_epoch_end(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        """Bridge sync Trainer callback to async optimizer.run_epoch_end()."""
        if not self._gate_inputs:
            raise ArtifactConsistencyError("missing_gate_input")
        artifact_input = self._gate_inputs.popleft()
        artifact_epoch = getattr(self._optimizer, "_artifact_epoch", None)
        expected_artifact_epoch = progress.current_epoch - 1
        if isinstance(artifact_epoch, int) and artifact_epoch != expected_artifact_epoch:
            raise ArtifactConsistencyError(
                "epoch_mismatch: "
                f"trainer_epoch={progress.current_epoch} artifact_epoch={artifact_epoch}"
            )
        expected_ids = {outcome.case_id for outcome in artifact_input.selected_batch.outcomes}
        actual_ids = {item.case.case_id for item in eval_info}
        if expected_ids != actual_ids:
            raise ArtifactConsistencyError("validation_case_set_mismatch")
        asyncio.run(
            self._optimizer.run_epoch_end(
                trainer_epoch=progress.current_epoch,
                val_results=eval_info,
                artifact_input=artifact_input,
            )
        )
