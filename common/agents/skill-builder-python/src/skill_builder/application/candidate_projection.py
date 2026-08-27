# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Small projections derived directly from a persistent candidate draft."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from skill_builder.domain.candidate_contract import export_package_path_allowed


def _load_json_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8", errors="replace"))
    except (OSError, TypeError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def _atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(path)


def candidate_export_files(root: Path) -> list[str]:
    generated = root / "generated-skill"
    if not generated.is_dir():
        return []
    return [
        f"generated-skill/{path.relative_to(generated).as_posix()}"
        for path in sorted(generated.rglob("*"))
        if path.is_file()
        and export_package_path_allowed(path.relative_to(generated).as_posix())
    ]


def candidate_completion_from_draft(
    root: Path,
    *,
    summary: str = "",
) -> dict[str, Any]:
    """Build the compact completion record from the current package files."""
    files = candidate_export_files(root)
    package_ready = "generated-skill/SKILL.md" in files
    return {
        # A model completion is not a delivery state.  Only a real package
        # entry can be projected as draft_ready; empty/partial drafts remain
        # explicitly unready until the atomic candidate commit validates them.
        "status": "draft_ready" if package_ready else "not_ready",
        "summary": (
            " ".join(str(summary or "").split()).strip()[:2000]
            or f"已完成 {len(files)} 个 Skill 包文件的持久化草稿。"
        ),
        "files": files,
    }


__all__ = [
    "candidate_completion_from_draft",
    "candidate_export_files",
]
