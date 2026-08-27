# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

from __future__ import annotations

import asyncio
import base64
import json
import hashlib
import logging
import os
import posixpath
import shutil
import tarfile
import tempfile
import weakref
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from skill_builder.adapters.jiuwenbox_client import (
    JiuwenboxSandboxClient,
    JiuwenboxRuntimeError,
    build_jiuwenbox_writable_workspace_policy,
)
from skill_builder.host_support import (
    canonical_workspace_relative_path,
    forbidden_skill_package_path,
    forbidden_skill_package_root,
    is_agent_readable_workspace_path,
    normalize_phase_workspace_read_path,
    phase_workspace_list_entry_allowed,
    phase_workspace_path_allowed,
    split_generated_skill_path,
    validation_output_directories,
)
from skill_builder.ports import ExecutionRequest, ExecutionResult


_LOGGER = logging.getLogger(__name__)


SANDBOX_WORKSPACE_PATH = "/workspace"
MAX_AGENT_FILE_READ_BYTES = 256 * 1024
MAX_AGENT_FILE_WRITE_BYTES = 1024 * 1024
WRITE_ROOTS = {"generated-skill", "validation", "workspace"}
SYNC_BACK_ROOTS = ("generated-skill", "validation", "workspace")
SKIP_ARCHIVE_NAMES = {".DS_Store", "__pycache__", "app.sqlite"}
SKIP_ARCHIVE_SUFFIXES = (".pyc", ".pyo")
SKIP_ARCHIVE_PREFIXES = ()
DEFAULT_SANDBOX_IO_TIMEOUT_SECONDS = 20
DEFAULT_SANDBOX_WRITE_TIMEOUT_SECONDS = 30


class SkillBuilderSandboxError(RuntimeError):
    """Raised when Skill Builder cannot use its configured sandbox."""


@dataclass(slots=True)
class SkillBuilderSandboxCommandResult:
    exit_code: int
    stdout: str
    stderr: str


class JiuwenboxWorkspacePort:
    """Create Jiuwenbox-backed workspaces for Scenario, Author and Repair."""

    @staticmethod
    def create_accessor(
        *,
        root: Path,
        workspace_id: str,
        purpose: str,
    ) -> "SkillBuilderSandboxSession":
        return SkillBuilderSandboxSession(
            root=root,
            workspace_id=workspace_id,
            purpose=purpose,
        )


class JiuwenboxExecutionPort:
    """Run final Acceptance commands in a short-lived Jiuwenbox sandbox."""

    async def run(self, request: ExecutionRequest) -> ExecutionResult:
        return await asyncio.to_thread(self._run_sync, request)

    @staticmethod
    def _run_sync(request: ExecutionRequest) -> ExecutionResult:
        cwd = Path(request.cwd).resolve()
        workspace_root = cwd
        found_generated_root = False
        while (
            workspace_root.name != "generated-skill"
            and workspace_root.parent != workspace_root
        ):
            workspace_root = workspace_root.parent
        if workspace_root.name == "generated-skill":
            found_generated_root = True
            workspace_root = workspace_root.parent
        if not found_generated_root:
            raise ValueError(
                "SkillBuilderExecutionPort cwd must be inside generated-skill"
            )

        session = SkillBuilderSandboxSession(
            root=workspace_root,
            workspace_id=f"acceptance-{workspace_root.name}",
            purpose="acceptance",
        )
        try:
            command = list(request.command)
            session.seal_generated_skill_sync_back()
            if command and Path(command[0]).name.startswith("python"):
                command[0] = "python3"
            relative_cwd = cwd.relative_to(workspace_root).as_posix()
            workdir = (
                f"{SANDBOX_WORKSPACE_PATH}/{relative_cwd}"
                if relative_cwd != "."
                else SANDBOX_WORKSPACE_PATH
            )
            output_directories = validation_output_directories(
                command,
                workdir=workdir,
                verify_root=f"{SANDBOX_WORKSPACE_PATH}/workspace/verify",
            )
            prepare = session.execute(
                [
                    "python3",
                    "-c",
                    (
                        "import json, pathlib, sys; "
                        "[pathlib.Path(p).mkdir(parents=True, exist_ok=True) "
                        "for p in json.loads(sys.argv[1])]"
                    ),
                    json.dumps(output_directories),
                ],
                timeout_seconds=min(20, max(1, int(request.timeout_seconds))),
                workdir=SANDBOX_WORKSPACE_PATH,
                env={},
            )
            if prepare.exit_code != 0:
                return ExecutionResult(
                    exit_code=prepare.exit_code,
                    stdout=prepare.stdout,
                    stderr=prepare.stderr,
                )

            execution_env = dict(request.env)
            package_python_path = (
                f"{SANDBOX_WORKSPACE_PATH}/generated-skill"
            )
            configured_python_path = str(
                execution_env.get("PYTHONPATH") or ""
            ).strip()
            execution_env["PYTHONPATH"] = (
                f"{package_python_path}:{configured_python_path}"
                if configured_python_path
                else package_python_path
            )
            result = session.execute(
                command,
                timeout_seconds=max(1, int(request.timeout_seconds)),
                workdir=workdir,
                env=execution_env,
            )
            return ExecutionResult(
                exit_code=result.exit_code,
                stdout=result.stdout,
                stderr=result.stderr,
            )
        finally:
            session.close()


def skill_builder_sandbox_enabled() -> bool:
    value = str(os.getenv("SKILL_BUILDER_SANDBOX_ENABLED") or "").strip().lower()
    return value in {"1", "true", "yes", "on"}


def skill_builder_sandbox_base_url() -> str:
    return (
        os.getenv("SKILL_BUILDER_JIUWENBOX_URL")
        or os.getenv("JIUWENBOX_URL")
        or "http://127.0.0.1:8321"
    ).strip()


def skill_builder_sandbox_timeout_seconds() -> float:
    return float(
        os.getenv("SKILL_BUILDER_JIUWENBOX_TIMEOUT_SECONDS")
        or os.getenv("JIUWENBOX_TIMEOUT_SECONDS")
        or 30
    )


def _normalize_rel_path(value: str, *, root: Path | None = None) -> str:
    return canonical_workspace_relative_path(value, workspace_root=root)


def _resolve_confined(root: Path, relative_path: str) -> Path:
    rel = _normalize_rel_path(relative_path, root=root)
    target = (root / rel).resolve()
    root_resolved = root.resolve()
    if target != root_resolved and not target.is_relative_to(root_resolved):
        raise ValueError("path escapes workspace")
    return target


def _host_write_file(root: Path, *, base: str, path: str, content: str) -> dict[str, Any]:
    if base not in WRITE_ROOTS:
        return {"ok": False, "error": "invalid_base"}
    content = str(content or "")
    if len(content.encode("utf-8")) > MAX_AGENT_FILE_WRITE_BYTES:
        return {"ok": False, "error": "file_too_large", "max_bytes": MAX_AGENT_FILE_WRITE_BYTES}
    try:
        rel = _normalize_rel_path(path, root=root)
        if rel == base or rel.startswith(f"{base}/"):
            rel = _normalize_rel_path(rel[len(base):].lstrip("/"))
        if base == "generated-skill":
            forbidden_path = forbidden_skill_package_path(rel)
            if forbidden_path:
                return {
                    "ok": False,
                    "error": "wrong_skill_path_root",
                    "message": f"`{forbidden_path}` is a platform workspace path, not a generated Skill package path.",
                }
        if rel == "." or rel.endswith("/"):
            return {"ok": False, "error": "invalid_path", "message": "Target path must be a file path."}
        target = _resolve_confined(root / base, rel)
    except ValueError as exc:
        return {"ok": False, "error": "invalid_path", "message": str(exc)}
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    return {
        "ok": True,
        "path": f"{base}/{target.relative_to(root / base).as_posix()}",
        "size_bytes": len(content.encode("utf-8")),
    }


def _host_write_bytes(root: Path, *, path: str, content: bytes) -> dict[str, Any]:
    data = bytes(content)
    if len(data) > MAX_AGENT_FILE_WRITE_BYTES:
        return {
            "ok": False,
            "error": "file_too_large",
            "max_bytes": MAX_AGENT_FILE_WRITE_BYTES,
        }
    try:
        rel = _normalize_rel_path(path, root=root)
        if rel.startswith("generated-skill/"):
            rel = _normalize_rel_path(rel.removeprefix("generated-skill/"))
        forbidden_path = forbidden_skill_package_path(rel)
        if forbidden_path:
            return {
                "ok": False,
                "error": "wrong_skill_path_root",
                "message": f"`{forbidden_path}` is a platform workspace path.",
            }
        if rel == "." or rel.endswith("/"):
            return {"ok": False, "error": "invalid_path"}
        target = _resolve_confined(root / "generated-skill", rel)
    except ValueError as exc:
        return {"ok": False, "error": "invalid_path", "message": str(exc)}
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return {
        "ok": True,
        "path": f"generated-skill/{rel}",
        "size_bytes": len(data),
    }


def _archive_member_allowed(root: Path, path: Path) -> bool:
    try:
        rel = path.relative_to(root).as_posix()
    except ValueError:
        return False
    parts = rel.split("/")
    if any(part in SKIP_ARCHIVE_NAMES for part in parts):
        return False
    if rel.startswith(SKIP_ARCHIVE_PREFIXES):
        return False
    if rel == ".skill-builder" or (
        rel.startswith(".skill-builder/") and not is_agent_readable_workspace_path(rel)
    ):
        return False
    if path.name.endswith(SKIP_ARCHIVE_SUFFIXES):
        return False
    return True


class SkillBuilderSandboxSession:
    """A jiuwenbox-backed Skill Builder workspace.

    Agent-core and the LLM stay in the backend process. File reads/writes and
    verification commands happen inside jiuwenbox, with generated artifacts
    copied back through explicit workspace-relative paths.
    """

    def __init__(self, *, root: Path, workspace_id: str, purpose: str):
        self.root = root.resolve()
        self.workspace_id = workspace_id
        self.purpose = purpose
        self.files_read: list[str] = []
        self.files_listed: list[str] = []
        self.files_written: list[str] = []
        self.generated_skill_sync_back_enabled = True
        self.validation_sync_back_enabled = True
        self.client = JiuwenboxSandboxClient(
            base_url=skill_builder_sandbox_base_url(),
            timeout_seconds=skill_builder_sandbox_timeout_seconds(),
        )
        self.sandbox_id: str | None = None
        self.closed = False
        self._sandbox_id_ref: dict[str, str | None] = {"value": None}
        self._finalizer = weakref.finalize(self, type(self)._finalize, self.client, self._sandbox_id_ref)
        try:
            self._create()
            self._upload_workspace()
        except SkillBuilderSandboxError:
            self.close()
            raise
        except Exception as exc:
            self.close()
            raise SkillBuilderSandboxError(f"初始化 Skill Builder 沙箱失败：{exc}") from exc

    @staticmethod
    def _finalize(client: JiuwenboxSandboxClient, sandbox_id_ref: dict[str, str | None]) -> None:
        sandbox_id = sandbox_id_ref.get("value")
        if sandbox_id:
            try:
                client.delete_sandbox(sandbox_id)
            except Exception:
                _LOGGER.debug("Failed to delete finalized Jiuwenbox sandbox %s.", sandbox_id, exc_info=True)
        try:
            client.close()
        except Exception:
            _LOGGER.debug("Failed to close finalized Jiuwenbox client.", exc_info=True)

    @property
    def sandbox_ref(self) -> dict[str, Any]:
        return {
            "provider": "jiuwenbox",
            "sandbox_id": self.sandbox_id,
            "purpose": self.purpose,
            "workspace_path": SANDBOX_WORKSPACE_PATH,
            "base_url": skill_builder_sandbox_base_url(),
        }

    def _create(self) -> None:
        policy = build_jiuwenbox_writable_workspace_policy(SANDBOX_WORKSPACE_PATH)
        try:
            sandbox = self.client.create_sandbox(
                env={
                    "SKILL_BUILDER_WORKSPACE_ID": self.workspace_id,
                    "SKILL_BUILDER_SANDBOX_PURPOSE": self.purpose,
                },
                policy=policy,
                policy_mode="append",
            )
        except JiuwenboxRuntimeError as exc:
            raise SkillBuilderSandboxError(f"创建 Skill Builder 沙箱失败：{exc}") from exc
        self.sandbox_id = sandbox.sandbox_id
        self._sandbox_id_ref["value"] = sandbox.sandbox_id

    def _exec(
        self,
        command: list[str],
        *,
        stdin: str | None = None,
        timeout_seconds: int | None = None,
        workdir: str | None = None,
        env: dict[str, str] | None = None,
    ) -> SkillBuilderSandboxCommandResult:
        if not self.sandbox_id:
            raise SkillBuilderSandboxError("Skill Builder sandbox is not initialized")
        try:
            result = self.client.exec_command(
                self.sandbox_id,
                command,
                workdir=workdir or SANDBOX_WORKSPACE_PATH,
                env=env,
                stdin=stdin,
                timeout_seconds=timeout_seconds
                or int(os.getenv("SKILL_BUILDER_SANDBOX_COMMAND_TIMEOUT_SECONDS") or 120),
            )
        except JiuwenboxRuntimeError as exc:
            raise SkillBuilderSandboxError(f"Skill Builder 沙箱命令执行失败：{exc}") from exc
        return SkillBuilderSandboxCommandResult(exit_code=result.exit_code, stdout=result.stdout, stderr=result.stderr)

    def execute(
        self,
        command: list[str],
        *,
        timeout_seconds: int,
        workdir: str = SANDBOX_WORKSPACE_PATH,
        env: dict[str, str] | None = None,
    ) -> SkillBuilderSandboxCommandResult:
        """Run one host-planned command without exposing sandbox internals to Core."""

        return self._exec(
            command,
            timeout_seconds=timeout_seconds,
            workdir=workdir,
            env=env,
        )

    def _exec_json(
        self, command: list[str], *, stdin: str | None = None, timeout_seconds: int | None = None
    ) -> dict[str, Any]:
        result = self._exec(command, stdin=stdin, timeout_seconds=timeout_seconds)
        if result.exit_code != 0:
            return {
                "ok": False,
                "error": "sandbox_command_failed",
                "stderr": result.stderr[-2000:],
                "stdout": result.stdout[-2000:],
            }
        try:
            data = json.loads(result.stdout or "{}")
        except json.JSONDecodeError:
            return {"ok": False, "error": "invalid_sandbox_json", "stdout": result.stdout[-2000:]}
        return data if isinstance(data, dict) else {"ok": False, "error": "invalid_sandbox_json_shape"}

    def _upload_workspace(self) -> None:
        with tempfile.NamedTemporaryFile(prefix="skill-builder-workspace-", suffix=".tar.gz") as tmp:
            with tarfile.open(tmp.name, "w:gz") as tf:
                for path in sorted(self.root.rglob("*")):
                    if not _archive_member_allowed(self.root, path):
                        continue
                    if path.is_symlink():
                        continue
                    try:
                        rel = path.relative_to(self.root).as_posix()
                        tf.add(path, arcname=rel, recursive=False)
                    except FileNotFoundError:
                        continue
            tmp.flush()
            archive_path = f"{SANDBOX_WORKSPACE_PATH}/.skill-builder-workspace.tar.gz"
            if not self.sandbox_id:
                raise SkillBuilderSandboxError("Skill Builder sandbox is not initialized")
            self.client.upload_file(self.sandbox_id, Path(tmp.name), archive_path)
            extract_code = (
                "import pathlib, tarfile\n"
                f"root=pathlib.Path({SANDBOX_WORKSPACE_PATH!r}).resolve()\n"
                "root.mkdir(parents=True, exist_ok=True)\n"
                f"archive=pathlib.Path({archive_path!r})\n"
                "with tarfile.open(archive, 'r:gz') as tf:\n"
                "    for member in tf.getmembers():\n"
                "        target=(root / member.name).resolve()\n"
                "        if target != root and root not in target.parents:\n"
                "            continue\n"
                "        if member.issym() or member.islnk():\n"
                "            continue\n"
                "        tf.extract(member, root)\n"
            )
            result = self._exec(["python3", "-c", extract_code], timeout_seconds=120)
            if result.exit_code != 0:
                raise SkillBuilderSandboxError(
                    f"初始化 Skill Builder 沙箱工作区失败：{result.stderr[-1000:] or result.stdout[-1000:]}"
                )

    def list_workspace_files(
        self,
        *,
        path: str = "inputs",
        recursive: bool = False,
        max_depth: int | None = None,
        _platform_internal: bool = False,
    ) -> dict[str, Any]:
        try:
            rel = _normalize_rel_path(path, root=self.root)
            if rel == ".":
                rel = "inputs"
            if not _platform_internal and not phase_workspace_path_allowed(
                getattr(self, "purpose", ""),
                rel,
                operation="list",
                workspace_root=self.root,
            ):
                return {
                    "ok": False,
                    "error": (
                        "phase_path_not_allowed"
                        if getattr(self, "purpose", "")
                        else "path_not_allowed"
                    ),
                    "message": (
                        "The current Skill Builder phase cannot list this workspace path."
                        if getattr(self, "purpose", "")
                        else "Only workspace-local files may be listed."
                    ),
                    "phase": getattr(self, "purpose", ""),
                    "path": rel,
                }
        except ValueError as exc:
            return {"ok": False, "error": "invalid_path", "message": str(exc)}
        if not _platform_internal:
            self.files_listed.append(rel)
        code = (
            "import json, pathlib, sys\n"
            f"root=pathlib.Path({SANDBOX_WORKSPACE_PATH!r}).resolve()\n"
            "rel=sys.argv[1]\n"
            "recursive=sys.argv[2]=='1'\n"
            "max_depth=int(sys.argv[3]) if sys.argv[3] else None\n"
            "target=(root / rel).resolve()\n"
            "def entry(p):\n"
            "    s=p.stat()\n"
            "    return {'path': p.relative_to(root).as_posix(), "
            "'type': 'directory' if p.is_dir() else 'file', "
            "'size_bytes': 0 if p.is_dir() else s.st_size}\n"
            "if target != root and root not in target.parents:\n"
            "    print(json.dumps({'ok': False, 'error': 'invalid_path'})); raise SystemExit(0)\n"
            "if not target.exists():\n"
            "    print(json.dumps({'ok': True, 'path': rel, 'exists': False, 'entries': []}, "
            "ensure_ascii=False)); raise SystemExit(0)\n"
            "if target.is_file():\n"
            "    print(json.dumps({'ok': True, 'path': rel, 'exists': True, 'entries': [entry(target)]}, "
            "ensure_ascii=False)); raise SystemExit(0)\n"
            "base_depth=len(target.relative_to(root).parts)\n"
            "entries=[]\n"
            "items=target.rglob('*') if recursive else target.iterdir()\n"
            "for item in sorted(items, key=lambda p: p.relative_to(root).as_posix().lower()):\n"
            "    if item.name in {'.DS_Store', '__pycache__'}:\n"
            "        continue\n"
            "    depth=len(item.relative_to(root).parts)-base_depth\n"
            "    if max_depth is not None and depth > max(0, max_depth):\n"
            "        continue\n"
            "    entries.append(entry(item))\n"
            "    if len(entries) >= 500:\n"
            "        print(json.dumps({'ok': True, 'path': rel, 'exists': True, 'truncated': True, "
            "'entries': entries}, ensure_ascii=False)); raise SystemExit(0)\n"
            "print(json.dumps({'ok': True, 'path': rel, 'exists': True, 'entries': entries}, ensure_ascii=False))\n"
        )
        result = self._exec_json(
            ["python3", "-c", code, rel, "1" if recursive else "0", str(max_depth) if max_depth is not None else ""],
            timeout_seconds=int(
                os.getenv("SKILL_BUILDER_SANDBOX_IO_TIMEOUT_SECONDS") or DEFAULT_SANDBOX_IO_TIMEOUT_SECONDS
            ),
        )
        if (
            not _platform_internal
            and result.get("ok")
            and isinstance(result.get("entries"), list)
        ):
            result["entries"] = [
                item
                for item in result["entries"]
                if isinstance(item, dict)
                and phase_workspace_list_entry_allowed(
                    getattr(self, "purpose", ""),
                    str(item.get("path") or ""),
                    is_dir=item.get("type") == "directory",
                    workspace_root=self.root,
                )
            ]
        return result

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
                normalize_phase_workspace_read_path(
                    getattr(self, "purpose", ""),
                    requested_rel,
                ),
                root=self.root,
            )
            if not phase_workspace_path_allowed(
                getattr(self, "purpose", ""),
                rel,
                operation="read",
                workspace_root=self.root,
            ):
                return {
                    "ok": False,
                    "error": (
                        "phase_path_not_allowed"
                        if getattr(self, "purpose", "")
                        else "path_not_allowed"
                    ),
                    "message": (
                        "The current Skill Builder phase cannot read this workspace path."
                        if getattr(self, "purpose", "")
                        else "Only workspace-local files may be read."
                    ),
                    "phase": getattr(self, "purpose", ""),
                    "path": rel,
                }
        except ValueError as exc:
            return {"ok": False, "error": "invalid_path", "message": str(exc)}
        self.files_read.append(rel)
        code = (
            "import json, pathlib, sys\n"
            f"root=pathlib.Path({SANDBOX_WORKSPACE_PATH!r}).resolve()\n"
            "rel=sys.argv[1]\n"
            "offset=int(sys.argv[2])\n"
            "requested_length=int(sys.argv[3]) if sys.argv[3] else None\n"
            "target=(root / rel).resolve()\n"
            "if target != root and root not in target.parents:\n"
            "    print(json.dumps({'ok': False, 'error': 'invalid_path'})); raise SystemExit(0)\n"
            "if not target.is_file():\n"
            "    print(json.dumps({'ok': False, 'error': 'not_found', 'path': rel}, "
            "ensure_ascii=False)); raise SystemExit(0)\n"
            f"max_chars={MAX_AGENT_FILE_READ_BYTES}\n"
            "length=max_chars if requested_length is None else min(max_chars, max(1, requested_length))\n"
            "offset=max(0, offset)\n"
            "full_text=target.read_text(encoding='utf-8', errors='replace')\n"
            "content=full_text[offset:offset + length]\n"
            "next_offset=offset + len(content)\n"
            "truncated=next_offset < len(full_text)\n"
            "size=target.stat().st_size\n"
            "print(json.dumps({'ok': True, 'path': rel, 'size_bytes': size, 'size_chars': len(full_text), "
            "'offset': offset, 'length': len(content), 'next_offset': next_offset if truncated else None, "
            "'truncated': truncated, 'content': content}, ensure_ascii=False))\n"
        )
        try:
            normalized_offset = max(0, int(offset))
            normalized_length = (
                None
                if length is None
                else min(MAX_AGENT_FILE_READ_BYTES, max(1, int(length)))
            )
        except (TypeError, ValueError):
            return {
                "ok": False,
                "error": "invalid_read_range",
                "message": "offset must be a non-negative integer and length must be a positive integer.",
            }
        return self._exec_json(
            [
                "python3",
                "-c",
                code,
                rel,
                str(normalized_offset),
                "" if normalized_length is None else str(normalized_length),
            ],
            timeout_seconds=int(
                os.getenv("SKILL_BUILDER_SANDBOX_IO_TIMEOUT_SECONDS") or DEFAULT_SANDBOX_IO_TIMEOUT_SECONDS
            ),
        )

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
            forbidden_path = forbidden_skill_package_path(rel)
            if forbidden_path:
                return {
                    "ok": False,
                    "error": "wrong_skill_path_root",
                    "message": f"`{forbidden_path}` is a platform workspace path.",
                }
            if rel == "." or rel.endswith("/"):
                return {"ok": False, "error": "invalid_path"}
        except ValueError as exc:
            return {"ok": False, "error": "invalid_path", "message": str(exc)}
        sandbox_rel = f"generated-skill/{rel}"
        encoded = base64.b64encode(data).decode("ascii")
        code = (
            "import base64, json, pathlib, sys\n"
            f"root=pathlib.Path({SANDBOX_WORKSPACE_PATH!r}).resolve()\n"
            "rel=sys.argv[1]\n"
            "target=(root / rel).resolve()\n"
            "if target != root and root not in target.parents:\n"
            "    print(json.dumps({'ok': False, 'error': 'invalid_path'})); raise SystemExit(0)\n"
            "data=base64.b64decode(sys.stdin.read().encode('ascii'), validate=True)\n"
            "target.parent.mkdir(parents=True, exist_ok=True)\n"
            "target.write_bytes(data)\n"
            "print(json.dumps({'ok': True, 'path': rel, 'size_bytes': len(data)}))\n"
        )
        result = self._exec_json(
            ["python3", "-c", code, sandbox_rel],
            stdin=encoded,
            timeout_seconds=int(
                os.getenv("SKILL_BUILDER_SANDBOX_WRITE_TIMEOUT_SECONDS")
                or DEFAULT_SANDBOX_WRITE_TIMEOUT_SECONDS
            ),
        )
        if result.get("ok"):
            host_result = _host_write_bytes(self.root, path=rel, content=data)
            if not host_result.get("ok"):
                return host_result
            self.files_written.append(str(result.get("path") or host_result["path"]))
            result["path"] = host_result["path"]
        return result

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
                        "delete_skill_file only manages generated Skill package files; "
                        f"`{forbidden_root}/` is a platform workspace root."
                    ),
                }
            if rel == "." or rel.endswith("/"):
                return {"ok": False, "error": "invalid_path", "message": "Target path must be a file path."}
        except ValueError as exc:
            return {"ok": False, "error": "invalid_path", "message": str(exc)}
        sandbox_rel = f"generated-skill/{rel}"
        code = (
            "import json, pathlib, sys\n"
            f"root=pathlib.Path({SANDBOX_WORKSPACE_PATH!r}).resolve()\n"
            "rel=sys.argv[1]\n"
            "target=(root / rel).resolve()\n"
            "if target != root and root not in target.parents:\n"
            "    print(json.dumps({'ok': False, 'error': 'invalid_path'})); raise SystemExit(0)\n"
            "deleted=target.is_file()\n"
            "if deleted:\n"
            "    target.unlink()\n"
            "    parent=target.parent\n"
            "    stop=(root / 'generated-skill').resolve()\n"
            "    while parent != stop:\n"
            "        try: parent.rmdir()\n"
            "        except OSError: break\n"
            "        parent=parent.parent\n"
            "print(json.dumps({'ok': True, 'deleted': deleted, 'path': rel}, ensure_ascii=False))\n"
        )
        result = self._exec_json(
            ["python3", "-c", code, sandbox_rel],
            timeout_seconds=int(
                os.getenv("SKILL_BUILDER_SANDBOX_WRITE_TIMEOUT_SECONDS") or DEFAULT_SANDBOX_WRITE_TIMEOUT_SECONDS
            ),
        )
        if result.get("ok"):
            try:
                host_target = _resolve_confined(self.root / "generated-skill", rel)
                if host_target.is_file():
                    host_target.unlink()
                parent = host_target.parent
                generated_root = (self.root / "generated-skill").resolve()
                while parent != generated_root:
                    try:
                        parent.rmdir()
                    except OSError:
                        break
                    parent = parent.parent
            except (OSError, ValueError) as exc:
                return {"ok": False, "error": "host_delete_failed", "message": str(exc)}
            exported_rel = f"generated-skill/{rel}"
            self.files_written.append(exported_rel)
            result["path"] = exported_rel
        return result

    def write_validation_file(self, *, path: str, content: str) -> dict[str, Any]:
        return self._write_file(base="validation", path=path, content=content)

    def _write_file(self, *, base: str, path: str, content: str) -> dict[str, Any]:
        try:
            rel = _normalize_rel_path(path, root=self.root)
            if rel == base or rel.startswith(f"{base}/"):
                rel = _normalize_rel_path(rel[len(base):].lstrip("/"))
            if base == "generated-skill":
                forbidden_path = forbidden_skill_package_path(rel)
                if forbidden_path:
                    return {
                        "ok": False,
                        "error": "wrong_skill_path_root",
                        "message": (
                            "write_skill_file only writes exported Skill package content; "
                            f"`{forbidden_path}` is owned by the platform workspace."
                        ),
                    }
            if rel == "." or rel.endswith("/"):
                return {"ok": False, "error": "invalid_path", "message": "Target path must be a file path."}
        except ValueError as exc:
            return {"ok": False, "error": "invalid_path", "message": str(exc)}
        content = str(content or "")
        if len(content.encode("utf-8")) > MAX_AGENT_FILE_WRITE_BYTES:
            return {"ok": False, "error": "file_too_large", "max_bytes": MAX_AGENT_FILE_WRITE_BYTES}
        sandbox_rel = f"{base}/{rel}"
        code = (
            "import json, pathlib, sys\n"
            f"root=pathlib.Path({SANDBOX_WORKSPACE_PATH!r}).resolve()\n"
            "rel=sys.argv[1]\n"
            "target=(root / rel).resolve()\n"
            "if target != root and root not in target.parents:\n"
            "    print(json.dumps({'ok': False, 'error': 'invalid_path'})); raise SystemExit(0)\n"
            "target.parent.mkdir(parents=True, exist_ok=True)\n"
            "content=sys.stdin.read()\n"
            "target.write_text(content, encoding='utf-8')\n"
            "print(json.dumps({'ok': True, 'path': rel, 'size_bytes': len(content.encode('utf-8'))}, "
            "ensure_ascii=False))\n"
        )
        result = self._exec_json(
            ["python3", "-c", code, sandbox_rel],
            stdin=content,
            timeout_seconds=int(
                os.getenv("SKILL_BUILDER_SANDBOX_WRITE_TIMEOUT_SECONDS") or DEFAULT_SANDBOX_WRITE_TIMEOUT_SECONDS
            ),
        )
        if result.get("ok"):
            host_result = _host_write_file(self.root, base=base, path=rel, content=content)
            if not host_result.get("ok"):
                return host_result
            self.files_written.append(str(result.get("path") or host_result.get("path")))
        return result

    def remove_workspace_paths(self, paths: list[str] | tuple[str, ...]) -> dict[str, Any]:
        """Remove platform-owned validation outputs from host and sandbox.

        Deletions are explicit because sync_back only downloads existing files
        and therefore cannot propagate a host-side unlink into an already
        running sandbox.  Only the two validation output roots are accepted.
        """

        normalized: list[str] = []
        for value in paths:
            raw = str(value or "").strip().replace("\\", "/").lstrip("./")
            if not raw or raw.startswith("/") or ".." in Path(raw).parts:
                return {"ok": False, "error": "invalid_path", "message": f"Invalid cleanup path: {value!r}"}
            if not (raw == "workspace/verify" or raw.startswith("workspace/verify/")):
                return {
                    "ok": False,
                    "error": "cleanup_scope_rejected",
                    "message": f"Cleanup is restricted to workspace/verify: {raw}",
                }
            normalized.append(raw)
        normalized = list(dict.fromkeys(normalized))
        if not normalized:
            return {"ok": True, "removed": []}

        code = (
            "import glob, json, pathlib, shutil, sys\n"
            f"root=pathlib.Path({SANDBOX_WORKSPACE_PATH!r}).resolve()\n"
            "patterns=json.loads(sys.argv[1])\n"
            "removed=[]\n"
            "for pattern in patterns:\n"
            "    matches=glob.glob(str(root / pattern), recursive=True) "
            "if any(ch in pattern for ch in '*?[') else [str(root / pattern)]\n"
            "    for raw in sorted(set(matches), reverse=True):\n"
            "        target=pathlib.Path(raw).resolve()\n"
            "        if target != root and root not in target.parents: continue\n"
            "        if target.is_dir(): shutil.rmtree(target); removed.append(target.relative_to(root).as_posix())\n"
            "        elif target.exists(): target.unlink(); removed.append(target.relative_to(root).as_posix())\n"
            "print(json.dumps({'ok': True, 'removed': sorted(set(removed))}, ensure_ascii=False))\n"
        )
        sandbox_result = self._exec_json(
            ["python3", "-c", code, json.dumps(normalized, ensure_ascii=False)],
            timeout_seconds=int(
                os.getenv("SKILL_BUILDER_SANDBOX_WRITE_TIMEOUT_SECONDS") or DEFAULT_SANDBOX_WRITE_TIMEOUT_SECONDS
            ),
        )
        if not sandbox_result.get("ok"):
            return sandbox_result

        host_removed: list[str] = []
        for pattern in normalized:
            matches = list(self.root.glob(pattern)) if any(char in pattern for char in "*?[") else [self.root / pattern]
            for target in sorted(set(matches), reverse=True):
                try:
                    resolved = target.resolve()
                    if resolved != self.root and not resolved.is_relative_to(self.root):
                        continue
                    if resolved.is_dir():
                        shutil.rmtree(resolved)
                        host_removed.append(resolved.relative_to(self.root).as_posix())
                    elif resolved.exists():
                        resolved.unlink()
                        host_removed.append(resolved.relative_to(self.root).as_posix())
                except OSError:
                    continue
        return {
            "ok": True,
            "removed": sorted(set([*sandbox_result.get("removed", []), *host_removed])),
        }

    def snapshot_generated_skill(self) -> dict[str, dict[str, Any]]:
        """Capture lightweight package hashes before untrusted validation."""

        snapshot: dict[str, dict[str, Any]] = {}
        generated = self.root / "generated-skill"
        if not generated.is_dir():
            return snapshot
        for path in sorted(generated.rglob("*")):
            if (
                not path.is_file()
                or "__pycache__" in path.parts
                or path.suffix.lower() in {".pyc", ".pyo"}
            ):
                continue
            try:
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
                rel = path.relative_to(generated).as_posix()
                snapshot[rel] = {
                    "sha256": digest,
                    "mode": path.stat().st_mode & 0o777,
                }
            except OSError:
                continue
        return snapshot

    def generated_skill_mutations(self, snapshot: dict[str, dict[str, Any]]) -> list[dict[str, str]]:
        """Compare the sandbox package with the pre-command package snapshot."""

        code = (
            "import hashlib, json, pathlib\n"
            f"root=(pathlib.Path({SANDBOX_WORKSPACE_PATH!r}) / 'generated-skill').resolve()\n"
            "state={}\n"
            "if root.is_dir():\n"
            "    for path in sorted(root.rglob('*')):\n"
            "        if not path.is_file() or '__pycache__' in path.parts "
            "or path.suffix.lower() in {'.pyc','.pyo'}: continue\n"
            "        try: state[path.relative_to(root).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()\n"
            "        except OSError: pass\n"
            "print(json.dumps({'ok': True, 'state': state}, ensure_ascii=False))\n"
        )
        result = self._exec_json(
            ["python3", "-c", code],
            timeout_seconds=int(
                os.getenv("SKILL_BUILDER_SANDBOX_IO_TIMEOUT_SECONDS") or DEFAULT_SANDBOX_IO_TIMEOUT_SECONDS
            ),
        )
        if not result.get("ok") or not isinstance(result.get("state"), dict):
            return [{"path": "generated-skill", "change": "inspection_failed"}]
        before = {path: str(item.get("sha256") or "") for path, item in snapshot.items()}
        after = {str(path): str(digest) for path, digest in result["state"].items()}
        return [
            {
                "path": f"generated-skill/{path}",
                "change": "added" if path not in before else "deleted" if path not in after else "modified",
            }
            for path in sorted(set(before) | set(after))
            if before.get(path) != after.get(path)
        ]

    def restore_generated_skill_snapshot(self, snapshot: dict[str, dict[str, Any]]) -> dict[str, Any]:
        """Restore the sandbox package from the still-unchanged host package."""

        generated = self.root / "generated-skill"
        current = self.snapshot_generated_skill()
        expected_hashes = {rel: str(item.get("sha256") or "") for rel, item in snapshot.items()}
        current_hashes = {rel: str(item.get("sha256") or "") for rel, item in current.items()}
        if current_hashes != expected_hashes:
            return {
                "ok": False,
                "error": "host_package_changed_during_validation",
                "message": "The host generated-skill package changed while validation was running.",
            }

        directories = sorted(
            {str(Path(rel).parent.as_posix()) for rel in snapshot if Path(rel).parent.as_posix() != "."}
        )
        reset_code = (
            "import json, pathlib, shutil, sys\n"
            f"root=(pathlib.Path({SANDBOX_WORKSPACE_PATH!r}) / 'generated-skill').resolve()\n"
            "if root.exists(): shutil.rmtree(root)\n"
            "root.mkdir(parents=True, exist_ok=True)\n"
            "for rel in json.loads(sys.argv[1]): (root / rel).mkdir(parents=True, exist_ok=True)\n"
            "print(json.dumps({'ok': True}))\n"
        )
        reset_result = self._exec_json(
            ["python3", "-c", reset_code, json.dumps(directories, ensure_ascii=False)],
            timeout_seconds=int(
                os.getenv("SKILL_BUILDER_SANDBOX_WRITE_TIMEOUT_SECONDS") or DEFAULT_SANDBOX_WRITE_TIMEOUT_SECONDS
            ),
        )
        if not reset_result.get("ok"):
            return reset_result
        try:
            for rel, item in snapshot.items():
                local_path = _resolve_confined(generated, rel)
                self.client.upload_file(
                    self.sandbox_id,
                    local_path,
                    posixpath.join(SANDBOX_WORKSPACE_PATH, "generated-skill", rel),
                )
        except Exception as exc:  # noqa: BLE001 - restoration must fail closed
            return {"ok": False, "error": "sandbox_restore_failed", "message": str(exc)[:1000]}
        mode_map = {rel: int(item.get("mode") or 0o644) for rel, item in snapshot.items()}
        chmod_code = (
            "import json, pathlib, sys\n"
            f"root=(pathlib.Path({SANDBOX_WORKSPACE_PATH!r}) / 'generated-skill').resolve()\n"
            "for rel, mode in json.loads(sys.argv[1]).items():\n"
            "    target=(root / rel).resolve()\n"
            "    if target.is_file() and (target == root or root in target.parents): target.chmod(int(mode))\n"
            "print(json.dumps({'ok': True}))\n"
        )
        chmod_result = self._exec_json(
            ["python3", "-c", chmod_code, json.dumps(mode_map, ensure_ascii=False)],
            timeout_seconds=int(
                os.getenv("SKILL_BUILDER_SANDBOX_WRITE_TIMEOUT_SECONDS") or DEFAULT_SANDBOX_WRITE_TIMEOUT_SECONDS
            ),
        )
        if not chmod_result.get("ok"):
            return chmod_result
        return {"ok": True, "restoredFileCount": len(snapshot)}

    def seal_generated_skill_sync_back(self) -> None:
        """Prevent validation execution from importing package mutations."""

        self.generated_skill_sync_back_enabled = False

    def seal_candidate_sync_back(self) -> None:
        """Freeze the signed candidate before closing the sandbox.

        Explicit Agent writes have already been mirrored to the host.  Once a
        candidate is signed, generic close-time synchronization must not copy
        an older sandbox package over that immutable host snapshot.
        """

        self.generated_skill_sync_back_enabled = False
        self.validation_sync_back_enabled = False

    def sync_back(self, roots: tuple[str, ...] = SYNC_BACK_ROOTS) -> list[str]:
        synced: list[str] = []
        if not self.sandbox_id:
            return synced
        for root_name in roots:
            if root_name == "generated-skill" and not getattr(self, "generated_skill_sync_back_enabled", True):
                continue
            if root_name == "validation" and not getattr(self, "validation_sync_back_enabled", True):
                continue
            listing = self.list_workspace_files(
                path=root_name,
                recursive=True,
                _platform_internal=True,
            )
            if not listing.get("ok"):
                continue
            for entry in listing.get("entries") or []:
                if not isinstance(entry, dict) or entry.get("type") == "directory":
                    continue
                rel = str(entry.get("path") or "").strip()
                if not rel:
                    continue
                try:
                    local_path = _resolve_confined(self.root, rel)
                except ValueError:
                    continue
                try:
                    data = self.client.download_file(self.sandbox_id, posixpath.join(SANDBOX_WORKSPACE_PATH, rel))
                    local_path.parent.mkdir(parents=True, exist_ok=True)
                    local_path.write_bytes(data)
                    synced.append(rel)
                except Exception:
                    _LOGGER.debug("Failed to download Jiuwenbox workspace file %s.", rel, exc_info=True)
        return synced

    def close(self, *, keep: bool | None = None) -> None:
        if self.closed:
            return
        self.closed = True
        keep_sandbox = keep
        if keep_sandbox is None:
            keep_sandbox = str(os.getenv("SKILL_BUILDER_SANDBOX_KEEP") or "").strip().lower() in {
                "1",
                "true",
                "yes",
                "on",
            }
        sandbox_id = self.sandbox_id
        if sandbox_id and not keep_sandbox:
            try:
                self.sync_back()
            except Exception:
                _LOGGER.debug("Failed to sync Jiuwenbox workspace before close.", exc_info=True)
        self.sandbox_id = None
        self._sandbox_id_ref["value"] = None
        if sandbox_id and not keep_sandbox:
            try:
                self.client.delete_sandbox(sandbox_id)
            except Exception:
                _LOGGER.debug("Failed to delete Jiuwenbox sandbox %s during close.", sandbox_id, exc_info=True)
        self.client.close()
        self._finalizer.detach()


def cleanup_skill_builder_workspace_sandboxes(*, workspace_id: str) -> list[str]:
    """Delete jiuwenbox sandboxes belonging to a deleted workspace."""

    normalized_workspace_id = str(workspace_id or "").strip()
    if not normalized_workspace_id or not skill_builder_sandbox_enabled():
        return []
    client = JiuwenboxSandboxClient(
        base_url=skill_builder_sandbox_base_url(),
        timeout_seconds=skill_builder_sandbox_timeout_seconds(),
    )
    deleted: list[str] = []
    try:
        for sandbox in client.list_sandboxes():
            env = sandbox.env or {}
            if str(env.get("SKILL_BUILDER_WORKSPACE_ID") or "") != normalized_workspace_id:
                continue
            try:
                client.delete_sandbox(sandbox.sandbox_id)
                deleted.append(sandbox.sandbox_id)
            except Exception:
                _LOGGER.debug("Failed to delete stale Jiuwenbox sandbox %s.", sandbox.sandbox_id, exc_info=True)
    finally:
        client.close()
    return deleted


def create_skill_builder_workspace_accessor(
    *, root: Path, workspace_id: str, purpose: str
) -> SkillBuilderSandboxSession | None:
    if not skill_builder_sandbox_enabled():
        return None
    return SkillBuilderSandboxSession(root=root, workspace_id=workspace_id, purpose=purpose)
