# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Workspace artifact discovery and material indexing."""

from __future__ import annotations

import re
from pathlib import Path

from skill_builder.application.file_helpers import _should_skip_package_path
from skill_builder.domain.candidate_contract import TEXT_ARTIFACT_SUFFIXES


def material_preview_entries(
    root: Path,
    *,
    max_files: int = 8,
    max_chars: int = 1800,
) -> list[dict[str, str]]:
    entries: list[dict[str, str]] = []
    inputs = root / "inputs"
    if not inputs.is_dir():
        return entries
    for path in sorted(inputs.rglob("*")):
        if len(entries) >= max_files:
            break
        if not path.is_file() or path.stat().st_size <= 0:
            continue
        relative_path = path.relative_to(root).as_posix()
        if path.suffix.lower() not in TEXT_ARTIFACT_SUFFIXES:
            parsed = path.with_name(f"{path.stem}_parsed.md")
            if parsed.is_file() and parsed.stat().st_size > 0:
                path = parsed
                relative_path = path.relative_to(root).as_posix()
            else:
                continue
        try:
            content = path.read_text(encoding="utf-8", errors="replace").strip()
        except OSError:
            continue
        if content:
            is_recording = _is_recording_material(content)
            content = _compact_recording_preview(content, max_chars=max_chars)
            entries.append(
                {
                    "path": relative_path,
                    "title": path.stem[:120],
                    "preview": content if is_recording else content[:max_chars],
                }
            )
    return entries


def _is_recording_material(content: str) -> bool:
    text = str(content or "")
    return bool(
        "## 录制步骤" in text
        or re.search(r"^###\s+步骤\s*\d+", text, flags=re.MULTILINE)
        or "web-recording" in text.lower()
    )


def _compact_recording_preview(content: str, *, max_chars: int) -> str:
    """Keep recording metadata and step actions, not a huge page-text dump."""

    text = str(content or "")
    if not _is_recording_material(text):
        return text[:max_chars]

    recording_limit = max(max_chars, 6_000)
    lines = text.splitlines()
    intro: list[str] = []
    steps: list[list[str]] = []
    current: list[str] | None = None
    for line in lines:
        if re.match(r"^###\s+步骤\s*\d+", line):
            current = [line]
            steps.append(current)
        elif current is None:
            if len("\n".join(intro)) < min(900, recording_limit // 2):
                intro.append(line)
        elif line.startswith("- ") and any(
            marker in line for marker in ("时间", "URL", "操作目标", "截图", "页面标题")
        ):
            current.append(line)

    compact: list[str] = ["\n".join(intro).strip(), "## 录制步骤索引"]
    compact.extend("\n".join(step).strip() for step in steps)
    result = "\n\n".join(item for item in compact if item)
    return result[:recording_limit]


def ensure_workspace_material_digest(
    root: Path,
    *,
    materials_markdown: str,
) -> str:
    """Write the deterministic, compact material index consumed by Scenario."""

    workspace_dir = root / "workspace"
    workspace_dir.mkdir(parents=True, exist_ok=True)
    entries = material_preview_entries(root, max_files=20, max_chars=2400)
    lines = [
        "# 材料摘要",
        "",
        "本文件由平台在运行前生成，用于帮助 Agent 快速理解上传材料。详细证据仍以 inputs/ 下原始文件和 *_parsed.md 可读副本为准。",
        "",
        "## 材料索引",
        "",
        materials_markdown.strip() or "- 当前还没有上传材料。",
        "",
        "## 可读摘录",
        "",
    ]
    if entries:
        for item in entries:
            lines.extend(
                [
                    f"### {item['path']}",
                    "",
                    item["preview"].strip() or "（空文件）",
                    "",
                ]
            )
    else:
        lines.extend(["没有发现可直接读取的文本或 parsed Markdown 摘录。", ""])
    digest_path = workspace_dir / "material_digest.md"
    digest_path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
    return digest_path.relative_to(root).as_posix()


def actual_artifact_files(root: Path) -> list[str]:
    files: list[str] = []
    for base in ("generated-skill", "validation"):
        base_path = root / base
        if not base_path.exists():
            continue
        for path in sorted(base_path.rglob("*")):
            if not path.is_file():
                continue
            relative_path = path.relative_to(root).as_posix()
            if not _should_skip_package_path(path, relative_path):
                files.append(relative_path)
    return files


__all__ = [
    "actual_artifact_files",
    "ensure_workspace_material_digest",
    "material_preview_entries",
]
