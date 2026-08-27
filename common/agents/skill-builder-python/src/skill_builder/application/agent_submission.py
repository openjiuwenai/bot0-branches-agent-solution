# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Host-neutral transactions for Scenario and Skill draft submission."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from skill_builder.application.artifact_digest import (
    candidate_artifact_signature,
    candidate_commit_from_signature,
    skill_artifact_sha256,
    verify_candidate_commit,
)
from skill_builder.application.draft_package_validation import validate_draft_package
from skill_builder.application.package_identity import resolve_package_identity
from skill_builder.application.implementation_plan import (
    load_implementation_plan,
    missing_required_plan_paths,
    synthesize_implementation_plan,
)
from skill_builder.application.revision_store import RevisionStore
from skill_builder.application.scenario_projection import load_persisted_scenario_contract
from skill_builder.domain.candidate_contract import REQUIRED_CANDIDATE_PATHS
from skill_builder.domain.execution import SKILL_BUILDER_POLICY_VERSION
from skill_builder.domain.scenario_contract import SCENARIO_CONTRACT_PATH


CANDIDATE_RECEIPT_PATH = "validation/diagnostics/candidate_receipt.json"


def persist_candidate_receipt(
    root: Path,
    *,
    candidate_commit: dict[str, Any],
    package_revision: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Persist a recreatable projection of the current package revision."""

    verification = verify_candidate_commit(root, candidate_commit)
    if not verification.get("ok"):
        return verification
    revision = package_revision or RevisionStore(root).current_package()
    if not isinstance(revision, dict):
        return {"ok": False, "error": "package_revision_missing"}
    receipt = {
        "schemaVersion": "skill-builder-candidate-receipt/v3",
        "policyVersion": str(
            candidate_commit.get("policyVersion") or SKILL_BUILDER_POLICY_VERSION
        ),
        "candidateCommit": candidate_commit,
        "packageRevision": revision,
    }
    target = root / CANDIDATE_RECEIPT_PATH
    temporary = target.with_name(f".{target.name}.tmp")
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary.write_text(
            json.dumps(receipt, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        temporary.replace(target)
    except OSError as exc:
        temporary.unlink(missing_ok=True)
        return {
            "ok": False,
            "error": "candidate_receipt_persist_failed",
            "message": str(exc)[:1000],
        }
    return {"ok": True, "error": None, "path": CANDIDATE_RECEIPT_PATH, **verification}


def verified_candidate_receipt_status(root: Path) -> dict[str, Any]:
    """Re-verify the durable package handoff without validation contracts."""

    target = root / CANDIDATE_RECEIPT_PATH
    value: dict[str, Any] = {}
    try:
        loaded = json.loads(target.read_text(encoding="utf-8"))
        value = dict(loaded) if isinstance(loaded, dict) else {}
    except FileNotFoundError:
        pass
    except (OSError, TypeError, ValueError) as exc:
        return {
            "ok": False,
            "error": "candidate_receipt_unreadable",
            "message": str(exc)[:1000],
        }

    store = RevisionStore(root)
    package_revision = store.current_package()
    candidate_commit = (
        package_revision.get("candidateCommit")
        if isinstance(package_revision, dict)
        else value.get("candidateCommit")
    )
    if not isinstance(candidate_commit, dict):
        return {"ok": False, "error": "candidate_receipt_missing"}
    verification = verify_candidate_commit(root, candidate_commit)
    if not verification.get("ok"):
        return verification
    package_validation = validate_draft_package(root)
    if not package_validation.ok:
        return {
            "ok": False,
            "error": "draft_package_invalid",
            "validation": package_validation.to_result(),
        }
    if package_revision is None:
        package_revision = store.commit_package(
            candidate_commit=candidate_commit,
            draft_revision=None,
        )["packageRevision"]
        persist_candidate_receipt(
            root,
            candidate_commit=candidate_commit,
            package_revision=package_revision,
        )
    return {
        "ok": True,
        "error": None,
        "path": CANDIDATE_RECEIPT_PATH,
        "candidate_commit": candidate_commit,
        "packageRevision": package_revision,
        "draftValidation": package_validation.to_result(),
        **verification,
    }


def ensure_workspace_package_revision(root: Path) -> dict[str, Any]:
    """Import any valid generated package into the package revision store."""

    current = verified_candidate_receipt_status(root)
    if current.get("ok"):
        return current
    package_validation = validate_draft_package(root)
    if not package_validation.ok:
        return {
            "ok": False,
            "error": "draft_package_invalid",
            "validation": package_validation.to_result(),
            "missing": [
                item.get("path")
                for item in package_validation.errors
                if item.get("path")
            ],
        }
    candidate_commit = candidate_commit_from_signature(
        candidate_artifact_signature(root),
        package_identity=resolve_package_identity(root, "").resolved_name,
    )
    verification = verify_candidate_commit(root, candidate_commit)
    if not verification.get("ok"):
        return verification
    try:
        revision_result = RevisionStore(root).commit_package(
            candidate_commit=candidate_commit,
            draft_revision=None,
        )
        receipt = persist_candidate_receipt(
            root,
            candidate_commit=candidate_commit,
            package_revision=revision_result["packageRevision"],
        )
    except (OSError, TypeError, ValueError, RuntimeError) as exc:
        return {
            "ok": False,
            "error": "package_revision_import_failed",
            "message": str(exc)[:1000],
        }
    if not receipt.get("ok"):
        return receipt
    return {
        "ok": True,
        "error": None,
        "path": CANDIDATE_RECEIPT_PATH,
        "candidate_commit": candidate_commit,
        "packageRevision": revision_result["packageRevision"],
        "draftValidation": package_validation.to_result(),
        **verification,
    }


def commit_candidate_completion(
    *,
    root: Path,
    completion: dict[str, Any],
    draft_revision: int | None = None,
    acceptance_result: dict[str, Any] | None = None,
    acceptance_artifacts: dict[str, Any] | None = None,
    agent_self_check: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Atomically commit one package revision after the minimum draft check."""

    payload_issue = candidate_completion_payload_issue(completion)
    if payload_issue is not None:
        return {**payload_issue, "completed": False, "stage": "candidate_completion"}
    if (root / "validation" / "author_handoff.json").is_file():
        implementation_plan = load_implementation_plan(root)
        if implementation_plan is None:
            synthesized = synthesize_implementation_plan(root)
            implementation_plan = load_implementation_plan(root)
            if not synthesized.get("ok") or implementation_plan is None:
                return {
                    "ok": False,
                    "completed": False,
                    "stage": "implementation_plan",
                    "error": "implementation_plan_missing_or_stale",
                    "issues": synthesized.get("issues") or [],
                    "message": "Core 无法从当前候选生成有效 ImplementationPlan。",
                }
        missing_required_paths = missing_required_plan_paths(root, implementation_plan)
        if missing_required_paths:
            return {
                "ok": False,
                "completed": False,
                "stage": "implementation_plan",
                "error": "implementation_plan_not_materialized",
                "missing": missing_required_paths,
                "message": "ImplementationPlan 中的必需 Skill、生产脚本或能力入口尚未生成。",
            }
    package_validation = validate_draft_package(root)
    if not package_validation.ok:
        return {
            "ok": False,
            "completed": False,
            "stage": "draft_validation",
            "error": "draft_package_invalid",
            "validation": package_validation.to_result(),
            "message": "候选尚未形成合法、可编辑、可打包的 Skill 草稿。",
        }
    candidate_commit = candidate_commit_from_signature(
        candidate_artifact_signature(root),
        package_identity=resolve_package_identity(root, "").resolved_name,
    )
    verification = verify_candidate_commit(root, candidate_commit)
    if not verification.get("ok"):
        return {
            **verification,
            "ok": False,
            "completed": False,
            "stage": "candidate_commit_verification",
        }
    try:
        revision_result = RevisionStore(root).commit_package(
            candidate_commit=candidate_commit,
            draft_revision=draft_revision,
        )
    except (OSError, TypeError, ValueError, RuntimeError) as exc:
        return {
            "ok": False,
            "completed": False,
            "stage": "candidate_revision",
            "error": "candidate_revision_commit_failed",
            "message": str(exc)[:1000],
        }
    receipt_status = persist_candidate_receipt(
        root,
        candidate_commit=candidate_commit,
        package_revision=revision_result["packageRevision"],
    )
    if not receipt_status.get("ok"):
        return {
            **receipt_status,
            "ok": False,
            "completed": False,
            "stage": "candidate_receipt",
        }
    acceptance_status = (
        str(acceptance_result.get("status") or "").strip().lower()
        if isinstance(acceptance_result, dict)
        else ""
    )
    completion_payload = {
        **completion,
        "suggested_next_message": "",
        "completion_source": "finish_draft",
        "candidate_commit": candidate_commit,
        "draft_ready": True,
        # The public legacy projection has only pass/fail/not_run.  Preserve
        # the full three-state acceptance fact under ``acceptance`` while
        # mapping warn to pass for existing hosts.
        "validation_status": (
            "pass"
            if acceptance_status in {"pass", "warn"}
            else "fail"
            if acceptance_status == "fail"
            else "not_run"
        ),
    }
    if isinstance(acceptance_result, dict):
        completion_payload["acceptance"] = acceptance_result
    if isinstance(agent_self_check, dict):
        completion_payload["agent_self_check"] = agent_self_check
    return {
        "ok": True,
        "completed": True,
        "stage": "candidate_commit",
        "candidate_commit": candidate_commit,
        "packageRevision": revision_result["packageRevision"],
        "draftValidation": package_validation.to_result(),
        "acceptance": acceptance_result,
        "acceptanceArtifacts": acceptance_artifacts,
        "agentSelfCheck": agent_self_check,
        "completion": completion_payload,
        "message": "Skill 草稿已提交。",
    }


def candidate_completion_tool_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "summary": {"type": "string", "maxLength": 2000},
            "agent_self_check": {
                "type": "object",
                "description": (
                    "Agent 对材料、文档、脚本和未验证边界的自检记录。自由文本只作补充；"
                    "平台仅复核可绑定当前包的结构化命令/文件证据，并保持最终验收权威。"
                ),
            },
            "implementation_evidence": {
                "type": "array",
                "maxItems": 200,
                "description": (
                    "可选的 ScenarioContract requirementId/ruleId 到包内实现诊断映射。"
                    "只提交 author_handoff.resolvedRequirements/businessRules 中的 ID，"
                    "不要提交 decisionId 或 capability 名称。"
                    "缺失或不完整不会阻断候选提交。"
                ),
                "items": {
                    "type": "object",
                    "properties": {
                        "contractId": {"type": "string"},
                        "implementationType": {
                            "type": "string",
                            "enum": ["code", "documentation", "manual_boundary"],
                        },
                        "path": {"type": "string"},
                        "symbol": {"type": "string"},
                        "verificationPath": {"type": "string"},
                        "assertion": {"type": "string"},
                    },
                    "required": ["contractId", "implementationType", "path"],
                    "additionalProperties": False,
                },
            },
        },
        "required": ["summary", "agent_self_check"],
        "additionalProperties": False,
    }


def candidate_completion_payload_issue(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return {
            "ok": False,
            "error": "candidate_completion_invalid",
            "message": "candidate completion must be an object.",
        }
    if not isinstance(value.get("summary", ""), str):
        return {
            "ok": False,
            "error": "candidate_completion_invalid",
            "field": "summary",
            "message": "candidate completion summary must be a string.",
        }
    return None


def scenario_submission_status(root: Path, agent_result: Any) -> dict[str, Any]:
    """Validate the Scenario Agent handoff against its persisted checkpoint."""

    response = getattr(agent_result, "final_response", None)
    if not isinstance(response, dict):
        return {"ok": False, "error": "scenario_submission_missing"}
    lifecycle_failure = response.get("lifecycle_failure")
    if isinstance(lifecycle_failure, dict):
        issues = [
            str(value)[:500]
            for value in lifecycle_failure.get("issues") or []
            if str(value or "").strip()
        ]
        return {
            "ok": False,
            "error": str(lifecycle_failure.get("code") or "scenario_lifecycle_failed"),
            "details": lifecycle_failure,
            "issues": issues[:20],
        }
    if str(response.get("completion_source") or "") != "scenario_contract_submission":
        return {"ok": False, "error": "scenario_submission_not_committed"}
    expected_hash = str(response.get("scenario_contract_hash") or "").strip()
    if not expected_hash:
        return {"ok": False, "error": "scenario_submission_hash_missing"}
    value, contract_issues = load_persisted_scenario_contract(root)
    if contract_issues:
        return {
            "ok": False,
            "error": "scenario_contract_invalid",
            "issues": contract_issues[:20],
        }
    actual_hash = str(value.get("semanticHash") or "").strip()
    if not actual_hash or actual_hash != expected_hash:
        return {
            "ok": False,
            "error": "scenario_submission_hash_mismatch",
            "expectedSemanticHash": expected_hash,
            "actualSemanticHash": actual_hash,
        }
    return {
        "ok": True,
        "error": None,
        "semanticHash": actual_hash,
        "path": SCENARIO_CONTRACT_PATH,
    }


def author_build_submission_status(root: Path, agent_result: Any) -> dict[str, Any]:
    """Validate an executable production package before self-check planning.

    This handoff is deliberately not a candidate commit.  It proves only that
    the controller-owned plan and its required production paths exist so a
    fresh Author Validate session can inspect the exact package.
    """

    response = getattr(agent_result, "final_response", None)
    if not isinstance(response, dict):
        return {"ok": False, "error": "author_build_completion_missing"}
    lifecycle_failure = response.get("lifecycle_failure")
    if isinstance(lifecycle_failure, dict):
        return {
            "ok": False,
            "error": str(
                lifecycle_failure.get("code") or "author_build_lifecycle_failed"
            ),
            "details": lifecycle_failure,
        }
    if str(response.get("completion_source") or "") != "author_build_completed":
        return {"ok": False, "error": "author_build_not_completed"}
    build_preflight = response.get("build_preflight")
    if (
        not isinstance(build_preflight, dict)
        or build_preflight.get("schemaVersion")
        != "skill-builder-author-build-preflight/v1"
    ):
        return {"ok": False, "error": "author_build_preflight_missing"}
    expected_artifact_sha256 = str(
        build_preflight.get("artifactSha256") or ""
    ).strip()
    actual_artifact_sha256 = skill_artifact_sha256(root / "generated-skill") or ""
    if not expected_artifact_sha256 or expected_artifact_sha256 != actual_artifact_sha256:
        return {
            "ok": False,
            "error": "author_build_preflight_stale",
            "expectedArtifactSha256": expected_artifact_sha256,
            "actualArtifactSha256": actual_artifact_sha256,
        }
    plan = load_implementation_plan(root)
    if plan is None:
        return {"ok": False, "error": "implementation_plan_missing_or_stale"}
    if plan.get("scriptsRequired") is not True:
        return {"ok": False, "error": "author_build_direction_mismatch"}
    missing = missing_required_plan_paths(root, plan)
    if missing:
        return {
            "ok": False,
            "error": "implementation_plan_not_materialized",
            "missing": missing,
        }
    package_validation = validate_draft_package(root)
    if not package_validation.ok:
        return {
            "ok": False,
            "error": "draft_package_invalid",
            "validation": package_validation.to_result(),
        }
    return {
        "ok": True,
        "error": None,
        "plan": plan,
        "draftValidation": package_validation.to_result(),
    }


def candidate_submission_status(root: Path, agent_result: Any) -> dict[str, Any]:
    """Validate the Author handoff using only package revision facts."""

    response = getattr(agent_result, "final_response", None)
    if not isinstance(response, dict):
        receipt = verified_candidate_receipt_status(root)
        return receipt if receipt.get("ok") else {"ok": False, "error": "candidate_completion_missing"}
    lifecycle_failure = response.get("lifecycle_failure")
    if isinstance(lifecycle_failure, dict):
        return {
            "ok": False,
            "error": str(lifecycle_failure.get("code") or "candidate_lifecycle_failed"),
            "details": lifecycle_failure,
        }
    if str(response.get("completion_source") or "") != "finish_draft":
        return {"ok": False, "error": "candidate_completion_not_committed"}
    commit_status = verify_candidate_commit(root, response.get("candidate_commit"))
    if not commit_status.get("ok"):
        return commit_status
    package_validation = validate_draft_package(root)
    if not package_validation.ok:
        return {
            "ok": False,
            "error": "draft_package_invalid",
            "validation": package_validation.to_result(),
        }
    return {
        "ok": True,
        "draftValidation": package_validation.to_result(),
        "acceptance": response.get("acceptance"),
        "agentSelfCheck": response.get("agent_self_check"),
        **commit_status,
    }


__all__ = [
    "CANDIDATE_RECEIPT_PATH",
    "REQUIRED_CANDIDATE_PATHS",
    "author_build_submission_status",
    "candidate_completion_payload_issue",
    "candidate_completion_tool_schema",
    "candidate_submission_status",
    "commit_candidate_completion",
    "ensure_workspace_package_revision",
    "persist_candidate_receipt",
    "scenario_submission_status",
    "verified_candidate_receipt_status",
]
