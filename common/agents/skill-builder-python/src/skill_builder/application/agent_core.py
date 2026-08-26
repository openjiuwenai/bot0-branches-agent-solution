# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import json
import logging
import os
import re
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Awaitable, Callable

from skill_builder.application.agent_policy import phase_scoped_user_message
from skill_builder.application.agent_submission import (
    author_build_submission_status,
    candidate_submission_status,
    scenario_submission_status,
)
from skill_builder.application.artifact_digest import candidate_artifact_signature
from skill_builder.domain.contract_decisions import (
    canonical_workspace_relative_path,
)
from skill_builder.domain.workspace_paths import (
    forbidden_skill_package_path,
    forbidden_skill_package_root,
    normalize_phase_workspace_read_path,
    phase_workspace_list_entry_allowed,
    phase_workspace_path_allowed,
    split_generated_skill_path,
)
from skill_builder.domain.candidate_contract import CANDIDATE_PROGRESS_PATHS
from skill_builder.resources import (
    AGENT_CORE_SKILLS_ROOT,
)
from skill_builder.runtime.serialization import json_safe
from skill_builder.ports import SkillBuilderWorkspacePort
from skill_builder.application.agent_policy import (
    DEFAULT_AGENT_TIMEOUT_SECONDS,
    DEFAULT_AGENT_TOTAL_TIMEOUT_SECONDS,
)
from skill_builder.application.scenario_projection import AUTHOR_HANDOFF_MAX_BYTES

logger = logging.getLogger(__name__)

MAX_AGENT_FILE_READ_BYTES = 256 * 1024
MAX_AGENT_FILE_WRITE_BYTES = 1024 * 1024
DEFAULT_NO_WRITE_CHUNK_LIMIT = 6_000
DEFAULT_NO_WRITE_SECONDS_LIMIT = 60
DEFAULT_NO_CHECKPOINT_CHUNK_LIMIT = 8_000
DEFAULT_NO_CHECKPOINT_SECONDS_LIMIT = 2 * 60
DEFAULT_TOTAL_TIMEOUT_SECONDS = DEFAULT_AGENT_TOTAL_TIMEOUT_SECONDS
DEFAULT_SCENARIO_TIMEOUT_SECONDS = DEFAULT_AGENT_TIMEOUT_SECONDS["scenario"]
DEFAULT_CHAT_TIMEOUT_SECONDS = DEFAULT_AGENT_TIMEOUT_SECONDS["chat"]
DEFAULT_EDIT_TIMEOUT_SECONDS = DEFAULT_AGENT_TIMEOUT_SECONDS["edit"]
DEFAULT_IDLE_TIMEOUT_SECONDS = 4 * 60
DEFAULT_SCRIPT_REJECTION_ESCALATION_THRESHOLD = 2
DEFAULT_MAX_STREAM_CHUNKS = 60_000
PLATFORM_SKELETON_MARKER = "<!-- skill-builder:platform-skeleton -->"
PLATFORM_FALLBACK_MARKERS = (
    "平台在 Agent 未完成写入时生成的保守草稿",
    "平台已生成保守 Skill 草稿",
    "当前为平台恢复草稿",
    "Agent-core 本轮没有完成有效文件写入",
    "Agent-core did not complete executable script generation",
    "deterministic fallback package",
)
DETERMINISTIC_REPAIR_MARKER = "<!-- skill-builder:deterministic-repair-placeholder -->"
KEY_ARTIFACT_PATHS = CANDIDATE_PROGRESS_PATHS
BROWSER_AUTOMATION_EXPORT_PATTERN = re.compile(r"\b(?:playwright|chromium|firefox|webkit|selenium|puppeteer)\b", re.IGNORECASE)
BROWSER_RUNTIME_BOUNDARY_PATTERN = re.compile(
    r"(导出|运行时|runtime).{0,40}(浏览器|自动化|Playwright|Selenium|Puppeteer)|"
    r"(浏览器|自动化|Playwright|Selenium|Puppeteer).{0,40}(运行时|runtime|导出|安装|依赖|权限|登录|验证码|未验证|mock|沙箱)",
    re.IGNORECASE,
)
PYTHON_STDLIB_MODULE_NAMES = set(sys.stdlib_module_names)
_AGENT_CORE_RUN_LOCK: asyncio.Lock | None = None


class SkillBuilderAgentCoreError(RuntimeError):
    """Raised when the openjiuwen agent-core bridge cannot produce a package."""


class SkillBuilderAgentLifecycleError(SkillBuilderAgentCoreError):
    """Structured failure at a logical Scenario/Author lifecycle boundary."""

    def __init__(self, message: str, *, code: str, phase: str):
        super().__init__(message)
        self.code = str(code or "agent_lifecycle_failed")
        self.phase = str(phase or "scenario")


class SkillBuilderAgentRuntimeUnavailableError(SkillBuilderAgentCoreError):
    """Raised when the model/runtime is unavailable and retrying repair cannot help."""

    def __init__(self, message: str, *, code: str | None = None):
        super().__init__(message)
        self.code = str(code or "runtime_unavailable")


@dataclass(slots=True)
class SkillBuilderAgentCoreResult:
    raw_output_text: str
    session_id: str
    files_read: list[str] = field(default_factory=list)
    files_listed: list[str] = field(default_factory=list)
    files_written: list[str] = field(default_factory=list)
    final_response: dict[str, Any] | None = None
    submission_status: dict[str, Any] | None = None


SkillBuilderEventEmitter = Callable[[str, str, dict[str, Any] | None], Awaitable[None]]


def _get_agent_core_run_lock() -> asyncio.Lock:
    global _AGENT_CORE_RUN_LOCK
    if _AGENT_CORE_RUN_LOCK is None:
        _AGENT_CORE_RUN_LOCK = asyncio.Lock()
    return _AGENT_CORE_RUN_LOCK


async def _emit(
    emit_event: SkillBuilderEventEmitter | None,
    event_type: str,
    summary: str,
    payload: dict[str, Any] | None = None,
) -> None:
    if emit_event is None:
        return
    try:
        await emit_event(event_type, summary, payload or {})
    except Exception as exc:  # pragma: no cover - event persistence must not break generation
        logger.warning("Skill Builder event emission failed: %s", exc)


def _positive_int_env(name: str, fallback: int, *, minimum: int = 1, maximum: int | None = None) -> int:
    try:
        value = int(os.getenv(name) or "")
    except (TypeError, ValueError):
        value = fallback
    value = max(minimum, value)
    if maximum is not None:
        value = min(maximum, value)
    return value


def _env_flag(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return str(raw).strip().lower() not in {"0", "false", "no", "off"}


def _agent_register_internal_skills_enabled() -> bool:
    # Native registration requires an unscoped tool named ``read_file``.
    # Skill Builder uses workspace-scoped, phase-aware readers and supports
    # concurrent workspaces, so preload the same internal skill content into
    # the prompt unless an operator explicitly opts into native registration.
    return _env_flag("SKILL_BUILDER_AGENT_REGISTER_INTERNAL_SKILLS", False)


def _agent_native_skill_registration_enabled(
    task_mode: str,
    *,
    interactive_mode: bool,
) -> bool:
    """Allow unscoped native Skill reads only in explicitly enabled helper modes."""

    return bool(
        not interactive_mode
        and str(task_mode or "").strip().lower() in {"chat", "edit"}
        and _agent_register_internal_skills_enabled()
        and _agent_expose_sys_read_file_enabled()
    )


def _agent_preload_internal_skills_enabled(*, register_internal_skills: bool) -> bool:
    return _env_flag("SKILL_BUILDER_AGENT_PRELOAD_INTERNAL_SKILLS", not register_internal_skills)


def _agent_expose_sys_read_file_enabled() -> bool:
    return _env_flag("SKILL_BUILDER_AGENT_EXPOSE_SYS_READ_FILE", False)


def _file_digest(path: Path) -> str | None:
    try:
        if not path.is_file():
            return None
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        return None


def _artifact_digests(root: Path) -> dict[str, str | None]:
    paths = set(KEY_ARTIFACT_PATHS)
    generated = root / "generated-skill"
    for directory in (generated / "scripts", generated / "fixtures"):
        if not directory.is_dir():
            continue
        paths.update(
            path.relative_to(root).as_posix()
            for path in directory.rglob("*")
            if path.is_file()
        )
    return {rel: _file_digest(root / rel) for rel in paths}


def _current_run_artifact_signature(root: Path, accessor: Any) -> tuple[tuple[str, str | None], ...]:
    del accessor  # Candidate identity is the complete package, not this turn's write log.
    return candidate_artifact_signature(root)


def _artifact_changed(root: Path, rel: str, *, initial_digests: dict[str, str | None]) -> bool:
    before = initial_digests.get(rel)
    after = _file_digest(root / rel)
    return after is not None and after != before


def _text_has_platform_placeholder(text: str) -> bool:
    if PLATFORM_SKELETON_MARKER in text:
        return True
    return any(marker in text for marker in PLATFORM_FALLBACK_MARKERS)


def _json_manifest_has_platform_placeholder(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    return value.get("platformSkeleton") is True or value.get("fallbackPackage") is True


def _workspace_has_platform_placeholder(root: Path) -> bool:
    manifest_path = root / "validation" / "artifact_manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8", errors="replace"))
    except Exception:
        manifest = None
    if _json_manifest_has_platform_placeholder(manifest):
        return True
    for rel in (
        "generated-skill/SKILL.md",
        "generated-skill/references/materials.md",
        "generated-skill/references/extraction-summary.md",
        "validation/scenario_summary.md",
    ):
        try:
            text = (root / rel).read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if _text_has_platform_placeholder(text):
            return True
    return False


def _normalized_written_files(accessor: Any) -> set[str]:
    values = getattr(accessor, "files_written", None) or []
    result: set[str] = set()
    for value in values:
        try:
            result.add(_normalize_rel_path(str(value)))
        except ValueError:
            continue
    return result


def _checkpoint_is_substantive(checkpoint_path: Path, *, initial_digest: str | None) -> bool:
    try:
        if not checkpoint_path.is_file():
            return False
        text = checkpoint_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    if _text_has_platform_placeholder(text):
        return False
    current_digest = _file_digest(checkpoint_path)
    return current_digest is not None and (initial_digest is None or current_digest != initial_digest)


def _checkpoint_is_available(root: Path, checkpoint_path: Path) -> bool:
    try:
        if not checkpoint_path.is_file():
            return False
        text = checkpoint_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    return bool(text.strip()) and not _text_has_platform_placeholder(text)


def _has_current_run_checkpoint(accessor: Any, checkpoint_path: Path, *, initial_digest: str | None) -> bool:
    written = _normalized_written_files(accessor)
    if "generated-skill/SKILL.md" in written and _checkpoint_is_available(checkpoint_path.parent.parent, checkpoint_path):
        return True
    return _checkpoint_is_substantive(checkpoint_path, initial_digest=initial_digest)


def _has_checkpoint_available(root: Path, accessor: Any, checkpoint_path: Path, *, initial_digest: str | None) -> bool:
    return _has_current_run_checkpoint(accessor, checkpoint_path, initial_digest=initial_digest) or _checkpoint_is_available(root, checkpoint_path)


def _task_mode_for_run_phase(run_phase: str) -> str:
    normalized = str(run_phase or "").strip().lower()
    modes = {
        "": "author",
        "initial": "author",
        "workflow": "author",
        "scenario": "scenario",
        "author": "author",
        "author_build": "author_build",
        "author_validate": "author_validate",
        "repair": "repair",
        "chat": "chat",
        "edit": "edit",
    }
    try:
        return modes[normalized]
    except KeyError as exc:
        raise ValueError(f"unsupported Skill Builder run phase: {normalized}") from exc


def _checkpoint_relative_path(task_mode: str) -> str:
    if task_mode == "scenario":
        return "validation/scenario_summary.md"
    return "generated-skill/SKILL.md"


def _checkpoint_required_message(task_mode: str, action: str) -> str:
    if task_mode == "scenario":
        return (
            f"{action} 前必须先提交结构化 ScenarioContract。"
            "平台会在同一次提交中生成 ScenarioContract；不要分别写平台投影文件。"
        )
    return (
        f"{action} 前必须先把真实候选直接写入持久化 Draft Workspace。"
        "至少生成带合法 name/description frontmatter 的 generated-skill/SKILL.md；"
        "references/scripts/fixtures/assets 仅在实际需要时生成，agents/openai.yaml 由宿主管理且禁止生成。"
        "完成后调用 finish_authoring；Core 会从实际文件生成 ImplementationPlan、执行完整预检并提交 PackageRevision。"
    )


def _artifact_has_substantive_change(root: Path, rel: str, *, initial_digests: dict[str, str | None]) -> bool:
    if not _artifact_changed(root, rel, initial_digests=initial_digests):
        return False
    path = root / rel
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    return bool(text.strip()) and not _text_has_platform_placeholder(text)


def _has_agent_artifact_progress(
    root: Path,
    accessor: Any,
    *,
    initial_digests: dict[str, str | None],
    task_mode: str,
) -> bool:
    written = _normalized_written_files(accessor)

    if task_mode == "scenario":
        for rel in (
            "validation/scenario_summary.md",
            "validation/scenario_understanding.md",
            "validation/scenario_contract.json",
            "validation/artifact_manifest.json",
        ):
            if rel in written and _artifact_has_substantive_change(root, rel, initial_digests=initial_digests):
                return True
            if _artifact_has_substantive_change(root, rel, initial_digests=initial_digests):
                return True
        return False

    for rel in (
        "generated-skill/SKILL.md",
        "generated-skill/references/materials.md",
        "generated-skill/references/extraction-summary.md",
        "validation/scenario_summary.md",
        "validation/artifact_manifest.json",
    ):
        if rel in written and _artifact_has_substantive_change(root, rel, initial_digests=initial_digests):
            return True
        if _artifact_has_substantive_change(root, rel, initial_digests=initial_digests):
            return True

    meaningful_generated_writes = [
        rel for rel in written
        if rel.startswith("generated-skill/")
        and rel not in {"generated-skill/agents/openai.yaml"}
    ]
    return bool(meaningful_generated_writes)


def _normalize_rel_path(value: str, *, root: Path | None = None) -> str:
    return canonical_workspace_relative_path(value, workspace_root=root)


def _result_error(value: Any) -> str | None:
    if hasattr(value, "is_err") and value.is_err():
        try:
            return str(value.error())
        except Exception:  # pragma: no cover - openjiuwen result compatibility
            return str(value)
    return None


def _resolve_confined(root: Path, relative_path: str) -> Path:
    rel = _normalize_rel_path(relative_path, root=root)
    target = (root / rel).resolve()
    root_resolved = root.resolve()
    if target != root_resolved and not target.is_relative_to(root_resolved):
        raise ValueError("path escapes workspace")
    return target


def _stdlib_conflicting_script_path(relative_path: str) -> dict[str, str] | None:
    parts = Path(relative_path).parts
    if len(parts) < 2 or parts[0] != "scripts":
        return None
    top_level = parts[1]
    module_name = Path(top_level).stem if len(parts) == 2 else top_level
    if module_name == "__init__" or module_name not in PYTHON_STDLIB_MODULE_NAMES:
        return None
    replacement_root = f"{module_name}_cli" if len(parts) == 2 else f"{module_name}_skill"
    replacement = (
        f"scripts/{replacement_root}{Path(top_level).suffix}"
        if len(parts) == 2
        else f"scripts/{replacement_root}/{'/'.join(parts[2:])}"
    )
    return {
        "moduleName": module_name,
        "replacementPath": replacement,
    }


class SkillBuilderWorkspaceAccessor:
    def __init__(self, *, root: Path, purpose: str = ""):
        self.root = root.resolve()
        self.purpose = str(purpose or "")
        self.files_read: list[str] = []
        self.files_listed: list[str] = []
        self.files_written: list[str] = []

    def list_workspace_files(self, *, path: str = "inputs", recursive: bool = False, max_depth: int | None = None) -> dict[str, Any]:
        try:
            rel = _normalize_rel_path(path, root=self.root)
            if rel == ".":
                rel = "inputs"
            if not phase_workspace_path_allowed(
                self.purpose,
                rel,
                operation="list",
                workspace_root=self.root,
            ):
                return {
                    "ok": False,
                    "error": "phase_path_not_allowed" if self.purpose else "path_not_allowed",
                    "message": (
                        "The current Skill Builder phase cannot list this workspace path."
                        if self.purpose
                        else "Only inputs/, generated-skill/, validation/, workspace/, playwright/, "
                        "and .skill-builder/skills/ may be listed."
                    ),
                    "phase": self.purpose,
                    "path": rel,
                }
            target = _resolve_confined(self.root, rel)
        except ValueError as exc:
            return {"ok": False, "error": "invalid_path", "message": str(exc)}
        self.files_listed.append(rel)
        if not target.exists():
            return {"ok": True, "path": rel, "exists": False, "entries": []}
        if target.is_file():
            entries = [self._entry(target)]
            entries = [
                item
                for item in entries
                if phase_workspace_list_entry_allowed(
                    self.purpose,
                    str(item.get("path") or ""),
                    is_dir=item.get("type") == "directory",
                    workspace_root=self.root,
                )
            ]
            return {"ok": True, "path": rel, "exists": True, "entries": entries}

        base_depth = len(target.relative_to(self.root).parts)
        entries: list[dict[str, Any]] = []
        if recursive:
            for item in sorted(target.rglob("*"), key=lambda p: p.relative_to(self.root).as_posix().lower()):
                if item.name in {".DS_Store", "__pycache__"}:
                    continue
                depth = len(item.relative_to(self.root).parts) - base_depth
                if max_depth is not None and depth > max(0, int(max_depth)):
                    continue
                candidate = self._entry(item)
                if phase_workspace_list_entry_allowed(
                    self.purpose,
                    str(candidate.get("path") or ""),
                    is_dir=candidate.get("type") == "directory",
                    workspace_root=self.root,
                ):
                    entries.append(candidate)
                if len(entries) >= 500:
                    return {"ok": True, "path": rel, "exists": True, "truncated": True, "entries": entries}
        else:
            entries = [
                candidate
                for item in sorted(target.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower()))
                if item.name != ".DS_Store"
                for candidate in [self._entry(item)]
                if phase_workspace_list_entry_allowed(
                    self.purpose,
                    str(candidate.get("path") or ""),
                    is_dir=candidate.get("type") == "directory",
                    workspace_root=self.root,
                )
            ]
        return {"ok": True, "path": rel, "exists": True, "entries": entries}

    def read_workspace_file(
        self,
        *,
        path: str,
        offset: int = 0,
        length: int | None = None,
    ) -> dict[str, Any]:
        try:
            requested_rel = _normalize_rel_path(path, root=self.root)
            rel = _normalize_rel_path(
                normalize_phase_workspace_read_path(self.purpose, requested_rel),
                root=self.root,
            )
            if not phase_workspace_path_allowed(
                self.purpose,
                rel,
                operation="read",
                workspace_root=self.root,
            ):
                return {
                    "ok": False,
                    "error": "phase_path_not_allowed" if self.purpose else "path_not_allowed",
                    "message": (
                        "The current Skill Builder phase cannot read this workspace path."
                        if self.purpose
                        else "Only workspace-local files may be read."
                    ),
                    "phase": self.purpose,
                    "path": rel,
                }
            target = _resolve_confined(self.root, rel)
        except ValueError as exc:
            return {"ok": False, "error": "invalid_path", "message": str(exc)}
        if not target.is_file():
            return {"ok": False, "error": "not_found", "path": rel}

        try:
            normalized_offset = max(0, int(offset))
            normalized_length = (
                MAX_AGENT_FILE_READ_BYTES
                if length is None
                else min(MAX_AGENT_FILE_READ_BYTES, max(1, int(length)))
            )
        except (TypeError, ValueError):
            return {
                "ok": False,
                "error": "invalid_read_range",
                "message": "offset 必须是非负整数，length 必须是正整数。",
            }

        size = target.stat().st_size
        full_text = target.read_text(encoding="utf-8", errors="replace")
        text = full_text[normalized_offset : normalized_offset + normalized_length]
        next_offset = normalized_offset + len(text)
        truncated = next_offset < len(full_text)
        self.files_read.append(rel)
        return {
            "ok": True,
            "path": rel,
            "size_bytes": size,
            "size_chars": len(full_text),
            "offset": normalized_offset,
            "length": len(text),
            "next_offset": next_offset if truncated else None,
            "truncated": truncated,
            "content": text,
        }

    def write_skill_file(self, *, path: str, content: str) -> dict[str, Any]:
        return self._write_file(base="generated-skill", path=path, content=content)

    def write_skill_bytes(self, *, path: str, content: bytes) -> dict[str, Any]:
        data = bytes(content)
        if len(data) > MAX_AGENT_FILE_WRITE_BYTES:
            return {
                "ok": False,
                "error": "file_too_large",
                "max_bytes": MAX_AGENT_FILE_WRITE_BYTES,
            }
        try:
            rel = _normalize_rel_path(path, root=self.root)
            if rel.startswith("generated-skill/"):
                rel = _normalize_rel_path(rel.removeprefix("generated-skill/"))
            if forbidden_path := forbidden_skill_package_path(rel):
                return {
                    "ok": False,
                    "error": "wrong_skill_path_root",
                    "message": f"`{forbidden_path}` 属于平台工作区。",
                }
            if rel == "." or rel.endswith("/"):
                return {"ok": False, "error": "invalid_path"}
            target = _resolve_confined(self.root / "generated-skill", rel)
        except ValueError as exc:
            return {"ok": False, "error": "invalid_path", "message": str(exc)}
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        exported_rel = f"generated-skill/{rel}"
        self.files_written.append(exported_rel)
        return {"ok": True, "path": exported_rel, "size_bytes": len(data)}

    def delete_skill_file(self, *, path: str) -> dict[str, Any]:
        try:
            normalized = _normalize_rel_path(path, root=self.root)
            rel, explicit_generated_root = split_generated_skill_path(normalized)
            rel = _normalize_rel_path(rel)
            forbidden_root = forbidden_skill_package_root(rel)
            if forbidden_root and not explicit_generated_root:
                return {
                    "ok": False,
                    "deleted": False,
                    "error": "wrong_skill_path_root",
                    "message": (
                        "delete_skill_file 只能删除导出 Skill 包内的文件；"
                        f"`{forbidden_root}/` 属于平台工作区。workspace/verify/ 等运行输出由验收执行器统一清理，"
                        "不能通过 Skill 文件工具删除。"
                    ),
                }
            if rel == "." or rel.endswith("/"):
                return {"ok": False, "error": "invalid_path", "message": "Target path must be a file path."}
            target = _resolve_confined(self.root / "generated-skill", rel)
        except ValueError as exc:
            return {"ok": False, "error": "invalid_path", "message": str(exc)}
        if not target.is_file():
            return {"ok": True, "deleted": False, "path": f"generated-skill/{rel}"}
        target.unlink()
        parent = target.parent
        generated_root = (self.root / "generated-skill").resolve()
        while parent != generated_root:
            try:
                parent.rmdir()
            except OSError:
                break
            parent = parent.parent
        exported_rel = f"generated-skill/{rel}"
        self.files_written.append(exported_rel)
        return {"ok": True, "deleted": True, "path": exported_rel}

    def write_validation_file(self, *, path: str, content: str) -> dict[str, Any]:
        return self._write_file(base="validation", path=path, content=content)

    def _write_file(self, *, base: str, path: str, content: str) -> dict[str, Any]:
        content = str(content or "")
        if len(content.encode("utf-8")) > MAX_AGENT_FILE_WRITE_BYTES:
            return {"ok": False, "error": "file_too_large", "max_bytes": MAX_AGENT_FILE_WRITE_BYTES}
        try:
            rel = _normalize_rel_path(path, root=self.root)
            if rel.startswith("..") or "/../" in f"/{rel}/":
                return {
                    "ok": False,
                    "error": "path_not_allowed",
                    "message": "Path must stay inside the target root. Package tools cannot write validation/ or workspace/; those paths are controller-owned.",
                }
            if rel == base or rel.startswith(f"{base}/"):
                rel = _normalize_rel_path(rel[len(base):].lstrip("/"))
            if base == "generated-skill":
                forbidden_path = forbidden_skill_package_path(rel)
                if forbidden_path:
                    return {
                        "ok": False,
                        "error": "wrong_skill_path_root",
                        "message": (
                            "write_skill_file 只能写导出 Skill 包内容，例如 SKILL.md、"
                            "references/...、scripts/...、fixtures/... 或 assets/...。"
                            f"`{forbidden_path}` 是平台工作区路径：验证脚本和报告写入 validation/，"
                            "验证命令输出写入 workspace/verify/，浏览器证据写入 playwright/。"
                        ),
                    }
                stdlib_conflict = _stdlib_conflicting_script_path(rel)
                if stdlib_conflict is not None:
                    return {
                        "ok": False,
                        "error": "python_module_name_conflict",
                        "path": rel,
                        **stdlib_conflict,
                        "message": (
                            "Python module name conflicts with the standard library; "
                            "choose a package-specific name."
                        ),
                    }
            if rel == "." or rel.endswith("/"):
                return {"ok": False, "error": "invalid_path", "message": "Target path must be a file path."}
            target = _resolve_confined(self.root / base, rel)
        except ValueError as exc:
            return {"ok": False, "error": "invalid_path", "message": str(exc)}
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        exported_rel = f"{base}/{target.relative_to(self.root / base).as_posix()}"
        self.files_written.append(exported_rel)
        return {"ok": True, "path": exported_rel, "size_bytes": len(content.encode("utf-8"))}

    def _entry(self, path: Path) -> dict[str, Any]:
        rel = path.relative_to(self.root).as_posix()
        stat = path.stat()
        return {
            "path": rel,
            "type": "directory" if path.is_dir() else "file",
            "size_bytes": 0 if path.is_dir() else stat.st_size,
        }

def _build_system_prompt(
    *,
    task_mode: str = "author",
    internal_skill_context: str = "",
) -> str:
    """Return the small phase protocol; detailed craft guidance lives in skills."""

    supported = {
        "chat",
        "edit",
        "scenario",
        "author",
        "author_build",
        "author_validate",
        "repair",
    }
    if task_mode not in supported:
        raise ValueError(f"unsupported Agent task mode: {task_mode}")

    if task_mode == "chat":
        return "\n".join(
            [
                "你是 Skill Builder 工作区的只读助手。",
                "只能读取当前工作区并基于真实文件回答；禁止写文件、运行命令、请求 HITL 或声称已修改产物。",
                "不要启动 Skill 抽取、修复或验收流程。",
                "最终只输出 JSON：",
                '{"status":"ready","summary":"中文回复","files":[],"pending_decisions":[],"blockers":[],"unverified_inputs":[],"unverified_capabilities":[],"suggested_next_message":""}',
            ]
        )

    if task_mode == "edit":
        return "\n".join(
            [
                "你是 Skill Builder 工作区的增量编辑助手。",
                "只修改用户明确要求的 generated-skill/ 文件；保留无关文件和稳定发布 name。",
                "业务逻辑、fixture、依赖或事实口径变更前必须读取直接相关的当前材料；纯 UI 文案除外。",
                "validation/ 是平台状态，禁止直接改写；本轮不执行平台验收。",
                "关键歧义返回 needs_input，不得猜测。",
                "最终只输出 JSON，并准确列出本轮实际修改文件。",
            ]
        )

    common = [
        "你是 Skill Builder 的单阶段 Agent；当前 worker/session 只承担 task_mode 指定的职责。",
        "默认抽取由 Scenario 先完成结构化材料交接，再由 Author 写包；当前 Author 不重新定义已确认的业务事实。"
        "只有显式 run_phase=author 的调用才可能没有 ScenarioContract。",
        "只使用已注册工具和当前工作区；不得访问工作区外路径或假设本地 shell 可用。",
        "inputs/ 是材料，generated-skill/ 是唯一可发布包；validation/、workspace/、playwright/ 和 .skill-builder/ 是平台状态。",
        "不得直接写平台状态文件。",
        "不得创建占位能力或伪造证据。",
        "详细写作和提取规则以当前已加载的内置 skill 为准；工具 schema 是唯一机器协议。",
        "用户可见内容以中文为主；产品/平台品牌名可保留英文，代码标识、路径和 JSON 字段保持原样。",
    ]

    if task_mode == "scenario":
        phase = [
            "# 当前阶段：Scenario",
            "调用一次 read_material_bundle 聚合材料；该工具已为长材料生成摘要，并为录屏生成覆盖完整流程的 recordingDigest。Scenario 不逐文件补读，直接基于 bundle 提交紧凑事实。",
            "只提交有证据的 facts 和真正需要用户选择的 conflicts；facts 至少覆盖 purpose、trigger、input、output、step。每一条 fact 都必须带 evidenceRefs 字符串数组，例如 inputs/source.md、material:<id> 或 platform:<id>；rule/requirement 还必须带不超过 300 字符的 sourceQuote；不生成机器 ID、能力布尔值或验收 DSL。",
            "facts 必须是原生 JSON 数组，每条 fact 是独立对象；不得把其他 fact 的 JSON 拼进某条 value 字符串。只使用内置 Skill 列出的 kind；外部系统信息使用 dependency，不使用 external_system。",
            "材料明确要求 CLI、脚本、命令行入口或离线可执行工具时，必须提交独立的 script_requirement fact 并保留证据；声明 API/browser 等运行机制时还必须提供材料逐句 sourceQuote，否则平台只把机制视为待确认选择，不能编译成硬能力。",
            "保持 ScenarioDraft 紧凑：不要复制整张 CSV、长段落或完整录屏；每个 fact.value 只保留可复用的业务摘要（优先短字符串或小型对象/数组），同一事实不要在多个 fact 中重复，conflicts 只列真正无法由材料确定的选择。",
            "录屏摘要中的 playwright/recordings、截图和 trace 路径只是历史引用。使用 recordingDigest 的 evidenceRef、interactions 和 observedStates 提取事实，直接提交 recording-only ScenarioDraft。",
            "聚合读取完成后不得输出材料分析正文，直接调用 write_scenario_draft 提交完整对象；仅当首次返回 next_action=repair_and_resubmit 时，按 issues 原地修正并最多再提交一次，禁止重新读取材料或第三次提交。",
            "提交成功立即结束；不要写 generated-skill、运行验证或自行请求 HITL。",
        ]
    elif task_mode == "author_build":
        phase = [
            "# 当前阶段：Author Build",
            "这是默认可执行包的生产文件阶段。scenario_author_handoff 和已确认 HITL 是权威业务方向，不得重新解释或扩展能力。",
            "如需实现细节，只调用一次 read_material_bundle 获取受控材料摘要；本阶段不逐文件读取 inputs/、平台 fixture 或尚未生成的包文件。使用 handoff 与 bundle 直接写包。",
            "直接生成实际需要的 SKILL.md、生产脚本、references 和业务 fixtures；Core 会从最终文件、能力契约和 requirements.txt 生成 ImplementationPlan。",
            "先写完整 SKILL.md，再写最少的生产脚本与业务 fixture。控制器生成的 sample-input/invalid 只验证 schema，不能作为 happy path；不要覆盖或删除。",
            "生产脚本必须提供 SKILL.md 中记录的可启动 CLI。外部依赖应延迟到真实执行路径初始化，--help 和参数解析不得因未安装 Playwright/网络依赖而失败。",
            "XLSX 业务 fixture 只能通过 write_tabular_fixture 提交列和行，由控制器生成真实工作簿；禁止用 write_skill_file 写伪 XLSX，也不要生成 fixture 生成器脚本。",
            "Python 写入工具会立即做语法和标准库命名检查；返回失败时旧文件仍保留，只修正该文件后重试。",
            "Build 只生成最小成功样例：结构化入口使用一份业务 happy fixture；外部浏览器/API 响应 fixture 只有在生产入口真实支持本地响应注入时才生成，禁止创建未被代码消费的展示样例。不要生成 invalid/error/empty fixture，平台已负责 schema invalid；受控外部重放和更深边界 case 属于 Validate。",
            "结构化浏览器/API 生产入口必须支持 --validate-only；该模式复用生产输入解析和字段校验，在初始化 Playwright、网络、凭据或外部 SDK 前返回。有效业务 fixture 退出 0，非法输入退出非 0。",
            "多输入关联场景只生成一组成套、可读且 key 可关联的 happy fixtures；不要生成复杂二进制生成器或复制原始业务材料。",
            "SKILL.md 和长生产脚本各用 write_skill_file 单独写入；2 至 4 个独立的小型 HTML/JSON/text fixture 或 reference 使用一次 write_skill_files 批量写入。XLSX 仍只用 write_tabular_fixture。不要反复重写已通过文件。",
            "本阶段禁止生成、修改或运行 self_check。生产包物化完成后调用 finish_authoring；控制器会提交候选并独立执行 CLI 启动与确定性 smoke。",
            "finish_authoring 的 agent_self_check 只报告静态生产包检查；不得声称业务重放或外部能力已经通过。",
        ]
    elif task_mode == "author_validate":
        phase = [
            "# 当前阶段：Author Validate",
            "这是默认可执行包的独立验证计划阶段。生产包已经冻结，本阶段不得修改 SKILL.md、生产脚本、references、fixtures、requirements 或 ImplementationPlan。",
            "用户消息已注入控制器校验过的 ImplementationPlan 和平台 fixture 路径；不要列目录或再次读取计划文件。只读取 SKILL.md、计划内生产脚本、业务成功 fixture 和 invalid fixture；不要读取 sample-input 或控制器生成的 self_check.py，禁止读取 inputs/ 或重新执行 Scenario。",
            "根据真实 CLI 和 fixture 调用 write_self_check_plan。必须包含 happy_path；结构化输入包含 invalid_input；外部入口还包含使用 Author 业务响应 fixture 的成功重放和独立 external_offline 边界。",
            "happy_path 必须调用生产入口并断言非空业务字段、数量或关键文本；不得把 blocked、仅退出码为零或平台 sample-input 当作业务成功。",
            "write_self_check_plan 通过后立即调用 run_offline_self_check。第一次失败时只根据返回的具体重放 finding 修正自检路径、operator 或命令，并最多复验一次；不得读取 self_check.py、改写生产文件或为了通过而弱化业务断言。",
            "离线自检为 pass/warn 后调用 finish_authoring，准确记录尚未执行的浏览器/API 外部验证。",
        ]
    elif task_mode == "author":
        phase = [
            "# 当前阶段：Author",
            "默认工作流已在用户消息中注入 scenario_author_handoff；它是 Scenario 到 Author 的权威决策交接。直接按其方向写包；实现页面解析、字段映射或复杂规则时，可用 read_workspace_file 各读取一次 evidenceRefs 明确引用的 inputs 文本，但不得遍历 inputs、读取未引用来源或覆盖 HITL 结论。",
            "如果用户消息包含“平台生成的有效 Scenario 决策交接”，其中同一 decisionId 的结果已经覆盖 pendingDecisions；最终 Skill 和正文不得再把该项写成待确认。",
            "原材料读取只用于实现细节查证，不得重新发明与 ScenarioContract 或 HITL 冲突的业务口径。",
            "显式 run_phase=author 且不存在 ScenarioContract 时，才直接基于当前材料完成写包。",
            "长材料（尤其录屏）只能补读一次；同一路径再次读取会被工具阻断，不要改变 offset 重试。已经完整读取且内容未变化的合同或候选文件也不要重复读取；写入导致内容变化后可以复核一次。使用已有材料摘要和上下文继续写包，并尽快完成草稿。",
            "Core 根据最终包文件、Scenario 能力契约和 requirements.txt 生成 ImplementationPlan；Author 不提交或改写 packageKind/scriptsRequired。控制器要求脚本时必须生成非自检生产脚本，不要求脚本时不生成 scripts。",
            "生成最小完整包：必须有合法 SKILL.md；references/scripts/assets 按实际需要生成。agents/openai.yaml 完全由宿主适配层管理，禁止生成或修改。",
            "对于尚无候选文件的新包，收到有效 scenario_author_handoff 后，第一轮写入只调用 write_skill_file 生成一份可直接交付且内容完整的 SKILL.md，禁止占位骨架。",
            "不得让模型编写复杂 xlsx、图片、字体或压缩包生成器、在模型输出中计算二进制载荷，也不要复制原始业务材料。控制器生成的 sample-input/invalid 只验证 schema；不要覆盖或删除。可执行 Skill 必须根据材料另建小型业务 happy fixture，自检的 happy_path/business_rule/file_handoff 不得把平台 schema fixture 当作业务成功证据。",
            "SKILL.md、较长生产脚本和接近输出上限的文件使用 write_skill_file 单独写入；2 至 4 个独立小型 reference 或文本 fixture 可用 write_skill_files 批量提交。XLSX 只用 write_tabular_fixture。大型实现按职责拆分；用 delete_skill_file 删除真实废弃文件。",
            "Author 只负责生成并自检草稿；控制器会在本阶段结束后自动执行完整预检和候选提交。本阶段不内联修复预检失败；可修复 finding 会由 workflow 交给具有独立轮次预算的 Repair 阶段。",
            "面向用户的离线脚本入口统一优先支持 --input PATH，产生机器输出时支持 --output PATH；多步脚本必须让前一步真实输出能作为后一步输入。",
            "Python 脚本或顶层包名不得与标准库模块同名（如 inspect.py、json.py、email/）；使用带业务含义的名称，如 inspect_cli.py、json_report.py，并在 SKILL.md、自检和其他脚本中保持引用一致。",
            "如果 generated-skill 文档公开了 CLI，在生产脚本和 CLI 文档完成后调用 write_self_check_plan，只提交结构化 checks、命令、输出断言和 covers；控制器即时校验并确定性生成唯一的 scripts/self_check.py。禁止用 write_skill_file、replace_skill_file_text 或 delete_skill_file 直接维护任何自检入口，也不要创建旁路自检。公开 CLI 有 happy_path，结构化输入有 invalid_input，只有一个 CLI 的真实输出被另一个 CLI 消费时才需要 file_handoff，外部入口有 external_offline。happy_path 只接受退出码 0，必须用 Author 创建的业务 fixture 产生至少一条有效业务结果并断言关键字段/数量，不能接受 blocked；平台 sample-input/invalid 不得用于业务成功用例。多输入关联、对账或文件交接场景应使用成套且键值可关联的业务 fixtures。缺少运行依赖的 blocked 降级只放在 external_offline。external_offline 优先断言结构化 blocked 输出；入口在依赖初始化前无法产出文件时，断言稳定的 stdout/stderr 业务错误原因，不能只断言退出码。write_self_check_plan 返回 ok=true 后立即运行自检；覆盖 warnings 只作诊断，不要为消除 warning 重写已通过的生产文件或重复提交计划。计划失败只修正具体 issues。",
            "非法记录不得进入 count、金额、评分、推荐或其他业务聚合。一个 ruleId 含多个失败条件时，每个条件都要由实际命令和业务断言覆盖，不能用缺列 case 代替重复值、非法枚举或边界数值；可在 ImplementationPlan 中增加小型业务 fixture，但不得替换平台 schema sample-input/invalid。",
            "材料中的外部系统、审批、浏览器或 API 只是业务依赖，不自动等于本包实现能力。只有 ScenarioContract/已确认决策要求且包内有真实入口时才能声明支持；纯 SOP/知识 Skill 应写为人工或外部系统边界，不得凭空增加浏览器/API 未验证能力。",
            "外部采集入口必须把实际响应解析为 ScenarioContract 声明的关键业务输出；只导航页面、截取整页文本或把正文片段写入 notes，不算结构化输出实现。关键输出字段不得在所有路径上保持空值后以“待查询/待确定”交付；离线自检至少要用受控 fixture 验证解析器能产出非空关键字段。真实 API 端点未由材料/HITL 提供时，必须通过 CLI 参数、环境变量或配置要求用户提供并标记未验证，禁止把 example.com/example.org/example.net 等保留示例域名写成生产默认端点。",
            "HITL 已确认的固定或排他选项必须同时落实到 SKILL.md、生产脚本和自检；不能只在边界说明中写固定值，却继续公开其他选项或从用户输入动态读取同一配置。携带数值的枚举（如 first_50 / 前 50 条）同样属于固定值，不能伪装成带默认值的可配置参数。",
            "写包和自检完成后必须调用 finish_authoring，提交 summary 和 agent_self_check。agent_self_check 记录实际执行结果和未验证能力；没有真实执行证据的脚本或业务结果必须标记为 not_run/partial。implementation_evidence 可选且只作诊断，不影响候选提交。",
            'finish_authoring 示例片段：{"summary":"已完成写包和离线检查","agent_self_check":{"status":"partial","summary":"已完成文档与脚本一致性复核，未执行外部 API。","checks":[{"id":"package_consistency","title":"文档与脚本一致性","status":"pass","message":"字段、参数和输出路径已逐项核对"}],"unverified":["真实外部 API 可达性"]}}',
        ]
    elif task_mode == "repair":
        phase = [
            "# 当前阶段：Repair",
            "这是一次独立、有界的机械修复，不是重新生成。只处理 workflow 交付的单一 rootCauseFamily 和 targetPaths。",
            "禁止读取 inputs/ 或 read_material_bundle；ScenarioContract、已确认决策、结构化 findings 和现有 generated-skill 是本阶段全部事实来源。",
            "先按 targetPaths 有界检查直接相关候选文件，然后必须实际写入或删除 generated-skill 文件；不得从头重写已通过文件。",
            "始终使用 write_skill_file 一次提交一个完整修复文件；不要在一次工具调用中生成多个文件，也不要重写与 finding 无关的文件。",
            "修复较大现有文件中的局部逻辑时优先使用 replace_skill_file_text，提供唯一匹配的 old_text 和最小 new_text；只有结构性重写才使用 write_skill_file 输出完整文件。",
            "若唯一 finding 是缺少业务 fixture，只写 RepairPlan 指定的 fixture；XLSX 使用 write_tabular_fixture，CSV/JSON 使用 write_skill_file，不修改生产脚本或 SKILL.md。",
            "Repair 不得循环尝试生成复杂二进制，不得用文本替代、复制原始业务材料或删除平台生成的静态 fixture。",
            "Repair 必须修改 finding 指向的候选文件。evidence、能力缺失、业务重放和外部环境问题不会进入本阶段；不要用重复读取或重复检查消耗本轮预算。",
            "自检协议、offline replay 和业务输出失败不会自动进入 Repair；若 handoff 中出现这些 finding，立即停止并保留诊断。",
            "Repair 不得为补齐外部能力编造 URL、账号、权限或响应结构；finding 没有提供真实值时保留可配置入口并如实标记 needs_review 边界。",
            "若 finding 为 python_module_name_conflict，按 replacementPath 重命名冲突模块，并同步更新 finding.referencePaths、SKILL.md、自检命令和包内导入；不要只复制出第二份文件。",
            "修改后运行必要自检并调用 finish_authoring；控制器会重新执行完整预检，修复结果不会因 Agent 自述而直接通过。",
        ]
    else:  # pragma: no cover - task modes are checked above
        raise ValueError(f"unsupported Agent task mode: {task_mode}")

    completion = [
        (
            "Scenario 必须由 write_scenario_draft 提交。"
            if task_mode == "scenario"
            else "Author Build 生产包结束后调用 finish_authoring；控制器随后提交候选并执行确定性交付验收。"
            if task_mode == "author_build"
            else "Author Validate 完成离线自检后调用 finish_authoring；随后由控制器自动预检并提交候选。"
            if task_mode == "author_validate"
            else "Author 或 Repair 写包结束后调用 finish_authoring；随后由控制器自动预检并提交候选。"
        ),
        (
            "工具接受提交后立即结束，不继续分析或等待自然语言收尾。"
            if task_mode == "scenario"
            else "finish_authoring 接受后立即结束，不要继续扩展包或输出自然语言总结。"
        ),
    ]
    preload = internal_skill_context.strip()
    parts = [*common, "", *phase, "", *completion]
    if preload:
        parts.extend(["", preload])
    return "\n".join(parts)
def _build_user_prompt(
    *,
    skill_name: str,
    display_name: str,
    description: str,
    version: str,
    tags: list[str],
    user_message: str,
    materials_markdown: str,
    author_handoff: dict[str, Any] | None = None,
    task_mode: str = "author",
) -> str:
    scoped_user_message = phase_scoped_user_message(user_message, task_mode=task_mode)
    payload = {
        "task_mode": task_mode,
        "skill_name": skill_name,
        "skill_name_rule": "If skill_name starts with skill-extract-, treat it as a temporary fallback and infer a meaningful kebab-case publish name from the uploaded materials.",
        "display_name": display_name,
        "description": description,
        "version": version,
        "tags": tags,
        "current_date": time.strftime("%Y-%m-%d", time.localtime()),
        "user_message": scoped_user_message,
        "output_language": (
            "zh-CN for validation/reporting. User-facing titles must contain Chinese text; "
            "product and platform brand names such as Gitee or GitCode may remain in Latin letters. "
            "Keep the kebab-case identifier in frontmatter name, $skill references, script names and code fields; "
            "lower-level SKILL.md section headings may remain conventional English when useful."
        ),
    }
    if task_mode in {"scenario", "author", "author_build"} and not (
        task_mode in {"author", "author_build"}
        and isinstance(author_handoff, dict)
    ):
        payload["uploaded_materials_index"] = materials_markdown
    if task_mode == "chat":
        heading = "请作为只读工作区助手回复这条消息，不要生成或修改 Skill。"
    elif task_mode == "edit":
        heading = "请只对当前已有 Skill 做这条消息明确要求的增量修改，不要重新抽取。"
    elif task_mode == "scenario":
        heading = "请完成场景理解并只提交一次 facts/conflicts；平台负责生成稳定 ID 和 ScenarioContract。"
    elif task_mode == "author_build":
        heading = "请按当前 Scenario 交接生成可执行 Skill 的生产包；本阶段不生成自检。"
    elif task_mode == "author_validate":
        heading = "请只为当前已冻结生产包生成并运行结构化离线自检。"
    elif task_mode == "repair":
        heading = "请按结构化预检 findings 对当前 Skill 草稿执行一次有界修复。"
    else:
        heading = "请直接基于当前材料生成可编辑、可打包的 Skill 草稿。"
    safe_payload = json_safe(payload, max_text_length=12000)
    if task_mode in {"author", "author_build", "author_validate"} and isinstance(
        author_handoff, dict
    ):
        handoff_bytes = len(
            json.dumps(
                author_handoff,
                ensure_ascii=False,
                separators=(",", ":"),
                default=str,
            ).encode("utf-8")
        )
        if handoff_bytes > AUTHOR_HANDOFF_MAX_BYTES:
            raise SkillBuilderAgentLifecycleError(
                "Author 交接超过确定性输入预算，平台已停止生成，未静默截断业务事实。",
                code="author_handoff_too_large",
                phase=task_mode,
            )
        safe_payload["scenario_author_handoff"] = author_handoff
    return heading + "\n\n" + json.dumps(safe_payload, ensure_ascii=False, indent=2)


async def run_skill_builder_agent_core(**kwargs: Any) -> SkillBuilderAgentCoreResult:
    serialize = str(os.getenv("SKILL_BUILDER_AGENT_CORE_SERIALIZE") or "1").strip().lower() not in {"0", "false", "no"}
    if not serialize:
        return await _run_skill_builder_agent_core_locked(**kwargs)
    lock = _get_agent_core_run_lock()
    wait_started: float | None = None
    if lock.locked():
        wait_started = time.time()
        await _emit(
            kwargs.get("emit_event"),
            "agent.queue_waiting",
            "已有 Skill 抽取 Agent 正在运行，当前任务排队等待执行。",
            {"workspace_id": kwargs.get("workspace_id"), "phase": kwargs.get("run_phase") or "initial"},
        )
    async with lock:
        if wait_started is not None:
            await _emit(
                kwargs.get("emit_event"),
                "agent.queue_started",
                "当前任务已获得 Agent 执行资源，开始运行。",
                {
                    "workspace_id": kwargs.get("workspace_id"),
                    "phase": kwargs.get("run_phase") or "initial",
                    "wait_seconds": round(time.time() - wait_started, 2),
                },
            )
        return await _run_skill_builder_agent_core_locked(**kwargs)


async def _run_skill_builder_agent_core_locked(
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
    from skill_builder.adapters.openjiuwen_runtime import run_skill_builder_agent_runtime

    result = await run_skill_builder_agent_runtime(
        root=root,
        workspace_id=workspace_id,
        skill_name=skill_name,
        display_name=display_name,
        description=description,
        version=version,
        tags=tags,
        user_message=user_message,
        materials_markdown=materials_markdown,
        emit_event=emit_event,
        run_phase=run_phase,
        timeout_seconds=timeout_seconds,
        workspace=workspace,
    )
    task_mode = _task_mode_for_run_phase(run_phase)
    if task_mode == "scenario":
        result.submission_status = scenario_submission_status(root, result)
    elif task_mode == "author_build":
        result.submission_status = author_build_submission_status(root, result)
    elif task_mode in {"author", "author_validate", "repair"}:
        result.submission_status = candidate_submission_status(root, result)
    return result
