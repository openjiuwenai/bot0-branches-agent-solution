# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Small file helpers shared by the standalone core and its current host adapter."""

from __future__ import annotations

import json
from pathlib import Path, PurePosixPath
import re
from typing import Any

import yaml

from skill_builder.application.draft_package_validation import validate_draft_package
from skill_builder.domain.candidate_contract import IGNORED_PACKAGE_PARTS, TEXT_ARTIFACT_SUFFIXES


MAX_TEXT_PREVIEW_BYTES = 256 * 1024


def _resolve_safe(root: Path, relative_path: str) -> Path:
    candidate = PurePosixPath(str(relative_path or ".").replace("\\", "/").strip())
    if candidate.is_absolute() or ".." in candidate.parts:
        raise ValueError("invalid workspace path")
    resolved_root = root.resolve()
    target = (resolved_root / candidate.as_posix()).resolve()
    if target != resolved_root and not target.is_relative_to(resolved_root):
        raise ValueError("invalid workspace path")
    return target


def _json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"


def _load_json_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def _has_substantive_generated_package(root: Path) -> bool:
    return validate_draft_package(root).ok


def _frontmatter_block(raw: str) -> dict[str, Any]:
    if not str(raw or "").startswith("---"):
        return {}
    lines = str(raw).splitlines()
    end = next((index for index in range(1, len(lines)) if lines[index].strip() == "---"), -1)
    if end < 0:
        return {}
    try:
        value = yaml.safe_load("\n".join(lines[1:end])) or {}
    except yaml.YAMLError:
        return {}
    return value if isinstance(value, dict) else {}


def _load_yaml_object(path: Path) -> dict[str, Any]:
    try:
        value = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except (OSError, UnicodeError, yaml.YAMLError):
        return {}
    return value if isinstance(value, dict) else {}


def _generated_openai_metadata(root: Path) -> tuple[str | None, str | None]:
    data = _load_yaml_object(root / "generated-skill" / "agents" / "openai.yaml")
    interface = data.get("interface") if isinstance(data.get("interface"), dict) else {}
    display_name = str(interface.get("display_name") or "").strip()
    short_description = str(interface.get("short_description") or "").strip()
    return display_name or None, short_description or None


def _generated_skill_heading(root: Path) -> str | None:
    for line in _read_text_artifact(root / "generated-skill" / "SKILL.md").splitlines():
        stripped = line.strip()
        if stripped.startswith("# "):
            return stripped[2:].strip()[:128] or None
    return None


def _contains_cjk(text: str | None) -> bool:
    return bool(re.search(r"[\u3400-\u9fff]", str(text or "")))


def _is_text_artifact(path: Path) -> bool:
    return path.suffix.lower() in TEXT_ARTIFACT_SUFFIXES


def _read_text_artifact(path: Path) -> str:
    try:
        if not path.is_file() or path.stat().st_size > MAX_TEXT_PREVIEW_BYTES:
            return ""
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def _should_skip_package_path(path: Path, rel: str) -> bool:
    parts = str(rel or "").replace("\\", "/").split("/")
    return bool(
        any(part in IGNORED_PACKAGE_PARTS for part in parts)
        or path.name.endswith((".pyc", ".pyo"))
        or path.name in {".DS_Store", "plugin.yaml"}
    )


# Public, host-neutral file policy helpers.  Hosts may use these for their
# editor/upload projections through ``skill_builder.spi``; the implementation
# remains owned by Core so path and text rules are not duplicated.
resolve_safe = _resolve_safe
frontmatter_block = _frontmatter_block
load_yaml_object = _load_yaml_object
is_text_artifact = _is_text_artifact
json_text = _json_text


__all__ = [
    "_contains_cjk", "_frontmatter_block", "_generated_openai_metadata",
    "_generated_skill_heading", "_has_substantive_generated_package",
    "_is_text_artifact", "_json_text", "_load_json_object", "_load_yaml_object",
    "_read_text_artifact", "_resolve_safe", "_should_skip_package_path",
    "resolve_safe", "frontmatter_block", "load_yaml_object",
    "is_text_artifact", "json_text",
]
