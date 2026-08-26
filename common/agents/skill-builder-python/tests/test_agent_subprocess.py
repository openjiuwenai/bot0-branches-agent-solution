from __future__ import annotations

import asyncio
import json
import os
import sys
from pathlib import Path

import pytest

from skill_builder import agent_worker
from skill_builder.adapters.subprocess_agent import (
    AgentCoreProcessConfig,
    SubprocessAgentRunner,
)
from skill_builder.application.agent_core import (
    SkillBuilderAgentCoreResult,
    SkillBuilderAgentLifecycleError,
)
from skill_builder.ports import SkillBuilderAgentRequest


PROJECT_ROOT = Path(__file__).resolve().parents[1]
PYTHONPATH = os.pathsep.join((str(PROJECT_ROOT / "src"), str(PROJECT_ROOT)))


def request(root: Path, emit_event=None) -> SkillBuilderAgentRequest:
    return SkillBuilderAgentRequest(
        root=root,
        workspace_id="workspace-1",
        skill_name="sample-skill",
        display_name="Sample Skill",
        description="Build a sample Skill.",
        version="0.1.0",
        tags=("sample",),
        user_message="build",
        materials_markdown="complete-material-payload",
        run_phase="scenario",
        emit_event=emit_event,
    )


def config(root: Path, **environment: str) -> AgentCoreProcessConfig:
    return AgentCoreProcessConfig(
        python_executable=sys.executable,
        worker_module="tests.fake_agent_worker",
        run_root=root / "runs",
        environment={"PYTHONPATH": PYTHONPATH, **environment},
    )


def test_subprocess_runner_preserves_request_result_and_events(tmp_path: Path) -> None:
    events: list[tuple[str, str, dict[str, object]]] = []

    async def emit(event_type, summary, payload):
        events.append((event_type, summary, payload))

    result = asyncio.run(
        SubprocessAgentRunner(config(tmp_path)).run(request(tmp_path, emit))
    )

    assert result.raw_output_text == "complete-material-payload"
    assert result.session_id == "fake-session"
    assert result.submission_status == {"ok": True}
    assert events == [
        ("agent.fake", "fake event", {"phase": "scenario"})
    ]


def test_subprocess_runner_restores_structured_lifecycle_error(tmp_path: Path) -> None:
    runner = SubprocessAgentRunner(
        config(tmp_path, FAKE_AGENT_WORKER_ERROR="1")
    )
    with pytest.raises(SkillBuilderAgentLifecycleError) as caught:
        asyncio.run(runner.run(request(tmp_path)))
    assert caught.value.code == "fake_rejected"
    assert caught.value.phase == "scenario"


def test_subprocess_runner_timeout_stops_worker(tmp_path: Path) -> None:
    base = config(tmp_path, FAKE_AGENT_WORKER_DELAY="5")
    runner = SubprocessAgentRunner(
        AgentCoreProcessConfig(
            python_executable=base.python_executable,
            worker_module=base.worker_module,
            run_root=base.run_root,
            timeout_seconds=1,
            terminate_grace_seconds=0.2,
            environment=base.environment,
        )
    )
    with pytest.raises(asyncio.TimeoutError):
        asyncio.run(runner.run(request(tmp_path)))


def test_worker_calls_existing_agent_core_and_writes_protocol(
    tmp_path: Path,
    monkeypatch,
) -> None:
    observed = {}

    async def fake_agent_core(**kwargs):
        observed.update(kwargs)
        await kwargs["emit_event"]("agent.fake", "event", {"ok": True})
        return SkillBuilderAgentCoreResult(
            raw_output_text="done",
            session_id="session-1",
            final_response={"status": "ready"},
        )

    monkeypatch.setattr(agent_worker, "run_skill_builder_agent_core", fake_agent_core)
    monkeypatch.setenv("SKILL_BUILDER_SANDBOX_ENABLED", "false")
    request_path = tmp_path / "request.json"
    result_path = tmp_path / "result.json"
    events_path = tmp_path / "events.jsonl"
    request_path.write_text(
        json.dumps(
            {
                "schema_version": agent_worker.PROTOCOL_VERSION,
                "root": str(tmp_path),
                "workspace_id": "workspace-1",
                "skill_name": "sample",
                "display_name": "Sample",
                "description": "Sample",
                "version": "0.1.0",
                "tags": [],
                "user_message": "build",
                "materials_markdown": "material",
                "run_phase": "scenario",
                "timeout_seconds": None,
            }
        ),
        encoding="utf-8",
    )

    assert asyncio.run(
        agent_worker.run_worker(request_path, result_path, events_path)
    ) == 0
    result = json.loads(result_path.read_text(encoding="utf-8"))
    assert result["schema_version"] == agent_worker.PROTOCOL_VERSION
    assert result["result"]["session_id"] == "session-1"
    assert observed["workspace"] is None
    assert "agent.fake" in events_path.read_text(encoding="utf-8")
