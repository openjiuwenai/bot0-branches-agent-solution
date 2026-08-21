"""Typed results for SkillHub publish/pull operations."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class PublishResult:
    asset_id: str
    skill_name: str
    version: str
    plugin_type: str
    publish_result: str | None
    moderation_status: str | None
    checksum_sha256: str
    version_desc: str | None
    local_revision: str | None


@dataclass(frozen=True)
class PullResult:
    asset_id: str
    skill_name: str
    version: str
    local_path: str
    revision: str


@dataclass(frozen=True)
class DeleteVersionResult:
    asset_id: str
    version: str
    deleted: bool
