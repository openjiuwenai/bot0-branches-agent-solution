"""Compose multiple Callbacks into a single callback.

Trainer only accepts a single Callbacks object. Use this class to combine
SkillDocumentCallbacks, RemoteSkillSyncCallback, and other callbacks.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

from openjiuwen.agent_evolving.trainer.progress import Callbacks

if TYPE_CHECKING:
    from openjiuwen.agent_evolving.dataset import EvaluatedCase
    from openjiuwen.agent_evolving.trainer.progress import Progress
    from openjiuwen.core.single_agent import BaseAgent

logger = logging.getLogger(__name__)


class ComposedCallbacks(Callbacks):  # type: ignore[misc]
    """Compose multiple Callbacks, calling each in registration order.

    Exceptions in one callback are caught and logged so subsequent
    callbacks still execute.
    """

    def __init__(self, *callbacks: Callbacks) -> None:
        self._callbacks = list(callbacks)

    def on_train_begin(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        for cb in self._callbacks:
            try:
                cb.on_train_begin(agent, progress, eval_info)
            except Exception:
                logger.warning(
                    "ComposedCallbacks: on_train_begin failed for %s",
                    type(cb).__name__,
                    exc_info=True,
                )

    def on_train_end(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        for cb in self._callbacks:
            try:
                cb.on_train_end(agent, progress, eval_info)
            except Exception:
                logger.warning(
                    "ComposedCallbacks: on_train_end failed for %s",
                    type(cb).__name__,
                    exc_info=True,
                )

    def on_train_epoch_begin(
        self,
        agent: BaseAgent,
        progress: Progress,
    ) -> None:
        for cb in self._callbacks:
            try:
                cb.on_train_epoch_begin(agent, progress)
            except Exception:
                logger.warning(
                    "ComposedCallbacks: on_train_epoch_begin failed for %s",
                    type(cb).__name__,
                    exc_info=True,
                )

    def on_train_epoch_end(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        for cb in self._callbacks:
            try:
                cb.on_train_epoch_end(agent, progress, eval_info)
            except Exception:
                logger.warning(
                    "ComposedCallbacks: on_train_epoch_end failed for %s",
                    type(cb).__name__,
                    exc_info=True,
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
            try:
                handler(payload)
            except Exception:
                logger.warning(
                    "ComposedCallbacks: on_gate_scored failed for %s",
                    type(cb).__name__,
                    exc_info=True,
                )
