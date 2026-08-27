# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Transactional snapshots for mutable Skill Builder artifact scopes."""

from __future__ import annotations

import hashlib
import json
import shutil
import time
from pathlib import Path
from typing import Any, Iterable


DEFAULT_ARTIFACT_SCOPES = (
    "generated-skill",
    "validation",
    ".skill-builder/drafts",
    ".skill-builder/revisions",
)

REJECTED_REPAIR_ROOT = Path(".skill-builder/rejected-repairs")
REJECTED_REPAIR_RETENTION = 5


def _scope_file_digests(base: Path, scope: str) -> dict[str, str]:
    scope_root = base / scope
    if not scope_root.is_dir():
        return {}
    result: dict[str, str] = {}
    for path in sorted(scope_root.rglob("*")):
        if not path.is_file() or path.is_symlink():
            continue
        try:
            relative = path.relative_to(base).as_posix()
            result[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
        except (OSError, ValueError):
            continue
    return result


def _workspace_artifact_changes(
    root: Path,
    backup_root: Path,
    *,
    scopes: Iterable[str],
) -> list[dict[str, str]]:
    baseline: dict[str, str] = {}
    candidate: dict[str, str] = {}
    for scope in scopes:
        baseline.update(_scope_file_digests(backup_root, scope))
        candidate.update(_scope_file_digests(root, scope))
    changes: list[dict[str, str]] = []
    for path in sorted(set(baseline) | set(candidate)):
        if baseline.get(path) == candidate.get(path):
            continue
        change = "modified"
        if path not in baseline:
            change = "added"
        elif path not in candidate:
            change = "deleted"
        changes.append({"path": path, "change": change})
    return changes


def preserve_rejected_workspace_artifacts(
    root: Path,
    backup_root: Path,
    *,
    metadata: dict[str, Any] | None = None,
    scopes: Iterable[str] = DEFAULT_ARTIFACT_SCOPES,
) -> dict[str, Any]:
    """Persist a rejected repair candidate outside the rollback scope.

    Rollback restores the last accepted materialized workspace, but the actual
    candidate and its Draft revisions remain inspectable and recoverable. The
    store is bounded so repeated failures cannot grow workspace state without
    limit.
    """

    resolved_root = root.resolve()
    scope_values = tuple(dict.fromkeys(str(scope).strip("/") for scope in scopes if str(scope).strip("/")))
    temporary_root: Path | None = None
    try:
        changes = _workspace_artifact_changes(
            resolved_root,
            backup_root.resolve(),
            scopes=scope_values,
        )
        if not changes:
            return {"ok": True, "preserved": False, "reason": "candidate_unchanged"}
        raw_transaction_id = backup_root.name.strip()
        transaction_id = (
            raw_transaction_id
            if raw_transaction_id
            and all(character.isalnum() or character in {"-", "_"} for character in raw_transaction_id)
            else hashlib.sha256(str(backup_root).encode("utf-8")).hexdigest()[:32]
        )
        archive_parent = resolved_root / REJECTED_REPAIR_ROOT
        archive_parent.mkdir(parents=True, exist_ok=True)
        archive_root = archive_parent / transaction_id
        temporary_root = archive_parent / f".{transaction_id}.tmp"
        shutil.rmtree(temporary_root, ignore_errors=True)
        candidate_root = temporary_root / "candidate"
        copied_scopes: list[str] = []
        for scope in scope_values:
            source = resolved_root / scope
            if not source.is_dir():
                continue
            shutil.copytree(source, candidate_root / scope)
            copied_scopes.append(scope)
        payload = {
            "schemaVersion": "skill-builder-rejected-repair/v1",
            "transactionId": transaction_id,
            "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "candidateRoot": "candidate",
            "candidateScopes": copied_scopes,
            "changedPaths": changes,
            "details": metadata or {},
        }
        temporary_root.mkdir(parents=True, exist_ok=True)
        (temporary_root / "assessment.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2, default=str) + "\n",
            encoding="utf-8",
        )
        if archive_root.exists():
            shutil.rmtree(archive_root)
        temporary_root.replace(archive_root)
        archives = sorted(
            (
                path
                for path in archive_parent.iterdir()
                if path.is_dir() and not path.name.startswith(".")
            ),
            key=lambda path: path.stat().st_mtime,
            reverse=True,
        )
        for expired in archives[REJECTED_REPAIR_RETENTION:]:
            shutil.rmtree(expired, ignore_errors=True)
        return {
            "ok": True,
            "preserved": True,
            "path": archive_root.relative_to(resolved_root).as_posix(),
            "changedPaths": changes,
        }
    except Exception as exc:  # noqa: BLE001 - preservation must never prevent rollback
        if temporary_root is not None:
            try:
                shutil.rmtree(temporary_root, ignore_errors=True)
            except Exception:
                pass
        return {
            "ok": False,
            "preserved": False,
            "error": "rejected_repair_preservation_failed",
            "message": str(exc)[:1000],
        }


def copy_workspace_artifact_snapshot(
    root: Path,
    backup_root: Path,
    *,
    scopes: Iterable[str] = DEFAULT_ARTIFACT_SCOPES,
) -> None:
    """Copy every mutable artifact scope before a candidate repair."""

    backup_root.mkdir(parents=True, exist_ok=False)
    for directory_name in scopes:
        source = root / directory_name
        if source.is_dir():
            shutil.copytree(source, backup_root / directory_name)


def restore_workspace_artifact_snapshot(
    root: Path,
    backup_root: Path,
    *,
    scopes: Iterable[str] = DEFAULT_ARTIFACT_SCOPES,
) -> None:
    """Restore a snapshot, including deletion of candidate-only scopes."""

    for directory_name in scopes:
        target = root / directory_name
        snapshot = backup_root / directory_name
        if target.exists():
            shutil.rmtree(target)
        if snapshot.is_dir():
            shutil.copytree(snapshot, target)


def discard_workspace_artifact_snapshot(backup_root: Path) -> None:
    """Discard a completed transaction snapshot."""

    shutil.rmtree(backup_root, ignore_errors=True)
    parent = backup_root.parent
    try:
        parent.rmdir()
    except OSError:
        pass


__all__ = [
    "DEFAULT_ARTIFACT_SCOPES",
    "REJECTED_REPAIR_RETENTION",
    "REJECTED_REPAIR_ROOT",
    "copy_workspace_artifact_snapshot",
    "discard_workspace_artifact_snapshot",
    "preserve_rejected_workspace_artifacts",
    "restore_workspace_artifact_snapshot",
]
