# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Atomic draft submission tool for the OpenJiuwen adapter."""

from __future__ import annotations

import json
import inspect
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from skill_builder.application.agent_core import _emit
from skill_builder.application.acceptance import (
    ACCEPTANCE_RESULT_PATH,
    ACCEPTANCE_SUMMARY_PATH,
    accept_skill_package,
    acceptance_exception_payload,
    acceptance_result_payload,
    persist_acceptance_files,
    render_acceptance_summary,
)
from skill_builder.application.fixture_builder import ensure_synthetic_input_fixtures
from skill_builder.application.agent_self_check import (
    AGENT_SELF_CHECK_PATH,
    normalize_agent_self_check,
    persist_agent_self_check,
)
from skill_builder.application.implementation_evidence import (
    persist_implementation_evidence,
)
from skill_builder.application.offline_validation import (
    validation_output_directories,
)
from skill_builder.application.agent_submission import (
    candidate_completion_tool_schema,
    commit_candidate_completion,
)
from skill_builder.application.artifact_digest import skill_artifact_sha256
from skill_builder.application.candidate_projection import candidate_completion_from_draft
from skill_builder.application.candidate_submission import (
    CANDIDATE_SUBMISSION_DRAFT_PATH,
    compact_submission_failure,
)
from skill_builder.application.draft_workspace import DraftWorkspaceStore
from skill_builder.ports import SkillBuilderEventEmitter
from skill_builder.ports import ExecutionRequest, ExecutionResult
from skill_builder.runtime.serialization import json_safe


@dataclass(slots=True)
class CandidateToolState:
    submission_attempt_count: int = 0
    completion_payload: dict[str, Any] | None = None
    # One author-side execution is enough to provide actionable feedback.  The
    # final candidate submission still runs an independent Core acceptance.
    offline_self_check_runs: int = 0
    offline_self_check_payload: dict[str, Any] | None = None
    offline_self_check_artifact_sha256: str | None = None
    # Last rejected preflight, used to stop an idempotent retry that did not
    # change either the artifact or the blocking findings.  This is a
    # controller invariant, not a model instruction.
    last_rejection_signature: tuple[str, tuple[str, ...], str] | None = None


def _agent_acceptance_feedback(payload: dict[str, Any]) -> dict[str, Any]:
    """Project full acceptance into bounded, actionable Agent feedback."""

    findings = [
        item
        for item in payload.get("findings") or []
        if isinstance(item, dict)
    ]
    failed = [
        json_safe(
            {
                key: item.get(key)
                for key in (
                    "id",
                    "severity",
                    "path",
                    "message",
                    "details",
                    "repairable",
                    "failureOwner",
                )
                if item.get(key) not in (None, "", [])
            },
            max_text_length=1500,
            max_items=20,
        )
        for item in findings
        if item.get("severity") == "fail"
    ][:12]
    warnings = [
        {
            key: item.get(key)
            for key in ("id", "severity", "message", "reviewRequired")
            if item.get(key) not in (None, "")
        }
        for item in findings
        if item.get("severity") == "warn"
    ][:12]
    return {
        key: payload.get(key)
        for key in (
            "schemaVersion",
            "status",
            "outcome",
            "deliveryStatus",
            "blockingFailureIds",
            "blockingCheckIds",
        )
    } | {
        "findings": failed,
        "warnings": warnings,
        "checks": [
            {
                "id": item.get("id"),
                "status": item.get("status"),
            }
            for item in payload.get("checks") or []
            if isinstance(item, dict)
        ][:30],
    }


def candidate_repair_progress(
    previous: tuple[str, tuple[str, ...], str] | None,
    current: tuple[str, tuple[str, ...], str],
) -> tuple[bool, str]:
    """Classify deterministic progress between two rejected candidates.

    A changed file is not sufficient progress when the same structured
    blockers remain.  A repair progresses only when it removes every old
    blocker, strictly reduces the old set, or reaches a genuinely different
    blocker after changing the artifact.
    """

    if previous is None:
        return True, "initial_rejection"
    previous_error, previous_blockers, previous_artifact = previous
    current_error, current_blockers, current_artifact = current
    if previous_artifact == current_artifact:
        return False, "artifact_unchanged"
    old = set(previous_blockers)
    new = set(current_blockers)
    if old:
        if new < old:
            return True, "blockers_reduced"
        if old.isdisjoint(new):
            return True, "previous_blockers_removed"
        return False, "previous_blockers_remain"
    if previous_error == current_error:
        return False, "root_error_unchanged"
    return True, "root_error_changed"


def _persist_acceptance_artifacts(
    *,
    root: Path,
    accessor: Any,
    payload: dict[str, Any],
    agent_self_check: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Persist one acceptance fact and its Markdown projection.

    The accessor remains the only write path for host-backed workspaces.  A
    direct filesystem fallback keeps the standalone adapter usable when no
    sandbox writer is present; neither branch changes the acceptance result.
    """

    documents = {
        ACCEPTANCE_RESULT_PATH: json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        ACCEPTANCE_SUMMARY_PATH: render_acceptance_summary(payload, agent_self_check),
    }
    persisted: list[str] = []
    failures: list[str] = []
    writer = getattr(accessor, "write_validation_file", None)
    if not callable(writer):
        return persist_acceptance_files(root, payload, agent_self_check)
    fallback_needed = False
    for path, content in documents.items():
        relative = path.removeprefix("validation/")
        result: dict[str, Any] | None = None
        if callable(writer):
            try:
                result = writer(path=relative, content=content)
            except Exception as exc:  # diagnostics must not crash acceptance
                failures.append(f"{path}: {str(exc)[:300]}")
                fallback_needed = True
        if result is not None and result.get("ok"):
            persisted.append(path)
            continue
        if result is not None and not result.get("ok"):
            failures.append(f"{path}: {str(result.get('error') or 'write_failed')[:300]}")
            fallback_needed = True
    if fallback_needed:
        fallback = persist_acceptance_files(root, payload, agent_self_check)
        for written_path in fallback.get("paths") or []:
            if written_path not in persisted:
                persisted.append(written_path)
        failures.extend(str(error) for error in fallback.get("errors") or [])
    return {
        "ok": not failures,
        "paths": persisted,
        "errors": failures,
    }


def _persist_agent_self_check_artifact(
    *,
    root: Path,
    accessor: Any,
    value: dict[str, Any] | None,
) -> dict[str, Any] | None:
    """Persist the Agent report; deterministic reconciliation happens separately."""

    payload = normalize_agent_self_check(value)
    if payload is None:
        return None
    content = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    writer = getattr(accessor, "write_validation_file", None)
    if callable(writer):
        try:
            result = writer(
                path=AGENT_SELF_CHECK_PATH.removeprefix("validation/"),
                content=content,
            )
            if result and result.get("ok"):
                return payload
        except Exception:
            pass
    return persist_agent_self_check(root, payload)


class _AccessorExecutionPort:
    """Run one Core-planned offline smoke inside the active workspace sandbox."""

    def __init__(self, *, root: Path, accessor: Any) -> None:
        self.root = root.resolve()
        self.accessor = accessor

    async def run(self, request: ExecutionRequest) -> ExecutionResult:
        execute = getattr(self.accessor, "execute", None)
        if not callable(execute):
            raise RuntimeError("workspace accessor does not expose an execution port")
        cwd = Path(request.cwd).resolve()
        if cwd != self.root and not cwd.is_relative_to(self.root):
            raise ValueError("smoke cwd is outside the Skill Builder workspace")
        relative_cwd = cwd.relative_to(self.root).as_posix()
        workdir = "/workspace" if relative_cwd == "." else f"/workspace/{relative_cwd}"
        command = list(request.command)
        if command and Path(command[0]).name.startswith("python"):
            command[0] = "python3"
        # Core acceptance creates host-side output paths before execution, but
        # the command runs in a separate JiuwenBox filesystem.  Prepare the
        # bounded verify directory and any per-run --output-dir parent there
        # as well so scripts cannot fail merely because their declared output
        # parent is absent in the sandbox mirror.
        sandbox_output_dirs = validation_output_directories(
            command,
            workdir=workdir,
            verify_root="/workspace/workspace/verify",
        )
        mkdir_expr = (
            "import pathlib; "
            f"[pathlib.Path(p).mkdir(parents=True, exist_ok=True) for p in {json.dumps(sandbox_output_dirs)}]"
        )
        prepare_result = execute(
            [
                "python3",
                "-c",
                mkdir_expr,
            ],
            timeout_seconds=min(20, max(1, int(request.timeout_seconds))),
            workdir="/workspace",
            env={},
        )
        if inspect.isawaitable(prepare_result):
            prepare_result = await prepare_result
        if int(getattr(prepare_result, "exit_code", 1)) != 0:
            return ExecutionResult(
                exit_code=int(getattr(prepare_result, "exit_code", 1)),
                stdout=str(getattr(prepare_result, "stdout", "") or ""),
                stderr=str(getattr(prepare_result, "stderr", "") or ""),
            )
        execution_env = dict(request.env)
        # Generated CLIs are launched as ``python scripts/entry.py`` from the
        # package root.  In that form Python puts ``scripts/`` (not the
        # package root) on sys.path, so legitimate sibling imports such as
        # ``from scripts.validator import ...`` otherwise fail only during
        # sandbox replay.  The host adapter owns this portable package-root
        # invariant; callers may still extend/override it explicitly.
        package_python_path = "/workspace/generated-skill"
        configured_python_path = str(execution_env.get("PYTHONPATH") or "").strip()
        execution_env["PYTHONPATH"] = (
            f"{package_python_path}:{configured_python_path}"
            if configured_python_path
            else package_python_path
        )
        result = execute(
            command,
            timeout_seconds=max(1, int(request.timeout_seconds)),
            workdir=workdir,
            env=execution_env,
        )
        if inspect.isawaitable(result):
            result = await result
        sync_back = getattr(self.accessor, "sync_back", None)
        if callable(sync_back):
            sync_back(("workspace",))
        return ExecutionResult(
            exit_code=int(getattr(result, "exit_code", 1)),
            stdout=str(getattr(result, "stdout", "") or ""),
            stderr=str(getattr(result, "stderr", "") or ""),
        )


def _accessor_execution_port(*, root: Path, accessor: Any) -> _AccessorExecutionPort | None:
    if not callable(getattr(accessor, "execute", None)):
        return None
    return _AccessorExecutionPort(root=root, accessor=accessor)


async def _accept_with_execution_accessor(
    *,
    root: Path,
    accessor: Any,
    execution_accessor_factory: Callable[[], Any | None] | None,
    smoke_timeout_seconds: int,
    agent_self_check: dict[str, Any] | None = None,
) -> Any:
    """Run acceptance in a short-lived execution sandbox when available.

    Authoring accessors may outlive the sandbox daemon's own lease.  Validation
    must therefore not inherit their process lifecycle.  Host writes remain
    on the durable workspace, so a fresh accessor observes the exact candidate
    while keeping execution failures separate from package failures.
    """

    execution_accessor = accessor
    owns_execution_accessor = False
    if execution_accessor_factory is not None:
        candidate = execution_accessor_factory()
        if candidate is not None:
            execution_accessor = candidate
            owns_execution_accessor = candidate is not accessor
    try:
        cleanup = getattr(execution_accessor, "remove_workspace_paths", None)
        if callable(cleanup):
            cleanup_result = cleanup(["workspace/verify/*"])
            if isinstance(cleanup_result, dict) and not cleanup_result.get("ok"):
                raise RuntimeError(
                    "validation output cleanup failed: "
                    + str(
                        cleanup_result.get("message")
                        or cleanup_result.get("error")
                        or "unknown error"
                    )
                )
        execution_port = _accessor_execution_port(
            root=root,
            accessor=execution_accessor,
        )
        if execution_port is not None:
            seal_generated = getattr(
                execution_accessor,
                "seal_generated_skill_sync_back",
                None,
            )
            if callable(seal_generated):
                seal_generated()
        return await accept_skill_package(
            root,
            execution_port=execution_port,
            smoke_timeout_seconds=smoke_timeout_seconds,
            agent_self_check=agent_self_check,
        )
    finally:
        cleanup = getattr(execution_accessor, "remove_workspace_paths", None)
        if callable(cleanup):
            try:
                cleanup(["workspace/verify/*"])
            except Exception:
                pass
        if owns_execution_accessor:
            close = getattr(execution_accessor, "close", None)
            if callable(close):
                try:
                    result = close()
                    if inspect.isawaitable(result):
                        await result
                except Exception:
                    pass


def create_offline_self_check_tool(
    *,
    tool: Callable[..., Any],
    name: str,
    root: Path,
    accessor: Any,
    emit_event: SkillBuilderEventEmitter | None,
    state: CandidateToolState,
    author_self_check_max_runs: int = 4,
    max_repair_attempts: int = 1,
    task_mode: str = "author",
    initial_artifact_sha256: str | None = None,
    execution_accessor_factory: Callable[[], Any | None] | None = None,
) -> Any:
    """Expose one bounded, evidence-producing offline check to Author.

    The Agent may run the same deterministic Core acceptance that will be
    rerun by the controller after ``finish_authoring``. This is feedback for authoring/repair, never a
    commit decision; the clean final acceptance remains authoritative.
    """

    mode = str(task_mode or "author").strip().lower()
    total_run_limit = (
        1 + max(0, int(max_repair_attempts))
        if mode == "repair"
        else max(1, int(author_self_check_max_runs))
    )
    budget_description = (
        f"Repair allows {total_run_limit} total run(s): one initial run plus "
        f"{max(0, int(max_repair_attempts))} configured repair rerun(s)."
        if mode == "repair"
        else f"Author allows up to {total_run_limit} total run(s)."
    )

    @tool(
        name=name,
        description=(
            "Run the current generated Skill's bounded offline self-check. "
            f"{budget_description} After the first run, another run is allowed only after a failed "
            "check and a changed generated-skill artifact. "
            "Use the returned observed command, exit code, and result when filling "
            "agent_self_check; external browser/API capabilities remain unverified."
        ),
        input_params={"type": "object", "properties": {}},
    )
    async def run_offline_self_check() -> dict[str, Any]:
        current_artifact_sha256 = skill_artifact_sha256(root / "generated-skill")
        if (
            mode == "repair"
            and current_artifact_sha256 == initial_artifact_sha256
        ):
            return {
                "ok": False,
                "error": "repair_write_required",
                "message": (
                    "Repair 尚未改变 generated-skill。请先按结构化 findings 修改目标文件，"
                    "候选摘要变化后再运行完整离线自检。"
                ),
            }
        previous_status = str(
            (state.offline_self_check_payload or {}).get("status") or ""
        ).strip().lower()
        rerun_allowed = bool(
            1 <= state.offline_self_check_runs < total_run_limit
            and previous_status == "fail"
            and state.offline_self_check_artifact_sha256
            and current_artifact_sha256 != state.offline_self_check_artifact_sha256
        )
        if state.offline_self_check_runs >= 1 and not rerun_allowed:
            mode_label = "Repair" if mode == "repair" else "Author"
            return {
                "ok": False,
                "error": "offline_self_check_already_run",
                "message": (
                    f"{mode_label} 离线自验证已执行 {state.offline_self_check_runs} 次（总额度 "
                    f"{total_run_limit} 次）；只有上一轮结果为 fail、generated-skill 已实际修改且"
                    "本阶段额度未耗尽时才允许复验。请使用已有结果提交。"
                ),
            }
        state.offline_self_check_runs += 1
        state.offline_self_check_artifact_sha256 = current_artifact_sha256
        try:
            acceptance = await _accept_with_execution_accessor(
                root=root,
                accessor=accessor,
                execution_accessor_factory=execution_accessor_factory,
                smoke_timeout_seconds=60,
            )
            payload = acceptance_result_payload(acceptance)
            state.offline_self_check_payload = payload
            feedback = _agent_acceptance_feedback(payload)
            await _emit(
                emit_event,
                "agent.offline_self_check_completed",
                f"Agent 已执行一次离线自验证：{acceptance.status}",
                {
                    "status": acceptance.status,
                    "result": payload,
                },
            )
            return {
                "ok": True,
                "status": acceptance.status,
                "result": feedback,
                "message": (
                    "离线自验证已完成。若结果为 fail，只根据 observed findings 修复，"
                    "修改后按剩余额度复验；完成后调用 finish_authoring 提交 Agent 自检。"
                ),
            }
        except Exception as exc:  # pragma: no cover - defensive adapter boundary
            message = str(exc)[:1000]
            await _emit(
                emit_event,
                "agent.offline_self_check_completed",
                "Agent 离线自验证执行异常。",
                {"status": "fail", "error": message},
            )
            return {
                "ok": False,
                "status": "fail",
                "error": "offline_self_check_error",
                "message": message,
            }

    return run_offline_self_check


def create_candidate_tool(
    *,
    tool: Callable[..., Any],
    name: str,
    root: Path,
    candidate_submission_limit: int,
    accessor: Any,
    draft_workspace: DraftWorkspaceStore,
    emit_event: SkillBuilderEventEmitter | None,
    record_submission_failure: Callable[[dict[str, Any]], dict[str, Any]],
    clear_submission_failure: Callable[[], None],
    seal_candidate_handoff: Callable[[Any], bool],
    state: CandidateToolState,
    execution_accessor_factory: Callable[[], Any | None] | None = None,
) -> Any:
    async def _finish_draft_impl(
        summary: str = "",
        agent_self_check: dict[str, Any] | None = None,
        implementation_evidence: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        state.submission_attempt_count += 1
        acceptance_payload: dict[str, Any] | None = None
        acceptance_artifacts: dict[str, Any] | None = None
        # Materialize deterministic, privacy-safe schema fixtures before the
        # candidate snapshot. They validate fields and invalid-input behavior,
        # never business success.
        fixture_generation_error = ""
        try:
            ensure_synthetic_input_fixtures(root, root / "generated-skill")
        except (OSError, TypeError, ValueError) as exc:
            fixture_generation_error = str(exc)[:500]
        completion = candidate_completion_from_draft(
            root,
            summary=summary,
        )
        normalized_agent_self_check = normalize_agent_self_check(
            agent_self_check,
            generated_files=(completion.get("files") if isinstance(completion, dict) else None),
        )
        if implementation_evidence is not None:
            persist_implementation_evidence(root, implementation_evidence)

        def fail(value: dict[str, Any]) -> dict[str, Any]:
            result = {
                **value,
                "submissionAttempts": state.submission_attempt_count,
                "maxSubmissions": candidate_submission_limit,
                "maxRepairAttempts": max(candidate_submission_limit - 1, 0),
            }
            blocker_ids = tuple(
                sorted(
                    {
                        str(item).strip()
                        for item in result.get("blockingFindingIds") or []
                        if str(item).strip()
                    }
                )
            )
            artifact_digest = skill_artifact_sha256(root / "generated-skill") or ""
            rejection_signature = (
                str(result.get("error") or "candidate_submission_rejected"),
                blocker_ids,
                artifact_digest,
            )
            progress, progress_reason = candidate_repair_progress(
                state.last_rejection_signature,
                rejection_signature,
            )
            no_progress = not progress
            state.last_rejection_signature = rejection_signature
            # Keep recovery finite and explicit.  The model gets one precise
            # correction opportunity inside this Author turn; after that the
            # phase terminates and the invalid draft remains available for a
            # user-triggered repair.  This is feedback, not an automatic
            # repair loop.
            remaining = max(candidate_submission_limit - state.submission_attempt_count, 0)
            if str(result.get("stage") or "") == "draft_acceptance":
                repairable = bool(result.get("repairable", False))
                repair_allowed = repairable and remaining > 0 and not no_progress
                result["repair"] = {
                    "allowed": repair_allowed,
                    "attempt": state.submission_attempt_count,
                    "remaining": remaining,
                    "fullPreflightRequired": True,
                    "noProgress": no_progress,
                    "progressReason": progress_reason,
                    "instruction": (
                        "候选摘要和阻断项与上一轮完全一致，判定本次提交没有修复进展；"
                        "停止继续改写，保留当前诊断。"
                        if no_progress
                        else "只修复 acceptance 返回的、repairable=true 的阻断项，保留已经正确的文件和业务规则；"
                        "当前 Agent 阶段结束后由 workflow 启动独立 Repair 并重新执行全部预检。"
                        if repair_allowed
                        else "当前阻断项不可自动修复或本轮修复额度已用尽；停止继续改写，等待显式用户修订。"
                    ),
                }
            draft_workspace.record_diagnostic(
                {
                    "stage": str(result.get("stage") or "finish_draft"),
                    "error": str(result.get("error") or "candidate_submission_rejected"),
                    "message": str(result.get("message") or "")[:1000],
                },
                phase="candidate_draft_rejected",
            )
            persisted = accessor.write_validation_file(
                path="diagnostics/candidate_submission.json",
                content=json.dumps(
                    {
                        "schemaVersion": "skill-builder-candidate-submission-draft/v2",
                        "completion": completion,
                        "packageArtifactSha256": skill_artifact_sha256(
                            root / "generated-skill"
                        ),
                        "failure": compact_submission_failure(result),
                    },
                    ensure_ascii=False,
                    indent=2,
                )
                + "\n",
            )
            result["candidateSubmissionDraft"] = CANDIDATE_SUBMISSION_DRAFT_PATH
            result["candidateSubmissionPersisted"] = bool(persisted.get("ok"))
            if acceptance_payload is not None:
                result["acceptance"] = acceptance_payload
            if acceptance_artifacts is not None:
                result["acceptanceArtifacts"] = acceptance_artifacts
            if normalized_agent_self_check is not None:
                result["agentSelfCheck"] = normalized_agent_self_check
            record_submission_failure(result)
            terminal_rejection = bool(
                state.submission_attempt_count >= candidate_submission_limit
                or (
                    str(result.get("stage") or "") == "draft_acceptance"
                    and not bool((result.get("repair") or {}).get("allowed"))
                )
            )
            if terminal_rejection:
                code = str(result.get("error") or "draft_package_invalid")
                state.completion_payload = {
                    "status": "failed",
                    "summary": "Skill 草稿未通过平台验收，当前 Draft 和验收诊断已保留。",
                    "files": sorted(set(accessor.files_written)),
                    "completion_source": "candidate_submission_rejected",
                    "lifecycle_failure": {
                        "code": code,
                        "phase": "author",
                        "last_submission_failure": result,
                    },
                }
                if normalized_agent_self_check is not None:
                    state.completion_payload["agent_self_check"] = normalized_agent_self_check
                result.update({"terminal": True, "next_action": "stop_current_phase"})
            return result

        if fixture_generation_error:
            return fail(
                {
                    "ok": False,
                    "completed": False,
                    "stage": "candidate_preparation",
                    "error": "synthetic_input_fixture_generation_failed",
                    "message": (
                        "平台无法生成结构化输入的最小脱敏 fixture："
                        f"{fixture_generation_error}"
                    ),
                    "repairable": False,
                    "failureOwners": ["controller"],
                }
            )

        try:
            draft_revision = draft_workspace.snapshot_revision(phase="package_prepared")
        except (OSError, TypeError, ValueError) as exc:
            return fail(
                {
                    "ok": False,
                    "completed": False,
                    "stage": "draft_snapshot",
                    "error": "draft_snapshot_failed",
                    "message": str(exc)[:500],
                }
            )

        try:
            acceptance = await _accept_with_execution_accessor(
                root=root,
                accessor=accessor,
                execution_accessor_factory=execution_accessor_factory,
                smoke_timeout_seconds=60,
                agent_self_check=normalized_agent_self_check,
            )
        except Exception as exc:  # acceptance must not crash the Agent worker
            acceptance = None
            acceptance_error = str(exc)[:1000]
        else:
            acceptance_error = ""
        acceptance_payload = (
            acceptance_result_payload(acceptance)
            if acceptance is not None
            else acceptance_exception_payload(acceptance_error or "未知验收错误")
        )
        acceptance_artifacts = _persist_acceptance_artifacts(
            root=root,
            accessor=accessor,
            payload=acceptance_payload,
            agent_self_check=normalized_agent_self_check,
        )
        normalized_agent_self_check = _persist_agent_self_check_artifact(
            root=root,
            accessor=accessor,
            value=normalized_agent_self_check,
        )
        if normalized_agent_self_check is not None:
            await _emit(
                emit_event,
                "agent.self_check_completed",
                str(normalized_agent_self_check.get("summary") or "Agent 已完成本轮自验证。")[:500],
                {
                    "status": normalized_agent_self_check.get("status"),
                    "result": normalized_agent_self_check,
                },
            )
        await _emit(
            emit_event,
            "skill.preflight_completed",
            str(
                (
                    (acceptance_payload.get("summary") or {}).get("message")
                    if isinstance(acceptance_payload.get("summary"), dict)
                    else ""
                )
                or "Skill 生成预检已完成。"
            )[:500],
            {
                "status": acceptance_payload.get("status"),
                "validationPhase": "preflight",
                "deliveryValidationStatus": "not_started",
                "result": {
                    **acceptance_payload,
                    "validationPhase": "preflight",
                    "deliveryValidationStatus": "not_started",
                },
            },
        )
        if acceptance_error or (
            acceptance is not None and acceptance.status == "fail"
        ):
            blocking_findings = [
                item
                for item in (acceptance.findings if acceptance is not None else ())
                if item.get("severity") == "fail"
            ]
            first_finding = (
                next(
                    (
                        item
                        for item in (acceptance.findings if acceptance is not None else ())
                        if item.get("severity") == "fail"
                    ),
                    None,
                )
                if acceptance is not None
                else None
            )
            failure_id = str((first_finding or {}).get("id") or "offline_smoke_failed")
            failure_message = (
                acceptance_error
                or str((first_finding or {}).get("message") or "Skill 自验收发现阻断问题。")
            )
            repairable = bool(
                not acceptance_error
                and blocking_findings
                and all(
                    bool(item.get("repairable"))
                    and str(item.get("failureOwner") or "package") == "package"
                    for item in blocking_findings
                )
            )
            failure_owners = sorted(
                {
                    str(item.get("failureOwner") or "package")
                    for item in blocking_findings
                }
            )
            return fail(
                {
                    "ok": False,
                    "completed": False,
                    "stage": "draft_acceptance",
                    "error": failure_id,
                    "message": failure_message,
                    "repairable": repairable,
                    "failureOwners": failure_owners,
                    "blockingFindingIds": [
                        str(item.get("id"))
                        for item in blocking_findings
                        if item.get("id")
                    ],
                    "acceptance": acceptance_payload,
                    "acceptanceArtifacts": acceptance_artifacts,
                    "agentSelfCheck": normalized_agent_self_check,
                }
            )
        finish_result = commit_candidate_completion(
            root=root,
            completion=completion,
            draft_revision=draft_revision.get("draftRevision"),
            acceptance_result=acceptance_payload,
            acceptance_artifacts=acceptance_artifacts,
            agent_self_check=normalized_agent_self_check,
        )
        if not finish_result.get("ok"):
            return fail(finish_result)
        seal_candidate_handoff(accessor)
        payload = finish_result.get("completion")
        if not isinstance(payload, dict):
            return fail(
                {
                    "ok": False,
                    "completed": False,
                    "stage": "candidate_commit",
                    "error": "candidate_completion_payload_missing",
                }
            )
        state.completion_payload = payload
        clear_submission_failure()
        await _emit(
            emit_event,
            "agent.finish_requested",
            "Agent 已提交可编辑、可打包的 Skill 草稿。",
            {
                "status": payload.get("status"),
                "files": (payload.get("files") or [])[:40],
                "candidate_commit": finish_result.get("candidate_commit"),
                "validation_status": payload.get("validation_status") or "not_run",
                "acceptance": payload.get("acceptance"),
                "agent_self_check": payload.get("agent_self_check"),
            },
        )
        return finish_result

    # Keep the decorated tool for the model-facing SDK, but expose the exact
    # coroutine used by the controller's finalization checkpoint.  Calling a
    # Jiuwen ``LocalFunction`` as a Python function raises ``object is not
    # callable``; the controller must bypass the transport wrapper while
    # retaining the same implementation and validation boundary.
    finish_draft = tool(
        name=name,
        description=(
            "Finish the current persistent Skill draft. The controller checks the minimum "
            "portable package structure, runs at most one bounded offline smoke when the "
            "workspace sandbox provides it, snapshots it, and commits one package revision."
        ),
        input_params=candidate_completion_tool_schema(),
    )(_finish_draft_impl)
    try:
        setattr(finish_draft, "invoke_direct", _finish_draft_impl)
    except Exception:
        # Some hosts may return an immutable tool proxy.  The normal model
        # path still works; the runtime falls back to ``invoke`` below.
        pass
    return finish_draft


__all__ = [
    "CandidateToolState",
    "candidate_repair_progress",
    "create_candidate_tool",
    "create_offline_self_check_tool",
]
