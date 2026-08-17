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

    async def communicate(self, input: bytes | None = None) -> tuple[bytes, bytes]:
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
    def test_direct_object(self) -> None:
        j = _parse_dimension_judgment(
            '{"dimension":"echoed","score":0.8,"reasoning":"ok"}', "safety"
        )
        assert j.dimension == "safety"  # stamped from requested name, not echoed
        assert j.score == 0.8
        assert j.reasoning == "ok"

    def test_claude_envelope_object_result(self) -> None:
        j = _parse_dimension_judgment(
            '{"type":"result","result":{"dimension":"x","score":0.5,"reasoning":"r"}}',
            "task_completion",
        )
        assert j.score == 0.5
        assert j.dimension == "task_completion"

    def test_claude_envelope_structured_output(self) -> None:
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

    def test_envelope_string_result(self) -> None:
        inner = '{"score":0.3,"reasoning":"r"}'
        j = _parse_dimension_judgment(
            '{{"result": "{}"}}'.format(inner.replace('"', '\\"')), "safety"
        )
        assert j.score == 0.3

    def test_score_clamped(self) -> None:
        j = _parse_dimension_judgment('{"score":1.5,"reasoning":"r"}', "safety")
        assert j.score == 1.0
        j2 = _parse_dimension_judgment('{"score":-0.2,"reasoning":"r"}', "safety")
        assert j2.score == 0.0

    def test_non_numeric_score_raises(self) -> None:
        with pytest.raises(EvaluationError, match="missing numeric 'score'"):
            _parse_dimension_judgment('{"reasoning":"r"}', "safety")

    def test_unparseable_raises(self) -> None:
        with pytest.raises(EvaluationError, match="no parseable judgment"):
            _parse_dimension_judgment("not json at all", "safety")

    def test_extract_dict_fenced(self) -> None:
        # extract_json_data has a fence fallback
        data = _extract_judgment_dict('```json\n{"score":0.4,"reasoning":"r"}\n```')
        assert data is not None and data["score"] == 0.4


# ---------------------------------------------------------------------------
# ClaudeRuntime
# ---------------------------------------------------------------------------


class TestClaudeRuntime:
    def test_command_construction(self, tmp_path: Path, _patch_create: Any) -> None:
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

    def test_env_passthrough(self, tmp_path: Path, _patch_create: Any) -> None:
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

    def test_cwd_is_workdir(self, tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")
        proc = _FakeProc(stdout=b'{"score":0.1,"reasoning":""}', returncode=0)
        cap = _patch_create(proc)
        runtime = ClaudeRuntime()
        asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))
        assert cap.kwargs["cwd"] == tmp_path

    def test_prompt_via_stdin(self, tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")
        proc = _FakeProc(stdout=b'{"score":0.1,"reasoning":""}', returncode=0)
        cap = _patch_create(proc)
        runtime = ClaudeRuntime()
        req = _request(workdir=tmp_path, schema_path=schema)
        asyncio.run(runtime.judge(req))
        assert cap.kwargs["stdin"] is not None  # PIPE
        assert cap.kwargs.get("input") is None  # input fed via communicate inside _run_subprocess

    def test_timeout_kills_process(self, tmp_path: Path, _patch_create: Any) -> None:
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

    def test_nonzero_exit_raises(self, tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")
        proc = _FakeProc(stdout=b"out", stderr=b"boom", returncode=2)
        _patch_create(proc)
        runtime = ClaudeRuntime()
        with pytest.raises(EvaluationError, match="exited 2"):
            asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))

    def test_bad_stdout_raises(self, tmp_path: Path, _patch_create: Any) -> None:
        schema = tmp_path / "s.json"
        schema.write_text("{}", encoding="utf-8")
        proc = _FakeProc(stdout=b"not json", returncode=0)
        _patch_create(proc)
        runtime = ClaudeRuntime()
        with pytest.raises(EvaluationError, match="no parseable judgment"):
            asyncio.run(runtime.judge(_request(workdir=tmp_path, schema_path=schema)))

    def test_binary_missing(self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
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
    def test_command_construction_and_parse(self, tmp_path: Path, _patch_create: Any) -> None:
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

    def test_missing_last_message_file_raises(self, tmp_path: Path, _patch_create: Any) -> None:
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
    def test_claude(self) -> None:
        assert isinstance(make_runtime("claude"), ClaudeRuntime)

    def test_codex(self) -> None:
        assert isinstance(make_runtime("codex"), CodexRuntime)

    def test_unknown_raises(self) -> None:
        with pytest.raises(ValueError, match="Unknown judge runtime"):
            make_runtime("openclaw")  # type: ignore[arg-type]


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
