"""OpenJiuwen implementation of the single-turn Skill Builder Agent runtime.

The adapter imports its explicit application dependencies and owns OpenJiuwen
tool registration, streaming, and finalization for one Agent turn. No host or
module namespace is injected into the runtime.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import inspect
import logging
import os
import time
import uuid
from pathlib import Path
from typing import Any, NamedTuple

from skill_builder.application.agent_core import (
    DEFAULT_IDLE_TIMEOUT_SECONDS,
    DEFAULT_MAX_STREAM_CHUNKS,
    DEFAULT_NO_CHECKPOINT_CHUNK_LIMIT,
    DEFAULT_NO_CHECKPOINT_SECONDS_LIMIT,
    DEFAULT_NO_WRITE_CHUNK_LIMIT,
    DEFAULT_NO_WRITE_SECONDS_LIMIT,
    SkillBuilderAgentCoreError,
    SkillBuilderAgentCoreResult,
    SkillBuilderAgentLifecycleError,
    SkillBuilderAgentRuntimeUnavailableError,
    SkillBuilderWorkspaceAccessor,
    _agent_expose_sys_read_file_enabled,
    _agent_native_skill_registration_enabled,
    _agent_preload_internal_skills_enabled,
    _artifact_digests,
    _build_system_prompt,
    _build_user_prompt,
    _checkpoint_relative_path,
    _checkpoint_required_message,
    _current_run_artifact_signature,
    _emit,
    _file_digest,
    _has_agent_artifact_progress,
    _has_checkpoint_available,
    _positive_int_env,
    _result_error,
    _task_mode_for_run_phase,
)
from skill_builder.application.agent_policy import (
    agent_model_tool_choice,
    author_model_tool_choice,
    author_no_tool_progress_is_stalled,
    author_observation_tool_is_progress,
    author_tool_progress_is_stalled,
    agent_runtime_iteration_limit,
    agent_stream_deadline_reason,
    agent_stream_wait_timeout,
    agent_timeout_budget,
    agent_tool_capabilities,
)
from skill_builder.application.artifact_digest import skill_artifact_sha256
from skill_builder.application.fixture_builder import ensure_synthetic_input_fixtures
from skill_builder.application.agent_self_check import (
    AGENT_SELF_CHECK_PATH,
    normalize_agent_self_check,
)
from skill_builder.application.implementation_evidence import load_implementation_evidence
from skill_builder.application.implementation_plan import (
    load_implementation_plan,
    missing_required_plan_paths,
    projected_scripts_required,
)
from skill_builder.application.draft_workspace import DraftWorkspaceStore
from skill_builder.application.scenario_projection import (
    AUTHOR_HANDOFF_MAX_BYTES,
    AUTHOR_HANDOFF_SCHEMA_VERSION,
    load_persisted_scenario_contract,
    scenario_projection_matches,
)
from skill_builder.runtime.agent_stream import (
    IncrementalTextProjector,
    OwnedAgentStreamTerminal,
    _agent_core_cleanup_compatibility_error,
    _agent_human_summary,
    _agent_runtime_failure_code,
    _agent_runtime_failure_message,
    _agent_stream_transient_error,
    _chunk_attr,
    _chunk_text,
    _normalize_agent_display_text,
    _parse_agent_json,
    _payload_dict,
    _result_content,
    _stream_chunk_events,
    start_owned_agent_stream,
)
from skill_builder.runtime.llm_settings import SkillBuilderLLMConfigError, resolve_skill_builder_llm_settings
from skill_builder.runtime.repair_settings import (
    repair_reserve_is_active,
    resolve_author_self_check_max_runs,
    resolve_max_repair_attempts,
    resolve_repair_reserve_timeout_seconds,
)
from skill_builder.resources import (
    AGENT_CORE_SKILLS_ROOT,
    build_internal_skill_context,
    build_internal_skill_routing_context,
    install_skill_builder_resources,
    internal_skill_context_paths,
)
from skill_builder.runtime.serialization import json_safe


class _AuthorProgressSignature(NamedTuple):
    artifact_sha256: str
    files_read: tuple[str, ...]
    files_listed: tuple[str, ...]
    implementation_plan_sha256: str
    offline_self_check_runs: int
    offline_self_check_status: str
from skill_builder.adapters.author_tools import (
    AuthorCompletionState,
    create_author_completion_tool,
    create_author_tools,
)
from skill_builder.adapters.candidate_tools import (
    CandidateToolState,
    _accept_with_execution_accessor,
    create_candidate_tool,
    create_offline_self_check_tool,
)
from skill_builder.adapters.scenario_tools import create_scenario_tools
from skill_builder.adapters.workspace_tools import (
    WorkspaceReadState,
    create_workspace_read_tools,
)
from skill_builder.ports import SkillBuilderEventEmitter, SkillBuilderWorkspacePort


logger = logging.getLogger(__name__)


def _author_tool_result_identity(event: dict[str, Any]) -> str:
    """Return one stable identity so repeated stream projections count once."""

    payload = event.get("payload") if isinstance(event.get("payload"), dict) else {}
    invoke_id = str(payload.get("invokeId") or "").strip()
    if invoke_id:
        return invoke_id
    return json.dumps(
        {
            key: payload.get(key)
            for key in ("name", "status", "startTime", "endTime", "inputs", "error")
        },
        ensure_ascii=False,
        sort_keys=True,
        default=str,
    )


def _configured_agent_iteration_limit(task_mode: str) -> int | None:
    """Resolve a phase override before the backward-compatible global value."""

    phase_name = str(task_mode or "").strip().upper()
    raw = (
        os.getenv(f"SKILL_BUILDER_AGENT_{phase_name}_MAX_ITERATIONS")
        if phase_name
        else None
    )
    if not raw and phase_name in {"AUTHOR_BUILD", "AUTHOR_VALIDATE"}:
        raw = os.getenv("SKILL_BUILDER_AGENT_AUTHOR_MAX_ITERATIONS")
    raw = raw or os.getenv("SKILL_BUILDER_AGENT_MAX_ITERATIONS")
    if not str(raw or "").strip():
        return None
    try:
        return int(str(raw).strip())
    except ValueError:
        return None


def _load_persisted_agent_self_check(root: Path) -> dict[str, Any] | None:
    """Load the last durable Agent report for controller-owned Repair close."""

    try:
        value = json.loads((root / AGENT_SELF_CHECK_PATH).read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError):
        return None
    return normalize_agent_self_check(value)


def _load_persisted_implementation_evidence(root: Path) -> list[dict[str, Any]] | None:
    """Load the durable evidence graph for controller-owned finalization."""

    value = load_implementation_evidence(root)
    return value or None


def _repair_offline_preflight_complete(
    *,
    task_mode: str,
    state: CandidateToolState,
) -> bool:
    """Return whether Repair has obtained an authoritative non-blocking replay."""

    status = str((state.offline_self_check_payload or {}).get("status") or "").strip().lower()
    return bool(task_mode == "repair" and status in {"pass", "warn"})


def _load_valid_author_handoff(
    root: Path,
    *,
    task_mode: str,
) -> dict[str, Any] | None:
    """Load only the current controller-owned Scenario-to-Author handoff."""

    if task_mode not in {"author", "author_build", "author_validate"}:
        return None
    contract, contract_issues = load_persisted_scenario_contract(root)
    if contract_issues or not scenario_projection_matches(root, contract):
        return None
    try:
        raw = (root / "validation" / "author_handoff.json").read_bytes()
        loaded = json.loads(raw.decode("utf-8"))
    except (OSError, TypeError, ValueError):
        return None
    invalid_handoff = (
        len(raw) > AUTHOR_HANDOFF_MAX_BYTES
        or not isinstance(loaded, dict)
        or loaded.get("schemaVersion") != AUTHOR_HANDOFF_SCHEMA_VERSION
        or not str(loaded.get("scenarioContractHash") or "").strip()
    )
    if invalid_handoff:
        return None
    return loaded


def _recoverable_tool_input_validation_error(payload: Any) -> bool:
    """Return whether OpenJiuwen rejected model arguments before tool entry.

    OpenJiuwen currently reports missing/invalid inputs as 189001 (validation)
    and extra inputs as 189002 (formatting).  Both are the same transport
    boundary: the registered tool was never entered.  The controller can
    therefore allow one correction and still fall back to deterministic
    finalization for an already-persisted candidate.
    """

    if not isinstance(payload, dict):
        return False
    error = payload.get("error")
    error_code = error.get("error_code") if isinstance(error, dict) else None
    message_values = (
        error.get("message") if isinstance(error, dict) else error,
        payload.get("message"),
        payload.get("exception"),
    )
    message = " ".join(str(value or "") for value in message_values).lower()
    normalized_code = str(error_code or "").strip()
    schema_failure = (
        "validation error for dynamicmodel" in message
        and (
            "validate data with schema failed" in message
            or "format data with schema failed" in message
        )
    )
    return normalized_code in {"189001", "189002"} or schema_failure


def _seal_candidate_handoff(accessor: Any) -> bool:
    """Prevent close-time sandbox sync from overwriting a signed host candidate.

    Agent writes are mirrored to the host eagerly and the package revision is
    signed from that snapshot. A generic sandbox close must not overwrite the
    committed draft with an older sandbox copy.
    """

    seal_sync_back = getattr(accessor, "seal_candidate_sync_back", None)
    if not callable(seal_sync_back):
        return False
    seal_sync_back()
    return True


def _controller_finalization_state(
    *,
    completion_payload: dict[str, Any] | None,
    active_submission_failure: dict[str, Any] | None,
    previous_submission_failure: dict[str, Any] | None,
) -> tuple[bool, bool]:
    """Return ``(required, repaired_after_rejection)`` for final preflight."""

    terminal_rejection = bool(
        isinstance(completion_payload, dict)
        and completion_payload.get("completion_source")
        == "candidate_submission_rejected"
    )
    repaired_after_rejection = bool(
        active_submission_failure is None
        and isinstance(previous_submission_failure, dict)
        and (completion_payload is None or terminal_rejection)
    )
    return completion_payload is None or repaired_after_rejection, repaired_after_rejection


async def _invoke_candidate_finalization(
    candidate_tool: Any,
    *,
    summary: str,
    agent_self_check: dict[str, Any] | None,
    implementation_evidence: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Invoke the candidate implementation without assuming a callable SDK tool."""

    payload: dict[str, Any] = {
        "summary": summary,
        "agent_self_check": agent_self_check,
    }
    if implementation_evidence is not None:
        payload["implementation_evidence"] = implementation_evidence
    direct = getattr(candidate_tool, "invoke_direct", None)
    if callable(direct):
        result = direct(**payload)
        return await result if inspect.isawaitable(result) else result
    invoke = getattr(candidate_tool, "invoke", None)
    if callable(invoke):
        result = invoke(payload)
        return await result if inspect.isawaitable(result) else result
    raise TypeError("candidate tool provides neither invoke_direct nor invoke")


async def _invoke_author_build_finalization(author_tool: Any) -> dict[str, Any]:
    """Close a complete Build artifact when the model omitted only finish."""

    payload = {
        "summary": "Core 最终检查点：生产包已物化，执行静态 Build preflight。",
        "agent_self_check": {
            "status": "partial",
            "summary": "Core 仅执行静态 Build preflight；交付运行验证尚未执行。",
            "checks": [],
            "unverified": ["交付 CLI smoke 与外部能力验证"],
        },
        "implementation_evidence": [],
    }
    direct = getattr(author_tool, "invoke_direct", None)
    if callable(direct):
        result = direct(**payload)
        return await result if inspect.isawaitable(result) else result
    invoke = getattr(author_tool, "invoke", None)
    if callable(invoke):
        result = invoke(payload)
        return await result if inspect.isawaitable(result) else result
    raise TypeError("author completion tool provides neither invoke_direct nor invoke")


def _author_build_needs_controller_finalization(
    *,
    task_mode: str,
    completion_present: bool,
    artifact_present: bool,
) -> bool:
    return bool(
        str(task_mode or "").strip().lower() == "author_build"
        and not completion_present
        and artifact_present
    )


def _can_finalize_after_agent_failure(
    *,
    task_mode: str,
    root: Path,
    completion_payload: dict[str, Any] | None,
) -> bool:
    """Allow one controller-owned commit attempt after a bounded Agent failure.

    A phase timeout is a failure of the Agent turn, not proof that the durable
    candidate is invalid.  If Author/Repair already produced the required
    checkpoint, the controller must run the same complete candidate gate once
    before closing the sandbox and surfacing the timeout.
    """

    return bool(
        task_mode in {"author", "author_build", "author_validate", "repair"}
        and completion_payload is None
        and skill_artifact_sha256(root / "generated-skill") is not None
    )


def _deferred_agent_failure_remains_authoritative(
    *,
    deferred_failure_present: bool,
    completion_payload: dict[str, Any] | None,
    submission_failure: dict[str, Any] | None,
) -> bool:
    """Prefer a deterministic candidate result over a later transport failure."""

    return bool(
        deferred_failure_present
        and completion_payload is None
        and submission_failure is None
    )


def _can_finalize_after_tool_input_failure(
    *,
    task_mode: str,
    root: Path,
    completion_payload: dict[str, Any] | None,
    validation_failure_count: int,
) -> bool:
    """Use deterministic preflight after the model repeats malformed tool input."""

    return bool(
        validation_failure_count > 1
        and _can_finalize_after_agent_failure(
            task_mode=task_mode,
            root=root,
            completion_payload=completion_payload,
        )
    )


async def run_skill_builder_agent_runtime(
    *,
    root: Path,
    workspace_id: str,
    skill_name: str,
    display_name: str,
    description: str,
    version: str,
    tags: list[str],
    user_message: str,
    materials_markdown: str,
    emit_event: SkillBuilderEventEmitter | None = None,
    run_phase: str = "initial",
    timeout_seconds: int | None = None,
    workspace: SkillBuilderWorkspacePort | None = None,
) -> SkillBuilderAgentCoreResult:

    # Keep runtime dependencies late-bound so public API monkeypatches and
    # deployment-specific adapters remain effective after this split.
    try:
        llm_settings = resolve_skill_builder_llm_settings()
    except SkillBuilderLLMConfigError as exc:
        raise SkillBuilderAgentCoreError(f"Skill 抽取所需 LLM 配置不可用：{exc}") from exc

    try:
        from openjiuwen.core.foundation.llm import Model, ModelClientConfig, ModelRequestConfig  # type: ignore
        from openjiuwen.core.foundation.tool import tool  # type: ignore
        from openjiuwen.core.runner import Runner  # type: ignore
        from openjiuwen.core.single_agent import (  # type: ignore
            AgentCard,
            ReActAgent,
            ReActAgentConfig,
            create_agent_session,
        )
        from openjiuwen.core.sys_operation import LocalWorkConfig, OperationMode, SysOperationCard  # type: ignore
        from skill_builder.adapters.openjiuwen_context import skill_builder_context_processors
        from skill_builder.adapters.openjiuwen_request_budget import SkillBuilderBudgetedModel
    except ModuleNotFoundError as exc:
        missing_name = exc.name or "openjiuwen"
        raise SkillBuilderAgentCoreError(
            f"Skill 抽取所需 Agent Core 依赖不可用：缺少 Python 模块 {missing_name}。"
            "请安装 openjiuwen-skill-builder[agent-openjiuwen-python]，并使用同一 Python 环境启动宿主。"
        ) from exc

    task_mode = _task_mode_for_run_phase(run_phase)
    author_modes = {"author", "author_build", "author_validate"}
    candidate_modes = {"author", "author_validate", "repair"}
    interactive_mode = task_mode in {"chat", "edit"}
    author_handoff = _load_valid_author_handoff(root, task_mode=task_mode)
    draft_workspace = DraftWorkspaceStore(root)
    draft_recovery = (
        draft_workspace.materialize_active_if_needed()
        if task_mode in author_modes | {"repair"}
        else {"ok": True, "materialized": False}
    )
    if draft_recovery.get("materialized"):
        await _emit(
            emit_event,
            "agent.draft_materialized",
            "已从持久化 Draft Workspace 恢复上一轮候选，继续当前职责。",
            draft_recovery,
        )
    if task_mode in author_modes | {"repair"}:
        # Create platform-owned structured fixtures before the model receives
        # write tools. This prevents a Repair turn from attempting to build or
        # overwrite opaque binary samples such as invalid.xlsx.
        with contextlib.suppress(OSError, TypeError, ValueError):
            ensure_synthetic_input_fixtures(root, root / "generated-skill")
    register_internal_skills = _agent_native_skill_registration_enabled(
        task_mode,
        interactive_mode=interactive_mode,
    )
    preload_internal_skills = (
        True
        if not interactive_mode
        else _agent_preload_internal_skills_enabled(
            register_internal_skills=register_internal_skills,
        )
    )
    internal_skill_context = (
        ""
        if interactive_mode
        else (
            build_internal_skill_context(task_mode)
            if preload_internal_skills
            else build_internal_skill_routing_context(task_mode)
        )
    )
    preloaded_context_paths = (
        internal_skill_context_paths(task_mode)
        if preload_internal_skills
        else []
    )
    installed_resources = (
        []
        if interactive_mode
        else install_skill_builder_resources(root, task_mode=task_mode)
    )
    if installed_resources:
        await _emit(
            emit_event,
            "agent.resources_installed",
            "已安装 Skill 抽取内置能力资源，并将资源副本写入当前工作区。",
            {
                "resources": installed_resources,
                "preloaded": bool(preload_internal_skills),
                "preloaded_context_paths": preloaded_context_paths,
                "native_skill_registration": bool(register_internal_skills),
                "task_mode": task_mode,
            },
        )

    sandbox_accessor = (
        workspace.create_accessor(root=root, workspace_id=workspace_id, purpose=run_phase)
        if workspace is not None
        else None
    )
    accessor = sandbox_accessor or SkillBuilderWorkspaceAccessor(root=root, purpose=run_phase)
    execution_accessor_factory = (
        (
            lambda: workspace.create_accessor(
                root=root,
                workspace_id=workspace_id,
                purpose="acceptance",
            )
        )
        if workspace is not None
        else None
    )
    if sandbox_accessor is not None:
        await _emit(
            emit_event,
            "sandbox.created",
            "Skill 抽取沙箱已创建，Agent 文件读写将在沙箱工作区内执行。",
            {**sandbox_accessor.sandbox_ref, "phase": run_phase},
        )

    session_prefix = f"skill-builder-{workspace_id[:16]}"
    session_id = f"{session_prefix}-{uuid.uuid4().hex}"
    tool_run_token = f"{workspace_id[:12]}_{uuid.uuid4().hex[:8]}"
    tool_names = {
        "list_workspace_files": f"skill_builder_list_workspace_files_{tool_run_token}",
        "read_workspace_file": f"skill_builder_read_workspace_file_{tool_run_token}",
        "read_material_bundle": f"skill_builder_read_material_bundle_{tool_run_token}",
        "write_skill_file": f"skill_builder_write_skill_file_{tool_run_token}",
        "write_skill_files": f"skill_builder_write_skill_files_{tool_run_token}",
        "write_tabular_fixture": f"skill_builder_write_tabular_fixture_{tool_run_token}",
        "write_self_check_plan": f"skill_builder_write_self_check_plan_{tool_run_token}",
        "replace_skill_file_text": f"skill_builder_replace_skill_file_text_{tool_run_token}",
        "delete_skill_file": f"skill_builder_delete_skill_file_{tool_run_token}",
        "run_offline_self_check": f"skill_builder_run_offline_self_check_{tool_run_token}",
        "write_scenario_draft": f"skill_builder_write_scenario_draft_{tool_run_token}",
        "finish_authoring": f"skill_builder_finish_authoring_{tool_run_token}",
        "finish_draft": f"skill_builder_finish_draft_{tool_run_token}",
    }

    checkpoint_rel = _checkpoint_relative_path(task_mode)
    checkpoint_path = root / checkpoint_rel
    initial_checkpoint_digest = _file_digest(checkpoint_path)
    # Candidate tools close over the revision baseline during registration.
    # Capture it before tool construction; assigning it only immediately before
    # the Agent run leaves normal Scenario/Author startup with an unbound local.
    initial_artifact_digests = _artifact_digests(root)
    initial_skill_artifact_sha256 = skill_artifact_sha256(root / "generated-skill")
    max_repair_attempts = resolve_max_repair_attempts()
    author_self_check_max_runs = resolve_author_self_check_max_runs()
    repair_reserve_timeout_seconds = resolve_repair_reserve_timeout_seconds()
    # One Agent phase owns one candidate attempt.  A repairable preflight
    # failure is handed to a fresh Repair phase by the workflow controller,
    # so Author and Repair never compete for the same ReAct iteration budget.
    candidate_submission_limit = 1
    candidate_tool_state = CandidateToolState()
    author_completion_state = AuthorCompletionState()
    tool_input_validation_failure_counts: dict[str, int] = {}
    # One diagnostic owner for every atomic submission boundary.  Keeping a
    # verification-only value caused iteration-limit failures to display an
    # older intent error after a newer smoke or package-commit failure.
    last_submission_failure: dict[str, Any] | None = None
    last_submission_failure_signature: tuple[tuple[str, str | None], ...] | None = None
    previous_submission_failure: dict[str, Any] | None = None

    def record_submission_failure(value: dict[str, Any]) -> dict[str, Any]:
        """Bind one submission diagnostic to the exact candidate it observed."""

        nonlocal last_submission_failure, last_submission_failure_signature, previous_submission_failure
        last_submission_failure = json_safe(value, max_text_length=12000)
        previous_submission_failure = last_submission_failure
        last_submission_failure_signature = _current_run_artifact_signature(root, accessor)
        return value

    def current_submission_failure() -> dict[str, Any] | None:
        """Return the failure only while the candidate artifact digest matches."""

        nonlocal last_submission_failure, last_submission_failure_signature
        if not isinstance(last_submission_failure, dict):
            return None
        current_signature = _current_run_artifact_signature(root, accessor)
        if last_submission_failure_signature != current_signature:
            last_submission_failure = None
            last_submission_failure_signature = None
            return None
        return last_submission_failure

    def clear_submission_failure() -> None:
        nonlocal last_submission_failure, last_submission_failure_signature, previous_submission_failure
        last_submission_failure = None
        last_submission_failure_signature = None
        previous_submission_failure = None

    def incomplete_lifecycle_error(message: str) -> SkillBuilderAgentLifecycleError:
        logical_phase = task_mode
        active_failure = current_submission_failure()
        historical_failure = previous_submission_failure
        repair_changed_candidate = (
            active_failure is None and isinstance(historical_failure, dict)
        )
        active_code = str(
            active_failure.get("error") if isinstance(active_failure, dict) else ""
        ).strip()
        code = active_code or (
            "candidate_resubmission_missing_after_repair"
            if repair_changed_candidate
            else
            "scenario_submission_missing"
            if logical_phase == "scenario"
            else "candidate_completion_missing"
        )
        active_message = str(
            active_failure.get("message") if isinstance(active_failure, dict) else ""
        ).strip()
        if active_message:
            message = f"{message} 最近一次结构化提交失败：{active_code or 'submission_invalid'}: {active_message[:1000]}"
        elif repair_changed_candidate:
            previous_code = str(historical_failure.get("error") or "candidate_submission_invalid")
            message = (
                f"{message} 候选已在最近一次提交失败（{previous_code}）后发生修改，"
                "但未提交新的 Author 自检完成信号。"
            )
        return SkillBuilderAgentLifecycleError(
            message,
            code=code,
            phase=logical_phase,
        )

    def artifact_checkpoint_ready() -> bool:
        try:
            return _has_checkpoint_available(root, accessor, checkpoint_path, initial_digest=initial_checkpoint_digest)
        except NameError:
            return bool(getattr(accessor, "files_written", None))

    def checkpoint_required_result(action: str) -> dict[str, Any]:
        return {
            "ok": False,
            "error": "artifact_checkpoint_required",
            "message": _checkpoint_required_message(task_mode, action),
        }

    workspace_read_tools = create_workspace_read_tools(
        tool=tool,
        names=tool_names,
        root=root,
        task_mode=task_mode,
        responsibility_phase=run_phase,
        accessor=accessor,
        materials_markdown=materials_markdown,
        emit_event=emit_event,
        state=WorkspaceReadState(),
        author_handoff_available=author_handoff is not None,
    )
    list_workspace_files = workspace_read_tools.list_files
    read_workspace_file = workspace_read_tools.read_file
    read_material_bundle_tool = workspace_read_tools.read_material_bundle

    def set_scenario_completion(value: dict[str, Any]) -> None:
        candidate_tool_state.completion_payload = value

    scenario_tools = create_scenario_tools(
        tool=tool,
        names=tool_names,
        task_mode=task_mode,
        accessor=accessor,
        draft_workspace=draft_workspace,
        emit_event=emit_event,
        record_failure=record_submission_failure,
        clear_failure=clear_submission_failure,
        set_completion=set_scenario_completion,
        authoritative_request_text=description,
    )
    write_scenario_draft = scenario_tools.write

    def set_scenario_protocol_failure(
        *,
        code: str,
        summary: str,
        details: dict[str, Any] | None = None,
    ) -> None:
        if task_mode != "scenario" or scenario_tools.state.committed:
            return
        set_scenario_completion(
            {
                "status": "failed",
                "summary": summary,
                "files": sorted(accessor.files_written),
                "completion_source": "scenario_protocol_failure",
                "lifecycle_failure": {
                    "code": code,
                    "rootBlockerCode": code,
                    "terminationCode": code,
                    "phase": "scenario",
                    **(details or {}),
                },
            }
        )

    author_tools = create_author_tools(
        tool=tool,
        names=tool_names,
        root=root,
        task_mode=task_mode,
        accessor=accessor,
        draft_workspace=draft_workspace,
        emit_event=emit_event,
    )
    write_skill_file = author_tools.write
    write_skill_files = author_tools.write_many
    write_tabular_fixture = author_tools.write_tabular_fixture
    write_self_check_plan = author_tools.write_self_check_plan
    replace_skill_file_text = author_tools.replace
    delete_skill_file = author_tools.delete

    async def run_author_build_preflight() -> Any:
        return await _accept_with_execution_accessor(
            root=root,
            accessor=accessor,
            execution_accessor_factory=execution_accessor_factory,
            smoke_timeout_seconds=60,
        )

    finish_authoring = create_author_completion_tool(
        tool=tool,
        name=tool_names["finish_authoring"],
        task_mode=task_mode,
        state=author_completion_state,
        emit_event=emit_event,
        root=root,
        initial_artifact_sha256=initial_skill_artifact_sha256,
        latest_offline_self_check=lambda: candidate_tool_state.offline_self_check_payload,
        build_preflight=(
            run_author_build_preflight
            if task_mode in {"author", "author_build"}
            else None
        ),
    )

    run_offline_self_check = create_offline_self_check_tool(
        tool=tool,
        name=tool_names["run_offline_self_check"],
        root=root,
        accessor=accessor,
        emit_event=emit_event,
        state=candidate_tool_state,
        author_self_check_max_runs=(
            min(author_self_check_max_runs, 2)
            if task_mode == "author_validate"
            else author_self_check_max_runs
        ),
        max_repair_attempts=max_repair_attempts,
        task_mode=task_mode,
        initial_artifact_sha256=initial_skill_artifact_sha256,
        execution_accessor_factory=execution_accessor_factory,
    )

    finish_draft = create_candidate_tool(
        tool=tool,
        name=tool_names["finish_draft"],
        root=root,
        candidate_submission_limit=candidate_submission_limit,
        accessor=accessor,
        draft_workspace=draft_workspace,
        emit_event=emit_event,
        record_submission_failure=record_submission_failure,
        clear_submission_failure=clear_submission_failure,
        seal_candidate_handoff=_seal_candidate_handoff,
        state=candidate_tool_state,
        execution_accessor_factory=execution_accessor_factory,
    )
    model_settings_phase = "author" if task_mode in author_modes else run_phase
    phase_max_tokens = llm_settings.max_tokens_for_phase(model_settings_phase)
    phase_enable_thinking = llm_settings.enable_thinking_for_phase(
        model_settings_phase
    )
    model_request_options: dict[str, Any] = {}
    phase_tool_choice = agent_model_tool_choice(task_mode)
    if phase_tool_choice is not None:
        model_request_options["tool_choice"] = phase_tool_choice
    if phase_enable_thinking is not None:
        # The OpenAI SDK rejects provider extensions as top-level create()
        # kwargs. extra_body merges them into the final JSON request instead.
        model_request_options["extra_body"] = {
            "enable_thinking": phase_enable_thinking,
        }
    raw_model = Model(
        model_client_config=ModelClientConfig(
            client_provider=llm_settings.provider,
            api_key=llm_settings.api_key,
            api_base=llm_settings.api_base,
            timeout=llm_settings.timeout_seconds,
            verify_ssl=False,
        ),
        model_config=ModelRequestConfig(
            model=llm_settings.model_name,
            temperature=float(os.getenv("SKILL_BUILDER_AGENT_TEMPERATURE") or llm_settings.temperature),
            top_p=llm_settings.top_p,
            max_tokens=phase_max_tokens,
            **model_request_options,
        ),
    )

    def resolve_author_tool_choice() -> str | None:
        plan = load_implementation_plan(root)
        self_check_runs = candidate_tool_state.offline_self_check_runs
        if task_mode == "author_build":
            self_check_runs = 1
        return author_model_tool_choice(
            plan_available=plan is not None,
            scripts_required=bool((plan or {}).get("scriptsRequired")),
            required_paths_materialized=bool(
                plan is not None and not missing_required_plan_paths(root, plan)
            ),
            offline_self_check_runs=self_check_runs,
        )

    model = SkillBuilderBudgetedModel(
        raw_model,
        max_request_bytes=llm_settings.max_request_bytes,
        headroom_ratio=llm_settings.request_headroom_ratio,
        configured_max_tokens=phase_max_tokens,
        emit_event=emit_event,
        run_phase=run_phase,
        tool_choice_resolver=(
            resolve_author_tool_choice
            if task_mode in author_modes
            else None
        ),
    )
    sys_operation_id = f"{workspace_id[:12]}_{uuid.uuid4().hex[:8]}_skill_io" if register_internal_skills else None
    config = ReActAgentConfig()
    if sys_operation_id:
        config.sys_operation_id = sys_operation_id
    config.configure_prompt_template([
        {"role": "system", "content": _build_system_prompt(
            task_mode=task_mode,
            internal_skill_context=internal_skill_context,
        )},
        {"role": "user", "content": "{{query}}"},
    ])
    config.configure_model_client(
        provider=llm_settings.provider,
        api_key=llm_settings.api_key,
        api_base=llm_settings.api_base,
        model_name=llm_settings.model_name,
    )
    config.configure_context_processors(skill_builder_context_processors())
    max_iterations = agent_runtime_iteration_limit(
        task_mode,
        configured=_configured_agent_iteration_limit(task_mode),
    )
    config.configure_max_iterations(max_iterations)

    agent = ReActAgent(card=AgentCard(id=session_prefix, name="SkillBuilderAgent"))
    agent.configure(config)
    agent.set_llm(model)
    accessor_closed = False
    registered_tool_ids: list[str] = []
    registered_sys_operation_ids: list[str] = []

    def unregister_agent_tools() -> None:
        for tool_id in reversed(registered_tool_ids):
            try:
                Runner.resource_mgr.remove_tool(tool_id)
            except Exception as exc:  # pragma: no cover - openjiuwen cleanup compatibility
                logger.debug("Ignoring Skill Builder tool cleanup failure for %s: %s", tool_id, exc)
        registered_tool_ids.clear()
        for sys_op_id in reversed(registered_sys_operation_ids):
            try:
                Runner.resource_mgr.remove_sys_operation(sys_op_id)
            except Exception as exc:  # pragma: no cover - openjiuwen cleanup compatibility
                logger.debug("Ignoring Skill Builder sys_operation cleanup failure for %s: %s", sys_op_id, exc)
        registered_sys_operation_ids.clear()

    async def close_accessor_once(session_id_value: str | None = None) -> None:
        nonlocal accessor_closed
        if accessor_closed:
            return
        accessor_closed = True
        if task_mode in author_modes | {"repair"}:
            try:
                draft_workspace.snapshot_revision(
                    phase=(
                        "package_revision"
                        if isinstance(candidate_tool_state.completion_payload, dict)
                        and candidate_tool_state.completion_payload.get("completion_source") == "finish_draft"
                        else "author_draft"
                    ),
                )
            except Exception as exc:  # noqa: BLE001 - close must retain the primary result
                logger.warning("Skill Builder draft snapshot failed during close: %s", exc)
        close_accessor = getattr(accessor, "close", None)
        try:
            if callable(close_accessor):
                close_accessor()
                if task_mode == "scenario":
                    close_summary = "场景理解沙箱已释放；人工确认完成后会启动新的写包沙箱。"
                    lifecycle_scope = "scenario_phase"
                elif run_phase in {"author", "author_build", "author_validate"}:
                    close_summary = "Skill 写包沙箱已清理。"
                    lifecycle_scope = f"{run_phase}_phase"
                elif run_phase == "repair":
                    close_summary = "显式修复沙箱已清理。"
                    lifecycle_scope = "repair_phase"
                else:
                    close_summary = "本阶段 Skill 抽取沙箱已清理。"
                    lifecycle_scope = f"{run_phase or 'agent'}_phase"
                await _emit(
                    emit_event,
                    "sandbox.closed",
                    close_summary,
                    {
                        "session_id": session_id_value,
                        "phase": run_phase,
                        "scope": lifecycle_scope,
                        "workspace_terminal": False,
                    },
                )
        except Exception as exc:  # noqa: BLE001 - adapter cleanup must not mask the agent result
            logger.warning("Skill Builder sandbox cleanup failed: %s", exc)
        finally:
            unregister_agent_tools()

    phase_capabilities = agent_tool_capabilities(
        task_mode,
        author_handoff_available=author_handoff is not None,
        candidate_available=initial_skill_artifact_sha256 is not None,
    )
    if task_mode == "author" and projected_scripts_required(root) is False:
        phase_capabilities = phase_capabilities - {
            "write_self_check_plan",
            "run_offline_self_check",
        }
    tool_registry = (
        ("read_material_bundle", read_material_bundle_tool),
        ("list_workspace_files", list_workspace_files),
        ("read_workspace_file", read_workspace_file),
        ("write_skill_file", write_skill_file),
        ("write_skill_files", write_skill_files),
        ("write_tabular_fixture", write_tabular_fixture),
        ("write_self_check_plan", write_self_check_plan),
        ("replace_skill_file_text", replace_skill_file_text),
        ("delete_skill_file", delete_skill_file),
        ("run_offline_self_check", run_offline_self_check),
        ("write_scenario_draft", write_scenario_draft),
        ("finish_authoring", finish_authoring),
        ("finish_draft", finish_draft),
    )
    # This is the complete model-facing surface. Platform projections and
    # candidate commits remain controller-owned and have no dormant tool
    # implementation that a policy change could accidentally expose.
    agent_tools = [
        tool_fn
        for capability, tool_fn in tool_registry
        if capability in phase_capabilities
    ]
    agent.ability_manager.add([tool_fn.card for tool_fn in agent_tools])
    if sys_operation_id:
        try:
            sys_op_card = SysOperationCard(
                id=sys_operation_id,
                mode=OperationMode.LOCAL,
                work_config=LocalWorkConfig(
                    sandbox_root=[str(root.resolve())],
                    restrict_to_sandbox=True,
                    shell_allowlist=[],
                ),
            )
            sys_op_result = Runner.resource_mgr.add_sys_operation(sys_op_card)
            sys_op_error = _result_error(sys_op_result)
            if sys_op_error:
                raise SkillBuilderAgentCoreError(f"注册 Skill 抽取只读文件系统失败：{sys_op_error}")
            registered_sys_operation_ids.append(sys_operation_id)
            # Native read_file cannot express the Builder's phase path policy.
            # Never expose it in contract-isolated Scenario/Author/Repair
            # phases; their reads must pass through the governed tools above.
            if _agent_expose_sys_read_file_enabled() and task_mode in {"chat", "edit"}:
                read_file_cards = Runner.resource_mgr.get_sys_op_tool_cards(
                    sys_operation_id,
                    operation_name="fs",
                    tool_name="read_file",
                )
                if read_file_cards:
                    if isinstance(read_file_cards, list):
                        agent.ability_manager.add(read_file_cards)
                    else:
                        agent.ability_manager.add([read_file_cards])
        except Exception as exc:  # noqa: BLE001 - native skill registration is an enhancement
            for sys_op_id in reversed(registered_sys_operation_ids):
                try:
                    Runner.resource_mgr.remove_sys_operation(sys_op_id)
                except Exception:
                    logger.debug("Failed to remove partially registered system operation %s.", sys_op_id, exc_info=True)
            registered_sys_operation_ids.clear()
            config.sys_operation_id = None
            sys_operation_id = None
            await _emit(
                emit_event,
                "agent.skills_register_failed",
                "agent-core 原生 skill 文件系统初始化失败，继续使用预加载规则文本作为兼容路径。",
                {"error": str(exc)[:2000], "task_mode": task_mode},
            )
    for stale_tool_id in (
        "skill_builder_list_workspace_files",
        "skill_builder_read_workspace_file",
        "skill_builder_write_skill_file",
        "skill_builder_write_skill_files",
        "skill_builder_write_self_check_plan",
        "skill_builder_replace_skill_file_text",
        "skill_builder_delete_skill_file",
    ):
        try:
            Runner.resource_mgr.remove_tool(stale_tool_id)
        except Exception as exc:  # pragma: no cover - best-effort cleanup for old static tools
            logger.debug("Ignoring stale static Skill Builder tool removal failure for %s: %s", stale_tool_id, exc)
    for tool_fn in agent_tools:
        tool_id = getattr(getattr(tool_fn, "card", None), "id", None)
        if tool_id:
            try:
                Runner.resource_mgr.remove_tool(tool_id)
            except Exception as exc:  # pragma: no cover - openjiuwen resource manager compatibility
                logger.debug("Ignoring stale Skill Builder tool removal failure for %s: %s", tool_id, exc)
        add_result = Runner.resource_mgr.add_tool(tool_fn)
        add_error = _result_error(add_result)
        if add_error:
            await close_accessor_once()
            raise SkillBuilderAgentCoreError(f"注册 Skill 抽取工具失败：{add_error}")
        if tool_id:
            get_result = Runner.resource_mgr.get_tool(str(tool_id))
            get_error = _result_error(get_result)
            if get_error:
                await close_accessor_once()
                raise SkillBuilderAgentCoreError(f"Skill 抽取工具注册后不可用：{tool_id}: {get_error}")
            registered_tool_ids.append(str(tool_id))

    try:
        await Runner.start()
    except Exception:
        await close_accessor_once()
        raise
    if sys_operation_id and sys_operation_id in registered_sys_operation_ids:
        try:
            skill_register_timeout = _positive_int_env(
                "SKILL_BUILDER_AGENT_REGISTER_INTERNAL_SKILLS_TIMEOUT_SECONDS",
                8,
                minimum=1,
            )
            await asyncio.wait_for(
                agent.register_skill(str((root / AGENT_CORE_SKILLS_ROOT).resolve())),
                timeout=skill_register_timeout,
            )
            await _emit(
                emit_event,
                "agent.skills_registered",
                "已通过 agent-core 原生 skill 注册加载 Skill 抽取内置能力。",
                {
                    "session_prefix": session_prefix,
                    "sys_operation_id": sys_operation_id,
                    "skills_root": AGENT_CORE_SKILLS_ROOT,
                    "skills": installed_resources,
                    "task_mode": task_mode,
                },
            )
        except Exception as exc:  # noqa: BLE001 - prompt preload remains as compatibility fallback
            await _emit(
                emit_event,
                "agent.skills_register_failed",
                "agent-core 原生 skill 注册失败或超时，继续使用预加载规则文本作为兼容路径。",
                {"error": str(exc)[:2000], "skills_root": AGENT_CORE_SKILLS_ROOT, "task_mode": task_mode},
            )
    session = create_agent_session(session_id=session_id, card=agent.card)
    user_prompt = _build_user_prompt(
        skill_name=skill_name,
        display_name=display_name,
        description=description,
        version=version,
        tags=tags,
        user_message=user_message,
        materials_markdown=materials_markdown,
        author_handoff=author_handoff,
        task_mode=task_mode,
    )
    started = time.monotonic()

    def elapsed_for_timeout() -> float:
        return max(0.0, time.monotonic() - started)

    no_write_chunk_limit = _positive_int_env("SKILL_BUILDER_AGENT_NO_WRITE_CHUNK_LIMIT", DEFAULT_NO_WRITE_CHUNK_LIMIT)
    no_write_seconds_limit = _positive_int_env(
        "SKILL_BUILDER_AGENT_NO_WRITE_SECONDS_LIMIT", DEFAULT_NO_WRITE_SECONDS_LIMIT
    )
    no_checkpoint_chunk_limit = _positive_int_env(
        "SKILL_BUILDER_AGENT_NO_CHECKPOINT_CHUNK_LIMIT", DEFAULT_NO_CHECKPOINT_CHUNK_LIMIT
    )
    no_checkpoint_seconds_limit = _positive_int_env(
        "SKILL_BUILDER_AGENT_NO_CHECKPOINT_SECONDS_LIMIT", DEFAULT_NO_CHECKPOINT_SECONDS_LIMIT
    )

    scenario_no_submit_text_limit = _positive_int_env(
        "SKILL_BUILDER_AGENT_SCENARIO_NO_SUBMIT_TEXT_LIMIT",
        12_000,
        minimum=4_000,
    )
    author_no_progress_text_limit = _positive_int_env(
        "SKILL_BUILDER_AGENT_AUTHOR_NO_PROGRESS_TEXT_LIMIT",
        12_000,
        minimum=4_000,
    )
    author_no_progress_tool_limit = _positive_int_env(
        "SKILL_BUILDER_AGENT_AUTHOR_NO_PROGRESS_TOOL_LIMIT",
        6,
        minimum=3,
    )

    def optional_timeout_env(name: str) -> int | None:
        raw = os.getenv(name)
        if raw in {None, ""}:
            return None
        try:
            return max(1, int(raw))
        except (TypeError, ValueError):
            return None

    global_timeout_seconds = optional_timeout_env("SKILL_BUILDER_AGENT_TOTAL_TIMEOUT_SECONDS")
    phase_timeout_name = {
        "chat": "SKILL_BUILDER_AGENT_CHAT_TIMEOUT_SECONDS",
        "edit": "SKILL_BUILDER_AGENT_EDIT_TIMEOUT_SECONDS",
        "scenario": "SKILL_BUILDER_AGENT_SCENARIO_TIMEOUT_SECONDS",
        "author": "SKILL_BUILDER_AGENT_AUTHOR_TIMEOUT_SECONDS",
        "author_build": "SKILL_BUILDER_AGENT_AUTHOR_TIMEOUT_SECONDS",
        "author_validate": "SKILL_BUILDER_AGENT_AUTHOR_TIMEOUT_SECONDS",
        "repair": "SKILL_BUILDER_AGENT_REPAIR_TIMEOUT_SECONDS",
    }.get(run_phase)
    phase_timeout_seconds = optional_timeout_env(phase_timeout_name) if phase_timeout_name else None
    total_timeout_seconds = agent_timeout_budget(
        run_phase,
        explicit=timeout_seconds,
        phase_configured=phase_timeout_seconds,
        global_configured=global_timeout_seconds,
    )

    def phase_timeout_limit() -> int:
        """Include one bounded reserve only while repairing a rejected draft."""

        if repair_reserve_timeout_seconds > 0 and repair_reserve_is_active(
            task_mode=task_mode,
            submission_attempt_count=candidate_tool_state.submission_attempt_count,
            offline_self_check_status=str(
                (candidate_tool_state.offline_self_check_payload or {}).get("status")
                or ""
            ),
            completion_present=(
                candidate_tool_state.completion_payload is not None
                or author_completion_state.completion_payload is not None
            ),
        ):
            return total_timeout_seconds + repair_reserve_timeout_seconds
        return total_timeout_seconds

    idle_timeout_seconds = _positive_int_env(
        "SKILL_BUILDER_AGENT_IDLE_TIMEOUT_SECONDS", DEFAULT_IDLE_TIMEOUT_SECONDS, minimum=30
    )
    max_stream_chunks = _positive_int_env(
        "SKILL_BUILDER_AGENT_MAX_STREAM_CHUNKS", DEFAULT_MAX_STREAM_CHUNKS, minimum=100
    )
    await _emit(
        emit_event,
        "agent.started",
        "工作区只读对话已启动。" if task_mode == "chat" else "Skill 增量修改已启动。" if task_mode == "edit" else "Skill 抽取 Agent 已启动。",
        {"session_id": session_id, "phase": run_phase, "task_mode": task_mode, "checkpoint": checkpoint_rel},
    )
    use_streaming = str(os.getenv("SKILL_BUILDER_AGENT_STREAMING") or "1").strip().lower() not in {"0", "false", "no"}
    iteration_limit_reached = False
    result = None
    deferred_agent_failure: SkillBuilderAgentCoreError | None = None
    final_text_parts: list[str] = []
    all_text_parts: list[str] = []
    if use_streaming and hasattr(Runner, "run_agent_streaming"):
        await _emit(emit_event, "agent.stream_started", "Agent 实时流已启动。", {"session_id": session_id})
        chunk_count = 0
        no_write_warning_emitted = False
        no_checkpoint_warning_emitted = False
        max_stream_warning_emitted = False
        last_stream_activity_at = started
        stream_owner = start_owned_agent_stream(
            lambda: Runner.run_agent_streaming(
                agent,
                {"query": user_prompt},
                session=session,
            )
        )
        stream_text_projector = IncrementalTextProjector()
        stream_recovery_count = 0
        scenario_visible_text_chars = 0
        author_visible_text_chars_since_tool = 0
        author_unchanged_tool_results = 0
        author_seen_tool_result_ids: set[str] = set()

        def author_progress_signature() -> _AuthorProgressSignature:
            return _AuthorProgressSignature(
                skill_artifact_sha256(root / "generated-skill"),
                tuple(accessor.files_read),
                tuple(accessor.files_listed),
                _file_digest(root / "validation" / "implementation_plan.json"),
                candidate_tool_state.offline_self_check_runs,
                str(
                    (candidate_tool_state.offline_self_check_payload or {}).get(
                        "status"
                    )
                    or ""
                ),
            )

        author_last_progress_signature = author_progress_signature()
        pending_error: SkillBuilderAgentCoreError | None = None
        try:
            while True:
                now = time.monotonic()
                elapsed = elapsed_for_timeout()
                stream_idle_seconds = max(0.0, now - last_stream_activity_at)
                deadline_reason = agent_stream_deadline_reason(
                    phase_elapsed_seconds=elapsed,
                    stream_idle_seconds=stream_idle_seconds,
                    phase_timeout_seconds=phase_timeout_limit(),
                    idle_timeout_seconds=idle_timeout_seconds,
                )
                if deadline_reason is not None:
                    logical_phase = task_mode
                    expected_artifact = (
                        "ScenarioContract"
                        if logical_phase == "scenario"
                        else "完整回复"
                        if logical_phase == "chat"
                        else "候选 Skill 产物"
                    )
                    if deadline_reason == "phase_timeout":
                        message = (
                            f"agent-core 本阶段达到 {phase_timeout_limit()} 秒绝对上限，"
                            f"仍未提交{expected_artifact}，已停止本轮运行。"
                        )
                    else:
                        message = (
                            f"agent-core 连续 {idle_timeout_seconds} 秒没有返回任何流事件，"
                            "判定模型调用或流连接已空闲，已停止本轮运行。"
                        )
                    await _emit(
                        emit_event,
                        "agent.error",
                        message,
                        {
                            "session_id": session_id,
                            "phase": run_phase,
                            "chunk_count": chunk_count,
                            "elapsed_seconds": round(elapsed, 2),
                            "timeout_kind": deadline_reason,
                            "phase_timeout_seconds": phase_timeout_limit(),
                            "stream_idle_seconds": round(stream_idle_seconds, 2),
                            "idle_timeout_seconds": idle_timeout_seconds,
                            "files_written": list(accessor.files_written),
                            "checkpoint": checkpoint_rel,
                            "checkpoint_exists": checkpoint_path.is_file(),
                        },
                    )
                    timeout_failure = (
                        incomplete_lifecycle_error(message)
                        if deadline_reason == "phase_timeout" and logical_phase != "chat"
                        else SkillBuilderAgentRuntimeUnavailableError(message)
                    )
                    # A bounded timeout must not discard a complete durable
                    # candidate.  Let the controller run one final acceptance
                    # gate before surfacing the timeout as a real failure.
                    if deadline_reason == "phase_timeout" and _can_finalize_after_agent_failure(
                        task_mode=task_mode,
                        root=root,
                        completion_payload=candidate_tool_state.completion_payload,
                    ):
                        deferred_agent_failure = timeout_failure
                    else:
                        pending_error = timeout_failure
                    break
                if chunk_count > max_stream_chunks and not max_stream_warning_emitted:
                    max_stream_warning_emitted = True
                    message = f"agent-core 实时流已超过 {max_stream_chunks} 个事件，平台继续消费直到 Agent 自然结束。"
                    await _emit(
                        emit_event,
                        "agent.progress",
                        message,
                        {
                            "session_id": session_id,
                            "phase": run_phase,
                            "chunk_count": chunk_count,
                            "elapsed_seconds": round(elapsed, 2),
                            "files_written": list(accessor.files_written),
                        },
                    )
                wait_timeout_seconds = agent_stream_wait_timeout(
                    phase_elapsed_seconds=elapsed,
                    stream_idle_seconds=stream_idle_seconds,
                    phase_timeout_seconds=phase_timeout_limit(),
                    idle_timeout_seconds=idle_timeout_seconds,
                )
                try:
                    stream_event_type, stream_event_value = await stream_owner.next_event(
                        timeout=wait_timeout_seconds
                    )
                    if stream_event_type == "terminal":
                        terminal = stream_event_value
                        if not isinstance(terminal, OwnedAgentStreamTerminal):
                            raise SkillBuilderAgentCoreError(
                                "agent-core 流所有者返回了无法识别的终止事件。"
                            )
                        if terminal.cleanup_error is not None:
                            await _emit(
                                emit_event,
                                "agent.cleanup_warning",
                                "Agent 流已在所有者上下文中关闭，但底层返回了次级清理提示。",
                                {
                                    "session_id": session_id,
                                    "phase": run_phase,
                                    "chunk_count": chunk_count,
                                    "raw_message": str(terminal.cleanup_error)[:4000],
                                    "primary_error": (
                                        str(terminal.error)[:4000]
                                        if terminal.error is not None
                                        else ""
                                    ),
                                },
                            )
                        if terminal.error is None:
                            break
                        raise terminal.error
                    if stream_event_type != "chunk":
                        raise SkillBuilderAgentCoreError(
                            f"agent-core 流所有者返回未知事件：{stream_event_type}"
                        )
                    chunk = stream_event_value
                except asyncio.TimeoutError:
                    continue
                except (
                    Exception
                ) as exc:  # noqa: BLE001 - classify model/runtime failures surfaced by agent-core streaming
                    elapsed = elapsed_for_timeout()
                    raw_message = str(exc)
                    can_recover_stream = (
                        pending_error is None
                        and candidate_tool_state.completion_payload is None
                        and author_completion_state.completion_payload is None
                        and stream_recovery_count < 1
                        and _agent_stream_transient_error(exc)
                    )
                    if can_recover_stream:
                        stream_recovery_count += 1
                        await stream_owner.close()
                        continuation_query = "\n".join(
                            [
                                "上一条模型流因瞬时连接中断。继续当前 Skill Builder 阶段，不要从头重做。",
                                "沿用同一 session 中已经完成的阶段输入和已成功提交的文件。",
                                "Scenario 继续完成 ScenarioContract；Author 或草稿修订继续使用当前材料、场景摘要和已确认答案，不得跨阶段重新解释材料。",
                                "中断时尚未形成完整 tool_call 的参数视为未提交；已经成功写入的文件均保留在持久化 Draft Workspace，只检查并补写缺失正文。",
                                "完成后调用 finish_authoring 提交摘要和 Agent 自检。",
                            ]
                        )
                        stream_owner = start_owned_agent_stream(
                            lambda query=continuation_query: Runner.run_agent_streaming(
                                agent,
                                {"query": query},
                                session=session,
                            )
                        )
                        stream_text_projector.reset()
                        await _emit(
                            emit_event,
                            "agent.stream_recovered",
                            "模型流发生一次瞬时连接中断，平台已沿用同一会话和有效契约继续当前阶段。",
                            {
                                "session_id": session_id,
                                "phase": run_phase,
                                "chunk_count": chunk_count,
                                "elapsed_seconds": round(elapsed, 2),
                                "recovery_attempt": stream_recovery_count,
                                "raw_message": raw_message[:1000],
                            },
                        )
                        continue
                    if pending_error is not None:
                        await _emit(
                            emit_event,
                            "agent.cleanup_warning",
                            "Agent 已返回终止错误；后续流读取/清理异常仅作为次级诊断，不覆盖主错误。",
                            {
                                "session_id": session_id,
                                "phase": run_phase,
                                "chunk_count": chunk_count,
                                "elapsed_seconds": round(elapsed, 2),
                                "raw_message": raw_message[:4000],
                                "primary_error": str(pending_error)[:4000],
                            },
                        )
                        break
                    if _agent_core_cleanup_compatibility_error(raw_message) and (
                        candidate_tool_state.completion_payload is not None
                        or author_completion_state.completion_payload is not None
                    ):
                        await _emit(
                            emit_event,
                            "agent.cleanup_warning",
                            "Agent 已完成原子候选提交，但流式资源清理返回兼容性提示；平台将重新校验候选摘要后验收。",
                            {
                                "session_id": session_id,
                                "phase": run_phase,
                                "chunk_count": chunk_count,
                                "elapsed_seconds": round(elapsed, 2),
                                "raw_message": raw_message[:4000],
                                "files_written": list(accessor.files_written),
                            },
                        )
                        break
                    message = (
                        "Agent 流式资源清理在原子候选提交前失败，当前阶段未形成可验收候选。"
                        if _agent_core_cleanup_compatibility_error(raw_message)
                        else _agent_runtime_failure_message(raw_message) or f"agent-core 执行失败：{raw_message[:1000]}"
                    )
                    await _emit(
                        emit_event,
                        "agent.error",
                        message,
                        {
                            "session_id": session_id,
                            "phase": run_phase,
                            "chunk_count": chunk_count,
                            "elapsed_seconds": round(elapsed, 2),
                            "raw_message": raw_message[:4000],
                        },
                    )
                    runtime_failure_message = _agent_runtime_failure_message(raw_message)
                    if runtime_failure_message:
                        runtime_failure = SkillBuilderAgentRuntimeUnavailableError(
                            message,
                            code=_agent_runtime_failure_code(raw_message),
                        )
                        if (
                            task_mode == "scenario"
                            and runtime_failure.code == "output_truncated"
                            and not scenario_tools.state.committed
                        ):
                            set_scenario_protocol_failure(
                                code="scenario_output_truncated",
                                summary=(
                                    "Scenario 模型输出达到阶段上限且尚未提交 ScenarioDraft；"
                                    "控制器将决定是否启动一次全新恢复。"
                                ),
                                details={"providerFailureCode": runtime_failure.code},
                            )
                        elif (
                            runtime_failure.code == "output_truncated"
                            and _can_finalize_after_agent_failure(
                                task_mode=task_mode,
                                root=root,
                                completion_payload=candidate_tool_state.completion_payload,
                            )
                        ):
                            deferred_agent_failure = runtime_failure
                            await _emit(
                                emit_event,
                                "agent.output_truncated_candidate_preserved",
                                "模型输出达到上限；平台已丢弃未完成的输出参数，并对已持久化候选执行确定性预检。",
                                {
                                    "session_id": session_id,
                                    "phase": run_phase,
                                    "files_written": list(accessor.files_written),
                                    "fallback": "candidate_preflight",
                                },
                            )
                        else:
                            pending_error = runtime_failure
                    else:
                        pending_error = SkillBuilderAgentCoreError(message)
                    break

                chunk_count += 1
                result = chunk
                elapsed = elapsed_for_timeout()
                last_stream_activity_at = time.monotonic()
                has_artifact_progress = _has_agent_artifact_progress(
                    root,
                    accessor,
                    initial_digests=initial_artifact_digests,
                    task_mode=task_mode,
                )
                no_write_progress = (
                    task_mode != "chat"
                    and not has_artifact_progress
                    and (chunk_count > no_write_chunk_limit or elapsed > no_write_seconds_limit)
                )
                if no_write_progress:
                    if not no_write_warning_emitted:
                        no_write_warning_emitted = True
                        await _emit(
                            emit_event,
                            "agent.progress",
                            "Agent 已运行较长时间，仍在分析材料，本阶段尚未写入新的核心产物；该里程碑仅用于诊断，平台继续等待流事件或阶段结果。",
                            {
                                "session_id": session_id,
                                "phase": run_phase,
                                "chunk_count": chunk_count,
                                "elapsed_seconds": round(elapsed, 2),
                                "files_written": list(accessor.files_written),
                                "checkpoint": checkpoint_rel,
                                "checkpoint_exists": checkpoint_path.is_file(),
                            },
                        )
                if task_mode != "chat" and not _has_checkpoint_available(
                    root, accessor, checkpoint_path, initial_digest=initial_checkpoint_digest
                ):
                    if chunk_count > no_checkpoint_chunk_limit or elapsed > no_checkpoint_seconds_limit:
                        if not no_checkpoint_warning_emitted:
                            no_checkpoint_warning_emitted = True
                            await _emit(
                                emit_event,
                                "agent.progress",
                                f"Agent 已运行较长时间但尚未写入阶段 checkpoint（{checkpoint_rel}），平台将继续等待首个产物。",
                                {
                                    "session_id": session_id,
                                    "phase": run_phase,
                                    "chunk_count": chunk_count,
                                    "elapsed_seconds": round(elapsed, 2),
                                    "checkpoint": checkpoint_rel,
                                    "files_written": list(accessor.files_written),
                                },
                            )
                chunk_type = str(_chunk_attr(chunk, "type") or _chunk_attr(chunk, "event_type") or "").strip()
                chunk_payload = _payload_dict(_chunk_attr(chunk, "payload"))
                if chunk_type == "answer":
                    answer_text = _chunk_text(chunk, chunk_payload)
                    if answer_text:
                        final_text_parts.append(answer_text)
                stream_events = _stream_chunk_events(
                    chunk,
                    text_projector=stream_text_projector,
                )
                tool_error_event = next(
                    (
                        event
                        for event in stream_events
                        if event.get("event_type") == "tool.error.stream"
                    ),
                    None,
                )
                for event in stream_events:
                    payload = event.get("payload") if isinstance(event.get("payload"), dict) else {}
                    text = str(payload.get("content") or payload.get("reasoning_content") or "")
                    if text:
                        all_text_parts.append(text)
                        if task_mode == "scenario":
                            scenario_visible_text_chars += len(text)
                        elif task_mode in author_modes:
                            author_visible_text_chars_since_tool += len(text)
                        if payload.get("final"):
                            final_text_parts.append(text)
                    await _emit(emit_event, str(event["event_type"]), str(event["summary"]), payload)
                if any(
                    event.get("event_type")
                    in {"tool.call.stream", "tool.result.stream", "tool.error.stream"}
                    for event in stream_events
                ):
                    author_visible_text_chars_since_tool = 0
                result_events = []
                for event in stream_events:
                    if event.get("event_type") in {"tool.result.stream", "tool.error.stream"}:
                        result_events.append(event)
                new_result_events = []
                for event in result_events:
                    if _author_tool_result_identity(event) not in author_seen_tool_result_ids:
                        new_result_events.append(event)
                result_events = new_result_events
                if result_events:
                    author_seen_tool_result_ids.update(
                        _author_tool_result_identity(event)
                        for event in result_events
                    )
                    current_progress_signature = author_progress_signature()
                    observation_progress = any(
                        author_observation_tool_is_progress(
                            task_mode=task_mode,
                            tool_name=str(
                                (event.get("payload") or {}).get("name") or ""
                            ),
                            ok=(
                                (
                                    (
                                        (event.get("payload") or {}).get("outputs")
                                        or {}
                                    ).get("outputs")
                                    or {}
                                ).get("ok")
                                is True
                            ),
                        )
                        for event in result_events
                    )
                    if (
                        current_progress_signature != author_last_progress_signature
                        or observation_progress
                    ):
                        author_unchanged_tool_results = 0
                        author_last_progress_signature = current_progress_signature
                    else:
                        author_unchanged_tool_results += 1
                scenario_submission_stalled = (
                    task_mode == "scenario"
                    and not scenario_tools.state.committed
                    and candidate_tool_state.completion_payload is None
                    and scenario_visible_text_chars > scenario_no_submit_text_limit
                )
                if scenario_submission_stalled:
                    set_scenario_protocol_failure(
                        code="scenario_protocol_stalled",
                        summary=(
                            "Scenario 持续输出分析正文但未提交 ScenarioDraft；"
                            "控制器已停止无进展输出并将决定是否启动一次全新恢复。"
                        ),
                        details={
                            "visibleTextChars": scenario_visible_text_chars,
                            "visibleTextLimit": scenario_no_submit_text_limit,
                        },
                    )
                    await _emit(
                        emit_event,
                        "agent.scenario_protocol_stalled",
                        "Scenario 未提交结构化草稿且分析正文已达到阶段预算，平台停止继续消费本次输出。",
                        {
                            "session_id": session_id,
                            "phase": run_phase,
                            "visible_text_chars": scenario_visible_text_chars,
                            "visible_text_limit": scenario_no_submit_text_limit,
                            "recovery": "fresh_scenario_once",
                        },
                    )
                    break
                if author_no_tool_progress_is_stalled(
                    task_mode=task_mode,
                    visible_text_chars_since_tool=author_visible_text_chars_since_tool,
                    text_limit=author_no_progress_text_limit,
                    completion_present=(
                        candidate_tool_state.completion_payload is not None
                        or author_completion_state.completion_payload is not None
                    ),
                ):
                    await _emit(
                        emit_event,
                        "agent.author_protocol_stalled",
                        "Author 长时间输出正文但没有工具进展，平台停止超长正文并进入 Core 预检。",
                        {
                            "session_id": session_id,
                            "phase": run_phase,
                            "visible_text_chars_since_tool": author_visible_text_chars_since_tool,
                            "visible_text_limit": author_no_progress_text_limit,
                            "recovery": "candidate_preflight",
                        },
                    )
                    break
                if author_tool_progress_is_stalled(
                    task_mode=task_mode,
                    unchanged_tool_results=author_unchanged_tool_results,
                    result_limit=author_no_progress_tool_limit,
                    completion_present=(
                        candidate_tool_state.completion_payload is not None
                        or author_completion_state.completion_payload is not None
                    ),
                ):
                    await _emit(
                        emit_event,
                        "agent.author_tool_progress_stalled",
                        "Author 连续调用工具但没有产生新的材料读取、计划、自检或候选变更，平台停止无进展循环。",
                        {
                            "session_id": session_id,
                            "phase": run_phase,
                            "unchanged_tool_results": author_unchanged_tool_results,
                            "result_limit": author_no_progress_tool_limit,
                            "fallback": "candidate_preflight",
                        },
                    )
                    break
                if tool_error_event is not None:
                    error_payload = (
                        tool_error_event.get("payload")
                        if isinstance(tool_error_event.get("payload"), dict)
                        else {}
                    )
                    tool_name = str(
                        error_payload.get("name")
                        or error_payload.get("tool_name")
                        or "tool"
                    )
                    raw_tool_error = str(
                        error_payload.get("error")
                        or error_payload.get("exception")
                        or error_payload.get("message")
                        or "tool execution failed"
                    )
                    if _recoverable_tool_input_validation_error(error_payload):
                        tool_input_validation_failure_counts[tool_name] = (
                            tool_input_validation_failure_counts.get(tool_name, 0) + 1
                        )
                        validation_failure_count = tool_input_validation_failure_counts[tool_name]
                        if validation_failure_count <= 1:
                            active_submission_failure = current_submission_failure()
                            if not isinstance(active_submission_failure, dict):
                                record_submission_failure({
                                    "ok": False,
                                    "error": "agent_tool_input_invalid",
                                    "tool": tool_name,
                                    "message": raw_tool_error[:1000],
                                    "retryCount": validation_failure_count,
                                    "retryPolicy": "sdk_react_continuation_once",
                                })
                            await _emit(
                                emit_event,
                                "agent.tool_input_retry_allowed",
                                "工具入参未通过 SDK schema 校验；平台继续消费当前 ReAct 流，由 SDK 在同一请求序列内纠正一次。",
                                {
                                    "session_id": session_id,
                                    "phase": run_phase,
                                    "tool": tool_name,
                                    "retryCount": validation_failure_count,
                                    "maxRetries": 1,
                                    "continuationStarted": False,
                                    "requestOwnership": "sdk_react_stream",
                                },
                            )
                            continue
                        if _can_finalize_after_tool_input_failure(
                            task_mode=task_mode,
                            root=root,
                            completion_payload=candidate_tool_state.completion_payload,
                            validation_failure_count=validation_failure_count,
                        ):
                            await _emit(
                                emit_event,
                                "agent.tool_input_fallback",
                                (
                                    "工具入参连续两次未通过 SDK schema 校验；"
                                    "平台将保留当前候选并执行完整预检，真实验收结论不会被工具错误覆盖。"
                                ),
                                {
                                    "session_id": session_id,
                                    "phase": run_phase,
                                    "tool": tool_name,
                                    "retryCount": validation_failure_count,
                                    "fallback": "candidate_preflight",
                                },
                            )
                            break
                    active_submission_failure = current_submission_failure()
                    atomic_submission_tool = tool_name in {
                        tool_names["finish_draft"],
                        tool_names["write_scenario_draft"],
                    }
                    if atomic_submission_tool and isinstance(active_submission_failure, dict):
                        root_code = str(active_submission_failure.get("error") or "").strip()
                        if not root_code:
                            for nested_key in ("verification", "smoke", "details"):
                                nested = active_submission_failure.get(nested_key)
                                if isinstance(nested, dict) and str(nested.get("error") or "").strip():
                                    root_code = str(nested.get("error") or "").strip()
                                    break
                        root_code = root_code or "candidate_submission_invalid"
                        scenario_submission = tool_name == tool_names["write_scenario_draft"]
                        candidate_tool_state.completion_payload = {
                            "status": "failed",
                            "summary": (
                                "原子候选工具返回了次级 transport/schema 错误；"
                                "平台已保留此前结构化提交失败及其持久化草稿。"
                            ),
                            "files": sorted(accessor.files_written),
                            "pending_decisions": [],
                            "blockers": [
                                f"{root_code}: "
                                + str(
                                    active_submission_failure.get("message") or "candidate submission rejected"
                                )[:500]
                            ],
                            "unverified_inputs": [],
                            "unverified_capabilities": [],
                            "suggested_next_message": "",
                            "completion_source": (
                                "scenario_contract_rejected" if scenario_submission else "candidate_submission_rejected"
                            ),
                            "lifecycle_failure": {
                                "code": root_code,
                                "rootBlockerCode": root_code,
                                "terminationCode": "atomic_tool_transport_failed_after_rejection",
                                "phase": task_mode,
                                "tool": tool_name,
                                "last_submission_failure": active_submission_failure,
                                "secondaryToolError": raw_tool_error[:1000],
                            },
                        }
                        break
                    tool_failure = record_submission_failure({
                        "ok": False,
                        "error": "agent_tool_execution_failed",
                        "tool": tool_name,
                        "message": raw_tool_error[:1000],
                        "payload": error_payload,
                    })
                    candidate_tool_state.completion_payload = {
                        "status": "failed",
                        "summary": f"平台工具 {tool_name} 执行失败，当前阶段已终止。",
                        "files": sorted(accessor.files_written),
                        "pending_decisions": [],
                        "blockers": [f"agent_tool_execution_failed: {raw_tool_error[:500]}"],
                        "unverified_inputs": [],
                        "unverified_capabilities": [],
                        "suggested_next_message": "",
                        "completion_source": "tool_execution_failed",
                        "lifecycle_failure": {
                            "code": "agent_tool_execution_failed",
                            "rootBlockerCode": "agent_tool_execution_failed",
                            "terminationCode": "agent_tool_execution_failed",
                            "phase": task_mode,
                            "tool": tool_name,
                            "last_submission_failure": tool_failure,
                        },
                    }
                    break
                if _repair_offline_preflight_complete(
                    task_mode=task_mode,
                    state=candidate_tool_state,
                ):
                    await _emit(
                        emit_event,
                        "agent.repair_preflight_accepted",
                        "Repair 已通过平台离线复验；控制器停止继续消费模型输出并执行最终完整预检。",
                        {
                            "session_id": session_id,
                            "phase": run_phase,
                            "status": (
                                candidate_tool_state.offline_self_check_payload or {}
                            ).get("status"),
                            "offlineSelfCheckRuns": candidate_tool_state.offline_self_check_runs,
                        },
                    )
                    break
                if chunk_type == "answer" and chunk_payload.get("result_type") == "error":
                    error_text = answer_text or _chunk_text(chunk, chunk_payload)
                    if "max iterations reached without completion" in str(error_text or "").lower():
                        iteration_limit_reached = True
                        active_submission_failure = current_submission_failure()
                        await _emit(
                            emit_event,
                            "agent.iteration_limit_reached",
                            f"Agent 已达到本阶段 {max_iterations} 轮安全上限，控制器将检查持久化产物并决定是否提交。",
                            {
                                "session_id": session_id,
                                "phase": run_phase,
                                "max_iterations": max_iterations,
                                "last_submission_failure": active_submission_failure,
                            },
                        )
                    else:
                        runtime_message = _agent_runtime_failure_message(error_text)
                        if runtime_message:
                            pending_error = SkillBuilderAgentRuntimeUnavailableError(
                                runtime_message,
                                code=_agent_runtime_failure_code(error_text),
                            )
                        else:
                            pending_error = SkillBuilderAgentCoreError(
                                f"agent-core 执行失败：{error_text[:1000]}" if error_text else "agent-core 执行失败。"
                            )
                    break
                if (
                    candidate_tool_state.completion_payload is not None
                    or author_completion_state.completion_payload is not None
                ):
                    await _emit(
                        emit_event,
                        "agent.stream_finish_accepted",
                        "已收到 Agent 完成信号，停止继续消费流式输出。",
                        {"session_id": session_id, "phase": run_phase, "chunk_count": chunk_count},
                    )
                    break
        finally:
            await stream_owner.close()
        await _emit(
            emit_event,
            "agent.stream_completed",
            "Agent 实时流已结束。",
            {"session_id": session_id, "chunk_count": chunk_count},
        )
        if pending_error is not None:
            await close_accessor_once(session_id)
            raise pending_error
    else:
        try:
            result = await asyncio.wait_for(
                Runner.run_agent(agent, {"query": user_prompt}, session=session),
                timeout=total_timeout_seconds,
            )
        except asyncio.TimeoutError as exc:
            message = (
                f"agent-core 本阶段达到 {total_timeout_seconds} 秒绝对上限且未完成阶段结果；"
                "当前阶段已终止。"
            )
            await _emit(
                emit_event,
                "agent.error",
                message,
                {
                    "session_id": session_id,
                    "phase": run_phase,
                    "elapsed_seconds": round(elapsed_for_timeout(), 2),
                    "files_written": list(accessor.files_written),
                    "checkpoint_exists": checkpoint_path.is_file(),
                },
            )
            timeout_failure = (
                SkillBuilderAgentRuntimeUnavailableError(message)
                if task_mode == "chat"
                else incomplete_lifecycle_error(message)
            )
            # Keep the non-streaming runner consistent with the streaming
            # path: a bounded Agent timeout is not proof that a durable
            # candidate is invalid.  If Author/Repair already wrote a
            # complete SKILL.md, defer the failure until the controller has
            # run its one final acceptance/commit checkpoint.
            if task_mode != "chat" and _can_finalize_after_agent_failure(
                task_mode=task_mode,
                root=root,
                completion_payload=candidate_tool_state.completion_payload,
            ):
                deferred_agent_failure = timeout_failure
            else:
                await close_accessor_once(session_id)
                raise timeout_failure from exc
        except Exception as exc:
            if _agent_stream_transient_error(exc):
                await _emit(
                    emit_event,
                    "agent.stream_recovered",
                    "模型调用发生一次瞬时连接中断，平台已沿用同一会话继续当前阶段。",
                    {
                        "session_id": session_id,
                        "phase": run_phase,
                        "elapsed_seconds": round(elapsed_for_timeout(), 2),
                        "recovery_attempt": 1,
                        "raw_message": str(exc)[:1000],
                    },
                )
                try:
                    result = await asyncio.wait_for(
                        Runner.run_agent(
                            agent,
                            {
                                "query": (
                                    "上一条模型调用因瞬时连接中断。沿用同一 session、已完成 HITL、"
                                    "场景摘要和已提交文件继续当前阶段；"
                                    "不要重复提问或从头生成，完成后调用 finish_authoring。"
                                )
                            },
                            session=session,
                        ),
                        timeout=max(0.05, total_timeout_seconds - elapsed_for_timeout()),
                    )
                except Exception as retry_exc:  # noqa: BLE001 - classify the single recovery result below
                    exc = retry_exc
                else:
                    exc = None
            if exc is not None:
                if isinstance(exc, asyncio.TimeoutError):
                    message = (
                        f"agent-core 本阶段达到 {total_timeout_seconds} 秒绝对上限且未完成阶段结果；"
                        "单次瞬时恢复不会重置阶段时限。"
                    )
                    await _emit(
                        emit_event,
                        "agent.error",
                        message,
                        {
                            "session_id": session_id,
                            "phase": run_phase,
                            "elapsed_seconds": round(elapsed_for_timeout(), 2),
                            "timeout_kind": "phase_timeout",
                            "phase_timeout_seconds": total_timeout_seconds,
                        },
                    )
                    timeout_failure = (
                        SkillBuilderAgentRuntimeUnavailableError(message)
                        if task_mode == "chat"
                        else incomplete_lifecycle_error(message)
                    )
                    if task_mode != "chat" and _can_finalize_after_agent_failure(
                        task_mode=task_mode,
                        root=root,
                        completion_payload=candidate_tool_state.completion_payload,
                    ):
                        deferred_agent_failure = timeout_failure
                    else:
                        await close_accessor_once(session_id)
                        raise timeout_failure from exc
                raw_message = str(exc)
                if _agent_core_cleanup_compatibility_error(raw_message) and (
                    candidate_tool_state.completion_payload is not None
                    or author_completion_state.completion_payload is not None
                ):
                    await _emit(
                        emit_event,
                        "agent.cleanup_warning",
                        "Agent 已完成原子候选提交，但资源清理返回兼容性提示；平台将重新校验候选摘要后验收。",
                        {
                            "session_id": session_id,
                            "phase": run_phase,
                            "elapsed_seconds": round(elapsed_for_timeout(), 2),
                            "raw_message": raw_message[:4000],
                            "files_written": list(accessor.files_written),
                        },
                    )
                    result = None
                elif "max iterations reached without completion" in raw_message.lower():
                    iteration_limit_reached = True
                    result = {"content": raw_message}
                    active_submission_failure = current_submission_failure()
                    await _emit(
                        emit_event,
                        "agent.iteration_limit_reached",
                        f"Agent 已达到本阶段 {max_iterations} 轮安全上限，控制器将检查持久化产物并决定是否提交。",
                        {
                            "session_id": session_id,
                            "phase": run_phase,
                            "max_iterations": max_iterations,
                            "last_submission_failure": active_submission_failure,
                        },
                    )
                else:
                    message = (
                        "Agent 资源清理在原子候选提交前失败，当前阶段未形成可验收候选。"
                        if _agent_core_cleanup_compatibility_error(raw_message)
                        else _agent_runtime_failure_message(raw_message) or f"agent-core 执行失败：{raw_message[:1000]}"
                    )
                    await _emit(
                        emit_event,
                        "agent.error",
                        message,
                        {
                            "session_id": session_id,
                            "phase": run_phase,
                            "elapsed_seconds": round(elapsed_for_timeout(), 2),
                            "raw_message": raw_message[:4000],
                        },
                    )
                    runtime_failure_message = _agent_runtime_failure_message(raw_message)
                    runtime_failure = (
                        SkillBuilderAgentRuntimeUnavailableError(
                            message,
                            code=_agent_runtime_failure_code(raw_message),
                        )
                        if runtime_failure_message
                        else SkillBuilderAgentCoreError(message)
                    )
                    truncated_scenario = (
                        isinstance(runtime_failure, SkillBuilderAgentRuntimeUnavailableError)
                        and task_mode == "scenario"
                        and runtime_failure.code == "output_truncated"
                        and not scenario_tools.state.committed
                    )
                    if truncated_scenario:
                        set_scenario_protocol_failure(
                            code="scenario_output_truncated",
                            summary=(
                                "Scenario 模型输出达到阶段上限且尚未提交 ScenarioDraft；"
                                "控制器将决定是否启动一次全新恢复。"
                            ),
                            details={"providerFailureCode": runtime_failure.code},
                        )
                        result = None
                    elif (
                        isinstance(runtime_failure, SkillBuilderAgentRuntimeUnavailableError)
                        and runtime_failure.code == "output_truncated"
                        and _can_finalize_after_agent_failure(
                            task_mode=task_mode,
                            root=root,
                            completion_payload=candidate_tool_state.completion_payload,
                        )
                    ):
                        deferred_agent_failure = runtime_failure
                        result = None
                        await _emit(
                            emit_event,
                            "agent.output_truncated_candidate_preserved",
                            "模型输出达到上限；平台已丢弃未完成的输出参数，并对已持久化候选执行确定性预检。",
                            {
                                "session_id": session_id,
                                "phase": run_phase,
                                "files_written": list(accessor.files_written),
                                "fallback": "candidate_preflight",
                            },
                        )
                    else:
                        await close_accessor_once(session_id)
                        raise runtime_failure from exc

    output_text = (
        "\n".join(final_text_parts).strip()
        or "\n".join(all_text_parts).strip()
        or _result_content(result)
    )
    parsed_final_response = _parse_agent_json(output_text)
    build_finalization_failure: dict[str, Any] | None = None

    if _author_build_needs_controller_finalization(
        task_mode=task_mode,
        completion_present=author_completion_state.completion_payload is not None,
        artifact_present=(
            skill_artifact_sha256(root / "generated-skill") is not None
        ),
    ):
        await _emit(
            emit_event,
            "agent.author_build_finalization_checkpoint",
            "Build 已形成候选但未提交完成信号，Core 执行一次静态 preflight。",
            {
                "session_id": session_id,
                "phase": run_phase,
                "iteration_limit_reached": iteration_limit_reached,
                "files_written": list(accessor.files_written),
            },
        )
        try:
            finalized = await _invoke_author_build_finalization(finish_authoring)
        except Exception as exc:  # pragma: no cover - defensive tool boundary
            build_finalization_failure = {
                "ok": False,
                "error": "author_build_finalization_failed",
                "message": str(exc)[:2000],
            }
        else:
            if not finalized.get("ok"):
                build_finalization_failure = finalized

    # Scenario submission is also a controller decision.  An SDK schema error
    # can prevent the model's corrective call from entering the Python tool,
    # while the previous complete draft remains durable.  Recompile that exact
    # draft once so transport noise cannot replace precise contract findings.
    persisted_scenario_sha256 = (
        scenario_tools.state.last_draft_sha256
        or str(draft_workspace.load_state().get("scenarioDraftSha256") or "")
    )
    scenario_checkpoint_ready = (
        task_mode == "scenario"
        and not scenario_tools.state.committed
        and persisted_scenario_sha256
        and not scenario_tools.state.submitted
    )
    if scenario_checkpoint_ready:
        candidate_tool_state.completion_payload = None
        await _emit(
            emit_event,
            "agent.scenario_finalization_checkpoint",
            "Scenario Agent 已持久化草稿但未完成提交，Core 执行一次最终契约检查点。",
            {
                "session_id": session_id,
                "phase": run_phase,
                "draftSha256": persisted_scenario_sha256,
                "submissionAttempts": scenario_tools.state.submission_attempts,
                "iteration_limit_reached": iteration_limit_reached,
            },
        )
        try:
            await scenario_tools.finalize_persisted()
        except Exception as exc:  # pragma: no cover - defensive boundary
            await _emit(
                emit_event,
                "agent.scenario_finalization_checkpoint_failed",
                "Scenario 最终契约检查点执行异常，草稿不会被提交。",
                {
                    "session_id": session_id,
                    "phase": run_phase,
                    "error": str(exc)[:2000],
                },
            )

    # Candidate submission is a controller decision, even when the model does
    # not submit its lightweight completion report after repairing files. A terminal rejection may
    # already occupy completion_payload when the Agent makes one final valid
    # edit. Detect that exact artifact transition and rerun the same complete
    # preflight once; passing still requires the normal atomic commit below.
    active_failure_at_finalization = current_submission_failure()
    controller_finalization_required, repaired_after_rejection = _controller_finalization_state(
        completion_payload=candidate_tool_state.completion_payload,
        active_submission_failure=active_failure_at_finalization,
        previous_submission_failure=previous_submission_failure,
    )
    offline_self_check_status = str(
        (candidate_tool_state.offline_self_check_payload or {}).get("status") or ""
    ).strip().lower()
    validation_finalization_ready = bool(
        task_mode != "author_validate"
        or offline_self_check_status in {"pass", "warn"}
    )
    candidate_checkpoint_ready = (
        task_mode in candidate_modes
        and validation_finalization_ready
        and controller_finalization_required
        and skill_artifact_sha256(root / "generated-skill") is not None
    )
    if candidate_checkpoint_ready:
        if repaired_after_rejection:
            candidate_tool_state.completion_payload = None
        author_completion = author_completion_state.completion_payload
        previous_agent_self_check = None
        implementation_evidence = None
        if (
            isinstance(author_completion, dict)
            and isinstance(author_completion.get("agent_self_check"), dict)
        ):
            previous_agent_self_check = author_completion.get("agent_self_check")
            implementation_evidence = author_completion.get("implementation_evidence")
        elif not repaired_after_rejection:
            if (
                isinstance(previous_submission_failure, dict)
                and isinstance(previous_submission_failure.get("agentSelfCheck"), dict)
            ):
                previous_agent_self_check = previous_submission_failure.get("agentSelfCheck")
            elif (
                isinstance(parsed_final_response, dict)
                and isinstance(parsed_final_response.get("agent_self_check"), dict)
            ):
                previous_agent_self_check = parsed_final_response.get("agent_self_check")
        if previous_agent_self_check is None and task_mode == "repair":
            previous_agent_self_check = _load_persisted_agent_self_check(root)
        if implementation_evidence is None:
            implementation_evidence = _load_persisted_implementation_evidence(root)
        await _emit(
            emit_event,
            "agent.finalization_checkpoint",
            (
                "Agent 已修改最近一次失败候选，Core 自动执行完整复验和候选提交。"
                if repaired_after_rejection
                else "Agent 写包阶段已结束，Core 执行完整预检和候选提交；检查失败不会提交草稿。"
            ),
            {
                "session_id": session_id,
                "phase": run_phase,
                "trigger": (
                    "candidate_changed_after_rejection"
                    if repaired_after_rejection
                    else "authoring_completed"
                    if isinstance(author_completion, dict)
                    else "agent_completion_missing"
                ),
                "iteration_limit_reached": iteration_limit_reached,
                "files_written": list(accessor.files_written),
            },
        )
        try:
            await _invoke_candidate_finalization(
                finish_draft,
                summary=(
                    "Core 自动复验：提交 Agent 已修复的 Skill 草稿。"
                    if repaired_after_rejection
                    else str(author_completion.get("summary") or "")[:2000]
                    if isinstance(author_completion, dict)
                    else "Core 最终质量检查点：提交当前 Skill 草稿。"
                ),
                agent_self_check=previous_agent_self_check,
                implementation_evidence=implementation_evidence,
            )
        except Exception as exc:  # pragma: no cover - defensive boundary
            await _emit(
                emit_event,
                "agent.finalization_checkpoint_failed",
                "最终质量检查点执行异常，候选不会被提交。",
                {
                    "session_id": session_id,
                    "phase": run_phase,
                    "error": str(exc)[:2000],
                },
            )

    if _deferred_agent_failure_remains_authoritative(
        deferred_failure_present=deferred_agent_failure is not None,
        completion_payload=candidate_tool_state.completion_payload,
        submission_failure=current_submission_failure(),
    ):
        if deferred_agent_failure is None:
            raise AssertionError
        await close_accessor_once(session_id)
        raise deferred_agent_failure

    runtime_message = (
        None if candidate_tool_state.completion_payload is not None else _agent_runtime_failure_message(output_text)
    )
    if runtime_message:
        await _emit(
            emit_event,
            "agent.error",
            runtime_message,
            {"session_id": session_id, "phase": run_phase, "raw_message": output_text[:4000]},
        )
        await close_accessor_once(session_id)
        raise SkillBuilderAgentRuntimeUnavailableError(
            runtime_message,
            code=_agent_runtime_failure_code(output_text),
        )
    if task_mode in candidate_modes and candidate_tool_state.completion_payload is None:
        active_submission_failure = current_submission_failure()
        historical_submission_failure = previous_submission_failure
        repair_changed_candidate = (
            active_submission_failure is None
            and isinstance(historical_submission_failure, dict)
        )
        latest_failure_code = ""
        if isinstance(active_submission_failure, dict):
            latest_failure_code = str(active_submission_failure.get("error") or "").strip()
            if not latest_failure_code:
                for nested_key in ("verification", "smoke", "details"):
                    nested = active_submission_failure.get(nested_key)
                    if isinstance(nested, dict) and str(nested.get("error") or "").strip():
                        latest_failure_code = str(nested.get("error") or "").strip()
                        break
        if (
            not latest_failure_code
            and task_mode == "author_validate"
            and offline_self_check_status == "fail"
        ):
            latest_failure_code = "offline_self_check_failed"
        logical_phase = task_mode
        root_blocker_code = latest_failure_code or (
            "candidate_resubmission_missing_after_repair"
            if repair_changed_candidate
            else
            "scenario_submission_missing"
            if logical_phase == "scenario"
            else "candidate_completion_not_committed"
        )
        termination_code = (
            "candidate_resubmission_missing_after_repair"
            if repair_changed_candidate
            else "agent_iteration_budget_exhausted"
            if iteration_limit_reached
            else "candidate_completion_not_committed"
        )
        # ``code`` remains backward compatible and points at the actionable
        # root blocker.  Iteration exhaustion is only how the phase stopped;
        # it must not overwrite the compiler/write/validation failure that the
        # operator and next Worker actually need to repair.
        lifecycle_code = root_blocker_code
        if iteration_limit_reached and latest_failure_code:
            lifecycle_summary = (
                f"Agent 在本阶段 {max_iterations} 轮安全上限前未能修复最新候选提交失败"
                f"（{latest_failure_code}）；当前文件仅保留用于诊断。"
            )
        elif repair_changed_candidate:
            previous_code = str(
                historical_submission_failure.get("error")
                or "candidate_submission_invalid"
            )
            lifecycle_summary = (
                f"Agent 已修改最近一次失败候选（{previous_code}），但未重新调用 "
                "finish_authoring；当前 Draft 和历史诊断已持久化。"
            )
        elif iteration_limit_reached:
            lifecycle_summary = (
                f"Agent 已用完本阶段 {max_iterations} 轮迭代，但仍未完成 "
                "Author 自检提交；当前 Draft 和诊断已持久化。"
            )
        else:
            lifecycle_summary = "Agent 未完成 Author 自检提交；当前 Draft 和诊断已持久化。"
        artifact_sha256 = skill_artifact_sha256(root / "generated-skill")
        final_response = {
            "status": "failed",
            "summary": lifecycle_summary,
            "files": sorted(accessor.files_written),
            "lifecycle_failure": {
                "code": lifecycle_code,
                "rootBlockerCode": root_blocker_code,
                "terminationCode": termination_code,
                "phase": logical_phase,
                "max_iterations": max_iterations,
                "structured_response_present": isinstance(parsed_final_response, dict),
                "iteration_budget_exhausted": iteration_limit_reached,
                "last_submission_failure": active_submission_failure,
                "previous_submission_failure": historical_submission_failure,
                "repair_submission_missing": repair_changed_candidate,
                "artifact_state": "present" if artifact_sha256 else "none",
                "artifact_sha256": artifact_sha256,
                "offlineSelfCheck": candidate_tool_state.offline_self_check_payload,
            },
        }
        await _emit(
            emit_event,
            "agent.phase_incomplete",
            final_response["summary"],
            {
                "session_id": session_id,
                "phase": run_phase,
                "files_written": list(accessor.files_written),
                "parsed_response": json_safe(parsed_final_response, max_text_length=4000),
            },
        )
    elif task_mode == "author_build":
        author_completion = author_completion_state.completion_payload
        if isinstance(author_completion, dict):
            final_response = {
                "status": "build_ready",
                "summary": str(author_completion.get("summary") or "")[:2000],
                "files": sorted(accessor.files_written),
                "completion_source": "author_build_completed",
                "agent_self_check": author_completion.get("agent_self_check"),
                "implementation_evidence": author_completion.get(
                    "implementation_evidence"
                ),
                "build_preflight": author_completion.get("build_preflight"),
            }
        else:
            failure_code = str(
                (build_finalization_failure or {}).get("error")
                or (
                    "agent_iteration_budget_exhausted"
                    if iteration_limit_reached
                    else "author_build_completion_missing"
                )
            )
            final_response = {
                "status": "failed",
                "summary": (
                    str((build_finalization_failure or {}).get("message") or "")
                    or (
                        f"Agent 已用完本阶段 {max_iterations} 轮迭代但未完成生产包交接。"
                        if iteration_limit_reached
                        else "Agent 未完成生产包交接。"
                    )
                ),
                "files": sorted(accessor.files_written),
                "lifecycle_failure": {
                    "code": failure_code,
                    "phase": task_mode,
                    "max_iterations": max_iterations,
                    "iteration_budget_exhausted": iteration_limit_reached,
                    "buildPreflightFailure": build_finalization_failure,
                },
            }
    else:
        final_response = candidate_tool_state.completion_payload or parsed_final_response
    if final_response and task_mode == "chat":
        summary_text = str(final_response.get("summary") or final_response.get("suggested_next_message") or "").strip()
        await _emit(
            emit_event,
            "assistant.message",
            "工作区助手已回复。",
            {"session_id": session_id, "phase": run_phase, "content": summary_text[:12000]},
        )
    elif final_response and isinstance(final_response.get("lifecycle_failure"), dict):
        # The lifecycle error was emitted above. Keep the structured failure
        # intact so every host applies the same candidate gate.
        pass
    elif final_response and task_mode == "scenario":
        status_text = str(final_response.get("status") or "needs_review").strip()
        summary_text = _agent_human_summary(final_response)
        await _emit(
            emit_event,
            "agent.scenario_summary",
            f"场景理解结果：{summary_text[:500]}",
            {
                "session_id": session_id,
                "phase": run_phase,
                "status": status_text,
                "result": json_safe(final_response, max_text_length=12000),
            },
        )
    elif final_response:
        status_text = str(final_response.get("status") or "needs_review").strip()
        summary_text = _agent_human_summary(final_response)
        await _emit(
            emit_event,
            "agent.draft_completed",
            f"Skill 草稿完成：{summary_text[:500]}",
            {
                "session_id": session_id,
                "phase": run_phase,
                "status": status_text,
                "result": json_safe(final_response, max_text_length=12000),
            },
        )
    elif output_text:
        display_output_text = _normalize_agent_display_text(output_text)
        await _emit(
            emit_event,
            "assistant.message",
            "Skill 抽取 Agent 已返回结果。",
            {"session_id": session_id, "content": display_output_text[:12000]},
        )
        if accessor.files_written:
            final_response = {
                "status": "needs_review",
                "summary": "Agent 已返回非结构化结果，本轮不能声明修改已完成。",
                "files": sorted(accessor.files_written),
                "structured_final_response_missing": True,
            }
    elif accessor.files_written:
        final_response = {
            "status": "needs_review",
            "summary": "Agent 未返回结构化最终结果，本轮不能声明修改已完成。",
            "files": sorted(accessor.files_written),
            "structured_final_response_missing": True,
        }
        await _emit(
            emit_event,
            "agent.error",
            "Agent 未返回结构化最终结果，本轮不能声明修改已完成。",
            {
                "session_id": session_id,
                "phase": run_phase,
                "status": "needs_review",
                "result": json_safe(final_response, max_text_length=12000),
            },
        )
    logger.info(
        "Skill Builder agent-core run completed workspace=%s session=%s elapsed=%.2fs files_written=%s",
        workspace_id,
        session_id,
        elapsed_for_timeout(),
        len(accessor.files_written),
    )
    await close_accessor_once(session_id)
    return SkillBuilderAgentCoreResult(
        raw_output_text=output_text,
        session_id=session_id,
        files_read=list(accessor.files_read),
        files_listed=list(accessor.files_listed),
        files_written=list(accessor.files_written),
        final_response=final_response,
    )


__all__ = ["run_skill_builder_agent_runtime"]
