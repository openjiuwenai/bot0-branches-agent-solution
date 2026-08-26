"""Canonical structured-input projection for ScenarioContract variants."""

from __future__ import annotations

import re
from typing import Any


def _first(value: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in value:
            return value.get(key)
    return None


def _field_contracts(value: dict[str, Any]) -> list[dict[str, Any]]:
    raw_required = _first(value, "required", "requiredFields", "必填", "必填字段")
    raw_optional = _first(value, "optional", "optionalFields", "可选", "可选字段")
    required = {
        str(item or "").strip()
        for item in raw_required or []
        if str(item or "").strip()
    } if isinstance(raw_required, list) else set()
    optional = {
        str(item or "").strip()
        for item in raw_optional or []
        if str(item or "").strip()
    } if isinstance(raw_optional, list) else set()
    fields: list[dict[str, Any]] = []
    seen: set[str] = set()
    raw_fields = _first(value, "fields", "字段", "columns", "列")
    source_fields = (
        raw_fields
        if isinstance(raw_fields, list)
        else [
            *(
                raw_required
                if isinstance(raw_required, list)
                else []
            ),
            *(
                raw_optional
                if isinstance(raw_optional, list)
                else []
            ),
        ]
    )
    for raw in source_fields:
        field = dict(raw) if isinstance(raw, dict) else {"name": str(raw or "").strip()}
        name = str(_first(field, "name", "field", "字段", "字段名", "列名") or "").strip()
        if not name or name in seen:
            continue
        seen.add(name)
        field["name"] = name
        field_type = _first(field, "type", "类型", "数据类型")
        if field_type not in (None, ""):
            field["type"] = field_type
        field_required = _first(field, "required", "必填", "是否必填")
        if isinstance(field_required, bool):
            field["required"] = field_required
        if not isinstance(field.get("required"), bool):
            if name in required:
                field["required"] = True
            elif name in optional:
                field["required"] = False
        fields.append(field)
    return fields


def scenario_structured_input_contracts(scenario: Any) -> list[dict[str, Any]]:
    """Normalize flat and named nested structured input declarations.

    Scenario facts can legitimately describe an input either as a direct
    contract (``{"format": ..., "fields": [...]}``) or as a named mapping
    (``{"商品清单": {"format": ..., "fields": [...]}}``). Both forms are
    projected into the same controller-owned representation.
    """

    if not isinstance(scenario, dict):
        return []
    contracts: list[dict[str, Any]] = []
    for item in scenario.get("inputs") or []:
        if not isinstance(item, dict):
            continue
        candidates: list[tuple[str, dict[str, Any]]] = []
        item_fields = _first(item, "fields", "字段", "columns", "列")
        if isinstance(item_fields, list):
            candidates.append(
                (
                    str(_first(item, "name", "名称", "file", "文件") or "").strip(),
                    item,
                )
            )
        else:
            candidates.extend(
                (str(name or "").strip(), nested)
                for name, nested in item.items()
                if isinstance(nested, dict)
                and isinstance(
                    _first(nested, "fields", "字段", "columns", "列"),
                    list,
                )
            )
        for name, raw in candidates:
            fields = _field_contracts(raw)
            if not fields:
                continue
            contract = dict(raw)
            contract["fields"] = fields
            format_value = _first(raw, "format", "格式", "fileFormat", "文件格式")
            if format_value not in (None, ""):
                contract["format"] = format_value
            description = _first(raw, "description", "描述", "说明")
            if description not in (None, ""):
                contract["description"] = description
            if name:
                contract.setdefault("name", name)
                contract.setdefault("description", name)
            contracts.append(contract)
    return contracts


def scenario_tabular_input_issues(scenario: Any) -> list[str]:
    """Require an explicit field surface for declared CSV/Excel inputs."""

    if not isinstance(scenario, dict):
        return []
    issues: list[str] = []
    for input_index, item in enumerate(scenario.get("inputs") or []):
        if not isinstance(item, dict):
            continue
        candidates: list[tuple[str, dict[str, Any]]] = []
        if _first(item, "format", "格式", "fileFormat", "文件格式") is not None:
            candidates.append((str(_first(item, "name", "名称") or input_index), item))
        else:
            candidates.extend(
                (str(name or nested_index), nested)
                for nested_index, (name, nested) in enumerate(item.items())
                if isinstance(nested, dict)
            )
        for name, contract in candidates:
            format_text = str(
                _first(contract, "format", "格式", "fileFormat", "文件格式")
                or ""
            ).strip()
            if not re.search(r"(?:^|[/,\s])(?:csv|xlsx?|excel|表格)(?:$|[/,\s])", format_text, re.IGNORECASE):
                continue
            fields = _first(contract, "fields", "字段", "columns", "列")
            required = _first(contract, "required", "requiredFields", "必填", "必填字段")
            optional = _first(contract, "optional", "optionalFields", "可选", "可选字段")
            if any(
                isinstance(value, list) and bool(value)
                for value in (fields, required, optional)
            ):
                continue
            issues.append(
                f"inputs[{input_index}].{name} declares tabular format {format_text!r} "
                "but has no fields[]; preserve the material field names explicitly"
            )
    return issues


__all__ = [
    "scenario_structured_input_contracts",
    "scenario_tabular_input_issues",
]
