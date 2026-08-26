# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Host-neutral Scenario/HITL/Author lifecycle orchestration.

This is the only module that decides which primary Agent phase runs next.
Hosts provide execution and persistence ports; they do not reproduce these
transitions.
"""

from __future__ import annotations

import json
import re
import uuid
from dataclasses import dataclass, replace
from enum import StrEnum
from pathlib import Path
from typing import Any, Awaitable, Callable

from skill_builder.application.agent_policy import (
    agent_phase_requires_candidate_commit,
    confirmed_decision_handoff,
    phase_scoped_user_message,
)
from skill_builder.application.agent_submission import (
    author_build_submission_status,
    candidate_submission_status,
    commit_candidate_completion,
    scenario_submission_status,
)
from skill_builder.application.execution_state import hitl_answer_is_deferred
from skill_builder.application.fixture_builder import (
    ensure_synthetic_input_fixtures,
    platform_owned_fixture_paths,
)
from skill_builder.application.lifecycle_io import SkillBuilderLifecycleIO
from skill_builder.application.input_contracts import scenario_structured_input_contracts
from skill_builder.application.repair_scope import persist_repair_plan
from skill_builder.application.run_artifacts import (
    changed_repair_artifact_files,
    relevant_repair_artifact_files,
    repair_artifact_snapshot,
)
from skill_builder.application.scenario_projection import (
    load_persisted_scenario_contract,
    project_persisted_scenario_contract,
    scenario_contract_hitl_request,
)
from skill_builder.domain.execution import (
    LifecycleCursor,
    SkillBuilderInput,
    SkillBuilderOptions,
    SkillBuilderState,
    cursor_for_phase,
)
from skill_builder.domain.candidate_contract import (
    EXPORT_ALLOWED_ROOT_DIRS,
    EXPORT_ALLOWED_ROOT_FILES,
)
from skill_builder.ports import SkillBuilderAgentResult
from skill_builder.runtime.repair_settings import (
    resolve_progressive_repair_attempt_limit,
)
from skill_builder.application.workspace_transaction import (
    copy_workspace_artifact_snapshot,
    discard_workspace_artifact_snapshot,
    preserve_rejected_workspace_artifacts,
    restore_workspace_artifact_snapshot,
)


AgentInvoker = Callable[
    [SkillBuilderInput, SkillBuilderOptions, SkillBuilderLifecycleIO],
    Awaitable[SkillBuilderAgentResult],
]


_REPAIR_TARGET_PATH_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_.-])(?:generated-skill/)?(?:"
    + "|".join(re.escape(path) for path in sorted(EXPORT_ALLOWED_ROOT_FILES))
    + r"|(?:"
    + "|".join(re.escape(path) for path in sorted(EXPORT_ALLOWED_ROOT_DIRS))
    + r")/[A-Za-z0-9_./-]+)"
)
_MECHANICAL_REPAIR_FAMILIES = {
    "package_structure": {
        "missing_skill_entry",
        "skill_entry_unreadable",
        "skill_frontmatter_invalid",
        "skill_name_invalid",
        "skill_description_missing",
        "package_metadata_invalid",
        "package_archive_unbuildable",
    },
    "python_static": {
        "python_syntax_invalid",
        "python_undefined_names",
        "python_module_name_conflict",
    },
    "package_references": {
        "skill_reference_missing",
        "nonportable_package_reference",
    },
    "business_fixture": {"implementation_plan_business_fixture_missing"},
    "offline_execution": {
        "business_output_invariant_failed",
        "offline_smoke_failed",
        "runtime_fixture_mismatch",
    },
}
_RECOVERABLE_SCENARIO_FAILURES = frozenset(
    {"agent_tool_input_invalid", "scenario_contract_invalid"}
)
_RECOVERABLE_AUTHOR_BUILD_FAILURES = frozenset(
    {
        "author_build_completion_missing",
        "author_build_not_completed",
        "controller_implementation_plan_invalid",
    }
)


class CandidateLifecycleState(StrEnum):
    COMMITTED = "candidate_committed"
    PREFLIGHT_FAILED = "candidate_preflight_failed"
    TOOL_FAILED = "agent_tool_failed"
    ZERO_ARTIFACT_BUDGET_EXHAUSTED = "zero_artifact_budget_exhausted"
    ZERO_ARTIFACT_UNCOMMITTED = "zero_artifact_uncommitted"
    CANDIDATE_BUDGET_EXHAUSTED = "candidate_uncommitted_budget_exhausted"
    CANDIDATE_UNCOMMITTED = "candidate_uncommitted"


@dataclass(frozen=True, slots=True)
class PrimaryWorkflowResult:
    agent_result: SkillBuilderAgentResult | None
    phase: str
    submission_status: dict[str, Any] | None = None

    @property
    def failed(self) -> bool:
        return bool(self.submission_status is not None and not self.submission_status.get("ok"))


def _scenario_contract(root) -> dict[str, Any]:
    value, issues = load_persisted_scenario_contract(root)
    return {} if issues else value


def _confirmations_for_scenario(
    confirmations: tuple[dict[str, Any], ...],
    scenario_contract: dict[str, Any],
) -> tuple[dict[str, Any], ...]:
    """Return the single current Scenario confirmation, ignoring stale history."""

    semantic_hash = str(scenario_contract.get("semanticHash") or "").strip()
    if not semantic_hash:
        return ()
    matching = tuple(
        item
        for item in confirmations
        if isinstance(item, dict)
        and str(item.get("scenarioContractHash") or "").strip() == semantic_hash
    )
    # One Scenario has one HITL boundary. A repeated identical hash can occur
    # after host retries; the latest completed answer is the durable choice.
    return matching[-1:] if matching else ()


def _failure_status(error: str, *, phase: str, issues: list[str] | None = None) -> dict[str, Any]:
    return {
        "ok": False,
        "error": error,
        "phase": phase,
        "issues": list(issues or []),
    }


def _phase_recovery_context(root: Path, status: dict[str, Any]) -> str:
    """Render bounded deterministic facts for one failed phase retry."""

    generated = root / "generated-skill"
    files = (
        sorted(
            path.relative_to(generated).as_posix()
            for path in generated.rglob("*")
            if path.is_file()
        )
        if generated.is_dir()
        else []
    )
    payload = {
        "error": status.get("error"),
        "issues": status.get("issues") or [],
        "details": status.get("details") or {},
        "existingGeneratedFiles": files[:100],
    }
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2)[:12000]


def _scenario_failure_is_recoverable(status: dict[str, Any]) -> bool:
    error = str(status.get("error") or "").strip()
    details = status.get("details") if isinstance(status.get("details"), dict) else {}
    root_error = str(details.get("rootBlockerCode") or details.get("code") or "").strip()
    return bool({error, root_error} & _RECOVERABLE_SCENARIO_FAILURES)


def _author_build_failure_is_recoverable(root: Path, status: dict[str, Any]) -> bool:
    if not (root / "generated-skill" / "SKILL.md").is_file():
        return False
    error = str(status.get("error") or "").strip()
    details = status.get("details") if isinstance(status.get("details"), dict) else {}
    code = str(details.get("code") or "").strip()
    return bool({error, code} & _RECOVERABLE_AUTHOR_BUILD_FAILURES)


def _business_fixture_repair_status(
    root: Path,
    status: dict[str, Any],
) -> dict[str, Any] | None:
    """Translate one controller plan failure into an exact fixture repair."""

    details = status.get("details") if isinstance(status.get("details"), dict) else {}
    preflight = (
        details.get("buildPreflightFailure")
        if isinstance(details.get("buildPreflightFailure"), dict)
        else {}
    )
    issue_ids = {
        str(item.get("id") or "")
        for item in preflight.get("issues") or []
        if isinstance(item, dict)
    }
    finding_id = "implementation_plan_business_fixture_missing"
    if issue_ids != {finding_id}:
        return None
    scenario, issues = load_persisted_scenario_contract(root)
    contracts = scenario_structured_input_contracts(scenario) if not issues else []
    format_text = str((contracts[0] if contracts else {}).get("format") or "").lower()
    fields = [
        str(item.get("name") or "").strip()
        for item in (contracts[0] if contracts else {}).get("fields") or []
        if isinstance(item, dict) and str(item.get("name") or "").strip()
    ]
    required_fields = [
        str(item).strip()
        for item in (contracts[0] if contracts else {}).get("required_fields") or []
        if str(item).strip()
    ]
    validation_rules = [
        str(item).strip()
        for item in (contracts[0] if contracts else {}).get("validation_rules") or []
        if str(item).strip()
    ]
    suffix = (
        ".xlsx"
        if any(token in format_text for token in ("xlsx", "excel"))
        else ".csv"
        if "csv" in format_text
        else ".jsonl"
        if "jsonl" in format_text
        else ".json"
    )
    path = f"fixtures/business-input{suffix}"
    finding = {
        "id": finding_id,
        "severity": "fail",
        "repairable": True,
        "failureOwner": "package",
        "path": f"generated-skill/{path}",
        "message": "可执行包缺少由生产入口消费的业务 happy fixture。",
        "details": [
            {
                "path": path,
                "format": format_text or suffix.removeprefix("."),
                "fields": fields,
                "requiredFields": required_fields,
                "validationRules": validation_rules,
            }
        ],
    }
    return {
        "ok": False,
        "error": status.get("error"),
        "stage": "draft_acceptance",
        "repairable": True,
        "failureOwners": ["package"],
        "blockingFindingIds": [finding_id],
        "acceptance": {"findings": [finding], "checks": []},
        "details": {"sourceFailure": details},
    }


def _author_build_repair_status(
    root: Path,
    status: dict[str, Any],
) -> dict[str, Any] | None:
    """Translate exact Build preflight findings into the bounded Repair shape."""

    fixture_status = _business_fixture_repair_status(root, status)
    if fixture_status is not None:
        return fixture_status
    details = status.get("details") if isinstance(status.get("details"), dict) else {}
    preflight = (
        details.get("buildPreflightFailure")
        if isinstance(details.get("buildPreflightFailure"), dict)
        else {}
    )
    findings = [
        dict(item)
        for item in preflight.get("findings") or []
        if isinstance(item, dict) and item.get("severity") == "fail"
    ]
    if not findings or not all(
        item.get("repairable") is True
        and str(item.get("failureOwner") or "package") == "package"
        for item in findings
    ):
        return None
    blocker_ids = sorted(
        {
            str(item.get("id") or "").strip()
            for item in findings
            if str(item.get("id") or "").strip()
        }
    )
    if not blocker_ids:
        return None
    return {
        "ok": False,
        "error": str(preflight.get("error") or status.get("error") or ""),
        "stage": "draft_acceptance",
        "message": str(preflight.get("message") or "")[:2000],
        "repairable": True,
        "failureOwners": ["package"],
        "blockingFindingIds": blocker_ids,
        "acceptance": {
            "status": "fail",
            "findings": findings,
            "checks": [],
        },
        "details": {"sourceFailure": details},
    }


def _candidate_failure(status: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(status, dict):
        return {}
    details = status.get("details")
    if isinstance(details, dict):
        failure = details.get("last_submission_failure")
        if isinstance(failure, dict):
            return failure
    return status


def _repair_finding_has_deterministic_evidence(finding: dict[str, Any]) -> bool:
    finding_id = str(finding.get("id") or "").strip()
    if finding_id == "business_output_invariant_failed":
        return bool(finding.get("details"))
    if finding_id not in {"offline_smoke_failed", "runtime_fixture_mismatch"}:
        return True
    details = finding.get("details")
    if not isinstance(details, dict):
        return False
    command = details.get("command")
    if not isinstance(command, list) or not command:
        return False
    if finding_id == "runtime_fixture_mismatch":
        if not str(details.get("fixture") or "").strip():
            return False
        input_contracts = details.get("inputContracts")
        if not isinstance(input_contracts, list) or not any(
            isinstance(contract, dict) and contract
            for contract in input_contracts
        ):
            return False
    return bool(
        str(details.get("stderr") or "").strip()
        or str(details.get("stdout") or "").strip()
    )


def _repairable_candidate_failure(status: dict[str, Any] | None) -> bool:
    failure = _candidate_failure(status)
    blocker_ids = {
        str(item).strip()
        for item in failure.get("blockingFindingIds") or []
        if str(item).strip()
    }
    families = {
        family
        for family, finding_ids in _MECHANICAL_REPAIR_FAMILIES.items()
        if blocker_ids & finding_ids
    }
    covered = set().union(
        *(
            finding_ids
            for family, finding_ids in _MECHANICAL_REPAIR_FAMILIES.items()
            if family in families
        )
    ) if families else set()
    acceptance = failure.get("acceptance")
    findings = acceptance.get("findings") if isinstance(acceptance, dict) else []
    blocking_findings = [
        item
        for item in findings or []
        if isinstance(item, dict) and item.get("severity") == "fail"
    ]
    finding_ids = {
        str(item.get("id") or "").strip()
        for item in blocking_findings
        if str(item.get("id") or "").strip()
    }
    return bool(
        failure.get("stage") == "draft_acceptance"
        and failure.get("repairable") is True
        and set(failure.get("failureOwners") or ["package"]) == {"package"}
        and len(families) == 1
        and blocker_ids
        and blocker_ids.issubset(covered)
        and finding_ids == blocker_ids
        and all(
            _repair_finding_has_deterministic_evidence(item)
            for item in blocking_findings
        )
    )


def _candidate_lifecycle_state(
    root: Path,
    status: dict[str, Any] | None,
) -> CandidateLifecycleState:
    """Classify one Author result from durable candidate and termination facts."""

    if isinstance(status, dict) and status.get("ok"):
        return CandidateLifecycleState.COMMITTED
    failure = _candidate_failure(status)
    lifecycle = (
        status.get("details")
        if isinstance(status, dict) and isinstance(status.get("details"), dict)
        else failure
    )
    if failure.get("stage") == "draft_acceptance":
        return CandidateLifecycleState.PREFLIGHT_FAILED
    failure_codes = {
        str(value or "").strip()
        for value in (
            status.get("error") if isinstance(status, dict) else None,
            failure.get("error"),
            failure.get("code"),
            lifecycle.get("code"),
            lifecycle.get("rootBlockerCode"),
            lifecycle.get("terminationCode"),
        )
        if str(value or "").strip()
    }
    if failure.get("stage") in {
        "draft_snapshot",
        "candidate_revision",
        "candidate_receipt",
        "candidate_commit_verification",
    } or failure_codes & {
        "agent_tool_input_invalid",
        "agent_tool_execution_failed",
        "atomic_tool_transport_failed_after_rejection",
    }:
        return CandidateLifecycleState.TOOL_FAILED
    # Controller-owned fixtures may exist before Author starts.  They are test
    # inputs, not evidence that the Agent produced a candidate package.
    artifact_present = (root / "generated-skill" / "SKILL.md").is_file()
    termination_code = str(
        lifecycle.get("terminationCode")
        or lifecycle.get("termination_code")
        or ""
    ).strip()
    if artifact_present:
        return (
            CandidateLifecycleState.CANDIDATE_BUDGET_EXHAUSTED
            if termination_code == "agent_iteration_budget_exhausted"
            else CandidateLifecycleState.CANDIDATE_UNCOMMITTED
        )
    return (
        CandidateLifecycleState.ZERO_ARTIFACT_BUDGET_EXHAUSTED
        if termination_code == "agent_iteration_budget_exhausted"
        else CandidateLifecycleState.ZERO_ARTIFACT_UNCOMMITTED
    )


def _candidate_status_with_state(
    root: Path,
    status: dict[str, Any],
) -> dict[str, Any]:
    return {
        **status,
        "lifecycleState": _candidate_lifecycle_state(root, status).value,
    }


def _candidate_repair_plan(
    status: dict[str, Any],
    *,
    attempt: int,
    max_attempts: int,
) -> dict[str, Any]:
    """Compile one bounded Repair plan from platform-owned findings."""

    failure = _candidate_failure(status)
    acceptance = failure.get("acceptance")
    findings = acceptance.get("findings") if isinstance(acceptance, dict) else []
    blocking_findings = [
        {
            key: item.get(key)
            for key in (
                "id",
                "severity",
                "message",
                "path",
                "failureOwner",
                "repairable",
                "details",
            )
            if item.get(key) not in (None, "", [], {})
        }
        for item in findings or []
        if isinstance(item, dict)
        and item.get("severity") == "fail"
        and item.get("repairable") is True
    ][:20]
    selected_family = next(
        (
            family
            for family, finding_ids in _MECHANICAL_REPAIR_FAMILIES.items()
            if any(item.get("id") in finding_ids for item in blocking_findings)
        ),
        "",
    )
    if selected_family:
        allowed_ids = _MECHANICAL_REPAIR_FAMILIES[selected_family]
        blocking_findings = [
            item for item in blocking_findings if item.get("id") in allowed_ids
        ]
    target_paths: set[str] = set()
    for item in blocking_findings:
        explicit_path = str(item.get("path") or "").strip().replace("\\", "/")
        if explicit_path.startswith("generated-skill/"):
            target_paths.add(explicit_path.removeprefix("generated-skill/"))
        elif _REPAIR_TARGET_PATH_PATTERN.fullmatch(explicit_path):
            target_paths.add(explicit_path)
        searchable = json.dumps(item, ensure_ascii=False, sort_keys=True)
        for match in _REPAIR_TARGET_PATH_PATTERN.findall(searchable):
            target_paths.add(match.removeprefix("generated-skill/").rstrip(".,:;)]}"))
    return {
        "schemaVersion": "skill-builder-repair-handoff/v1",
        "attempt": attempt,
        "maxAttempts": max_attempts,
        "error": failure.get("error") or status.get("error"),
        "message": failure.get("message"),
        "blockingFindingIds": failure.get("blockingFindingIds") or [],
        "findings": blocking_findings,
        "targetPaths": sorted(path for path in target_paths if path),
        "sourceMaterialReadsAllowed": False,
        "requiredMutationBeforeFullRecheck": True,
        "acceptedMutationKinds": ["generated_skill"],
        "repairScope": "existing_candidate_mechanical_fix_only",
        "rootCauseFamily": selected_family,
    }


def _candidate_repair_message(
    status: dict[str, Any],
    *,
    attempt: int,
    max_attempts: int,
    plan: dict[str, Any] | None = None,
) -> str:
    """Render one bounded Repair handoff from a persisted controller plan."""

    payload = plan or _candidate_repair_plan(
        status,
        attempt=attempt,
        max_attempts=max_attempts,
    )
    return "\n".join(
        (
            "平台生成预检未通过，请在当前持久化 Draft 上做一次有界修复。",
            "只修复下列 repairable=true 的阻断项；保留已通过文件和业务规则，不重跑 Scenario/HITL，不从头生成。",
            "不得读取 inputs/ 或重新加载材料包；先检查 targetPaths 指向的候选文件，并只处理 rootCauseFamily 对应的机械问题。",
            "若为 runtime_fixture_mismatch，必须保留 details.fixture 的原 fixture 路径并使用 details.command 的原验证命令复验；不得通过删除或替换 fixture、移除校验参数或改用其他运行模式绕过失败。",
            "修复后返回结构化自检摘要；控制器会自动重跑全部预检并决定是否提交候选。",
            "",
            json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2),
        )
    )


def _candidate_acceptance(status: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(status, dict):
        return {}
    direct = status.get("acceptance")
    if isinstance(direct, dict):
        return direct
    failure = _candidate_failure(status)
    acceptance = failure.get("acceptance")
    return acceptance if isinstance(acceptance, dict) else {}


def _repair_issue_keys(status: dict[str, Any] | None) -> set[str]:
    acceptance = _candidate_acceptance(status)
    keys: set[str] = set()
    for finding in acceptance.get("findings") or []:
        if not isinstance(finding, dict) or finding.get("severity") != "fail":
            continue
        finding_id = str(finding.get("id") or "unknown").strip()
        details = [item for item in finding.get("details") or [] if isinstance(item, dict)]
        if not details:
            keys.add(finding_id)
            continue
        for detail in details:
            identity: dict[str, Any] = {
                key: detail.get(key)
                for key in (
                    "id",
                    "kind",
                    "caseId",
                    "contractId",
                    "obligationKind",
                    "path",
                    "commandIndex",
                )
                if detail.get(key) not in (None, "", [], {})
            }
            assertion = detail.get("assertion")
            if isinstance(assertion, dict):
                identity["assertion"] = {
                    key: assertion.get(key)
                    for key in ("source", "path", "operator")
                    if assertion.get(key) not in (None, "")
                }
            for key in ("missingFields", "missingSections"):
                if isinstance(detail.get(key), list):
                    identity[key] = sorted(str(item) for item in detail[key])
            if not any(
                key in identity
                for key in ("caseId", "contractId", "path", "assertion", "missingFields", "missingSections")
            ):
                identity["message"] = str(detail.get("message") or "")[:500]
            keys.add(
                f"{finding_id}:"
                + json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            )
    if keys:
        return keys
    return {
        str(value).strip()
        for value in _candidate_failure(status).get("blockingFindingIds") or []
        if str(value).strip()
    }


def _repair_check_regressions(
    before_status: dict[str, Any] | None,
    after_status: dict[str, Any] | None,
) -> list[dict[str, str]]:
    before_checks = {
        str(item.get("id") or ""): str(item.get("status") or "")
        for item in _candidate_acceptance(before_status).get("checks") or []
        if isinstance(item, dict) and str(item.get("id") or "")
    }
    after_checks = {
        str(item.get("id") or ""): str(item.get("status") or "")
        for item in _candidate_acceptance(after_status).get("checks") or []
        if isinstance(item, dict) and str(item.get("id") or "")
    }
    return [
        {"id": check_id, "before": "pass", "after": after_checks[check_id]}
        for check_id, before_value in sorted(before_checks.items())
        if before_value == "pass"
        and check_id in after_checks
        and after_checks[check_id] != "pass"
    ]


def _repair_blocker_delta(
    before_status: dict[str, Any] | None,
    after_status: dict[str, Any] | None,
) -> dict[str, Any]:
    """Describe whether a Repair made the blocking set strictly smaller."""

    before = {
        str(value).strip()
        for value in _candidate_failure(before_status).get("blockingFindingIds") or []
        if str(value).strip()
    }
    after = {
        str(value).strip()
        for value in _candidate_failure(after_status).get("blockingFindingIds") or []
        if str(value).strip()
    }
    resolved = before - after
    introduced = after - before
    before_issues = _repair_issue_keys(before_status)
    after_issues = _repair_issue_keys(after_status)
    resolved_issues = before_issues - after_issues
    introduced_issues = after_issues - before_issues
    return {
        "before": sorted(before),
        "after": sorted(after),
        "resolved": sorted(resolved),
        "remaining": sorted(before & after),
        "introduced": sorted(introduced),
        "beforeIssues": sorted(before_issues),
        "afterIssues": sorted(after_issues),
        "resolvedIssues": sorted(resolved_issues),
        "introducedIssues": sorted(introduced_issues),
        "strictProgress": bool(
            resolved_issues
            and not introduced_issues
            and len(after_issues) < len(before_issues)
        ),
    }


async def _run_candidate_repairs(
    *,
    builder_input: SkillBuilderInput,
    options: SkillBuilderOptions,
    lifecycle_io: SkillBuilderLifecycleIO,
    invoke_agent: AgentInvoker,
    result: SkillBuilderAgentResult,
    status: dict[str, Any],
    source_phase: str = "author",
) -> PrimaryWorkflowResult:
    if not _repairable_candidate_failure(status):
        failure = _candidate_failure(status)
        blocker_ids = {
            str(item)
            for item in failure.get("blockingFindingIds") or []
            if str(item).strip()
        }
        owners = set(failure.get("failureOwners") or ["package"])
        routed_status = {
            **status,
            "repairRouting": {
                "started": False,
                "reason": "non_mechanical_or_multiple_root_causes",
                "nextPhase": None,
                "failureOwners": sorted(owners),
                "blockingFindingIds": sorted(blocker_ids),
            },
        }
        await lifecycle_io.emit(
            "agent.repair_skipped",
            "阻断项不属于单一机械根因族，未启动 Repair。",
            routed_status["repairRouting"],
        )
        return PrimaryWorkflowResult(result, source_phase, routed_status)

    max_attempts = resolve_progressive_repair_attempt_limit()
    if max_attempts <= 0:
        return PrimaryWorkflowResult(result, source_phase, status)
    initial_plan = _candidate_repair_plan(
        status,
        attempt=1,
        max_attempts=max_attempts,
    )
    if not initial_plan.get("targetPaths"):
        routed_status = {
            **status,
            "repairRouting": {
                "started": False,
                "reason": "repair_target_paths_empty",
                "nextPhase": None,
            },
        }
        await lifecycle_io.emit(
            "agent.repair_skipped",
            "阻断项没有安全的机械修复目标，未启动 Repair。",
            routed_status["repairRouting"],
        )
        return PrimaryWorkflowResult(result, source_phase, routed_status)

    original_result = result
    original_status = status
    transaction_root = (
        builder_input.root
        / ".skill-builder"
        / "repair-transactions"
        / uuid.uuid4().hex
    )
    copy_workspace_artifact_snapshot(builder_input.root, transaction_root)

    async def rollback_repair(
        *,
        reason: str,
        rejected_status: dict[str, Any],
        metadata: dict[str, Any],
    ) -> PrimaryWorkflowResult:
        preserved = preserve_rejected_workspace_artifacts(
            builder_input.root,
            transaction_root,
            metadata={
                "reason": reason,
                "attempt": 1,
                "statusBefore": original_status,
                "rejectedStatus": rejected_status,
                **metadata,
            },
        )
        restore_workspace_artifact_snapshot(builder_input.root, transaction_root)
        if hasattr(original_result, "submission_status"):
            original_result.submission_status = original_status
        await lifecycle_io.emit(
            "agent.repair_rolled_back",
            "Repair 候选未满足范围或回归约束，已恢复修复前版本。",
            {
                "phase": "repair",
                "attempt": 1,
                "reason": reason,
                "rejectedRepair": preserved,
                **metadata,
            },
        )
        return PrimaryWorkflowResult(original_result, source_phase, original_status)

    try:
        repair_snapshot_before = repair_artifact_snapshot(builder_input.root)
        persist_repair_plan(builder_input.root, initial_plan)
        await lifecycle_io.set_cursor(LifecycleCursor.REPAIRING)
        await lifecycle_io.emit(
            "agent.repair_started",
            "生成预检发现单一机械阻断项，启动一次 Repair。",
            {
                "phase": "repair",
                "attempt": 1,
                "maxAttempts": 1,
                "failure": {
                    key: _candidate_failure(original_status).get(key)
                    for key in (
                        "stage",
                        "error",
                        "message",
                        "repairable",
                        "blockingFindingIds",
                    )
                    if _candidate_failure(original_status).get(key)
                    not in (None, "", [], {})
                },
            },
        )
        repair_input = replace(
            builder_input,
            user_message=_candidate_repair_message(
                original_status,
                attempt=1,
                max_attempts=1,
                plan=initial_plan,
            ),
        )
        repair_result = await invoke_agent(
            repair_input,
            replace(options, run_phase="repair"),
            lifecycle_io,
        )
        repair_status = _candidate_status_with_state(
            builder_input.root,
            candidate_submission_status(builder_input.root, repair_result),
        )
        if hasattr(repair_result, "submission_status"):
            repair_result.submission_status = repair_status

        blocker_delta = _repair_blocker_delta(original_status, repair_status)
        regressions = _repair_check_regressions(original_status, repair_status)
        hard_regressions = [
            item for item in regressions if item.get("after") == "fail"
        ]
        changed_paths = relevant_repair_artifact_files(
            changed_repair_artifact_files(
                repair_snapshot_before,
                repair_artifact_snapshot(builder_input.root),
            )
        )
        platform_paths = {
            f"generated-skill/{path}"
            for path in platform_owned_fixture_paths(
                builder_input.root,
                builder_input.root / "generated-skill",
            )
        }
        allowed_paths = {
            f"generated-skill/{path}"
            for path in initial_plan.get("targetPaths") or []
        } | platform_paths | {"generated-skill/agents/openai.yaml"}
        unauthorized_paths = sorted(changed_paths - allowed_paths)
        await lifecycle_io.emit(
            "agent.repair_completed",
            "Repair 已通过完整预检。"
            if repair_status.get("ok")
            else "Repair 未通过完整预检。",
            {
                "phase": "repair",
                "attempt": 1,
                "maxAttempts": 1,
                "status": repair_status,
                "blockerDelta": blocker_delta,
                "changedPaths": sorted(changed_paths),
                "unauthorizedPaths": unauthorized_paths,
                "checkRegressions": regressions,
            },
        )
        if unauthorized_paths or hard_regressions:
            return await rollback_repair(
                reason=(
                    "repair_path_out_of_scope"
                    if unauthorized_paths
                    else "repair_check_regression"
                ),
                rejected_status=repair_status,
                metadata={
                    "unauthorizedPaths": unauthorized_paths,
                    "checkRegressions": regressions,
                    "blockerDelta": blocker_delta,
                },
            )
        if repair_status.get("ok"):
            return PrimaryWorkflowResult(repair_result, "repair", repair_status)
        return await rollback_repair(
            reason="repair_not_accepted",
            rejected_status=repair_status,
            metadata={"blockerDelta": blocker_delta},
        )
    except Exception:
        preserve_rejected_workspace_artifacts(
            builder_input.root,
            transaction_root,
            metadata={"reason": "repair_exception"},
        )
        restore_workspace_artifact_snapshot(builder_input.root, transaction_root)
        raise
    finally:
        discard_workspace_artifact_snapshot(transaction_root)


async def _complete_author_attempt(
    *,
    builder_input: SkillBuilderInput,
    options: SkillBuilderOptions,
    lifecycle_io: SkillBuilderLifecycleIO,
    invoke_agent: AgentInvoker,
    result: SkillBuilderAgentResult,
    status: dict[str, Any],
    source_phase: str = "author",
) -> PrimaryWorkflowResult:
    """Close one Author attempt through an optional mechanical Repair."""

    current_result = result
    current_status = _candidate_status_with_state(builder_input.root, status)
    if hasattr(current_result, "submission_status"):
        current_result.submission_status = current_status
    if current_status.get("ok"):
        return PrimaryWorkflowResult(current_result, source_phase, current_status)
    return await _run_candidate_repairs(
        builder_input=builder_input,
        options=options,
        lifecycle_io=lifecycle_io,
        invoke_agent=invoke_agent,
        result=current_result,
        status=current_status,
        source_phase=source_phase,
    )


def _projected_scripts_required(root: Path) -> bool | None:
    """Read the controller-owned delivery direction produced before Author."""

    try:
        value = json.loads(
            (root / "validation" / "artifact_manifest.json").read_text(
                encoding="utf-8"
            )
        )
    except (OSError, UnicodeError, TypeError, ValueError, json.JSONDecodeError):
        return None
    signature = value.get("behaviorSignature") if isinstance(value, dict) else None
    scripts_required = (
        signature.get("scriptsRequired") if isinstance(signature, dict) else None
    )
    return scripts_required if isinstance(scripts_required, bool) else None


async def run_primary_workflow(
    *,
    builder_input: SkillBuilderInput,
    options: SkillBuilderOptions,
    state: SkillBuilderState,
    lifecycle_io: SkillBuilderLifecycleIO,
    invoke_agent: AgentInvoker,
) -> PrimaryWorkflowResult:
    """Advance the primary workflow to HITL pause or committed candidate."""

    requested_phase = str(options.run_phase or "initial").strip().lower() or "initial"
    supported_phases = {"initial", "workflow", "scenario", "author", "repair", "chat", "edit"}
    if requested_phase not in supported_phases:
        raise ValueError(f"unsupported Skill Builder run phase: {requested_phase}")
    workflow_entry = requested_phase in {"initial", "workflow"}

    if not workflow_entry:
        await lifecycle_io.set_cursor(cursor_for_phase(requested_phase))
        phase_options = replace(options, run_phase=requested_phase)
        result = await invoke_agent(builder_input, phase_options, lifecycle_io)
        status = None
        if requested_phase == "scenario":
            status = scenario_submission_status(builder_input.root, result)
        elif agent_phase_requires_candidate_commit(requested_phase):
            status = candidate_submission_status(builder_input.root, result)
            status = _candidate_status_with_state(builder_input.root, status)
        if status is not None and hasattr(result, "submission_status"):
            result.submission_status = status
        if requested_phase == "author" and isinstance(status, dict):
            return await _complete_author_attempt(
                builder_input=builder_input,
                options=options,
                lifecycle_io=lifecycle_io,
                invoke_agent=invoke_agent,
                result=result,
                status=status,
            )
        return PrimaryWorkflowResult(result, requested_phase, status)

    # The default extraction path deliberately retains both built-in skills:
    # Scenario performs the one structured material handoff, then Author writes
    # the package from that handoff.  The two model responsibilities are still
    # separate, but this controller runs one Scenario submission, one optional
    # aggregated HITL, and one Author submission. A repairable
    # platform preflight failure may start a separately budgeted Repair phase;
    # it never extends or reuses the Author Agent session.
    confirmations = tuple(state.hitl_confirmations)
    scenario_contract = _scenario_contract(builder_input.root)
    # A persisted answer is only resumable when its authoritative Scenario
    # checkpoint is present. A stale/orphaned host HITL row must not unlock the
    # Author phase or replace material extraction.
    # A valid contract left in the workspace is not, by itself, a current
    # handoff.  A fresh default build must rerun Scenario instead of silently
    # reusing a previous attempt's facts.  The only resumable shortcut is a
    # contract bound to a durable HITL confirmation from this workflow.
    current_confirmations = _confirmations_for_scenario(confirmations, scenario_contract)
    scenario_ready = bool(scenario_contract and current_confirmations)
    scenario_options = replace(
        options,
        run_phase="scenario",
    )
    scenario_result: SkillBuilderAgentResult | None = None
    scenario_status: dict[str, Any] | None = None
    if not scenario_ready:
        await lifecycle_io.set_cursor(LifecycleCursor.SCENARIO)
        scenario_input = replace(
            builder_input,
            user_message=phase_scoped_user_message(builder_input.user_message, task_mode="scenario"),
        )
        scenario_result = await invoke_agent(scenario_input, scenario_options, lifecycle_io)
        scenario_status = scenario_submission_status(builder_input.root, scenario_result)
        if hasattr(scenario_result, "submission_status"):
            scenario_result.submission_status = scenario_status
        if not scenario_status.get("ok") and _scenario_failure_is_recoverable(
            scenario_status
        ):
            await lifecycle_io.emit(
                "agent.scenario_recovery_started",
                "Scenario 结构化提交失败，使用持久化草稿和精确 issues 重试一次。",
                {
                    "phase": "scenario",
                    "attempt": 1,
                    "error": scenario_status.get("error"),
                },
            )
            recovery_input = replace(
                scenario_input,
                user_message="\n\n".join(
                    (
                        scenario_input.user_message,
                        "上一次 Scenario 提交未通过。只根据以下控制器事实修正结构，"
                        "不得改变业务方向或补造证据：\n"
                        + _phase_recovery_context(
                            builder_input.root,
                            scenario_status,
                        ),
                    )
                ),
            )
            scenario_result = await invoke_agent(
                recovery_input,
                scenario_options,
                lifecycle_io,
            )
            scenario_status = scenario_submission_status(
                builder_input.root,
                scenario_result,
            )
            if hasattr(scenario_result, "submission_status"):
                scenario_result.submission_status = scenario_status
            await lifecycle_io.emit(
                "agent.scenario_recovery_completed",
                "Scenario 单次结构恢复已完成。",
                {
                    "phase": "scenario",
                    "attempt": 1,
                    "ok": bool(scenario_status.get("ok")),
                    "error": scenario_status.get("error"),
                },
            )
        if not scenario_status.get("ok"):
            return PrimaryWorkflowResult(
                scenario_result,
                "scenario",
                scenario_status,
            )
        scenario_contract = _scenario_contract(builder_input.root)
        scenario_ready = bool(scenario_contract)

    confirmations = _confirmations_for_scenario(confirmations, scenario_contract)
    # Historical workspace HITL rows are audit data, not input to the current
    # lifecycle. Keeping them in state would let validation re-aggregate stale
    # capability decisions even when Author compiled the correct contract.
    state.hitl_confirmations = confirmations

    if not confirmations:
        projected, projection_issues = project_persisted_scenario_contract(builder_input.root)
        if projection_issues:
            return PrimaryWorkflowResult(
                scenario_result,
                "scenario",
                _failure_status(
                    "scenario_projection_failed",
                    phase="scenario",
                    issues=projection_issues,
                ),
            )
        await lifecycle_io.emit(
            "artifact.scenario_projected",
            "平台已从唯一 ScenarioContract 生成场景投影。",
            {"phase": "scenario", "files": projected},
        )
        hitl_request, hitl_issues = scenario_contract_hitl_request(
            _scenario_contract(builder_input.root)
        )
        if hitl_issues:
            return PrimaryWorkflowResult(
                scenario_result,
                "scenario",
                _failure_status(
                    "scenario_hitl_compile_failed",
                    phase="scenario",
                    issues=hitl_issues,
                ),
            )
        if hitl_request:
            answer = await lifecycle_io.request_user(hitl_request)
            if hitl_answer_is_deferred(answer):
                return PrimaryWorkflowResult(
                    scenario_result,
                    "scenario",
                    scenario_status,
                )
            confirmations = _confirmations_for_scenario(
                tuple(state.hitl_confirmations),
                _scenario_contract(builder_input.root),
            )
            state.hitl_confirmations = confirmations
            if not confirmations:
                return PrimaryWorkflowResult(
                    scenario_result,
                    "scenario",
                    _failure_status(
                        "scenario_confirmation_binding_failed",
                        phase="scenario",
                        issues=["人工确认未绑定到当前 ScenarioContract semanticHash"],
                    ),
                )

    # Refresh the platform-owned projection after HITL so Author sees the
    # same effective decision that is passed below.  The authoritative
    # ScenarioContract itself is intentionally left unchanged; its semantic
    # hash remains the binding for this confirmation.
    projected, projection_issues = project_persisted_scenario_contract(
        builder_input.root,
        confirmations=confirmations,
    )
    if projection_issues:
        return PrimaryWorkflowResult(
            scenario_result,
            "scenario",
            _failure_status(
                "scenario_projection_failed",
                phase="scenario",
                issues=projection_issues,
            ),
        )
    await lifecycle_io.emit(
        "artifact.scenario_projected",
        "平台已刷新场景事实投影并绑定当前人工确认结果。",
        {"phase": "scenario", "files": projected, "confirmed": bool(confirmations)},
    )

    try:
        fixture_result = ensure_synthetic_input_fixtures(
            builder_input.root,
            builder_input.root / "generated-skill",
        )
    except (OSError, TypeError, ValueError) as exc:
        return PrimaryWorkflowResult(
            scenario_result,
            "author_preparation",
            {
                "ok": False,
                "error": "synthetic_input_fixture_generation_failed",
                "phase": "author_preparation",
                "message": str(exc)[:1000],
                "repairable": False,
                "failureOwners": ["controller"],
            },
        )
    await lifecycle_io.emit(
        "artifact.validation_fixtures_prepared",
        "平台已在 Author 启动前准备规范化验证样例。",
        {"phase": "author_preparation", **fixture_result},
    )

    await lifecycle_io.set_cursor(LifecycleCursor.AUTHORING)
    scripts_required = _projected_scripts_required(builder_input.root)
    if scripts_required is None:
        return PrimaryWorkflowResult(
            scenario_result,
            "author_preparation",
            _failure_status(
                "behavior_signature_missing",
                phase="author_preparation",
                issues=["Scenario 投影缺少控制器签发的 scriptsRequired"],
            ),
        )
    author_phase = "author_build" if scripts_required else "author"
    author_options = replace(options, run_phase=author_phase)
    author_message = phase_scoped_user_message(
        builder_input.user_message,
        task_mode=author_phase,
    )
    fixture_contracts = fixture_result.get("contracts")
    if isinstance(fixture_contracts, list) and fixture_contracts:
        author_message = "\n\n".join(
            (
                author_message,
                "## 平台生成的结构化验证输入\n\n"
                "以下路径属于控制器生成的脱敏 schema fixture，只用于字段和 invalid_input 检查；"
                "不得覆盖这些文件，也不得用于 happy_path/business_rule/file_handoff。"
                "可执行 Skill 请根据材料另建小型业务 happy fixture。\n\n"
                + json.dumps(
                    {
                        "schemaVersion": "skill-builder-input-fixture-handoff/v1",
                        "inputs": fixture_contracts,
                    },
                    ensure_ascii=False,
                    sort_keys=True,
                    indent=2,
                ),
            )
        )
    handoff = confirmed_decision_handoff(confirmations)
    if handoff:
        author_message = f"{author_message}\n\n{handoff}"
    author_input = replace(
        builder_input,
        user_message=author_message,
    )
    try:
        author_result = await invoke_agent(author_input, author_options, lifecycle_io)
    except Exception as exc:
        if not scripts_required or not (
            builder_input.root / "generated-skill" / "SKILL.md"
        ).is_file():
            raise
        await lifecycle_io.emit(
            "agent.author_build_recovery_started",
            "Author Build 异常退出但候选检查点存在，续跑一次。",
            {
                "phase": "author_build",
                "attempt": 1,
                "error": f"{type(exc).__name__}: {exc}"[:1000],
            },
        )
        recovery_input = replace(
            author_input,
            user_message="\n\n".join(
                (
                    author_input.user_message,
                    "上一次 Author Build 异常退出，但已有候选文件。保留现有正确文件，"
                    "只补齐缺失内容并立即调用 finish_authoring。现有检查点：\n"
                    + _phase_recovery_context(
                        builder_input.root,
                        {
                            "error": "author_build_interrupted",
                            "details": {"message": str(exc)[:1000]},
                        },
                    ),
                )
            ),
        )
        author_result = await invoke_agent(
            recovery_input,
            author_options,
            lifecycle_io,
        )
        await lifecycle_io.emit(
            "agent.author_build_recovery_completed",
            "Author Build 检查点续跑已返回。",
            {"phase": "author_build", "attempt": 1, "ok": True},
        )
    if scripts_required:
        build_status = author_build_submission_status(
            builder_input.root,
            author_result,
        )
        if hasattr(author_result, "submission_status"):
            author_result.submission_status = build_status
        preflight_repair_status = _author_build_repair_status(
            builder_input.root,
            build_status,
        )
        if not build_status.get("ok") and preflight_repair_status is not None:
            return await _run_candidate_repairs(
                builder_input=builder_input,
                options=options,
                lifecycle_io=lifecycle_io,
                invoke_agent=invoke_agent,
                result=author_result,
                status=preflight_repair_status,
                source_phase="author_build",
            )
        if not build_status.get("ok") and _author_build_failure_is_recoverable(
            builder_input.root,
            build_status,
        ):
            await lifecycle_io.emit(
                "agent.author_build_recovery_started",
                "Author Build 已留下候选检查点，按精确失败信息续跑一次。",
                {
                    "phase": "author_build",
                    "attempt": 1,
                    "error": build_status.get("error"),
                },
            )
            recovery_input = replace(
                author_input,
                user_message="\n\n".join(
                    (
                        author_input.user_message,
                        "当前生产包未通过控制器提交边界。保留已有正确文件，"
                        "只补齐或修正以下失败事实后立即调用 finish_authoring：\n"
                        + _phase_recovery_context(
                            builder_input.root,
                            build_status,
                        ),
                    )
                ),
            )
            author_result = await invoke_agent(
                recovery_input,
                author_options,
                lifecycle_io,
            )
            build_status = author_build_submission_status(
                builder_input.root,
                author_result,
            )
            if hasattr(author_result, "submission_status"):
                author_result.submission_status = build_status
            await lifecycle_io.emit(
                "agent.author_build_recovery_completed",
                "Author Build 单次检查点续跑已完成。",
                {
                    "phase": "author_build",
                    "attempt": 1,
                    "ok": bool(build_status.get("ok")),
                    "error": build_status.get("error"),
                },
            )
        if not build_status.get("ok"):
            return PrimaryWorkflowResult(
                author_result,
                "author_build",
                build_status,
            )
        await lifecycle_io.emit(
            "agent.author_build_completed",
            "可执行 Skill 生产包已通过静态预检，提交候选并启动 Core 交付验收。",
            {
                "phase": "author_build",
                "nextPhase": "candidate_commit",
                "scriptsRequired": True,
            },
        )
        build_response = getattr(author_result, "final_response", None)
        build_response = build_response if isinstance(build_response, dict) else {}
        committed = commit_candidate_completion(
            root=builder_input.root,
            completion={
                "summary": str(
                    build_response.get("summary")
                    or "可执行 Skill 生产包已通过 Build 静态预检。"
                )[:2000]
            },
            agent_self_check=(
                build_response.get("agent_self_check")
                if isinstance(build_response.get("agent_self_check"), dict)
                else None
            ),
        )
        if committed.get("ok") and isinstance(committed.get("completion"), dict):
            author_result.final_response = committed["completion"]
        committed_status = candidate_submission_status(
            builder_input.root,
            author_result,
        )
        committed_status = _candidate_status_with_state(
            builder_input.root,
            committed_status,
        )
        if hasattr(author_result, "submission_status"):
            author_result.submission_status = committed_status
        return PrimaryWorkflowResult(
            author_result,
            "author_build",
            committed_status,
        )

    author_status = candidate_submission_status(builder_input.root, author_result)
    author_status = _candidate_status_with_state(builder_input.root, author_status)
    if hasattr(author_result, "submission_status"):
        author_result.submission_status = author_status
    return await _complete_author_attempt(
        builder_input=author_input,
        options=options,
        lifecycle_io=lifecycle_io,
        invoke_agent=invoke_agent,
        result=author_result,
        status=author_status,
        source_phase="author",
    )


__all__ = [
    "AgentInvoker",
    "CandidateLifecycleState",
    "PrimaryWorkflowResult",
    "run_primary_workflow",
]
