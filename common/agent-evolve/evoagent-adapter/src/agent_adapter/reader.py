"""LogReader — incremental file reading with offset tracking and half-line caching."""

from pathlib import Path

from agent_adapter.offset import FileOffset, OffsetManager, _compute_first_line_hash


class LogReader:
    """Incremental log file reader with offset tracking.

    Supports:
    - Glob-based file discovery
    - Offset-based incremental reading (only new content)
    - Half-line caching per file (incomplete lines at read boundary)
    - First-start strategy (tail or head)
    - Multi-file reading (processes ALL matching files, oldest first)
    """

    def __init__(
        self,
        log_dir: Path,
        log_pattern: str = "process_*.log",
        start_from: str = "tail",
        offset_file: Path | None = None,
    ) -> None:
        self._log_dir = log_dir
        self._log_pattern = log_pattern
        self._start_from = start_from
        # Use the configured offset_file path (resolved relative to adapter root),
        # falling back to log_dir-based path only if not provided.
        if offset_file is not None:
            self._offset_mgr = OffsetManager(offset_file=offset_file)
        else:
            self._offset_mgr = OffsetManager(offset_file=log_dir / ".agent-adapter" / "offsets.json")
        self._initialized_files: set[str] = set()
        # Per-file half-line cache: incomplete lines at read boundary are
        # stored here and prepended to the next read of the same file.
        self._pending_lines: dict[str, str] = {}
        self._current_file: str | None = None

    @property
    def offset_mgr(self) -> OffsetManager:
        """The shared OffsetManager instance for this reader.

        Coordinated components (e.g. Pipeline) should use this instead
        of creating a separate OffsetManager to avoid redundant disk I/O
        and state divergence.
        """
        return self._offset_mgr

    def discover_files(self) -> list[Path]:
        """Discover log files matching the glob pattern, sorted by modification time."""
        files = sorted(
            self._log_dir.glob(self._log_pattern),
            key=lambda p: p.stat().st_mtime,
        )
        return files

    def read_new_lines(self) -> tuple[list[str], str]:
        """Read new lines from ALL matching log files.

        Processes files in mtime order (oldest first) so that
        START/END pairs are processed chronologically across files.

        Returns (lines, pending_line) where pending_line is any
        incomplete line from the last file processed.
        """
        files = self.discover_files()
        if not files:
            return [], ""

        all_lines: list[str] = []
        last_pending = ""

        for target in files:
            lines, pending = self._read_single_file(target)
            all_lines.extend(lines)
            last_pending = pending

        return all_lines, last_pending

    def _read_single_file(self, target: Path) -> tuple[list[str], str]:
        """Read new lines from a single log file.

        Handles offset initialization, integrity checks, half-line
        caching, and offset updates for this file.

        Returns (lines, pending_line) where pending_line is any
        incomplete line (no trailing newline) to be cached for next poll.
        """
        filename = target.name

        # Initialize offset for new files
        if filename not in self._initialized_files:
            self._init_file(filename, target)

        entry = self._offset_mgr.get(filename)
        if entry is None or entry.completed:
            return [], self._pending_lines.get(filename, "")

        # Check file integrity
        needs_reset, _ = self._offset_mgr.check_file_integrity(filename, target)
        if needs_reset:
            self._offset_mgr.update(filename, FileOffset(
                offset=0,
                file_size=target.stat().st_size,
                first_line_hash=_compute_first_line_hash(target),
                completed=False,
            ))
            entry = self._offset_mgr.get(filename)

        # Read from current offset
        offset = entry.offset
        file_size = target.stat().st_size

        if offset >= file_size:
            return [], self._pending_lines.get(filename, "")

        # Use binary mode to ensure offset tracking (byte positions)
        # matches file_size (st_size). Text mode on Windows silently
        # converts \r\n → \n, causing len(text_content) < file_size
        # and leaving a gap that produces duplicate reads on the next poll.
        with open(target, "rb") as f:
            f.seek(offset)
            raw_bytes = f.read()

        if not raw_bytes:
            return [], self._pending_lines.get(filename, "")

        raw_content = raw_bytes.decode("utf-8", errors="replace")

        # Normalize \r\n → \n so downstream parsing is consistent
        raw_content = raw_content.replace("\r\n", "\n").replace("\r", "\n")

        # Split into lines, handling pending from previous poll of this file
        pending = self._pending_lines.get(filename, "")
        full_content = pending + raw_content
        lines = full_content.split("\n")

        # If the content doesn't end with newline, the last fragment is incomplete
        new_pending = ""
        if not raw_content.endswith("\n"):
            new_pending = lines[-1]
            lines = lines[:-1]
        else:
            # Remove the empty trailing element from split
            if lines and lines[-1] == "":
                lines = lines[:-1]

        self._pending_lines[filename] = new_pending

        # Filter out empty lines
        lines = [line for line in lines if line.strip()]

        # Update offset — use byte length (len(raw_bytes)), not character length,
        # because offset and file_size are both byte positions.
        new_offset = offset + len(raw_bytes) - len(new_pending.encode("utf-8", errors="replace"))
        self._offset_mgr.update(filename, FileOffset(
            offset=new_offset,
            file_size=file_size,
            first_line_hash=entry.first_line_hash,
            completed=False,
        ))

        return lines, new_pending

    def _init_file(self, filename: str, file_path: Path) -> None:
        """Initialize offset tracking for a new file."""
        self._initialized_files.add(filename)
        start_offset = self._offset_mgr.get_start_offset(
            filename, file_path, self._start_from,
        )
        if start_offset == -1:
            return  # File already completed

        file_size = file_path.stat().st_size
        first_hash = _compute_first_line_hash(file_path)
        self._offset_mgr.update(filename, FileOffset(
            offset=start_offset,
            file_size=file_size,
            first_line_hash=first_hash,
            completed=False,
        ))
