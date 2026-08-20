"""Orchestrate SkillHub publish/pull against local skill directories."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Literal

from agent_adapter.config import AdapterConfig
from agent_adapter.skill_store import SkillStoreProtocol
from agent_adapter.skillhub.client import SkillHubClient
from agent_adapter.skillhub.errors import SkillHubError, SkillHubValidationError
from agent_adapter.skillhub.packager import build_zip, extract_zip, sha256_hex, validate_skill_directory
from agent_adapter.skillhub.types import DeleteVersionResult, PublishResult, PullResult


class SkillHubService:
    """High-level SkillHub operations for adapter routes."""

    def __init__(self, *, config: AdapterConfig, skill_store: SkillStoreProtocol) -> None:
        if not config.skillhub_enabled:
            raise ValueError("SkillHub integration is not enabled")
        token = _resolve_token(config)
        self._config = config
        self._skill_store = skill_store
        self._client = SkillHubClient(
            base_url=config.skillhub_base_url,
            token=token,
            auth_mode=config.skillhub_auth_mode,
            connect_timeout=config.skillhub_connect_timeout,
            publish_timeout=config.skillhub_publish_timeout,
        )

    def list_hub_skills(
        self,
        *,
        page: int = 1,
        page_size: int = 20,
        keyword: str | None = None,
    ) -> dict:
        return self._client.list_plugins(
            page=page,
            page_size=page_size,
            plugin_type=self._config.skillhub_default_plugin_type,
            search_keyword=keyword,
        )

    def get_hub_version(self, asset_id: str, version: str) -> dict:
        return self._client.get_version(asset_id, version)

    def publish_skill(
        self,
        agent_name: str,
        skill_name: str,
        *,
        plugin_version: str | None = None,
        asset_id: str | None = None,
        version_desc: str | None = None,
        force: bool = False,
    ) -> PublishResult:
        skill_dir = self._skill_dir(agent_name, skill_name)
        validate_skill_directory(skill_dir)
        version = plugin_version or _next_version(
            self._client,
            asset_id=asset_id,
            strategy=self._config.skillhub_version_strategy,
        )
        zip_bytes = build_zip(skill_dir)
        checksum = sha256_hex(zip_bytes)
        local_revision = self._skill_store.get_revision(agent_name, skill_name)
        data = self._client.publish(
            zip_bytes=zip_bytes,
            checksum_sha256=checksum,
            plugin_version=version,
            plugin_id=asset_id,
            version_desc=version_desc,
            force=force,
        )
        return PublishResult(
            asset_id=str(data.get("plugin_id") or data.get("asset_id") or asset_id or ""),
            skill_name=str(data.get("name") or skill_name),
            version=str(data.get("version") or version),
            plugin_type=str(data.get("plugin_type") or self._config.skillhub_default_plugin_type),
            publish_result=_optional_str(data.get("publish_result")),
            moderation_status=_optional_str(data.get("moderation_status")),
            checksum_sha256=checksum,
            version_desc=version_desc,
            local_revision=local_revision,
        )

    def pull_skill(
        self,
        agent_name: str,
        asset_id: str,
        version: str,
        *,
        overwrite: bool = True,
    ) -> PullResult:
        zip_bytes = self._client.download_zip(asset_id, version=version)
        agent_dir = self._agent_skills_dir(agent_name)
        target_dir = extract_zip(zip_bytes, agent_dir, overwrite=overwrite)
        skill_name = target_dir.name
        content = (target_dir / "SKILL.md").read_text(encoding="utf-8")
        revision = sha256_hex(content.encode("utf-8"))
        return PullResult(
            asset_id=asset_id,
            skill_name=skill_name,
            version=version,
            local_path=str(target_dir),
            revision=revision,
        )

    def delete_hub_version(self, asset_id: str, version: str) -> DeleteVersionResult:
        data = self._client.delete_version(asset_id, version)
        return DeleteVersionResult(
            asset_id=asset_id,
            version=version,
            deleted=bool(data.get("deleted", True)),
        )

    def _skill_dir(self, agent_name: str, skill_name: str) -> Path:
        agent_dir = self._agent_skills_dir(agent_name)
        return agent_dir / skill_name

    def _agent_skills_dir(self, agent_name: str) -> Path:
        for agent in self._config.agents:
            if agent.name == agent_name:
                raw = agent.skills_dir or (Path(self._config.skills_root) / agent_name)
                return Path(raw)
        raise SkillHubValidationError(f"Agent '{agent_name}' not found")


def _resolve_token(config: AdapterConfig) -> str:
    if config.skillhub_token:
        return config.skillhub_token.strip()
    env_name = config.skillhub_token_env or "SKILLHUB_TOKEN"
    token = os.environ.get(env_name, "").strip()
    if not token:
        raise SkillHubValidationError(
            f"SkillHub token missing: set ADAPTER_SKILLHUB_TOKEN or env {env_name}"
        )
    return token


def _optional_str(value: object) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _next_version(
    client: SkillHubClient,
    *,
    asset_id: str | None,
    strategy: Literal["patch", "manual"],
) -> str:
    if strategy != "patch":
        raise SkillHubValidationError("plugin_version is required when version_strategy=manual")
    if not asset_id:
        return "1.0.0"
    listing = client.list_plugins(page=1, page_size=1, asset_id=asset_id)
    items = listing.get("items") or []
    if not items:
        return "1.0.0"
    first = items[0]
    if not isinstance(first, dict):
        return "1.0.0"
    latest = str(first.get("latest_version") or first.get("public_latest_version") or "0.0.0")
    return _bump_patch(latest)


def _bump_patch(version: str) -> str:
    parts = version.split(".")
    if len(parts) != 3 or not all(part.isdigit() for part in parts):
        raise SkillHubValidationError(f"Cannot bump non-semver version: {version}")
    major, minor, patch = (int(parts[0]), int(parts[1]), int(parts[2]))
    return f"{major}.{minor}.{patch + 1}"
