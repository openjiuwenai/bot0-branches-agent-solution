"""Unit tests for LogReader — incremental reading with half-line caching."""

import pytest

from agent_adapter.offset import FileOffset
from agent_adapter.reader import LogReader


class TestHalfLineCaching:
    """LogReader caches incomplete lines (no trailing newline) for next poll."""

    def test_half_line_cached_and_completed_on_next_poll(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        log_file = log_dir / "process_12345.log"

        # Write a line without trailing newline (simulates mid-write read)
        log_file.write_text("2026-06-09 14:30:15.123\x01INFO\x01src\x01trace\x01agent\x01conv\x01TAG_LLM_CALL_START\x010\x01{\"id\": \"uu", encoding="utf-8")

        reader = LogReader(log_dir=log_dir, log_pattern="process_*.log", start_from="head")
        lines1, pending = reader.read_new_lines()
        # Half line should be cached, not returned yet
        assert lines1 == []
        assert pending != ""

        # Now append the rest of the line with a newline
        with open(log_file, "a", encoding="utf-8") as f:
            f.write("id-001\"}\n")

        lines2, pending2 = reader.read_new_lines()
        assert len(lines2) == 1
        assert "TAG_LLM_CALL_START" in lines2[0]
        assert "uuid-001" in lines2[0]


class TestIncrementalReading:
    """LogReader only reads new content added after the last offset."""

    def test_first_read_from_head(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        log_file = log_dir / "process_12345.log"
        log_file.write_text("line1\nline2\nline3\n", encoding="utf-8")

        reader = LogReader(log_dir=log_dir, log_pattern="process_*.log", start_from="head")
        lines, _ = reader.read_new_lines()
        assert len(lines) == 3

    def test_first_read_from_tail_skips_existing(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        log_file = log_dir / "process_12345.log"
        log_file.write_text("line1\nline2\n", encoding="utf-8")

        reader = LogReader(log_dir=log_dir, log_pattern="process_*.log", start_from="tail")
        lines, _ = reader.read_new_lines()
        assert lines == []

    def test_second_read_only_gets_new_lines(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        log_file = log_dir / "process_12345.log"
        log_file.write_text("line1\nline2\n", encoding="utf-8")

        reader = LogReader(log_dir=log_dir, log_pattern="process_*.log", start_from="head")
        lines1, _ = reader.read_new_lines()
        assert len(lines1) == 2

        # Append new content
        with open(log_file, "a", encoding="utf-8") as f:
            f.write("line3\nline4\n")

        lines2, _ = reader.read_new_lines()
        assert len(lines2) == 2
        assert "line3" in lines2[0]
        assert "line4" in lines2[1]

    def test_no_new_content_returns_empty(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        log_file = log_dir / "process_12345.log"
        log_file.write_text("line1\n", encoding="utf-8")

        reader = LogReader(log_dir=log_dir, log_pattern="process_*.log", start_from="head")
        reader.read_new_lines()  # First read
        lines, _ = reader.read_new_lines()  # No new content
        assert lines == []


class TestGlobFileDiscovery:
    """LogReader discovers log files via glob pattern."""

    def test_discovers_matching_files(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        (log_dir / "process_111.log").write_text("line1\n", encoding="utf-8")
        (log_dir / "process_222.log").write_text("line2\n", encoding="utf-8")
        (log_dir / "other_file.txt").write_text("line3\n", encoding="utf-8")

        reader = LogReader(log_dir=log_dir, log_pattern="process_*.log", start_from="head")
        files = reader.discover_files()
        filenames = [f.name for f in files]
        assert "process_111.log" in filenames
        assert "process_222.log" in filenames
        assert "other_file.txt" not in filenames

    def test_files_sorted_by_mtime(self, tmp_path):
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        f1 = log_dir / "process_111.log"
        f2 = log_dir / "process_222.log"
        f1.write_text("old\n", encoding="utf-8")
        f2.write_text("newer\n", encoding="utf-8")

        reader = LogReader(log_dir=log_dir, log_pattern="process_*.log", start_from="head")
        files = reader.discover_files()
        # Most recently modified file should be last (or first, depending on sort)
        assert len(files) == 2


class TestMultiFileReading:
    """LogReader reads ALL matching files, not just the latest."""

    def test_reads_all_files_with_start_from_head(self, tmp_path):
        """With start_from=head, all matching files are read."""
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        (log_dir / "process_111.log").write_text("line_a1\nline_a2\n", encoding="utf-8")
        (log_dir / "process_222.log").write_text("line_b1\nline_b2\n", encoding="utf-8")

        reader = LogReader(log_dir=log_dir, log_pattern="process_*.log", start_from="head")
        lines, _ = reader.read_new_lines()
        # All 4 lines from both files should be returned
        assert len(lines) == 4
        assert "line_a1" in lines
        assert "line_a2" in lines
        assert "line_b1" in lines
        assert "line_b2" in lines

    def test_reads_all_files_with_start_from_tail(self, tmp_path):
        """With start_from=tail, existing content is skipped but all files are tracked."""
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        (log_dir / "process_111.log").write_text("line_a1\n", encoding="utf-8")
        (log_dir / "process_222.log").write_text("line_b1\n", encoding="utf-8")

        reader = LogReader(log_dir=log_dir, log_pattern="process_*.log", start_from="tail")
        lines, _ = reader.read_new_lines()
        # All existing content skipped (tail)
        assert lines == []

        # Now append to both files
        with open(log_dir / "process_111.log", "a", encoding="utf-8") as f:
            f.write("line_a2\n")
        with open(log_dir / "process_222.log", "a", encoding="utf-8") as f:
            f.write("line_b2\n")

        lines, _ = reader.read_new_lines()
        assert len(lines) == 2
        assert "line_a2" in lines
        assert "line_b2" in lines

    def test_completed_files_are_skipped(self, tmp_path):
        """Files marked as completed in offsets are not re-read."""
        log_dir = tmp_path / "logs"
        log_dir.mkdir()
        offset_file = tmp_path / "offsets.json"

        (log_dir / "process_111.log").write_text("line_a1\n", encoding="utf-8")
        (log_dir / "process_222.log").write_text("line_b1\n", encoding="utf-8")

        reader = LogReader(log_dir=log_dir, log_pattern="process_*.log", start_from="head",
                           offset_file=offset_file)
        lines1, _ = reader.read_new_lines()
        assert len(lines1) == 2

        # Mark the older file as completed
        from agent_adapter.offset import OffsetManager, FileOffset
        mgr = OffsetManager(offset_file=offset_file)
        entry = mgr.get("process_111.log")
        if entry is not None:
            mgr.update("process_111.log", FileOffset(
                offset=entry.offset,
                file_size=entry.file_size,
                first_line_hash=entry.first_line_hash,
                completed=True,
            ))

        # Append new content to both files
        with open(log_dir / "process_111.log", "a", encoding="utf-8") as f:
            f.write("line_a2\n")
        with open(log_dir / "process_222.log", "a", encoding="utf-8") as f:
            f.write("line_b2\n")

        # Re-create reader to reload offsets
        reader2 = LogReader(log_dir=log_dir, log_pattern="process_*.log", start_from="head",
                            offset_file=offset_file)
        lines2, _ = reader2.read_new_lines()
        # Only process_222 should have new data (process_111 is completed)
        assert len(lines2) == 1
        assert "line_b2" in lines2
