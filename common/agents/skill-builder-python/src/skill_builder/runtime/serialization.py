"""Serialization helpers shared by standalone Skill Builder workflows."""

from __future__ import annotations

from typing import Any


def json_safe(
    value: Any,
    *,
    max_text_length: int = 8000,
    max_items: int | None = None,
) -> Any:
    """Return a redacted JSON-compatible value without silently losing rows.

    Persistence and lifecycle state use this helper as well as UI/event
    previews. Callers that intentionally build a bounded preview opt in with
    ``max_items``; authoritative values are otherwise preserved.
    """

    if value is None or isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, str):
        return value[:max_text_length]
    if isinstance(value, dict):
        safe: dict[str, Any] = {}
        for key, item in value.items():
            key_text = str(key)
            if key_text.lower() in {"api_key", "apikey", "authorization", "token", "secret", "password"}:
                safe[key_text] = "<redacted>"
            else:
                safe[key_text] = json_safe(
                    item,
                    max_text_length=max_text_length,
                    max_items=max_items,
                )
        return safe
    if isinstance(value, (list, tuple, set)):
        items = list(value)
        if max_items is not None:
            items = items[: max(0, int(max_items))]
        return [
            json_safe(item, max_text_length=max_text_length, max_items=max_items)
            for item in items
        ]
    model_dump = getattr(value, "model_dump", None)
    if callable(model_dump):
        try:
            return json_safe(
                model_dump(),
                max_text_length=max_text_length,
                max_items=max_items,
            )
        except Exception:  # noqa: BLE001
            pass
    return str(value)[:max_text_length]


__all__ = ["json_safe"]
