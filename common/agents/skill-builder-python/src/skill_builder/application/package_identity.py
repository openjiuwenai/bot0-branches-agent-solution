# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Resolve the immutable identity of one generated Skill package.

The workspace name is an orchestration concern.  A generated package name is
an artifact concern.  Treating both as one untyped ``skill_name`` caused the
Author to correctly replace ``skill-extract-*`` while Validation later tried
to restore that temporary name.  This module is the single authority used by
candidate commit, validation, delivery and host metadata adoption.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from skill_builder.application.package_builder import normalize_skill_slug
from skill_builder.domain.package_metadata import skill_name_from_markdown


TEMPORARY_PACKAGE_NAMES = frozenset({"generated-skill"})
TEMPORARY_PACKAGE_PREFIXES = ("skill-extract-",)


def is_temporary_package_name(value: str | None) -> bool:
    """Return whether ``value`` identifies orchestration, not publication."""

    raw = str(value or "").strip().lower()
    if not raw:
        return True
    normalized = normalize_skill_slug(raw)
    return normalized in TEMPORARY_PACKAGE_NAMES or normalized.startswith(
        TEMPORARY_PACKAGE_PREFIXES
    )


def _candidate_frontmatter(root: Path) -> tuple[str | None, str | None]:
    skill_md = root / "generated-skill" / "SKILL.md"
    try:
        content = skill_md.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None, None
    name = skill_name_from_markdown(content)
    description: str | None = None
    if content.startswith("---"):
        lines = content.splitlines()
        end = next(
            (index for index in range(1, len(lines)) if lines[index].strip() == "---"),
            -1,
        )
        if end > 0:
            try:
                import yaml

                parsed: Any = yaml.safe_load("\n".join(lines[1:end]))
            except Exception:  # noqa: BLE001 - invalid metadata remains a validation finding
                parsed = None
            if isinstance(parsed, dict):
                raw_description = parsed.get("description")
                if isinstance(raw_description, str) and raw_description.strip():
                    description = raw_description.strip()
    return name, description


@dataclass(frozen=True, slots=True)
class PackageIdentity:
    """Typed workspace-name to package-name resolution result."""

    requested_name: str
    resolved_name: str
    candidate_name: str | None
    candidate_description: str | None
    requested_is_temporary: bool
    source: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "requestedName": self.requested_name,
            "resolvedName": self.resolved_name,
            "candidateName": self.candidate_name,
            "requestedNameTemporary": self.requested_is_temporary,
            "source": self.source,
        }


def resolve_package_identity(
    root: Path,
    requested_name: str | None,
) -> PackageIdentity:
    """Resolve publication identity without weakening an explicit real name.

    A semantic requested name remains authoritative and a mismatching
    frontmatter name is left for deterministic validation to reject.  Only a
    temporary/default request delegates publication identity to the submitted
    candidate.
    """

    normalized_requested = normalize_skill_slug(str(requested_name or ""))
    requested_is_temporary = is_temporary_package_name(requested_name)
    raw_candidate_name, candidate_description = _candidate_frontmatter(root)
    candidate_name = (
        normalize_skill_slug(raw_candidate_name)
        if isinstance(raw_candidate_name, str) and raw_candidate_name.strip()
        else None
    )
    if (
        requested_is_temporary
        and candidate_name
        and not is_temporary_package_name(candidate_name)
    ):
        resolved_name = candidate_name
        source = "candidate_frontmatter"
    else:
        resolved_name = normalized_requested
        source = "requested_name"
    return PackageIdentity(
        requested_name=normalized_requested,
        resolved_name=resolved_name,
        candidate_name=candidate_name,
        candidate_description=candidate_description,
        requested_is_temporary=requested_is_temporary,
        source=source,
    )


__all__ = [
    "PackageIdentity",
    "TEMPORARY_PACKAGE_NAMES",
    "TEMPORARY_PACKAGE_PREFIXES",
    "is_temporary_package_name",
    "resolve_package_identity",
]
