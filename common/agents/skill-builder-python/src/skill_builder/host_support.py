"""Core-owned support surface for a concrete host adapter.

This module is intentionally separate from :mod:`skill_builder.spi`.
``spi`` contains replaceable extension contracts (Agent, State, HITL,
Workspace and Execution). A host may use this curated support surface for
projection, path and worker wiring, but it must not import ``application`` or
``domain`` modules directly.

The module only re-exports existing Core implementations; it does not create
another rule set or duplicate lifecycle logic.
"""

from pathlib import Path

from skill_builder.application.agent_core import (
    SkillBuilderAgentCoreError,
    SkillBuilderAgentCoreResult,
    SkillBuilderAgentLifecycleError,
    SkillBuilderAgentRuntimeUnavailableError,
    _task_mode_for_run_phase,
    run_skill_builder_agent_core,
)
from skill_builder.application.agent_policy import CANONICAL_EXTRACTION_INSTRUCTION
from skill_builder.application.file_helpers import (
    frontmatter_block,
    is_text_artifact,
    json_text,
    load_yaml_object,
    resolve_safe,
)
from skill_builder.application.generated_metadata import (
    clean_workspace_evaluation_suffix,
    display_name_should_be_localized,
    generated_chinese_workspace_title,
    workspace_title_should_be_localized,
)
from skill_builder.application.generation_checkpoint import (
    ensure_generation_checkpoint,
    reset_generated_outputs,
)
from skill_builder.application.hitl_form_contract import (
    format_hitl_answer_text,
    public_decision_form_fields,
)
from skill_builder.application.execution_state import normalize_bound_decision_form_answer
from skill_builder.application.hitl_forms import _trim_hitl_visible_text
from skill_builder.application.package_builder import (
    DEFAULT_SKILL_DESCRIPTION,
    DEFAULT_SKILL_DISPLAY_NAME,
    DEFAULT_SKILL_TAGS,
    DEFAULT_SKILL_VERSION,
    normalize_skill_slug,
    should_export_skill_path,
)
from skill_builder.application.offline_validation import (
    validation_output_directories,
)
from skill_builder.application.agent_submission import verified_candidate_receipt_status
from skill_builder.application.revision_store import RevisionStore
from skill_builder.domain.contract_decisions import canonical_workspace_relative_path
from skill_builder.domain.workspace_paths import (
    forbidden_skill_package_path,
    forbidden_skill_package_root,
    is_agent_readable_workspace_path,
    normalize_phase_workspace_read_path,
    phase_workspace_list_entry_allowed,
    phase_workspace_path_allowed,
    split_generated_skill_path,
)
from skill_builder.runtime.run_timing import (
    SkillBuilderRunTiming,
    finalize_interrupted_run_timing,
    refresh_running_run_timing,
    set_run_timing_hitl_pause,
)
from skill_builder.runtime.performance_trace import SkillBuilderPerformanceTrace
from skill_builder.runtime.serialization import json_safe


task_mode_for_run_phase = _task_mode_for_run_phase
trim_hitl_visible_text = _trim_hitl_visible_text


def committed_package_revision_available(root: Path) -> bool:
    """Return whether the current generated package matches its Core receipt."""

    return bool(verified_candidate_receipt_status(root).get("ok"))


def validation_revision_available(root: Path) -> bool:
    """Return whether the current package has a compatible validation receipt."""

    return RevisionStore(root).current_validation(compatible=True) is not None


__all__ = [
    "CANONICAL_EXTRACTION_INSTRUCTION",
    "DEFAULT_SKILL_DESCRIPTION",
    "DEFAULT_SKILL_DISPLAY_NAME",
    "DEFAULT_SKILL_TAGS",
    "DEFAULT_SKILL_VERSION",
    "SkillBuilderAgentCoreError",
    "SkillBuilderAgentCoreResult",
    "SkillBuilderAgentLifecycleError",
    "SkillBuilderAgentRuntimeUnavailableError",
    "SkillBuilderRunTiming",
    "SkillBuilderPerformanceTrace",
    "canonical_workspace_relative_path",
    "committed_package_revision_available",
    "clean_workspace_evaluation_suffix",
    "display_name_should_be_localized",
    "ensure_generation_checkpoint",
    "finalize_interrupted_run_timing",
    "forbidden_skill_package_path",
    "forbidden_skill_package_root",
    "format_hitl_answer_text",
    "frontmatter_block",
    "generated_chinese_workspace_title",
    "is_agent_readable_workspace_path",
    "is_text_artifact",
    "json_safe",
    "json_text",
    "load_yaml_object",
    "normalize_phase_workspace_read_path",
    "normalize_bound_decision_form_answer",
    "normalize_skill_slug",
    "phase_workspace_list_entry_allowed",
    "phase_workspace_path_allowed",
    "public_decision_form_fields",
    "refresh_running_run_timing",
    "reset_generated_outputs",
    "resolve_safe",
    "run_skill_builder_agent_core",
    "set_run_timing_hitl_pause",
    "should_export_skill_path",
    "split_generated_skill_path",
    "task_mode_for_run_phase",
    "trim_hitl_visible_text",
    "validation_revision_available",
    "validation_output_directories",
    "workspace_title_should_be_localized",
]
