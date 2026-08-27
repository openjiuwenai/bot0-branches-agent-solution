# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Persisted agent-run and scenario checkpoint artifacts."""

from __future__ import annotations

import hashlib
import time
from pathlib import Path

from skill_builder.application.agent_core import SkillBuilderAgentCoreResult
from skill_builder.application.artifact_inventory import actual_artifact_files
from skill_builder.application.package_builder import normalize_skill_slug
from skill_builder.application.file_helpers import (
    _load_json_object,
)
from skill_builder.runtime.serialization import json_safe
from skill_builder.domain.scenario_contract import (
    SCENARIO_CONTRACT_PATH,
    normalize_scenario_contract,
)
from skill_builder.application.scenario_projection import scenario_projection_matches


def record_agent_core_run(
    root: Path,
    agent_result: SkillBuilderAgentCoreResult | None,
    fallback_reason: str | None,
    *,
    phase: str = "initial",
    attempt: int = 0,
) -> None:
    validation = root.resolve() / "validation"
    validation.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema_version": "skill-builder-agent-core-run/v1",
        "phase": phase,
        "attempt": attempt,
        "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "agent_core": bool(agent_result),
        "session_id": agent_result.session_id if agent_result else None,
        "files_read": agent_result.files_read if agent_result else [],
        "files_listed": agent_result.files_listed if agent_result else [],
        "files_written": agent_result.files_written if agent_result else [],
        "final_response": agent_result.final_response if agent_result else None,
        "submission_status": (
            getattr(agent_result, "submission_status", None)
            if agent_result
            else None
        ),
        "fallback_reason": fallback_reason,
    }
    existing_runs = _load_json_object(validation / "agent_core_runs.json").get("runs")
    runs = existing_runs if isinstance(existing_runs, list) else []
    runs.append(json_safe(payload, max_text_length=8000))
    aggregate = {
        "schema_version": "skill-builder-agent-core-runs/v1",
        "latest_phase": phase,
        "latest_attempt": attempt,
        "run_count": len(runs),
        "runs": runs[-10:],
    }
    latest = {**payload, "run_count": len(runs)}
    (validation / "agent_core_run.json").write_text(
        _json_text(json_safe(latest, max_text_length=12000)),
        encoding="utf-8",
    )
    (validation / "agent_core_runs.json").write_text(
        _json_text(json_safe(aggregate, max_text_length=40000)),
        encoding="utf-8",
    )


def repair_artifact_snapshot(root: Path) -> dict[str, str]:
    ignored = {
        "validation/agent_core_run.json",
        "validation/agent_core_runs.json",
    }
    snapshot: dict[str, str] = {}
    for relative_path in actual_artifact_files(root):
        if relative_path in ignored:
            continue
        try:
            snapshot[relative_path] = hashlib.sha256((root / relative_path).read_bytes()).hexdigest()
        except OSError:
            continue
    return snapshot


def changed_repair_artifact_files(before: dict[str, str], after: dict[str, str]) -> set[str]:
    return {path for path in set(before) | set(after) if before.get(path) != after.get(path)}


def relevant_repair_artifact_files(files: set[str]) -> set[str]:
    result = set()
    excluded_prefixes = (
        "generated-skill/.skill-builder/",
        "generated-skill/inputs/",
        "generated-skill/output/",
        "generated-skill/playwright/",
        "generated-skill/validation/",
        "generated-skill/workspace/",
    )
    for relative_path in files:
        if relative_path.startswith("generated-skill/") and not relative_path.startswith(excluded_prefixes):
            result.add(relative_path)
    return result


def has_substantive_scenario_checkpoint(root: Path) -> bool:
    raw_contract = _load_json_object(root / SCENARIO_CONTRACT_PATH)
    normalized_contract, contract_issues = normalize_scenario_contract(raw_contract)
    contract_ready = bool(
        raw_contract
        and not contract_issues
        and raw_contract.get("semanticHash") == normalized_contract.get("semanticHash")
    )
    return contract_ready and scenario_projection_matches(root, normalized_contract)


def scenario_checkpoint_skill_name(root: Path) -> str:
    manifest = _load_json_object(root / "validation" / "artifact_manifest.json")
    name = normalize_skill_slug(str(manifest.get("skillName") or manifest.get("name") or ""))
    return name if name and not name.startswith("skill-extract-") else ""


def _json_text(value: object) -> str:
    import json

    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


__all__ = [
    "changed_repair_artifact_files",
    "has_substantive_scenario_checkpoint",
    "record_agent_core_run",
    "relevant_repair_artifact_files",
    "repair_artifact_snapshot",
    "scenario_checkpoint_skill_name",
]
