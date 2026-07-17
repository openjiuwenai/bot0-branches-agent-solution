"""Runtime config resolver tests."""

from __future__ import annotations

from pathlib import Path

import pytest

from evo_agent.config import EvolveConfig
from evo_agent.runtime_config import OptimizationConfigResolver
from evo_agent.scenario.registry import ScenarioRegistry
from evo_agent.types import OptimizeRequest


@pytest.fixture
def scenarios_dir(tmp_path: Path) -> Path:
    scenarios = tmp_path / "scenarios"
    scenario_dir = scenarios / "edp_agent"
    scenario_dir.mkdir(parents=True)
    (scenario_dir / "scenario.yaml").write_text(
        """\
schema_version: "1.0"
optimizer_class: optimizer.EDPOpt
adapter_url: "http://scenario-adapter:9090"
rollout:
  extra_data:
    role_id: "1"
    role_name: "mobile-bank"
hyperparams:
  num_epochs: 5
  batch_size: 8
  accumulation: 2
  minibatch_size: 4
  edit_budget: 7
  scheduler_mode: cosine
  update_mode: patch
  score_threshold: 0.6
  parallelism: 4
  num_parallel: 6
  use_slow_update: true
  use_meta_skill: false
""",
        encoding="utf-8",
    )
    return scenarios


def _config(tmp_path: Path) -> EvolveConfig:
    return EvolveConfig(
        adapter_url="http://config-adapter:9090",
        artifact_dir=tmp_path / "artifacts",
        default_epochs=3,
        default_batch_size=4,
        accumulation=1,
        minibatch_size=2,
        edit_budget=3,
        scheduler_mode="constant",
        update_mode="patch",
        score_threshold=0.5,
        parallelism=2,
        remote_parallel=2,
        use_slow_update=False,
        use_meta_skill=True,
    )


def _resolver(tmp_path: Path, scenarios_dir: Path) -> OptimizationConfigResolver:
    return OptimizationConfigResolver(
        _config(tmp_path),
        registry=ScenarioRegistry(scenarios_dir=scenarios_dir),
    )


def test_resolve_uses_scenario_defaults_before_config(tmp_path: Path, scenarios_dir: Path) -> None:
    resolved = _resolver(tmp_path, scenarios_dir).resolve(
        OptimizeRequest(scenario="edp_agent", agent_name="agent")
    )

    assert resolved.num_epochs == 5
    assert resolved.batch_size == 8
    assert resolved.accumulation == 2
    assert resolved.num_parallel == 6
    assert resolved.adapter_url == "http://scenario-adapter:9090"
    assert resolved.rollout_extra_data == {"role_id": "1", "role_name": "mobile-bank"}


def test_request_overrides_scenario_defaults(tmp_path: Path, scenarios_dir: Path) -> None:
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="agent",
        adapter_url="http://request-adapter:9090",
        num_epochs=2,
        batch_size=3,
        hyperparams={
            "num_parallel": 9,
            "parallelism": 8,
            "custom_param": "kept",
        },
        rollout_extra_data={"role_name": "override", "tenant": "test"},
    )

    resolved = _resolver(tmp_path, scenarios_dir).resolve(request)

    assert resolved.adapter_url == "http://request-adapter:9090"
    assert resolved.num_epochs == 2
    assert resolved.batch_size == 3
    assert resolved.num_parallel == 9
    assert resolved.parallelism == 8
    assert resolved.rollout_extra_data == {
        "role_id": "1",
        "role_name": "override",
        "tenant": "test",
    }
    assert resolved.extra_hyperparams == {"custom_param": "kept"}


def test_config_is_last_resort(tmp_path: Path) -> None:
    scenarios = tmp_path / "scenarios"
    scenario_dir = scenarios / "minimal"
    scenario_dir.mkdir(parents=True)
    (scenario_dir / "scenario.yaml").write_text(
        """\
schema_version: "1.0"
optimizer_class: optimizer.SimpleOpt
""",
        encoding="utf-8",
    )

    resolved = OptimizationConfigResolver(
        _config(tmp_path),
        registry=ScenarioRegistry(scenarios_dir=scenarios),
    ).resolve(OptimizeRequest(scenario="minimal", agent_name="agent"))

    assert resolved.num_epochs == 3
    assert resolved.batch_size == 4
    assert resolved.accumulation == 1
    assert resolved.num_parallel == 2
    assert resolved.adapter_url == "http://config-adapter:9090"


def test_invalid_explicit_value_fails_at_resolve(tmp_path: Path, scenarios_dir: Path) -> None:
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="agent",
        hyperparams={"num_parallel": 0},
    )

    with pytest.raises(ValueError, match="num_parallel"):
        _resolver(tmp_path, scenarios_dir).resolve(request)


# ── preserve_frontmatter 透传链 B（config → resolver） ──


def test_evolve_config_preserve_frontmatter_defaults_true() -> None:
    """默认 preserve_frontmatter=True（与现状冻结行为一致）。"""
    assert EvolveConfig().preserve_frontmatter is True


def test_evolve_config_preserve_frontmatter_env_overrides(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """EVO_PRESERVE_FRONTMATTER=false 能翻转为 False。"""
    monkeypatch.setenv("EVO_PRESERVE_FRONTMATTER", "false")
    assert EvolveConfig().preserve_frontmatter is False


def test_resolve_preserve_frontmatter_defaults_from_config(
    tmp_path: Path, scenarios_dir: Path
) -> None:
    """无 hyperparams 覆盖时，resolved 取 config 默认（True）。"""
    resolved = _resolver(tmp_path, scenarios_dir).resolve(
        OptimizeRequest(scenario="edp_agent", agent_name="agent")
    )
    assert resolved.preserve_frontmatter is True


def test_resolve_preserve_frontmatter_scenario_hyperparam_overrides(
    tmp_path: Path, scenarios_dir: Path
) -> None:
    """scenario hyperparams 的 preserve_frontmatter 覆盖 config 默认。"""
    resolved = _resolver(tmp_path, scenarios_dir).resolve(
        OptimizeRequest(
            scenario="edp_agent",
            agent_name="agent",
            hyperparams={"preserve_frontmatter": False},
        )
    )
    assert resolved.preserve_frontmatter is False


def test_resolve_preserve_frontmatter_not_in_extra_hyperparams(
    tmp_path: Path, scenarios_dir: Path
) -> None:
    """preserve_frontmatter 是受管字段，不应泄漏到 extra_hyperparams。"""
    resolved = _resolver(tmp_path, scenarios_dir).resolve(
        OptimizeRequest(
            scenario="edp_agent",
            agent_name="agent",
            hyperparams={"preserve_frontmatter": False},
        )
    )
    assert "preserve_frontmatter" not in resolved.extra_hyperparams


def test_optimizer_runtime_dependencies_contains_preserve_frontmatter(
    tmp_path: Path, scenarios_dir: Path
) -> None:
    """optimizer_runtime_dependencies() 透出 preserve_frontmatter key。"""
    resolved = _resolver(tmp_path, scenarios_dir).resolve(
        OptimizeRequest(scenario="edp_agent", agent_name="agent")
    )
    deps = resolved.optimizer_runtime_dependencies()
    assert "preserve_frontmatter" in deps
    assert deps["preserve_frontmatter"] is True
