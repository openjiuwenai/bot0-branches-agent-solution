"""Schema-scoped parsing for structured LLM output."""

from __future__ import annotations

import json
import logging
import re
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Literal

type StructuredOutputErrorCategory = Literal[
    "syntax",
    "top_level_type",
    "duplicate_key",
    "required_key",
    "field_type",
    "structure",
    "business_validation",
]

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class StructuredOutputPolicy:
    """Deterministic repair limits for one named output schema."""

    schema_name: str = "legacy"
    required_keys: frozenset[str] = frozenset()
    allowed_comma_next_keys: frozenset[str] = frozenset()
    allow_single_missing_comma: bool = True
    reject_duplicate_keys: bool = True


@dataclass(frozen=True)
class JsonRepairOperation:
    """One deterministic source edit applied before parsing."""

    op: Literal[
        "strip_code_fence",
        "remove_comment",
        "remove_trailing_comma",
        "normalize_quote",
        "escape_control_character",
        "append_closing_delimiter",
        "insert_comma",
    ]
    offset: int | None = None
    next_key: str | None = None


@dataclass(frozen=True)
class StructuredOutputResult:
    """Parsed object and complete validation/repair provenance."""

    data: dict[str, Any] | None
    error: str
    error_category: StructuredOutputErrorCategory | None
    parse_mode: Literal["exact", "deterministic_repair", "deterministic_comma_repair", "failed"]
    repair_operations: tuple[JsonRepairOperation, ...] = ()
    repaired_text: str | None = None


@dataclass(frozen=True)
class ValidationResult:
    """Stable outcome returned by a stage validator."""

    ok: bool
    category: StructuredOutputErrorCategory | None = None
    message: str = ""


def parse_structured_output(
    raw: str,
    *,
    policy: StructuredOutputPolicy,
    validator: Callable[[dict[str, Any]], ValidationResult],
) -> StructuredOutputResult:
    """Parse one complete JSON object using deterministic, schema-scoped repair."""
    exact = _load_and_validate(raw, policy=policy, validator=validator)
    if exact.error_category is None:
        return exact

    operations: list[JsonRepairOperation] = []
    candidate = raw
    fence = _FENCE_RE.match(candidate)
    if fence is not None:
        candidate = fence.group(1)
        operations.append(JsonRepairOperation("strip_code_fence"))

    candidate, changed = _normalize_unambiguous_single_quotes(candidate)
    if changed:
        operations.append(JsonRepairOperation("normalize_quote"))
    candidate, changed = _remove_line_comments(candidate)
    if changed:
        operations.append(JsonRepairOperation("remove_comment"))
    candidate, changed = _escape_string_control_characters(candidate)
    if changed:
        operations.append(JsonRepairOperation("escape_control_character"))
    candidate, changed = _remove_trailing_commas(candidate)
    if changed:
        operations.append(JsonRepairOperation("remove_trailing_comma"))
    candidate, changed = _append_closing_delimiters(candidate)
    if changed:
        operations.append(JsonRepairOperation("append_closing_delimiter"))

    repaired = _load_and_validate(candidate, policy=policy, validator=validator)
    if repaired.error_category is None and operations:
        return StructuredOutputResult(
            repaired.data,
            "",
            None,
            "deterministic_repair",
            tuple(operations),
            candidate,
        )

    if policy.allow_single_missing_comma and repaired.error_category == "syntax":
        comma_repair = _insert_allowed_comma(candidate, policy)
        if comma_repair is not None:
            repaired_text, operation = comma_repair
            comma_result = _load_and_validate(
                repaired_text,
                policy=policy,
                validator=validator,
            )
            if comma_result.error_category is None:
                return StructuredOutputResult(
                    comma_result.data,
                    "",
                    None,
                    "deterministic_comma_repair",
                    (*operations, operation),
                    repaired_text,
                )
            return _with_repair_provenance(
                comma_result,
                operations=(*operations, operation),
                repaired_text=repaired_text,
            )

    if operations:
        return _with_repair_provenance(
            repaired,
            operations=tuple(operations),
            repaired_text=candidate,
        )
    return exact


def _with_repair_provenance(
    result: StructuredOutputResult,
    *,
    operations: tuple[JsonRepairOperation, ...],
    repaired_text: str,
) -> StructuredOutputResult:
    """Attach attempted deterministic edits without changing a failed outcome."""
    return StructuredOutputResult(
        data=result.data,
        error=result.error,
        error_category=result.error_category,
        parse_mode=result.parse_mode,
        repair_operations=operations,
        repaired_text=repaired_text,
    )


def _load_and_validate(
    text: str,
    *,
    policy: StructuredOutputPolicy,
    validator: Callable[[dict[str, Any]], ValidationResult],
) -> StructuredOutputResult:
    duplicate_keys: list[str] = []

    def build_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                duplicate_keys.append(key)
            result[key] = value
        return result

    def reject_nonstandard_constant(value: str) -> Any:
        raise ValueError(f"non-standard JSON numeric constant: {value}")

    try:
        data = json.loads(
            text,
            object_pairs_hook=build_object,
            parse_constant=reject_nonstandard_constant,
        )
    except (json.JSONDecodeError, TypeError, ValueError):
        return StructuredOutputResult(None, "invalid JSON syntax", "syntax", "failed")
    if policy.reject_duplicate_keys and duplicate_keys:
        return StructuredOutputResult(
            None,
            f"duplicate keys: {sorted(set(duplicate_keys))!r}",
            "duplicate_key",
            "failed",
        )
    if not isinstance(data, dict):
        return StructuredOutputResult(
            None,
            "top-level JSON value must be an object",
            "top_level_type",
            "failed",
        )
    missing = policy.required_keys.difference(data)
    if missing:
        return StructuredOutputResult(
            None,
            f"missing required keys: {sorted(missing)!r}",
            "required_key",
            "failed",
        )
    try:
        validation = validator(data)
    except Exception:
        return StructuredOutputResult(
            None,
            "structured output validator raised an exception",
            "business_validation",
            "failed",
        )
    if not validation.ok:
        return StructuredOutputResult(
            None,
            validation.message or "structured output validation failed",
            validation.category or "business_validation",
            "failed",
        )
    return StructuredOutputResult(data, "", None, "exact")


_FENCE_RE = re.compile(r"\A\s*```(?:json)?\s*\n?(.*?)\n?```\s*\Z", re.DOTALL | re.IGNORECASE)


def _insert_allowed_comma(
    text: str,
    policy: StructuredOutputPolicy,
) -> tuple[str, JsonRepairOperation] | None:
    """Return the sole schema-allowed object comma insertion, if unambiguous."""
    candidates: list[tuple[int, str]] = []
    pattern = re.compile(r'(?<=[0-9eE.truefalsnul}\]\}"])\s+(?="(?P<key>[^"\\]+)"\s*:)')
    for match in pattern.finditer(text):
        key = match.group("key")
        if key not in policy.allowed_comma_next_keys:
            continue
        if _container_at(text, match.start()) != "{":
            continue
        candidates.append((match.start(), key))
    if len(candidates) != 1:
        return None
    offset, key = candidates[0]
    operation = JsonRepairOperation("insert_comma", offset=offset, next_key=key)
    return f"{text[:offset]},{text[offset:]}", operation


def _container_at(text: str, offset: int) -> str | None:
    stack: list[str] = []
    in_string = False
    escaped = False
    for char in text[:offset]:
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char in "[{":
            stack.append(char)
        elif char == "]" and stack and stack[-1] == "[":
            stack.pop()
        elif char == "}" and stack and stack[-1] == "{":
            stack.pop()
    return stack[-1] if stack else None


def _remove_trailing_commas(text: str) -> tuple[str, bool]:
    output: list[str] = []
    in_string = False
    escaped = False
    removed = False
    index = 0
    while index < len(text):
        char = text[index]
        if in_string:
            output.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            output.append(char)
            index += 1
            continue
        if char == ",":
            lookahead = index + 1
            while lookahead < len(text) and text[lookahead].isspace():
                lookahead += 1
            if lookahead < len(text) and text[lookahead] in "}]":
                removed = True
                index += 1
                continue
        output.append(char)
        index += 1
    return "".join(output), removed


def _remove_line_comments(text: str) -> tuple[str, bool]:
    output: list[str] = []
    in_string = False
    escaped = False
    removed = False
    index = 0
    while index < len(text):
        char = text[index]
        if in_string:
            output.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            output.append(char)
            index += 1
            continue
        if char == "/" and index + 1 < len(text) and text[index + 1] == "/":
            removed = True
            index += 2
            while index < len(text) and text[index] not in "\r\n":
                index += 1
            continue
        output.append(char)
        index += 1
    return "".join(output), removed


def _append_closing_delimiters(text: str) -> tuple[str, bool]:
    stack: list[str] = []
    in_string = False
    escaped = False
    for char in text:
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char in "[{":
            stack.append(char)
        elif char in "]}":
            expected = "[" if char == "]" else "{"
            if not stack or stack[-1] != expected:
                return text, False
            stack.pop()
    if in_string or escaped or not stack:
        return text, False
    closers = "".join("]" if opener == "[" else "}" for opener in reversed(stack))
    return text + closers, True


def _escape_string_control_characters(text: str) -> tuple[str, bool]:
    output: list[str] = []
    in_string = False
    escaped = False
    changed = False
    for char in text:
        if not in_string:
            output.append(char)
            if char == '"':
                in_string = True
            continue
        if escaped:
            output.append(char)
            escaped = False
        elif char == "\\":
            output.append(char)
            escaped = True
        elif char == '"':
            output.append(char)
            in_string = False
        elif ord(char) < 0x20:
            output.append(json.dumps(char)[1:-1])
            changed = True
        else:
            output.append(char)
    return "".join(output), changed


def _normalize_unambiguous_single_quotes(text: str) -> tuple[str, bool]:
    if "'" not in text:
        return text, False
    output: list[str] = []
    stack: list[str] = []
    index = 0
    changed = False
    while index < len(text):
        char = text[index]
        if char == '"':
            end = _quoted_end(text, index, '"')
            if end is None:
                return text, False
            output.append(text[index : end + 1])
            index = end + 1
            continue
        if char in "[{":
            stack.append(char)
        elif char in "]}":
            expected = "[" if char == "]" else "{"
            if not stack or stack[-1] != expected:
                return text, False
            stack.pop()
        if char != "'":
            output.append(char)
            index += 1
            continue
        previous = _previous_nonspace(text, index)
        is_key = bool(stack and stack[-1] == "{" and previous in {"{", ","})
        is_value = previous in {":", "[", ","} and not is_key
        if not (is_key or is_value):
            return text, False
        end = text.find("'", index + 1)
        if end < 0:
            return text, False
        following = _next_nonspace(text, end)
        if (is_key and following != ":") or (is_value and following not in {",", "]", "}"}):
            return text, False
        content = text[index + 1 : end]
        if "'" in content or '"' in content or "\\" in content:
            return text, False
        output.extend(('"', content, '"'))
        changed = True
        index = end + 1
    return "".join(output), changed


def _quoted_end(text: str, start: int, quote: str) -> int | None:
    escaped = False
    for index in range(start + 1, len(text)):
        char = text[index]
        if escaped:
            escaped = False
        elif char == "\\":
            escaped = True
        elif char == quote:
            return index
    return None


def _previous_nonspace(text: str, offset: int) -> str | None:
    index = offset - 1
    while index >= 0 and text[index].isspace():
        index -= 1
    return text[index] if index >= 0 else None


def _next_nonspace(text: str, offset: int) -> str | None:
    index = offset + 1
    while index < len(text) and text[index].isspace():
        index += 1
    return text[index] if index < len(text) else None


def fix_json_text(text: str) -> str:
    """Compatibility helper for string-aware trailing-comma repair."""
    return _remove_trailing_commas(text)[0]


def extract_json(
    raw: str,
    *,
    policy: StructuredOutputPolicy,
) -> StructuredOutputResult:
    """Compatibility entry point; new callers should provide a stage validator."""
    return parse_structured_output(
        raw,
        policy=policy,
        validator=lambda _data: ValidationResult(ok=True),
    )


def extract_json_data(raw: str) -> dict[str, Any] | None:
    """Compatibility projection for legacy callers that only need object data."""
    result = extract_json(raw, policy=StructuredOutputPolicy())
    if result.data is None:
        fences = re.findall(r"```(?:json)?\s*(.*?)\s*```", raw, re.DOTALL | re.IGNORECASE)
        if len(fences) == 1:
            result = extract_json(fences[0], policy=StructuredOutputPolicy())
    return result.data


def log_structured_output(
    result: StructuredOutputResult,
    *,
    stage: str,
    schema_name: str,
    invocation_id: str = "unknown",
    attempt: object = "unknown",
    finish_reason: object = "unknown",
    transport_complete: object = "unknown",
    fallback: str | None = None,
) -> None:
    """Emit stable structured-output diagnostics without accepting raw response text."""
    fields = (
        f"stage={stage} schema_name={schema_name} invocation_id={invocation_id} "
        f"attempt={attempt} parse_mode={result.parse_mode} "
        f"repair_operations={result.repair_operations!r} finish_reason={finish_reason} "
        f"transport_complete={transport_complete}"
    )
    if result.error_category is None:
        if result.parse_mode != "exact":
            logger.warning("JSON repaired %s", fields)
        return
    logger.warning(
        "structured output failed %s category=%s fallback=%s",
        fields,
        result.error_category,
        fallback or "none",
    )


JsonRepairPolicy = StructuredOutputPolicy
JsonExtractionResult = StructuredOutputResult


__all__ = [
    "JsonExtractionResult",
    "JsonRepairOperation",
    "JsonRepairPolicy",
    "StructuredOutputErrorCategory",
    "StructuredOutputPolicy",
    "StructuredOutputResult",
    "ValidationResult",
    "extract_json",
    "extract_json_data",
    "fix_json_text",
    "log_structured_output",
    "parse_structured_output",
]
