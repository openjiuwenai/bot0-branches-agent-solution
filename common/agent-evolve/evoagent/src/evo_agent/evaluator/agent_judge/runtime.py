"""Judge-agent runtime adapters — spawn a coding-agent CLI per dimension.

A :class:`JudgeAgentRuntime` is a pluggable abstraction over the real coding
agents (claude code, codex) that the agent-as-judge feature drives as
**subprocesses**. v1 ships :class:`ClaudeRuntime` and :class:`CodexRuntime`;
:openclaw is deferred (the Protocol + ``make_runtime`` already accommodate it).

Each adapter spawns one bounded, non-interactive subprocess per dimension with
``cwd`` set to the isolated workdir, parses the agent's structured final output
into a :class:`DimensionJudgment`, and raises :class:`EvaluationError`
(categorized) on timeout / non-zero exit / unparseable output.

CLI flag shapes are **not** verified against the installed binaries (the repo
has zero prior subprocess usage); they are isolated in module-level constants so
they can be corrected in one place after a ``claude --help`` / ``codex --help``
pass. See the plan's risk #1.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Literal, Protocol, runtime_checkable

from pydantic import ValidationError

from evo_agent.evaluator.agent_judge.schemas import DimensionJudgment
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.llm.structured_output import extract_json_data

logger = logging.getLogger(__name__)

__all__ = [
    "ClaudeRuntime",
    "CodexRuntime",
    "JudgeAgentRuntime",
    "RuntimeJudgeRequest",
    "make_runtime",
]

# --- CLI flag constants (verify against `claude --help` / `codex --help`) -----
_CLAUDE_BINARY = "claude"
_CLAUDE_PERMISSION_MODE = "plan"  # read-only, no side effects (bounded, per Q1)
_CODEX_BINARY = "codex"
_CODEX_SANDBOX = "read-only"  # bounded file access (per Q1)


@dataclass(frozen=True)
class RuntimeJudgeRequest:
    """Everything one dimension's agent subprocess needs to run."""

    dimension_name: str
    prompt: str
    workdir: Path
    schema_path: Path
    tool_allowlist: tuple[str, ...]
    run_timeout: float


@runtime_checkable
class JudgeAgentRuntime(Protocol):
    """Pluggable abstraction over a coding-agent CLI."""

    async def judge(self, request: RuntimeJudgeRequest) -> DimensionJudgment:
        """Run one dimension's judgment; raise ``EvaluationError`` on failure."""
        ...


def _build_env(extra_env: dict[str, str] | None) -> dict[str, str]:
    """Merge the host environment with per-runtime overrides (API keys, etc.)."""
    env: dict[str, str] = dict(os.environ)
    if extra_env:
        env.update(extra_env)
    return env


def _extract_judgment_dict(raw: str) -> dict[str, object] | None:
    """Pull the ``{score, reasoning}`` object out of an agent's raw stdout.

    Tries, in order: a direct JSON object (with fence fallback via
    :func:`extract_json_data`), then a claude ``{"result": ...}`` envelope
    (``result`` may be a JSON string or an embedded object).
    """
    data = extract_json_data(raw)
    if isinstance(data, dict) and ("score" in data or "reasoning" in data):
        return data
    try:
        envelope = json.loads(raw)
    except (ValueError, TypeError):
        envelope = None
    if isinstance(envelope, dict):
        # claude returns the schema-constrained object both as a serialized
        # ``result`` string and as a ready ``structured_output`` dict; prefer the
        # dict (already validated against our schema) before re-parsing result.
        structured = envelope.get("structured_output")
        if isinstance(structured, dict):
            return structured
        result = envelope.get("result")
        if isinstance(result, str):
            inner = extract_json_data(result)
            if isinstance(inner, dict):
                return inner
        elif isinstance(result, dict):
            return result
    if isinstance(data, dict):
        return data
    return None


def _parse_dimension_judgment(raw: str, dimension_name: str) -> DimensionJudgment:
    """Parse agent stdout into a :class:`DimensionJudgment`.

    The ``dimension`` field is stamped from the *requested* dimension name
    (the agent may parrot the wrong label — risk #10). ``score`` is clamped to
    ``[0, 1]`` so a stray ``1.05`` does not hard-fail the run.
    """
    data = _extract_judgment_dict(raw)
    if data is None:
        raise EvaluationError(
            category="agent_judge_output_error",
            safe_message=f"agent produced no parseable judgment for dimension {dimension_name!r}",
            raw_response=raw,
        )
    raw_score = data.get("score")
    if isinstance(raw_score, bool) or not isinstance(raw_score, (int, float)):
        raise EvaluationError(
            category="agent_judge_output_error",
            safe_message=f"agent judgment for {dimension_name!r} missing numeric 'score'",
            raw_response=raw,
        )
    score = max(0.0, min(1.0, float(raw_score)))
    reasoning = data.get("reasoning", "")
    if not isinstance(reasoning, str):
        reasoning = str(reasoning)
    try:
        return DimensionJudgment(dimension=dimension_name, score=score, reasoning=reasoning)
    except ValidationError as exc:
        raise EvaluationError(
            category="agent_judge_output_error",
            safe_message=f"agent judgment for {dimension_name!r} failed validation: {exc}",
            raw_response=raw,
        ) from exc


async def _run_subprocess(
    *,
    command: list[str],
    stdin_text: str | None,
    workdir: Path,
    env: dict[str, str],
    run_timeout: float,
    dimension_name: str,
) -> str:
    """Spawn a bounded subprocess, return its stdout text, or raise EvaluationError.

    On timeout the process is killed and awaited so no orphan lingers.
    """
    try:
        proc: asyncio.subprocess.Process = await asyncio.create_subprocess_exec(
            *command,
            stdin=asyncio.subprocess.PIPE if stdin_text is not None else None,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            cwd=workdir,
            env=env,
        )
    except FileNotFoundError as exc:
        raise EvaluationError(
            category="agent_judge_binary_missing",
            safe_message=f"judge binary not found: {command[0]!r}",
        ) from exc

    try:
        stdin_bytes = stdin_text.encode("utf-8") if stdin_text is not None else None
        stdout_b, stderr_b = await asyncio.wait_for(
            proc.communicate(input=stdin_bytes),
            timeout=run_timeout,
        )
    except TimeoutError:
        if proc.returncode is None:
            proc.kill()
            await proc.wait()
        raise EvaluationError(
            category="agent_judge_timeout",
            safe_message=(
                f"judge agent timed out after {run_timeout}s for dimension {dimension_name!r}"
            ),
        ) from None
    finally:
        # Never orphan the subprocess: kill it if still running on any non-success
        # exit (cancellation, transport error). On success/timeout returncode is set.
        if proc.returncode is None:
            proc.kill()

    stdout = stdout_b.decode("utf-8", errors="replace")
    if proc.returncode != 0:
        stderr = stderr_b.decode("utf-8", errors="replace")
        raise EvaluationError(
            category="agent_judge_run_error",
            safe_message=(
                f"judge agent exited {proc.returncode} for dimension {dimension_name!r}: "
                f"{stderr.strip()[:500]}"
            ),
            raw_response=stdout,
        )
    return stdout


class ClaudeRuntime:
    """Drive ``claude`` (Claude Code) as the judge agent."""

    def __init__(
        self, *, binary: str = _CLAUDE_BINARY, extra_env: dict[str, str] | None = None
    ) -> None:
        self._binary = binary
        self._extra_env = extra_env or {}

    async def judge(self, request: RuntimeJudgeRequest) -> DimensionJudgment:
        command = [
            self._binary,
            "-p",
            "-",  # read prompt from stdin to avoid argv length limits
            "--output-format",
            "json",
            "--json-schema",
            request.schema_path.read_text(encoding="utf-8"),
            "--allowedTools",
            ",".join(request.tool_allowlist),
            "--permission-mode",
            _CLAUDE_PERMISSION_MODE,
        ]
        stdout = await _run_subprocess(
            command=command,
            stdin_text=request.prompt,
            workdir=request.workdir,
            env=_build_env(self._extra_env),
            run_timeout=request.run_timeout,
            dimension_name=request.dimension_name,
        )
        return _parse_dimension_judgment(stdout, request.dimension_name)


class CodexRuntime:
    """Drive ``codex`` (codex-cli) as the judge agent.

    Codex writes its schema-constrained final message to the ``-o`` file; we
    read that file (falling back to stdout JSONL) rather than parsing the
    streamed event envelope, which is less stable across versions.
    """

    def __init__(
        self, *, binary: str = _CODEX_BINARY, extra_env: dict[str, str] | None = None
    ) -> None:
        self._binary = binary
        self._extra_env = extra_env or {}

    async def judge(self, request: RuntimeJudgeRequest) -> DimensionJudgment:
        last_message_path = request.workdir / "codex_last_message.json"
        command = [
            self._binary,
            "exec",
            "--output-schema",
            str(request.schema_path),
            "-o",
            str(last_message_path),
            "--sandbox",
            _CODEX_SANDBOX,
            "--json",
            request.prompt,
        ]
        await _run_subprocess(
            command=command,
            stdin_text=None,
            workdir=request.workdir,
            env=_build_env(self._extra_env),
            run_timeout=request.run_timeout,
            dimension_name=request.dimension_name,
        )
        raw = last_message_path.read_text(encoding="utf-8") if last_message_path.exists() else ""
        if not raw:
            raise EvaluationError(
                category="agent_judge_output_error",
                safe_message=f"codex produced no last-message file for {request.dimension_name!r}",
            )
        return _parse_dimension_judgment(raw, request.dimension_name)


def make_runtime(
    runtime: Literal["claude", "codex"],
    *,
    extra_env: dict[str, str] | None = None,
) -> JudgeAgentRuntime:
    """Resolve a runtime name to an adapter (factory hook for the evaluator)."""
    if runtime == "claude":
        return ClaudeRuntime(extra_env=extra_env)
    if runtime == "codex":
        return CodexRuntime(extra_env=extra_env)
    raise ValueError(f"Unknown judge runtime: {runtime!r} (use 'claude' or 'codex')")
