"""Isolated per-run workdir for the judge agent subprocess.

Each ``AgentEvaluator.evaluate`` call opens a fresh temp directory, materializes
the full trajectory (``trajectory.jsonl``) plus a compact summary
(``trajectory.md``), copies the bundled evaluator-skill ``SKILL.md`` docs (both
common skills auto-discovered from ``evaluator_skills/common/`` and dimension-
specific skills referenced by ``JudgeDimension.skills``), and writes the
dimension-judgment JSON schema the agent's CLI will be constrained by. The
agent runs with ``cwd=workdir`` so its built-in ``Read``/``Grep`` tools see
exactly these files and nothing else from the host.

Evaluator skills live in a folder-based structure under
``evaluator_skills/``::

    evaluator_skills/
    ├── common/                   ← universal skills (auto-mounted for all dims)
    │   ├── trajectory_reader/SKILL.md
    │   └── ...
    └── answer_faithfulness/      ← dimension-specific skills
        └── faithfulness_checklist/SKILL.md

``discover_common_skills()`` scans ``common/`` at runtime to find all skills
with a ``SKILL.md``; ``materialize_helper_skills()`` searches both ``common/``
and dimension directories to resolve a skill name to its ``SKILL.md`` content.

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

__all__ = ["SCHEMA_FILENAME", "WorkdirManager", "discover_common_skills"]

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
        """Copy evaluator skill ``SKILL.md`` docs and scripts into the workdir.

        Searches ``evaluator_skills/common/<name>/SKILL.md`` first, then
        dimension-specific directories ``evaluator_skills/<dim>/<name>/SKILL.md``.
        For each skill, also copies any ``.py`` scripts in the same directory
        so the judge agent can execute them via ``Bash``.
        Raises ``FileNotFoundError`` if a skill cannot be resolved.
        """
        if not names:
            return
        skills_root = files("evo_agent.evaluator.agent_judge.evaluator_skills")
        for name in names:
            source = _find_skill_md(skills_root, name)
            if source is None:
                raise FileNotFoundError(f"evaluator skill not found: {name!r}")
            (self.path / f"{name}.md").write_text(
                source.read_text(encoding="utf-8"), encoding="utf-8"
            )
            # Also mount sibling .py scripts from the same skill directory
            skill_dir = source.parent
            for entry in skill_dir.iterdir():
                if entry.is_file() and entry.name.endswith(".py"):
                    (self.path / entry.name).write_text(
                        entry.read_text(encoding="utf-8"), encoding="utf-8"
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


# ---------------------------------------------------------------------------
# Module-level helpers
# ---------------------------------------------------------------------------

_EVALUATOR_SKILLS_PKG = "evo_agent.evaluator.agent_judge.evaluator_skills"


def _find_skill_md(
    skills_root: Any,
    name: str,
) -> Any | None:
    """Search for ``<name>/SKILL.md`` across common/ and dimension directories.

    Returns the ``importlib.resources`` Traversable for the first match, or
    ``None`` if no directory contains ``<name>/SKILL.md``.  ``common/`` is
    checked first so universal skills take precedence.
    """
    # 1. common/<name>/SKILL.md
    common_dir = skills_root.joinpath("common")
    if common_dir.is_dir():
        candidate = common_dir.joinpath(name, "SKILL.md")
        if candidate.is_file():
            return candidate
    # 2. <dimension>/<name>/SKILL.md — scan all top-level dirs except common/
    for entry in sorted(skills_root.iterdir(), key=lambda e: e.name):
        if entry.is_dir() and entry.name != "common":
            candidate = entry.joinpath(name, "SKILL.md")
            if candidate.is_file():
                return candidate
    return None


def discover_common_skills() -> list[str]:
    """Scan ``evaluator_skills/common/`` and return names of skills with a SKILL.md.

    Each subdirectory of ``common/`` that contains a ``SKILL.md`` file is
    considered a common evaluator skill.  The returned list is sorted for
    deterministic mounting order.
    """
    common_root = files(f"{_EVALUATOR_SKILLS_PKG}.common")
    result: list[str] = []
    for entry in sorted(common_root.iterdir(), key=lambda e: e.name):
        if entry.is_dir() and entry.joinpath("SKILL.md").is_file():
            result.append(entry.name)
    return result
