"""FileCleaner — triple cleanup strategy for output directory management.

Three strategies executed in order:
1. Retention: delete files older than output_retention_days
2. Max files: delete oldest files when count exceeds output_max_files
3. Truncation: trim oversized files to output_trim_target_ratio of threshold
"""

import asyncio
import re
import time
from pathlib import Path

import structlog

from agent_adapter.config import AdapterConfig

logger = structlog.get_logger(__name__)


def _parse_size(size_str: str) -> int:
    """Parse a human-readable size string to bytes.

    Supported suffixes: KB, MB, GB (case-insensitive).
    Returns bytes as integer.
    """
    match = re.match(r"^(\d+(?:\.\d+)?)\s*(KB|MB|GB)?$", size_str.strip(), re.IGNORECASE)
    if not match:
        raise ValueError(f"Invalid size format: {size_str!r}")
    value = float(match.group(1))
    unit = (match.group(2) or "").upper()
    multipliers = {"": 1, "KB": 1024, "MB": 1024**2, "GB": 1024**3}
    return int(value * multipliers[unit])


class FileCleaner:
    """Manages output file cleanup based on retention, count, and size policies."""

    def __init__(self, config: AdapterConfig, output_dir_override: str | None = None) -> None:
        self._output_dir = Path(output_dir_override) if output_dir_override else Path(config.output_dir)
        self._retention_days = config.output_retention_days
        self._max_files = config.output_max_files
        self._max_file_size = _parse_size(config.output_max_file_size)
        self._trim_target_ratio = config.output_trim_target_ratio
        self._lock = asyncio.Lock()

    def _list_jsonl_files(self) -> list[Path]:
        """List all JSONL files in the output directory, sorted by mtime ascending."""
        if not self._output_dir.exists():
            return []
        return sorted(
            (f for f in self._output_dir.glob("*.jsonl") if f.is_file()),
            key=lambda p: p.stat().st_mtime,
        )

    def retention_cleanup(self) -> int:
        """Delete JSONL files older than output_retention_days.

        Returns the number of files deleted.
        """
        if not self._output_dir.exists():
            return 0

        now = time.time()
        threshold = self._retention_days * 86400
        deleted = 0

        for f in self._output_dir.glob("*.jsonl"):
            if not f.is_file():
                continue
            age = now - f.stat().st_mtime
            if age > threshold:
                f.unlink()
                deleted += 1
                logger.info("retention_cleanup_deleted", file=f.name, age_days=round(age / 86400, 1))

        return deleted

    def max_files_cleanup(self) -> int:
        """Delete oldest JSONL files when total count exceeds output_max_files.

        Files are sorted by mtime ascending (oldest first); excess files
        from the oldest end are removed.

        Returns the number of files deleted.
        """
        files = self._list_jsonl_files()
        if len(files) <= self._max_files:
            return 0

        excess = len(files) - self._max_files
        deleted = 0
        for f in files[:excess]:
            f.unlink()
            deleted += 1
            logger.info("max_files_cleanup_deleted", file=f.name)

        return deleted

    def truncate_oversized_files(self) -> int:
        """Head-truncate JSONL files exceeding output_max_file_size.

        When a file exceeds the size threshold, its head is trimmed so the
        remaining content is approximately output_trim_target_ratio of the
        threshold. Truncation removes whole lines from the beginning to
        ensure each remaining line is valid JSONL.

        Returns the number of files truncated.
        """
        truncated = 0
        target_size = int(self._max_file_size * self._trim_target_ratio)

        for f in self._list_jsonl_files():
            file_size = f.stat().st_size
            if file_size <= self._max_file_size:
                continue

            # Read all lines, then find how many to keep from the tail
            content = f.read_text(encoding="utf-8")
            lines = content.split("\n")

            # Remove empty trailing element from split
            if lines and lines[-1] == "":
                lines = lines[:-1]

            # Keep dropping lines from the head until we're under target_size
            kept_lines = lines
            while kept_lines:
                estimated_size = sum(len(line) + 1 for line in kept_lines)
                if estimated_size <= target_size:
                    break
                kept_lines = kept_lines[1:]

            if kept_lines and len(kept_lines) < len(lines):
                # Rewrite file with only the kept lines
                f.write_text("\n".join(kept_lines) + "\n", encoding="utf-8")
                truncated += 1
                logger.info(
                    "truncated_oversized_file",
                    file=f.name,
                    original_size=file_size,
                    new_size=f.stat().st_size,
                )
            elif not kept_lines:
                # Edge case: even a single line exceeds target — keep it
                # to avoid data loss, log a warning
                logger.warning(
                    "single_line_exceeds_target",
                    file=f.name,
                    line_size=len(lines[-1]) if lines else 0,
                    target_size=target_size,
                )

        return truncated

    def run_all(self) -> dict[str, int]:
        """Execute all three cleanup strategies in order.

        Order: retention → max_files → truncation.
        Returns a summary dict with counts of actions taken.
        """
        retention_deleted = self.retention_cleanup()
        max_files_deleted = self.max_files_cleanup()
        truncated = self.truncate_oversized_files()

        if retention_deleted or max_files_deleted or truncated:
            logger.info(
                "cleanup_completed",
                retention_deleted=retention_deleted,
                max_files_deleted=max_files_deleted,
                truncated=truncated,
            )

        self.check_disk_space()

        return {
            "retention_deleted": retention_deleted,
            "max_files_deleted": max_files_deleted,
            "truncated": truncated,
        }

    def check_disk_space(self, warning_threshold_gb: float = 1.0) -> None:
        """Check available disk space and log a warning if low.

        Uses os.statvfs (Unix) or shutil.disk_usage (cross-platform).
        """
        import shutil

        if not self._output_dir.exists():
            return

        try:
            usage = shutil.disk_usage(self._output_dir)
            if usage.free < warning_threshold_gb * 1024**3:
                logger.warning(
                    "low_disk_space",
                    free_gb=round(usage.free / (1024**3), 2),
                    threshold_gb=warning_threshold_gb,
                    output_dir=str(self._output_dir),
                )
        except OSError:
            pass
