"""测试公共 fixture。"""

from pathlib import Path

import pytest


@pytest.fixture
def sample_skill_dir(tmp_path: Path) -> Path:
    """创建一个包含 SKILL.md 的临时 skill 目录。"""
    skill_dir = tmp_path / "test_skill"
    skill_dir.mkdir()
    (skill_dir / "SKILL.md").write_text("# Test Skill\n\nA test skill.", encoding="utf-8")
    return skill_dir


@pytest.fixture
def sample_dataset_yaml(tmp_path: Path) -> Path:
    """创建一个临时 dataset.yaml。"""
    yaml_content = """\
schema_version: "1.0"
name: test_dataset
cases: items.json
train_split: 0.8
seed: 0

evaluator:
  dotted_path: openjiuwen.agent_evolving.evaluator.MetricEvaluator
  kwargs:
    metric: exact_match
"""
    yaml_path = tmp_path / "dataset.yaml"
    yaml_path.write_text(yaml_content, encoding="utf-8")
    return yaml_path
