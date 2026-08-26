# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Bounded package write and delete tools for the Author agent."""

from __future__ import annotations

import ast
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Awaitable, Callable

from skill_builder.application.agent_core import (
    _emit,
    _normalize_rel_path,
    _stdlib_conflicting_script_path,
)
from skill_builder.application.agent_self_check import normalize_agent_self_check
from skill_builder.application.agent_submission import candidate_completion_tool_schema
from skill_builder.application.acceptance import (
    _external_success_evidence,
    accept_skill_package,
    self_check_protocol_requirements,
)
from skill_builder.application.artifact_digest import skill_artifact_sha256
from skill_builder.application.draft_workspace import DraftWorkspaceStore
from skill_builder.application.fixture_builder import (
    build_tabular_xlsx_bytes,
    platform_fixture_business_replay_issues,
    platform_owned_fixture_paths,
)
from skill_builder.application.implementation_evidence import (
    normalize_implementation_evidence,
    persist_implementation_evidence,
)
from skill_builder.application.implementation_integrity import (
    empty_conditional_branch_source_signals,
    platform_only_document_reference_source_signals,
    unused_mapping_input_source_signals,
)
from skill_builder.application.finding_ownership import (
    FINDING_OWNERSHIP_SCHEMA_VERSION,
    FindingPhase,
    findings_for_phase,
)
from skill_builder.application.implementation_plan import (
    load_implementation_plan,
    missing_required_plan_paths,
    projected_scripts_required,
    synthesize_implementation_plan,
)
from skill_builder.application.offline_validation import (
    SELF_CHECK_PLANNED_STATUS,
    SELF_CHECK_SCHEMA_VERSION,
    compile_self_check_plan_script,
    normalize_self_check_contract_covers,
    self_check_package_input_issues,
    validate_self_check_summary,
)
from skill_builder.application.python_name_analysis import (
    analyze_undefined_python_names,
)
from skill_builder.application.repair_scope import repair_target_paths
from skill_builder.domain.workspace_paths import (
    forbidden_skill_package_path,
    forbidden_skill_package_root,
    split_generated_skill_path,
)
from skill_builder.ports import SkillBuilderEventEmitter


MAX_BATCH_WRITE_FILES = 4
MAX_BATCH_WRITE_BYTES = 256 * 1024
MAX_REPLACE_TEXT_BYTES = 64 * 1024
# The transport accepts a larger logical request. The controller normalizes
# it into bounded batches before touching the workspace.
MAX_WRITE_REQUEST_FILES = 64
_BINARY_SKILL_SUFFIXES = frozenset(
    {".xlsx", ".xls", ".zip", ".pdf", ".png", ".jpg", ".jpeg", ".gif"}
)
_CONTROLLER_SELF_CHECK_PATHS = (
    "scripts/self_check.py",
    "scripts/run_offline_test.py",
    "scripts/offline_test.py",
)
_SELF_CHECK_PLAN_WARNING_IDS = frozenset(
    {
        "self_check_external_offline_missing",
        "self_check_invalid_input_missing",
        "self_check_file_handoff_missing",
        "self_check_cli_coverage_incomplete",
        "self_check_contract_coverage_incomplete",
        "self_check_output_field_coverage_incomplete",
        "self_check_output_type_coverage_incomplete",
        "self_check_output_section_coverage_incomplete",
    }
)


@dataclass(frozen=True, slots=True)
class AuthorTools:
    write: Any
    write_many: Any
    write_tabular_fixture: Any
    write_self_check_plan: Any
    replace: Any
    delete: Any


@dataclass(slots=True)
class AuthorCompletionState:
    completion_payload: dict[str, Any] | None = None


def create_author_completion_tool(
    *,
    tool: Callable[..., Any],
    name: str,
    task_mode: str,
    state: AuthorCompletionState,
    emit_event: SkillBuilderEventEmitter | None,
    root: Path | None = None,
    initial_artifact_sha256: str | None = None,
    latest_offline_self_check: Callable[[], dict[str, Any] | None] | None = None,
    build_preflight: Callable[[], Awaitable[Any]] | None = None,
) -> Any:
    """Capture the Agent report without granting candidate commit authority."""

    transport_schema = candidate_completion_tool_schema()
    # OpenAI-compatible providers sometimes emit an empty completion call.
    # Let the handler return its existing field-specific correction instead
    # of terminating the whole ReAct stream in SDK schema validation.
    transport_schema.pop("required", None)

    @tool(
        name=name,
        description=(
            "Finish the Author or Repair turn by submitting its summary and structured "
            "agent self-check. This does not validate or commit the package; the controller "
            "runs the complete preflight after the Agent stops."
        ),
        input_params=transport_schema,
    )
    async def finish_authoring(
        summary: str = "",
        agent_self_check: dict[str, Any] | None = None,
        implementation_evidence: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        if task_mode not in {
            "author",
            "author_build",
            "author_validate",
            "repair",
        }:
            return {
                "ok": False,
                "error": "wrong_phase",
                "message": "只有 Author 或 Repair 阶段可以提交 Agent 自检。",
            }
        implementation_plan = load_implementation_plan(root) if root is not None else None
        if (
            root is not None
            and task_mode in {"author", "author_build"}
            and (root / "validation" / "author_handoff.json").is_file()
            and implementation_plan is None
        ):
            synthesized = synthesize_implementation_plan(root)
            if not synthesized.get("ok"):
                return {
                    "ok": False,
                    "error": "controller_implementation_plan_invalid",
                    "issues": synthesized.get("issues") or [],
                    "message": (
                        "Core 无法从当前包生成有效 ImplementationPlan；"
                        "请按 issues 补齐生产脚本、业务 fixture 或能力入口。"
                    ),
                }
            implementation_plan = load_implementation_plan(root)
        if (
            root is not None
            and task_mode in {"author", "author_build", "author_validate"}
            and (root / "validation" / "author_handoff.json").is_file()
        ):
            if implementation_plan is None:
                return {
                    "ok": False,
                    "error": "implementation_plan_required",
                    "message": "Author 完成前必须存在通过控制器校验的 ImplementationPlan。",
                }
            missing_required_paths = missing_required_plan_paths(
                root,
                implementation_plan,
            )
            if missing_required_paths:
                return {
                    "ok": False,
                    "error": "implementation_plan_not_materialized",
                    "missingPaths": missing_required_paths,
                    "message": "ImplementationPlan 中的必需 Skill、生产脚本或能力入口尚未生成。",
                }
        build_preflight_receipt: dict[str, Any] | None = None
        if root is not None and task_mode in {"author", "author_build"}:
            preflight = (
                await build_preflight()
                if callable(build_preflight)
                else await accept_skill_package(root, execution_port=None)
            )
            preflight_payload = (
                preflight.to_result()
                if callable(getattr(preflight, "to_result", None))
                else preflight
                if isinstance(preflight, dict)
                else {}
            )
            build_findings = findings_for_phase(
                preflight_payload.get("findings") or [],
                FindingPhase.BUILD,
                severity="fail",
            )
            if build_findings:
                return {
                    "ok": False,
                    "error": "author_build_preflight_failed",
                    "findings": [
                        {
                            key: finding.get(key)
                            for key in (
                                "id",
                                "severity",
                                "category",
                                "message",
                                "path",
                                "failureOwner",
                                "repairable",
                                "details",
                            )
                            if finding.get(key) not in (None, "", [], {})
                        }
                        for finding in build_findings[:20]
                    ],
                    "message": (
                        "生产包未通过 Build-owned 预检。只修复返回 findings 指向的"
                        "生产文件，再次调用 finish_authoring；不要生成 self-check。"
                    ),
                }
            if task_mode == "author_build":
                build_preflight_receipt = {
                    "schemaVersion": "skill-builder-author-build-preflight/v1",
                    "findingOwnershipVersion": FINDING_OWNERSHIP_SCHEMA_VERSION,
                    "artifactSha256": skill_artifact_sha256(
                        root / "generated-skill"
                    ),
                    "blockingFindingIds": [],
                }
        latest_check = (
            latest_offline_self_check()
            if callable(latest_offline_self_check)
            else None
        )
        if (
            task_mode in {"author", "author_validate"}
            and root is not None
            and (
                bool((implementation_plan or {}).get("scriptsRequired"))
                or self_check_protocol_requirements(root).get("documented_entrypoints")
            )
            and not latest_check
        ):
            return {
                "ok": False,
                "error": "offline_self_check_required",
                "message": (
                    "Skill 已公开生产 CLI；完成 Author 前必须先提交结构化自检计划，"
                    "并运行一次控制器离线自验证。"
                ),
            }
        if str((latest_check or {}).get("status") or "").strip().lower() == "fail":
            return {
                "ok": False,
                "error": "offline_self_check_failed",
                "message": (
                    "最近一次控制器离线自验证仍为 fail；"
                    "请只修复返回的阻断项，修改产物后重新运行离线自验证。"
                ),
            }
        normalized_summary = str(summary or "").strip()[:2000]
        if not normalized_summary:
            return {
                "ok": False,
                "error": "author_summary_required",
                "message": "完成 Author 阶段前必须提交非空 summary。",
            }
        normalized_self_check = normalize_agent_self_check(agent_self_check)
        if normalized_self_check is None:
            return {
                "ok": False,
                "error": "agent_self_check_required",
                "message": "完成 Author 阶段前必须提交结构化 agent_self_check。",
            }
        normalized_evidence = normalize_implementation_evidence(
            implementation_evidence
        )
        if root is not None:
            persist_implementation_evidence(root, normalized_evidence)
        artifact_changed = bool(
            root is not None
            and skill_artifact_sha256(root / "generated-skill")
            != initial_artifact_sha256
        )
        if task_mode == "repair" and root is not None and not artifact_changed:
            return {
                "ok": False,
                "error": "repair_write_required",
                "message": (
                    "Repair 尚未改变 generated-skill。"
                    "请只修复 finding 指向的候选文件。"
                ),
                "acceptedMutationKinds": ["generated_skill"],
            }
        payload = {
            "completion_source": "authoring_completed",
            "summary": normalized_summary,
            "agent_self_check": normalized_self_check,
            # The completion payload retains the submitted delta for backwards
            # compatible Agent reports; the durable graph is persisted above
            # and the controller loads that complete graph at finalization.
            "implementation_evidence": normalized_evidence,
        }
        if build_preflight_receipt is not None:
            payload["build_preflight"] = build_preflight_receipt
        state.completion_payload = payload
        await _emit(
            emit_event,
            "agent.authoring_completed",
            normalized_summary[:500],
            {
                "phase": task_mode,
                "agent_self_check": normalized_self_check,
            },
        )
        return {
            "ok": True,
            "completed": True,
            "next_action": "stop_current_phase",
            "message": "Agent 自检已提交；控制器将独立执行完整预检。",
        }

    return finish_authoring


def create_author_tools(
    *,
    tool: Callable[..., Any],
    names: dict[str, str],
    root,
    task_mode: str,
    accessor: Any,
    draft_workspace: DraftWorkspaceStore,
    emit_event: SkillBuilderEventEmitter | None,
) -> AuthorTools:
    """Register package mutations; semantic quality is not a write-time gate."""

    allowed_repair_paths = (
        repair_target_paths(root) if task_mode == "repair" else None
    )

    def _repair_scope_error(normalized: str) -> dict[str, Any] | None:
        if task_mode != "repair":
            return None
        if allowed_repair_paths is None:
            return {
                "ok": False,
                "error": "repair_scope_missing",
                "path": normalized,
                "message": "Repair 缺少控制器签发的文件修改范围，已拒绝候选写入。",
            }
        if normalized not in allowed_repair_paths:
            return {
                "ok": False,
                "error": "repair_path_out_of_scope",
                "path": normalized,
                "allowedPaths": sorted(allowed_repair_paths),
                "message": "该文件不在本次 Repair 的控制器允许范围内。",
            }
        return None

    def _validated_path(
        path: str,
        *,
        controller_self_check_write: bool = False,
    ) -> tuple[str | None, dict[str, Any] | None]:
        try:
            normalized = _normalize_rel_path(path, root=root)
        except ValueError:
            normalized = str(path or "").replace("\\", "/")
        if normalized.startswith("generated-skill/"):
            normalized = normalized.removeprefix("generated-skill/")
        if normalized == "agents/openai.yaml":
            return None, {
                "ok": False,
                "error": "host_metadata_owned",
                "message": (
                    "agents/openai.yaml 完全由宿主适配层管理；"
                    "Author 不得生成或修改该文件。"
                ),
            }
        if (
            task_mode == "author"
            and projected_scripts_required(root) is False
            and normalized.startswith("scripts/")
        ):
            return None, {
                "ok": False,
                "error": "scripts_not_required",
                "path": normalized,
                "message": (
                    "Core 已签发 scriptsRequired=false；知识/SOP Skill 不生成 scripts。"
                ),
            }
        if (
            task_mode in {"author", "author_build"}
            and (root / "validation" / "author_handoff.json").is_file()
            and not controller_self_check_write
        ):
            plan = load_implementation_plan(root)
            if plan is not None and normalized not in set(plan.get("files") or []):
                return None, {
                    "ok": False,
                    "error": "implementation_plan_path_out_of_scope",
                    "path": normalized,
                    "allowedPaths": plan.get("files") or [],
                    "message": "该文件不在已确认的 ImplementationPlan 中。",
                }
        if repair_scope_error := _repair_scope_error(normalized):
            return None, repair_scope_error
        if (
            normalized in _CONTROLLER_SELF_CHECK_PATHS
            and not controller_self_check_write
        ):
            return None, {
                "ok": False,
                "error": "controller_self_check_owned",
                "path": normalized,
                "message": (
                    "离线自检入口由控制器根据结构化计划生成；"
                    "请调用 write_self_check_plan，不要直接写入或改写脚本。"
                ),
            }
        stdlib_conflict = _stdlib_conflicting_script_path(normalized)
        if stdlib_conflict is not None:
            return None, {
                "ok": False,
                "error": "python_module_name_conflict",
                "path": normalized,
                **stdlib_conflict,
                "message": (
                    "Python module name conflicts with the standard library; "
                    "choose a package-specific name."
                ),
            }
        if forbidden := forbidden_skill_package_path(normalized):
            return None, {
                "ok": False,
                "error": "wrong_skill_path_root",
                "message": f"`{forbidden}` 属于平台工作区，不能写入导出包。",
            }
        return normalized, None

    def _content_validation_error(
        normalized: str,
        content: str,
    ) -> dict[str, Any] | None:
        exported_path = f"generated-skill/{normalized}"
        previous_file_preserved = (root / exported_path).is_file()
        if normalized in platform_owned_fixture_paths(root, root / "generated-skill"):
            return {
                "ok": False,
                "error": "platform_fixture_owned",
                "message": (
                    "该结构化 fixture 由平台按 ScenarioContract 确定性生成，模型不能覆盖。"
                    "请直接在自检和文档中引用它。"
                ),
                "path": exported_path,
                "previousFilePreserved": previous_file_preserved,
            }
        if Path(normalized).suffix.lower() in _BINARY_SKILL_SUFFIXES:
            return {
                "ok": False,
                "error": "binary_content_requires_structured_tool",
                "path": exported_path,
                "message": (
                    "write_skill_file 只写 UTF-8 文本，不能伪造二进制文件。"
                    "XLSX 业务 fixture 请调用 write_tabular_fixture；其他二进制资产需由宿主提供。"
                ),
                "previousFilePreserved": previous_file_preserved,
            }
        if Path(normalized).suffix.lower() in {
            ".md",
            ".markdown",
            ".txt",
            ".yaml",
            ".yml",
        }:
            nonportable_references = platform_only_document_reference_source_signals(
                content,
                path=normalized,
            )
            if nonportable_references:
                return {
                    "ok": False,
                    "error": "package_document_integrity_invalid",
                    "path": exported_path,
                    "findings": [
                        {
                            "id": "nonportable_package_reference",
                            "severity": "fail",
                            "category": "package_integrity",
                            "message": (
                                "导出文档引用了仅在 SkillBuilder workspace 中存在的路径；"
                                "请将必要事实归纳到 references/ 并使用包内相对路径。"
                            ),
                            "failureOwner": "package",
                            "repairable": True,
                            "details": nonportable_references[:50],
                        }
                    ],
                    "message": (
                        "导出文档未通过写入前可移植性校验；旧文件保持不变。"
                        "删除 workspace-only 路径或改为包内相对路径后重新提交。"
                    ),
                    "previousFilePreserved": previous_file_preserved,
                }
        if not normalized.endswith(".py"):
            return None
        try:
            ast.parse(content, filename=normalized)
        except SyntaxError as exc:
            return {
                "ok": False,
                "error": "python_syntax_invalid",
                "path": exported_path,
                "line": exc.lineno,
                "offset": exc.offset,
                "message": str(exc.msg or "Python syntax is invalid"),
                "previousFilePreserved": previous_file_preserved,
            }
        findings: list[dict[str, Any]] = []
        undefined_analysis = analyze_undefined_python_names(
            content,
            filename=normalized,
        )
        if undefined_analysis["undefinedNames"]:
            findings.append(
                {
                    "id": "python_undefined_names",
                    "severity": "fail",
                    "category": "executable_integrity",
                    "message": "Python 脚本引用了未定义名称。",
                    "failureOwner": "package",
                    "repairable": True,
                    "details": [
                        {
                            "path": normalized,
                            "names": undefined_analysis["undefinedNames"],
                        }
                    ],
                }
            )
        empty_branches = empty_conditional_branch_source_signals(
            content,
            path=normalized,
        )
        if empty_branches:
            findings.append(
                {
                    "id": "empty_required_branch",
                    "severity": "fail",
                    "category": "executable_integrity",
                    "message": "生产脚本包含只执行 pass 的条件分支，分支能力不会产生实现结果。",
                    "failureOwner": "package",
                    "repairable": False,
                    "details": empty_branches[:50],
                }
            )
        unused_inputs = unused_mapping_input_source_signals(
            content,
            path=normalized,
        )
        if unused_inputs:
            findings.append(
                {
                    "id": "declared_input_not_consumed",
                    "severity": "fail",
                    "category": "executable_integrity",
                    "message": "生产脚本读取了输入字段但没有将其用于业务处理或输出。",
                    "failureOwner": "package",
                    "repairable": False,
                    "details": unused_inputs[:50],
                }
            )
        if findings:
            return {
                "ok": False,
                "error": "python_build_integrity_invalid",
                "path": exported_path,
                "findings": findings,
                "message": (
                    "Python 生产文件未通过写入前 Build 校验；旧文件保持不变。"
                    "只修复返回 findings 后重新提交该文件。"
                ),
                "previousFilePreserved": previous_file_preserved,
            }
        return None

    async def _write_one(
        path: str,
        content: str,
        *,
        emit_tool_events: bool,
        controller_self_check_write: bool = False,
    ) -> dict[str, Any]:
        if task_mode == "scenario":
            return {
                "ok": False,
                "error": "wrong_phase",
                "message": "Scenario 阶段不能修改 generated-skill/。",
            }
        normalized, path_error = _validated_path(
            path,
            controller_self_check_write=controller_self_check_write,
        )
        if path_error is not None:
            return path_error
        assert normalized is not None

        if emit_tool_events:
            await _emit(
                emit_event,
                "tool.started",
                f"写入 Skill 文件：{path}",
                {"tool": "write_skill_file", "path": path},
            )
        if content_error := _content_validation_error(normalized, content):
            if emit_tool_events:
                await _emit(
                    emit_event,
                    "tool.completed",
                    "Skill 文件写入前校验失败，旧文件保持不变。",
                    {
                        "tool": "write_skill_file",
                        "path": content_error.get("path") or path,
                        "ok": False,
                        "error": content_error.get("error"),
                        "findingIds": [
                            str(item.get("id") or "")
                            for item in content_error.get("findings") or []
                            if isinstance(item, dict)
                        ],
                    },
                )
            return content_error
        result: dict[str, Any] = accessor.write_skill_file(path=path, content=content)
        if result.get("ok"):
            result["persisted"] = True
            result["draft"] = draft_workspace.capture_file(normalized)
            if not result["draft"].get("ok"):
                result.update(
                    {
                        "ok": False,
                        "error": "draft_persistence_failed",
                        "message": "文件已写入工作树，但未同步到持久化 Draft。",
                    }
                )
        if emit_tool_events:
            await _emit(
                emit_event,
                "tool.completed",
                "Skill 文件已写入。" if result.get("ok") else "Skill 文件写入失败。",
                {
                    "tool": "write_skill_file",
                    "path": result.get("path") or path,
                    "ok": result.get("ok"),
                    "size_bytes": result.get("size_bytes"),
                },
            )
        return result

    @tool(
        name=names["write_skill_file"],
        description=(
            "Write one complete portable SKILL.md, long production script, or single independent "
            "file relative to generated-skill/. Use write_skill_files once for 2 to 4 small "
            "references or HTML/JSON/text fixtures instead of writing each separately. Use "
            "write_self_check_plan for the offline self-check; "
            "this tool cannot write diagnostic entrypoints. Never write placeholder content. "
            "Controller-owned sample-input/invalid fixtures validate schema only; Author Build "
            "creates only the minimum material-grounded happy fixture. An external HTML/JSON "
            "response fixture is useful only when the production entrypoint actually consumes it."
        ),
        input_params={
            "type": "object",
            "properties": {"path": {"type": "string"}, "content": {"type": "string"}},
            "required": ["path", "content"],
        },
    )
    async def write_skill_file(path: str, content: str) -> dict[str, Any]:
        return await _write_one(path, content, emit_tool_events=True)

    @tool(
        name=names.get("write_tabular_fixture", "write_tabular_fixture"),
        description=(
            "Write one real XLSX business fixture from structured columns and rows. "
            "Use only for a package-local fixtures/*.xlsx path already present in the "
            "ImplementationPlan. This replaces text or generator-script attempts to create XLSX."
        ),
        input_params={
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "columns": {
                    "type": "array",
                    "minItems": 1,
                    "maxItems": 100,
                    "items": {"type": "string"},
                },
                "rows": {
                    "type": "array",
                    "minItems": 1,
                    "maxItems": 100,
                    "items": {
                        "type": "array",
                        "maxItems": 100,
                        "items": {},
                    },
                },
            },
            "required": ["path", "columns", "rows"],
            "additionalProperties": False,
        },
    )
    async def write_tabular_fixture(
        path: str,
        columns: list[str],
        rows: list[list[Any]],
    ) -> dict[str, Any]:
        if task_mode not in {"author", "author_build"}:
            return {"ok": False, "error": "wrong_phase"}
        normalized, path_error = _validated_path(path)
        if path_error is not None:
            return path_error
        assert normalized is not None
        if not normalized.startswith("fixtures/") or not normalized.endswith(".xlsx"):
            return {
                "ok": False,
                "error": "tabular_fixture_path_invalid",
                "path": normalized,
                "message": "结构化表格工具只写 ImplementationPlan 中的 fixtures/*.xlsx。",
            }
        if normalized in platform_owned_fixture_paths(root, root / "generated-skill"):
            return {
                "ok": False,
                "error": "platform_fixture_owned",
                "path": f"generated-skill/{normalized}",
            }
        normalized_columns = [str(value or "").strip() for value in columns]
        if (
            not normalized_columns
            or any(not value for value in normalized_columns)
            or len(set(normalized_columns)) != len(normalized_columns)
        ):
            return {"ok": False, "error": "tabular_fixture_columns_invalid"}
        if not rows or any(
            not isinstance(row, list) or len(row) != len(normalized_columns)
            for row in rows
        ):
            return {
                "ok": False,
                "error": "tabular_fixture_rows_invalid",
                "columnCount": len(normalized_columns),
            }
        records = [
            {
                column: "" if value is None else str(value)
                for column, value in zip(normalized_columns, row)
            }
            for row in rows
        ]
        content = build_tabular_xlsx_bytes(normalized_columns, records)
        write_bytes = getattr(accessor, "write_skill_bytes", None)
        if not callable(write_bytes):
            return {"ok": False, "error": "binary_write_not_supported"}
        await _emit(
            emit_event,
            "tool.started",
            f"写入结构化 XLSX fixture：{path}",
            {"tool": "write_tabular_fixture", "path": path},
        )
        result = write_bytes(path=path, content=content)
        if result.get("ok"):
            result["persisted"] = True
            result["rowCount"] = len(records)
            result["columnCount"] = len(normalized_columns)
            result["draft"] = draft_workspace.capture_file(normalized)
            if not result["draft"].get("ok"):
                result.update(
                    {
                        "ok": False,
                        "error": "draft_persistence_failed",
                    }
                )
        await _emit(
            emit_event,
            "tool.completed",
            "结构化 XLSX fixture 已写入。"
            if result.get("ok")
            else "结构化 XLSX fixture 写入失败。",
            {
                "tool": "write_tabular_fixture",
                "path": result.get("path") or path,
                "ok": result.get("ok"),
                "size_bytes": result.get("size_bytes"),
            },
        )
        return result

    @tool(
        name=names.get("write_self_check_plan", "write_self_check_plan"),
        description=(
            "Validate and persist a structured offline replay plan after all production "
            "scripts and CLI documentation are complete. Submit only checks with package-local "
            "Python commands, expected exit codes, deterministic output assertions, and covers. "
            "The controller compiles scripts/self_check.py; never write that file directly."
        ),
        input_params={
            "type": "object",
            "properties": {
                "checks": {
                    "type": "array",
                    "minItems": 1,
                    "maxItems": 24,
                    "items": {
                        "type": "object",
                        "properties": {
                            "id": {"type": "string"},
                            "kind": {
                                "type": "string",
                                "enum": [
                                    "happy_path",
                                    "invalid_input",
                                    "business_rule",
                                    "file_handoff",
                                    "external_offline",
                                ],
                            },
                            "covers": {
                                "type": "array",
                                "items": {"type": "string"},
                            },
                            "commands": {
                                "type": "array",
                                "items": {
                                    "anyOf": [
                                        {
                                            "type": "object",
                                            "properties": {
                                                "command": {
                                                    "type": "array",
                                                    "items": {"type": "string"},
                                                },
                                                "expectedExitCodes": {
                                                    "type": "array",
                                                    "items": {"type": "integer"},
                                                },
                                            },
                                            "required": ["command"],
                                        },
                                        {
                                            "type": "array",
                                            "items": {"type": "string"},
                                        },
                                    ]
                                },
                            },
                            "assertions": {
                                "type": "array",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "source": {"type": "string"},
                                        "path": {"type": "string"},
                                        "operator": {"type": "string"},
                                        "assertion": {"type": "string"},
                                        "expected": {},
                                        "commandIndex": {"type": "integer"},
                                        "message": {"type": "string"},
                                    },
                                    "required": ["source", "path"],
                                },
                            },
                        },
                        "required": [
                            "id",
                            "kind",
                            "covers",
                            "commands",
                            "assertions",
                        ],
                    },
                }
            },
            "required": ["checks"],
            "additionalProperties": False,
        },
    )
    async def write_self_check_plan(
        checks: list[Any] | None = None,
    ) -> dict[str, Any]:
        if task_mode == "author" and projected_scripts_required(root) is False:
            return {
                "ok": False,
                "error": "self_check_not_required",
                "message": "scriptsRequired=false，不生成可执行 self-check。",
            }
        payload = {
            "schemaVersion": SELF_CHECK_SCHEMA_VERSION,
            "status": SELF_CHECK_PLANNED_STATUS,
            "checks": normalize_self_check_contract_covers(root, checks),
        }
        requirements = self_check_protocol_requirements(
            root,
            root / "generated-skill",
        )
        protocol = validate_self_check_summary(payload, **requirements)
        unexpected_external_cases = [
            str(case.get("id") or "")
            for case in protocol.cases
            if case.get("kind") == "external_offline"
            and not requirements.get("external_entrypoints")
        ]
        if unexpected_external_cases:
            issues = [
                {
                    "id": "self_check_external_offline_unexpected",
                    "message": (
                        "当前 Skill 没有浏览器/API/外部运行入口；"
                        "删除 external_offline 用例，只保留本地 happy_path、"
                        "invalid_input 和必要 business_rule。"
                    ),
                    "caseIds": unexpected_external_cases,
                },
                *protocol.issues,
            ]
        else:
            issues = [
                *protocol.issues,
                *self_check_package_input_issues(
                    root / "generated-skill",
                    protocol.cases,
                ),
                *platform_fixture_business_replay_issues(
                    root,
                    root / "generated-skill",
                    protocol.cases,
                ),
            ]
        if task_mode in {"author", "author_validate"}:
            implementation_plan = load_implementation_plan(root) or {}
            capability_entrypoints = implementation_plan.get("capabilityEntrypoints")
            external_entrypoints = {
                str(path).strip()
                for capability, path in (
                    capability_entrypoints.items()
                    if isinstance(capability_entrypoints, dict)
                    else ()
                )
                if capability in {
                    "api_runtime",
                    "browser_runtime",
                    "external_runtime",
                }
                and str(path).strip()
            }
            planned_cases = tuple({**case, "status": "pass"} for case in protocol.cases)
            if external_entrypoints and not _external_success_evidence(
                root,
                list(planned_cases),
                external_entrypoints=external_entrypoints,
            ):
                issues.append(
                    {
                        "id": "self_check_external_success_fixture_missing",
                        "message": (
                            "外部生产入口的 happy_path/business_rule/file_handoff "
                            "必须显式传入 Author 创建的本地 HTML/JSON 响应 fixture；"
                            "blocked 降级请保留在独立 external_offline 用例。"
                        ),
                        "entrypoints": sorted(external_entrypoints),
                    }
                )
        blocking_issues = [
            item
            for item in issues
            if str(item.get("id") or "") not in _SELF_CHECK_PLAN_WARNING_IDS
        ]
        warning_issues = [
            item
            for item in issues
            if str(item.get("id") or "") in _SELF_CHECK_PLAN_WARNING_IDS
        ]
        if blocking_issues:
            return {
                "ok": False,
                "error": "self_check_plan_invalid",
                "message": (
                    "结构化离线重放计划未通过控制器校验；"
                    "请只修正返回的具体 issues 后重试。"
                ),
                "issues": list(blocking_issues)[:50],
                "warnings": list(warning_issues)[:50],
            }
        target_path = _CONTROLLER_SELF_CHECK_PATHS[0]
        if task_mode == "repair" and allowed_repair_paths is not None:
            target_path = next(
                (
                    path
                    for path in _CONTROLLER_SELF_CHECK_PATHS
                    if path in allowed_repair_paths
                ),
                target_path,
            )
        result = await _write_one(
            target_path,
            compile_self_check_plan_script(protocol.cases),
            emit_tool_events=True,
            controller_self_check_write=True,
        )
        if result.get("ok"):
            result.update(
                {
                    "controllerOwned": True,
                    "caseCount": len(protocol.cases),
                    "next_action": "run_offline_self_check",
                    "warnings": list(warning_issues)[:50],
                }
            )
        return result

    @tool(
        name=names.get("replace_skill_file_text", "replace_skill_file_text"),
        description=(
            "Repair-only exact text replacement for one existing allowed Skill file. "
            "Use this instead of rewriting a large file when the failing block is known."
        ),
        input_params={
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "old_text": {"type": "string", "minLength": 1},
                "new_text": {"type": "string"},
            },
            "required": ["path", "old_text", "new_text"],
            "additionalProperties": False,
        },
    )
    async def replace_skill_file_text(
        path: str,
        old_text: str,
        new_text: str,
    ) -> dict[str, Any]:
        if task_mode != "repair":
            return {"ok": False, "error": "wrong_phase"}
        normalized, path_error = _validated_path(path)
        if path_error is not None:
            return path_error
        assert normalized is not None
        if normalized in platform_owned_fixture_paths(root, root / "generated-skill"):
            return {
                "ok": False,
                "error": "platform_fixture_owned",
                "path": f"generated-skill/{normalized}",
            }
        if not old_text or len(old_text.encode("utf-8")) > MAX_REPLACE_TEXT_BYTES:
            return {
                "ok": False,
                "error": "repair_replacement_invalid",
                "max_bytes": MAX_REPLACE_TEXT_BYTES,
            }
        if len(new_text.encode("utf-8")) > MAX_REPLACE_TEXT_BYTES:
            return {
                "ok": False,
                "error": "repair_replacement_too_large",
                "max_bytes": MAX_REPLACE_TEXT_BYTES,
            }
        target = root / "generated-skill" / normalized
        try:
            content = target.read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            return {
                "ok": False,
                "error": "repair_target_not_readable",
                "path": normalized,
            }
        occurrence_count = content.count(old_text)
        if occurrence_count != 1:
            return {
                "ok": False,
                "error": "repair_replacement_not_unique",
                "path": normalized,
                "occurrenceCount": occurrence_count,
            }
        result = await _write_one(
            normalized,
            content.replace(old_text, new_text, 1),
            emit_tool_events=True,
        )
        if result.get("ok"):
            result["replacementCount"] = 1
        return result

    @tool(
        name=names["write_skill_files"],
        description=(
            "Write one bounded logical batch of 2 to 4 independent small UTF-8 text files "
            "relative to generated-skill/. Use this for small references and HTML/JSON/text "
            "fixtures. Keep SKILL.md and long production scripts in separate write_skill_file "
            "calls; use write_tabular_fixture for XLSX. The entire batch is validated before "
            "any file is written."
        ),
        input_params={
            "type": "object",
            "properties": {
                "files": {
                    "type": "array",
                    # Keep transport validation deliberately permissive. The
                    # handler returns recoverable, indexed diagnostics for a
                    # bad entry instead of letting the SDK abort the Agent.
                    "items": {
                        "type": "object",
                        "properties": {
                            "path": {"type": "string"},
                            "content": {"type": "string"},
                        },
                    },
                }
            },
        },
    )
    async def write_skill_files(files: list[Any] | None = None) -> dict[str, Any]:
        if task_mode == "scenario":
            return {
                "ok": False,
                "error": "wrong_phase",
                "message": "Scenario 阶段不能修改 generated-skill/。",
            }
        if not isinstance(files, list) or not files:
            return {
                "ok": False,
                "error": "files_required",
                "message": (
                    "files 必须包含 2 至 4 个小型文本文件；"
                    "每项都提交完整的 path 和 content。"
                ),
                "next_action": "write_small_text_batch",
            }
        if len(files) > MAX_WRITE_REQUEST_FILES:
            return {
                "ok": False,
                "error": "too_many_files_in_request",
                "max_files": MAX_WRITE_REQUEST_FILES,
                "actual_files": len(files),
            }
        normalized_files: list[tuple[str, str]] = []
        entry_errors: dict[int, dict[str, Any]] = {}
        total_bytes = 0
        for index, item in enumerate(files):
            if (
                not isinstance(item, dict)
                or not isinstance(item.get("path"), str)
                or not isinstance(item.get("content"), str)
            ):
                entry_errors[index] = {
                    "ok": False,
                    "error": "invalid_file_entry",
                    "message": "每个文件都必须包含字符串 path 和 content。",
                }
                continue
            path = item["path"]
            content = item["content"]
            total_bytes += len(path.encode("utf-8")) + len(content.encode("utf-8"))
            _normalized, path_error = _validated_path(path)
            if path_error is not None:
                entry_errors[index] = path_error
                continue
            assert _normalized is not None
            if content_error := _content_validation_error(_normalized, content):
                entry_errors[index] = content_error
                continue
            normalized_files.append((path, content))
        if entry_errors:
            results = [
                entry_errors.get(index)
                or {
                    "ok": False,
                    "error": "batch_validation_failed",
                    "message": "同一逻辑批次包含无效文件，未写入任何文件。",
                }
                for index in range(len(files))
            ]
            return {
                "ok": False,
                "error": "batch_validation_failed",
                "file_count": len(files),
                "size_bytes": total_bytes,
                "results": results,
                "failed": [
                    {"index": index, **result}
                    for index, result in enumerate(results)
                ],
                "batches": [],
            }
        if total_bytes > MAX_BATCH_WRITE_BYTES:
            return {
                "ok": False,
                "error": "batch_too_large",
                "max_bytes": MAX_BATCH_WRITE_BYTES,
                "actual_bytes": total_bytes,
            }

        await _emit(
            emit_event,
            "tool.started",
            f"批量写入 {len(normalized_files)} 个 Skill 文件。",
            {
                "tool": "write_skill_files",
                "file_count": len(normalized_files),
                "size_bytes": total_bytes,
            },
        )
        results: list[dict[str, Any]] = []
        batches: list[dict[str, Any]] = []
        for batch_start in range(0, len(normalized_files), MAX_BATCH_WRITE_FILES):
            batch = normalized_files[batch_start : batch_start + MAX_BATCH_WRITE_FILES]
            batch_results = [
                await _write_one(path, content, emit_tool_events=False)
                for path, content in batch
            ]
            results.extend(batch_results)
            batches.append(
                {
                    "index": len(batches),
                    "start": batch_start,
                    "count": len(batch_results),
                    "ok": all(item.get("ok") for item in batch_results),
                }
            )
        failed = [
            {"index": index, **result}
            for index, result in enumerate(results)
            if not result.get("ok")
        ]
        await _emit(
            emit_event,
            "tool.completed",
            (
                f"已批量写入 {len(results)} 个 Skill 文件。"
                if not failed
                else f"批量写入完成，{len(failed)} 个文件失败。"
            ),
            {
                "tool": "write_skill_files",
                "ok": not failed,
                "file_count": len(results),
                "failed_count": len(failed),
                "size_bytes": total_bytes,
            },
        )
        return {
            "ok": not failed,
            "file_count": len(results),
            "size_bytes": total_bytes,
            "results": results,
            "failed": failed,
            "batches": batches,
        }

    @tool(
        name=names["delete_skill_file"],
        description="Delete one obsolete file from generated-skill/.",
        input_params={
            "type": "object",
            "properties": {"path": {"type": "string"}},
            "required": ["path"],
        },
    )
    async def delete_skill_file(path: str) -> dict[str, Any]:
        if task_mode == "scenario":
            return {"ok": False, "error": "wrong_phase"}
        try:
            normalized = _normalize_rel_path(path, root=root)
        except ValueError:
            normalized = str(path or "").replace("\\", "/")
        normalized, explicit_generated_root = split_generated_skill_path(normalized)
        if forbidden_skill_package_root(normalized) and not explicit_generated_root:
            return {
                "ok": False,
                "deleted": False,
                "error": "wrong_skill_path_root",
                "message": "删除工具只管理导出包文件。",
            }
        if repair_scope_error := _repair_scope_error(normalized):
            return {**repair_scope_error, "deleted": False}
        if normalized in _CONTROLLER_SELF_CHECK_PATHS:
            return {
                "ok": False,
                "deleted": False,
                "error": "controller_self_check_owned",
                "path": normalized,
                "message": (
                    "离线自检入口由控制器管理；"
                    "请调用 write_self_check_plan 更新计划，不能直接删除入口。"
                ),
            }
        if normalized in platform_owned_fixture_paths(root, root / "generated-skill"):
            return {
                "ok": False,
                "deleted": False,
                "error": "platform_fixture_owned",
                "message": (
                    "该结构化 fixture 由平台按 ScenarioContract 确定性生成，模型不能删除。"
                    "请直接在自检和文档中引用它。"
                ),
                "path": f"generated-skill/{normalized}",
            }
        delete_file = getattr(accessor, "delete_skill_file", None)
        if not callable(delete_file):
            return {"ok": False, "error": "delete_not_supported"}
        result = delete_file(path=path)
        if result.get("deleted"):
            result["draft"] = draft_workspace.remove_file(normalized)
        await _emit(
            emit_event,
            "tool.completed",
            "Skill 文件已删除。" if result.get("deleted") else "Skill 文件无需删除。",
            {"tool": "delete_skill_file", "path": result.get("path") or path, **result},
        )
        return result

    return AuthorTools(
        write=write_skill_file,
        write_many=write_skill_files,
        write_tabular_fixture=write_tabular_fixture,
        write_self_check_plan=write_self_check_plan,
        replace=replace_skill_file_text,
        delete=delete_skill_file,
    )


__all__ = [
    "AuthorCompletionState",
    "AuthorTools",
    "MAX_BATCH_WRITE_BYTES",
    "MAX_BATCH_WRITE_FILES",
    "MAX_REPLACE_TEXT_BYTES",
    "MAX_WRITE_REQUEST_FILES",
    "create_author_completion_tool",
    "create_author_tools",
]
