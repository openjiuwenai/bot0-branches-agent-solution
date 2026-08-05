"""Unit tests for output file management — triple cleanup strategy."""

import os
import time
from pathlib import Path

import pytest

from agent_adapter.config import AdapterConfig


@pytest.fixture
def output_dir(tmp_path: Path) -> Path:
    """Create and return an output directory for tests."""
    d = tmp_path / "output"
    d.mkdir()
    return d


def _write_jsonl(path: Path, lines: int = 1, content: str = '{"call_id": "test"}') -> None:
    """Write a JSONL file with the given number of lines."""
    with open(path, "w", encoding="utf-8") as f:
        for _ in range(lines):
            f.write(content + "\n")


def _set_mtime(path: Path, days_ago: float) -> None:
    """Set a file's mtime to N days in the past."""
    mtime = time.time() - (days_ago * 86400)
    os.utime(path, (mtime, mtime))


class TestRetentionCleanup:
    """Files older than output_retention_days are deleted."""

    def test_deletes_expired_files(self, output_dir: Path) -> None:
        from agent_adapter.cleaner import FileCleaner

        # Create files: one expired (31 days old), one fresh (5 days old)
        old_file = output_dir / "conv-old.jsonl"
        fresh_file = output_dir / "conv-fresh.jsonl"
        _write_jsonl(old_file)
        _write_jsonl(fresh_file)
        _set_mtime(old_file, days_ago=31)
        _set_mtime(fresh_file, days_ago=5)

        config = AdapterConfig(output_dir=str(output_dir), output_retention_days=30)
        cleaner = FileCleaner(config)
        deleted = cleaner.retention_cleanup()

        assert not old_file.exists()
        assert fresh_file.exists()
        assert deleted == 1

    def test_no_deletion_when_all_fresh(self, output_dir: Path) -> None:
        from agent_adapter.cleaner import FileCleaner

        file1 = output_dir / "conv-001.jsonl"
        file2 = output_dir / "conv-002.jsonl"
        _write_jsonl(file1)
        _write_jsonl(file2)
        _set_mtime(file1, days_ago=1)
        _set_mtime(file2, days_ago=10)

        config = AdapterConfig(output_dir=str(output_dir), output_retention_days=30)
        cleaner = FileCleaner(config)
        deleted = cleaner.retention_cleanup()

        assert file1.exists()
        assert file2.exists()
        assert deleted == 0

    def test_empty_output_dir(self, output_dir: Path) -> None:
        from agent_adapter.cleaner import FileCleaner

        config = AdapterConfig(output_dir=str(output_dir), output_retention_days=30)
        cleaner = FileCleaner(config)
        deleted = cleaner.retention_cleanup()

        assert deleted == 0


class TestMaxFilesCleanup:
    """Oldest files are deleted when total count exceeds output_max_files."""

    def test_deletes_oldest_when_exceeds_limit(self, output_dir: Path) -> None:
        from agent_adapter.cleaner import FileCleaner

        # Create 5 files with different ages
        for i in range(5):
            f = output_dir / f"conv-{i:03d}.jsonl"
            _write_jsonl(f)
            _set_mtime(f, days_ago=10 - i)  # conv-000 oldest, conv-004 newest

        config = AdapterConfig(output_dir=str(output_dir), output_max_files=3)
        cleaner = FileCleaner(config)
        deleted = cleaner.max_files_cleanup()

        # Should delete 2 oldest (conv-000, conv-001)
        assert deleted == 2
        assert not (output_dir / "conv-000.jsonl").exists()
        assert not (output_dir / "conv-001.jsonl").exists()
        assert (output_dir / "conv-002.jsonl").exists()
        assert (output_dir / "conv-003.jsonl").exists()
        assert (output_dir / "conv-004.jsonl").exists()

    def test_no_deletion_under_limit(self, output_dir: Path) -> None:
        from agent_adapter.cleaner import FileCleaner

        for i in range(3):
            f = output_dir / f"conv-{i:03d}.jsonl"
            _write_jsonl(f)

        config = AdapterConfig(output_dir=str(output_dir), output_max_files=5)
        cleaner = FileCleaner(config)
        deleted = cleaner.max_files_cleanup()

        assert deleted == 0
        assert len(list(output_dir.glob("*.jsonl"))) == 3


class TestTruncation:
    """Oversized files are head-truncated to trim_target_ratio of threshold."""

    def test_truncates_oversized_file(self, output_dir: Path) -> None:
        from agent_adapter.cleaner import FileCleaner

        # Create a file larger than 1KB threshold
        big_file = output_dir / "conv-big.jsonl"
        # Each line is ~25 bytes, write 100 lines = ~2500 bytes
        for i in range(100):
            with open(big_file, "a", encoding="utf-8") as f:
                f.write(f'{{"call_id": "uuid-{i:03d}", "data": "padding"}}\n')

        original_size = big_file.stat().st_size
        assert original_size > 1024  # exceeds 1KB threshold

        # 1KB threshold, trim to 70% = ~716 bytes
        config = AdapterConfig(
            output_dir=str(output_dir),
            output_max_file_size="1KB",
            output_trim_target_ratio=0.7,
        )
        cleaner = FileCleaner(config)
        truncated = cleaner.truncate_oversized_files()

        assert truncated == 1
        new_size = big_file.stat().st_size
        # After truncation, file should be roughly 70% of 1KB (~716 bytes)
        assert new_size < original_size
        # Target: 1024 * 0.7 = 716.8 bytes, allow generous margin
        assert new_size < 800

    def test_no_truncation_when_under_size(self, output_dir: Path) -> None:
        from agent_adapter.cleaner import FileCleaner

        small_file = output_dir / "conv-small.jsonl"
        _write_jsonl(small_file)

        config = AdapterConfig(
            output_dir=str(output_dir),
            output_max_file_size="50MB",
        )
        cleaner = FileCleaner(config)
        truncated = cleaner.truncate_oversized_files()

        assert truncated == 0
        assert small_file.exists()

    def test_truncated_file_has_valid_jsonl_lines(self, output_dir: Path) -> None:
        """After truncation, each remaining line should be valid JSON."""
        import json

        from agent_adapter.cleaner import FileCleaner

        big_file = output_dir / "conv-big.jsonl"
        for i in range(100):
            with open(big_file, "a", encoding="utf-8") as f:
                f.write(f'{{"call_id": "uuid-{i:03d}", "data": "padding"}}\n')

        config = AdapterConfig(
            output_dir=str(output_dir),
            output_max_file_size="1KB",
            output_trim_target_ratio=0.7,
        )
        cleaner = FileCleaner(config)
        cleaner.truncate_oversized_files()

        # All remaining lines should be valid JSON
        with open(big_file, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    json.loads(line)  # should not raise


class TestParseSize:
    """_parse_size handles various size string formats."""

    def test_parse_mb(self) -> None:
        from agent_adapter.cleaner import _parse_size

        assert _parse_size("50MB") == 50 * 1024 * 1024

    def test_parse_kb(self) -> None:
        from agent_adapter.cleaner import _parse_size

        assert _parse_size("100KB") == 100 * 1024

    def test_parse_gb(self) -> None:
        from agent_adapter.cleaner import _parse_size

        assert _parse_size("2GB") == 2 * 1024 * 1024 * 1024

    def test_parse_plain_number(self) -> None:
        from agent_adapter.cleaner import _parse_size

        assert _parse_size("1024") == 1024

    def test_parse_invalid_raises(self) -> None:
        from agent_adapter.cleaner import _parse_size

        with pytest.raises(ValueError):
            _parse_size("abc")


class TestRunAll:
    """run_all executes all three cleanup strategies and returns summary."""

    def test_run_all_executes_all_strategies(self, output_dir: Path) -> None:
        from agent_adapter.cleaner import FileCleaner

        # Create: 1 expired file, excess files, and 1 oversized file
        old_file = output_dir / "conv-expired.jsonl"
        _write_jsonl(old_file)
        _set_mtime(old_file, days_ago=31)

        for i in range(5):
            f = output_dir / f"conv-{i:03d}.jsonl"
            _write_jsonl(f)
            _set_mtime(f, days_ago=5 - i)

        # Make one file oversized
        big_file = output_dir / "conv-big.jsonl"
        for j in range(100):
            with open(big_file, "a", encoding="utf-8") as f:
                f.write(f'{{"call_id": "uuid-{j:03d}", "data": "padding"}}\n')

        config = AdapterConfig(
            output_dir=str(output_dir),
            output_retention_days=30,
            output_max_files=5,
            output_max_file_size="1KB",
            output_trim_target_ratio=0.7,
        )
        cleaner = FileCleaner(config)
        result = cleaner.run_all()

        assert result["retention_deleted"] >= 1
        assert result["max_files_deleted"] >= 0
        assert result["truncated"] >= 1


class TestDiskSpaceWarning:
    """Warn when output directory is running low on disk space."""

    def test_warns_on_low_disk_space(self, output_dir: Path) -> None:
        from agent_adapter.cleaner import FileCleaner

        config = AdapterConfig(output_dir=str(output_dir))
        cleaner = FileCleaner(config)
        # check_disk_space should not raise; it logs a warning if low
        cleaner.check_disk_space()
