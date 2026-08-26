# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Single ownership matrix for SkillBuilder acceptance findings."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Iterable


FINDING_OWNERSHIP_SCHEMA_VERSION = "skill-builder-finding-ownership/v1"


class FindingPhase(StrEnum):
    BUILD = "build"
    VALIDATE = "validate"
    FINAL = "final"
    CONTROLLER = "controller"
    ENVIRONMENT = "environment"


class FindingResolution(StrEnum):
    REVISE_BUILD = "revise_build"
    REVISE_VALIDATION = "revise_validation"
    FAIL = "fail"
    REVIEW = "review"
    WARN = "warn"


@dataclass(frozen=True, slots=True)
class FindingOwnership:
    phase: FindingPhase
    resolution: FindingResolution


def _policies(
    phase: FindingPhase,
    resolution: FindingResolution,
    *finding_ids: str,
) -> dict[str, FindingOwnership]:
    policy = FindingOwnership(phase=phase, resolution=resolution)
    return {finding_id: policy for finding_id in finding_ids}


FINDING_OWNERSHIP: dict[str, FindingOwnership] = {
    **_policies(
        FindingPhase.BUILD,
        FindingResolution.REVISE_BUILD,
        "missing_skill_entry",
        "unsafe_package_symlink",
        "package_path_escape",
        "reserved_package_path",
        "unsupported_package_entry",
        "multiple_skill_roots",
        "executable_outside_scripts",
        "skill_entry_unreadable",
        "skill_frontmatter_invalid",
        "skill_name_invalid",
        "skill_description_missing",
        "package_archive_unbuildable",
        "nonportable_package_reference",
        "sample_business_data_hardcoded",
        "reserved_example_endpoint",
        "structured_output_field_never_populated",
        "empty_required_branch",
        "declared_input_not_consumed",
        "confirmed_decision_not_enforced",
        "confirmed_decision_documentation_suspected",
        "python_syntax_invalid",
        "python_module_name_conflict",
        "python_undefined_names",
        "placeholder_implementation",
        "required_capability_not_implemented",
        "capability_declaration_unbacked",
        "skill_reference_missing",
        "documented_cli_entrypoint_missing",
        "input_fixture_contract_invalid",
        "html_fixture_charset_invalid",
        "documented_cli_startup_failed",
        "expected_output_missing",
        "python_runtime_exception",
        "offline_smoke_business_failed",
        "offline_smoke_failed",
        "offline_smoke_timeout",
    ),
    **_policies(
        FindingPhase.VALIDATE,
        FindingResolution.REVISE_VALIDATION,
        "self_check_not_exercising_production",
        "offline_cli_entrypoint_not_exercised",
        "offline_self_check_noop",
        "structured_self_check_missing",
        "structured_self_check_protocol_missing",
        "self_check_package_mutated",
        "self_check_protocol_invalid",
        "self_check_summary_invalid",
        "self_check_summary_missing",
        "offline_self_check_process_failed",
        "offline_replay_failed",
    ),
    **_policies(
        FindingPhase.FINAL,
        FindingResolution.FAIL,
    ),
    **_policies(
        FindingPhase.FINAL,
        FindingResolution.WARN,
        "agent_self_check_capability_status_inconsistent",
        "implementation_evidence_incomplete",
        "optional_openai_metadata_unreadable",
    ),
    **_policies(
        FindingPhase.CONTROLLER,
        FindingResolution.FAIL,
        "acceptance_internal_error",
        "capability_contract_conflict",
    ),
    **_policies(
        FindingPhase.CONTROLLER,
        FindingResolution.REVIEW,
        "requirement_evidence_reference_only",
    ),
    **_policies(
        FindingPhase.ENVIRONMENT,
        FindingResolution.REVIEW,
        "documented_cli_startup_unavailable",
        "offline_smoke_unavailable",
        "required_external_capability_not_verified",
    ),
    **_policies(
        FindingPhase.ENVIRONMENT,
        FindingResolution.WARN,
        "external_runtime_unverified",
    ),
}


_PREFIX_OWNERSHIP: tuple[tuple[str, FindingOwnership], ...] = (
    (
        "optional_openai_metadata_",
        FindingOwnership(FindingPhase.FINAL, FindingResolution.WARN),
    ),
)


def finding_ownership(
    finding_id: str,
    *,
    failure_owner: str = "",
) -> FindingOwnership | None:
    """Return the earliest phase that can act on one finding."""

    normalized = str(finding_id or "").strip()
    if (
        normalized == "input_fixture_contract_invalid"
        and str(failure_owner or "").strip() == "controller"
    ):
        return FindingOwnership(FindingPhase.CONTROLLER, FindingResolution.FAIL)
    direct = FINDING_OWNERSHIP.get(normalized)
    if direct is not None:
        return direct
    return next(
        (policy for prefix, policy in _PREFIX_OWNERSHIP if normalized.startswith(prefix)),
        None,
    )


def findings_for_phase(
    findings: Iterable[dict[str, Any]],
    phase: FindingPhase,
    *,
    severity: str | None = None,
) -> list[dict[str, Any]]:
    """Select findings owned by one phase without changing their severity."""

    selected: list[dict[str, Any]] = []
    for finding in findings:
        if not isinstance(finding, dict):
            continue
        if severity is not None and str(finding.get("severity") or "") != severity:
            continue
        policy = finding_ownership(
            str(finding.get("id") or ""),
            failure_owner=str(finding.get("failureOwner") or ""),
        )
        if policy is not None and policy.phase == phase:
            selected.append(finding)
    return selected


__all__ = [
    "FINDING_OWNERSHIP",
    "FINDING_OWNERSHIP_SCHEMA_VERSION",
    "FindingOwnership",
    "FindingPhase",
    "FindingResolution",
    "finding_ownership",
    "findings_for_phase",
]
