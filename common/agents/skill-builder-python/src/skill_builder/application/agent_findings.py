# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Reconcile model-reported findings with deterministic platform evidence."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any


_PLATFORM_CHECK_ALIASES = {
    "draft_package": "package_structure",
    "package_consistency": "package_structure",
    "package_structure": "package_structure",
    "python_syntax": "python_syntax_invalid",
    "script_syntax": "python_syntax_invalid",
    "python_undefined_names": "python_undefined_names",
    "undefined_names": "python_undefined_names",
    "skill_references": "skill_reference_missing",
    "package_references": "skill_reference_missing",
    "offline_self_check": "offline_smoke_failed",
    "offline_smoke": "offline_smoke_failed",
    "placeholder_implementation": "placeholder_implementation",
    "external_runtime_placeholder": "placeholder_implementation",
    "sample_business_data_hardcoded": "sample_business_data_hardcoded",
    "nonportable_package_reference": "nonportable_package_reference",
    "capability_consistency": "capability_declaration_unbacked",
}


def _token(value: Any) -> str:
    return re.sub(r"[^a-z0-9_]+", "_", str(value or "").strip().lower()).strip("_")[:80]


def _status(value: Any) -> str:
    return str(value or "").strip().lower()


def _confined_package_path(root: Path, value: Any) -> Path | None:
    text = str(value or "").strip().replace("\\", "/")
    if not text:
        return None
    if text.startswith("./"):
        text = text[2:]
    if not text.startswith("generated-skill/"):
        text = f"generated-skill/{text.lstrip('/')}"
    try:
        target = (root / text).resolve()
        generated = (root / "generated-skill").resolve()
    except OSError:
        return None
    if target != generated and not target.is_relative_to(generated):
        return None
    return target


def _command_script(root: Path, command: Any) -> Path | None:
    parts = command if isinstance(command, list) else str(command or "").split()
    for part in parts:
        text = str(part or "").strip()
        if not text.endswith(".py"):
            continue
        target = _confined_package_path(root, text)
        if target is not None and target.is_file():
            return target
    return None


def _nonzero_exit_code(value: Any) -> int | None:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed != 0 else None


def _evidence_is_confirmed(root: Path, evidence: dict[str, Any]) -> bool:
    kind = str(evidence.get("kind") or "").strip().lower()
    if kind == "command_result":
        return bool(
            _nonzero_exit_code(evidence.get("exitCode")) is not None
            and _command_script(root, evidence.get("command")) is not None
        )

    target = _confined_package_path(root, evidence.get("path"))
    if target is None:
        return False
    if kind == "missing_file":
        return not target.exists()
    if kind not in {"file_assertion", "source_location"} or not target.is_file():
        return False
    try:
        text = target.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    quote = str(evidence.get("quote") or "").strip()
    if not quote:
        return False
    line = evidence.get("line")
    if line is not None:
        try:
            lines = text.splitlines()
            if quote not in lines[max(1, int(line)) - 1]:
                return False
        except (IndexError, TypeError, ValueError):
            return False
    elif quote not in text:
        return False
    if kind == "source_location":
        return True
    expected = str(evidence.get("expected") or "").strip()
    actual = str(evidence.get("actual") or "").strip()
    return bool(expected and actual and expected != actual)


def reconcile_agent_findings(
    *,
    root: Path,
    agent_self_check: dict[str, Any] | None,
    platform_findings: list[dict[str, Any]],
) -> dict[str, Any]:
    """Return only independently confirmed Agent blockers plus review warnings.

    Platform-native findings are deduplicated.  Free-form or weakly evidenced
    Agent failures stay visible as warnings and are never sent into automatic
    repair.  A non-zero package command or a package-local source assertion is
    blocking only after Core can bind it to the current generated artifact.
    """

    if not isinstance(agent_self_check, dict):
        return {
            "findings": [],
            "check": None,
            "diagnostics": {
                "reportedFailureCount": 0,
                "confirmedFailureCount": 0,
                "unconfirmedFailureCount": 0,
                "deduplicatedFailureCount": 0,
            },
        }

    platform_failure_ids = {
        str(item.get("id") or "")
        for item in platform_findings
        if isinstance(item, dict) and item.get("severity") == "fail"
    }
    checks = agent_self_check.get("checks")
    checks = checks if isinstance(checks, list) else []
    failed_checks = [item for item in checks if isinstance(item, dict) and _status(item.get("status")) == "fail"]
    if _status(agent_self_check.get("status")) == "fail" and not failed_checks:
        failed_checks = [
            {
                "id": "agent_report",
                "title": "Agent 自验证",
                "status": "fail",
                "message": agent_self_check.get("summary") or "Agent 报告为失败，但未提供结构化失败检查项。",
            }
        ]

    findings: list[dict[str, Any]] = []
    confirmed_count = 0
    unconfirmed_count = 0
    deduplicated_count = 0
    for index, check in enumerate(failed_checks):
        raw_id = str(check.get("id") or f"check_{index + 1}")
        check_id = _token(raw_id) or f"check_{index + 1}"
        platform_id = _PLATFORM_CHECK_ALIASES.get(check_id, check_id)
        if platform_id in platform_failure_ids:
            deduplicated_count += 1
            continue

        evidence = [item for item in check.get("evidence") or [] if isinstance(item, dict)]
        direct_command_evidence = {
            "kind": "command_result",
            "command": check.get("command"),
            "exitCode": check.get("exitCode"),
        }
        if check.get("command") and check.get("exitCode") is not None:
            evidence.append(direct_command_evidence)
        confirmed = [item for item in evidence if _evidence_is_confirmed(root, item)]
        message = str(check.get("message") or check.get("title") or raw_id).strip()[:2000]
        if confirmed:
            confirmed_count += 1
            finding_id = f"agent_confirmed_{check_id}"
            findings.append(
                {
                    "id": finding_id,
                    "rootCauseId": f"agent:{check_id}",
                    "severity": "fail",
                    "category": "agent_evidence",
                    "audience": "user",
                    "repairable": True,
                    "title": str(check.get("title") or "Agent 发现的确定性问题")[:200],
                    "message": message or "Agent 发现的问题已由平台证据复核确认。",
                    "evidence": confirmed[:20],
                }
            )
            continue

        unconfirmed_count += 1
        findings.append(
            {
                "id": f"agent_unconfirmed_{check_id}",
                "rootCauseId": f"agent:{check_id}",
                "severity": "warn",
                "category": "agent_review",
                "audience": "user",
                "repairable": False,
                "title": str(check.get("title") or "Agent 自验证提示")[:200],
                "message": (
                    (message or "Agent 报告了失败项")
                    + "；平台未取得可独立复核的确定性证据，已保留为人工复核提示。"
                )[:2000],
            }
        )

    reported_count = len(failed_checks)
    status = "fail" if confirmed_count else "warn" if unconfirmed_count else "pass"
    check = {
        "id": "agent_findings_reconciliation",
        "status": status,
        "message": (
            f"Agent 报告 {reported_count} 个失败项：平台确认 {confirmed_count} 个，"
            f"保留人工复核 {unconfirmed_count} 个，去重 {deduplicated_count} 个。"
        ),
        "reportedFailureCount": reported_count,
        "confirmedFailureCount": confirmed_count,
        "unconfirmedFailureCount": unconfirmed_count,
        "deduplicatedFailureCount": deduplicated_count,
    }
    return {
        "findings": findings,
        "check": check,
        "diagnostics": {
            "reportedFailureCount": reported_count,
            "confirmedFailureCount": confirmed_count,
            "unconfirmedFailureCount": unconfirmed_count,
            "deduplicatedFailureCount": deduplicated_count,
        },
    }


__all__ = ["reconcile_agent_findings"]
