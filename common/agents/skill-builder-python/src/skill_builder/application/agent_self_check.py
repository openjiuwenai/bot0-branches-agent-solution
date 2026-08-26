# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""The structured, model-reported self-check for one Author turn.

The Agent can record what it read/reviewed and what it could not execute.
Free-form claims never decide acceptance; Core may independently re-check the
small structured evidence subset and remains the only pass/fail authority.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


AGENT_SELF_CHECK_PATH = "validation/diagnostics/agent_self_check.json"

_STATUS_VALUES = {"pass", "warn", "partial", "not_run", "fail"}
_EVIDENCE_KINDS = {"command_result", "file_assertion", "missing_file", "source_location"}
_EVIDENCE_LEVELS = {"static_analysis", "offline_execution", "external_execution"}
_RUNTIME_CHECK_MARKERS = (
    "browser",
    "playwright",
    "selenium",
    "puppeteer",
    "api_runtime",
    "external_runtime",
    "external execution",
    "external_execution",
    "浏览器",
    "外部运行",
    "外部采集",
    "在线采集",
    "实时采集",
)


def _text(value: Any, *, limit: int = 2000) -> str:
    return str(value or "").strip()[:limit]


def _list(value: Any, *, limit: int = 100) -> list[Any]:
    return list(value)[:limit] if isinstance(value, list) else []


def _normalize_evidence(value: Any) -> list[dict[str, Any]]:
    """Keep only evidence shapes that Core can independently re-check.

    Free-form Agent prose remains useful in ``message`` but must never become
    a platform blocker.  Structured evidence is deliberately small: command
    results and package-local file assertions are enough for deterministic
    reconciliation without turning the Agent report into another validator.
    """

    result: list[dict[str, Any]] = []
    for raw in _list(value, limit=50):
        if not isinstance(raw, dict):
            continue
        kind = _text(raw.get("kind") or raw.get("type"), limit=40).lower()
        if kind not in _EVIDENCE_KINDS:
            continue
        item: dict[str, Any] = {"kind": kind}
        path = _text(raw.get("path") or raw.get("file"), limit=1000)
        if path:
            item["path"] = path
        command = raw.get("command")
        if isinstance(command, list):
            item["command"] = [_text(part, limit=500) for part in command[:30]]
        elif command:
            item["command"] = _text(command, limit=2000)
        exit_code = raw.get("exitCode", raw.get("exit_code"))
        if exit_code is not None:
            item["exitCode"] = exit_code
        line = raw.get("line")
        if line is not None:
            try:
                item["line"] = max(1, int(line))
            except (TypeError, ValueError):
                pass
        for key in ("quote", "expected", "actual", "message"):
            text = _text(raw.get(key), limit=2000)
            if text:
                item[key] = text
        result.append(item)
    return result


def _is_external_runtime_check(*values: Any) -> bool:
    text = " ".join(str(value or "") for value in values).lower()
    return any(marker in text for marker in _RUNTIME_CHECK_MARKERS)


def _evidence_level(raw: dict[str, Any], evidence: list[dict[str, Any]]) -> str:
    claimed = _text(
        raw.get("evidenceLevel") or raw.get("evidence_level"),
        limit=40,
    ).lower()
    if claimed in _EVIDENCE_LEVELS:
        return claimed
    if any(item.get("kind") == "command_result" for item in evidence):
        return "offline_execution"
    return "static_analysis"


def normalize_agent_self_check(
    value: Any,
    *,
    generated_files: list[str] | None = None,
) -> dict[str, Any] | None:
    """Normalize an optional Agent report without trusting its claims."""

    if not isinstance(value, dict):
        return None
    status = _text(value.get("status") or "not_run", limit=32).lower()
    if status not in _STATUS_VALUES:
        status = "partial"
    checks: list[dict[str, Any]] = []
    downgraded_external_checks: list[str] = []
    for index, raw in enumerate(_list(value.get("checks"))):
        if not isinstance(raw, dict):
            continue
        check_status = _text(raw.get("status") or "not_run", limit=32).lower()
        if check_status not in _STATUS_VALUES:
            check_status = "partial"
        check_id = _text(raw.get("id") or raw.get("name") or f"check_{index + 1}", limit=100)
        title = _text(raw.get("title") or raw.get("name") or check_id, limit=200)
        message = _text(raw.get("message") or raw.get("detail") or raw.get("result"), limit=2000)
        evidence = _normalize_evidence(raw.get("evidence"))
        evidence_level = _evidence_level(raw, evidence)
        external_runtime_check = _is_external_runtime_check(check_id, title, message)
        # Agent-authored evidence is useful diagnostics but is not a trusted
        # external execution receipt. Until a platform execution port supplies
        # that receipt, runtime capability checks cannot claim pass.
        if external_runtime_check and check_status == "pass":
            check_status = "partial"
            downgraded_external_checks.append(title or check_id)
        if external_runtime_check and evidence_level == "external_execution":
            evidence_level = (
                "offline_execution"
                if any(item.get("kind") == "command_result" for item in evidence)
                else "static_analysis"
            )
        item: dict[str, Any] = {
            "id": check_id,
            "title": title,
            "status": check_status,
            "message": message,
            "evidenceLevel": evidence_level,
        }
        command = raw.get("command")
        if isinstance(command, list):
            item["command"] = [_text(part, limit=500) for part in command[:30]]
        elif command:
            item["command"] = _text(command, limit=2000)
        for key in ("exit_code", "exitCode"):
            if key in raw:
                item["exitCode"] = raw.get(key)
        files = [_text(part, limit=1000) for part in _list(raw.get("files"), limit=50)]
        if files:
            item["files"] = [path for path in files if path]
        if evidence:
            item["evidence"] = evidence
        checks.append(item)
    files = generated_files if generated_files is not None else _list(
        value.get("generated_files") or value.get("generatedFiles") or value.get("files")
    )
    files = [_text(path, limit=1000) for path in files if _text(path, limit=1000)]
    unverified = [_text(item, limit=2000) for item in _list(value.get("unverified") or value.get("unverified_capabilities"))]
    unverified.extend(
        f"{title}：仅有 Agent 静态/离线证据，未取得平台可信的外部执行证据。"
        for title in downgraded_external_checks
    )
    if status == "pass" and downgraded_external_checks:
        status = "partial"
    return {
        "schemaVersion": "skill-builder-agent-self-check/v1",
        "status": status,
        "title": _text(value.get("title") or "Agent 自验证", limit=200),
        "summary": _text(value.get("summary") or value.get("message"), limit=4000),
        "checks": checks,
        "generatedFiles": files,
        "unverified": list(dict.fromkeys(item for item in unverified if item)),
        "generatedAt": _text(value.get("generatedAt"), limit=80) or datetime.now(timezone.utc).isoformat(),
    }


def persist_agent_self_check(root: Path, value: Any, *, generated_files: list[str] | None = None) -> dict[str, Any] | None:
    """Persist one normalized Agent report for hosts that need a durable view."""

    payload = normalize_agent_self_check(value, generated_files=generated_files)
    if payload is None:
        return None
    target = root / AGENT_SELF_CHECK_PATH
    temporary = target.with_name(f".{target.name}.tmp")
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(target)
    except OSError:
        temporary.unlink(missing_ok=True)
        return None
    return payload


__all__ = ["AGENT_SELF_CHECK_PATH", "normalize_agent_self_check", "persist_agent_self_check"]
