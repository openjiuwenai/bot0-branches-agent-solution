"""Pipeline — three-phase integration of reader, parser, assembler, and archive writer."""

import asyncio
import time
from pathlib import Path

import structlog

from agent_adapter.config import AdapterConfig, AgentEntryConfig
from agent_adapter.offset import FileOffset
from agent_adapter.parser import parse_log_lines
from agent_adapter.reader import LogReader
from agent_adapter.trace_assembler import TraceArchiveWriter, TraceAssembler

logger = structlog.get_logger(__name__)


class Pipeline:
    """Three-phase pipeline: read → parse → assemble → write.

    Phase 1: LogReader reads new lines from log files
    Phase 2: Parser extracts structured records with TAG filtering
    Phase 3: TraceAssembler merges START/END into trace/observation records,
             TraceArchiveWriter outputs JSON archives per conversation
    """

    def __init__(self, config: AdapterConfig, agent_name: str = "default") -> None:
        self._agent_name = agent_name
        self._config = config
        self._log_dir = Path(config.log_dir)
        # Ensure offset_file is absolute; resolve relative paths against adapter root
        offset_path = Path(config.offset_file)
        if not offset_path.is_absolute():
            from agent_adapter.config import _ADAPTER_ROOT
            offset_path = (_ADAPTER_ROOT / offset_path).resolve()
        self._offset_file = offset_path
        self._match_tags = set(config.match_tags)
        self._pair_timeout = config.pair_timeout
        self._start_from = config.start_from

        # Warn if log_dir does not exist — a common misconfiguration
        if not self._log_dir.exists():
            logger.warning(
                "log_dir_not_found",
                log_dir=str(self._log_dir),
                hint="Check that log_dir points to an existing directory with EDPAgent logs",
            )

        self._reader = LogReader(
            log_dir=self._log_dir,
            log_pattern=config.log_pattern,
            start_from=self._start_from,
            offset_file=self._offset_file,
        )
        self._assembler = TraceAssembler(pair_timeout_seconds=self._pair_timeout)
        self._writer = TraceArchiveWriter(output_dir=Path(config.output_dir))
        self._lock = asyncio.Lock()

        # Track which PID file is currently active
        self._active_filename: str | None = None

        # Track start time for uptime calculation
        self._started_at: float = time.monotonic()

        # Track last read time
        self._last_read_time: str | None = None

    @property
    def agent_name(self) -> str:
        """Name of the agent this pipeline collects logs for."""
        return self._agent_name

    async def poll(self) -> list[dict]:
        """Execute one poll cycle: read → parse → assemble → write.

        Thread-safe via asyncio.Lock.
        Returns list of merged trace/observation dicts produced in this cycle.
        """
        async with self._lock:
            return self._do_poll()

    def poll_sync(self) -> list[dict]:
        """Synchronous version of poll (for testing without asyncio)."""
        return self._do_poll()

    def get_status(self) -> dict:
        """Return current adapter runtime status for the /api/v1/status endpoint."""
        from datetime import datetime, timezone

        # Get offset for the active file (reuse reader's OffsetManager)
        offset = 0
        if self._active_filename:
            entry = self._reader.offset_mgr.get(self._active_filename)
            if entry is not None:
                offset = entry.offset

        # Count output directory files
        output_path = Path(self._config.output_dir)
        output_dir_files = 0
        if output_path.exists():
            output_dir_files = sum(1 for f in output_path.glob("*.jsonl") if f.is_file())

        uptime = time.monotonic() - self._started_at

        return {
            "agent_name": self._agent_name,
            "active_file": self._active_filename,
            "offset": offset,
            "pending_starts_count": self._assembler.pending_count,
            "last_read_time": self._last_read_time,
            "output_dir_files": output_dir_files,
            "uptime_seconds": round(uptime, 1),
        }

    def _do_poll(self) -> list[dict]:
        """Internal poll implementation."""
        from datetime import datetime, timezone

        all_records: list[dict] = []
        pid_switched = False

        # Check for PID switch before reading
        current_files = self._reader.discover_files()
        if current_files:
            latest_file = current_files[-1]
            latest_name = latest_file.name

            if self._active_filename is not None and latest_name != self._active_filename:
                # PID switch detected — flush pending as pid_switch
                switch_records = self._handle_pid_switch(latest_name)
                all_records.extend(switch_records)
                pid_switched = True

            self._active_filename = latest_name

        # Phase 1 + 2: Read new lines and parse
        raw_lines, pending = self._reader.read_new_lines()

        if raw_lines:
            self._last_read_time = datetime.now(timezone.utc).isoformat()
            parsed, _, _ = parse_log_lines(raw_lines, self._match_tags, pending_line="")

            # Phase 3: Feed parsed records into trace assembler
            for parsed_record in parsed:
                merged_list = self._assembler.feed(parsed_record)
                for merged in merged_list:
                    self._writer.write(merged)
                    all_records.append(merged)

                    # If this is a completed TRACE record, finalize the conversation.
                    # TRACE records: no "type" field, carry "timestamp" (not
                    # start_time/end_time).  "output" signals the END was merged.
                    if "type" not in merged and "timestamp" in merged and "output" in merged:
                        conversation_id = merged.get("session_id", "")
                        if conversation_id:
                            self._writer.finalize_conversation(conversation_id)

        # Timeout check after each poll (skip if PID just switched —
        # pending was already flushed by mark_pid_switch)
        if not pid_switched:
            timeout_records = self._assembler.check_timeouts()
            for record in timeout_records:
                self._writer.write(record)
            all_records.extend(timeout_records)

        return all_records

    def _handle_pid_switch(self, new_filename: str) -> list[dict]:
        """Handle PID switch: mark old pending as incomplete, set old file completed."""
        logger.info("pid_switch_detected", old_file=self._active_filename, new_file=new_filename)

        # Flush all pending STARTs as pid_switch incomplete
        records = self._assembler.mark_pid_switch()

        # Mark old file as completed in offsets (reuse reader's OffsetManager)
        if self._active_filename:
            mgr = self._reader.offset_mgr
            entry = mgr.get(self._active_filename)
            if entry is not None:
                mgr.update(self._active_filename, FileOffset(
                    offset=entry.offset,
                    file_size=entry.file_size,
                    first_line_hash=entry.first_line_hash,
                    completed=True,
                ))

        # Write all incomplete records
        for record in records:
            self._writer.write(record)

        return records


def create_pipeline_for_agent(
    agent_cfg: AgentEntryConfig,
    shared_cfg: AdapterConfig,
) -> Pipeline:
    """Create a Pipeline instance from an AgentEntryConfig + shared AdapterConfig.

    The AgentEntryConfig provides per-agent paths (log_dir, output_dir, offset_file,
    log_pattern). The shared AdapterConfig provides shared settings (poll_interval,
    match_tags, pair_timeout, start_from, cleanup settings).

    This builds a temporary AdapterConfig that merges per-agent paths with shared
    settings, then constructs a Pipeline with the agent's name.
    """
    merged = shared_cfg.model_copy(update={
        "log_dir": agent_cfg.log_dir,
        "log_pattern": agent_cfg.log_pattern or shared_cfg.log_pattern,
        "output_dir": agent_cfg.output_dir or f"data/output/{agent_cfg.name}",
        "offset_file": agent_cfg.offset_file or f"data/offsets/{agent_cfg.name}.json",
    })
    return Pipeline(merged, agent_name=agent_cfg.name)
