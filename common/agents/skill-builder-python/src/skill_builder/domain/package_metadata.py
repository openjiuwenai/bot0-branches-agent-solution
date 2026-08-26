# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Canonical metadata contract for a portable generated Skill package.

The authoring preflight, final acceptance and repair policy must agree on the
same ``agents/openai.yaml`` semantics.  Keep parsing and validation here so a
candidate cannot pass one boundary and fail at a later one for the same fact.
"""

from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Any, Mapping

import yaml


OPENAI_METADATA_PATH = "agents/openai.yaml"
OPENAI_INTERFACE_REQUIRED_FIELDS = (
    "display_name",
    "short_description",
    "default_prompt",
)
OPENAI_RUNTIME_CONFIGURATION_KEYS = frozenset(
    {
        "api_key",
        "base_url",
        "frequency_penalty",
        "max_tokens",
        "model",
        "presence_penalty",
        "response_format",
        "seed",
        "stop",
        "temperature",
        "tool_choice",
        "tools",
        "top_p",
    }
)


@dataclass(frozen=True, slots=True)
class PackageMetadataValidation:
    data: Mapping[str, Any] | None
    errors: tuple[str, ...]

    @property
    def ok(self) -> bool:
        return not self.errors


def _runtime_configuration_paths(value: Any, *, prefix: str = "") -> tuple[str, ...]:
    paths: list[str] = []
    if isinstance(value, Mapping):
        for raw_key, child in value.items():
            key = str(raw_key or "").strip()
            path = f"{prefix}.{key}" if prefix else key
            if key.lower() in OPENAI_RUNTIME_CONFIGURATION_KEYS:
                paths.append(path)
            paths.extend(_runtime_configuration_paths(child, prefix=path))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            path = f"{prefix}[{index}]" if prefix else f"[{index}]"
            paths.extend(_runtime_configuration_paths(child, prefix=path))
    return tuple(paths)


def validate_openai_metadata_content(
    content: str,
    *,
    skill_name: str,
) -> PackageMetadataValidation:
    """Parse and validate the host-neutral UI metadata contract."""

    try:
        parsed = yaml.safe_load(str(content or ""))
    except yaml.YAMLError as exc:
        return PackageMetadataValidation(
            None,
            (f"{OPENAI_METADATA_PATH} 不是有效 YAML：{str(exc)[:500]}",),
        )
    if not isinstance(parsed, Mapping):
        return PackageMetadataValidation(
            None,
            (f"{OPENAI_METADATA_PATH} 顶层必须是对象。",),
        )

    errors: list[str] = []
    forbidden_paths = sorted(set(_runtime_configuration_paths(parsed)))
    if forbidden_paths:
        errors.append(
            f"{OPENAI_METADATA_PATH} 只能包含 UI metadata，不能包含运行配置："
            + "、".join(forbidden_paths[:12])
            + (" 等" if len(forbidden_paths) > 12 else "")
            + "。"
        )

    interface = parsed.get("interface")
    if not isinstance(interface, Mapping):
        errors.append("缺少对象 interface。")
        interface = {}
    values = {
        field: str(interface.get(field) or "").strip()
        for field in OPENAI_INTERFACE_REQUIRED_FIELDS
    }
    for field in OPENAI_INTERFACE_REQUIRED_FIELDS:
        if not values[field]:
            errors.append(f"缺少 interface.{field}。")
    expected_reference = f"${str(skill_name or '').strip()}"
    if values["default_prompt"] and expected_reference != "$" and expected_reference not in values["default_prompt"]:
        errors.append(f"interface.default_prompt 必须引用生成 Skill：{expected_reference}。")

    return PackageMetadataValidation(parsed, tuple(errors))


def skill_name_from_markdown(content: str) -> str | None:
    """Read the stable package name from SKILL.md frontmatter without I/O."""

    match = re.match(r"^---\s*\n(.*?)\n---(?:\s*\n|$)", str(content or ""), re.DOTALL)
    if not match:
        return None
    try:
        frontmatter = yaml.safe_load(match.group(1))
    except yaml.YAMLError:
        return None
    if not isinstance(frontmatter, Mapping):
        return None
    value = str(frontmatter.get("name") or "").strip()
    return value or None


__all__ = [
    "OPENAI_INTERFACE_REQUIRED_FIELDS",
    "OPENAI_METADATA_PATH",
    "OPENAI_RUNTIME_CONFIGURATION_KEYS",
    "PackageMetadataValidation",
    "skill_name_from_markdown",
    "validate_openai_metadata_content",
]
