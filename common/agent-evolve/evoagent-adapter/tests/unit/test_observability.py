"""Unit tests for observability improvements — structlog warnings."""

import json
from pathlib import Path

import pytest

from agent_adapter.config import AdapterConfig


class TestOffsetIntegrityWarning:
    """offsets.json verification failure produces structlog warning."""

    def test_file_shrunk_produces_warning(self, tmp_path: Path, capsys) -> None:
        from agent_adapter.offset import OffsetManager, FileOffset

        offset_file = tmp_path / "offsets.json"
        mgr = OffsetManager(offset_file=offset_file)

        log_file = tmp_path / "process_12345.log"
        log_file.write_text("x" * 1000, encoding="utf-8")
        mgr.update("process_12345.log", FileOffset(
            offset=900, file_size=1000,
            first_line_hash="abc12345", completed=False,
        ))

        log_file.write_text("small", encoding="utf-8")

        needs_reset, reason = mgr.check_file_integrity("process_12345.log", log_file)
        assert needs_reset is True
        assert "shrunk" in reason

        output = capsys.readouterr().out
        assert "offset_integrity_failure" in output
        assert "process_12345.log" in output

    def test_hash_mismatch_produces_warning(self, tmp_path: Path, capsys) -> None:
        from agent_adapter.offset import OffsetManager, FileOffset

        offset_file = tmp_path / "offsets.json"
        mgr = OffsetManager(offset_file=offset_file)

        log_file = tmp_path / "process_12345.log"
        log_file.write_text("original content\nmore data\n", encoding="utf-8")
        mgr.update("process_12345.log", FileOffset(
            offset=10, file_size=30,
            first_line_hash="wrong_hash", completed=False,
        ))

        log_file.write_text("replaced content\nmore data\n", encoding="utf-8")

        needs_reset, reason = mgr.check_file_integrity("process_12345.log", log_file)
        assert needs_reset is True
        assert "hash_mismatch" in reason

        output = capsys.readouterr().out
        assert "offset_integrity_failure" in output


class TestParseFailureRateWarning:
    """High parse failure rate produces structlog warning."""

    def test_high_failure_rate_produces_warning(self, capsys) -> None:
        from agent_adapter.parser import parse_log_lines

        valid = "\x01".join([
            "2026-06-10 14:30:15.000", "INFO", "src:1", "trace-x",
            "agent-y", "conv-z", "TAG_LLM_CALL_START", "0",
            json.dumps({"id": "uuid-001"}),
        ])
        non_matching = "\x01".join([
            "2026-06-10 14:30:16.000", "INFO", "src:1", "trace-x",
            "agent-y", "conv-z", "SOME_OTHER_TAG", "0", "ignored",
        ])

        lines = [valid] + [non_matching] * 5
        records, _, stats = parse_log_lines(lines, {"TAG_LLM_CALL_START", "TAG_LLM_CALL_END"})

        assert len(records) == 1
        assert stats.total == 6
        assert stats.failed == 5
        assert stats.failure_rate > 0.5

        output = capsys.readouterr().out
        assert "parse_failure_rate_high" in output


class TestIncompleteRecordLogging:
    """Incomplete records produce structlog info with diagnostic details."""

    def test_pair_timeout_produces_incomplete_log(self, capsys) -> None:
        from datetime import datetime, timedelta, timezone

        from agent_adapter.pairing import PairingEngine
        from agent_adapter.parser import ParsedTagRecord

        engine = PairingEngine(pair_timeout_seconds=5)
        start = ParsedTagRecord(
            time="2026-06-10 14:30:15.000",
            level="INFO",
            source="src:1",
            trace_id="trace-x",
            agent_id="agent-y",
            conversation_id="conv-001",
            tag="TAG_LLM_CALL_START",
            cost="0",
            message={"id": "uuid-timeout"},
        )
        engine.feed(start)

        call_id = list(engine._pending_starts.keys())[0]
        record, _arrival = engine._pending_starts[call_id]
        engine._pending_starts[call_id] = (
            record,
            datetime.now(timezone.utc) - timedelta(seconds=10),
        )

        results = engine.check_timeouts()
        assert len(results) == 1
        assert results[0].incomplete_reason == "pair_timeout"

        output = capsys.readouterr().out
        assert "incomplete_record" in output
        assert "uuid-timeout" in output
        assert "pair_timeout" in output

    def test_pid_switch_produces_incomplete_log(self, capsys) -> None:
        from agent_adapter.pairing import PairingEngine
        from agent_adapter.parser import ParsedTagRecord

        engine = PairingEngine(pair_timeout_seconds=300)
        start = ParsedTagRecord(
            time="2026-06-10 14:30:15.000",
            level="INFO",
            source="src:1",
            trace_id="trace-x",
            agent_id="agent-y",
            conversation_id="conv-001",
            tag="TAG_LLM_CALL_START",
            cost="0",
            message={"id": "uuid-pid"},
        )
        engine.feed(start)

        results = engine.mark_pid_switch()
        assert len(results) == 1

        output = capsys.readouterr().out
        assert "incomplete_record" in output
        assert "pid_switch" in output
