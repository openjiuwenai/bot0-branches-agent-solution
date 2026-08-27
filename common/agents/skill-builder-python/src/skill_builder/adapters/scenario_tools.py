"""Single-submit Scenario draft tool for the OpenJiuwen adapter."""

from __future__ import annotations

import copy
import json
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from skill_builder.application.agent_core import _emit
from skill_builder.application.draft_workspace import DraftWorkspaceStore
from skill_builder.application.input_contracts import scenario_tabular_input_issues
from skill_builder.application.scenario_projection import (
    scenario_contract_artifacts,
    scenario_contract_hitl_request,
)
from skill_builder.domain.scenario_contract import (
    normalize_scenario_contract,
    scenario_required_capabilities,
    scenario_draft_shape_issues,
)
from skill_builder.ports import SkillBuilderEventEmitter
from skill_builder.runtime.serialization import json_safe


@dataclass(slots=True)
class ScenarioToolState:
    submitted: bool = False
    committed: bool = False
    submission_attempts: int = 0
    last_draft_sha256: str = ""
    last_rejection_signature: tuple[str, tuple[str, ...], str] | None = None
    last_rejection: dict[str, Any] | None = None
    normalization_warnings: list[dict[str, Any]] = field(default_factory=list)


@dataclass(frozen=True, slots=True)
class ScenarioTools:
    write: Any
    finalize_persisted: Callable[[], Awaitable[dict[str, Any]]]
    state: ScenarioToolState


def scenario_repair_progress(
    previous: tuple[str, tuple[str, ...], str] | None,
    current: tuple[str, tuple[str, ...], str],
) -> tuple[bool, str]:
    """Classify a Scenario correction by contract findings, not tool traffic."""

    if previous is None:
        return True, "initial_rejection"
    previous_error, previous_issues, previous_draft = previous
    current_error, current_issues, current_draft = current
    if not current_draft:
        return False, "draft_missing"
    if previous_draft == current_draft:
        return False, "draft_unchanged"
    old = set(previous_issues)
    new = set(current_issues)
    if old:
        if new < old:
            return True, "issues_reduced"
        if old.isdisjoint(new):
            return True, "previous_issues_removed"
        return False, "previous_issues_remain"
    if previous_error == current_error:
        return False, "root_error_unchanged"
    return True, "root_error_changed"


def decode_scenario_transport_value(value: Any, expected_type: type) -> Any:
    """Decode one lossless JSON transport wrapper emitted by some providers."""

    if isinstance(value, expected_type):
        return value
    if not isinstance(value, str) or not value.strip():
        return value
    try:
        decoded = json.loads(value)
    except (TypeError, ValueError):
        return value
    return decoded if isinstance(decoded, expected_type) else value


def merge_split_fact_evidence(value: Any) -> Any:
    """Merge adjacent fact/evidence fragments emitted as separate objects.

    Only complementary adjacent objects are merged. No evidence is invented,
    and already valid facts retain their exact representation.
    """

    if not isinstance(value, dict) or not isinstance(value.get("facts"), list):
        return value
    source = copy.deepcopy(value)
    facts = source["facts"]
    merged: list[Any] = []
    index = 0
    while index < len(facts):
        current = facts[index]
        following = facts[index + 1] if index + 1 < len(facts) else None
        complementary_evidence = (
            isinstance(current, dict)
            and current.get("kind")
            and "value" in current
            and not current.get("evidenceRefs")
            and isinstance(following, dict)
            and following.get("evidenceRefs")
            and not following.get("kind")
            and "value" not in following
            and set(following).issubset({"evidenceRefs", "sourceQuote"})
        )
        if complementary_evidence:
            combined = {**current, "evidenceRefs": following["evidenceRefs"]}
            if not combined.get("sourceQuote") and following.get("sourceQuote"):
                combined["sourceQuote"] = following["sourceQuote"]
            merged.append(combined)
            index += 2
            continue
        merged.append(current)
        index += 1
    source["facts"] = merged
    return source


def lift_nested_fact_metadata(value: Any) -> Any:
    """Lift transport metadata misplaced inside ``fact.value``.

    Only existing evidenceRefs/sourceQuote values move. No provenance is
    invented, and facts already carrying outer metadata stay unchanged.
    """

    if not isinstance(value, dict) or not isinstance(value.get("facts"), list):
        return value
    source = copy.deepcopy(value)
    for fact in source["facts"]:
        if not isinstance(fact, dict) or not isinstance(fact.get("value"), dict):
            continue
        nested = fact["value"]
        for key in ("evidenceRefs", "sourceQuote"):
            if fact.get(key) in (None, "", []) and nested.get(key) not in (
                None,
                "",
                [],
            ):
                fact[key] = nested.pop(key)
    return source


def drop_ungrounded_optional_facts(value: Any) -> tuple[Any, list[dict[str, Any]]]:
    """Drop optional facts without provenance while keeping core facts strict."""

    if not isinstance(value, dict) or not isinstance(value.get("facts"), list):
        return value, []
    optional_kinds = {"non_trigger", "dependency", "script_requirement", "acceptance"}
    source = copy.deepcopy(value)
    kept: list[Any] = []
    dropped: list[dict[str, Any]] = []
    for index, fact in enumerate(source["facts"]):
        kind = (
            str(fact.get("kind") or "").strip().lower().replace("-", "_")
            if isinstance(fact, dict)
            else ""
        )
        if (
            isinstance(fact, dict)
            and kind in optional_kinds
            and not fact.get("evidenceRefs")
        ):
            dropped.append(
                {
                    "index": index,
                    "kind": kind,
                    "reason": "evidence_refs_missing",
                }
            )
            continue
        kept.append(fact)
    source["facts"] = kept
    return source, dropped


def create_scenario_tools(
    *,
    tool: Callable[..., Any],
    names: dict[str, str],
    task_mode: str,
    accessor: Any,
    draft_workspace: DraftWorkspaceStore,
    emit_event: SkillBuilderEventEmitter | None,
    record_failure: Callable[[dict[str, Any]], dict[str, Any]],
    clear_failure: Callable[[], None],
    set_completion: Callable[[dict[str, Any]], None],
    authoritative_request_text: str = "",
) -> ScenarioTools:
    """Register the single atomic Scenario submission tool."""

    state = ScenarioToolState()

    def fail_turn(result: dict[str, Any], *, summary: str) -> dict[str, Any]:
        state.submitted = True
        result.update(
            {
                "terminal": True,
                "next_action": "stop_current_phase",
                "submissionAttempts": state.submission_attempts,
            }
        )
        set_completion(
            {
                "status": "failed",
                "summary": summary,
                "files": sorted(set(accessor.files_written)),
                "pending_decisions": [],
                "blockers": [
                    str(value)[:500]
                    for value in (result.get("issues") or [result.get("message") or result.get("error")])[:20]
                    if str(value or "").strip()
                ],
                "unverified_inputs": [],
                "unverified_capabilities": [],
                "suggested_next_message": "",
                "completion_source": "scenario_contract_rejected",
                "lifecycle_failure": {
                    "code": str(result.get("error") or "scenario_contract_invalid"),
                    "phase": "scenario",
                    "submissionAttempts": state.submission_attempts,
                    "issues": [str(value)[:500] for value in (result.get("issues") or [])[:20]],
                },
            }
        )
        return record_failure(result)

    def rejection_progress(
        result: dict[str, Any],
        *,
        draft_sha256: str,
    ) -> tuple[bool, str]:
        issues = tuple(
            sorted(
                {
                    str(value).strip()
                    for value in result.get("issues") or []
                    if str(value or "").strip()
                }
            )
        )
        signature = (
            str(result.get("error") or "scenario_contract_invalid"),
            issues,
            str(draft_sha256 or ""),
        )
        progress = scenario_repair_progress(state.last_rejection_signature, signature)
        state.last_rejection_signature = signature
        state.last_rejection = json_safe(result, max_text_length=12000)
        return progress

    def request_bounded_repair(
        result: dict[str, Any],
        *,
        progress_reason: str,
    ) -> dict[str, Any]:
        result.update(
            {
                "terminal": False,
                "next_action": "repair_and_resubmit",
                "submissionAttempts": state.submission_attempts,
                "remainingSubmissionAttempts": 1,
                "repair": {
                    "allowed": True,
                    "noProgress": False,
                    "progressReason": progress_reason,
                },
                "message": (
                    f"{str(result.get('message') or 'ScenarioContract 未通过统一契约编译。')} "
                    "请只按 issues 修正当前 ScenarioDraft，不要重新读取材料；本阶段最多再提交一次。"
                ),
            }
        )
        return record_failure(result)

    @tool(
        name=names["write_scenario_draft"],
        description=(
            "Atomically persist, normalize and submit the complete ScenarioDraft as a native JSON object. "
            "One successful call completes the Scenario worker; one bounded correction is allowed only "
            "when the first result explicitly returns next_action=repair_and_resubmit."
        ),
        input_params={
            "type": "object",
            "properties": {
                "content": {
                    "type": "object",
                    "description": (
                        "Complete ScenarioDraft JSON object. The controller applies all "
                        "authoritative shape, evidence and contract validation."
                    ),
                    "properties": {
                        "facts": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "additionalProperties": True,
                            },
                        },
                        "conflicts": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "additionalProperties": True,
                            },
                        },
                        "skillName": {"type": "string"},
                        "displayName": {"type": "string"},
                    },
                    "required": ["facts", "conflicts"],
                    "additionalProperties": False,
                },
                # Some OpenAI-compatible models flatten the sole object
                # argument into the tool root. Keep both transport shapes;
                # the same handler and contract compiler remain authoritative.
                "facts": {
                    "anyOf": [
                        {
                            "type": "array",
                            "items": {"type": "object", "additionalProperties": True},
                        },
                        {"type": "string"},
                    ],
                },
                "conflicts": {
                    "anyOf": [
                        {
                            "type": "array",
                            "items": {"type": "object", "additionalProperties": True},
                        },
                        {"type": "string"},
                    ],
                },
                "skillName": {"type": "string"},
                "displayName": {"type": "string"},
            },
            "additionalProperties": False,
        },
    )
    async def write_scenario_draft(
        content: Any = None,
        facts: Any = None,
        conflicts: Any = None,
        skillName: str = "",
        displayName: str = "",
    ) -> dict[str, Any]:
        if task_mode != "scenario":
            return {
                "ok": False,
                "error": "scenario_draft_not_available",
                "message": "当前阶段只消费已确认的场景快照。",
            }
        if state.submitted:
            return {
                "ok": False,
                "error": "scenario_turn_already_submitted",
                "message": "本轮 Scenario 已提交，不能再次提交。",
            }
        content = decode_scenario_transport_value(content, dict)
        facts = decode_scenario_transport_value(facts, list)
        conflicts = decode_scenario_transport_value(conflicts, list)
        if not isinstance(content, dict) and isinstance(facts, list):
            content = {
                "facts": facts,
                "conflicts": conflicts if isinstance(conflicts, list) else [],
            }
            if str(skillName or "").strip():
                content["skillName"] = skillName
            if str(displayName or "").strip():
                content["displayName"] = displayName
        if not isinstance(content, dict):
            result = {
                "ok": False,
                "error": "scenario_draft_transport_invalid",
                "issues": ["ScenarioDraft content/facts object is required"],
                "message": (
                    "请提交 content={facts, conflicts}，或直接提交等价的顶层 "
                    "facts/conflicts；不得发送空工具参数。"
                ),
            }
            state.submission_attempts += 1
            progress, progress_reason = rejection_progress(
                result,
                draft_sha256="",
            )
            if state.submission_attempts < 2 and progress:
                return request_bounded_repair(
                    result,
                    progress_reason=progress_reason,
                )
            return fail_turn(
                result,
                summary="ScenarioDraft 空工具调用经一次有限修正后仍无有效内容。",
            )
        merged_content = lift_nested_fact_metadata(
            merge_split_fact_evidence(content)
        )
        normalized_content, dropped_facts = drop_ungrounded_optional_facts(
            merged_content
        )
        if dropped_facts:
            state.normalization_warnings.extend(dropped_facts)
        result = draft_workspace.persist_scenario_draft(normalized_content)
        if dropped_facts:
            result["droppedOptionalFacts"] = dropped_facts
        if result.get("ok"):
            state.last_draft_sha256 = str(result.get("sha256") or "")
        await _emit(
            emit_event,
            "agent.scenario_draft_persisted" if result.get("ok") else "tool.completed",
            "ScenarioDraft 已持久化，平台正在同一次工具调用内完成原子提交。"
            if result.get("ok")
            else "ScenarioDraft 持久化失败。",
            {"tool": "write_scenario_draft", **json_safe(result, max_text_length=2000)},
        )
        if not result.get("ok"):
            state.submission_attempts += 1
            previous_rejection = dict(state.last_rejection or {})
            progress, progress_reason = rejection_progress(result, draft_sha256="")
            if state.submission_attempts < 2 and progress:
                return request_bounded_repair(result, progress_reason=progress_reason)
            if previous_rejection and not progress:
                previous_rejection["secondaryTransportFailure"] = json_safe(
                    result,
                    max_text_length=2000,
                )
                result = previous_rejection
            result.setdefault("repair", {}).update(
                {
                    "allowed": False,
                    "noProgress": not progress,
                    "progressReason": progress_reason,
                }
            )
            return fail_turn(
                result,
                summary="ScenarioDraft 传输经一次有限修正后仍无有效进展，场景职责已停止。",
            )
        committed = await _commit_scenario_draft(
            str(result.get("sha256") or ""),
            tool_name="write_scenario_draft",
        )
        committed.setdefault("draftSha256", result.get("sha256"))
        committed.setdefault("draftPath", result.get("path"))
        return committed

    async def _commit_scenario_draft(
        sha256: str,
        *,
        tool_name: str,
        controller_finalization: bool = False,
    ) -> dict[str, Any]:
        if task_mode != "scenario":
            result = {
                "ok": False,
                "error": "scenario_contract_not_available",
                "message": "当前阶段只消费已确认的 ScenarioContract，不重新抽取场景。",
            }
        elif state.committed:
            result = {
                "ok": False,
                "error": "scenario_contract_already_committed",
                "message": "ScenarioContract 已成功提交；请结束当前阶段。",
            }
        elif state.submitted:
            result = {
                "ok": False,
                "error": "scenario_turn_already_submitted",
                "terminal": True,
                "next_action": "stop_current_phase",
                "message": "本轮 Scenario 的原子提交已结束；不要在同一 Agent 内形成第二个修复循环。",
            }
        else:
            state.submission_attempts += 1
            decoded_contract, transport_issue = draft_workspace.load_scenario_draft(
                expected_sha256=sha256,
            )
            if transport_issue is not None or decoded_contract is None:
                failure = {
                    **(transport_issue or {"error": "scenario_draft_invalid"}),
                    "ok": False,
                    "message": str(
                        (transport_issue or {}).get("message")
                        or "ScenarioDraft 无法按提交的 sha256 加载。"
                    ),
                }
                progress, progress_reason = rejection_progress(
                    failure,
                    draft_sha256=sha256,
                )
                if not controller_finalization and state.submission_attempts < 2 and progress:
                    result = request_bounded_repair(
                        failure,
                        progress_reason=progress_reason,
                    )
                else:
                    failure["repair"] = {
                        "allowed": False,
                        "noProgress": not progress,
                        "progressReason": progress_reason,
                    }
                    result = fail_turn(
                        failure,
                        summary="ScenarioDraft 经一次有限修正后仍无法解析，场景职责已停止。",
                    )
            else:
                contract_source = copy.deepcopy(decoded_contract)
                request_text = str(authoritative_request_text or "").strip()
                request_capabilities = scenario_required_capabilities(
                    {"purpose": request_text}
                )
                if request_text and request_capabilities:
                    facts = contract_source.get("facts")
                    facts = list(facts) if isinstance(facts, list) else []
                    facts.append(
                        {
                            "kind": "step",
                            "value": request_text,
                            "evidenceRefs": ["platform:user-request"],
                            "sourceQuote": request_text,
                        }
                    )
                    contract_source["facts"] = facts
                normalized, contract_issues = normalize_scenario_contract(contract_source)
                draft_issues = [
                    *scenario_draft_shape_issues(decoded_contract),
                    *scenario_tabular_input_issues(normalized),
                ]
                projection_artifacts, projection_issues = (
                    scenario_contract_artifacts(normalized)
                    if not contract_issues and not draft_issues
                    else ({}, [])
                )
                hitl_request, hitl_issues = (
                    scenario_contract_hitl_request(normalized)
                    if not contract_issues and not draft_issues
                    else ({}, [])
                )
                contract_submission_issues = list(
                    dict.fromkeys([*draft_issues, *contract_issues])
                )
                platform_projection_issues = list(
                    dict.fromkeys([*projection_issues, *hitl_issues])
                )
                submission_issues = list(
                    dict.fromkeys(
                        [*contract_submission_issues, *platform_projection_issues]
                    )
                )
                if submission_issues:
                    platform_failure = bool(
                        platform_projection_issues and not contract_submission_issues
                    )
                    failure = {
                        "ok": False,
                        "error": (
                            "scenario_projection_invalid"
                            if platform_failure
                            else "scenario_contract_invalid"
                        ),
                        "issues": submission_issues,
                        "message": (
                            "ScenarioContract 已通过输入校验，但平台投影编译失败；"
                            "该错误不会交给 Scenario Agent 修复。"
                            if platform_failure
                            else "ScenarioContract 未通过统一契约编译。"
                        ),
                    }
                    progress, progress_reason = rejection_progress(
                        failure,
                        draft_sha256=sha256,
                    )
                    if platform_failure:
                        result = fail_turn(
                            failure,
                            summary="ScenarioContract 平台投影失败，场景职责已停止。",
                        )
                    elif not controller_finalization and state.submission_attempts < 2 and progress:
                        result = request_bounded_repair(
                            failure,
                            progress_reason=progress_reason,
                        )
                    else:
                        failure["repair"] = {
                            "allowed": False,
                            "noProgress": not progress,
                            "progressReason": progress_reason,
                        }
                        result = fail_turn(
                            failure,
                            summary="ScenarioContract 经一次有限修正后仍未通过结构编译，场景职责已停止。",
                        )
                else:
                    write_result = accessor.write_validation_file(
                        path="scenario_contract.json",
                        content=json.dumps(normalized, ensure_ascii=False, indent=2) + "\n",
                    )
                    projection_results = [
                        accessor.write_validation_file(path=path, content=content)
                        for path, content in projection_artifacts.items()
                    ] if write_result.get("ok") else []
                    projection_failed = [item for item in projection_results if not item.get("ok")]
                    result = {
                        **write_result,
                        "contractHash": normalized["semanticHash"],
                        "schemaVersion": normalized["schemaVersion"],
                        "projectionFiles": [
                            f"validation/{path}" for path in projection_artifacts
                        ],
                        "hitlKind": hitl_request.get("kind") if hitl_request else None,
                        "decisionIds": hitl_request.get("decision_ids") or [],
                        "nextAction": (
                            "platform_create_scenario_hitl"
                            if hitl_request
                            else "continue_author"
                        ),
                    }
                    if projection_failed or not result.get("ok"):
                        result.update(
                            {
                                "ok": False,
                                "error": "scenario_projection_write_failed",
                                "projectionErrors": projection_failed,
                                "message": "ScenarioContract 或平台投影无法原子持久化。",
                            }
                        )
                        result = fail_turn(
                            result,
                            summary="ScenarioContract 平台投影写入失败，场景职责已停止。",
                        )
                    else:
                        pending_decisions = [
                            {
                                "decision_id": str(item.get("decisionId") or ""),
                                "title": str(item.get("title") or item.get("decisionId") or ""),
                                "status": "pending",
                            }
                            for item in normalized.get("pendingDecisions") or []
                            if isinstance(item, dict) and str(item.get("decisionId") or "").strip()
                        ]
                        set_completion(
                            {
                                "status": "needs_review" if hitl_request else "completed",
                                "summary": (
                                    "ScenarioContract 已提交，平台已准备统一人工确认。"
                                    if hitl_request
                                    else "ScenarioContract 已提交且无待决策项，可直接进入 Author。"
                                ),
                                "files": [
                                    "validation/scenario_contract.json",
                                    *[f"validation/{path}" for path in projection_artifacts],
                                ],
                                "pending_decisions": pending_decisions,
                                "blockers": [],
                                "unverified_inputs": [],
                                "unverified_capabilities": [],
                                "normalization_warnings": list(
                                    state.normalization_warnings
                                ),
                                "suggested_next_message": "",
                                "completion_source": "scenario_contract_submission",
                                "scenario_contract_hash": normalized["semanticHash"],
                            }
                        )
                        state.committed = True
                        state.submitted = True
                        scenario_state = draft_workspace.load_state()
                        scenario_state.update(
                            {
                                "phase": "scenario_submitted",
                                "scenarioContractHash": normalized["semanticHash"],
                            }
                        )
                        draft_workspace.save_state(scenario_state)
                        clear_failure()

        await _emit(
            emit_event,
            "agent.scenario_submitted" if result.get("ok") else "tool.completed",
            (
                "ScenarioContract 已提交，宿主将创建统一人工确认。"
                if result.get("hitlKind")
                else "ScenarioContract 已提交，可直接进入 Author。"
            )
            if result.get("ok")
            else (
                "ScenarioContract 提交未通过，可按精确问题有限修正一次。"
                if not result.get("terminal")
                else "ScenarioContract 提交未通过。"
            ),
            {"tool": tool_name, **json_safe(result, max_text_length=2000)},
        )
        return result

    async def finalize_persisted() -> dict[str, Any]:
        """Run the same compiler against the last durable Scenario draft."""

        sha256 = state.last_draft_sha256
        if not sha256:
            workspace_state = draft_workspace.load_state()
            sha256 = str(workspace_state.get("scenarioDraftSha256") or "")
        if not sha256:
            return {
                "ok": False,
                "error": "scenario_draft_missing",
                "terminal": True,
                "next_action": "stop_current_phase",
                "message": "Scenario Agent 未持久化可供控制器复核的草稿。",
            }
        state.last_draft_sha256 = sha256
        return await _commit_scenario_draft(
            sha256,
            tool_name="controller_finalize_scenario_draft",
            controller_finalization=True,
        )

    return ScenarioTools(
        write=write_scenario_draft,
        finalize_persisted=finalize_persisted,
        state=state,
    )


__all__ = [
    "decode_scenario_transport_value",
    "merge_split_fact_evidence",
    "ScenarioToolState",
    "ScenarioTools",
    "create_scenario_tools",
    "scenario_repair_progress",
]
