"""Durable atomic writers shared by every JSON, JSONL, and marker artifact."""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
from pathlib import Path
from typing import Any

from evo_agent.errors import ArtifactPersistenceError


def atomic_write_json(path: Path, data: Any) -> None:
    payload = json.dumps(data, indent=2, ensure_ascii=False, default=str).encode("utf-8")
    atomic_write_bytes(path, payload)


def atomic_write_jsonl(path: Path, rows: list[Any] | tuple[Any, ...]) -> None:
    payload = b"".join(
        (json.dumps(row, ensure_ascii=False, default=str) + "\n").encode("utf-8") for row in rows
    )
    atomic_write_bytes(path, payload)


def atomic_write_marker(path: Path) -> None:
    atomic_write_bytes(path, b"")


def atomic_write_bytes(path: Path, payload: bytes) -> None:
    temporary: Path | None = None
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
        temporary = Path(temp_name)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        temporary = None
        # Best-effort directory fsync so rename is durable on POSIX. Windows (and
        # some network FS) cannot os.open() a directory — PermissionError — so
        # skip rather than failing after the file is already published.
        try:
            directory_fd = os.open(path.parent, os.O_RDONLY)
        except OSError:
            directory_fd = -1
        if directory_fd >= 0:
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    except BaseException as exc:
        if temporary is not None:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass
        if isinstance(exc, (KeyboardInterrupt, SystemExit)):
            raise
        raise ArtifactPersistenceError(str(path), exc) from exc


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(65536), b""):
                digest.update(chunk)
    except OSError as exc:
        raise ArtifactPersistenceError(str(path), exc) from exc
    return digest.hexdigest()


__all__ = [
    "atomic_write_bytes",
    "atomic_write_json",
    "atomic_write_jsonl",
    "atomic_write_marker",
    "sha256_file",
]
