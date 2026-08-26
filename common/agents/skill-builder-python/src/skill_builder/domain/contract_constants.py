# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Small shared constants still used by the public HITL boundary."""

from __future__ import annotations

from typing import Any


SANDBOX_WORKSPACE_ROOT = "/workspace"
MAX_TEXT_PREVIEW_BYTES = 1024 * 1024

DECISION_CONTRACT_SCHEMA_VERSION = "skill-builder-decision-contract/v2"
DECISION_CONTRACT_NORMALIZER_VERSION = 17
KNOWN_DECISION_CAPABILITIES = {
    "api_runtime",
    "browser_runtime",
    "external_runtime",
    "collection_script",
    "runtime_screenshot_output",
    "structured_output",
}
DECISION_CAPABILITY_ALIASES = {
    "browser_runtime_export": "browser_runtime",
    "structured_json_output": "structured_output",
}


def canonical_decision_capability(value: Any) -> str | None:
    """Return the one persisted capability key accepted by every layer."""

    normalized = str(value or "").strip().lower().replace("-", "_")
    normalized = DECISION_CAPABILITY_ALIASES.get(normalized, normalized)
    return normalized if normalized in KNOWN_DECISION_CAPABILITIES else None
