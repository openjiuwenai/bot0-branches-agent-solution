# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Host-neutral Agent Core subprocess adapter."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import sys
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping

from skill_builder.agent_worker import PROTOCOL_VERSION
from skill_builder.application.agent_core import (
    SkillBuilderAgentCoreError,
    SkillBuilderAgentCoreResult,
    SkillBuilderAgentLifecycleError,
    SkillBuilderAgentRuntimeUnavailableError,
)
from skill_builder.ports.runtime import SkillBuilderAgentRequest


_LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class AgentCoreProcessConfig:
    """Process controls; model and Jiuwenbox settings remain environment based."""

    python_executable: str = sys.executable
    worker_module: str = "skill_builder.agent_worker"
    run_root: Path | None = None
    timeout_seconds: int | None = None
    terminate_grace_seconds: float = 5.0
    max_concurrency: int = 1
    environment: Mapping[str, str] = field(default_factory=dict)


def _write_json_atomic(path: Path, value: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(f"{path.suffix}.tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, path)


class SubprocessAgentRunner:
    """Execute each Scenario/Author/Repair Agent phase in a child process."""

    def __init__(self, config: AgentCoreProcessConfig | None = None) -> None:
        self.config = config or AgentCoreProcessConfig()
        self._semaphore = asyncio.Semaphore(
            max(1, int(self.config.max_concurrency))
        )

    async def run(
        self,
        request: SkillBuilderAgentRequest,
    ) -> SkillBuilderAgentCoreResult:
        if request.workspace is not None:
            raise ValueError(
                "SubprocessAgentRunner creates its workspace adapter inside the "
                "worker; configure Jiuwenbox through SKILL_BUILDER_* variables"
            )
        async with self._semaphore:
            return await self._run_once(request)

    async def _run_once(
        self,
        request: SkillBuilderAgentRequest,
    ) -> SkillBuilderAgentCoreResult:
        run_root = (
            self.config.run_root.resolve()
            if self.config.run_root is not None
            else request.root.resolve()
            / ".skill-builder"
            / "agent-core-workers"
        )
        run_dir = run_root / uuid.uuid4().hex
        request_path = run_dir / "request.json"
        result_path = run_dir / "result.json"
        events_path = run_dir / "events.jsonl"
        run_dir.mkdir(parents=True, exist_ok=False)
        _write_json_atomic(
            request_path,
            {
                "schema_version": PROTOCOL_VERSION,
                "root": str(request.root.resolve()),
                "workspace_id": request.workspace_id,
                "skill_name": request.skill_name,
                "display_name": request.display_name,
                "description": request.description,
                "version": request.version,
                "tags": list(request.tags),
                "user_message": request.user_message,
                "materials_markdown": request.materials_markdown,
                "run_phase": request.run_phase,
                "timeout_seconds": request.timeout_seconds,
            },
        )

        environment = os.environ.copy()
        environment.update(
            {str(key): str(value) for key, value in self.config.environment.items()}
        )
        process = await asyncio.create_subprocess_exec(
            self.config.python_executable,
            "-m",
            self.config.worker_module,
            str(request_path),
            str(result_path),
            str(events_path),
            cwd=str(request.root.resolve()),
            env=environment,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        event_task = asyncio.create_task(
            self._forward_events(process, events_path, request)
        )
        try:
            communicate = process.communicate()
            if self.config.timeout_seconds is None:
                stdout, stderr = await communicate
            else:
                stdout, stderr = await asyncio.wait_for(
                    communicate,
                    timeout=max(1, int(self.config.timeout_seconds)),
                )
        except (asyncio.CancelledError, asyncio.TimeoutError):
            await self._stop_process(process)
            raise
        finally:
            await event_task

        payload: dict[str, object] = {}
        if result_path.is_file():
            try:
                loaded = json.loads(result_path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    payload = loaded
            except (OSError, ValueError):
                payload = {}
        result = payload.get("result")
        if (
            payload.get("schema_version") == PROTOCOL_VERSION
            and payload.get("ok") is True
            and isinstance(result, dict)
        ):
            return SkillBuilderAgentCoreResult(
                raw_output_text=str(result.get("raw_output_text") or ""),
                session_id=str(result.get("session_id") or ""),
                files_read=[str(item) for item in result.get("files_read") or []],
                files_listed=[
                    str(item) for item in result.get("files_listed") or []
                ],
                files_written=[
                    str(item) for item in result.get("files_written") or []
                ],
                final_response=(
                    dict(result["final_response"])
                    if isinstance(result.get("final_response"), dict)
                    else None
                ),
                submission_status=(
                    dict(result["submission_status"])
                    if isinstance(result.get("submission_status"), dict)
                    else None
                ),
            )

        stderr_text = stderr.decode(errors="replace")[-4000:]
        stdout_text = stdout.decode(errors="replace")[-2000:]
        message = str(
            payload.get("error")
            or stderr_text
            or stdout_text
            or f"Agent worker exited with code {process.returncode}"
        )
        error_type = str(payload.get("error_type") or "worker")
        if error_type == "runtime_unavailable":
            raise SkillBuilderAgentRuntimeUnavailableError(
                message,
                code=str(payload.get("error_code") or "runtime_unavailable"),
            )
        if error_type == "agent_lifecycle":
            raise SkillBuilderAgentLifecycleError(
                message,
                code=str(payload.get("error_code") or "agent_lifecycle_failed"),
                phase=str(payload.get("phase") or request.run_phase),
            )
        raise SkillBuilderAgentCoreError(message)

    async def _forward_events(
        self,
        process: asyncio.subprocess.Process,
        path: Path,
        request: SkillBuilderAgentRequest,
    ) -> None:
        offset = 0
        pending = ""
        while True:
            if path.is_file():
                with path.open("r", encoding="utf-8") as stream:
                    stream.seek(offset)
                    chunk = stream.read()
                    offset = stream.tell()
                if chunk:
                    pending += chunk
                    lines = pending.split("\n")
                    pending = lines.pop()
                    for line in lines:
                        await self._emit_line(line, request)
            if process.returncode is not None:
                if pending.strip():
                    await self._emit_line(pending, request)
                return
            await asyncio.sleep(0.1)

    @staticmethod
    async def _emit_line(
        line: str,
        request: SkillBuilderAgentRequest,
    ) -> None:
        if request.emit_event is None or not line.strip():
            return
        try:
            value = json.loads(line)
            if not isinstance(value, dict):
                return
            payload = value.get("payload")
            await request.emit_event(
                str(value.get("event_type") or "agent.event"),
                str(value.get("summary") or "Agent event"),
                dict(payload) if isinstance(payload, dict) else {},
            )
        except Exception:
            _LOGGER.debug("Failed to forward an Agent worker event line.", exc_info=True)

    async def _stop_process(
        self,
        process: asyncio.subprocess.Process,
    ) -> None:
        if process.returncode is not None:
            return
        process.terminate()
        try:
            await asyncio.wait_for(
                process.wait(),
                timeout=max(0.1, float(self.config.terminate_grace_seconds)),
            )
        except asyncio.TimeoutError:
            process.kill()
            await process.wait()


__all__ = ["AgentCoreProcessConfig", "SubprocessAgentRunner"]
