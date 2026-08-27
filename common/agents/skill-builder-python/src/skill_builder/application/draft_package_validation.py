# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""The single readiness check for a generated Skill draft package."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
from typing import Any, Mapping

import yaml

from skill_builder.application.package_builder import (
    SkillPackageBuildError,
    build_skill_export_archive,
    resolve_skill_package_metadata,
)
from skill_builder.domain.candidate_contract import export_package_path_allowed
from skill_builder.domain.package_metadata import validate_openai_metadata_content
from skill_builder.domain.workspace_paths import forbidden_skill_package_path


_SKILL_NAME_RE = re.compile(r"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
_FRONTMATTER_RE = re.compile(r"^---\s*\n(.*?)\n---(?:\s*\n|$)", re.DOTALL)
_EXECUTABLE_SOURCE_SUFFIXES = frozenset({".py", ".js", ".ts", ".mjs"})


@dataclass(frozen=True, slots=True)
class DraftPackageValidation:
    ok: bool
    errors: tuple[dict[str, Any], ...]
    warnings: tuple[dict[str, Any], ...]
    files: tuple[str, ...]
    metadata: dict[str, str]

    def to_result(self) -> dict[str, Any]:
        blocking_ids = [str(item["id"]) for item in self.errors]
        return {
            "schemaVersion": "skill-builder-draft-validation/v1",
            "status": "pass" if self.ok else "fail",
            "outcome": "ready" if self.ok else "failed",
            "deliveryStatus": "ready" if self.ok else "blocked",
            "blockingFailureIds": blocking_ids,
            "blockingCheckIds": blocking_ids,
            "findings": [*self.errors, *self.warnings],
            "warnings": list(self.warnings),
            "files": list(self.files),
            "metadata": dict(self.metadata),
            "summary": {
                "status": "pass" if self.ok else "fail",
                "title": "Skill 草稿包可用" if self.ok else "Skill 草稿包不完整",
                "message": (
                    f"最小包校验通过，共 {len(self.files)} 个可导出文件。"
                    if self.ok
                    else f"最小包校验发现 {len(self.errors)} 个阻断问题。"
                ),
                "nextSteps": [] if self.ok else ["修复最小包结构后重新提交草稿。"],
            },
        }


def _finding(
    finding_id: str,
    message: str,
    *,
    path: str | None = None,
    severity: str = "fail",
) -> dict[str, Any]:
    value: dict[str, Any] = {
        "id": finding_id,
        "rootCauseId": finding_id,
        "severity": severity,
        "category": "package_integrity",
        "audience": "user",
        "repairable": True,
        "title": "Skill 草稿包结构",
        "message": message,
    }
    if path:
        value["path"] = path
    return value


def _frontmatter(markdown: str) -> tuple[Mapping[str, Any] | None, str | None]:
    match = _FRONTMATTER_RE.match(markdown)
    if match is None:
        return None, "SKILL.md 缺少 YAML frontmatter。"
    try:
        value = yaml.safe_load(match.group(1))
    except yaml.YAMLError as exc:
        return None, f"SKILL.md frontmatter 不是有效 YAML：{str(exc)[:500]}"
    if not isinstance(value, Mapping):
        return None, "SKILL.md frontmatter 顶层必须是对象。"
    return value, None


def validate_draft_package(root: Path) -> DraftPackageValidation:
    """Validate only the invariants required to edit, export, and reuse a Skill."""

    workspace_root = root.resolve()
    generated = workspace_root / "generated-skill"
    errors: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = []
    files: list[str] = []
    metadata: dict[str, str] = {}

    skill_entry = generated / "SKILL.md"
    if not generated.is_dir() or not skill_entry.is_file() or skill_entry.is_symlink():
        errors.append(
            _finding(
                "missing_skill_entry",
                "generated-skill/SKILL.md 必须存在且是普通文件。",
                path="generated-skill/SKILL.md",
            )
        )

    if generated.is_dir():
        for path in sorted(generated.rglob("*")):
            relative = path.relative_to(generated).as_posix()
            display_path = f"generated-skill/{relative}"
            if path.is_symlink():
                errors.append(
                    _finding(
                        "unsafe_package_symlink",
                        "Skill 包不能包含符号链接。",
                        path=display_path,
                    )
                )
                continue
            try:
                path.resolve().relative_to(generated.resolve())
            except (OSError, ValueError):
                errors.append(
                    _finding(
                        "package_path_escape",
                        "Skill 包路径越过 generated-skill 边界。",
                        path=display_path,
                    )
                )
                continue
            if path.is_dir():
                if forbidden_skill_package_path(relative):
                    errors.append(
                        _finding(
                            "reserved_package_path",
                            "Skill 包包含平台保留目录。",
                            path=display_path,
                        )
                    )
                continue
            if not path.is_file():
                errors.append(
                    _finding(
                        "unsupported_package_entry",
                        "Skill 包只允许普通文件和目录。",
                        path=display_path,
                    )
                )
                continue
            if relative != "SKILL.md" and path.name == "SKILL.md":
                errors.append(
                    _finding(
                        "multiple_skill_roots",
                        "Skill 包只能有一个根 SKILL.md，不能嵌套额外 Skill 根。",
                        path=display_path,
                    )
                )
            if forbidden_skill_package_path(relative):
                errors.append(
                    _finding(
                        "reserved_package_path",
                        "Skill 包包含平台保留路径。",
                        path=display_path,
                    )
                )
            if (
                path.suffix.lower() in _EXECUTABLE_SOURCE_SUFFIXES
                and not relative.startswith("scripts/")
            ):
                errors.append(
                    _finding(
                        "executable_outside_scripts",
                        "可执行源码必须位于 scripts/；references 和 fixtures 不能作为旁路生产入口。",
                        path=display_path,
                    )
                )
            if export_package_path_allowed(relative):
                files.append(display_path)

    if skill_entry.is_file() and not skill_entry.is_symlink():
        try:
            markdown = skill_entry.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            errors.append(
                _finding(
                    "skill_entry_unreadable",
                    f"SKILL.md 无法按 UTF-8 读取：{str(exc)[:500]}",
                    path="generated-skill/SKILL.md",
                )
            )
        else:
            frontmatter, issue = _frontmatter(markdown)
            if issue:
                errors.append(
                    _finding(
                        "skill_frontmatter_invalid",
                        issue,
                        path="generated-skill/SKILL.md",
                    )
                )
            elif frontmatter is not None:
                name = str(frontmatter.get("name") or "").strip()
                description = str(frontmatter.get("description") or "").strip()
                metadata.update({"name": name, "description": description})
                if not name or not _SKILL_NAME_RE.fullmatch(name):
                    errors.append(
                        _finding(
                            "skill_name_invalid",
                            "frontmatter.name 必须是非空 kebab-case 标识。",
                            path="generated-skill/SKILL.md",
                        )
                    )
                if not description:
                    errors.append(
                        _finding(
                            "skill_description_missing",
                            "frontmatter.description 不能为空。",
                            path="generated-skill/SKILL.md",
                        )
                    )

    openai_metadata = generated / "agents" / "openai.yaml"
    if openai_metadata.is_file() and not openai_metadata.is_symlink():
        try:
            content = openai_metadata.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            warnings.append(
                _finding(
                    "optional_openai_metadata_unreadable",
                    f"可选 UI 元数据无法读取：{str(exc)[:500]}",
                    path="generated-skill/agents/openai.yaml",
                    severity="warn",
                )
            )
        else:
            result = validate_openai_metadata_content(
                content,
                skill_name=metadata.get("name", ""),
            )
            for index, message in enumerate(result.errors):
                warnings.append(
                    _finding(
                        f"optional_openai_metadata_invalid_{index + 1}",
                        message,
                        path="generated-skill/agents/openai.yaml",
                        severity="warn",
                    )
                )

    if not errors:
        package_metadata = resolve_skill_package_metadata(
            skill_name=metadata.get("name"),
            fallback_skill_name=metadata.get("name") or "generated-skill",
            description=metadata.get("description"),
        )
        try:
            build_skill_export_archive(generated, package_metadata)
        except (OSError, SkillPackageBuildError, ValueError) as exc:
            errors.append(
                _finding(
                    "package_archive_unbuildable",
                    f"Skill 草稿无法构建安全归档：{str(exc)[:500]}",
                )
            )

    return DraftPackageValidation(
        ok=not errors,
        errors=tuple(errors),
        warnings=tuple(warnings),
        files=tuple(sorted(dict.fromkeys(files))),
        metadata=metadata,
    )


__all__ = ["DraftPackageValidation", "validate_draft_package"]
