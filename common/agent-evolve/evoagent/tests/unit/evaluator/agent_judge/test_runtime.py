"""agent_judge runtime 适配器单元测试 — 命令构造 / env 透传 / 解析 / 超时 / 坏 stdout。

不跑真实 claude/codex 二进制；用 fake Process 替身 ``asyncio.create_subprocess_exec``。
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any

import pytest

from evo_agent.evaluator.agent_judge.runtime import (
    ClaudeRuntime,
    CodexRuntime,
    JiuwenSwarmRuntime,
    RuntimeJudgeRequest,
    _extract_judgment_dict,
    _parse_dimension_judgment,
    make_runtime,
)
from evo_agent.evaluator.domain.scoring import EvaluationError

# ---------------------------------------------------------------------------
# Fake subprocess plumbing
# ---------------------------------------------------------------------------


class _FakeProc:
    """假 asyncio.subprocess.Process。"""

    def __init__(
        self,
        *,
        stdout: bytes = b"",
        stderr: bytes = b"",
        returncode: int = 0,
        exc: BaseException | None = None,
    ) -> None:
        self._stdout = stdout
        self._stderr = stderr
        self._rc = returncode
        self._exc = exc
        self.returncode: int | None = None  # in-flight until communicate
        self.killed = False
        self.waited = False

    async def communicate(self, input: bytes | None = None) -> tuple[bytes, bytes]:  # noqa: A002
        if self._exc is not None:
            raise self._exc
        self.returncode = self._rc
        return self._stdout, self._stderr

    def kill(self) -> None:
        self.killed = True

    async def wait(self) -> int:
        self.waited = True
        return self._rc


class _CreateCapture:
    """Captures the args/kwargs passed to create_subprocess_exec."""

    def __init__(self, proc: _FakeProc, *, write_file: tuple[str, bytes] | None = None) -> None:
        self._proc = proc
        self._write_file = write_file
        self.args: tuple[Any, ...] = ()
        self.kwargs: dict[str, Any] = {}

    async def __call__(self, *args: Any, **kwargs: Any) -> _FakeProc:
        self.args = args
        self.kwargs = kwargs
        # CodexRuntime reads a last-message file the real CLI would have written.
        if self._write_file is not None:
            cwd = kwargs.get("cwd")
            assert isinstance(cwd, Path)
            (cwd / self._write_file[0]).write_bytes(self._write_file[1])
        return self._proc


def _request(
    *,
    workdir: Path,
    schema_path: Path,
    allowlist: tuple[str, ...] = ("Read", "Grep"),
    run_timeout: float = 5.0,
) -> RuntimeJudgeRequest:
    return RuntimeJudgeRequest(
        dimension_name="safety",
        prompt="judge this",
        workdir=workdir,
        schema_path=schema_path,
        tool_allowlist=allowlist,
        run_timeout=run_timeout,
    )


@pytest.fixture
def _patch_create(monkeypatch: pytest.MonkeyPatch) -> Any:
    """Install a capturable create_subprocess_exec; returns the capture holder."""

    def install(proc: _FakeProc, *, write_file: tuple[str, bytes] | None = None) -> _CreateCapture:
        cap = _CreateCapture(proc, write_file=write_file)
        monkeypatch.setattr(asyncio, "create_subprocess_exec", cap)
        return cap

    return install


# ---------------------------------------------------------------------------
# _parse_dimension_judgment / _extract_judgment_dict (pure)
# ---------------------------------------------------------------------------


class TestParseDimensionJudgment:
    @staticmethod
    def test_direct_object() -> None:
        j = _parse_dimension_judgment(
            '{"dimension":"echoed","score":0.8,"reasoning":"ok"}', "safety"
        )
        assert j.dimension == "safety"  # stamped from requested name, not echoed
        assert j.score == 0.8
        assert j.reasoning == "ok"

    @staticmethod
    def test_claude_envelope_object_result() -> None:
        j = _parse_dimension_judgment(
            '{"type":"result","result":{"dimension":"x","score":0.5,"reasoning":"r"}}',
            "task_completion",
        )
        assert j.score == 0.5
        assert j.dimension == "task_completion"

    @staticmethod
    def test_claude_envelope_structured_output() -> None:
        # real claude --output-format json envelope: result (string) +
        # structured_output (ready dict, schema-validated) + type=success
        j = _parse_dimension_judgment(
            '{"is_error":false,"result":"{\\"score\\":0.8,\\"reasoning\\":\\"r\\"}",'
            '"structured_output":{"score":0.8,"reasoning":"r"},"type":"result","subtype":"success"}',
            "safety",
        )
        assert j.score == 0.8
        assert j.reasoning == "r"
        assert j.dimension == "safety"

    @staticmethod
    def test_envelope_string_result() -> None:
        inner = '{"score":0.3,"reasoning":"r"}'
        j = _parse_dimension_judgment(
            '{{"result": "{}"}}'.format(inner.replace('"', '\\"')), "safety"
        )
        assert j.score == 0.3

    @staticmethod
    def test_score_clamped() -> None:
        j = _parse_dimension_judgment('{"score":1.5,"reasoning":"r"}', "safety")
        assert j.score == 1.0
        j2 = _parse_dimension_judgment('{"score":-0.2,"reasoning":"r"}', "safety")
        assert j2.score == 0.0

    @staticmethod
    def test_non_numeric_score_raises() -> None:
        with pytest.raises(EvaluationError, match="missing numeric 'score'"):
            _parse_dimension_judgment('{"reasoning":"r"}', "safety")

    @staticmethod
    def test_unparseable_raises() -> None:
        with pytest.raises(EvaluationError, match="no parseable judgment"):
            _parse_dimension_judgment("not json at all", "safety")

    @staticmethod
    def test_extract_dict_fenced() -> None:
        # extract_json_data has a fence fallback
        data = _extract_judgment_dict('```json\n{"score":0.4,"reasoning":"r"}\n```')
        assert data is not None and data["score"] == 0.4


# ---------------------------------------------------------------------------
# ClaudeRuntime
# ---------------------------------------------------------------------------


class TestClaudeRuntime:
    @staticmethod
    def test_command_construction(tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "schema.json"
        schema.write_text('{"type":"object"}', encoding="utf-8")
        proc = _FakeProc(stdout=b'{"dimension":"x","score":0.9,"reasoning":"r"}', returncode=0)
        cap = _patch_create(proc)
        runtime = ClaudeRuntime(extra_env={"ANTHROPIC_API_KEY": "k"})
        j = asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))
        assert j.score == 0.9
        cmd = cap.args
        assert cmd[0] == "claude"
        assert "-p" in cmd and "-" in cmd
        assert "--output-format" in cmd
        assert "--json-schema" in cmd
        # schema is passed inline as JSON content, NOT as a file path
        schema_idx = cmd.index("--json-schema")
        assert cmd[schema_idx + 1] == schema.read_text(encoding="utf-8")
        assert str(schema) not in cmd
        assert "--allowedTools" in cmd
        assert "Read,Grep" in cmd
        assert "--permission-mode" in cmd and "plan" in cmd

    @staticmethod
    def test_env_passthrough(tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")
        proc = _FakeProc(stdout=b'{"score":0.1,"reasoning":""}', returncode=0)
        cap = _patch_create(proc)
        runtime = ClaudeRuntime(extra_env={"ANTHROPIC_API_KEY": "sk-test", "EXTRA": "v"})
        asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))
        env = cap.kwargs["env"]
        assert env["ANTHROPIC_API_KEY"] == "sk-test"
        assert env["EXTRA"] == "v"
        assert "PATH" in env  # host env inherited

    @staticmethod
    def test_cwd_is_workdir(tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")
        proc = _FakeProc(stdout=b'{"score":0.1,"reasoning":""}', returncode=0)
        cap = _patch_create(proc)
        runtime = ClaudeRuntime()
        asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))
        assert cap.kwargs["cwd"] == tmp_path

    @staticmethod
    def test_prompt_via_stdin(tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")
        proc = _FakeProc(stdout=b'{"score":0.1,"reasoning":""}', returncode=0)
        cap = _patch_create(proc)
        runtime = ClaudeRuntime()
        req = _request(workdir=tmp_path, schema_path=schema)
        asyncio.run(runtime.judge(req))
        assert cap.kwargs["stdin"] is not None  # PIPE
        assert cap.kwargs.get("input") is None  # input fed via communicate inside _run_subprocess

    @staticmethod
    def test_timeout_kills_process(tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")
        proc = _FakeProc(exc=TimeoutError())
        _patch_create(proc)
        runtime = ClaudeRuntime()
        with pytest.raises(EvaluationError) as exc_info:
            asyncio.run(
                runtime.judge(_request(workdir=tmp_path, schema_path=schema, run_timeout=0.01))
            )
        assert exc_info.value.category == "agent_judge_timeout"
        assert proc.killed

    @staticmethod
    def test_nonzero_exit_raises(tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")
        proc = _FakeProc(stdout=b"out", stderr=b"boom", returncode=2)
        _patch_create(proc)
        runtime = ClaudeRuntime()
        with pytest.raises(EvaluationError, match="exited 2"):
            asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))

    @staticmethod
    def test_bad_stdout_raises(tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")
        proc = _FakeProc(stdout=b"not json", returncode=0)
        _patch_create(proc)
        runtime = ClaudeRuntime()
        with pytest.raises(EvaluationError, match="no parseable judgment"):
            asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))

    @staticmethod
    def test_binary_missing(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")

        async def _missing(*a: Any, **k: Any) -> _FakeProc:  # noqa: ARG001
            raise FileNotFoundError("no claude")

        monkeypatch.setattr(asyncio, "create_subprocess_exec", _missing)
        runtime = ClaudeRuntime()
        with pytest.raises(EvaluationError, match="binary not found") as exc_info:
            asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))
        assert exc_info.value.category == "agent_judge_binary_missing"


# ---------------------------------------------------------------------------
# CodexRuntime
# ---------------------------------------------------------------------------


class TestCodexRuntime:
    @staticmethod
    def test_command_construction_and_parse(tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "schema.json"
        schema.write_text("{}", encoding="utf-8")
        judgment = b'{"dimension":"echoed","score":0.7,"reasoning":"r"}'
        proc = _FakeProc(returncode=0)
        cap = _patch_create(proc, write_file=("codex_last_message.json", judgment))
        runtime = CodexRuntime(extra_env={"OPENAI_API_KEY": "ok"})
        j = asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))
        assert j.score == 0.7
        assert j.dimension == "safety"  # stamped, not echoed
        cmd = cap.args
        assert cmd[0] == "codex"
        assert "exec" in cmd
        assert "--output-schema" in cmd and str(schema) in cmd
        assert "--sandbox" in cmd and "read-only" in cmd
        assert "--json" in cmd
        assert "judge this" in cmd  # prompt passed as argv element
        assert cap.kwargs["env"]["OPENAI_API_KEY"] == "ok"

    @staticmethod
    def test_missing_last_message_file_raises(tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")
        proc = _FakeProc(returncode=0)
        _patch_create(proc)  # no write_file → last-message file absent
        runtime = CodexRuntime()
        with pytest.raises(EvaluationError, match="no last-message file"):
            asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))


# ---------------------------------------------------------------------------
# make_runtime
# ---------------------------------------------------------------------------


class TestMakeRuntime:
    @staticmethod
    def test_claude() -> None:
        assert isinstance(make_runtime("claude"), ClaudeRuntime)

    @staticmethod
    def test_codex() -> None:
        assert isinstance(make_runtime("codex"), CodexRuntime)

    @staticmethod
    def test_jiuwenswarm() -> None:
        rt = make_runtime("jiuwenswarm")
        assert isinstance(rt, JiuwenSwarmRuntime)
        assert rt._agent_profile == "codex"  # default profile

    def test_jiuwenswarm_with_profile(self) -> None:
        rt = make_runtime("jiuwenswarm", agent_profile="custom_agent")
        assert isinstance(rt, JiuwenSwarmRuntime)
        assert rt._agent_profile == "custom_agent"

    @staticmethod
    def test_unknown_raises() -> None:
        with pytest.raises(ValueError, match="Unknown judge runtime"):
            make_runtime("openclaw")  # type: ignore[arg-type]


# ---------------------------------------------------------------------------
# JiuwenSwarmRuntime — mock AcpStdioClient (jiuwenswarm is a lazy import)
# ---------------------------------------------------------------------------


class _FakeAcpClient:
    """Fake AcpStdioClient for testing JiuwenSwarmRuntime without jiuwenswarm."""

    def __init__(
        self,
        command: str,
        args: list[str] | None = None,
        *,
        cwd: str | None = None,
        env: dict[str, Any] | None = None,
    ) -> None:
        self.command = command
        self.args = args or []
        self.cwd = cwd
        self.env = env
        self.connected = False
        self.closed = True

    async def connect(self) -> None:
        self.connected = True

    async def chat(self, message: str, *, timeout: float | None = None) -> str:
        return '{"score": 0.7, "reasoning": "ok"}'

    async def close(self) -> None:
        self.closed = True


def _install_fake_acp(
    monkeypatch: pytest.MonkeyPatch,  # noqa: ARG001 — kept for API compat
    client_cls: type = _FakeAcpClient,
    config: dict[str, Any] | None = None,
) -> type:
    """Return constructor kwargs for JiuwenSwarmRuntime to inject fake ACP client."""
    if config is None:
        config = {
            "acp_agents": {
                "codex": {
                    "command": "npx",
                    "args": ["@zed-industries/codex-acp@latest"],
                    "env": {"OPENAI_API_KEY": "test-key"},
                },
                "empty_cmd": {"command": "", "args": []},
            }
        }
    # Store the config on the client_cls for easy access in tests.
    client_cls._test_config = config  # type: ignore[attr-defined]
    return client_cls


def _make_runtime(
    client_cls: type,
    *,
    agent_profile: str = "codex",
    extra_env: dict[str, str] | None = None,
) -> JiuwenSwarmRuntime:
    """Build a JiuwenSwarmRuntime with injected fake client + config."""
    config = getattr(client_cls, "_test_config", {"acp_agents": {}})
    return JiuwenSwarmRuntime(
        agent_profile=agent_profile,
        extra_env=extra_env,
        _client_factory=client_cls,
        _config_loader=lambda: config,
    )


class TestJiuwenSwarmRuntime:
    @staticmethod
    def test_judge_parses_response(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        schema = tmp_path / "schema.json"
        schema.write_text("{}", encoding="utf-8")

        class MyClient(_FakeAcpClient):
            async def chat(self, message: str, *, timeout: float | None = None) -> str:
                return '{"score": 0.85, "reasoning": "well done"}'

        _install_fake_acp(monkeypatch, MyClient)
        runtime = _make_runtime(MyClient)
        j = asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))
        assert j.dimension == "safety"
        assert j.score == 0.85
        assert j.reasoning == "well done"

    @staticmethod
    def test_cwd_is_workdir(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        schema = tmp_path / "schema.json"
        schema.write_text("{}", encoding="utf-8")

        clients: list[_FakeAcpClient] = []

        class TrackingClient(_FakeAcpClient):
            def __init__(self, *args: Any, **kwargs: Any) -> None:
                super().__init__(*args, **kwargs)
                clients.append(self)

        _install_fake_acp(monkeypatch, TrackingClient)
        runtime = _make_runtime(TrackingClient)
        asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))
        assert len(clients) == 1
        assert clients[0].cwd == str(tmp_path)

    @staticmethod
    def test_synthesize_parses_dict(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        schema = tmp_path / "schema.json"
        schema.write_text("{}", encoding="utf-8")
        attr = {
            "skill_attributions": [
                {"skill_name": "s", "usage_status": "executed", "impact": "positive", "reason": "r"}
            ],
            "attribution_status": "completed",
        }

        class AttrClient(_FakeAcpClient):
            async def chat(self, message: str, *, timeout: float | None = None) -> str:
                return json.dumps(attr)

        _install_fake_acp(monkeypatch, AttrClient)
        runtime = _make_runtime(AttrClient)
        data = asyncio.run(runtime.synthesize(_request(workdir=tmp_path, schema_path=schema)))
        assert data == attr

    @staticmethod
    def test_synthesize_unparseable_raises(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        schema = tmp_path / "schema.json"
        schema.write_text("{}", encoding="utf-8")

        class BadClient(_FakeAcpClient):
            async def chat(self, message: str, *, timeout: float | None = None) -> str:
                return "not json at all"

        _install_fake_acp(monkeypatch, BadClient)
        runtime = _make_runtime(BadClient)
        with pytest.raises(EvaluationError, match="no parseable output"):
            asyncio.run(runtime.synthesize(_request(workdir=tmp_path, schema_path=schema)))

    @staticmethod
    def test_runtime_error_becomes_evaluation_error(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        schema = tmp_path / "schema.json"
        schema.write_text("{}", encoding="utf-8")

        class ErrClient(_FakeAcpClient):
            async def chat(self, message: str, *, timeout: float | None = None) -> str:
                raise RuntimeError("ACP agent crashed")

        _install_fake_acp(monkeypatch, ErrClient)
        runtime = _make_runtime(ErrClient)
        with pytest.raises(EvaluationError, match="jiuwenswarm agent error"):
            asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))

    def test_timeout_becomes_evaluation_error(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        schema = tmp_path / "schema.json"
        schema.write_text("{}", encoding="utf-8")

        class TimeoutClient(_FakeAcpClient):
            async def chat(self, message: str, *, timeout: float | None = None) -> str:
                raise TimeoutError()

        _install_fake_acp(monkeypatch, TimeoutClient)
        runtime = _make_runtime(TimeoutClient)
        with pytest.raises(EvaluationError) as exc_info:
            asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))
        assert exc_info.value.category == "agent_judge_timeout"

    def test_unknown_profile_raises(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        schema = tmp_path / "schema.json"
        schema.write_text("{}", encoding="utf-8")
        _install_fake_acp(monkeypatch)
        runtime = _make_runtime(_FakeAcpClient, agent_profile="nonexistent")
        with pytest.raises(EvaluationError, match="unknown jiuwenswarm agent profile"):
            asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))

    def test_empty_command_raises(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        schema = tmp_path / "schema.json"
        schema.write_text("{}", encoding="utf-8")
        _install_fake_acp(monkeypatch)
        runtime = _make_runtime(_FakeAcpClient, agent_profile="empty_cmd")
        with pytest.raises(EvaluationError, match="has no command"):
            asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))

    def test_extra_env_merged(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        schema = tmp_path / "schema.json"
        schema.write_text("{}", encoding="utf-8")

        clients: list[_FakeAcpClient] = []

        class TrackingClient(_FakeAcpClient):
            def __init__(self, *args: Any, **kwargs: Any) -> None:
                super().__init__(*args, **kwargs)
                clients.append(self)

        _install_fake_acp(monkeypatch, TrackingClient)
        runtime = _make_runtime(TrackingClient, extra_env={"MY_KEY": "my_val"})
        asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))
        env = clients[0].env
        assert env is not None
        # profile env from config
        assert env["OPENAI_API_KEY"] == "test-key"
        # extra_env overrides
        assert env["MY_KEY"] == "my_val"
        # host env inherited
        assert "PATH" in env


# ---------------------------------------------------------------------------
# synthesize (attribution agent) — same spawn, parsed as a plain dict
# ---------------------------------------------------------------------------


class TestSynthesize:
    def test_claude_structured_output_dict(self, tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "schema.json"
        schema.write_text('{"type":"object"}', encoding="utf-8")
        attr = {
            "skill_attributions": [
                {"skill_name": "x", "usage_status": "executed", "impact": "positive", "reason": "r"}
            ],
            "attribution_status": "completed",
            "attribution_error": None,
        }
        envelope = json.dumps(
            {
                "is_error": False,
                "result": json.dumps(attr),
                "structured_output": attr,
                "type": "result",
                "subtype": "success",
            }
        )
        proc = _FakeProc(stdout=envelope.encode("utf-8"), returncode=0)
        _patch_create(proc)
        runtime = ClaudeRuntime()
        data = asyncio.run(runtime.synthesize(_request(workdir=tmp_path, schema_path=schema)))
        assert data == attr

    def test_claude_returns_bare_dict(self, tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")
        attr = {"skill_attributions": [], "attribution_status": "completed"}
        proc = _FakeProc(stdout=json.dumps(attr).encode("utf-8"), returncode=0)
        _patch_create(proc)
        runtime = ClaudeRuntime()
        data = asyncio.run(runtime.synthesize(_request(workdir=tmp_path, schema_path=schema)))
        assert data == attr

    def test_claude_unparseable_raises(self, tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")
        proc = _FakeProc(stdout=b"not json", returncode=0)
        _patch_create(proc)
        runtime = ClaudeRuntime()
        with pytest.raises(EvaluationError, match="no parseable output"):
            asyncio.run(runtime.synthesize(_request(workdir=tmp_path, schema_path=schema)))

    def test_codex_reads_attribution_message(self, tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "schema.json"
        schema.write_text("{}", encoding="utf-8")
        attr = {
            "skill_attributions": [
                {"skill_name": "y", "usage_status": "misused", "impact": "negative", "reason": "r"}
            ],
            "attribution_status": "failed",
            "attribution_error": "unsure",
        }
        proc = _FakeProc(returncode=0)
        cap = _patch_create(
            proc, write_file=("codex_attribution_message.json", json.dumps(attr).encode("utf-8"))
        )
        runtime = CodexRuntime()
        data = asyncio.run(runtime.synthesize(_request(workdir=tmp_path, schema_path=schema)))
        assert data == attr
        # the attribution step writes a distinct message file, not the judge's
        assert "-o" in cap.args
        o_idx = cap.args.index("-o")
        assert cap.args[o_idx + 1].endswith("codex_attribution_message.json")
