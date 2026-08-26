# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

from __future__ import annotations

import shutil
from pathlib import Path


RESOURCES_ROOT = Path(__file__).resolve().parent
RESOURCE_ROOT = RESOURCES_ROOT / "internal-skills"
AGENT_CORE_RESOURCE_ROOT = ".skill-builder"
AGENT_CORE_SKILLS_ROOT = f"{AGENT_CORE_RESOURCE_ROOT}/skills"
INTERNAL_SKILL_NAMES = (
    "scenario-skill-builder",
    "skill-package-author",
)

_SCENARIO_REFERENCES = (
    "references/extraction-checklist.md",
    "references/evidence-boundaries.md",
    "references/numeric-precision.md",
)
_PACKAGE_REFERENCES = (
    "references/package-layout.md",
    "references/external-evidence.md",
    "references/final-checklist.md",
    "references/offline-validation.md",
    "references/numeric-precision.md",
)


def install_skill_builder_resources(root: Path, *, task_mode: str | None = None) -> list[str]:
    """Install Skill Builder internal resources for the agent-core bridge.

    The openjiuwen adapter keeps an auditable copy under ``.skill-builder``.
    The workspace copy remains available for native agent-core skill
    registration, follow-up reads, and traceability.
    """

    installed: list[str] = []
    obsolete_extensions = root / AGENT_CORE_RESOURCE_ROOT / "extensions"
    if obsolete_extensions.exists():
        shutil.rmtree(obsolete_extensions)
    selected_skill_names = (
        {
            path.split("/", 1)[0]
            for path, _content in _selected_internal_skill_files(task_mode)
        }
        if task_mode is not None
        else set(INTERNAL_SKILL_NAMES)
    )
    targets = ((RESOURCE_ROOT, root / AGENT_CORE_SKILLS_ROOT),)
    for source_root, target_root in targets:
        if target_root.exists():
            shutil.rmtree(target_root)
        if not source_root.is_dir():
            continue
        for source in sorted(item for item in source_root.iterdir() if item.is_dir()):
            if source_root == RESOURCE_ROOT and source.name not in selected_skill_names:
                continue
            target = target_root / source.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(source, target)
            installed.append(target.relative_to(root).as_posix())
    return installed


def _read_internal_skill_file(skill_name: str, relative_path: str) -> tuple[str, str] | None:
    path = RESOURCE_ROOT / skill_name / relative_path
    if not path.is_file():
        return None
    try:
        content = path.read_text(encoding="utf-8", errors="replace").strip()
    except OSError:
        return None
    if not content:
        return None
    return f"{skill_name}/{relative_path}", content


def _selected_internal_skill_files(task_mode: str) -> list[tuple[str, str]]:
    normalized = str(task_mode or "").strip().lower()
    selected: list[tuple[str, str]] = []

    def add(skill_name: str, *relative_paths: str) -> None:
        for relative_path in relative_paths:
            item = _read_internal_skill_file(skill_name, relative_path)
            if item is not None:
                selected.append(item)

    if normalized == "scenario":
        add("scenario-skill-builder", "SKILL.md", *_SCENARIO_REFERENCES)
        return selected

    if normalized in {"author", "author_build", "author_validate", "repair"}:
        add("skill-package-author", "SKILL.md", *_PACKAGE_REFERENCES)
        return selected

    add("scenario-skill-builder", "SKILL.md", *_SCENARIO_REFERENCES)
    add("skill-package-author", "SKILL.md", *_PACKAGE_REFERENCES)
    return selected


def internal_skill_context_paths(task_mode: str) -> list[str]:
    return [path for path, _content in _selected_internal_skill_files(task_mode)]


def build_internal_skill_routing_context(task_mode: str) -> str:
    """Return a short native-skill routing note without inlining skill content."""

    paths = internal_skill_context_paths(task_mode)
    skills = []
    for name in INTERNAL_SKILL_NAMES:
        if any(path.startswith(f"{name}/") for path in paths):
            skills.append(name)
    if not skills:
        skills = list(INTERNAL_SKILL_NAMES)
    lines = [
        "# Agent-core 内置 Skill 路由",
        "",
        "平台已将 Skill 抽取内置能力注册到当前 agent-core 会话，默认按内置 skill 的职责推进，不要把 `.skill-builder/skills/` 当作上传材料重新抽取。",
        "- `scenario-skill-builder`：负责材料理解、业务规则抽取、关键歧义识别和 HITL 清账。",
        "- `skill-package-author`：负责把已确认场景写成可复用 Skill 包及必要的脚本、fixtures 和 references。",
        "",
        "当前会话相关内置 skill：" + "、".join(skills),
        "如需核对完整原文，只读取与当前失败项或当前步骤直接相关的 `.skill-builder/skills/.../SKILL.md` 或 references；不要把读取这些文件作为固定前置步骤。",
    ]
    return "\n".join(lines)


def build_internal_skill_context(task_mode: str, *, max_chars: int = 120_000) -> str:
    """Return stage-specific internal skill rules for direct prompt injection."""

    sections: list[str] = [
        "# 已预加载的 Skill 抽取规则",
        "",
        "以下规则由平台在 Agent 启动前加载，优先级高于普通用户材料。Agent 不需要先发现这些文件才能遵循规则；如需核对原文，可继续读取 `.skill-builder/skills/...`。",
    ]
    used = len("\n".join(sections))
    for path, content in _selected_internal_skill_files(task_mode):
        block = f"\n\n## {path}\n\n{content}"
        if used + len(block) > max_chars:
            sections.extend(["", f"## {path}", "", "该规则文件因上下文预算被截断；需要时通过工作区读取工具查看完整内容。"])
            break
        sections.append(block)
        used += len(block)
    return "\n".join(sections).strip()
