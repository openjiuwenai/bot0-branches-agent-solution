"""Minimal integrity rules for one Scenario HITL decision form."""

from __future__ import annotations

import json
import re
from typing import Any


_COUNT_DIGITS = {"二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}


def _declared_decision_count(message: str | None) -> int:
    text = str(message or "")
    counts: list[int] = []
    for pattern in (
        r"([2-9]|[二三四五六七八九])\s*(?:个|项)\s*(?:需要[^\n]{0,16})?(?:关键)?(?:问题|决策(?:点|项)?)",
        r"(?:关键|需要[^\n]{0,16}确认(?:的)?)(?:问题|决策(?:点|项)?)?\s*[：:]?\s*([2-9]|[二三四五六七八九])\s*(?:个|项)",
    ):
        for match in re.finditer(pattern, text, re.IGNORECASE):
            token = match.group(1)
            counts.append(int(token) if token.isdigit() else _COUNT_DIGITS.get(token, 0))
    headings = {
        int(value)
        for value in re.findall(
            r"(?:^|\n)\s*#{1,6}\s*(?:关键\s*)?决策\s*([1-9])\s*[：:]",
            text,
            re.IGNORECASE,
        )
    }
    if headings:
        counts.extend((max(headings), len(headings)))
    return max(counts, default=0)


def decision_form_integrity_issues(
    *,
    message: str | None,
    fields: list[Any] | None,
    default_value: str | dict[str, Any] | None,
) -> list[str]:
    field_ids = [
        str(item.get("id") or item.get("decisionId") or item.get("key") or "").strip()
        for item in (fields or [])
        if isinstance(item, dict)
    ]
    field_ids = [value for value in field_ids if value]
    unique = set(field_ids)
    defaults: dict[str, Any] = {}
    if isinstance(default_value, dict):
        defaults = {str(key).strip(): value for key, value in default_value.items() if str(key).strip()}
    elif isinstance(default_value, str) and default_value.strip():
        try:
            parsed = json.loads(default_value)
        except json.JSONDecodeError:
            parsed = None
        if isinstance(parsed, dict):
            defaults = {str(key).strip(): value for key, value in parsed.items() if str(key).strip()}
    errors: list[str] = []
    declared = _declared_decision_count(message)
    if declared and len(unique) < declared:
        errors.append(f"正文声明 {declared} 项决策，但只有 {len(unique)} 个可交互字段")
    missing_defaults = sorted(set(defaults) - unique)
    if missing_defaults:
        errors.append("默认值没有对应的可交互字段：" + "、".join(missing_defaults))
    if len(field_ids) != len(unique):
        errors.append("决策表单包含重复 decisionId")
    return errors[:20]


__all__ = ["decision_form_integrity_issues"]
