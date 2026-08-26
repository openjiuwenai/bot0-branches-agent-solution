# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Run one Skill Builder Agent Core phase in an isolated Python process."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import time
from pathlib import Path
from typing import Any

from skill_builder.adapters.jiuwenbox import (
    JiuwenboxWorkspacePort,
    skill_builder_sandbox_enabled,
)
from skill_builder.application.agent_core import (
    SkillBuilderAgentCoreError,
    SkillBuilderAgentCoreResult,
    SkillBuilderAgentLifecycleError,
    SkillBuilderAgentRuntimeUnavailableError,
    run_skill_builder_agent_core,
)
from skill_builder.runtime.serialization import json_safe


PROTOCOL_VERSION = "skill-builder-agent-worker/v1"
DELTA_FLUSH_CHARS = 1000
DELTA_FLUSH_SECONDS = 0.75


def _write_json_atomic(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(f"{path.suffix}.tmp")
    safe_value = json_safe(value, max_text_length=200000)
    temporary.write_text(
        json.dumps(safe_value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, path)


def _result_payload(result: SkillBuilderAgentCoreResult) -> dict[str, Any]:
    return {
        "raw_output_text": result.raw_output_text,
        "session_id": result.session_id,
        "files_read": list(result.files_read),
        "files_listed": list(result.files_listed),
        "files_written": list(result.files_written),
        "final_response": result.final_response,
        "submission_status": result.submission_status,
    }


class JsonlEventWriter:
    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self._stream = path.open("a", encoding="utf-8", buffering=1)
        self._sequence = 0
        self._delta_buffer: list[str] = []
        self._delta_payload: dict[str, Any] = {}
        self._delta_last_flush = time.monotonic()

    def _append(
        self,
        event_type: str,
        summary: str,
        payload: dict[str, Any] | None,
    ) -> None:
        self._sequence += 1
        value = {
            "schema_version": PROTOCOL_VERSION,
            "sequence": self._sequence,
            "timestamp": time.time(),
            "event_type": event_type,
            "summary": summary,
            "payload": json_safe(payload or {}, max_text_length=200000),
        }
        self._stream.write(json.dumps(value, ensure_ascii=False) + "\n")
        self._stream.flush()

    def flush_delta(self) -> None:
        if not self._delta_buffer:
            return
        content = "".join(self._delta_buffer)
        self._append(
            "assistant.delta",
            "Agent stream output",
            {
                **self._delta_payload,
                "content": content,
                "compacted": True,
                "delta_count": len(self._delta_buffer),
            },
        )
        self._delta_buffer = []
        self._delta_payload = {}
        self._delta_last_flush = time.monotonic()

    async def emit(
        self,
        event_type: str,
        summary: str,
        payload: dict[str, Any] | None = None,
    ) -> None:
        value = payload or {}
        if event_type == "assistant.delta":
            if str(value.get("chunk_type") or "").strip() == "llm_reasoning":
                return
            content = str(value.get("content") or "")
            if not content:
                return
            if not self._delta_buffer:
                self._delta_payload = {
                    key: item for key, item in value.items() if key != "content"
                }
                self._delta_last_flush = time.monotonic()
            self._delta_buffer.append(content)
            if (
                sum(len(part) for part in self._delta_buffer)
                >= DELTA_FLUSH_CHARS
                or time.monotonic() - self._delta_last_flush
                >= DELTA_FLUSH_SECONDS
            ):
                self.flush_delta()
            return
        self.flush_delta()
        self._append(event_type, summary, value)

    def close(self) -> None:
        self.flush_delta()
        self._stream.close()


async def run_worker(
    request_path: Path,
    result_path: Path,
    events_path: Path,
) -> int:
    writer = JsonlEventWriter(events_path)
    try:
        request = json.loads(request_path.read_text(encoding="utf-8"))
        if request.get("schema_version") != PROTOCOL_VERSION:
            raise ValueError("unsupported Agent worker protocol version")
        root = Path(str(request["root"])).resolve()
        workspace = (
            JiuwenboxWorkspacePort()
            if skill_builder_sandbox_enabled()
            else None
        )
        result = await run_skill_builder_agent_core(
            root=root,
            workspace_id=str(request["workspace_id"]),
            skill_name=str(request["skill_name"]),
            display_name=str(request["display_name"]),
            description=str(request["description"]),
            version=str(request["version"]),
            tags=[str(item) for item in request.get("tags") or []],
            user_message=str(request.get("user_message") or ""),
            materials_markdown=str(request.get("materials_markdown") or ""),
            emit_event=writer.emit,
            run_phase=str(request.get("run_phase") or "initial"),
            timeout_seconds=(
                int(request["timeout_seconds"])
                if request.get("timeout_seconds") is not None
                else None
            ),
            workspace=workspace,
        )
        writer.flush_delta()
        _write_json_atomic(
            result_path,
            {
                "schema_version": PROTOCOL_VERSION,
                "ok": True,
                "result": _result_payload(result),
            },
        )
        return 0
    except SkillBuilderAgentRuntimeUnavailableError as exc:
        _write_json_atomic(
            result_path,
            {
                "schema_version": PROTOCOL_VERSION,
                "ok": False,
                "error_type": "runtime_unavailable",
                "error_code": exc.code,
                "error": str(exc),
            },
        )
        return 20
    except SkillBuilderAgentLifecycleError as exc:
        _write_json_atomic(
            result_path,
            {
                "schema_version": PROTOCOL_VERSION,
                "ok": False,
                "error_type": "agent_lifecycle",
                "error_code": exc.code,
                "phase": exc.phase,
                "error": str(exc),
            },
        )
        return 11
    except SkillBuilderAgentCoreError as exc:
        _write_json_atomic(
            result_path,
            {
                "schema_version": PROTOCOL_VERSION,
                "ok": False,
                "error_type": "agent_core",
                "error": str(exc),
            },
        )
        return 10
    except Exception as exc:  # noqa: BLE001 - returned through the worker protocol
        _write_json_atomic(
            result_path,
            {
                "schema_version": PROTOCOL_VERSION,
                "ok": False,
                "error_type": "worker",
                "error": str(exc),
            },
        )
        return 1
    finally:
        writer.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Run one Skill Builder Agent Core phase."
    )
    parser.add_argument("request_path", type=Path)
    parser.add_argument("result_path", type=Path)
    parser.add_argument("events_path", type=Path)
    args = parser.parse_args(argv)
    return asyncio.run(
        run_worker(args.request_path, args.result_path, args.events_path)
    )


if __name__ == "__main__":
    raise SystemExit(main())


__all__ = ["PROTOCOL_VERSION", "main", "run_worker"]
