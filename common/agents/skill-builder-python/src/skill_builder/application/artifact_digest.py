# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

from __future__ import annotations

import hashlib
import hmac
import json
from pathlib import Path
from pathlib import PurePosixPath
from typing import Any, Iterable

from skill_builder.domain.candidate_contract import (
    export_package_path_allowed,
)
from skill_builder.domain.execution import SKILL_BUILDER_POLICY_VERSION
from skill_builder.application.package_identity import resolve_package_identity


def _candidate_generated_files(
    generated_root: Path,
    *,
    path_allowed: Any = export_package_path_allowed,
) -> list[Path]:
    if not generated_root.is_dir():
        return []
    result = []
    for path in generated_root.rglob("*"):
        if not path.is_file() or path.is_symlink():
            continue
        if path_allowed(path.relative_to(generated_root).as_posix()):
            result.append(path)
    return result


def skill_artifact_sha256(generated_root: Path) -> str | None:
    root = generated_root.resolve()
    if not root.is_dir():
        return None
    digest = hashlib.sha256()
    file_count = 0
    for path in sorted(root.rglob("*")):
        if (
            not path.is_file()
            or path.is_symlink()
            or not export_package_path_allowed(path.relative_to(root).as_posix())
        ):
            continue
        relative_path = path.relative_to(root).as_posix()
        digest.update(relative_path.encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
        file_count += 1
    return digest.hexdigest() if file_count else None


def candidate_commit_from_signature(
    signature: Iterable[tuple[str, str | None]],
    *,
    package_identity: str = "",
    policy_version: str = SKILL_BUILDER_POLICY_VERSION,
) -> dict[str, Any]:
    """Create the portable commit record used at the Agent/host boundary."""

    normalized = tuple(sorted((str(path), digest) for path, digest in signature))
    return {
        "schemaVersion": "skill-builder-candidate-commit/v2",
        "artifactSha256": hashlib.sha256(
            json.dumps(normalized, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        ).hexdigest(),
        "artifactPaths": [path for path, _digest in normalized],
        "packageIdentity": str(package_identity or "").strip(),
        "policyVersion": str(policy_version or SKILL_BUILDER_POLICY_VERSION),
        "artifactCount": len(normalized),
    }


def candidate_artifact_signature(
    root: Path,
) -> tuple[tuple[str, str], ...]:
    """Return the immutable package revision at Agent handoff.

    The signature owns only the portable package. Platform diagnostics and
    validation files can change without invalidating an authored draft.
    """

    resolved_root = root.resolve()
    paths: list[Path] = []
    generated_root = resolved_root / "generated-skill"
    paths.extend(_candidate_generated_files(generated_root))
    signature: list[tuple[str, str]] = []
    for path in sorted(set(paths)):
        try:
            relative_path = path.relative_to(resolved_root).as_posix()
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
        except (OSError, ValueError):
            continue
        signature.append((relative_path, digest))
    return tuple(signature)


def verify_candidate_commit(root: Path, value: Any) -> dict[str, Any]:
    """Verify that a submitted candidate still matches the committed files."""

    if not isinstance(value, dict):
        return {"ok": False, "error": "candidate_commit_missing"}
    expected = str(value.get("artifactSha256") or "").strip()
    raw_paths = value.get("artifactPaths")
    if not expected or not isinstance(raw_paths, list) or not raw_paths:
        return {"ok": False, "error": "candidate_commit_incomplete"}
    paths: list[str] = []
    for raw in raw_paths:
        path = str(raw or "").replace("\\", "/").strip()
        while path.startswith("./"):
            path = path[2:]
        candidate = PurePosixPath(path)
        invalid_path = (
            not path
            or path.startswith("/")
            or ".." in candidate.parts
            or not path.startswith("generated-skill/")
        )
        if invalid_path:
            return {"ok": False, "error": "candidate_commit_path_invalid", "path": path}
        paths.append(candidate.as_posix())
    if len(paths) != len(set(paths)) or int(value.get("artifactCount") or -1) != len(paths):
        return {"ok": False, "error": "candidate_commit_path_count_mismatch"}
    current_signature = candidate_artifact_signature(root)
    current_paths = {path for path, _digest in current_signature}
    submitted_paths = set(paths)
    missing = sorted(submitted_paths - current_paths)
    unexpected = sorted(current_paths - submitted_paths)
    actual = candidate_commit_from_signature(current_signature)["artifactSha256"]
    digest_matches = hmac.compare_digest(expected, actual)
    if not digest_matches or missing or unexpected:
        return {
            "ok": False,
            "error": "candidate_commit_mismatch",
            "expectedArtifactSha256": expected,
            "actualArtifactSha256": actual,
            "missing": missing,
            "unexpected": unexpected,
            "artifactCount": len(current_signature),
        }

    actual_package_identity = resolve_package_identity(root, "").resolved_name
    expected_package_identity = str(value.get("packageIdentity") or "").strip()
    if (
        expected_package_identity
        and not hmac.compare_digest(expected_package_identity, actual_package_identity)
    ):
        return {
            "ok": False,
            "error": "candidate_package_identity_mismatch",
            "expectedPackageIdentity": expected_package_identity,
            "actualPackageIdentity": actual_package_identity,
            "expectedArtifactSha256": expected,
            "actualArtifactSha256": actual,
            "artifactCount": len(current_signature),
        }
    return {
        "ok": True,
        "error": None,
        "expectedArtifactSha256": expected,
        "actualArtifactSha256": actual,
        "missing": [],
        "unexpected": [],
        "artifactCount": len(current_signature),
        "packageIdentity": actual_package_identity,
    }


__all__ = [
    "candidate_artifact_signature",
    "candidate_commit_from_signature",
    "skill_artifact_sha256",
    "verify_candidate_commit",
]
