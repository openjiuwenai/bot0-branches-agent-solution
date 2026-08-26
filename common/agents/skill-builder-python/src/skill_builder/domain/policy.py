# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Deterministic identity for the effective Skill Builder policy."""

from __future__ import annotations

import hashlib
import json

from .candidate_contract import REQUIRED_CANDIDATE_PATHS

SKILL_BUILDER_POLICY_SCHEMA_VERSION = "skill-builder-policy/v3"


def skill_builder_policy_payload() -> dict[str, object]:
    """Return the stable draft-package policy identity."""

    return {
        "schemaVersion": SKILL_BUILDER_POLICY_SCHEMA_VERSION,
        "requiredDraftPaths": sorted(REQUIRED_CANDIDATE_PATHS),
    }


def skill_builder_policy_digest() -> str:
    encoded = json.dumps(
        skill_builder_policy_payload(),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def skill_builder_policy_version() -> str:
    return f"skill-builder-v2+sha256:{skill_builder_policy_digest()}"


__all__ = [
    "SKILL_BUILDER_POLICY_SCHEMA_VERSION",
    "skill_builder_policy_digest",
    "skill_builder_policy_payload",
    "skill_builder_policy_version",
]
