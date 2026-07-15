"""ScenarioConfig 单元测试 — 配置解析 + skill 过滤。"""

from __future__ import annotations

from pathlib import Path

import pytest

from evo_agent.scenario.registry import ScenarioConfig, ScenarioRegistry

# ── helpers ──


@pytest.fixture
def scenarios_dir(tmp_path: Path) -> Path:
    """创建一个临时 scenarios 目录。"""
    scenarios = tmp_path / "scenarios"
    scenarios.mkdir()
    return scenarios


def _write_yaml(scenarios_dir: Path, name: str, content: str) -> None:
    folder = scenarios_dir / name
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "scenario.yaml").write_text(content, encoding="utf-8")


# ── ScenarioConfig dataclass ──


def test_scenario_config_fields() -> None:
    """ScenarioConfig 包含 optimizer_class, hyperparams, adapter_url, skills, rollout。"""
    cfg = ScenarioConfig(
        optimizer_class="optimizer.MyOpt",
        hyperparams={"batch_size": 8},
        adapter_url="http://localhost:9090",
        skills=[{"name": "skill_a", "optimize": True}],
        rollout={"max_turns": 10},
    )
    assert cfg.optimizer_class == "optimizer.MyOpt"
    assert cfg.hyperparams == {"batch_size": 8}
    assert cfg.adapter_url == "http://localhost:9090"
    assert cfg.skills == [{"name": "skill_a", "optimize": True}]
    assert cfg.rollout == {"max_turns": 10}


def test_scenario_config_frozen() -> None:
    """ScenarioConfig 是 frozen dataclass，不可变。"""
    cfg = ScenarioConfig(optimizer_class="optimizer.MyOpt")
    with pytest.raises(AttributeError):
        cfg.optimizer_class = "other"  # type: ignore[misc]


def test_scenario_config_defaults() -> None:
    """adapter_url 默认空字符串，skills/rollout/hyperparams 默认空。"""
    cfg = ScenarioConfig(optimizer_class="optimizer.MyOpt")
    assert cfg.adapter_url == ""
    assert cfg.skills == []
    assert cfg.rollout == {}
    assert cfg.hyperparams == {}


# ── load_scenario_config() ──


_FULL_YAML = """\
schema_version: "1.0"
optimizer_class: optimizer.EDPOpt
adapter_url: "http://localhost:9090"
skills:
  - name: product_recommend_skill
    optimize: true
  - name: fund_planning_skill
    optimize: false
rollout:
  max_turns: 10
  extra_data:
    role_id: "1"
hyperparams:
  batch_size: 8
  num_parallel: 8
"""


def test_load_scenario_config_full(scenarios_dir: Path) -> None:
    """解析包含全部字段的 yaml。"""
    _write_yaml(scenarios_dir, "full", _FULL_YAML)

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    cfg = registry.load_scenario_config("full")

    assert cfg.optimizer_class == "optimizer.EDPOpt"
    assert cfg.adapter_url == "http://localhost:9090"
    assert len(cfg.skills) == 2
    assert cfg.rollout == {"max_turns": 10, "extra_data": {"role_id": "1"}}
    assert cfg.hyperparams == {"batch_size": 8, "num_parallel": 8}


def test_load_scenario_config_minimal(scenarios_dir: Path) -> None:
    """只有 schema_version + optimizer_class + hyperparams。"""
    _write_yaml(
        scenarios_dir,
        "minimal",
        """\
schema_version: "1.0"
optimizer_class: optimizer.SimpleOpt
hyperparams:
  batch_size: 4
""",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    cfg = registry.load_scenario_config("minimal")

    assert cfg.optimizer_class == "optimizer.SimpleOpt"
    assert cfg.hyperparams == {"batch_size": 4}
    assert cfg.adapter_url == ""
    assert cfg.skills == []


def test_scenario_config_missing_version(scenarios_dir: Path) -> None:
    """schema_version 缺失时抛 ValueError。"""
    _write_yaml(
        scenarios_dir,
        "no_ver",
        """\
optimizer_class: optimizer.MyOpt
hyperparams:
  batch_size: 4
""",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    with pytest.raises(ValueError, match="schema_version"):
        registry.load_scenario_config("no_ver")


def test_scenario_config_wrong_version(scenarios_dir: Path) -> None:
    """schema_version 不是 '1.0' 时抛 ValueError。"""
    _write_yaml(
        scenarios_dir,
        "bad_ver",
        """\
schema_version: "2.0"
optimizer_class: optimizer.MyOpt
""",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    with pytest.raises(ValueError, match="schema_version"):
        registry.load_scenario_config("bad_ver")


# ── get_optimize_skills() ──


def test_get_optimize_skills_filters_false() -> None:
    """optimize: false 的 skill 不出现在返回列表。"""
    cfg = ScenarioConfig(
        optimizer_class="optimizer.MyOpt",
        skills=[
            {"name": "skill_a", "optimize": True},
            {"name": "skill_b", "optimize": False},
            {"name": "skill_c", "optimize": True},
        ],
    )
    result = cfg.get_optimize_skills()
    assert result == ["skill_a", "skill_c"]


def test_get_optimize_skills_all_true() -> None:
    """全部 optimize: true 时返回所有 skill 名。"""
    cfg = ScenarioConfig(
        optimizer_class="optimizer.MyOpt",
        skills=[
            {"name": "skill_a", "optimize": True},
            {"name": "skill_b", "optimize": True},
        ],
    )
    result = cfg.get_optimize_skills()
    assert result == ["skill_a", "skill_b"]


def test_get_optimize_skills_empty() -> None:
    """无 skills 字段时返回空列表。"""
    cfg = ScenarioConfig(optimizer_class="optimizer.MyOpt")
    result = cfg.get_optimize_skills()
    assert result == []
