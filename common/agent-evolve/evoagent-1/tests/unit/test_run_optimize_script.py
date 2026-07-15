"""run_optimize.py resolve_params() 单元测试 — 参数优先级解析。

使用 importlib.util 从文件路径加载模块（skills/ 目录无 __init__.py）。
"""

from __future__ import annotations

import argparse
import importlib.util
from pathlib import Path
from unittest.mock import patch

import pytest


def _load_run_optimize():  # type: ignore[no-untyped-def]
    """从文件路径加载 run_optimize 模块。"""
    path = (
        Path(__file__).resolve().parents[2]
        / "skills"
        / "optimize_skill"
        / "scripts"
        / "run_optimize.py"
    )
    spec = importlib.util.spec_from_file_location("run_optimize", path)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_run_optimize = _load_run_optimize()
resolve_params = _run_optimize.resolve_params
ResolveParamsError = _run_optimize.ResolveParamsError


def _make_args(**overrides: object) -> argparse.Namespace:
    """构造 argparse.Namespace 用于测试 resolve_params。"""
    defaults: dict[str, object] = {
        "scenario": "edp_agent",
        "dataset_manifest": Path("data/dataset.yaml"),
        "adapter_url": None,
        "skills": None,
        "agent_name": None,
        "epochs": 3,
        "batch_size": 4,
    }
    defaults.update(overrides)
    return argparse.Namespace(**defaults)


def _make_config(**overrides: object):  # type: ignore[no-untyped-def]
    """构造 ScenarioConfig 用于测试。"""
    from evo_agent.scenario.registry import ScenarioConfig

    defaults: dict[str, object] = {
        "optimizer_class": "optimizer.X",
        "adapter_url": "http://yaml-host:9090",
        "skills": [
            {"name": "skill_a", "optimize": True},
            {"name": "skill_b", "optimize": False},
            {"name": "skill_c", "optimize": True},
        ],
    }
    defaults.update(overrides)
    return ScenarioConfig(**defaults)  # type: ignore[arg-type]


# ── adapter_url 优先级 ──


def test_resolve_adapter_url_from_cli() -> None:
    """CLI 指定 --adapter-url 时优先使用 CLI 值。"""
    args = _make_args(adapter_url="http://cli-host:9090")
    config = _make_config(adapter_url="http://yaml-host:9090")

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        request = resolve_params(args)

    assert request.adapter_url == "http://cli-host:9090"


def test_resolve_adapter_url_from_yaml() -> None:
    """CLI 未指定 --adapter-url 时使用 scenario.yaml 值。"""
    args = _make_args()  # adapter_url=None
    config = _make_config(adapter_url="http://yaml-host:9090")

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        request = resolve_params(args)

    assert request.adapter_url == "http://yaml-host:9090"


def test_resolve_adapter_url_missing_all(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """三处均未指定 adapter_url 时抛出 ResolveParamsError。"""
    args = _make_args()  # adapter_url=None
    config = _make_config(adapter_url="")

    # 确保 EvolveConfig 也没有 adapter_url
    monkeypatch.setenv("EVO_ADAPTER_URL", "")
    from evo_agent.config import EvolveConfig

    EvolveConfig.get.cache_clear()

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        with pytest.raises(ResolveParamsError):
            resolve_params(args)

    # 清理
    EvolveConfig.get.cache_clear()


def test_resolve_adapter_url_invalid_format() -> None:
    """adapter_url 无 http/https 前缀时抛出 ResolveParamsError。"""
    args = _make_args(adapter_url="ftp://invalid:9090")
    config = _make_config()

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        with pytest.raises(ResolveParamsError):
            resolve_params(args)


# ── skills 优先级 ──


def test_resolve_skills_from_cli() -> None:
    """CLI 指定 --skills 时覆盖 yaml。"""
    args = _make_args(skills="custom_a,custom_b")
    config = _make_config()

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        request = resolve_params(args)

    assert request.skills == ["custom_a", "custom_b"]


def test_resolve_skills_from_yaml() -> None:
    """CLI 未指定 --skills 时使用 optimize=true 列表。"""
    args = _make_args()  # skills=None
    config = _make_config()

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        request = resolve_params(args)

    assert request.skills == ["skill_a", "skill_c"]


def test_resolve_skills_empty_yaml() -> None:
    """yaml 中无 skills 时返回空列表。"""
    args = _make_args()  # skills=None
    config = _make_config(skills=[])

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        request = resolve_params(args)

    assert request.skills == []


def test_resolve_skills_empty_string_override() -> None:
    """CLI --skills "" 显式传入空串时覆盖 yaml，返回空列表。"""
    args = _make_args(skills="")
    config = _make_config()

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        request = resolve_params(args)

    assert request.skills == []


# ── agent_name 优先级 ──


def test_resolve_agent_name_default() -> None:
    """CLI 未指定 --agent-name 时使用 scenario 名称。"""
    args = _make_args()  # agent_name=None
    config = _make_config()

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        request = resolve_params(args)

    assert request.agent_name == "edp_agent"


def test_resolve_agent_name_from_cli() -> None:
    """CLI 指定 --agent-name 时优先使用 CLI 值。"""
    args = _make_args(agent_name="custom_agent")
    config = _make_config()

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        request = resolve_params(args)

    assert request.agent_name == "custom_agent"


# ── 错误传播 ──


def test_resolve_scenario_not_found() -> None:
    """scenario.yaml 不存在时 FileNotFoundError 向上抛出。"""
    args = _make_args(scenario="nonexistent_scenario")

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.side_effect = FileNotFoundError(
            "Scenario config not found",
        )
        with pytest.raises(FileNotFoundError):
            resolve_params(args)


# ── 完整集成 ──


def test_resolve_full_scenario_config() -> None:
    """完整 scenario.yaml 的所有参数正确填充。"""
    args = _make_args(
        scenario="my_scenario",
        adapter_url="http://cli:9090",
        skills="s1,s2",
        agent_name="my_agent",
        epochs=5,
        batch_size=8,
        dataset_manifest=Path("data/custom.yaml"),
    )
    config = _make_config()

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        request = resolve_params(args)

    assert request.scenario == "my_scenario"
    assert request.adapter_url == "http://cli:9090"
    assert request.skills == ["s1", "s2"]
    assert request.agent_name == "my_agent"
    assert request.num_epochs == 5
    assert request.batch_size == 8
    assert request.dataset_manifest_path == Path("data/custom.yaml")


# --- W8.8: 新 OptimizeRequest 字段 ---


def test_cli_constructs_request_with_manifest() -> None:
    """CLI 构建的请求 dataset_manifest_path 非 None。"""
    args = _make_args()
    config = _make_config()

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        request = resolve_params(args)

    assert request.dataset_manifest_path == Path("data/dataset.yaml")
    assert request.dataset_path == ""
    assert request.dataset_manifest_path is not None


def test_cli_adapter_url_from_config(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """CLI 和 scenario.yaml 均无 adapter_url 时，从 EvolveConfig 兜底。"""
    args = _make_args()  # adapter_url=None
    config = _make_config(adapter_url="")  # yaml 也没有

    # 设置 EVO_ADAPTER_URL 环境变量
    monkeypatch.setenv("EVO_ADAPTER_URL", "http://config-host:9090")
    # 清除 lru_cache
    from evo_agent.config import EvolveConfig

    EvolveConfig.get.cache_clear()

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        request = resolve_params(args)

    assert request.adapter_url == "http://config-host:9090"

    # 清理
    EvolveConfig.get.cache_clear()


def test_cli_task_name_format() -> None:
    """task_name 格式为 'cli-{scenario}'。"""
    args = _make_args(scenario="my_scenario")
    config = _make_config()

    with patch.object(_run_optimize, "ScenarioRegistry") as mock_reg:
        mock_reg.return_value.load_scenario_config.return_value = config
        request = resolve_params(args)

    assert request.task_name == "cli-my_scenario"
