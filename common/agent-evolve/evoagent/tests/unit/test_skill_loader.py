"""SkillLoader 单元测试。"""

from evo_agent.skill_loader import SkillLoader


def test_load_existing_skill(sample_skill_dir):
    content = SkillLoader.load(sample_skill_dir)
    assert "Test Skill" in content


def test_load_missing_skill(tmp_path):
    import pytest

    with pytest.raises(FileNotFoundError):
        SkillLoader.load(tmp_path / "nonexistent")
