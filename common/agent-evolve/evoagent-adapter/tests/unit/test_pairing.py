"""Unit tests for the START/END pairing state machine."""

import json

import pytest

from agent_adapter.pairing import PairedRecord, PairingEngine
from agent_adapter.parser import ParsedTagRecord

# ── Helpers ──

def make_start_record(
    call_id: str = "uuid-001",
    conversation_id: str = "conv-999",
    trace_id: str = "trace-abc",
    agent_id: str = "agent-001",
    start_time: str = "2026-06-09 14:30:15.123",
    model: str = "model-sample",
) -> ParsedTagRecord:
    message = {
        "id": call_id,
        "type": "GENERATION",
        "model": model,
        "input": {"messages": [{"role": "user", "content": "hello"}]},
    }
    return ParsedTagRecord(
        time=start_time,
        level="INFO",
        source="log_rail:before_model_call:78",
        trace_id=trace_id,
        agent_id=agent_id,
        conversation_id=conversation_id,
        tag="TAG_LLM_CALL_START",
        cost="0",
        message=message,
    )


def make_end_record(
    call_id: str = "uuid-001",
    conversation_id: str = "conv-999",
    trace_id: str = "trace-abc",
    agent_id: str = "agent-001",
    end_time: str = "2026-06-09 14:30:17.456",
    output: dict | None = None,
) -> ParsedTagRecord:
    if output is None:
        output = {"text": "hi"}
    message = {
        "id": call_id,
        "end_time": end_time,
        "output": output,
        "total_cost": 2333,
    }
    return ParsedTagRecord(
        time=end_time,
        level="INFO",
        source="log_rail:after_model_call:78",
        trace_id=trace_id,
        agent_id=agent_id,
        conversation_id=conversation_id,
        tag="TAG_LLM_CALL_END",
        cost="2333",
        message=message,
    )


class TestNormalPairing:
    """START→END normal pairing produces a complete record."""

    def test_start_then_end_produces_complete_record(self):
        engine = PairingEngine(pair_timeout_seconds=300)
        start = make_start_record()
        end = make_end_record()

        results = engine.feed(start)
        assert results == []  # START is cached, no output yet

        results = engine.feed(end)
        assert len(results) == 1
        record = results[0]
        assert record.complete is True
        assert record.call_id == "uuid-001"
        assert record.conversation_id == "conv-999"
        assert record.model == "model-sample"

    def test_duration_ms_calculated_correctly(self):
        engine = PairingEngine(pair_timeout_seconds=300)
        engine.feed(make_start_record(start_time="2026-06-09 14:30:15.000"))
        results = engine.feed(make_end_record(end_time="2026-06-09 14:30:17.456"))
        assert len(results) == 1
        assert results[0].duration_ms == 2456

    def test_input_from_start_output_from_end(self):
        engine = PairingEngine(pair_timeout_seconds=300)
        engine.feed(make_start_record())
        results = engine.feed(make_end_record(output={"text": "response"}))
        assert len(results) == 1
        record = results[0]
        assert record.input == {"messages": [{"role": "user", "content": "hello"}]}
        assert record.output == {"text": "response"}

    def test_trace_id_and_agent_id_carried_over(self):
        engine = PairingEngine(pair_timeout_seconds=300)
        engine.feed(make_start_record(trace_id="trace-xyz", agent_id="agent-007"))
        results = engine.feed(make_end_record(trace_id="trace-xyz", agent_id="agent-007"))
        assert results[0].trace_id == "trace-xyz"
        assert results[0].agent_id == "agent-007"


class TestPairTimeout:
    """START waiting too long for END produces an incomplete record."""

    def test_timeout_produces_incomplete_record(self):
        from datetime import datetime, timedelta, timezone

        engine = PairingEngine(pair_timeout_seconds=5)
        engine.feed(make_start_record(start_time="2026-06-09 14:30:15.000"))

        # Simulate the START having arrived 10 seconds ago by patching
        # the arrival time in the pending_starts dict
        call_id = list(engine._pending_starts.keys())[0]
        record, _arrival = engine._pending_starts[call_id]
        engine._pending_starts[call_id] = (
            record,
            datetime.now(timezone.utc) - timedelta(seconds=10),
        )

        results = engine.check_timeouts()
        assert len(results) == 1
        record = results[0]
        assert record.complete is False
        assert record.incomplete_reason == "pair_timeout"
        assert record.call_id == "uuid-001"
        assert record.end_time is None
        assert record.duration_ms is None
        assert record.output is None

    def test_no_timeout_before_threshold(self):
        engine = PairingEngine(pair_timeout_seconds=300)
        engine.feed(make_start_record(start_time="2026-06-09 14:30:15.000"))

        # Just fed — should not timeout yet
        results = engine.check_timeouts()
        assert results == []


class TestOrphanEnd:
    """END without a matching START produces an incomplete record."""

    def test_orphan_end_produces_incomplete_record(self):
        engine = PairingEngine(pair_timeout_seconds=300)
        end = make_end_record()
        results = engine.feed(end)
        assert len(results) == 1
        record = results[0]
        assert record.complete is False
        assert record.incomplete_reason == "orphan_end"
        assert record.call_id == "uuid-001"


class TestPidSwitch:
    """PID switch marks all pending STARTs as incomplete."""

    def test_pid_switch_flushes_all_pending(self):
        engine = PairingEngine(pair_timeout_seconds=300)
        engine.feed(make_start_record(call_id="uuid-001"))
        engine.feed(make_start_record(call_id="uuid-002"))

        results = engine.mark_pid_switch()
        assert len(results) == 2
        assert all(r.complete is False for r in results)
        assert all(r.incomplete_reason == "pid_switch" for r in results)
        call_ids = {r.call_id for r in results}
        assert call_ids == {"uuid-001", "uuid-002"}


class TestMultipleConcurrentStarts:
    """Multiple START records with different call_ids pair independently."""

    def test_independent_pairing(self):
        engine = PairingEngine(pair_timeout_seconds=300)
        engine.feed(make_start_record(call_id="uuid-001"))
        engine.feed(make_start_record(call_id="uuid-002"))

        # END for uuid-002 arrives first
        results = engine.feed(make_end_record(call_id="uuid-002"))
        assert len(results) == 1
        assert results[0].call_id == "uuid-002"
        assert results[0].complete is True

        # END for uuid-001 arrives later
        results = engine.feed(make_end_record(call_id="uuid-001"))
        assert len(results) == 1
        assert results[0].call_id == "uuid-001"
        assert results[0].complete is True
