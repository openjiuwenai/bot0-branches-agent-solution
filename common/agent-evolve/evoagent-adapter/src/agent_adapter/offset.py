"""Offset management — persistence, verification, and first-start strategy."""

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal

import structlog

logger = structlog.get_logger(__name__)


@dataclass(frozen=True)
class FileOffset:
    """Tracking state for a single log file."""

    offset: int
    file_size: int
    first_line_hash: str
    completed: bool


class OffsetManager:
    """Manages offset persistence and file integrity checks.

    Reads/writes offsets.json to track how far we've read into each
    log file, and detects file rotation/rebuild via size and hash checks.
    """

    def __init__(self, offset_file: Path) -> None:
        self._offset_file = offset_file
        self._entries: dict[str, FileOffset] = {}
        self._load()

    def get(self, filename: str) -> FileOffset | None:
        """Get the offset entry for a file, or None if not tracked."""
        return self._entries.get(filename)

    def update(self, filename: str, entry: FileOffset) -> None:
        """Update the offset entry for a file and persist to disk."""
        self._entries[filename] = entry
        self._save()

    def check_file_integrity(self, filename: str, file_path: Path) -> tuple[bool, str]:
        """Check if a file has been rotated or rebuilt.

        Returns (needs_reset, reason). If needs_reset is True, the caller
        should reset offset to 0 and re-read from the beginning.
        """
        entry = self._entries.get(filename)
        if entry is None:
            return False, ""

        actual_size = file_path.stat().st_size

        # File shrunk → rotation/rebuild
        if actual_size < entry.offset:
            reason = f"file_size_shrunk: expected>={entry.offset}, actual={actual_size}"
            logger.warning(
                "offset_integrity_failure",
                filename=filename,
                reason=reason,
                expected_offset=entry.offset,
                actual_size=actual_size,
            )
            return True, reason

        # First line hash mismatch → file was replaced
        current_hash = _compute_first_line_hash(file_path)
        if current_hash != entry.first_line_hash:
            reason = f"first_line_hash_mismatch: stored={entry.first_line_hash}, actual={current_hash}"
            logger.warning(
                "offset_integrity_failure",
                filename=filename,
                reason=reason,
                stored_hash=entry.first_line_hash,
                actual_hash=current_hash,
            )
            return True, reason

        return False, ""

    def get_start_offset(
        self,
        filename: str,
        file_path: Path,
        start_from: Literal["tail", "head"],
    ) -> int:
        """Determine the starting offset for a file.

        Returns:
        - -1 if the file is marked completed (skip it)
        - Existing offset if the file is already tracked and has new data
          (offset < current file size)
        - 0 if start_from="head" and the tracked offset is at or past EOF
          (i.e. offset >= current file_size — the file was fully read in a
          previous run and the user wants to re-read from the beginning)
        - file_size if start_from="tail" (new file, start from end)
        - 0 if start_from="head" (new file, start from beginning)
        """
        entry = self._entries.get(filename)
        if entry is not None:
            if entry.completed:
                return -1
            # Compare against the CURRENT file size, not the stored one.
            # The file may have grown (new data appended) or been rotated.
            current_size = file_path.stat().st_size
            if entry.offset < current_size:
                # File has new data since last read — continue from offset
                return entry.offset
            # Offset is at or past EOF and start_from=head — the user
            # likely switched from tail→head and wants to re-read from start.
            if start_from == "head":
                logger.info(
                    "offset_reset_head",
                    filename=filename,
                    old_offset=entry.offset,
                    reason="offset_at_eof_with_head_strategy",
                )
                return 0
            # start_from=tail and at EOF — stay at EOF, wait for new data
            return entry.offset

        # New file — use start_from strategy
        if start_from == "tail":
            return file_path.stat().st_size
        return 0

    def _load(self) -> None:
        """Load offsets from the JSON file."""
        if not self._offset_file.exists():
            return
        try:
            data = json.loads(self._offset_file.read_text(encoding="utf-8"))
            for filename, entry_data in data.get("files", {}).items():
                self._entries[filename] = FileOffset(
                    offset=entry_data["offset"],
                    file_size=entry_data["file_size"],
                    first_line_hash=entry_data["first_line_hash"],
                    completed=entry_data.get("completed", False),
                )
        except (json.JSONDecodeError, KeyError):
            pass  # Corrupted file → start fresh

    def _save(self) -> None:
        """Persist current offsets to the JSON file."""
        data = {
            "files": {
                filename: {
                    "offset": entry.offset,
                    "file_size": entry.file_size,
                    "first_line_hash": entry.first_line_hash,
                    "completed": entry.completed,
                }
                for filename, entry in self._entries.items()
            },
            "last_update": datetime.now(timezone.utc).isoformat(),
        }
        self._offset_file.parent.mkdir(parents=True, exist_ok=True)
        self._offset_file.write_text(json.dumps(data, indent=2), encoding="utf-8")


def _compute_first_line_hash(file_path: Path, max_bytes: int = 128) -> str:
    """Compute a hash of the first line (up to max_bytes) for integrity checking."""
    try:
        with open(file_path, encoding="utf-8", errors="replace") as f:
            first_line = f.readline()[:max_bytes]
        return hashlib.md5(first_line.encode("utf-8")).hexdigest()[:8]
    except (OSError, UnicodeDecodeError):
        return ""
