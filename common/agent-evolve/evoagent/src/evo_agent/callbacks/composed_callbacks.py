"""Compose multiple Callbacks into a single callback.

Trainer only accepts a single Callbacks object. Use this class to combine
SkillDocumentCallbacks, RemoteSkillSyncCallback, and other callbacks.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from typing import TYPE_CHECKING, Any

from openjiuwen.agent_evolving.trainer.progress import Callbacks

from evo_agent.errors import FatalOptimizationError

if TYPE_CHECKING:
    from openjiuwen.agent_evolving.dataset import EvaluatedCase
    from openjiuwen.agent_evolving.trainer.progress import Progress
    from openjiuwen.core.single_agent import BaseAgent

logger = logging.getLogger(__name__)


class ComposedCallbacks(Callbacks):  # type: ignore[misc]
    """Compose multiple Callbacks, calling each in registration order.

    Ordinary callback exceptions are caught and logged so subsequent callbacks
    still execute (best-effort). ``FatalOptimizationError`` (and subclasses like
    ``ManagedDocApplyError``) **propagate**: the marker signals a failure that
    must abort the optimization job — swallowing it would let training continue
    in a diverged state. Only the ``FatalOptimizationError`` marker is recognized
    here (no reverse dependency on ``adapter_client``).
    """

    def __init__(self, *callbacks: Callbacks) -> None:
        self._callbacks = list(callbacks)

    def _invoke(self, cb: Callbacks, action: Callable[[], None], hook: str) -> None:
        """Run one callback's hook: best-effort swallow ordinary exceptions,
        re-raise ``FatalOptimizationError`` so it aborts the job."""
        try:
            action()
        except FatalOptimizationError:
            logger.error(
                "ComposedCallbacks: %s raised FatalOptimizationError for %s — aborting",
                hook,
                type(cb).__name__,
                exc_info=True,
            )
            raise
        except Exception:
            logger.warning(
                "ComposedCallbacks: %s failed for %s",
                hook,
                type(cb).__name__,
                exc_info=True,
            )

    def on_train_begin(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        for cb in self._callbacks:
            self._invoke(
                cb, lambda: cb.on_train_begin(agent, progress, eval_info), "on_train_begin"
            )

    def on_train_end(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        for cb in self._callbacks:
            self._invoke(cb, lambda: cb.on_train_end(agent, progress, eval_info), "on_train_end")

    def on_train_epoch_begin(
        self,
        agent: BaseAgent,
        progress: Progress,
    ) -> None:
        for cb in self._callbacks:
            self._invoke(
                cb, lambda: cb.on_train_epoch_begin(agent, progress), "on_train_epoch_begin"
            )

    def on_train_epoch_end(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        for cb in self._callbacks:
            self._invoke(
                cb, lambda: cb.on_train_epoch_end(agent, progress, eval_info), "on_train_epoch_end"
            )

    def on_gate_scored(self, payload: dict[str, Any]) -> None:
        """Forward gate scores (base/candidate/tie info) to subscribers.

        Non-vendor hook (not part of the ``Callbacks`` protocol): EvoTrainer
        calls this after the validation gate so ProgressCallback can surface
        the candidate score in SSE. Forwarded only to callbacks that implement
        it; others are silently skipped.
        """
        for cb in self._callbacks:
            handler = getattr(cb, "on_gate_scored", None)
            if not callable(handler):
                continue
            self._invoke(cb, lambda: handler(payload), "on_gate_scored")

    def on_gate_artifact_ready(self, artifact_input: Any) -> None:
        """Forward the complete gate/validation artifact input."""
        for cb in self._callbacks:
            handler = getattr(cb, "on_gate_artifact_ready", None)
            if callable(handler):
                self._invoke(cb, lambda: handler(artifact_input), "on_gate_artifact_ready")

    def on_validation_coverage_failed(self, error: Any) -> None:
        """Export diagnostics synchronously before the trainer re-raises coverage failure."""
        for cb in self._callbacks:
            handler = getattr(cb, "on_validation_coverage_failed", None)
            if callable(handler):
                self._invoke(
                    cb,
                    lambda: handler(error),
                    "on_validation_coverage_failed",
                )
