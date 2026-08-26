# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Host-independent Skill package metadata and archive construction."""

from __future__ import annotations

import hashlib
import io
import re
import zipfile
from dataclasses import dataclass
from pathlib import Path

import yaml

from skill_builder.domain.candidate_contract import (
    export_package_path_allowed,
)

DEFAULT_SKILL_DISPLAY_NAME = "Skill 抽取草稿"
DEFAULT_SKILL_DESCRIPTION = "基于上传材料生成的业务 Skill 草稿，用于沉淀流程、规则、输入输出和需复核边界。"
DEFAULT_SKILL_VERSION = "0.1.0"
DEFAULT_SKILL_TAGS = ("skill-extract",)

EXPORT_REQUIRED_ENTRY = "SKILL.md"
EXPORT_UI_METADATA_ENTRY = "agents/openai.yaml"
@dataclass(frozen=True, slots=True)
class SkillPackageMetadata:
    skill_name: str
    display_name: str
    description: str
    version: str
    tags: tuple[str, ...] = DEFAULT_SKILL_TAGS


class SkillPackageBuildError(RuntimeError):
    """Raised when a generated directory cannot form a valid Skill archive."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def normalize_skill_slug(value: str) -> str:
    raw = (value or "").strip().lower()
    slug = re.sub(r"[^a-z0-9]+", "-", raw).strip("-")
    slug = re.sub(r"-{2,}", "-", slug)
    if not slug or not re.match(r"^[a-z]", slug):
        slug = f"skill-{slug}" if slug else "generated-skill"
    slug = slug[:64].strip("-")
    if not re.match(r"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$", slug):
        return "generated-skill"
    return slug


def clean_skill_tags(tags: list[str] | tuple[str, ...] | None) -> tuple[str, ...]:
    cleaned: list[str] = []
    for item in tags or ():
        tag = str(item or "").strip()
        if tag and tag not in cleaned:
            cleaned.append(tag[:64])
    return tuple(cleaned[:20])


def resolve_skill_package_metadata(
    *,
    skill_name: str | None,
    fallback_skill_name: str,
    display_name: str | None = None,
    description: str | None = None,
    version: str | None = None,
    tags: list[str] | tuple[str, ...] | None = None,
) -> SkillPackageMetadata:
    return SkillPackageMetadata(
        skill_name=normalize_skill_slug(skill_name or fallback_skill_name),
        display_name=(display_name or DEFAULT_SKILL_DISPLAY_NAME).strip(),
        description=(description or DEFAULT_SKILL_DESCRIPTION).strip(),
        version=(version or DEFAULT_SKILL_VERSION).strip() or DEFAULT_SKILL_VERSION,
        tags=clean_skill_tags(tags or DEFAULT_SKILL_TAGS),
    )


def upsert_skill_frontmatter(raw: str, *, skill_name: str, description: str) -> str:
    body = raw
    if raw.startswith("---"):
        lines = raw.splitlines()
        end = next((idx for idx in range(1, len(lines)) if lines[idx].strip() == "---"), -1)
        if end >= 0:
            body = "\n".join(lines[end + 1 :]).lstrip("\n")
    return f"---\nname: {skill_name}\ndescription: {description}\n---\n\n{body}"


def should_export_skill_path(path: Path, relative_path: str) -> bool:
    del path  # Kept for the stable host-facing callback signature.
    return export_package_path_allowed(relative_path)


def build_plugin_yaml(metadata: SkillPackageMetadata, *, author: str) -> str:
    payload = {
        "name": metadata.skill_name,
        "version": metadata.version,
        "display_name": metadata.display_name,
        "description": metadata.description,
        "runtime": {"type": "skill"},
        "metadata": {
            "author": author or "host-user",
            "tags": list(metadata.tags),
        },
    }
    return yaml.safe_dump(payload, allow_unicode=True, sort_keys=False)


def build_skill_export_archive(generated_root: Path, metadata: SkillPackageMetadata) -> tuple[bytes, str, str]:
    generated = generated_root.resolve()
    _require_skill_entry(generated)
    buffer = io.BytesIO()
    wrote_skill_md = False
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path, relative_path in _exported_files(generated):
            if relative_path == EXPORT_REQUIRED_ENTRY:
                raw = path.read_text(encoding="utf-8", errors="replace")
                archive.writestr(
                    relative_path,
                    upsert_skill_frontmatter(
                        raw,
                        skill_name=metadata.skill_name,
                        description=metadata.description,
                    ),
                )
                wrote_skill_md = True
            else:
                archive.write(path, relative_path)
    if not wrote_skill_md:
        raise SkillPackageBuildError("missing_skill_entry", "生成包缺少 SKILL.md，不能下载")
    content = buffer.getvalue()
    return content, f"{metadata.skill_name}-{metadata.version}-skill.zip", hashlib.sha256(content).hexdigest()


def build_skill_publish_archive(
    generated_root: Path,
    metadata: SkillPackageMetadata,
    *,
    author: str,
) -> tuple[bytes, str, str]:
    generated = generated_root.resolve()
    _require_skill_entry(generated)
    package_root = f"{metadata.skill_name}-{metadata.version}"
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(f"{package_root}/plugin.yaml", build_plugin_yaml(metadata, author=author))
        for path, relative_path in _exported_files(generated):
            archive_path = f"{package_root}/{metadata.skill_name}/{relative_path}"
            if relative_path == EXPORT_REQUIRED_ENTRY:
                raw = path.read_text(encoding="utf-8", errors="replace")
                archive.writestr(
                    archive_path,
                    upsert_skill_frontmatter(
                        raw,
                        skill_name=metadata.skill_name,
                        description=metadata.description,
                    ),
                )
            else:
                archive.write(path, archive_path)
    content = buffer.getvalue()
    return content, f"{metadata.skill_name}-{metadata.version}.zip", hashlib.sha256(content).hexdigest()


def _require_skill_entry(generated: Path) -> None:
    if not (generated / EXPORT_REQUIRED_ENTRY).is_file():
        raise SkillPackageBuildError("missing_skill_entry", "请先生成 Skill 包草稿")


def _exported_files(generated: Path) -> list[tuple[Path, str]]:
    files: list[tuple[Path, str]] = []
    for path in sorted(generated.rglob("*")):
        if not path.is_file():
            continue
        relative_path = path.relative_to(generated).as_posix()
        if should_export_skill_path(path, relative_path):
            files.append((path, relative_path))
    return files


__all__ = [
    "DEFAULT_SKILL_DISPLAY_NAME",
    "DEFAULT_SKILL_DESCRIPTION",
    "DEFAULT_SKILL_VERSION",
    "DEFAULT_SKILL_TAGS",
    "SkillPackageBuildError",
    "SkillPackageMetadata",
    "build_plugin_yaml",
    "build_skill_export_archive",
    "build_skill_publish_archive",
    "clean_skill_tags",
    "normalize_skill_slug",
    "resolve_skill_package_metadata",
    "should_export_skill_path",
    "upsert_skill_frontmatter",
]
