"""START/END pairing state machine for matching call lifecycle records."""

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Literal

import structlog

from agent_adapter.parser import ParsedTagRecord

logger = structlog.get_logger(__name__)

# Timestamp format used in EDPAgent logs
_TS_FORMAT = "%Y-%m-%d %H:%M:%S.%f"


@dataclass(frozen=True)
class PairedRecord:
    """A complete or incomplete call record produced by the pairing engine."""

    call_id: str
    trace_id: str
    agent_id: str
    conversation_id: str
    model: str | None
    type: str | None
    start_time: str | None
    end_time: str | None
    duration_ms: int | None
    input: dict | None
    output: dict | None
    complete: bool
    incomplete_reason: Literal["pair_timeout", "pid_switch", "orphan_end"] | None


class PairingEngine:
    """State machine that pairs START and END tag records by call_id.

    Call feed() with each parsed record. START records are cached in
    pending_starts until a matching END arrives. Call check_timeouts()
    periodically to emit incomplete records for timed-out STARTs.
    Call mark_pid_switch() when a PID change is detected.

    Timeout is measured from when the START was *received* by feed(),
    not from the log timestamp. This ensures historical log replay
    doesn't immediately trigger timeouts.
    """

    def __init__(self, pair_timeout_seconds: int = 300) -> None:
        self._pair_timeout_seconds = pair_timeout_seconds
        # Maps call_id → (record, arrival_time as UTC datetime)
        self._pending_starts: dict[str, tuple[ParsedTagRecord, datetime]] = {}

    def feed(self, record: ParsedTagRecord) -> list[PairedRecord]:
        """Feed a parsed record into the pairing engine.

        Returns any PairedRecords produced by this input (0 or 1).
        """
        if record.tag.endswith("_START"):
            return self._handle_start(record)
        if record.tag.endswith("_END"):
            return self._handle_end(record)
        return []

    def check_timeouts(self) -> list[PairedRecord]:
        """Check all pending STARTs for timeout.

        Uses real wall-clock time to measure how long a START has been
        waiting since it was fed into the engine. Returns incomplete
        records for any START that has waited longer than
        pair_timeout_seconds.
        """
        now = datetime.now(timezone.utc)
        timed_out: list[PairedRecord] = []
        expired_ids: list[str] = []

        for call_id, (start_record, arrival_time) in self._pending_starts.items():
            elapsed = (now - arrival_time).total_seconds()
            if elapsed > self._pair_timeout_seconds:
                timed_out.append(_make_incomplete(
                    start_record, "pair_timeout",
                ))
                expired_ids.append(call_id)

        for call_id in expired_ids:
            del self._pending_starts[call_id]

        return timed_out

    def mark_pid_switch(self) -> list[PairedRecord]:
        """Mark all pending STARTs as incomplete due to PID switch.

        Returns all flushed records and clears the pending cache.
        """
        results: list[PairedRecord] = []
        for call_id, (start_record, _arrival_time) in self._pending_starts.items():
            results.append(_make_incomplete(start_record, "pid_switch"))
        self._pending_starts.clear()
        return results

    def _handle_start(self, record: ParsedTagRecord) -> list[PairedRecord]:
        call_id = _extract_call_id(record)
        if call_id:
            self._pending_starts[call_id] = (record, datetime.now(timezone.utc))
        return []

    def _handle_end(self, record: ParsedTagRecord) -> list[PairedRecord]:
        call_id = _extract_call_id(record)
        if not call_id:
            return []

        entry = self._pending_starts.pop(call_id, None)
        if entry is None:
            # Orphan END — no matching START
            return [_make_orphan_end(record)]

        start_record = entry[0]
        # Merge START + END into a complete record
        return [_merge_pair(start_record, record)]


def _extract_call_id(record: ParsedTagRecord) -> str | None:
    """Extract the call id from a record's message field."""
    msg = record.message
    if isinstance(msg, dict) and "id" in msg:
        return str(msg["id"])
    return None


def _parse_ts(ts: str) -> datetime:
    """Parse a timestamp string into a datetime object."""
    return datetime.strptime(ts, _TS_FORMAT)


def _merge_pair(start: ParsedTagRecord, end: ParsedTagRecord) -> PairedRecord:
    """Merge a START and END record into a complete PairedRecord."""
    start_msg = start.message if isinstance(start.message, dict) else {}
    end_msg = end.message if isinstance(end.message, dict) else {}

    start_time = start.time
    end_time = end.time
    duration_ms: int | None = None
    if start_time and end_time:
        try:
            delta = _parse_ts(end_time) - _parse_ts(start_time)
            duration_ms = int(delta.total_seconds() * 1000)
        except (ValueError, TypeError):
            pass

    return PairedRecord(
        call_id=_extract_call_id(start) or "",
        trace_id=start.trace_id,
        agent_id=start.agent_id,
        conversation_id=start.conversation_id,
        model=start_msg.get("model"),
        type=start_msg.get("type"),
        start_time=start_time,
        end_time=end_time,
        duration_ms=duration_ms,
        input=start_msg.get("input"),
        output=end_msg.get("output"),
        complete=True,
        incomplete_reason=None,
    )


def _make_incomplete(
    start: ParsedTagRecord,
    reason: Literal["pair_timeout", "pid_switch"],
) -> PairedRecord:
    """Create an incomplete PairedRecord from a pending START."""
    call_id = _extract_call_id(start) or ""
    logger.info(
        "incomplete_record",
        call_id=call_id,
        incomplete_reason=reason,
        conversation_id=start.conversation_id,
    )
    start_msg = start.message if isinstance(start.message, dict) else {}
    return PairedRecord(
        call_id=call_id,
        trace_id=start.trace_id,
        agent_id=start.agent_id,
        conversation_id=start.conversation_id,
        model=start_msg.get("model"),
        type=start_msg.get("type"),
        start_time=start.time,
        end_time=None,
        duration_ms=None,
        input=start_msg.get("input"),
        output=None,
        complete=False,
        incomplete_reason=reason,
    )


def _make_orphan_end(end: ParsedTagRecord) -> PairedRecord:
    """Create an incomplete PairedRecord from an orphan END."""
    call_id = _extract_call_id(end) or ""
    logger.info(
        "incomplete_record",
        call_id=call_id,
        incomplete_reason="orphan_end",
        conversation_id=end.conversation_id,
    )
    end_msg = end.message if isinstance(end.message, dict) else {}
    return PairedRecord(
        call_id=call_id,
        trace_id=end.trace_id,
        agent_id=end.agent_id,
        conversation_id=end.conversation_id,
        model=end_msg.get("model"),
        type=end_msg.get("type"),
        start_time=None,
        end_time=end.time,
        duration_ms=None,
        input=None,
        output=end_msg.get("output"),
        complete=False,
        incomplete_reason="orphan_end",
    )
