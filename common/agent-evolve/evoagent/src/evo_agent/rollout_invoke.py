"""Shared rollout invoke helpers (empty-extract retry)."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from typing import Any

from evo_agent.evaluator.metrics.extract import (
    AnswerFieldExtractConfig,
    is_extracted_field_missing,
)

logger = logging.getLogger(__name__)


async def invoke_with_empty_extract_retry(
    *,
    invoke_once: Callable[[str], Awaitable[dict[str, Any]]],
    new_conversation_id: Callable[[], str],
    extract_cfg: AnswerFieldExtractConfig | None,
    max_attempts: int,
    backoff_secs: float,
    case_id: str,
    phase: str,
) -> tuple[dict[str, Any], str]:
    """Invoke agent; retry with a fresh conversation_id when extract field is empty.

    When ``extract_cfg`` is None, invokes once (no empty-field retry).
    """
    attempts = max(max_attempts, 1) if extract_cfg is not None else 1
    answer: dict[str, Any] = {"answer": ""}
    conversation_id = ""

    for attempt in range(attempts):
        conversation_id = new_conversation_id()
        if attempt > 0:
            logger.warning(
                "Empty extract field; retrying invoke phase=%s case=%s "
                "attempt=%d/%d conversation_id=%s",
                phase,
                case_id,
                attempt + 1,
                attempts,
                conversation_id,
            )
            if backoff_secs > 0:
                await asyncio.sleep(backoff_secs)
        answer = await invoke_once(conversation_id)
        if not is_extracted_field_missing(answer, extract_cfg):
            return answer, conversation_id

    logger.warning(
        "Empty extract field after %d attempts phase=%s case=%s conversation_id=%s",
        attempts,
        phase,
        case_id,
        conversation_id,
    )
    return answer, conversation_id
