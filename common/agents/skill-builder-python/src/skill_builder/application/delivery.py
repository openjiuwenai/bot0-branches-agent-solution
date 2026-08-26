# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Draft export and validated publication helpers."""

from __future__ import annotations

from skill_builder.application.artifact_digest import skill_artifact_sha256
from skill_builder.application.agent_submission import ensure_workspace_package_revision
from skill_builder.application.draft_package_validation import validate_draft_package
from skill_builder.application.package_identity import resolve_package_identity
from skill_builder.application.package_builder import (
    SkillPackageBuildError,
    build_skill_export_archive,
    build_skill_publish_archive,
    resolve_skill_package_metadata,
)
from skill_builder.domain.execution import SkillBuilderExecution


def build_execution_export_archive(execution: SkillBuilderExecution) -> tuple[bytes, str, str]:
    require_exportable_draft(execution)
    metadata = _execution_package_metadata(execution)
    return build_skill_export_archive(execution.generated_root, metadata)


def build_execution_publish_archive(
    execution: SkillBuilderExecution,
    *,
    author: str,
) -> tuple[bytes, str, str]:
    require_unchanged_validated_artifact(execution)
    metadata = _execution_package_metadata(execution)
    return build_skill_publish_archive(execution.generated_root, metadata, author=author)


def require_unchanged_validated_artifact(
    execution: SkillBuilderExecution,
) -> None:
    if execution.validation_result is None:
        raise SkillPackageBuildError("artifact_not_validated", "当前 Skill 尚未执行独立验收，不能构建交付包")
    if not execution.artifact_sha256:
        raise SkillPackageBuildError("artifact_not_validated", "当前 Skill 没有绑定验收产物摘要")
    current_sha256 = skill_artifact_sha256(execution.generated_root)
    if current_sha256 != execution.artifact_sha256:
        raise SkillPackageBuildError(
            "artifact_changed_after_validation",
            "Skill 产物在验收后发生变化，请重新执行 validate 后再打包",
        )
    if not execution.publishable:
        raise SkillPackageBuildError("workspace_not_publishable", "当前 Skill 尚未通过独立验收，不能构建发布包")


def require_exportable_draft(execution: SkillBuilderExecution) -> None:
    validation = validate_draft_package(execution.input.root)
    if not validation.ok:
        raise SkillPackageBuildError(
            "draft_not_ready",
            "当前 Skill 尚未形成合法、可打包的草稿",
        )
    revision = ensure_workspace_package_revision(execution.input.root)
    if not revision.get("ok"):
        raise SkillPackageBuildError(
            "draft_revision_unavailable",
            "当前 Skill 草稿无法绑定 PackageRevision",
        )


def _execution_package_metadata(execution: SkillBuilderExecution):
    identity = resolve_package_identity(execution.input.root, execution.input.skill_name)
    return resolve_skill_package_metadata(
        skill_name=identity.resolved_name,
        fallback_skill_name=identity.resolved_name,
        display_name=execution.input.display_name,
        description=identity.candidate_description or execution.input.description,
        version=execution.input.version,
        tags=execution.input.tags,
    )


__all__ = [
    "build_execution_export_archive",
    "build_execution_publish_archive",
    "require_exportable_draft",
    "require_unchanged_validated_artifact",
]
