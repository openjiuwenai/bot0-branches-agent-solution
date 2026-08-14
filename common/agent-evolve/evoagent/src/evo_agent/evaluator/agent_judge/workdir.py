"""Isolated per-run workdir for the judge agent subprocess.

Each ``AgentEvaluator.evaluate`` call opens a fresh temp directory, materializes
the full trajectory (``trajectory.jsonl``) plus a compact summary
(``trajectory.md``), copies the preset's bundled helper-skill ``.md`` docs, and
writes the dimension-judgment JSON schema the agent's CLI will be constrained
by. The agent runs with ``cwd=workdir`` so its built-in ``Read``/``Grep`` tools
see exactly these files and nothing else from the host.

Cleanup is best-effort: ``__exit__`` ``rmtree``s the tree unless
``keep_on_error`` (gated on ``EVO_DEBUG_AGENT_JUDGE_WORKDIR=1`` upstream) holds
it for post-mortem inspection.
"""

from __future__ import annotations

import json
import shutil
import tempfile
from importlib.resources import files
from pathlib import Path
from typing import Any

from evo_agent.evaluator.domain.models import StandardTrajectory

__all__ = ["SCHEMA_FILENAME", "WorkdirManager"]

SCHEMA_FILENAME = "dimension_judgment.schema.json"


class WorkdirManager:
    """Context-managed temp workdir holding the agent's entire readable world."""

    def __init__(
        self,
        *,
        base_dir: str | None = None,
        keep_on_error: bool = False,
        prefix: str = "evo-agent-judge-",
    ) -> None:
        self._base_dir = base_dir
        self._keep_on_error = keep_on_error
        self._prefix = prefix
        self._path: Path | None = None

    @property
    def path(self) -> Path:
        if self._path is None:
            raise RuntimeError("WorkdirManager used outside its context manager")
        return self._path

    def __enter__(self) -> WorkdirManager:
        self._path = Path(tempfile.mkdtemp(prefix=self._prefix, dir=self._base_dir))
        return self

    def __exit__(self, *exc: object) -> None:
        if self._path is None:
            return
        # ``exc`` is the (exc_type, exc_val, exc_tb) triple; exc_type is None on
        # a clean exit. Keep the tree only when an exception occurred AND the
        # caller asked to preserve it for post-mortem inspection.
        if exc[0] is not None and self._keep_on_error:
            return
        shutil.rmtree(self._path, ignore_errors=True)
        self._path = None

    def materialize_trajectory(
        self,
        trajectory: StandardTrajectory | dict[str, Any],
        *,
        compacted_text: str,
    ) -> None:
        """Write the full message stream to ``trajectory.jsonl`` and the compact
        summary to ``trajectory.md``.

        ``trajectory.jsonl`` is one JSON object per message so the agent can
        ``Grep`` individual lines; ``trajectory.md`` is the budgeted summary
        embedded directly in the per-dim prompt.
        """
        data = trajectory.model_dump() if isinstance(trajectory, StandardTrajectory) else trajectory
        messages = data.get("messages", []) if isinstance(data, dict) else []
        with (self.path / "trajectory.jsonl").open("w", encoding="utf-8") as handle:
            for message in messages:
                handle.write(json.dumps(message, ensure_ascii=False, default=str))
                handle.write("\n")
        (self.path / "trajectory.md").write_text(compacted_text, encoding="utf-8")

    def materialize_helper_skills(self, names: tuple[str, ...]) -> None:
        """Copy bundled ``helper_skills/<name>.md`` docs into the workdir."""
        if not names:
            return
        helper_root = files("evo_agent.evaluator.agent_judge.helper_skills")
        for name in names:
            source = helper_root.joinpath(f"{name}.md")
            if not source.is_file():
                raise FileNotFoundError(f"helper skill not found: {name!r}")
            (self.path / f"{name}.md").write_text(
                source.read_text(encoding="utf-8"), encoding="utf-8"
            )

    def write_schema(
        self,
        schema: dict[str, Any],
        *,
        filename: str = SCHEMA_FILENAME,
    ) -> Path:
        """Write the dimension-judgment JSON schema; return its path."""
        target = self.path / filename
        target.write_text(json.dumps(schema, ensure_ascii=False), encoding="utf-8")
        return target
