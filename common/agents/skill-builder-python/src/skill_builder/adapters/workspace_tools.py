# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Phase-scoped workspace and material read tools."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from skill_builder.application.agent_core import (
    MAX_AGENT_FILE_READ_BYTES,
    _emit,
    _normalize_rel_path,
)
from skill_builder.application.material_bundle import (
    SCENARIO_MATERIAL_FOLLOWUP_MAX_CHARS,
    SCENARIO_MATERIAL_MAX_DIGEST_CHARS,
    SCENARIO_MATERIAL_MAX_FILE_CHARS,
    SCENARIO_MATERIAL_MAX_INDEX_CHARS,
    SCENARIO_MATERIAL_MAX_TOTAL_CHARS,
    material_followup_read_error,
    material_path_is_model_readable_text,
    read_material_bundle as read_material_bundle_core,
)
from skill_builder.application.repair_scope import repair_target_paths
from skill_builder.domain.workspace_paths import (
    normalize_phase_workspace_read_path,
    phase_workspace_path_allowed,
)
from skill_builder.ports import SkillBuilderEventEmitter


MAX_REPAIR_PREWRITE_INSPECTIONS = 8


def _author_evidence_paths(root: Path) -> frozenset[str]:
    try:
        handoff = json.loads(
            (root / "validation" / "author_handoff.json").read_text(
                encoding="utf-8"
            )
        )
    except (OSError, TypeError, ValueError):
        return frozenset()

    paths: set[str] = set()

    def collect(value: Any, *, evidence_refs: bool = False) -> None:
        if isinstance(value, dict):
            for key, nested in value.items():
                collect(nested, evidence_refs=key == "evidenceRefs")
            return
        if isinstance(value, list):
            for nested in value:
                collect(nested, evidence_refs=evidence_refs)
            return
        if not evidence_refs or not isinstance(value, str):
            return
        normalized = value.replace("\\", "/").strip().lstrip("./")
        if normalized.startswith("inputs/") and ".." not in normalized.split("/"):
            paths.add(normalized)
            source = Path(normalized)
            parsed = source.with_name(f"{source.stem}_parsed.md").as_posix()
            if (root / parsed).is_file():
                paths.add(parsed)

    collect(handoff)
    return frozenset(paths)


@dataclass(slots=True)
class WorkspaceReadState:
    material_bundle_snapshot: dict[str, Any] | None = None
    material_bundle_followup_paths: set[str] = field(default_factory=set)
    material_followup_reads: set[str] = field(default_factory=set)
    # Author/Repair may inspect one source file once after the aggregate
    # material bundle.  Keeping this at the tool-session boundary prevents a
    # model from spending its whole iteration budget rereading a long
    # recording with different offsets.
    material_reads: set[str] = field(default_factory=set)
    workspace_file_digests: dict[str, str] = field(default_factory=dict)
    workspace_file_next_offsets: dict[str, int | None] = field(default_factory=dict)
    # A Repair handoff already contains structured findings and target paths.
    # Bound pre-write inspection so a model cannot consume the whole repair
    # turn by browsing an unchanged candidate.
    repair_prewrite_inspections: int = 0


@dataclass(frozen=True, slots=True)
class WorkspaceReadTools:
    list_files: Any
    read_file: Any
    read_material_bundle: Any


def create_workspace_read_tools(
    *,
    tool: Callable[..., Any],
    names: dict[str, str],
    root: Path,
    task_mode: str,
    responsibility_phase: str | None = None,
    accessor: Any,
    materials_markdown: str,
    emit_event: SkillBuilderEventEmitter | None,
    state: WorkspaceReadState,
    author_handoff_available: bool = False,
) -> WorkspaceReadTools:
    active_responsibility = str(responsibility_phase or task_mode or "").strip().lower()
    repair_targets = (
        repair_target_paths(root) if active_responsibility == "repair" else None
    )
    repair_workspace_targets = {
        f"generated-skill/{path}" for path in (repair_targets or ())
    }
    author_evidence_paths = (
        _author_evidence_paths(root) if author_handoff_available else frozenset()
    )

    def workspace_file_digest(path: str) -> str | None:
        try:
            target = (root / path).resolve()
            resolved_root = root.resolve()
            if target != resolved_root and not target.is_relative_to(resolved_root):
                return None
            if not target.is_file():
                return None
            return hashlib.sha256(target.read_bytes()).hexdigest()
        except (OSError, ValueError):
            return None

    def repeated_unchanged_read(
        *,
        active_phase: str,
        path: str,
        offset: int,
        digest: str | None,
    ) -> bool:
        if active_phase not in {
            "author",
            "author_build",
            "author_validate",
            "repair",
        } or digest is None:
            return False
        previous_digest = state.workspace_file_digests.get(path)
        if previous_digest != digest:
            state.workspace_file_next_offsets.pop(path, None)
            return False
        expected_offset = state.workspace_file_next_offsets.get(path)
        return expected_offset is None or max(0, int(offset)) != expected_offset

    def repair_has_mutated_candidate() -> bool:
        return any(
            str(path or "").replace("\\", "/").startswith("generated-skill/")
            for path in (getattr(accessor, "files_written", None) or [])
        )

    def repair_inspection_block() -> dict[str, Any] | None:
        active_phase = active_responsibility
        if active_phase != "repair" or repair_has_mutated_candidate():
            return None
        inspection_limit = max(
            1,
            len(repair_workspace_targets)
            if repair_targets is not None
            else MAX_REPAIR_PREWRITE_INSPECTIONS,
        )
        if state.repair_prewrite_inspections < inspection_limit:
            state.repair_prewrite_inspections += 1
            return None
        return {
            "ok": False,
            "error": "repair_write_required",
            "message": (
                "Repair 的写前候选检查额度已用完。请停止继续列举/读取，"
                "根据 handoff 的 findings 与 targetPaths 修改 generated-skill；"
                "候选变化后可继续自检和提交。"
            ),
            "maxPrewriteInspections": inspection_limit,
        }

    def repair_read_scope_block(path: str, *, operation: str) -> dict[str, Any] | None:
        if active_responsibility != "repair" or repair_targets is None:
            return None
        normalized = str(path or "").replace("\\", "/").strip("/")
        if operation == "read" and normalized in repair_workspace_targets:
            return None
        return {
            "ok": False,
            "error": "repair_read_out_of_scope",
            "message": (
                "Repair 只能读取 repair_plan.targetPaths 指向的候选文件；"
                "findings 和目标清单已在 handoff 中提供，禁止遍历整个包。"
            ),
            "path": normalized,
            "targetPaths": sorted(repair_targets),
        }

    @tool(
        name=names["list_workspace_files"],
        description=(
            "List files inside the current Skill Builder workspace. Only .skill-builder/skills is readable under the "
            "platform-private .skill-builder root."
        ),
        input_params={
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "recursive": {"type": "boolean"},
                "max_depth": {"type": "integer"},
            },
        },
    )
    async def list_workspace_files(
        path: str = "inputs", recursive: bool = False, max_depth: int | None = None
    ) -> dict[str, Any]:
        active_responsibility = responsibility_phase or task_mode
        normalized_list_path = str(path or "").replace("\\", "/").strip("/")
        if (
            str(active_responsibility or "").strip().lower()
            in {"author", "author_build"}
            and author_handoff_available
            and (
                normalized_list_path == "inputs"
                or normalized_list_path.startswith("inputs/")
            )
        ):
            result = {
                "ok": False,
                "error": "author_handoff_source_list_forbidden",
                "message": (
                    "Author 只能按 scenario_author_handoff.evidenceRefs 读取具体材料，"
                    "不能遍历 inputs/ 或重新执行 Scenario 发现。"
                ),
                "phase": active_responsibility,
                "path": path,
            }
            await _emit(
                emit_event,
                "tool.completed",
                f"已阻止 Author 遍历交接来源：{path}",
                {"tool": "list_workspace_files", **result},
            )
            return result
        if not phase_workspace_path_allowed(
            active_responsibility,
            path,
            operation="list",
            workspace_root=root,
        ):
            result = {
                "ok": False,
                "error": "phase_path_not_allowed",
                "message": (
                    "当前阶段不能读取该路径。Scenario 只读取上传材料；Author 只读取结构化契约、"
                    "内置能力资源和 generated-skill 候选；Author 只能读取 authoringContract 明确引用的单个材料，不能重新遍历 inputs/ 或旧自然语言投影。"
                ),
                "phase": active_responsibility,
                "path": path,
            }
            await _emit(
                emit_event,
                "tool.completed",
                f"已阻止跨阶段列目录：{path}",
                {"tool": "list_workspace_files", **result},
            )
            return result
        blocked = repair_read_scope_block(path, operation="list")
        if blocked:
            await _emit(
                emit_event,
                "tool.completed",
                f"已阻止 Repair 遍历候选：{path}",
                {"tool": "list_workspace_files", **blocked},
            )
            return blocked
        blocked = repair_inspection_block()
        if blocked:
            return {**blocked, "path": path}
        await _emit(
            emit_event,
            "tool.started",
            f"列出工作区文件：{path}",
            {"tool": "list_workspace_files", "path": path, "recursive": recursive},
        )
        result = accessor.list_workspace_files(path=path, recursive=recursive, max_depth=max_depth)
        await _emit(
            emit_event,
            "tool.completed",
            f"已列出工作区文件：{path}",
            {
                "tool": "list_workspace_files",
                "path": path,
                "ok": result.get("ok"),
                "entries": len(result.get("entries") or []),
            },
        )
        return result

    @tool(
        name=names["read_workspace_file"],
        description=(
            "Read one UTF-8 text slice from generated-skill/ or another path allowed by the current "
            "phase. With scenario_author_handoff, Author may read only inputs paths explicitly cited by "
            "evidenceRefs; directory listing, uncited sources, and validation projections stay blocked. "
            "For a long file, continue with next_offset as offset; offset and length are character counts. "
            "Scenario may make one bounded follow-up read for a truncated material; Author/Repair may make "
            "at most one direct read per inputs/ file, including long recordings, so do not retry the same path."
        ),
        input_params={
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "offset": {"type": "integer", "minimum": 0, "default": 0},
                "length": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": MAX_AGENT_FILE_READ_BYTES,
                },
            },
            "required": ["path"],
        },
    )
    async def read_workspace_file(
        path: str,
        offset: int = 0,
        length: int | None = None,
    ) -> dict[str, Any]:
        active_responsibility = responsibility_phase or task_mode
        try:
            requested_workspace_path = _normalize_rel_path(path, root=root)
        except ValueError:
            requested_workspace_path = path
        normalized_request_path = normalize_phase_workspace_read_path(
            active_responsibility,
            requested_workspace_path,
        )
        normalized_handoff_path = str(normalized_request_path or "").replace("\\", "/").lstrip("./")
        if (
            str(active_responsibility or "").strip().lower()
            in {"author", "author_build"}
            and author_handoff_available
            and not (
                normalized_handoff_path == "generated-skill"
                or normalized_handoff_path.startswith("generated-skill/")
                or normalized_handoff_path == ".skill-builder/skills"
                or normalized_handoff_path.startswith(".skill-builder/skills/")
                or normalized_handoff_path in author_evidence_paths
            )
        ):
            result = {
                "ok": False,
                "error": "author_handoff_source_not_cited",
                "message": (
                    "Author 只能读取 scenario_author_handoff.evidenceRefs 明确引用的材料；"
                    "未引用材料和 validation 投影不能在写包阶段重新解释。"
                ),
                "phase": active_responsibility,
                "path": normalized_request_path,
                "requestedPath": path,
            }
            await _emit(
                emit_event,
                "tool.completed",
                f"已阻止 Author 重复读取交接来源：{path}",
                {"tool": "read_workspace_file", **result},
            )
            return result
        if not phase_workspace_path_allowed(
            active_responsibility,
            normalized_request_path,
            operation="read",
            workspace_root=root,
        ):
            historical_recording_reference = str(normalized_request_path or "").replace("\\", "/").startswith(
                "playwright/recordings/"
            )
            result = {
                "ok": False,
                "error": "phase_path_not_allowed",
                "message": (
                    "录屏摘要中的 playwright/recordings 路径只是历史引用，未作为 inputs 材料提供时"
                    "不能读取。请停止查找原始 trace，直接使用已读取的 web-recording.md 摘要提交 ScenarioContract。"
                    if historical_recording_reference and str(active_responsibility).lower() == "scenario"
                    else "当前阶段不能读取该路径。请只使用 inputs、当前 Scenario 状态和 generated-skill 草稿。"
                ),
                "phase": active_responsibility,
                "path": normalized_request_path,
                "requestedPath": path,
            }
            await _emit(
                emit_event,
                "tool.completed",
                f"已阻止跨阶段读取：{path}",
                {"tool": "read_workspace_file", **result},
            )
            return result
        blocked = repair_read_scope_block(
            str(normalized_request_path or ""),
            operation="read",
        )
        if blocked:
            await _emit(
                emit_event,
                "tool.completed",
                f"已阻止 Repair 读取目标范围外文件：{path}",
                {"tool": "read_workspace_file", **blocked},
            )
            return blocked
        blocked = repair_inspection_block()
        if blocked:
            return {**blocked, "path": normalized_request_path}
        normalized_material_path = str(normalized_request_path or "").replace("\\", "/").lstrip("./")
        active_phase = str(active_responsibility or task_mode or "").strip().lower()
        current_digest = workspace_file_digest(normalized_material_path)
        if repeated_unchanged_read(
            active_phase=active_phase,
            path=normalized_material_path,
            offset=offset,
            digest=current_digest,
        ):
            result = {
                "ok": False,
                "error": "file_already_read_unchanged",
                "path": normalized_material_path,
                "message": (
                    "该文件内容自上次读取后没有变化，已有内容仍在本轮上下文中。"
                    "请停止重复读取并继续写包或提交 finish_authoring；文件写入变化后可以重新读取复核。"
                ),
            }
            await _emit(
                emit_event,
                "tool.completed",
                f"已阻止重复读取未变化文件：{path}",
                {"tool": "read_workspace_file", **result},
            )
            return result
        if (
            normalized_material_path.startswith("inputs/")
            and not material_path_is_model_readable_text(normalized_material_path)
        ):
            parsed_path = str(
                Path(normalized_material_path).with_name(
                    f"{Path(normalized_material_path).stem}_parsed.md"
                )
            ).replace("\\", "/")
            result = {
                "ok": False,
                "error": "binary_material_requires_preprocessing",
                "path": normalized_material_path,
                "parsedPath": parsed_path,
                "message": (
                    "该上传材料是二进制文件，不能通过 UTF-8 文本工具读取。"
                    "请使用平台生成的 *_parsed.md/OCR 副本；若副本不存在，"
                    "只能把原文件记录为未解析证据，不能猜测其中内容。"
                ),
            }
            await _emit(
                emit_event,
                "tool.completed",
                f"已阻止二进制材料进入模型上下文：{path}",
                {"tool": "read_workspace_file", **result},
            )
            return result
        material_read_error = (
            material_followup_read_error(
                normalized_material_path,
                bundle_loaded=state.material_bundle_snapshot is not None,
                followup_paths=state.material_bundle_followup_paths,
                consumed_paths=state.material_followup_reads,
            )
            if active_phase == "scenario"
            else None
        )
        if (
            material_read_error is None
            and active_phase
            in {"author", "author_build", "author_validate", "repair"}
            and normalized_material_path.startswith("inputs/")
            and normalized_material_path in state.material_reads
        ):
            material_read_error = "material_already_read"
        if material_read_error:
            cached_recording = next(
                (
                    item
                    for item in (state.material_bundle_snapshot or {}).get("files") or []
                    if isinstance(item, dict)
                    and str(item.get("path") or "").replace("\\", "/").lstrip("./")
                    == normalized_material_path
                    and isinstance(item.get("recordingDigest"), dict)
                    and item.get("coverageComplete") is True
                ),
                None,
            )
            if (
                material_read_error == "material_bundle_already_contains_file"
                and cached_recording is not None
            ):
                result = {
                    "ok": True,
                    "cached": True,
                    "path": normalized_material_path,
                    "coverageComplete": True,
                    "recordingDigest": cached_recording["recordingDigest"],
                    "message": (
                        "该录屏已由控制器完整扫描；这里返回同一确定性摘要，"
                        "不再暴露 offset/next_offset。请直接提交 ScenarioContract。"
                    ),
                }
                await _emit(
                    emit_event,
                    "tool.completed",
                    f"已返回录屏确定性摘要：{path}",
                    {"tool": "read_workspace_file", **result},
                )
                return result
            result = {
                "ok": False,
                "error": material_read_error,
                "path": normalized_material_path,
                "message": (
                    "Scenario 必须先调用 read_material_bundle 聚合读取全部材料；"
                    "不要在聚合前逐文件遍历 inputs/。"
                    if material_read_error == "material_bundle_required_first"
                    else
                    "该材料已经包含在本轮 read_material_bundle 返回的上下文中。"
                    "只有被标记为 truncated/omitted 的文件允许补读一次；请直接提交 ScenarioContract，"
                    "不要重复读取同一长材料。"
                    if material_read_error == "material_bundle_already_contains_file"
                    else
                    "该材料已在本轮 Author/Repair 中读取过。请停止重复读取，使用材料摘要、"
                    "ScenarioContract 和已有上下文继续写包，完成后调用 finish_authoring 提交结构化自检摘要。"
                ),
            }
            await _emit(
                emit_event,
                "tool.completed",
                f"已阻止重复读取材料：{path}",
                {"tool": "read_workspace_file", **result},
            )
            return result
        await _emit(
            emit_event,
            "tool.started",
            f"读取材料：{normalized_request_path}",
            {
                "tool": "read_workspace_file",
                "path": normalized_request_path,
                "requestedPath": path,
            },
        )
        effective_length = length
        if (
            active_phase in {"scenario", "author", "author_build", "repair"}
            and normalized_material_path in state.material_bundle_followup_paths
        ):
            # A long recording may be inspected once, but never inject an
            # entire recording into the ReAct context. The digest remains the
            # default evidence path; this bounded slice is only for a marked
            # truncated/omitted file.
            effective_length = min(
                SCENARIO_MATERIAL_FOLLOWUP_MAX_CHARS,
                length if length is not None else SCENARIO_MATERIAL_FOLLOWUP_MAX_CHARS,
            )
        result = accessor.read_workspace_file(
            path=normalized_request_path,
            offset=offset,
            length=effective_length,
        )
        if result.get("ok") and active_phase in {
            "author",
            "author_build",
            "author_validate",
            "repair",
        }:
            observed_digest = current_digest or workspace_file_digest(
                normalized_material_path
            )
            if observed_digest is not None:
                state.workspace_file_digests[normalized_material_path] = observed_digest
                state.workspace_file_next_offsets[normalized_material_path] = (
                    int(result.get("next_offset"))
                    if result.get("truncated") is True
                    and result.get("next_offset") is not None
                    else None
                )
        if result.get("ok") and normalized_material_path in state.material_bundle_followup_paths:
            state.material_followup_reads.add(normalized_material_path)
        if (
            result.get("ok")
            and active_phase in {"author", "author_build", "repair"}
            and normalized_material_path.startswith("inputs/")
        ):
            state.material_reads.add(normalized_material_path)
        await _emit(
            emit_event,
            "tool.completed",
            f"已读取材料：{normalized_request_path}",
            {
                "tool": "read_workspace_file",
                "path": normalized_request_path,
                "requestedPath": path,
                "offset": result.get("offset"),
                "length": result.get("length"),
                "next_offset": result.get("next_offset"),
                "truncated": result.get("truncated"),
                "ok": result.get("ok"),
                "size_bytes": result.get("size_bytes"),
            },
        )
        return result

    @tool(
        name=names["read_material_bundle"],
        description=(
            "Read the material digest, recursive inputs index, bounded previews, and deterministic full-flow digests "
            "for web recordings. Use this first during Scenario extraction or Author drafting. Repair uses the "
            "persisted Scenario and findings instead. "
            "A recording with coverageComplete=true must not be read by offset."
        ),
        input_params={"type": "object", "properties": {}},
    )
    async def read_material_bundle_tool() -> dict[str, Any]:
        if task_mode == "repair":
            result = {
                "ok": False,
                "error": "repair_material_read_not_allowed",
                "message": (
                    "Repair 只能使用持久化 ScenarioContract、预检 findings 和当前 generated-skill；"
                    "不得重新读取上传材料。请定位 finding 指向的候选文件并先完成实际修改。"
                ),
            }
            await _emit(
                emit_event,
                "tool.completed",
                "已阻止 Repair 重新加载上传材料。",
                {"tool": "read_material_bundle", **result},
            )
            return result
        if task_mode not in {"scenario", "author", "author_build", "repair"}:
            result = {
                "ok": False,
                "error": "material_read_not_available",
                "message": "当前只读/编辑阶段不加载完整材料包。",
            }
            await _emit(
                emit_event,
                "tool.completed",
                "当前阶段未加载材料包。",
                {"tool": "read_material_bundle", **result},
            )
            return result
        if state.material_bundle_snapshot is not None:
            result = {
                "ok": True,
                "schemaVersion": state.material_bundle_snapshot.get("schemaVersion"),
                "cached": True,
                "message": (
                    "材料包已在本轮完整返回过；为避免重复注入长文本，本次只返回索引。"
                    "请使用已有上下文提交 ScenarioContract。"
                ),
                "files": [
                    {
                        "path": item.get("path"),
                        "sizeBytes": item.get("sizeBytes"),
                        "truncated": item.get("truncated"),
                        "coverageComplete": item.get("coverageComplete"),
                        "recordingDigest": item.get("recordingDigest"),
                    }
                    for item in state.material_bundle_snapshot.get("files") or []
                    if isinstance(item, dict)
                ],
                "omittedReadableFiles": state.material_bundle_snapshot.get("omittedReadableFiles") or [],
            }
            await _emit(
                emit_event,
                "tool.completed",
                "材料包已读取，本次返回缓存索引。",
                {"tool": "read_material_bundle", "ok": True, "cached": True},
            )
            return result
        await _emit(
            emit_event,
            "tool.started",
            "汇总读取上传材料。",
            {"tool": "read_material_bundle"},
        )
        bundle_kwargs: dict[str, Any] = {
            "materials_markdown": materials_markdown,
        }
        if task_mode == "scenario":
            bundle_kwargs.update(
                {
                    "max_file_chars": SCENARIO_MATERIAL_MAX_FILE_CHARS,
                    "max_total_chars": SCENARIO_MATERIAL_MAX_TOTAL_CHARS,
                    "max_digest_chars": SCENARIO_MATERIAL_MAX_DIGEST_CHARS,
                    "max_index_chars": SCENARIO_MATERIAL_MAX_INDEX_CHARS,
                }
            )
        result = read_material_bundle_core(accessor, **bundle_kwargs)
        if result.get("ok"):
            state.material_bundle_snapshot = result
            state.material_bundle_followup_paths = {
                str(item.get("path") or "").replace("\\", "/").lstrip("./")
                for item in result.get("files") or []
                if isinstance(item, dict) and item.get("truncated") is True
            }
            state.material_bundle_followup_paths.update(
                str(item or "").replace("\\", "/").lstrip("./")
                for item in result.get("omittedReadableFiles") or []
                if str(item or "").strip()
            )
        await _emit(
            emit_event,
            "tool.completed",
            "已汇总读取上传材料。" if result.get("ok") else "上传材料汇总读取失败。",
            {
                "tool": "read_material_bundle",
                "ok": result.get("ok"),
                "readable_files": len(result.get("files") or []),
                "truncated": bool(result.get("truncated")),
            },
        )
        return result

    return WorkspaceReadTools(
        list_files=list_workspace_files,
        read_file=read_workspace_file,
        read_material_bundle=read_material_bundle_tool,
    )


__all__ = [
    "MAX_REPAIR_PREWRITE_INSPECTIONS",
    "WorkspaceReadState",
    "WorkspaceReadTools",
    "create_workspace_read_tools",
]
