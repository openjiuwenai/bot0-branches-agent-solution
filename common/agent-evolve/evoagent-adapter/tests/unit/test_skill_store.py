"""Unit tests for SkillStore."""

from pathlib import Path

import pytest

from agent_adapter.skill_store import (
    AgentNotFoundError,
    InvalidSkillNameError,
    SkillNotFoundError,
    SkillStore,
)


@pytest.fixture
def store(tmp_path: Path) -> SkillStore:
    root = tmp_path / "skills"
    edp = root / "edp_agent"
    edp.mkdir(parents=True)
    skill_dir = edp / "demo_skill"
    skill_dir.mkdir()
    (skill_dir / "SKILL.md").write_text("# Demo\n", encoding="utf-8")
    return SkillStore(
        skills_root=root,
        agent_skills_dirs={"edp_agent": edp},
    )


def test_list_skills(store: SkillStore) -> None:
    names = [s.name for s in store.list_skills("edp_agent")]
    assert names == ["demo_skill"]


def test_read_skill(store: SkillStore) -> None:
    doc = store.read_skill("edp_agent", "demo_skill")
    assert doc.skill_name == "demo_skill"
    assert doc.content == "# Demo\n"
    assert len(doc.revision) == 64


def test_update_skill_atomic(store: SkillStore) -> None:
    result = store.update_skill("edp_agent", "demo_skill", "# Updated\n")
    assert result.success is True
    assert store.read_skill("edp_agent", "demo_skill").content == "# Updated\n"
    assert store.get_revision("edp_agent", "demo_skill") == result.revision


def test_unknown_agent(store: SkillStore) -> None:
    with pytest.raises(AgentNotFoundError):
        store.list_skills("missing")


def test_missing_skill(store: SkillStore) -> None:
    with pytest.raises(SkillNotFoundError):
        store.read_skill("edp_agent", "no_such_skill")


def test_update_skill_requires_existing_file(store: SkillStore) -> None:
    with pytest.raises(SkillNotFoundError):
        store.update_skill("edp_agent", "no_such_skill", "# New\n")


def test_restore_skills(store: SkillStore) -> None:
    store.update_skill("edp_agent", "demo_skill", "# Updated\n")
    results = store.restore_skills("edp_agent", ["demo_skill"])
    assert results[0].success is True
    assert store.read_skill("edp_agent", "demo_skill").content == "# Demo\n"


def test_restore_skills_no_snapshot(store: SkillStore) -> None:
    results = store.restore_skills("edp_agent", ["demo_skill"])
    assert results[0].success is False
    assert results[0].message == "未找到快照：该 Skill 未被更新过"


def test_invalid_skill_name(store: SkillStore) -> None:
    with pytest.raises(InvalidSkillNameError):
        store.update_skill("edp_agent", "../escape", "x")
