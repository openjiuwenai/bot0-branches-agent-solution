# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Independent package and optional validation revision state."""

from __future__ import annotations

import hashlib
import json
import time
from pathlib import Path
from typing import Any


REVISION_STATE_SCHEMA_VERSION = "skill-builder-revision-state/v2"
REVISION_STATE_PATH = ".skill-builder/revisions/state.json"


def _json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def _atomic_write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(_json_text(value), encoding="utf-8")
    temporary.replace(path)


def _digest(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            default=str,
        ).encode("utf-8")
    ).hexdigest()


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _record(value: Any) -> dict[str, Any] | None:
    return dict(value) if isinstance(value, dict) else None


class RevisionStore:
    """Own the only valid transitions between immutable lifecycle revisions."""

    def __init__(self, root: Path):
        self.root = root.resolve()
        self.revisions_root = self.root / ".skill-builder" / "revisions"
        self.state_path = self.root / REVISION_STATE_PATH

    def load_state(self) -> dict[str, Any]:
        try:
            value = json.loads(self.state_path.read_text(encoding="utf-8", errors="replace"))
        except (OSError, TypeError, ValueError, json.JSONDecodeError):
            value = {}
        state = dict(value) if isinstance(value, dict) else {}
        return {
            "schemaVersion": REVISION_STATE_SCHEMA_VERSION,
            "package": _record(state.get("package")),
            "validation": _record(state.get("validation")),
            "lifecyclePhase": str(state.get("lifecyclePhase") or "draft"),
            "updatedAt": state.get("updatedAt"),
        }

    def _save_state(self, state: dict[str, Any]) -> dict[str, Any]:
        payload = {
            **state,
            "schemaVersion": REVISION_STATE_SCHEMA_VERSION,
            "updatedAt": _now(),
        }
        _atomic_write_json(self.state_path, payload)
        return payload

    def _persist_record(self, kind: str, record: dict[str, Any]) -> None:
        revision = int(record["revision"])
        target = self.revisions_root / f"{kind}s" / f"{revision:06d}.json"
        if target.is_file():
            existing = json.loads(target.read_text(encoding="utf-8", errors="replace"))
            if existing != record:
                raise RuntimeError(f"immutable {kind} revision {revision} already exists")
            return
        _atomic_write_json(target, record)

    def current_package(self) -> dict[str, Any] | None:
        return _record(self.load_state().get("package"))

    def current_validation(self, *, compatible: bool = True) -> dict[str, Any] | None:
        state = self.load_state()
        validation = _record(state.get("validation"))
        if not compatible or validation is None:
            return validation
        package = _record(state.get("package"))
        if package is None:
            return None
        return validation if (
            validation.get("packageRevision") == package.get("revision")
            and validation.get("packageSha256") == package.get("sha256")
        ) else None

    def commit_package(
        self,
        *,
        candidate_commit: dict[str, Any],
        draft_revision: int | None,
    ) -> dict[str, Any]:
        """Commit one immutable package revision."""

        package_sha256 = str(candidate_commit.get("artifactSha256") or "").strip()
        if not package_sha256:
            raise ValueError("candidate commit is missing artifactSha256")
        state = self.load_state()
        current = _record(state.get("package"))
        commit_sha256 = _digest(candidate_commit)
        changed = not (
            current
            and current.get("sha256") == package_sha256
            and current.get("commitSha256") == commit_sha256
        )
        revision = int((current or {}).get("revision") or 0) + (1 if changed else 0)
        if not changed and current is not None:
            package = current
        else:
            package = {
                "schemaVersion": "skill-builder-package-revision/v1",
                "revision": revision,
                "sha256": package_sha256,
                "commitSha256": commit_sha256,
                "draftRevision": max(0, int(draft_revision or 0)),
                "candidateCommit": dict(candidate_commit),
                "createdAt": _now(),
            }
            self._persist_record("package", package)
        state["package"] = package
        state["lifecyclePhase"] = "package_ready"
        self._save_state(state)
        return {
            "ok": True,
            "changed": changed,
            "packageRevision": package,
        }

    def record_validation(
        self,
        validation_result: dict[str, Any],
        *,
        phase: str,
    ) -> dict[str, Any]:
        """Commit a ValidationRevision bound to the exact package revision."""

        state = self.load_state()
        package = _record(state.get("package"))
        if package is None:
            raise RuntimeError("validation revision requires a committed package revision")
        semantic_sha256 = _digest(validation_result)
        current = _record(state.get("validation"))
        changed = not (
            current
            and current.get("sha256") == semantic_sha256
            and current.get("packageRevision") == package.get("revision")
            and current.get("packageSha256") == package.get("sha256")
        )
        revision = int((current or {}).get("revision") or 0) + (1 if changed else 0)
        if not changed and current is not None:
            validation = current
        else:
            validation = {
                "schemaVersion": "skill-builder-validation-revision/v1",
                "revision": revision,
                "sha256": semantic_sha256,
                "packageRevision": package.get("revision"),
                "packageSha256": package.get("sha256"),
                "phase": str(phase or "blocked"),
                "result": dict(validation_result),
                "createdAt": _now(),
            }
            self._persist_record("validation", validation)
        state["validation"] = validation
        state["lifecyclePhase"] = str(phase or "blocked")
        self._save_state(state)
        return {
            "ok": True,
            "changed": changed,
            "validationRevision": validation,
        }

    def summary(self) -> dict[str, Any]:
        state = self.load_state()
        package = _record(state.get("package"))
        validation = self.current_validation(compatible=True)
        return {
            "phase": state.get("lifecyclePhase") or "draft",
            "packageRevision": package.get("revision") if package else None,
            "validationRevision": validation.get("revision") if validation else None,
            "package": package,
            "validation": validation,
        }


__all__ = [
    "REVISION_STATE_PATH",
    "REVISION_STATE_SCHEMA_VERSION",
    "RevisionStore",
]
