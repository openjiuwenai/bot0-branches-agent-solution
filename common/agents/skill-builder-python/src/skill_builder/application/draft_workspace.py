# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Persistent draft and revision storage for Skill Builder generation.

The export directory is the materialized working tree used by Agent tools.
This module keeps an independent active copy and immutable turn revisions so a
worker, sandbox, or transport failure never turns authored files into
turn-scoped temporary state.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import time
from pathlib import Path, PurePosixPath
from typing import Any

from skill_builder.application.structured_payload import decode_structured_mapping
from skill_builder.domain.scenario_contract import SCENARIO_DRAFT_MAX_BYTES
from skill_builder.domain.workspace_paths import forbidden_skill_package_path


DRAFT_STATE_SCHEMA_VERSION = "skill-builder-draft-state/v5"
DRAFT_STATE_PATH = ".skill-builder/drafts/state.json"
SCENARIO_DRAFT_PATH = ".skill-builder/drafts/scenario/current.json"


def _json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def _atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(path)


def _sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _normalize_package_path(value: Any) -> str:
    if not isinstance(value, str):
        return ""
    raw = value.replace("\\", "/").strip()
    while raw.startswith("./"):
        raw = raw[2:]
    if raw.startswith("generated-skill/"):
        raw = raw.removeprefix("generated-skill/")
    candidate = PurePosixPath(raw)
    if not raw or candidate.is_absolute() or ".." in candidate.parts:
        return ""
    normalized = candidate.as_posix()
    return "" if forbidden_skill_package_path(normalized) else normalized


def _copy_tree(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(f".{target.name}.tmp")
    shutil.rmtree(temporary, ignore_errors=True)
    temporary.mkdir(parents=True, exist_ok=False)
    if source.is_dir():
        for path in sorted(source.rglob("*")):
            if not path.is_file() or "__pycache__" in path.parts or path.suffix in {".pyc", ".pyo"}:
                continue
            relative = path.relative_to(source)
            destination = temporary / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, destination)
    if target.exists():
        shutil.rmtree(target)
    temporary.replace(target)


def _tree_digest(root: Path) -> tuple[str, list[str]]:
    digest = hashlib.sha256()
    files: list[str] = []
    if root.is_dir():
        for path in sorted(root.rglob("*")):
            if not path.is_file() or "__pycache__" in path.parts or path.suffix in {".pyc", ".pyo"}:
                continue
            relative = path.relative_to(root).as_posix()
            content = path.read_bytes()
            digest.update(relative.encode("utf-8"))
            digest.update(b"\0")
            digest.update(hashlib.sha256(content).digest())
            digest.update(b"\0")
            files.append(relative)
    return digest.hexdigest(), files


class DraftWorkspaceStore:
    """Own the durable Scenario and candidate draft lifecycle for one root."""

    def __init__(self, root: Path):
        self.root = root.resolve()
        self.private_root = self.root / ".skill-builder" / "drafts"
        self.state_path = self.root / DRAFT_STATE_PATH
        self.scenario_path = self.root / SCENARIO_DRAFT_PATH
        self.active_root = self.private_root / "candidate" / "active"
        self.revisions_root = self.private_root / "candidate" / "revisions"

    def load_state(self) -> dict[str, Any]:
        try:
            value = json.loads(self.state_path.read_text(encoding="utf-8", errors="replace"))
        except (OSError, TypeError, ValueError):
            value = {}
        state = dict(value) if isinstance(value, dict) else {}
        state["schemaVersion"] = DRAFT_STATE_SCHEMA_VERSION
        state.setdefault("phase", "prepared")
        state.setdefault("scenarioRevision", 0)
        state.setdefault("draftRevision", 0)
        state.setdefault("diagnostics", [])
        return state

    def save_state(self, state: dict[str, Any]) -> dict[str, Any]:
        payload = {
            **state,
            "schemaVersion": DRAFT_STATE_SCHEMA_VERSION,
            "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        _atomic_write_text(self.state_path, _json_text(payload))
        return payload

    def persist_scenario_draft(self, content: Any) -> dict[str, Any]:
        """Persist a bounded JSON draft before the small terminal submission."""

        decoded, issue = decode_structured_mapping(
            content,
            payload_name="ScenarioDraft",
            error_code="scenario_draft_transport_invalid",
            max_bytes=SCENARIO_DRAFT_MAX_BYTES,
            max_depth=12,
            max_nodes=2_500,
        )
        if issue is not None or decoded is None:
            return issue or {"ok": False, "error": "scenario_draft_transport_invalid"}
        encoded = _json_text(decoded).encode("utf-8")
        digest = _sha256_bytes(encoded)
        previous_digest = None
        try:
            previous_digest = _sha256_bytes(self.scenario_path.read_bytes())
        except OSError:
            pass
        _atomic_write_text(self.scenario_path, encoded.decode("utf-8"))
        state = self.load_state()
        if previous_digest != digest:
            state["scenarioRevision"] = int(state.get("scenarioRevision") or 0) + 1
        state.update({
            "phase": "scenario_draft",
            "scenarioDraftSha256": digest,
            "scenarioDraftPath": SCENARIO_DRAFT_PATH,
        })
        state = self.save_state(state)
        return {
            "ok": True,
            "path": SCENARIO_DRAFT_PATH,
            "sha256": digest,
            "scenarioRevision": state["scenarioRevision"],
            "sizeBytes": len(encoded),
        }

    def load_scenario_draft(self, *, expected_sha256: str = "") -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
        try:
            encoded = self.scenario_path.read_bytes()
        except OSError as exc:
            return None, {
                "ok": False,
                "error": "scenario_draft_missing",
                "path": SCENARIO_DRAFT_PATH,
                "message": str(exc)[:500],
            }
        actual_sha256 = _sha256_bytes(encoded)
        expected = str(expected_sha256 or "").strip().lower()
        if expected and expected != actual_sha256:
            return None, {
                "ok": False,
                "error": "scenario_draft_hash_mismatch",
                "path": SCENARIO_DRAFT_PATH,
                "expectedSha256": expected,
                "actualSha256": actual_sha256,
            }
        decoded, issue = decode_structured_mapping(
            encoded.decode("utf-8", errors="replace"),
            payload_name="ScenarioDraft",
            error_code="scenario_draft_invalid",
            max_bytes=SCENARIO_DRAFT_MAX_BYTES,
            max_depth=12,
            max_nodes=2_500,
        )
        return decoded, issue

    def capture_file(self, path: str) -> dict[str, Any]:
        """Mirror one accepted package write into the durable active draft."""

        normalized = _normalize_package_path(path)
        if not normalized:
            return {"ok": False, "error": "invalid_draft_path", "path": str(path or "")}
        source = self.root / "generated-skill" / normalized
        if not source.is_file():
            return {"ok": False, "error": "draft_source_missing", "path": f"generated-skill/{normalized}"}
        destination = self.active_root / normalized
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(f".{destination.name}.tmp")
        shutil.copy2(source, temporary)
        temporary.replace(destination)
        state = self.load_state()
        state.update({"phase": "author_draft", "activeDraftDirty": True})
        self.save_state(state)
        return {
            "ok": True,
            "path": f"generated-skill/{normalized}",
            "sha256": _sha256_bytes(source.read_bytes()),
        }

    def remove_file(self, path: str) -> dict[str, Any]:
        normalized = _normalize_package_path(path)
        if not normalized:
            return {"ok": False, "error": "invalid_draft_path", "path": str(path or "")}
        target = self.active_root / normalized
        target.unlink(missing_ok=True)
        parent = target.parent
        while parent != self.active_root:
            try:
                parent.rmdir()
            except OSError:
                break
            parent = parent.parent
        state = self.load_state()
        state.update({"phase": "author_draft", "activeDraftDirty": True})
        self.save_state(state)
        return {"ok": True, "path": f"generated-skill/{normalized}", "deleted": True}

    def sync_active_from_workspace(self) -> dict[str, Any]:
        _copy_tree(self.root / "generated-skill", self.active_root)
        digest, files = _tree_digest(self.active_root)
        state = self.load_state()
        state.update({
            "phase": "author_draft",
            "activeDraftSha256": digest,
            "activeDraftFiles": [f"generated-skill/{path}" for path in files],
            "activeDraftDirty": True,
        })
        self.save_state(state)
        return {"ok": True, "sha256": digest, "files": state["activeDraftFiles"]}

    def materialize_active_if_needed(self) -> dict[str, Any]:
        """Recover a missing or partially lost materialized working tree.

        Existing files are never overwritten here.  A controller or repair
        step may have produced a newer accepted file after the last Draft
        capture, while absent files are always safe to restore from the
        durable active copy.
        """

        generated = self.root / "generated-skill"
        current_skill = generated / "SKILL.md"
        draft_skill = self.active_root / "SKILL.md"
        if not draft_skill.is_file() or draft_skill.stat().st_size <= 0:
            return {"ok": True, "materialized": False, "reason": "active_draft_missing"}
        if not current_skill.is_file() or current_skill.stat().st_size <= 0:
            _copy_tree(self.active_root, generated)
            digest, files = _tree_digest(generated)
            return {
                "ok": True,
                "materialized": True,
                "mode": "full",
                "sha256": digest,
                "files": [f"generated-skill/{path}" for path in files],
            }

        restored_files: list[str] = []
        for source in sorted(self.active_root.rglob("*")):
            if not source.is_file() or "__pycache__" in source.parts or source.suffix in {".pyc", ".pyo"}:
                continue
            relative = source.relative_to(self.active_root)
            destination = generated / relative
            if destination.is_file():
                continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            temporary = destination.with_name(f".{destination.name}.tmp")
            shutil.copy2(source, temporary)
            temporary.replace(destination)
            restored_files.append(f"generated-skill/{relative.as_posix()}")
        if not restored_files:
            return {"ok": True, "materialized": False, "reason": "working_tree_present"}
        digest, files = _tree_digest(generated)
        return {
            "ok": True,
            "materialized": True,
            "mode": "partial",
            "restoredFiles": restored_files,
            "sha256": digest,
            "files": [f"generated-skill/{path}" for path in files],
        }

    def snapshot_revision(
        self,
        *,
        phase: str,
        diagnostics: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        """Create one immutable authoring draft revision when content changed."""

        self.sync_active_from_workspace()
        digest, files = _tree_digest(self.active_root)
        state = self.load_state()
        revision = int(state.get("draftRevision") or 0)
        changed = bool(digest != str(state.get("lastRevisionSha256") or ""))
        if changed:
            revision += 1
            revision_root = self.revisions_root / f"{revision:06d}"
            _copy_tree(self.active_root, revision_root / "generated-skill")
            _atomic_write_text(
                revision_root / "revision.json",
                _json_text({
                    "schemaVersion": "skill-builder-draft-revision/v1",
                    "revision": revision,
                    "phase": phase,
                    "sha256": digest,
                    "files": [f"generated-skill/{path}" for path in files],
                    "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                }),
            )
        state.update({
            "phase": phase,
            "draftRevision": revision,
            "lastRevisionSha256": digest,
            "activeDraftSha256": digest,
            "activeDraftFiles": [f"generated-skill/{path}" for path in files],
            "activeDraftDirty": False,
        })
        if diagnostics:
            previous = state.get("diagnostics") if isinstance(state.get("diagnostics"), list) else []
            state["diagnostics"] = [*previous, *diagnostics][-20:]
        state = self.save_state(state)
        return {
            "ok": True,
            "changed": changed,
            "draftRevision": revision,
            "sha256": digest,
            "files": state["activeDraftFiles"],
        }

    def record_diagnostic(self, diagnostic: dict[str, Any], *, phase: str) -> dict[str, Any]:
        state = self.load_state()
        previous = state.get("diagnostics") if isinstance(state.get("diagnostics"), list) else []
        state.update({
            "phase": phase,
            "diagnostics": [*previous, dict(diagnostic)][-20:],
        })
        return self.save_state(state)

__all__ = [
    "DRAFT_STATE_PATH",
    "DRAFT_STATE_SCHEMA_VERSION",
    "DraftWorkspaceStore",
    "SCENARIO_DRAFT_PATH",
]
