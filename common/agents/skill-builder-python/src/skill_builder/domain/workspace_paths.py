# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Shared workspace path policy for generated Skill packages.

The local accessor, the Jiuwenbox-backed accessor, and the Agent tool layer
must enforce the same package boundary.  Keeping the reserved roots here
prevents an adapter from accidentally accepting a path that another adapter
rejects or cannot later delete.
"""

from __future__ import annotations

from pathlib import Path


SKILL_PACKAGE_FORBIDDEN_ROOTS = frozenset(
    {
        ".skill-builder",
        "generated-skill",
        "inputs",
        "output",
        "validation",
        "workspace",
    }
)

SKILL_PACKAGE_PLATFORM_ROOT_FILES = frozenset(
    {
        "artifact_manifest.json",
        "author_handoff.json",
        "implementation_plan.json",
        "decision_overrides.json",
        "material_facts.json",
        "scenario_contract.json",
        "scenario_summary.md",
        "scenario_understanding.md",
    }
)

AGENT_READABLE_WORKSPACE_ROOTS = frozenset(
    {"inputs", "generated-skill", "validation", "workspace"}
)
AGENT_READABLE_PRIVATE_PREFIX = ".skill-builder/skills"

SCENARIO_READ_PATHS = frozenset({"workspace/material_digest.md"})


def split_generated_skill_path(path: str) -> tuple[str, bool]:
    """Return the package-relative path and whether the package root was explicit."""

    normalized = str(path or "").replace("\\", "/").strip("/")
    explicit = normalized == "generated-skill" or normalized.startswith("generated-skill/")
    if normalized == "generated-skill":
        return ".", True
    if explicit:
        normalized = normalized.removeprefix("generated-skill/")
    return normalized or ".", explicit


def forbidden_skill_package_root(path: str) -> str | None:
    """Return the reserved first path component, if the package path is invalid."""

    relative, _explicit = split_generated_skill_path(path)
    first = relative.split("/", 1)[0]
    return first if first in SKILL_PACKAGE_FORBIDDEN_ROOTS else None


def forbidden_skill_package_path(path: str) -> str | None:
    """Return a reserved package root or platform-only root filename."""

    relative, _explicit = split_generated_skill_path(path)
    forbidden_root = forbidden_skill_package_root(relative)
    if forbidden_root:
        return forbidden_root
    if "/" not in relative and relative in SKILL_PACKAGE_PLATFORM_ROOT_FILES:
        return relative
    return None


def is_agent_readable_workspace_path(path: str) -> bool:
    """Return whether an Agent workspace tool may list/read ``path``.

    ``.skill-builder`` is platform-private except for installed internal skill
    resources. Local and sandbox accessors share this policy so worker requests,
    timing files and rejected candidates cannot leak through one adapter.
    """

    normalized = str(path or "").replace("\\", "/").strip("/") or "."
    first = normalized.split("/", 1)[0]
    if first in AGENT_READABLE_WORKSPACE_ROOTS:
        return True
    return normalized == AGENT_READABLE_PRIVATE_PREFIX or normalized.startswith(
        f"{AGENT_READABLE_PRIVATE_PREFIX}/"
    )


def normalize_phase_workspace_read_path(phase: str, path: str) -> str:
    """Normalize model-facing package reads to the workspace package root.

    ``write_skill_file`` and ``delete_skill_file`` use exported
    package-relative paths such as ``scripts/run.py``.  Requiring Author to add
    ``generated-skill/`` only when reading the same file creates two path
    dialects inside one tool protocol. Author and draft revision therefore accept the
    same package-relative spelling and project it to the durable Draft
    Workspace before applying the normal phase policy.

    Reserved workspace roots and platform-owned root files are never projected;
    they retain their explicit paths and are still accepted or rejected by
    :func:`phase_workspace_path_allowed`.
    """

    normalized = str(path or "").replace("\\", "/").strip("/") or "."
    while normalized.startswith("./"):
        normalized = normalized[2:]
    if _normalized_phase(phase) not in {
        "author",
        "author_build",
        "author_validate",
        "repair",
    }:
        return normalized
    if is_agent_readable_workspace_path(normalized):
        return normalized
    if normalized == "." or ".." in normalized.split("/"):
        return normalized
    if forbidden_skill_package_path(normalized) is not None:
        return normalized
    relative, _explicit = split_generated_skill_path(normalized)
    if relative == ".":
        return normalized
    return f"generated-skill/{relative}"


def _normalized_phase(value: str) -> str:
    normalized = str(value or "").strip().lower()
    if normalized in {"initial", "workflow"}:
        return "scenario"
    return normalized


def phase_workspace_path_allowed(
    phase: str,
    path: str,
    *,
    operation: str = "read",
    workspace_root: Path | str | None = None,
) -> bool:
    """Return whether one Agent phase may observe a workspace path.

    This is the single phase policy used by the tool facade and concrete
    workspace adapters. Scenario and Author are distinct lifecycle phases with
    an intentionally one-way handoff: Scenario reads source material; Author
    reads structured contracts and the candidate package.
    """

    normalized_path = str(path or "").replace("\\", "/").strip("/") or "."
    while normalized_path.startswith("./"):
        normalized_path = normalized_path[2:]
    normalized_phase = _normalized_phase(phase)
    normalized_operation = str(operation or "read").strip().lower()
    if not is_agent_readable_workspace_path(normalized_path):
        return False
    if normalized_phase in {"", "chat", "edit"}:
        return True
    if normalized_path == AGENT_READABLE_PRIVATE_PREFIX or normalized_path.startswith(
        f"{AGENT_READABLE_PRIVATE_PREFIX}/"
    ):
        return True
    if normalized_phase == "scenario":
        if normalized_path == "inputs" or normalized_path.startswith("inputs/"):
            return True
        if normalized_path in SCENARIO_READ_PATHS:
            return True
        return normalized_operation == "list" and normalized_path == "workspace"
    if normalized_phase in {"author", "author_build"}:
        return normalized_path.split("/", 1)[0] in {
            "inputs",
            "generated-skill",
            "validation",
            "workspace",
        }
    if normalized_phase == "author_validate":
        if normalized_path in {
            "generated-skill/scripts/self_check.py",
            "generated-skill/scripts/run_offline_test.py",
            "generated-skill/scripts/offline_test.py",
        } or normalized_path.startswith("generated-skill/fixtures/sample-input"):
            return False
        return normalized_path.split("/", 1)[0] in {
            "generated-skill",
            "validation",
            "workspace",
        }
    if normalized_phase == "repair":
        return normalized_path.split("/", 1)[0] in {
            "generated-skill",
            "validation",
            "workspace",
        }
    return False


def phase_workspace_list_entry_allowed(
    phase: str,
    path: str,
    *,
    is_dir: bool,
    workspace_root: Path | str | None = None,
) -> bool:
    """Filter a directory listing with the same phase contract as reads."""

    if phase_workspace_path_allowed(
        phase,
        path,
        operation="list" if is_dir else "read",
        workspace_root=workspace_root,
    ):
        return True
    normalized_path = str(path or "").replace("\\", "/").strip("/")
    while normalized_path.startswith("./"):
        normalized_path = normalized_path[2:]
    normalized_phase = _normalized_phase(phase)
    if not is_dir:
        return False
    if normalized_phase == "scenario":
        return any(value.startswith(f"{normalized_path}/") for value in SCENARIO_READ_PATHS)
    if normalized_phase in {"author", "author_build"}:
        return normalized_path.split("/", 1)[0] in {
            "inputs",
            "generated-skill",
            "validation",
            "workspace",
        }
    if normalized_phase == "author_validate":
        return normalized_path.split("/", 1)[0] in {
            "generated-skill",
            "validation",
            "workspace",
        }
    if normalized_phase == "repair":
        return normalized_path.split("/", 1)[0] in {
            "generated-skill",
            "validation",
            "workspace",
        }
    return False

__all__ = [
    "AGENT_READABLE_PRIVATE_PREFIX",
    "AGENT_READABLE_WORKSPACE_ROOTS",
    "SKILL_PACKAGE_FORBIDDEN_ROOTS",
    "SKILL_PACKAGE_PLATFORM_ROOT_FILES",
    "SCENARIO_READ_PATHS",
    "forbidden_skill_package_path",
    "forbidden_skill_package_root",
    "is_agent_readable_workspace_path",
    "normalize_phase_workspace_read_path",
    "phase_workspace_list_entry_allowed",
    "phase_workspace_path_allowed",
    "split_generated_skill_path",
]
