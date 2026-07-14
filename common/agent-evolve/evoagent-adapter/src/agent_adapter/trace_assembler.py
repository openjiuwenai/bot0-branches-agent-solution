"""Trace assembly — Langfuse-style observation merging and archive writing.

Replaces the flat PairedRecord pairing with a merge-by-id strategy that
produces trace/observation records matching the Langfuse data model.

Key concepts:
- TAG_HTTP_REQUEST → TRACE record (top-level, id == trace_id)
- TAG_LLM_CALL     → GENERATION observation
- TAG_TOOL_EXECUTE  → TOOL observation
- TAG_SKILL_EXECUTE → SKILL observation
- TAG_VERSATILE     → TOOL observation
- TAG_PLANNING_DECISION → SPAN observation (singleton, no pairing)

START and END events with the same `id` are merged using a fill-missing
strategy: fields absent in the target are added from the source; fields
already present in the target are preserved (START wins for overlaps).
"""

import json
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Literal

import structlog

from agent_adapter.parser import ParsedTagRecord

logger = structlog.get_logger(__name__)


# ── Observation type classification ──────────────────────────────────────────


class ObservationType(str, Enum):
    """Langfuse-style observation types."""

    TRACE = "TRACE"
    GENERATION = "GENERATION"
    TOOL = "TOOL"
    SKILL = "SKILL"
    SPAN = "SPAN"


# Map from base tag (without _START/_END suffix) to observation type
_TAG_TYPE_MAP: dict[str, ObservationType] = {
    "TAG_HTTP_REQUEST": ObservationType.TRACE,
    "TAG_LLM_CALL": ObservationType.GENERATION,
    "TAG_TOOL_EXECUTE": ObservationType.TOOL,
    "TAG_SKILL_EXECUTE": ObservationType.SKILL,
    "TAG_VERSATILE": ObservationType.TOOL,
    "TAG_PLANNING_DECISION": ObservationType.SPAN,
}

# Singleton tags that have no _START/_END pairing — emitted immediately
_SINGLETON_TAGS: set[str] = {"TAG_PLANNING_DECISION"}

# Fields that use recursive dict-fill / list-append-dedup during merge
_MERGE_FIELDS: set[str] = {"metadata", "tags"}


def classify_tag(tag: str) -> tuple[ObservationType, str, bool, bool]:
    """Classify a tag string into its observation type and attributes.

    Args:
        tag: Full tag string, e.g. "TAG_LLM_CALL_START"

    Returns:
        (observation_type, base_tag, is_start, is_paired)
        - observation_type: The Langfuse-style type
        - base_tag: Tag without _START/_END suffix
        - is_start: True for _START suffix, False for _END
        - is_paired: True if tag participates in START/END pairing
    """
    is_start = tag.endswith("_START")
    # Strip _START or _END suffix to get the base tag
    if is_start:
        base_tag = tag[: -len("_START")]
    elif tag.endswith("_END"):
        base_tag = tag[: -len("_END")]
    else:
        base_tag = tag

    obs_type = _TAG_TYPE_MAP.get(base_tag, ObservationType.SPAN)
    is_paired = base_tag not in _SINGLETON_TAGS

    return obs_type, base_tag, is_start, is_paired


# ── Merge logic (fill-missing strategy) ──────────────────────────────────────


def _merge_list_unique_keep_existing(
    dst_list: list, src_list: list
) -> list:
    """Append items from src_list that are not already in dst_list."""
    for item in src_list:
        if item not in dst_list:
            dst_list.append(item)
    return dst_list


def _merge_json_value_inplace(dst_val: object, src_val: object) -> object:
    """Recursively merge dict/list values; other types keep dst unchanged."""
    if isinstance(dst_val, dict) and isinstance(src_val, dict):
        for k, v in src_val.items():
            if k not in dst_val:
                dst_val[k] = v
                continue
            if isinstance(dst_val.get(k), dict) and isinstance(v, dict):
                _merge_json_value_inplace(dst_val[k], v)
            elif isinstance(dst_val.get(k), list) and isinstance(v, list):
                _merge_list_unique_keep_existing(dst_val[k], v)
        return dst_val

    if isinstance(dst_val, list) and isinstance(src_val, list):
        return _merge_list_unique_keep_existing(dst_val, src_list=src_val)

    return dst_val


def merge_dicts_fill_missing(target: dict, source: dict) -> dict:
    """Merge source into target using fill-missing strategy.

    For each key in source:
    - If key not in target → add it
    - If key in _MERGE_FIELDS (metadata, tags) → recursive dict fill / list dedup
    - Otherwise → skip (target's value preserved)

    This matches the reference merge_json_by_id() from 1_log_analyze.py.
    """
    for k, v in source.items():
        if k == "id":
            continue  # Never overwrite the id
        if k not in target:
            target[k] = v
            continue
        if k in _MERGE_FIELDS:
            if isinstance(target.get(k), dict) and isinstance(v, dict):
                _merge_json_value_inplace(target[k], v)
            elif isinstance(target.get(k), list) and isinstance(v, list):
                _merge_list_unique_keep_existing(target[k], v)
            # Otherwise keep target's value
            continue
        # Key already in target and not a merge field → skip (target wins)
    return target


# ── TraceAssembler (replaces PairingEngine) ──────────────────────────────────


class TraceAssembler:
    """State machine that assembles trace archive records from parsed tag records.

    Core logic:
    - For paired tags (_START/_END): cache START, merge with END by id
    - For singleton tags: emit immediately
    - TAG_HTTP_REQUEST: produces TRACE records (id == trace_id)
    - Other tags: produce observation records (carry trace_id)
    - Inject context: trace_id, session_id from log line fixed fields
    """

    def __init__(self, pair_timeout_seconds: int = 300) -> None:
        self._pair_timeout_seconds = pair_timeout_seconds
        # Maps record_id → (merged_dict, arrival_time as UTC datetime, conversation_id)
        self._pending: dict[str, tuple[dict, datetime, str]] = {}

    @property
    def pending_count(self) -> int:
        """Number of START records awaiting their matching END."""
        return len(self._pending)

    def feed(self, record: ParsedTagRecord) -> list[dict]:
        """Feed a parsed record into the assembler.

        Returns list of completed merged dicts (0 or 1 for paired tags,
        1 for singleton tags).
        """
        obs_type, base_tag, is_start, is_paired = classify_tag(record.tag)

        # Build the record dict from the message payload
        message = record.message if isinstance(record.message, dict) else {}
        merged = dict(message)  # shallow copy of the message payload

        # Inject context based on observation type
        if obs_type == ObservationType.TRACE:
            # TRACE records (TAG_HTTP_REQUEST_START/END):
            # - Use trace_id as the record id (always override)
            # - Do NOT inject "type" — TRACE records carry no type field
            # - Do NOT inject start_time/end_time — TRACE records use
            #   their original "timestamp" field from the payload
            # - Preserve the original "timestamp" from the payload
            merged["id"] = record.trace_id
            merged["session_id"] = record.conversation_id
            # Ensure timestamp exists (from payload or log line time)
            merged.setdefault("timestamp", record.time)
        else:
            # Observation records (GENERATION, TOOL, SKILL, SPAN):
            # - Inject the observation type
            # - Carry trace_id as a foreign key
            # - Use start_time/end_time for temporal context
            # - Remove redundant timestamp if start_time or end_time exists
            merged["type"] = obs_type.value
            merged.setdefault("trace_id", record.trace_id)
            merged["session_id"] = record.conversation_id

            # Inject time from log line if not in payload
            if is_start:
                merged.setdefault("start_time", record.time)
            else:
                merged.setdefault("end_time", record.time)

            # Remove redundant timestamp: if start_time or end_time is
            # present, timestamp is unnecessary for temporal context.
            if ("start_time" in merged or "end_time" in merged) and "timestamp" in merged:
                del merged["timestamp"]

        # Singleton tags: emit immediately
        if not is_paired:
            return [merged]

        # Paired tags: START/END matching by record id
        # For TRACE records, the id is the trace_id; for observations, it's from the message.
        # END events for TRACE records may not have "id" in their payload,
        # so we use the log line's trace_id as the lookup key.
        if obs_type == ObservationType.TRACE:
            record_id = record.trace_id
        else:
            record_id = merged.get("id", "")
        if not record_id:
            return []

        if is_start:
            # Cache the START record
            self._pending[record_id] = (merged, datetime.now(timezone.utc), record.conversation_id)
            return []

        # END: merge with cached START
        entry = self._pending.pop(record_id, None)
        if entry is None:
            # Orphan END — no matching START
            merged["_incomplete"] = True
            merged["_incomplete_reason"] = "orphan_end"
            logger.info(
                "incomplete_record",
                call_id=record_id,
                incomplete_reason="orphan_end",
                conversation_id=record.conversation_id,
            )
            return [merged]

        start_dict = entry[0]
        # Merge START + END: START fields are preserved, END fields fill in
        merge_dicts_fill_missing(start_dict, merged)
        return [start_dict]

    def check_timeouts(self) -> list[dict]:
        """Check all pending STARTs for timeout.

        Uses wall-clock time since feed() was called.
        Returns incomplete records for timed-out STARTs.
        """
        now = datetime.now(timezone.utc)
        timed_out: list[dict] = []
        expired_ids: list[str] = []

        for record_id, (start_dict, arrival_time, conversation_id) in self._pending.items():
            elapsed = (now - arrival_time).total_seconds()
            if elapsed > self._pair_timeout_seconds:
                start_dict["_incomplete"] = True
                start_dict["_incomplete_reason"] = "pair_timeout"
                timed_out.append(start_dict)
                expired_ids.append(record_id)
                logger.info(
                    "incomplete_record",
                    call_id=record_id,
                    incomplete_reason="pair_timeout",
                    conversation_id=conversation_id,
                )

        for record_id in expired_ids:
            del self._pending[record_id]

        return timed_out

    def mark_pid_switch(self) -> list[dict]:
        """Mark all pending STARTs as incomplete due to PID switch.

        Returns all flushed records and clears the pending cache.
        """
        results: list[dict] = []
        for record_id, (start_dict, _arrival_time, conversation_id) in self._pending.items():
            start_dict["_incomplete"] = True
            start_dict["_incomplete_reason"] = "pid_switch"
            results.append(start_dict)
            logger.info(
                "incomplete_record",
                call_id=record_id,
                incomplete_reason="pid_switch",
                conversation_id=conversation_id,
            )
        self._pending.clear()
        return results


# ── TraceArchiveWriter (replaces JsonlWriter) ────────────────────────────────


class TraceArchiveWriter:
    """Writes trace archive files — one JSONL file per conversation.

    File naming: {conversation_id}.jsonl
    Content: one JSON object per line (JSONL format).

    The writer accumulates records in memory per conversation and appends
    them as JSONL lines when finalized.  If a file already exists for a
    conversation, new lines are appended so that all data for the same
    conversation stays in one file.
    """

    def __init__(self, output_dir: Path) -> None:
        self._output_dir = output_dir
        self._output_dir.mkdir(parents=True, exist_ok=True)
        # Maps conversation_id → list[dict] of merged records
        self._conversations: dict[str, list[dict]] = {}

    def write(self, merged_record: dict) -> None:
        """Add a merged record to its conversation's buffer."""
        # Use session_id (injected by TraceAssembler from log line)
        # as the grouping key. Fallback to trace_id.
        conversation_id = (
            merged_record.get("session_id")
            or merged_record.get("trace_id", "unknown")
        )
        if conversation_id not in self._conversations:
            self._conversations[conversation_id] = []

        self._conversations[conversation_id].append(merged_record)

    def finalize_conversation(self, conversation_id: str) -> Path | None:
        """Append a conversation's accumulated records to a JSONL file.

        Each record is written as a single JSON line.  If the file already
        exists, new lines are appended so that all data for the same
        conversation stays in one file.

        Returns the path of the written file, or None if no records.
        """
        records = self._conversations.pop(conversation_id, None)
        if not records:
            return None

        file_path = self._output_dir / f"{conversation_id}.jsonl"

        with open(file_path, "a", encoding="utf-8") as f:
            for record in records:
                f.write(json.dumps(record, ensure_ascii=False) + "\n")

        logger.info(
            "trace_archive_written",
            conversation_id=conversation_id,
            record_count=len(records),
            file_path=str(file_path),
        )
        return file_path

    def finalize_all(self) -> list[Path]:
        """Write all buffered conversations to files."""
        paths: list[Path] = []
        for conversation_id in list(self._conversations.keys()):
            path = self.finalize_conversation(conversation_id)
            if path:
                paths.append(path)
        return paths

    def list_conversations(self) -> list[str]:
        """List all conversation IDs that have archive files."""
        if not self._output_dir.exists():
            return []
        return [
            f.stem
            for f in self._output_dir.glob("*.jsonl")
            if f.is_file()
        ]
