"""Log line parser — \x01 delimited parsing with three-level JSON fault tolerance."""

import ast
import json
import re
from dataclasses import dataclass

import structlog

# Pattern to detect the start of a new log line (timestamp prefix)
_NEW_LINE_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}")

logger = structlog.get_logger(__name__)

# Field indices in \x01-delimited log lines
_IDX_TIME = 0
_IDX_LEVEL = 1
_IDX_SOURCE = 2
_IDX_TRACE_ID = 3
_IDX_AGENT_ID = 4
_IDX_CONVERSATION_ID = 5
_IDX_TAG = 6
_IDX_COST = 7
_IDX_MESSAGE = 8

_MIN_FIELDS_FOR_TAG = 7  # At least up to the tag field

# Keywords to extract when JSON parsing fails (fallback strategy)
_FALLBACK_KEYWORDS = [
    "id",
    "type",
    "model",
    "name",
    "start_time",
    "end_time",
    "status_message",
    "input",
    "output",
    "total_cost",
    "internal_model_id",
]


@dataclass(frozen=True)
class ParsedTagRecord:
    """A single parsed tag record from an EDPAgent log line."""

    time: str
    level: str
    source: str
    trace_id: str
    agent_id: str
    conversation_id: str
    tag: str
    cost: str
    message: dict | str | None


def parse_log_line(line: str, match_tags: set[str]) -> ParsedTagRecord | None:
    """Parse a single \\x01-delimited log line into a structured record.

    Returns None if the line does not contain a matching tag or is unparseable.
    """
    if not line or not line.strip():
        return None

    parts = line.split("\x01")
    if len(parts) < _MIN_FIELDS_FOR_TAG:
        return None

    tag = parts[_IDX_TAG].strip()
    if tag not in match_tags:
        return None

    # Extract fixed fields — use empty string for missing fields
    time = parts[_IDX_TIME].strip() if len(parts) > _IDX_TIME else ""
    level = parts[_IDX_LEVEL].strip() if len(parts) > _IDX_LEVEL else ""
    source = parts[_IDX_SOURCE].strip() if len(parts) > _IDX_SOURCE else ""
    trace_id = parts[_IDX_TRACE_ID].strip() if len(parts) > _IDX_TRACE_ID else ""
    agent_id = parts[_IDX_AGENT_ID].strip() if len(parts) > _IDX_AGENT_ID else ""
    conversation_id = parts[_IDX_CONVERSATION_ID].strip() if len(parts) > _IDX_CONVERSATION_ID else ""
    cost = parts[_IDX_COST].strip() if len(parts) > _IDX_COST else ""

    # Parse message JSON with three-level fault tolerance
    raw_message = parts[_IDX_MESSAGE].strip() if len(parts) > _IDX_MESSAGE else ""
    message = _parse_message(raw_message)

    return ParsedTagRecord(
        time=time,
        level=level,
        source=source,
        trace_id=trace_id,
        agent_id=agent_id,
        conversation_id=conversation_id,
        tag=tag,
        cost=cost,
        message=message,
    )


def _parse_message(raw: str) -> dict | str | None:
    """Parse message field with three-level fault tolerance.

    Level 1: ast.literal_eval (handles single-quote JSON)
    Level 2: json.loads (standard JSON)
    Level 3: keyword fallback extraction
    On total failure: return raw string as raw_message.
    """
    if not raw:
        return None

    # Level 1: ast.literal_eval (tolerates single quotes)
    try:
        result = ast.literal_eval(raw)
        if isinstance(result, dict):
            return result
    except (ValueError, SyntaxError):
        pass

    # Level 2: json.loads
    try:
        result = json.loads(raw)
        if isinstance(result, dict):
            return result
    except json.JSONDecodeError:
        pass

    # Level 3: keyword fallback extraction
    extracted = _extract_keywords(raw, _FALLBACK_KEYWORDS)
    if extracted:
        return extracted

    # Total failure: preserve raw string
    logger.warning("message_parse_failed", raw_message=raw[:200])
    return raw


@dataclass(frozen=True)
class ParseStats:
    """Per-call parse statistics returned by parse_log_lines.

    Callers accumulate these across calls to track failure rates
    without relying on module-level mutable state.
    """

    total: int = 0
    failed: int = 0

    @property
    def failure_rate(self) -> float:
        if self.total == 0:
            return 0.0
        return self.failed / self.total


def _extract_keywords(payload: str, keywords: list[str]) -> dict | None:
    """Extract keyword-value pairs from a string when JSON parsing fails.

    Uses regex to find "keyword": <value> patterns and extracts
    balanced braces/brackets for nested structures.
    """
    result: dict = {}
    for keyword in keywords:
        pattern = rf"""['"]({keyword})['"]\s*:\s*"""
        match = re.search(pattern, payload)
        if not match:
            continue

        start_pos = match.end()
        if start_pos >= len(payload):
            continue

        value = _extract_value_at(payload, start_pos)
        if value is not None:
            # Try to parse the extracted value as JSON
            try:
                result[keyword] = json.loads(value)
            except (json.JSONDecodeError, ValueError):
                result[keyword] = value

    return result if result else None


def _extract_value_at(payload: str, start: int) -> str | None:
    """Extract a balanced JSON value starting at position `start`."""
    if start >= len(payload):
        return None

    char = payload[start]
    if char == "{":
        return _extract_balanced(payload, start, "{", "}")
    if char == "[":
        return _extract_balanced(payload, start, "[", "]")
    if char in ('"', "'"):
        quote = char
        end = payload.find(quote, start + 1)
        if end != -1:
            return payload[start : end + 1]
        return None
    # Simple scalar value
    match = re.match(r"[^\s,}\]]+", payload[start:])
    if match:
        return match.group(0)
    return None


def _extract_balanced(payload: str, start: int, open_char: str, close_char: str) -> str | None:
    """Extract balanced bracket content supporting nesting."""
    if payload[start] != open_char:
        return None

    count = 0
    i = start
    in_string = False
    string_quote: str | None = None

    while i < len(payload):
        char = payload[i]

        if in_string:
            if char == string_quote and payload[i - 1] != "\\":
                in_string = False
        else:
            if char in ('"', "'"):
                in_string = True
                string_quote = char
            elif char == open_char:
                count += 1
            elif char == close_char:
                count -= 1
                if count == 0:
                    return payload[start : i + 1]

        i += 1

    return None


def is_new_log_line(line: str) -> bool:
    """Check if a line starts with a timestamp, indicating a new log entry.

    Continuation lines (e.g. multi-line JSON in the message field) do not
    start with a timestamp pattern and are appended to the previous line.
    """
    return bool(_NEW_LINE_PATTERN.match(line))


def parse_log_lines(
    raw_lines: list[str],
    match_tags: set[str],
    pending_line: str = "",
) -> tuple[list[ParsedTagRecord], str, ParseStats]:
    """Parse multiple raw log lines, handling multi-line message continuation.

    Returns (parsed_records, pending_line, stats) where:
    - pending_line is any incomplete line to be carried forward
    - stats contains per-call parse statistics for observability

    Callers accumulate ParseStats across calls to detect sustained high
    failure rates.
    """
    records: list[ParsedTagRecord] = []
    current_line = pending_line
    batch_total = 0
    batch_failed = 0

    for raw in raw_lines:
        if not raw:
            continue

        if current_line and not is_new_log_line(raw):
            # Continuation of previous line — append with newline separator
            current_line += "\n" + raw
            continue

        # New line starts — parse any accumulated previous line
        if current_line:
            batch_total += 1
            record = parse_log_line(current_line, match_tags)
            if record is not None:
                records.append(record)
            else:
                batch_failed += 1

        current_line = raw

    # Parse the last accumulated line
    if current_line:
        batch_total += 1
        record = parse_log_line(current_line, match_tags)
        if record is not None:
            records.append(record)
        else:
            batch_failed += 1

    stats = ParseStats(total=batch_total, failed=batch_failed)

    if batch_failed > 0 and stats.failure_rate > 0.5:
        logger.warning(
            "parse_failure_rate_high",
            failed_count=batch_failed,
            total_count=batch_total,
            failure_rate=round(stats.failure_rate, 3),
        )

    # Return empty pending_line since we've processed everything;
    # actual half-line (no trailing newline) handling is done by the caller
    # who sees the raw file read boundary.
    return records, "", stats
