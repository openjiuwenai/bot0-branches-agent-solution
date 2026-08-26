# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""One transport boundary for model-authored structured payloads.

Tool adapters may accept either a native JSON object or a JSON-encoded string,
but domain compilers always receive a bounded mapping.  Keeping this conversion
outside domain normalization prevents host/Pydantic behavior from becoming a
second contract authority.
"""

from __future__ import annotations

import json
from typing import Any


STRUCTURED_PAYLOAD_MAX_BYTES = 256 * 1024
STRUCTURED_PAYLOAD_MAX_DEPTH = 24
STRUCTURED_PAYLOAD_MAX_NODES = 20_000


class _DuplicateKeyError(ValueError):
    pass


def structured_mapping_input_schema(
    object_schema: dict[str, Any],
    *,
    max_characters: int = STRUCTURED_PAYLOAD_MAX_BYTES,
) -> dict[str, Any]:
    """Accept the same logical mapping through object or JSON-string transport."""

    return {
        "anyOf": [
            object_schema,
            {
                "type": "string",
                "maxLength": max(1, int(max_characters)),
                "description": "JSON-encoded object compatibility transport.",
            },
        ]
    }


def _object_without_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateKeyError(f"duplicate object key: {key}")
        result[key] = value
    return result


def _payload_shape_issue(
    value: Any,
    *,
    max_depth: int,
    max_nodes: int,
) -> str | None:
    remaining = max(1, int(max_nodes))
    stack: list[tuple[Any, int]] = [(value, 1)]
    while stack:
        current, depth = stack.pop()
        remaining -= 1
        if remaining < 0:
            return f"payload exceeds {max_nodes} JSON nodes"
        if depth > max(1, int(max_depth)):
            return f"payload exceeds nesting depth {max_depth}"
        if isinstance(current, dict):
            stack.extend((item, depth + 1) for item in current.values())
        elif isinstance(current, list):
            stack.extend((item, depth + 1) for item in current)
    return None


def decode_structured_mapping(
    value: Any,
    *,
    payload_name: str,
    error_code: str,
    max_bytes: int = STRUCTURED_PAYLOAD_MAX_BYTES,
    max_depth: int = STRUCTURED_PAYLOAD_MAX_DEPTH,
    max_nodes: int = STRUCTURED_PAYLOAD_MAX_NODES,
) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
    """Decode one bounded object without echoing model-authored content."""

    transport = "object" if isinstance(value, dict) else "json_string" if isinstance(value, str) else type(value).__name__
    parsed: Any = value
    if isinstance(value, str):
        encoded = value.encode("utf-8")
        if len(encoded) > max_bytes:
            return None, {
                "ok": False,
                "error": error_code,
                "message": f"{payload_name} JSON exceeds the {max_bytes}-byte transport limit.",
                "transport": transport,
                "sizeBytes": len(encoded),
                "maxBytes": max_bytes,
            }
        try:
            parsed = json.loads(value, object_pairs_hook=_object_without_duplicate_keys)
        except _DuplicateKeyError:
            return None, {
                "ok": False,
                "error": error_code,
                "message": f"{payload_name} JSON contains duplicate keys.",
                "transport": transport,
            }
        except json.JSONDecodeError as exc:
            return None, {
                "ok": False,
                "error": error_code,
                "message": f"{payload_name} must be a valid JSON object.",
                "transport": transport,
                "jsonError": {
                    "line": exc.lineno,
                    "column": exc.colno,
                    "position": exc.pos,
                    "reason": exc.msg[:300],
                },
            }
        except RecursionError:
            return None, {
                "ok": False,
                "error": error_code,
                "message": f"{payload_name} JSON exceeds the supported nesting depth.",
                "transport": transport,
            }
    elif isinstance(value, dict):
        shape_issue = _payload_shape_issue(
            value,
            max_depth=max_depth,
            max_nodes=max_nodes,
        )
        if shape_issue:
            return None, {
                "ok": False,
                "error": error_code,
                "message": f"{payload_name} {shape_issue}.",
                "transport": transport,
            }
        try:
            encoded = json.dumps(
                value,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        except (TypeError, ValueError, RecursionError) as exc:
            return None, {
                "ok": False,
                "error": error_code,
                "message": f"{payload_name} contains non-JSON values.",
                "transport": transport,
                "detail": str(exc)[:300],
            }
        if len(encoded) > max_bytes:
            return None, {
                "ok": False,
                "error": error_code,
                "message": f"{payload_name} exceeds the {max_bytes}-byte transport limit.",
                "transport": transport,
                "sizeBytes": len(encoded),
                "maxBytes": max_bytes,
            }
    else:
        return None, {
            "ok": False,
            "error": error_code,
            "message": f"{payload_name} must be an object or a JSON-encoded object string.",
            "transport": transport,
        }

    if not isinstance(parsed, dict):
        return None, {
            "ok": False,
            "error": error_code,
            "message": f"{payload_name} JSON root must be an object.",
            "transport": transport,
            "rootType": type(parsed).__name__,
        }
    shape_issue = None if isinstance(value, dict) else _payload_shape_issue(
        parsed,
        max_depth=max_depth,
        max_nodes=max_nodes,
    )
    if shape_issue:
        return None, {
            "ok": False,
            "error": error_code,
            "message": f"{payload_name} {shape_issue}.",
            "transport": transport,
        }
    return parsed, None


__all__ = [
    "STRUCTURED_PAYLOAD_MAX_BYTES",
    "STRUCTURED_PAYLOAD_MAX_DEPTH",
    "STRUCTURED_PAYLOAD_MAX_NODES",
    "decode_structured_mapping",
    "structured_mapping_input_schema",
]
