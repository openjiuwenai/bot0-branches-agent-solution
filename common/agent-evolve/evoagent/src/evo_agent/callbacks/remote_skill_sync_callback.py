"""Push skill content to remote rollout agents after each epoch.

When the rollout Agent runs on a remote machine, the local Trainer's
candidate validation updates the in-memory operator state, but the remote
agent's skill file may not be synced. This callback POSTs the authoritative
skill_content to a remote endpoint after each epoch.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable
from typing import TYPE_CHECKING, Any

import requests
from openjiuwen.agent_evolving.trainer.progress import Callbacks

if TYPE_CHECKING:
    from openjiuwen.agent_evolving.dataset import EvaluatedCase
    from openjiuwen.agent_evolving.trainer.progress import Progress
    from openjiuwen.core.single_agent import BaseAgent

logger = logging.getLogger(__name__)


class RemoteSkillSyncCallback(Callbacks):  # type: ignore[misc]
    """Push latest skill content from local operator to remote rollout Agent."""

    def __init__(
        self,
        sync_endpoint: str,
        skill_name: str,
        content_provider: Callable[[], str],
        *,
        timeout: float = 30.0,
        max_retries: int = 2,
    ) -> None:
        self._sync_endpoint = sync_endpoint
        self._skill_name = skill_name
        self._content_provider = content_provider
        self._timeout = timeout
        self._max_retries = max_retries
        self._last_pushed_epoch: int | str = -1
        # trust_env=False: 忽略 HTTP_PROXY 等环境变量，skill sync 走直连
        self._session = requests.Session()
        self._session.trust_env = False

    @classmethod
    def from_operator(
        cls,
        *,
        sync_endpoint: str,
        skill_name: str,
        operator: Any,
        timeout: float = 30.0,
        max_retries: int = 2,
    ) -> RemoteSkillSyncCallback:
        """Build callback that reads skill content from an operator."""
        from evo_agent.protocols import SKILL_CONTENT_TARGET

        return cls(
            sync_endpoint=sync_endpoint,
            skill_name=skill_name,
            content_provider=lambda: operator.get_state().get(SKILL_CONTENT_TARGET, ""),
            timeout=timeout,
            max_retries=max_retries,
        )

    def on_train_epoch_end(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        """Push skill content after each epoch, avoiding duplicate pushes."""
        if progress.current_epoch == self._last_pushed_epoch:
            return

        self._post_with_retry(
            {
                "skill_name": self._skill_name,
                "content": self._content_provider(),
                "epoch": progress.current_epoch,
                "score": progress.best_score,
            }
        )
        self._last_pushed_epoch = progress.current_epoch

    def on_train_end(
        self,
        agent: BaseAgent,
        progress: Progress,
        eval_info: list[EvaluatedCase],
    ) -> None:
        """Push final skill content when training ends."""
        self._post_with_retry(
            {
                "skill_name": self._skill_name,
                "content": self._content_provider(),
                "epoch": "final",
                "score": progress.best_score,
            }
        )

    def _post_with_retry(self, payload: dict) -> None:  # type: ignore[type-arg]
        """POST with exponential backoff retry."""
        for attempt in range(self._max_retries + 1):
            try:
                resp = self._session.post(self._sync_endpoint, json=payload, timeout=self._timeout)
                resp.raise_for_status()
                return
            except requests.RequestException as e:
                if attempt == self._max_retries:
                    logger.warning(
                        "RemoteSkillSync: failed to push skill epoch=%s after %s attempts: %s",
                        payload.get("epoch"),
                        self._max_retries + 1,
                        e,
                    )
                else:
                    time.sleep(2**attempt)
