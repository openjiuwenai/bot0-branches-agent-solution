from __future__ import annotations

import asyncio
from pathlib import Path
from types import SimpleNamespace

from skill_builder.adapters import jiuwenbox
from skill_builder.adapters.jiuwenbox import (
    JiuwenboxExecutionPort,
    JiuwenboxWorkspacePort,
    SkillBuilderSandboxCommandResult,
)
from skill_builder.ports import ExecutionRequest
from skill_builder import recording as recording_module
from skill_builder.recording import ActiveWebRecording, stop_recording


class FakeSandboxSession:
    instances: list["FakeSandboxSession"] = []

    def __init__(self, *, root: Path, workspace_id: str, purpose: str) -> None:
        self.root = root
        self.workspace_id = workspace_id
        self.purpose = purpose
        self.commands: list[dict[str, object]] = []
        self.sealed = False
        self.closed = False
        self.instances.append(self)

    def seal_generated_skill_sync_back(self) -> None:
        self.sealed = True

    def execute(self, command, *, timeout_seconds, workdir, env):
        self.commands.append(
            {
                "command": list(command),
                "timeout_seconds": timeout_seconds,
                "workdir": workdir,
                "env": dict(env),
            }
        )
        return SkillBuilderSandboxCommandResult(
            exit_code=0,
            stdout="ok" if len(self.commands) == 2 else "",
            stderr="",
        )

    def close(self) -> None:
        self.closed = True


def test_jiuwenbox_workspace_and_acceptance_adapters(monkeypatch, tmp_path: Path) -> None:
    FakeSandboxSession.instances.clear()
    monkeypatch.setattr(jiuwenbox, "SkillBuilderSandboxSession", FakeSandboxSession)
    workspace_port = JiuwenboxWorkspacePort()
    accessor = workspace_port.create_accessor(
        root=tmp_path,
        workspace_id="workspace-1",
        purpose="scenario",
    )
    assert accessor.root == tmp_path
    assert accessor.purpose == "scenario"

    generated = tmp_path / "generated-skill"
    generated.mkdir()
    result = asyncio.run(
        JiuwenboxExecutionPort().run(
            ExecutionRequest(
                command=("python", "scripts/check.py"),
                cwd=generated,
                timeout_seconds=30,
                env={"CHECK_MODE": "offline"},
            )
        )
    )
    session = FakeSandboxSession.instances[-1]
    assert session.purpose == "acceptance"
    assert session.sealed is True
    assert session.closed is True
    assert len(session.commands) == 2
    run = session.commands[1]
    assert run["command"] == ["python3", "scripts/check.py"]
    assert run["workdir"] == "/workspace/generated-skill"
    assert run["env"]["CHECK_MODE"] == "offline"
    assert run["env"]["PYTHONPATH"] == "/workspace/generated-skill"
    assert result.exit_code == 0
    assert result.stdout == "ok"


class FakeTracing:
    def __init__(self) -> None:
        self.stopped = False

    async def stop(self, *, path: str) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_bytes(b"trace")
        self.stopped = True


class FakeContext:
    def __init__(self) -> None:
        self.tracing = FakeTracing()
        self.closed = False

    async def storage_state(self, *, path: str) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text("{}", encoding="utf-8")

    async def close(self) -> None:
        self.closed = True


class FakePlaywright:
    def __init__(self) -> None:
        self.stopped = False

    async def stop(self) -> None:
        self.stopped = True


def test_recording_stop_creates_durable_markdown_and_releases_runtime(tmp_path: Path) -> None:
    workspace_id = "recording-workspace"
    context = FakeContext()
    playwright = FakePlaywright()
    recording = ActiveWebRecording(
        id="recording-1",
        workspace_id=workspace_id,
        title="Public workflow",
        goal="Demonstrate a public lookup",
        start_url="https://example.com/start",
        current_url="https://example.com/result",
        status="recording",
        started_at="2026-08-26T00:00:00Z",
        stopped_at=None,
        recording_path="playwright/recordings/recording-1/recording.md",
        material_path=None,
        trace_path=None,
        error=None,
        context=context,
        playwright=playwright,
        page=SimpleNamespace(),
        steps=[
            {
                "index": 1,
                "type": "navigate",
                "url": "https://example.com/result",
                "timestamp": "2026-08-26T00:00:01Z",
            }
        ],
        event_count=1,
    )
    recording_module._ACTIVE_WEB_RECORDINGS[workspace_id] = recording
    try:
        completed, markdown = asyncio.run(
            stop_recording(
                root=tmp_path,
                workspace_id=workspace_id,
                recording_id=recording.id,
            )
        )
    finally:
        recording_module._ACTIVE_WEB_RECORDINGS.pop(workspace_id, None)

    assert completed.status == "completed"
    assert completed.material_path == (
        "inputs/external-sources/recording-1/web-recording.md"
    )
    assert (tmp_path / completed.material_path).read_text(encoding="utf-8") == markdown
    assert "Public workflow" in markdown
    assert context.closed is True
    assert context.tracing.stopped is True
    assert playwright.stopped is True
    assert workspace_id not in recording_module._ACTIVE_WEB_RECORDINGS
