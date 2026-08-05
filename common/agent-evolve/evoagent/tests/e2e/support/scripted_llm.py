"""Scripted external LLM boundary used by the managed-document E2E."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class _Response:
    content: str
    finish_reason: str = "stop"
    metadata: dict[str, Any] | None = None


class ScriptedLLM:
    """Return the one edit needed to make the fake Agent answer ``NEW``."""

    async def invoke(self, messages: list[Any], **_kwargs: Any) -> _Response:
        prompt = "\n".join(str(getattr(message, "content", "")) for message in messages)
        if "## Failed Trajectories" not in prompt:
            raise AssertionError(f"unexpected E2E LLM prompt: {prompt[:200]}")
        content = json.dumps(
            {
                "batch_size": 2,
                "failure_summary": [
                    {
                        "failure_type": "stale_rule",
                        "count": 2,
                        "description": "Agent still follows the OLD rule marker.",
                    }
                ],
                "patch": {
                    "reasoning": "Switch the active rule marker used by the Agent.",
                    "edits": [
                        {
                            "op": "replace",
                            "target": "ANSWER=OLD",
                            "content": "ANSWER=NEW",
                            "support_count": 2,
                        }
                    ],
                },
            }
        )
        return _Response(content=content, metadata={"transport_complete": True})
