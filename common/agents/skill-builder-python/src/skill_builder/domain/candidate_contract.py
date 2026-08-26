# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Single authoritative file surface for one generated Skill draft."""

from __future__ import annotations

from pathlib import PurePosixPath

IGNORED_PACKAGE_PARTS = frozenset({
    ".git", "__pycache__", ".venv", "venv", "dist", "out", "__MACOSX",
})
EXPORT_ALLOWED_ROOT_DIRS = frozenset({
    "agents", "references", "scripts", "fixtures", "assets",
})
EXPORT_ALLOWED_ROOT_FILES = frozenset({
    "SKILL.md",
    "requirements.txt",
    "pyproject.toml",
    "package.json",
    "package-lock.json",
    "pnpm-lock.yaml",
    "yarn.lock",
})
EXPORT_IGNORED_SUFFIXES = (".pyc", ".pyo", ".log", ".trace.zip", "~")
TEXT_ARTIFACT_SUFFIXES = (
    ".md", ".txt", ".yaml", ".yml", ".json", ".py", ".js", ".ts", ".mjs",
)


REQUIRED_EXPORT_PATHS = frozenset({"generated-skill/SKILL.md"})
REQUIRED_CANDIDATE_PATHS = REQUIRED_EXPORT_PATHS

# Optional references remain useful progress signals, but they are not a
# universal package requirement.  A generated Skill may use any meaningful
# reference filenames, and the self-check separately validates broken links.
CANDIDATE_PROGRESS_PATHS = tuple(sorted({
    *REQUIRED_CANDIDATE_PATHS,
    "generated-skill/references/materials.md",
    "generated-skill/references/extraction-summary.md",
}))


def _normalized_package_path_parts(relative_path: str) -> tuple[str, tuple[str, ...]] | None:
    normalized = str(relative_path or "").replace("\\", "/").strip()
    while normalized.startswith("./"):
        normalized = normalized[2:]
    if normalized.startswith("/"):
        return False
    if normalized.startswith("generated-skill/"):
        normalized = normalized.removeprefix("generated-skill/")
    candidate = PurePosixPath(normalized)
    parts = candidate.parts
    if not parts or candidate.is_absolute() or ".." in parts:
        return None
    return normalized, parts


def _package_path_is_ephemeral(normalized: str, parts: tuple[str, ...]) -> bool:
    if any(part in IGNORED_PACKAGE_PARTS or part.startswith(".") for part in parts):
        return True
    name = parts[-1]
    if name.endswith(EXPORT_IGNORED_SUFFIXES):
        return True
    if name in {".DS_Store", "plugin.yaml"}:
        return True
    return False


def export_package_path_allowed(relative_path: str) -> bool:
    """Return whether one generated-skill file belongs to the portable candidate.

    Signing, edit tracking, download and publication share this allowlist.
    Runtime caches and platform helper files are excluded so they cannot create
    receipt drift.
    """

    parsed = _normalized_package_path_parts(relative_path)
    if parsed is None:
        return False
    normalized, parts = parsed
    if _package_path_is_ephemeral(normalized, parts):
        return False
    if normalized in REQUIRED_EXPORT_PATHS:
        return True
    if normalized in EXPORT_ALLOWED_ROOT_FILES:
        return True
    return parts[0] in EXPORT_ALLOWED_ROOT_DIRS


__all__ = [
    "CANDIDATE_PROGRESS_PATHS",
    "EXPORT_ALLOWED_ROOT_DIRS",
    "EXPORT_ALLOWED_ROOT_FILES",
    "IGNORED_PACKAGE_PARTS",
    "REQUIRED_CANDIDATE_PATHS",
    "REQUIRED_EXPORT_PATHS",
    "TEXT_ARTIFACT_SUFFIXES",
    "export_package_path_allowed",
]
