"""Filesystem-backed skill storage for POST /api/v1/skills.

Each agent_name maps to a directory under skills_root (or per-agent skills_dir).
Skill layout matches openjiuwen convention: {skill_name}/SKILL.md

Also provides :class:`CompositeSkillStore` to dispatch per-agent to local FS
or jiuwenbox backends.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Protocol, runtime_checkable

import structlog

logger = structlog.get_logger(__name__)

_SAFE_NAME = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_-]*$")
_SKILL_MD = "SKILL.md"
_META_DIR = ".meta"
_SNAPSHOT_SUFFIX = ".snapshot"
_MSG_NO_SNAPSHOT = "未找到快照：该 Skill 未被更新过"
_MSG_SKILL_NOT_FOUND = "Skill 不存在"


class SkillStoreError(Exception):
    """Base error for skill store operations."""


class AgentNotFoundError(SkillStoreError):
    """Unknown agent_name."""


class SkillNotFoundError(SkillStoreError):
    """Skill directory or SKILL.md missing."""


class InvalidSkillNameError(SkillStoreError):
    """skill_name failed validation."""


@dataclass(frozen=True)
class SkillSummary:
    name: str


@dataclass(frozen=True)
class SkillContent:
    skill_name: str
    content: str
    revision: str


@dataclass(frozen=True)
class SkillUpdateResult:
    success: bool
    skill_name: str
    revision: str
    message: str | None = None


@dataclass(frozen=True)
class SkillRestoreResult:
    skill_name: str
    success: bool
    message: str | None = None


@runtime_checkable
class SkillStoreProtocol(Protocol):
    """Shared surface used by API routes (local FS or jiuwenbox)."""

    def list_skills(self, agent_name: str) -> list[SkillSummary]: ...

    def read_skill(self, agent_name: str, skill_name: str) -> SkillContent: ...

    def update_skill(
        self,
        agent_name: str,
        skill_name: str,
        content: str,
    ) -> SkillUpdateResult: ...

    def restore_skills(
        self,
        agent_name: str,
        skill_names: list[str],
    ) -> list[SkillRestoreResult]: ...

    def get_revision(self, agent_name: str, skill_name: str) -> str | None: ...


class CompositeSkillStore:
    """Dispatch skill operations to a per-agent backend."""

    def __init__(self, backends: dict[str, SkillStoreProtocol]) -> None:
        if not backends:
            raise ValueError("CompositeSkillStore requires at least one backend")
        self._backends = backends

    def list_skills(self, agent_name: str) -> list[SkillSummary]:
        return self._backend(agent_name).list_skills(agent_name)

    def read_skill(self, agent_name: str, skill_name: str) -> SkillContent:
        return self._backend(agent_name).read_skill(agent_name, skill_name)

    def update_skill(
        self,
        agent_name: str,
        skill_name: str,
        content: str,
    ) -> SkillUpdateResult:
        return self._backend(agent_name).update_skill(agent_name, skill_name, content)

    def restore_skills(
        self,
        agent_name: str,
        skill_names: list[str],
    ) -> list[SkillRestoreResult]:
        return self._backend(agent_name).restore_skills(agent_name, skill_names)

    def get_revision(self, agent_name: str, skill_name: str) -> str | None:
        return self._backend(agent_name).get_revision(agent_name, skill_name)

    def _backend(self, agent_name: str) -> SkillStoreProtocol:
        if agent_name not in self._backends:
            raise AgentNotFoundError(f"Agent '{agent_name}' not found")
        return self._backends[agent_name]


class SkillStore:
    """Read/write skills on a shared filesystem (container bind mounts)."""

    def __init__(
        self,
        *,
        skills_root: Path,
        agent_skills_dirs: dict[str, Path],
    ) -> None:
        self._skills_root = skills_root
        self._agent_skills_dirs = agent_skills_dirs

    @classmethod
    def from_agent_configs(
        cls,
        *,
        skills_root: Path,
        agents: list,
    ) -> SkillStore:
        """Build store from AdapterConfig.agents (AgentEntryConfig list)."""
        mapping: dict[str, Path] = {}
        for agent in agents:
            name = agent.name
            raw_dir = getattr(agent, "skills_dir", None) or (skills_root / name)
            mapping[name] = Path(raw_dir)
        return cls(skills_root=skills_root, agent_skills_dirs=mapping)

    def list_skills(self, agent_name: str) -> list[SkillSummary]:
        agent_dir = self._agent_dir(agent_name)
        if not agent_dir.is_dir():
            return []

        skills: list[SkillSummary] = []
        for child in sorted(agent_dir.iterdir()):
            if not child.is_dir() or child.name.startswith("."):
                continue
            if (child / _SKILL_MD).is_file():
                skills.append(SkillSummary(name=child.name))
        return skills

    def read_skill(self, agent_name: str, skill_name: str) -> SkillContent:
        skill_path = self._skill_md_path(agent_name, skill_name)
        if not skill_path.is_file():
            raise SkillNotFoundError(
                f"Skill '{skill_name}' not found for agent '{agent_name}'"
            )
        content = skill_path.read_text(encoding="utf-8")
        revision = self._content_revision(content)
        return SkillContent(
            skill_name=skill_name,
            content=content,
            revision=revision,
        )

    def update_skill(
        self,
        agent_name: str,
        skill_name: str,
        content: str,
    ) -> SkillUpdateResult:
        skill_path = self._skill_md_path(agent_name, skill_name)
        if not skill_path.is_file():
            raise SkillNotFoundError(
                f"Skill '{skill_name}' not found for agent '{agent_name}'"
            )

        self._ensure_snapshot(agent_name, skill_name)

        skill_dir = skill_path.parent
        tmp_path = skill_dir / f".{_SKILL_MD}.{os.getpid()}.tmp"

        try:
            tmp_path.write_text(content, encoding="utf-8")
            os.replace(tmp_path, skill_path)
        finally:
            if tmp_path.exists():
                tmp_path.unlink(missing_ok=True)

        revision = self._content_revision(content)
        agent_dir = self._agent_dir(agent_name)
        self._write_meta(agent_dir, skill_name, revision)

        logger.info(
            "skill_updated",
            agent_name=agent_name,
            skill_name=skill_name,
            revision=revision,
            path=str(skill_path),
        )
        return SkillUpdateResult(
            success=True,
            skill_name=skill_name,
            revision=revision,
        )

    def restore_skills(
        self,
        agent_name: str,
        skill_names: list[str],
    ) -> list[SkillRestoreResult]:
        """Restore each skill from its pre-update snapshot (batch, per-item errors)."""
        results: list[SkillRestoreResult] = []
        for raw_name in skill_names:
            try:
                self._validate_name(raw_name, label="skill_name")
            except InvalidSkillNameError as exc:
                results.append(
                    SkillRestoreResult(
                        skill_name=raw_name,
                        success=False,
                        message=str(exc),
                    )
                )
                continue

            skill_name = raw_name
            skill_path = self._skill_md_path(agent_name, skill_name)
            if not skill_path.is_file():
                results.append(
                    SkillRestoreResult(
                        skill_name=skill_name,
                        success=False,
                        message=_MSG_SKILL_NOT_FOUND,
                    )
                )
                continue

            snapshot_path = self._snapshot_path(agent_name, skill_name)
            if not snapshot_path.is_file():
                results.append(
                    SkillRestoreResult(
                        skill_name=skill_name,
                        success=False,
                        message=_MSG_NO_SNAPSHOT,
                    )
                )
                continue

            content = snapshot_path.read_text(encoding="utf-8")
            skill_dir = skill_path.parent
            tmp_path = skill_dir / f".{_SKILL_MD}.{os.getpid()}.tmp"
            try:
                tmp_path.write_text(content, encoding="utf-8")
                os.replace(tmp_path, skill_path)
            finally:
                if tmp_path.exists():
                    tmp_path.unlink(missing_ok=True)

            revision = self._content_revision(content)
            agent_dir = self._agent_dir(agent_name)
            self._write_meta(agent_dir, skill_name, revision)

            logger.info(
                "skill_restored",
                agent_name=agent_name,
                skill_name=skill_name,
                revision=revision,
                path=str(skill_path),
            )
            results.append(SkillRestoreResult(skill_name=skill_name, success=True))

        return results

    def get_revision(self, agent_name: str, skill_name: str) -> str | None:
        meta_path = self._meta_path(agent_name, skill_name)
        if not meta_path.is_file():
            return None
        try:
            data = json.loads(meta_path.read_text(encoding="utf-8"))
            rev = data.get("revision")
            return str(rev) if rev else None
        except (json.JSONDecodeError, OSError):
            return None

    def _agent_dir(self, agent_name: str) -> Path:
        self._validate_name(agent_name, label="agent_name")
        if agent_name not in self._agent_skills_dirs:
            raise AgentNotFoundError(f"Agent '{agent_name}' not found")
        return self._agent_skills_dirs[agent_name]

    def _skill_md_path(self, agent_name: str, skill_name: str) -> Path:
        self._validate_name(skill_name, label="skill_name")
        agent_dir = self._agent_dir(agent_name)
        return self._resolve_under(agent_dir, skill_name) / _SKILL_MD

    @staticmethod
    def _validate_name(value: str, *, label: str) -> None:
        if not value or not _SAFE_NAME.match(value):
            raise InvalidSkillNameError(f"Invalid {label}: {value!r}")

    def _resolve_under(self, base: Path, name: str) -> Path:
        self._validate_name(name, label="skill_name")
        resolved_base = base.resolve()
        target = (base / name).resolve()
        if not str(target).startswith(str(resolved_base)):
            raise InvalidSkillNameError(f"Path escapes agent skills directory: {name!r}")
        return target

    def _meta_path(self, agent_name: str, skill_name: str) -> Path:
        agent_dir = self._agent_dir(agent_name)
        meta_dir = agent_dir / _META_DIR
        return meta_dir / f"{skill_name}.json"

    def _snapshot_path(self, agent_name: str, skill_name: str) -> Path:
        agent_dir = self._agent_dir(agent_name)
        meta_dir = agent_dir / _META_DIR
        return meta_dir / f"{skill_name}{_SNAPSHOT_SUFFIX}"

    def _ensure_snapshot(self, agent_name: str, skill_name: str) -> None:
        """Capture pre-update content once; snapshot survives restore (idempotent)."""
        snapshot_path = self._snapshot_path(agent_name, skill_name)
        if snapshot_path.is_file():
            return
        current = self.read_skill(agent_name, skill_name).content
        meta_dir = snapshot_path.parent
        meta_dir.mkdir(parents=True, exist_ok=True)
        tmp = snapshot_path.with_suffix(".tmp")
        tmp.write_text(current, encoding="utf-8")
        os.replace(tmp, snapshot_path)

    def _write_meta(self, agent_dir: Path, skill_name: str, revision: str) -> None:
        meta_dir = agent_dir / _META_DIR
        meta_dir.mkdir(parents=True, exist_ok=True)
        payload = {
            "skill_name": skill_name,
            "revision": revision,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        meta_file = meta_dir / f"{skill_name}.json"
        tmp = meta_file.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        os.replace(tmp, meta_file)

    @staticmethod
    def _content_revision(content: str) -> str:
        return hashlib.sha256(content.encode("utf-8")).hexdigest()
