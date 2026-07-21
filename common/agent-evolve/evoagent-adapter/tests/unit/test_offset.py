"""Unit tests for OffsetManager — offset persistence and file verification."""

import json

import pytest

from agent_adapter.offset import FileOffset, OffsetManager


class TestOffsetPersistence:
    """OffsetManager persists and restores offsets via JSON file."""

    def test_save_and_load_offset(self, tmp_path):
        offset_file = tmp_path / "offsets.json"
        mgr = OffsetManager(offset_file=offset_file)

        mgr.update("process_12345.log", FileOffset(
            offset=1024,
            file_size=2048,
            first_line_hash="abc123",
            completed=False,
        ))

        # Load fresh instance from the same file
        mgr2 = OffsetManager(offset_file=offset_file)
        entry = mgr2.get("process_12345.log")
        assert entry is not None
        assert entry.offset == 1024
        assert entry.file_size == 2048
        assert entry.first_line_hash == "abc123"
        assert entry.completed is False

    def test_update_offset_increments(self, tmp_path):
        offset_file = tmp_path / "offsets.json"
        mgr = OffsetManager(offset_file=offset_file)

        mgr.update("process_12345.log", FileOffset(
            offset=1024, file_size=2048, first_line_hash="abc", completed=False,
        ))
        mgr.update("process_12345.log", FileOffset(
            offset=2048, file_size=4096, first_line_hash="abc", completed=False,
        ))

        entry = mgr.get("process_12345.log")
        assert entry.offset == 2048
        assert entry.file_size == 4096

    def test_no_offset_file_returns_none(self, tmp_path):
        offset_file = tmp_path / "nonexistent.json"
        mgr = OffsetManager(offset_file=offset_file)
        assert mgr.get("process_12345.log") is None

    def test_multiple_files_tracked(self, tmp_path):
        offset_file = tmp_path / "offsets.json"
        mgr = OffsetManager(offset_file=offset_file)

        mgr.update("process_111.log", FileOffset(
            offset=100, file_size=200, first_line_hash="h1", completed=False,
        ))
        mgr.update("process_222.log", FileOffset(
            offset=300, file_size=600, first_line_hash="h2", completed=False,
        ))

        assert mgr.get("process_111.log").offset == 100
        assert mgr.get("process_222.log").offset == 300


class TestFileVerification:
    """OffsetManager detects file rotation/rebuild via size and hash checks."""

    def test_file_shrunk_resets_offset(self, tmp_path):
        """If actual file size < stored offset, the file was rotated/rebuilt."""
        offset_file = tmp_path / "offsets.json"
        log_file = tmp_path / "process_12345.log"
        log_file.write_text("x" * 100, encoding="utf-8")

        mgr = OffsetManager(offset_file=offset_file)
        mgr.update("process_12345.log", FileOffset(
            offset=500, file_size=1000, first_line_hash="abc", completed=False,
        ))

        # File is only 100 bytes but offset was 500 → needs reset
        needs_reset, reason = mgr.check_file_integrity("process_12345.log", log_file)
        assert needs_reset is True
        assert "size" in reason.lower() or "shrunk" in reason.lower() or "rotation" in reason.lower()

    def test_file_grew_normally_no_reset(self, tmp_path):
        """If file grew beyond offset, no reset needed."""
        offset_file = tmp_path / "offsets.json"
        log_file = tmp_path / "process_12345.log"
        log_file.write_text("x" * 2000, encoding="utf-8")

        mgr = OffsetManager(offset_file=offset_file)
        # Compute the real hash so integrity check passes
        from agent_adapter.offset import _compute_first_line_hash
        real_hash = _compute_first_line_hash(log_file)

        mgr.update("process_12345.log", FileOffset(
            offset=500, file_size=1000, first_line_hash=real_hash, completed=False,
        ))

        needs_reset, _ = mgr.check_file_integrity("process_12345.log", log_file)
        assert needs_reset is False

    def test_first_line_hash_mismatch_resets(self, tmp_path):
        """If first line hash changed, the file was replaced."""
        offset_file = tmp_path / "offsets.json"
        log_file = tmp_path / "process_12345.log"
        log_file.write_text("different first line content\n" + "x" * 2000, encoding="utf-8")

        mgr = OffsetManager(offset_file=offset_file)
        mgr.update("process_12345.log", FileOffset(
            offset=100, file_size=2000, first_line_hash="wrong_hash", completed=False,
        ))

        needs_reset, reason = mgr.check_file_integrity("process_12345.log", log_file)
        assert needs_reset is True
        assert "hash" in reason.lower()

    def test_unknown_file_no_reset(self, tmp_path):
        """A file not in offsets.json does not trigger reset."""
        offset_file = tmp_path / "offsets.json"
        log_file = tmp_path / "process_new.log"
        log_file.write_text("x" * 100, encoding="utf-8")

        mgr = OffsetManager(offset_file=offset_file)
        needs_reset, _ = mgr.check_file_integrity("process_new.log", log_file)
        assert needs_reset is False


class TestFirstStartStrategy:
    """OffsetManager returns the correct starting offset for first-start scenarios."""

    def test_first_start_tail_returns_file_size(self, tmp_path):
        log_file = tmp_path / "process_12345.log"
        log_file.write_text("x" * 1000, encoding="utf-8")

        offset_file = tmp_path / "offsets.json"
        mgr = OffsetManager(offset_file=offset_file)
        start_offset = mgr.get_start_offset("process_12345.log", log_file, start_from="tail")
        assert start_offset == 1000

    def test_first_start_head_returns_zero(self, tmp_path):
        log_file = tmp_path / "process_12345.log"
        log_file.write_text("x" * 1000, encoding="utf-8")

        offset_file = tmp_path / "offsets.json"
        mgr = OffsetManager(offset_file=offset_file)
        start_offset = mgr.get_start_offset("process_12345.log", log_file, start_from="head")
        assert start_offset == 0

    def test_existing_offset_ignores_start_from_when_not_at_eof(self, tmp_path):
        """If the file is partially read, existing offset takes precedence even with start_from=head."""
        log_file = tmp_path / "process_12345.log"
        log_file.write_text("x" * 2000, encoding="utf-8")

        offset_file = tmp_path / "offsets.json"
        mgr = OffsetManager(offset_file=offset_file)
        mgr.update("process_12345.log", FileOffset(
            offset=500, file_size=1000, first_line_hash="abc", completed=False,
        ))

        # File is partially read (offset 500 < file_size 1000) — keep reading from 500
        start_offset = mgr.get_start_offset("process_12345.log", log_file, start_from="head")
        assert start_offset == 500

    def test_existing_offset_at_eof_resets_with_head(self, tmp_path):
        """If offset>=current_file_size and start_from=head, re-read from start.

        Simulates a previous tail run where offset reached file_size,
        and the file has NOT grown since — user switches to head to re-read.
        """
        log_file = tmp_path / "process_12345.log"
        # File is exactly 1000 bytes — same as the stored offset/file_size.
        # This means the file was fully read and hasn't grown.
        log_file.write_text("x" * 1000, encoding="utf-8")

        offset_file = tmp_path / "offsets.json"
        mgr = OffsetManager(offset_file=offset_file)
        mgr.update("process_12345.log", FileOffset(
            offset=1000, file_size=1000, first_line_hash="abc", completed=False,
        ))

        # offset (1000) >= current file size (1000) and start_from=head → reset to 0
        start_offset = mgr.get_start_offset("process_12345.log", log_file, start_from="head")
        assert start_offset == 0

    def test_existing_offset_at_eof_keeps_eof_with_tail(self, tmp_path):
        """If offset>=current_file_size and start_from=tail, stay at EOF (no re-read)."""
        log_file = tmp_path / "process_12345.log"
        log_file.write_text("x" * 1000, encoding="utf-8")

        offset_file = tmp_path / "offsets.json"
        mgr = OffsetManager(offset_file=offset_file)
        mgr.update("process_12345.log", FileOffset(
            offset=1000, file_size=1000, first_line_hash="abc", completed=False,
        ))

        # offset (1000) >= current file size (1000) and start_from=tail → stay at EOF
        start_offset = mgr.get_start_offset("process_12345.log", log_file, start_from="tail")
        assert start_offset == 1000

    def test_existing_offset_resumes_when_file_grew(self, tmp_path):
        """If offset<current_file_size (file grew), resume from offset even with start_from=head."""
        log_file = tmp_path / "process_12345.log"
        # File grew to 2000 bytes after a previous read that stopped at offset=1000
        log_file.write_text("x" * 2000, encoding="utf-8")

        offset_file = tmp_path / "offsets.json"
        mgr = OffsetManager(offset_file=offset_file)
        mgr.update("process_12345.log", FileOffset(
            offset=1000, file_size=1000, first_line_hash="abc", completed=False,
        ))

        # offset (1000) < current file size (2000) → resume reading from 1000
        start_offset = mgr.get_start_offset("process_12345.log", log_file, start_from="head")
        assert start_offset == 1000

    def test_completed_file_returns_negative_one(self, tmp_path):
        log_file = tmp_path / "process_12345.log"
        offset_file = tmp_path / "offsets.json"
        mgr = OffsetManager(offset_file=offset_file)
        mgr.update("process_12345.log", FileOffset(
            offset=1000, file_size=1000, first_line_hash="abc", completed=True,
        ))

        start_offset = mgr.get_start_offset("process_12345.log", log_file, start_from="tail")
        assert start_offset == -1  # Signal: skip this file
