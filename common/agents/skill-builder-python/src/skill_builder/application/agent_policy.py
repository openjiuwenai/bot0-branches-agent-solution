# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Deterministic execution budgets shared by Agent adapters."""

from __future__ import annotations

import json
from typing import Any, Literal

CANONICAL_EXTRACTION_INSTRUCTION = (
    "请基于当前工作区全部材料完成 Skill 抽取并生成可编辑、可打包的草稿。"
)
AUTHORING_INSTRUCTION = (
    "请根据当前阶段的 Scenario 交接生成可复用 Skill 草稿；"
    "Core 会从实际包文件生成 ImplementationPlan。长材料（尤其录屏）每个路径最多补读一次，"
    "不要通过改变 offset 重复读取；至少写入合法 SKILL.md，完成后调用 finish_authoring 提交结构化自检摘要。"
    "复杂二进制不得由模型反复编写生成器或尝试在模型输出中计算；不得写占位文件或复制原始业务材料。"
    "ScenarioContract 声明结构化输入时，控制器会生成脱敏的 schema fixture；"
    "samplePath/invalidPath 只用于字段与非法输入检查，不能证明业务 happy path。"
    "可执行 Skill 必须根据材料另建小型业务 fixture，并由本地测试产生真实业务结果。"
)
AUTHOR_BUILD_INSTRUCTION = (
    "请根据当前阶段的 Scenario 交接生成可复用 Skill 生产包；"
    "直接物化 SKILL.md、必要的生产脚本、references 和业务 fixture，Core 从实际文件生成 ImplementationPlan。"
    "本阶段不生成或执行 self_check；生产包完成后调用 finish_authoring，由 Core 提交候选并执行交付验收。"
    "结构化浏览器/API 入口必须提供 --validate-only，复用生产输入解析和字段校验，"
    "并在初始化浏览器、网络、凭据或外部 SDK 前返回。"
)
AUTHOR_VALIDATE_INSTRUCTION = (
    "请只验证当前已生成的可执行 Skill 包。读取 ImplementationPlan、SKILL.md、生产脚本和业务 fixture，"
    "调用 write_self_check_plan 生成控制器自检，随后调用 run_offline_self_check；"
    "通过后调用 finish_authoring。不得修改生产脚本、SKILL.md、references、fixtures 或重新读取上传材料。"
)

_AGENT_TOOL_CAPABILITIES = {
    "chat": frozenset({"list_workspace_files", "read_workspace_file"}),
    "edit": frozenset({
        "read_material_bundle",
        "list_workspace_files",
        "read_workspace_file",
        "write_skill_file",
        "write_skill_files",
        "delete_skill_file",
    }),
    "scenario": frozenset({
        "read_material_bundle",
        "write_scenario_draft",
    }),
    # Authoring and repair write the persistent Draft Workspace directly. The
    # controller owns revision snapshots, projections, preflight and signing;
    # no model-facing stage/commit transaction exists.
    "author": frozenset({
        "read_material_bundle",
        "list_workspace_files",
        "read_workspace_file",
        "write_skill_file",
        "write_skill_files",
        "write_tabular_fixture",
        "write_self_check_plan",
        "delete_skill_file",
        "run_offline_self_check",
        "finish_authoring",
    }),
    "author_build": frozenset({
        "read_material_bundle",
        "list_workspace_files",
        "read_workspace_file",
        "write_skill_file",
        "write_skill_files",
        "write_tabular_fixture",
        "delete_skill_file",
        "finish_authoring",
    }),
    "author_validate": frozenset({
        "read_workspace_file",
        "write_self_check_plan",
        "run_offline_self_check",
        "finish_authoring",
    }),
    "repair": frozenset({
        "read_workspace_file",
        "write_skill_file",
        "write_tabular_fixture",
        "write_self_check_plan",
        "replace_skill_file_text",
        "delete_skill_file",
        "run_offline_self_check",
        "finish_authoring",
    }),
}

DEFAULT_AGENT_ITERATIONS = {
    "chat": 6,
    # Scenario is a bounded first handoff in the default workflow.  It remains
    # separately callable for review-only use, while Author stays responsible
    # for package materialization and its own single completion submission.
    "scenario": 8,
    "edit": 12,
    "author_build": 24,
    # Inspect the bounded plan files, run once, allow one plan-only correction,
    # rerun, and finish. Production files remain immutable throughout.
    "author_validate": 12,
    # Repair owns one mechanical root-cause family and one bounded producer
    # set. Twelve model/tool cycles cover inspect, mutate, verify and finish
    # without recreating the former broad multi-cause repair session.
    "repair": 12,
}
DEFAULT_AGENT_TIMEOUT_SECONDS = {
    "chat": 2 * 60,
    "edit": 6 * 60,
    "scenario": 5 * 60,
    "author": 8 * 60,
    "author_build": 6 * 60,
    "author_validate": 4 * 60,
    "repair": 8 * 60,
}
DEFAULT_AGENT_TOTAL_TIMEOUT_SECONDS = 20 * 60


def phase_scoped_user_message(message: str, *, task_mode: str) -> str:
    """Return one idempotent message for the direct Author phase."""

    text = str(message or "").strip()
    normalized_mode = str(task_mode or "").strip().lower()
    if normalized_mode not in {"author", "author_build", "author_validate"}:
        return text
    instruction = (
        AUTHOR_BUILD_INSTRUCTION
        if normalized_mode == "author_build"
        else AUTHOR_VALIDATE_INSTRUCTION
        if normalized_mode == "author_validate"
        else AUTHORING_INSTRUCTION
    )
    supplement = text
    for known_instruction in (
        CANONICAL_EXTRACTION_INSTRUCTION,
        AUTHORING_INSTRUCTION,
        AUTHOR_BUILD_INSTRUCTION,
        AUTHOR_VALIDATE_INSTRUCTION,
    ):
        supplement = supplement.replace(known_instruction, "").strip()
    while supplement.startswith("用户补充要求："):
        supplement = supplement.removeprefix("用户补充要求：").strip()
    if not supplement:
        return instruction
    return f"{instruction}\n\n用户补充要求：{supplement}"


def confirmed_decision_handoff(
    confirmations: tuple[dict[str, Any], ...] | list[dict[str, Any]] = (),
) -> str:
    """Render the current HITL answer as one explicit Author handoff.

    ``validation/scenario_contract.json`` intentionally keeps the original
    Scenario semantic hash and its declared conflicts.  The answer is durable
    workflow state, not a second business contract.  Author nevertheless
    needs a machine-readable indication that the matching conflict is now
    resolved; otherwise it may faithfully copy ``pendingDecisions`` into the
    exported Skill.  This projection keeps the source contract unchanged and
    gives Author one unambiguous effective decision payload.
    """

    resolved: list[dict[str, Any]] = []
    for confirmation in confirmations:
        if not isinstance(confirmation, dict):
            continue
        answer = confirmation.get("answer")
        decision_contract = (
            answer.get("decision_contract")
            if isinstance(answer, dict)
            else None
        )
        if not isinstance(decision_contract, dict):
            continue
        decisions = decision_contract.get("decisions")
        if not isinstance(decisions, dict) or not decisions:
            continue
        resolved.append(
            {
                "scenarioContractHash": str(
                    confirmation.get("scenarioContractHash") or ""
                ).strip(),
                "decisionContract": {
                    "schemaVersion": decision_contract.get("schemaVersion"),
                    "normalizerVersion": decision_contract.get("normalizerVersion"),
                    "decisions": decisions,
                    "capabilities": decision_contract.get("capabilities") or {},
                    "conditionalCapabilities": decision_contract.get(
                        "conditionalCapabilities"
                    )
                    or {},
                    "implementationDependencies": decision_contract.get(
                        "implementationDependencies"
                    )
                    or [],
                    "conflicts": decision_contract.get("conflicts") or [],
                },
            }
        )
    if not resolved:
        return ""
    payload = {
        "schemaVersion": "skill-builder-author-handoff/v1",
        "resolvedDecisions": resolved,
    }
    return "\n".join(
        (
            "## 平台生成的有效 Scenario 决策交接",
            "",
            "以下 JSON 是当前 ScenarioContract 对应的 HITL 已确认结果。",
            "它覆盖同一 decisionId 的 pendingDecisions；最终 Skill 不得再把这些项标记为待确认，也不得改写其语义值。",
            "",
            json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2),
        )
    )


def agent_tool_capabilities(
    task_mode: str,
    *,
    author_handoff_available: bool = False,
    candidate_available: bool = True,
) -> frozenset[str]:
    """Return the single authoritative tool surface for one Agent phase."""

    normalized = str(task_mode or "").strip().lower()
    if normalized in _AGENT_TOOL_CAPABILITIES:
        capabilities = _AGENT_TOOL_CAPABILITIES[normalized]
        if normalized in {"author", "author_build", "author_validate"} and author_handoff_available:
            if normalized == "author_build":
                # Build consumes one bounded aggregate for implementation
                # detail; arbitrary reads otherwise recreate Scenario and
                # spend the write/completion budget on invalid paths.
                capabilities = capabilities - {
                    "list_workspace_files",
                    "read_workspace_file",
                }
            else:
                capabilities = capabilities - {"read_material_bundle"}
            if normalized != "author_build" and not candidate_available:
                # Author may read only evidenceRefs explicitly cited by the
                # handoff. Directory listing stays unavailable so source
                # access cannot turn into a second Scenario pass.
                capabilities = capabilities - {"list_workspace_files"}
        return frozenset(capabilities)
    return frozenset()


def agent_model_tool_choice(task_mode: str) -> str | None:
    """Require tool progress in phases whose only valid completion is a tool."""

    normalized = str(task_mode or "").strip().lower()
    return "required" if normalized in {"scenario", "repair"} else None


def author_model_tool_choice(
    *,
    plan_available: bool,
    scripts_required: bool,
    required_paths_materialized: bool,
    offline_self_check_runs: int,
) -> str | None:
    """Require tools only at Author's plan and executable verification boundaries."""

    if not plan_available:
        return "required"
    if (
        scripts_required
        and required_paths_materialized
        and int(offline_self_check_runs) <= 0
    ):
        return "required"
    return None


def author_no_tool_progress_is_stalled(
    *,
    task_mode: str,
    visible_text_chars_since_tool: int,
    text_limit: int,
    completion_present: bool,
) -> bool:
    """Stop an Author response that spends its output budget without a tool."""

    return bool(
        str(task_mode or "").strip().lower()
        in {"author", "author_build", "author_validate"}
        and not completion_present
        and int(visible_text_chars_since_tool) > max(1, int(text_limit))
    )


def author_tool_progress_is_stalled(
    *,
    task_mode: str,
    unchanged_tool_results: int,
    result_limit: int,
    completion_present: bool,
) -> bool:
    """Stop repeated tool calls that do not change any durable Author state."""

    return bool(
        str(task_mode or "").strip().lower()
        in {"author", "author_build", "author_validate"}
        and not completion_present
        and int(unchanged_tool_results) >= max(1, int(result_limit))
    )


def author_observation_tool_is_progress(
    *,
    task_mode: str,
    tool_name: str,
    ok: bool,
) -> bool:
    """Treat each successful bounded workspace observation as real progress."""

    normalized_mode = str(task_mode or "").strip().lower()
    normalized_name = str(tool_name or "").strip()
    return bool(
        normalized_mode in {"author", "author_build", "author_validate"}
        and ok
        and any(
            marker in normalized_name
            for marker in (
                "read_workspace_file",
                "list_workspace_files",
                "read_material_bundle",
            )
        )
    )


def agent_phase_requires_candidate_commit(run_phase: str) -> bool:
    """Return whether a phase may hand an export candidate to validation."""

    return str(run_phase or "").strip().lower() not in {
        "scenario",
        "chat",
        "edit",
    }


def agent_iteration_budget(
    task_mode: str,
    *,
    configured: int | None = None,
) -> int:
    """Return one bounded safety budget for the phase's actual tool protocol.

    Author no longer pays stage/commit/report-maintenance turns per file, so
    business object counts must not be translated into a completion gate. A
    fixed safety ceiling prevents runaway reasoning while keeping generation
    cost independent from incidental Scenario list lengths.
    """

    if configured is not None:
        value = configured
    elif str(task_mode or "").strip().lower() == "author":
        value = 32
    else:
        value = DEFAULT_AGENT_ITERATIONS.get(str(task_mode or "").strip().lower(), 16)
    return max(4, min(int(value), 40))


def agent_runtime_iteration_limit(
    task_mode: str,
    *,
    configured: int | None = None,
) -> int:
    """Resolve the actual openJiuwen loop limit at one pre-run boundary."""

    normalized = str(task_mode or "").strip().lower()
    return agent_iteration_budget(
        normalized,
        configured=configured,
    )


def agent_timeout_budget(
    run_phase: str,
    *,
    explicit: int | None = None,
    phase_configured: int | None = None,
    global_configured: int | None = None,
) -> int:
    """Resolve timeout precedence once for every concrete Agent adapter."""

    normalized_phase = str(run_phase or "").strip().lower()
    policy_phase = {
    }.get(normalized_phase, normalized_phase)
    value = next(
        (
            candidate
            for candidate in (explicit, phase_configured, global_configured)
            if candidate is not None
        ),
        DEFAULT_AGENT_TIMEOUT_SECONDS.get(
            policy_phase,
            DEFAULT_AGENT_TOTAL_TIMEOUT_SECONDS,
        ),
    )
    return max(30 if normalized_phase == "chat" else 60, int(value))


AgentStreamDeadlineReason = Literal["phase_timeout", "stream_idle"]


def agent_stream_deadline_reason(
    *,
    phase_elapsed_seconds: float,
    stream_idle_seconds: float,
    phase_timeout_seconds: float,
    idle_timeout_seconds: float,
) -> AgentStreamDeadlineReason | None:
    """Return the terminal runtime deadline, if any.

    Artifact and checkpoint milestones are diagnostics only. A streaming
    Agent may be stopped only by the phase's absolute budget or by receiving
    no stream event for the configured idle interval.
    """

    if phase_elapsed_seconds >= phase_timeout_seconds:
        return "phase_timeout"
    if idle_timeout_seconds > 0 and stream_idle_seconds >= idle_timeout_seconds:
        return "stream_idle"
    return None


def agent_stream_wait_timeout(
    *,
    phase_elapsed_seconds: float,
    stream_idle_seconds: float,
    phase_timeout_seconds: float,
    idle_timeout_seconds: float,
) -> float:
    """Return the next bounded stream wait without creating another deadline."""

    remaining = [phase_timeout_seconds - phase_elapsed_seconds]
    if idle_timeout_seconds > 0:
        remaining.append(idle_timeout_seconds - stream_idle_seconds)
    return max(0.05, min(remaining))


__all__ = [
    "AUTHORING_INSTRUCTION",
    "CANONICAL_EXTRACTION_INSTRUCTION",
    "DEFAULT_AGENT_ITERATIONS",
    "DEFAULT_AGENT_TIMEOUT_SECONDS",
    "DEFAULT_AGENT_TOTAL_TIMEOUT_SECONDS",
    "agent_tool_capabilities",
    "agent_phase_requires_candidate_commit",
    "agent_iteration_budget",
    "agent_runtime_iteration_limit",
    "author_model_tool_choice",
    "author_no_tool_progress_is_stalled",
    "author_observation_tool_is_progress",
    "author_tool_progress_is_stalled",
    "confirmed_decision_handoff",
    "agent_stream_deadline_reason",
    "agent_stream_wait_timeout",
    "agent_timeout_budget",
    "phase_scoped_user_message",
]
