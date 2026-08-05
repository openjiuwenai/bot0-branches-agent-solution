"""Jiuwenbox-backed skill storage: SKILL.md lives inside the sandbox FS.

Snapshots / revision meta stay on the Adapter local filesystem so restore
does not depend on sandbox persistence. This replaces the three-way host
bind-mount model for skill hot-update when EDPAgent runs in sandbox mode.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path
from typing import TypeVar

import structlog

from agent_adapter.jiuwenbox_client import JiuwenBoxClient, JiuwenBoxClientError
from agent_adapter.sandbox_resolve import (
    SandboxResolveError,
    SandboxResolveMode,
    resolve_sandbox_id,
)
from agent_adapter.skill_store import (
    AgentNotFoundError,
    InvalidSkillNameError,
    SandboxUnavailableError,
    SkillContent,
    SkillNotFoundError,
    SkillRestoreResult,
    SkillStoreError,
    SkillSummary,
    SkillUpdateResult,
)

logger = structlog.get_logger(__name__)

_SAFE_NAME = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_-]*$")
_SKILL_MD = "SKILL.md"
_META_DIR = ".meta"
_SNAPSHOT_SUFFIX = ".snapshot"
_MSG_NO_SNAPSHOT = "未找到快照：该 Skill 未被更新过"
_MSG_SKILL_NOT_FOUND = "Skill 不存在"
_STALE_SANDBOX_STATUS = frozenset({404, 409})

T = TypeVar("T")


class JiuwenBoxSkillStore:
    """SkillStore-compatible backend that reads/writes via jiuwenbox file API."""

    def __init__(
        self,
        *,
        agent_names: set[str],
        client: JiuwenBoxClient,
        remote_skills_dir: str = "/tmp/skills",
        local_meta_root: Path,
        sandbox_id: str | None = None,
        sandbox_id_resolve: SandboxResolveMode = "from_logs",
        log_dir: str | None = None,
        log_pattern: str = "process_*.log",
        agent_remote_dirs: dict[str, str] | None = None,
        agent_meta_dirs: dict[str, Path] | None = None,
        agent_log_dirs: dict[str, str | None] | None = None,
        agent_sandbox_ids: dict[str, str | None] | None = None,
        agent_resolve_modes: dict[str, SandboxResolveMode] | None = None,
    ) -> None:
        self._agent_names = agent_names
        self._client = client
        self._remote_skills_dir = remote_skills_dir.rstrip("/") or "/tmp/skills"
        self._local_meta_root = local_meta_root
        self._sandbox_id = sandbox_id
        self._sandbox_id_resolve: SandboxResolveMode = sandbox_id_resolve
        self._log_dir = log_dir
        self._log_pattern = log_pattern
        self._agent_remote_dirs = agent_remote_dirs or {}
        self._agent_meta_dirs = agent_meta_dirs or {}
        self._agent_log_dirs = agent_log_dirs or {}
        self._agent_sandbox_ids = agent_sandbox_ids or {}
        self._agent_resolve_modes = agent_resolve_modes or {}
        # Per-agent cache; cleared on stale sandbox responses (404/409).
        self._cached_sandbox_ids: dict[str, str] = {}

    def list_skills(self, agent_name: str) -> list[SkillSummary]:
        self._ensure_agent(agent_name)
        remote_root = self._remote_root(agent_name)
        try:
            items = self._call_sandbox(
                agent_name,
                lambda sid: self._client.list_files(
                    sid,
                    remote_root,
                    recursive=False,
                    include_files=False,
                    include_dirs=True,
                ),
            )
        except JiuwenBoxClientError as exc:
            if exc.status_code in _STALE_SANDBOX_STATUS:
                return []
            raise SkillStoreError(str(exc)) from exc

        skills: list[SkillSummary] = []
        for item in items:
            name = str(item.get("name") or "")
            if not name or name.startswith("."):
                continue
            if not _SAFE_NAME.match(name):
                continue
            # Trust safe directory names (avoid N+1 download of each SKILL.md).
            skills.append(SkillSummary(name=name))
        return sorted(skills, key=lambda s: s.name)

    def read_skill(self, agent_name: str, skill_name: str) -> SkillContent:
        self._validate_name(skill_name, label="skill_name")
        self._ensure_agent(agent_name)
        path = self._skill_md_remote(agent_name, skill_name)
        try:
            raw = self._call_sandbox(
                agent_name,
                lambda sid: self._client.download_file(sid, path),
            )
        except JiuwenBoxClientError as exc:
            if exc.status_code == 404:
                # Same HTTP status for missing file vs dead sandbox — distinguish.
                if self._agent_sandbox_unavailable(agent_name):
                    raise SandboxUnavailableError(
                        f"Sandbox unavailable for agent '{agent_name}'"
                    ) from exc
                raise SkillNotFoundError(
                    f"Skill '{skill_name}' not found for agent '{agent_name}'"
                ) from exc
            raise SkillStoreError(str(exc)) from exc
        content = raw.decode("utf-8")
        return SkillContent(
            skill_name=skill_name,
            content=content,
            revision=self._content_revision(content),
        )

    def update_skill(
        self,
        agent_name: str,
        skill_name: str,
        content: str,
    ) -> SkillUpdateResult:
        self._validate_name(skill_name, label="skill_name")
        self._ensure_agent(agent_name)
        # One download: existence check + snapshot source.
        existing = self.read_skill(agent_name, skill_name)
        self._ensure_snapshot(agent_name, skill_name, content=existing.content)

        path = self._skill_md_remote(agent_name, skill_name)
        try:
            self._call_sandbox(
                agent_name,
                lambda sid: self._client.upload_file(
                    sid,
                    path,
                    content.encode("utf-8"),
                    file_name=_SKILL_MD,
                ),
            )
        except JiuwenBoxClientError as exc:
            raise SkillStoreError(str(exc)) from exc

        revision = self._content_revision(content)
        self._write_meta(agent_name, skill_name, revision)
        logger.info(
            "skill_updated_jiuwenbox",
            agent_name=agent_name,
            skill_name=skill_name,
            revision=revision,
            sandbox_id=self._cached_sandbox_ids.get(agent_name),
            path=path,
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
        results: list[SkillRestoreResult] = []
        for raw_name in skill_names:
            try:
                self._validate_name(raw_name, label="skill_name")
            except InvalidSkillNameError as exc:
                results.append(
                    SkillRestoreResult(skill_name=raw_name, success=False, message=str(exc))
                )
                continue

            skill_name = raw_name
            try:
                self.read_skill(agent_name, skill_name)
            except SkillNotFoundError:
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
            path = self._skill_md_remote(agent_name, skill_name)
            try:
                self._call_sandbox(
                    agent_name,
                    lambda sid, p=path, body=content.encode("utf-8"): self._client.upload_file(
                        sid,
                        p,
                        body,
                        file_name=_SKILL_MD,
                    ),
                )
            except JiuwenBoxClientError as exc:
                results.append(
                    SkillRestoreResult(
                        skill_name=skill_name,
                        success=False,
                        message=str(exc),
                    )
                )
                continue

            revision = self._content_revision(content)
            self._write_meta(agent_name, skill_name, revision)
            logger.info(
                "skill_restored_jiuwenbox",
                agent_name=agent_name,
                skill_name=skill_name,
                revision=revision,
                sandbox_id=self._cached_sandbox_ids.get(agent_name),
                path=path,
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

    def invalidate_sandbox_cache(self, agent_name: str | None = None) -> None:
        if agent_name is None:
            self._cached_sandbox_ids.clear()
        else:
            self._cached_sandbox_ids.pop(agent_name, None)

    def _call_sandbox(self, agent_name: str, fn: Callable[[str], T]) -> T:
        """Run a jiuwenbox call; on stale sandbox id, drop cache and retry once.

        Download/list 404 often means the *file* is missing, not the sandbox.
        Only retry when the cached sandbox is no longer ready (or status is 409).
        """
        sandbox_id = self._resolve(agent_name)
        try:
            return fn(sandbox_id)
        except JiuwenBoxClientError as exc:
            if exc.status_code not in _STALE_SANDBOX_STATUS:
                raise
            mode = self._agent_resolve_modes.get(agent_name, self._sandbox_id_resolve)
            if mode == "fixed":
                raise
            if not self._sandbox_looks_stale(sandbox_id, status_code=exc.status_code):
                raise
            logger.warning(
                "sandbox_id_stale_retry",
                agent_name=agent_name,
                sandbox_id=sandbox_id,
                status_code=exc.status_code,
            )
            self.invalidate_sandbox_cache(agent_name)
            sandbox_id = self._resolve(agent_name, force=True)
            return fn(sandbox_id)

    def _sandbox_looks_stale(self, sandbox_id: str, *, status_code: int | None) -> bool:
        if status_code == 409:
            return True
        try:
            sandboxes = self._client.list_sandboxes()
        except JiuwenBoxClientError:
            return True
        ready_ids = {
            str(item.get("id"))
            for item in sandboxes
            if isinstance(item, dict) and str(item.get("phase", "")).lower() == "ready"
        }
        return sandbox_id not in ready_ids

    def _resolve(self, agent_name: str, *, force: bool = False) -> str:
        mode = self._agent_resolve_modes.get(agent_name, self._sandbox_id_resolve)
        preferred = self._agent_sandbox_ids.get(agent_name, self._sandbox_id)
        log_dir = self._agent_log_dirs.get(agent_name, self._log_dir)

        if not force and agent_name in self._cached_sandbox_ids:
            return self._cached_sandbox_ids[agent_name]

        if mode == "fixed" and preferred:
            self._cached_sandbox_ids[agent_name] = preferred
            return preferred
        try:
            sid = resolve_sandbox_id(
                client=self._client,
                mode=mode,
                sandbox_id=preferred,
                log_dir=log_dir,
                log_pattern=self._log_pattern,
            )
        except SandboxResolveError as exc:
            raise SandboxUnavailableError(str(exc)) from exc
        self._cached_sandbox_ids[agent_name] = sid
        return sid

    def _agent_sandbox_unavailable(self, agent_name: str) -> bool:
        """True when there is no ready sandbox or the cached id is no longer ready."""
        try:
            sandboxes = self._client.list_sandboxes()
        except JiuwenBoxClientError:
            return True
        ready_ids = {
            str(item.get("id"))
            for item in sandboxes
            if isinstance(item, dict) and str(item.get("phase", "")).lower() == "ready"
        }
        if not ready_ids:
            return True
        cached = self._cached_sandbox_ids.get(agent_name)
        if cached is not None and cached not in ready_ids:
            return True
        return False

    def _remote_root(self, agent_name: str) -> str:
        return (self._agent_remote_dirs.get(agent_name) or self._remote_skills_dir).rstrip("/")

    def _skill_md_remote(self, agent_name: str, skill_name: str) -> str:
        return f"{self._remote_root(agent_name)}/{skill_name}/{_SKILL_MD}"

    def _meta_dir(self, agent_name: str) -> Path:
        custom = self._agent_meta_dirs.get(agent_name)
        if custom is not None:
            return custom / _META_DIR
        return self._local_meta_root / agent_name / _META_DIR

    def _meta_path(self, agent_name: str, skill_name: str) -> Path:
        return self._meta_dir(agent_name) / f"{skill_name}.json"

    def _snapshot_path(self, agent_name: str, skill_name: str) -> Path:
        return self._meta_dir(agent_name) / f"{skill_name}{_SNAPSHOT_SUFFIX}"

    def _ensure_snapshot(
        self,
        agent_name: str,
        skill_name: str,
        *,
        content: str | None = None,
    ) -> None:
        snapshot_path = self._snapshot_path(agent_name, skill_name)
        if snapshot_path.is_file():
            return
        if content is None:
            content = self.read_skill(agent_name, skill_name).content
        meta_dir = snapshot_path.parent
        meta_dir.mkdir(parents=True, exist_ok=True)
        tmp = snapshot_path.with_suffix(".tmp")
        tmp.write_text(content, encoding="utf-8")
        os.replace(tmp, snapshot_path)

    def _write_meta(self, agent_name: str, skill_name: str, revision: str) -> None:
        meta_dir = self._meta_dir(agent_name)
        meta_dir.mkdir(parents=True, exist_ok=True)
        payload = {
            "skill_name": skill_name,
            "revision": revision,
            "updated_at": datetime.now(timezone.utc).isoformat(),
            "backend": "jiuwenbox",
        }
        meta_file = meta_dir / f"{skill_name}.json"
        tmp = meta_file.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        os.replace(tmp, meta_file)

    def _ensure_agent(self, agent_name: str) -> None:
        self._validate_name(agent_name, label="agent_name")
        if agent_name not in self._agent_names:
            raise AgentNotFoundError(f"Agent '{agent_name}' not found")

    @staticmethod
    def _validate_name(value: str, *, label: str) -> None:
        if not value or not _SAFE_NAME.match(value):
            raise InvalidSkillNameError(f"Invalid {label}: {value!r}")

    @staticmethod
    def _content_revision(content: str) -> str:
        return hashlib.sha256(content.encode("utf-8")).hexdigest()
