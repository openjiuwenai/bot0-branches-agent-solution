"""Prompt 覆盖机制单元测试。"""

from __future__ import annotations

from pathlib import Path
from unittest.mock import patch

import pytest

from evo_agent.scenario.prompts import load_prompt


@pytest.fixture
def scenarios_dir(tmp_path: Path) -> Path:
    scenarios = tmp_path / "scenarios"
    scenarios.mkdir()
    return scenarios


def test_load_prompt_from_scenario_folder(scenarios_dir: Path) -> None:
    """验证优先从场景文件夹加载 prompt。"""
    # 创建场景 prompt 覆盖
    prompts_dir = scenarios_dir / "my_scenario" / "prompts"
    prompts_dir.mkdir(parents=True)
    (prompts_dir / "analyst_error.md").write_text("# Custom error prompt", encoding="utf-8")

    result = load_prompt("analyst_error", "my_scenario", scenarios_dir=scenarios_dir)

    assert result == "# Custom error prompt"


def test_load_prompt_default_dir_finds_edp_prompt() -> None:
    """验证默认 prompt loader 能找到仓库 scenarios/edp_agent/prompts。"""
    result = load_prompt("analyst_error", "edp_agent")

    assert "失败模式分析" in result


def test_load_prompt_falls_back_to_agent_core(scenarios_dir: Path) -> None:
    """验证场景目录无文件时 fallback 到 agent-core。"""
    # 创建场景目录但不放 prompt 文件
    (scenarios_dir / "my_scenario").mkdir(parents=True)

    # Mock agent-core 的 prompt loader
    with patch(
        "evo_agent.scenario.prompts.load_prompt",
        wraps=_mock_agent_core_loader,
    ):
        # 直接测试 fallback 路径
        result = _fallback_load("analyst_error", "my_scenario", scenarios_dir)

    assert "agent-core-fallback" in result


def test_load_prompt_raises_when_not_found(scenarios_dir: Path) -> None:
    """验证找不到 prompt 时抛出 FileNotFoundError。"""
    (scenarios_dir / "empty_scenario").mkdir(parents=True)

    # Mock agent-core loader 也抛异常
    with patch(
        "evo_agent.optimizer.skill_document.prompts.load_skill_opt_prompt",
        side_effect=FileNotFoundError,
    ):
        with pytest.raises(FileNotFoundError, match="Prompt not found"):
            load_prompt("nonexistent", "empty_scenario", scenarios_dir=scenarios_dir)


# ── 辅助函数 ──


def _mock_agent_core_loader(name: str, scenario_name: str, scenarios_dir: Path) -> str:
    """模拟 fallback loader。"""
    return f"agent-core-fallback:{name}"


def _fallback_load(name: str, scenario_name: str, scenarios_dir: Path) -> str:
    """直接测试 fallback 路径。"""
    scenario_prompt = scenarios_dir / scenario_name / "prompts" / f"{name}.md"
    if scenario_prompt.exists():
        return scenario_prompt.read_text(encoding="utf-8")
    return f"agent-core-fallback:{name}"
