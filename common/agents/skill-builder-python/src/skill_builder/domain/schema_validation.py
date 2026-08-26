# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Small dependency-free validator for model boundary JSON schemas."""

from __future__ import annotations

import json
import re
from typing import Any


def _matches_type(value: Any, expected: str) -> bool:
    return {
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "number": isinstance(value, (int, float)) and not isinstance(value, bool),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
        "null": value is None,
    }.get(expected, True)


def json_schema_issues(value: Any, schema: Any, *, label: str) -> list[str]:
    """Validate the bounded schema subset used by Agent tool contracts.

    Tool frameworks validate native object calls, but JSON-string transports
    are decoded after tool validation. Reusing the exact tool schema here keeps
    one contract authority without adding a heavyweight runtime dependency.
    """

    issues: list[str] = []

    def walk(current: Any, current_schema: Any, path: str) -> None:
        if not isinstance(current_schema, dict):
            return
        alternatives = current_schema.get("oneOf") or current_schema.get("anyOf")
        if isinstance(alternatives, list):
            if not any(not json_schema_issues(current, option, label=path) for option in alternatives):
                issues.append(f"{path} does not match any allowed schema")
            return
        if isinstance(current_schema.get("enum"), list) and current not in current_schema["enum"]:
            issues.append(
                f"{path} must be one of: "
                + ", ".join(str(item) for item in current_schema["enum"])
            )
            return
        expected = current_schema.get("type")
        if isinstance(expected, str) and not _matches_type(current, expected):
            issues.append(f"{path} must be {expected}")
            return

        if isinstance(current, dict):
            properties = current_schema.get("properties")
            properties = properties if isinstance(properties, dict) else {}
            unknown = sorted(str(key) for key in current if key not in properties)
            additional = current_schema.get("additionalProperties", True)
            if additional is False and unknown:
                issues.append(f"{path} contains unsupported fields: {', '.join(unknown)}")
            required = current_schema.get("required")
            for key in required if isinstance(required, list) else []:
                if key not in current:
                    issues.append(f"{path}.{key} is required")
            maximum = current_schema.get("maxProperties")
            if isinstance(maximum, int) and len(current) > maximum:
                issues.append(f"{path} exceeds {maximum} properties")
            for key, child in current.items():
                child_schema = properties.get(key)
                if child_schema is None and isinstance(additional, dict):
                    child_schema = additional
                if child_schema is not None:
                    walk(child, child_schema, f"{path}.{key}")
            return

        if isinstance(current, list):
            minimum = current_schema.get("minItems")
            maximum = current_schema.get("maxItems")
            if isinstance(minimum, int) and len(current) < minimum:
                issues.append(f"{path} must contain at least {minimum} items")
            if isinstance(maximum, int) and len(current) > maximum:
                issues.append(f"{path} exceeds {maximum} items")
            if current_schema.get("uniqueItems") is True:
                encoded = [
                    json.dumps(item, ensure_ascii=False, sort_keys=True, default=str)
                    for item in current
                ]
                if len(set(encoded)) != len(encoded):
                    issues.append(f"{path} items must be unique")
            item_schema = current_schema.get("items")
            for index, child in enumerate(current, start=1):
                walk(child, item_schema, f"{path}[{index}]")
            return

        if isinstance(current, str):
            minimum = current_schema.get("minLength")
            maximum = current_schema.get("maxLength")
            if isinstance(minimum, int) and len(current) < minimum:
                issues.append(f"{path} must contain at least {minimum} characters")
            if isinstance(maximum, int) and len(current) > maximum:
                issues.append(f"{path} exceeds {maximum} characters")
            pattern = current_schema.get("pattern")
            if isinstance(pattern, str) and re.fullmatch(pattern, current) is None:
                issues.append(f"{path} does not match the required pattern")
            return

        if isinstance(current, (int, float)) and not isinstance(current, bool):
            minimum = current_schema.get("minimum")
            maximum = current_schema.get("maximum")
            if isinstance(minimum, (int, float)) and current < minimum:
                issues.append(f"{path} must be at least {minimum}")
            if isinstance(maximum, (int, float)) and current > maximum:
                issues.append(f"{path} must be at most {maximum}")

    walk(value, schema, label)
    return list(dict.fromkeys(issues))


__all__ = ["json_schema_issues"]
