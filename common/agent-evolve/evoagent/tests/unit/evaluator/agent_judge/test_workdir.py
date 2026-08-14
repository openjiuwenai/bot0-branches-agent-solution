"""WorkdirManager 单元测试 — 临时目录生命周期 + 物化。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from evo_agent.evaluator.agent_judge.schemas import dimension_judgment_json_schema
from evo_agent.evaluator.agent_judge.workdir import SCHEMA_FILENAME, WorkdirManager
from evo_agent.evaluator.domain.models import StandardTrajectory

_TRAJECTORY = StandardTrajectory.model_validate(
    {"messages": [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "yo"}]}
)


class TestWorkdirManager:
    def test_creates_and_cleans_up(self, tmp_path: Path) -> None:
        with WorkdirManager(base_dir=str(tmp_path)) as wd:
            p = wd.path
            assert p.exists()
            assert p.parent == tmp_path
            assert p.name.startswith("evo-agent-judge-")
        assert not p.exists()

    def test_used_outside_context_raises(self) -> None:
        wd = WorkdirManager()
        with pytest.raises(RuntimeError, match="outside its context manager"):
            _ = wd.path

    def test_keep_on_error_preserves_dir(self, tmp_path: Path) -> None:
        with pytest.raises(ValueError, match="boom"):
            with WorkdirManager(base_dir=str(tmp_path), keep_on_error=True) as wd:
                raise ValueError("boom")
        assert wd.path.exists()
        # manual cleanup so tmp_path stays tidy
        import shutil

        shutil.rmtree(wd.path, ignore_errors=True)

    def test_clean_exit_still_cleans_with_keep_on_error(self, tmp_path: Path) -> None:
        # keep_on_error only retains on exception; clean exit always cleans.
        with WorkdirManager(base_dir=str(tmp_path), keep_on_error=True) as wd:
            p = wd.path
            pass
        assert not p.exists()

    def test_materialize_trajectory(self, tmp_path: Path) -> None:
        with WorkdirManager(base_dir=str(tmp_path)) as wd:
            wd.materialize_trajectory(_TRAJECTORY, compacted_text="## summary")
            jsonl = (wd.path / "trajectory.jsonl").read_text(encoding="utf-8")
            lines = [ln for ln in jsonl.splitlines() if ln]
            assert len(lines) == 2
            assert json.loads(lines[0])["role"] == "user"
            assert json.loads(lines[1])["content"] == "yo"
            md = (wd.path / "trajectory.md").read_text(encoding="utf-8")
            assert md == "## summary"

    def test_materialize_trajectory_dict_input(self, tmp_path: Path) -> None:
        traj_dict = {"messages": [{"role": "assistant", "content": "x"}], "summary": "s"}
        with WorkdirManager(base_dir=str(tmp_path)) as wd:
            wd.materialize_trajectory(traj_dict, compacted_text="c")
            assert (wd.path / "trajectory.jsonl").exists()

    def test_materialize_helper_skills(self, tmp_path: Path) -> None:
        # judge_rubric_guide ships with the package (registered default helper).
        with WorkdirManager(base_dir=str(tmp_path)) as wd:
            wd.materialize_helper_skills(("judge_rubric_guide",))
            content = (wd.path / "judge_rubric_guide.md").read_text(encoding="utf-8")
            assert content.strip()

    def test_materialize_helper_skills_missing_raises(self, tmp_path: Path) -> None:
        with WorkdirManager(base_dir=str(tmp_path)) as wd:
            with pytest.raises(FileNotFoundError, match="helper skill not found"):
                wd.materialize_helper_skills(("definitely_not_a_helper",))

    def test_materialize_helper_skills_empty_noop(self, tmp_path: Path) -> None:
        with WorkdirManager(base_dir=str(tmp_path)) as wd:
            wd.materialize_helper_skills(())  # no files written, no error
            assert wd.path.exists()

    def test_write_schema(self, tmp_path: Path) -> None:
        schema = dimension_judgment_json_schema()
        with WorkdirManager(base_dir=str(tmp_path)) as wd:
            path = wd.write_schema(schema)
            assert path.name == SCHEMA_FILENAME
            assert path.parent == wd.path
            loaded = json.loads(path.read_text(encoding="utf-8"))
            assert loaded == schema
