"""Single-file doc storage for POST /api/v1/managed-docs.

Mirrors ``skill_store.SkillStore``'s storage semantics but in single-file form
(spec managed-doc-agent-rule §5.2): atomic write (tmp + ``os.replace``),
``.meta/{kind}.json`` revision bookmark (written only after a successful apply,
by the service layer — storage itself just exposes ``write_meta``),
``.meta/{kind}.snapshot`` (idempotent first-update capture), sha256 revisioning,
restore-from-snapshot, and path-traversal protection against the host-side path.
"""

from __future__ import annotations

import hashlib
import json
import os
import secrets
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import structlog

logger = structlog.get_logger(__name__)

_META_DIR = ".meta"
_SNAPSHOT_SUFFIX = ".snapshot"
_MSG_NO_SNAPSHOT = "未找到快照：该 doc 未被更新过"
_MSG_DOC_NOT_FOUND = "doc 文件不存在"


class DocStorageError(Exception):
    """Base error for managed-doc storage operations."""


class AgentNotFoundError(DocStorageError):
    """Unknown agent_name (raised by registry/service, not DocStorage itself)."""


class DocNotFoundError(DocStorageError):
    """doc file or snapshot missing."""


class InvalidDocPathError(DocStorageError):
    """configured path escapes the allowed root."""


class DocStorage:
    """Storage for a single managed-doc file on a shared host mount.

    ``allow_root`` is the agent mount root (deployment convention); the
    configured ``path`` must resolve under it. ``.meta`` lives under
    ``allow_root`` so the file and its bookmarks share one trusted root.
    """

    def __init__(self, *, kind: str, path: str, allow_root: Path) -> None:
        self._kind = kind
        self._raw_path = path
        self._allow_root = Path(allow_root)
        # Eager validation: fail fast on a misconfigured path (defence-in-depth,
        # mirrors skill_store._resolve_under but for a host-side absolute path).
        self._resolved_path = self._resolve_path()

    # ── path resolution & traversal protection ───────────────────────

    def _resolve_path(self) -> Path:
        root = self._allow_root.resolve()
        raw = Path(self._raw_path)
        resolved = (root / raw).resolve() if not raw.is_absolute() else raw.resolve()
        if not resolved.is_relative_to(root):
            raise InvalidDocPathError(
                f"Path escapes allowed root: {self._raw_path!r}"
            )
        return resolved

    @property
    def _meta_dir(self) -> Path:
        return self._allow_root / _META_DIR

    @property
    def _meta_path(self) -> Path:
        return self._meta_dir / f"{self._kind}.json"

    @property
    def _snapshot_path(self) -> Path:
        return self._meta_dir / f"{self._kind}{_SNAPSHOT_SUFFIX}"

    @property
    def _tmp_path(self) -> Path:
        # Random suffix so concurrent updates of same-kind docs sharing a parent
        # directory cannot collide on the tmp filename (I2).
        return self._allow_root / f".{self._kind}.{os.getpid()}.{secrets.token_hex(4)}.tmp"

    # ── file read / write ───────────────────────────────────────────

    def read_file(self) -> str:
        if not self._resolved_path.is_file():
            raise DocNotFoundError(_MSG_DOC_NOT_FOUND)
        return self._resolved_path.read_text(encoding="utf-8")

    def write_file_atomic(self, content: str) -> None:
        """Atomic write: tmp file + ``os.replace``. On failure the original is
        untouched and the tmp is cleaned (AC2.1)."""
        self._meta_dir.mkdir(parents=True, exist_ok=True)
        tmp = self._tmp_path
        try:
            tmp.write_text(content, encoding="utf-8")
            os.replace(tmp, self._resolved_path)
        finally:
            if tmp.exists():
                tmp.unlink(missing_ok=True)

    # ── .meta revision bookmark ─────────────────────────────────────

    def read_meta(self) -> dict[str, Any] | None:
        """Return the ``.meta`` payload, or None when absent (AC2.4).

        The service layer treats ``revision is None`` as ``applied_revision=None``
        (content path computes ``pending_apply=True``).
        """
        if not self._meta_path.is_file():
            return None
        try:
            data = json.loads(self._meta_path.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else None
        except (json.JSONDecodeError, OSError):
            return None

    def write_meta(self, *, revision: str) -> None:
        """Persist the applied-revision bookmark (called by service only after a
        successful apply — storage does not decide apply success)."""
        self._meta_dir.mkdir(parents=True, exist_ok=True)
        payload = {
            "kind": self._kind,
            "revision": revision,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        tmp = self._meta_dir / f".{self._kind}.{secrets.token_hex(4)}.tmp"
        try:
            tmp.write_text(json.dumps(payload, indent=2), encoding="utf-8")
            os.replace(tmp, self._meta_path)
        finally:
            if tmp.exists():
                tmp.unlink(missing_ok=True)

    def read_revision(self) -> str | None:
        meta = self.read_meta()
        if meta is None:
            return None
        rev = meta.get("revision")
        return str(rev) if rev else None

    # ── snapshot ────────────────────────────────────────────────────

    def ensure_snapshot(self) -> None:
        """Capture the pre-update file content once; idempotent (AC2.2).

        Repeated updates only write the snapshot the first time — it preserves
        the original version for restore, surviving later overwrites. If the doc
        file does not yet exist (bootstrap of a new doc), there is no pre-update
        content to capture, so the snapshot is skipped — a later ``restore``
        will 404, which is the correct contract for a bootstrapped doc.
        """
        if self._snapshot_path.is_file():
            return
        if not self._resolved_path.is_file():
            return  # nothing to snapshot yet (first-ever update on a new doc)
        current = self.read_file()
        self._meta_dir.mkdir(parents=True, exist_ok=True)
        tmp = self._meta_dir / f".{self._kind}.{secrets.token_hex(4)}.snapshot.tmp"
        try:
            tmp.write_text(current, encoding="utf-8")
            os.replace(tmp, self._snapshot_path)
        finally:
            if tmp.exists():
                tmp.unlink(missing_ok=True)

    def read_snapshot(self) -> str:
        if not self._snapshot_path.is_file():
            raise DocNotFoundError(_MSG_NO_SNAPSHOT)
        return self._snapshot_path.read_text(encoding="utf-8")

    # ── helpers ─────────────────────────────────────────────────────

    @staticmethod
    def sha256(content: str) -> str:
        return hashlib.sha256(content.encode("utf-8")).hexdigest()
