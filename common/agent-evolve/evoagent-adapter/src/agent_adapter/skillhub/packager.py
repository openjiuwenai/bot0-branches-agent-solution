"""Skill directory zip packaging for SkillHub publish/pull."""

from __future__ import annotations

import hashlib
import io
import re
import zipfile
from pathlib import Path
from typing import Any

import yaml

from agent_adapter.skillhub.errors import SkillHubValidationError

_SKILL_MD = "SKILL.md"
_FRONTMATTER_RE = re.compile(r"^---\s*\n(.*?)\n---\s*\n", re.DOTALL)


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_frontmatter(skill_md: str) -> dict[str, Any] | None:
    match = _FRONTMATTER_RE.match(skill_md)
    if not match:
        return None
    try:
        meta = yaml.safe_load(match.group(1))
    except yaml.YAMLError:
        return None
    return meta if isinstance(meta, dict) else None


def parse_frontmatter_name(skill_md: str) -> str | None:
    meta = parse_frontmatter(skill_md)
    if not meta:
        return None
    name = meta.get("name")
    return str(name).strip() if name else None


def validate_skill_directory(skill_dir: Path) -> str:
    """Validate skill layout and return canonical skill name."""
    if not skill_dir.is_dir():
        raise SkillHubValidationError(f"Skill directory not found: {skill_dir}")
    skill_md_path = skill_dir / _SKILL_MD
    if not skill_md_path.is_file():
        raise SkillHubValidationError(f"SKILL.md missing under {skill_dir}")
    content = skill_md_path.read_text(encoding="utf-8")
    name = parse_frontmatter_name(content)
    if not name:
        raise SkillHubValidationError("SKILL.md frontmatter must include non-empty 'name'")
    if skill_dir.name != name:
        raise SkillHubValidationError(
            f"Directory name '{skill_dir.name}' must match SKILL.md name '{name}'"
        )
    return name


def _build_plugin_yaml(skill_name: str, frontmatter: dict[str, Any]) -> str:
    desc_raw = frontmatter.get("description")
    description = desc_raw.strip() if isinstance(desc_raw, str) and desc_raw.strip() else skill_name
    display_raw = frontmatter.get("display_name")
    display_name = display_raw.strip() if isinstance(display_raw, str) and display_raw.strip() else skill_name
    author_raw = frontmatter.get("author")
    author = author_raw.strip() if isinstance(author_raw, str) and author_raw.strip() else "evoagent-adapter"
    tags_val = frontmatter.get("tags")
    tags: list[str] = []
    if isinstance(tags_val, list):
        tags = [str(t).strip() for t in tags_val if isinstance(t, str) and str(t).strip()]
    elif isinstance(tags_val, str) and tags_val.strip():
        tags = [tags_val.strip()]
    if not tags:
        tags = ["skill"]
    data = {
        "name": skill_name,
        "display_name": display_name,
        "description": description,
        "runtime": {"type": "skill"},
        "metadata": {"author": author, "tags": tags},
    }
    return yaml.safe_dump(data, allow_unicode=True, sort_keys=False)


def build_zip(skill_dir: Path) -> bytes:
    """Pack a local skill dir into SkillHub layout: {name}/plugin.yaml + {name}/{name}/..."""
    skill_name = validate_skill_directory(skill_dir)
    skill_md = (skill_dir / _SKILL_MD).read_text(encoding="utf-8")
    frontmatter = parse_frontmatter(skill_md) or {}
    plugin_yaml = _build_plugin_yaml(skill_name, frontmatter)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(f"{skill_name}/plugin.yaml", plugin_yaml.encode("utf-8"))
        for path in sorted(skill_dir.rglob("*")):
            if not path.is_file():
                continue
            rel = path.relative_to(skill_dir)
            arcname = f"{skill_name}/{skill_name}/{rel.as_posix()}"
            zf.writestr(arcname, path.read_bytes())
    return buffer.getvalue()


def extract_zip(zip_bytes: bytes, target_parent: Path, *, overwrite: bool = True) -> Path:
    """Extract skill zip into target_parent/{skill_name}/ (flat adapter layout)."""
    buffer = io.BytesIO(zip_bytes)
    with zipfile.ZipFile(buffer, mode="r") as zf:
        names = [n.replace("\\", "/") for n in zf.namelist() if not n.endswith("/")]
        skill_roots = _detect_skill_roots(names)
        if len(skill_roots) == 1:
            skill_name, skill_prefix = next(iter(skill_roots.items()))
        else:
            skill_name, skill_prefix = _detect_root_skill_md(zf, names)
        target_dir = target_parent / skill_name
        if target_dir.exists() and not overwrite:
            raise SkillHubValidationError(f"Skill directory already exists: {target_dir}")
        target_dir.mkdir(parents=True, exist_ok=True)
        for name in names:
            if not name.startswith(skill_prefix):
                continue
            rel = Path(name[len(skill_prefix):])
            if ".." in rel.parts:
                raise SkillHubValidationError(f"Unsafe zip entry: {name}")
            out_path = target_dir / rel
            out_path.parent.mkdir(parents=True, exist_ok=True)
            out_path.write_bytes(zf.read(name))
    validate_skill_directory(target_dir)
    return target_dir


def _detect_root_skill_md(zf: zipfile.ZipFile, names: list[str]) -> tuple[str, str]:
    """Handle SkillHub artifact zip with a single root SKILL.md."""
    root_skill = [n for n in names if n == _SKILL_MD or n.endswith(f"/{_SKILL_MD}")]
    if len(root_skill) != 1:
        raise SkillHubValidationError("Zip must contain exactly one skill root with SKILL.md")
    entry = root_skill[0]
    if entry == _SKILL_MD:
        content = zf.read(entry).decode("utf-8")
        skill_name = parse_frontmatter_name(content)
        if not skill_name:
            raise SkillHubValidationError("SKILL.md frontmatter must include non-empty 'name'")
        return skill_name, ""
    parts = entry.split("/")
    skill_name = parts[0]
    return skill_name, f"{skill_name}/"


def _detect_skill_roots(names: list[str]) -> dict[str, str]:
    """Map skill_name -> zip prefix ending with '/' for files under skill workspace."""
    roots: dict[str, str] = {}
    for name in names:
        normalized = name.replace("\\", "/")
        if not normalized.endswith(f"/{_SKILL_MD}"):
            continue
        parts = normalized.split("/")
        if len(parts) < 2:
            continue
        # Hub layout: {plugin}/{skill}/SKILL.md
        if len(parts) == 3:
            plugin_root, skill_dir, _ = parts
            if plugin_root == skill_dir:
                roots[skill_dir] = f"{plugin_root}/{skill_dir}/"
                continue
        # Flat layout: {skill}/SKILL.md
        if len(parts) == 2:
            skill_name = parts[0]
            roots[skill_name] = f"{skill_name}/"
    return roots
