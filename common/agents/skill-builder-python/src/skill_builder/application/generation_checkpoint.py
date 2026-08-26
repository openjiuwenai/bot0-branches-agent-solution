# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Durable generation checkpoints independent from a concrete host."""

from __future__ import annotations

import json
import shutil
from pathlib import Path

from skill_builder.domain.candidate_contract import (
    CANDIDATE_PROGRESS_PATHS,
    REQUIRED_CANDIDATE_PATHS,
)


SKILL_BUILDER_SKELETON_MARKER = "<!-- skill-builder:platform-skeleton -->"


def ensure_generation_checkpoint(
    *,
    root: Path,
    skill_name: str,
    display_name: str,
    description: str,
    materials_markdown: str,
    material_titles: list[str] | tuple[str, ...] = (),
) -> list[str]:
    """Create private recovery state before the first real candidate commit.

    A checkpoint is not a deliverable.  Keeping it outside ``generated-skill``
    and ``validation`` prevents placeholder files from being mistaken for
    Agent progress, package content, or an acceptance contract.
    """

    resolved_root = root.resolve()
    written: list[str] = []

    def write_if_missing(relative_path: str, content: str) -> None:
        target = resolved_root / relative_path
        if target.is_file() and target.stat().st_size > 0:
            return
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        written.append(relative_path)

    material_summary = "、".join(material_titles[:12]) if material_titles else "未上传材料"
    write_if_missing(
        ".skill-builder/checkpoints/generation.json",
        _json_text(
            {
                "schemaVersion": "skill-builder-generation-checkpoint/v1",
                "stage": "prepared",
                "skillName": skill_name,
                "displayName": display_name,
                "description": description,
                "materialTitles": list(material_titles[:12]),
                "materialSummary": material_summary,
                "requiredCandidateFiles": sorted(REQUIRED_CANDIDATE_PATHS),
                "progressHintFiles": sorted(
                    set(CANDIDATE_PROGRESS_PATHS) - set(REQUIRED_CANDIDATE_PATHS)
                ),
                "materialIndexAvailable": bool(materials_markdown.strip()),
            }
        ),
    )
    write_if_missing(
        ".skill-builder/checkpoints/material-index.md",
        "# 上传材料索引\n\n" + (materials_markdown.strip() or "当前没有可用材料索引。") + "\n",
    )
    return written


def reset_generated_outputs(root: Path) -> None:
    resolved_root = root.resolve()
    for name in ("generated-skill", "validation", "playwright"):
        target = resolved_root / name
        if target.exists():
            shutil.rmtree(target)
    workspace_root = resolved_root / "workspace"
    for name in ("verify", "material_digest.md"):
        target = workspace_root / name
        if target.is_dir():
            shutil.rmtree(target)
        elif target.exists():
            target.unlink()
    checkpoint_root = resolved_root / ".skill-builder" / "checkpoints"
    if checkpoint_root.exists():
        shutil.rmtree(checkpoint_root)
    # A fresh extraction must not resume a WAITING_FOR_USER checkpoint or a
    # durable candidate handoff from a previous generation. Continuations call
    # the host reset with ``reset_outputs=False`` and preserve this state.
    state_root = resolved_root / ".skill-builder" / "state"
    if state_root.exists():
        shutil.rmtree(state_root)
    for name in ("drafts", "revisions", "context"):
        private_root = resolved_root / ".skill-builder" / name
        if private_root.exists():
            shutil.rmtree(private_root)


def _json_text(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


__all__ = [
    "SKILL_BUILDER_SKELETON_MARKER",
    "ensure_generation_checkpoint",
    "reset_generated_outputs",
]
