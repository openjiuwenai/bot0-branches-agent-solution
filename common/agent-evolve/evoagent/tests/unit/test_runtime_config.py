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


def test_reliability_and_llm_budget_are_typed_runtime_contracts(
    tmp_path: Path, scenarios_dir: Path
) -> None:
    resolved = _resolver(tmp_path, scenarios_dir).resolve(
        OptimizeRequest(
            scenario="edp_agent",
            agent_name="agent",
            hyperparams={
                "validation_max_case_attempts": 3,
                "validation_min_success_ratio": 0.9,
                "validation_require_same_case_set": False,
            },
        )
    )

    assert resolved.validation_max_case_attempts == 3
    assert resolved.validation_min_success_ratio == 0.9
    assert resolved.validation_require_same_case_set is False
    assert resolved.llm_context_window_tokens == 32768
    assert resolved.llm_output_reserve_tokens == 2048
    assert resolved.llm_safety_margin_tokens == 512
    assert resolved.llm_chars_per_token == 2.0


def test_llm_budget_uses_request_then_scenario_then_environment(tmp_path: Path) -> None:
    """LLM 预算字段遵循统一三层优先级，且不会泄漏到 extra_hyperparams。"""
    scenarios = tmp_path / "scenarios"
    scenario_dir = scenarios / "llm_budget"
    scenario_dir.mkdir(parents=True)
    (scenario_dir / "scenario.yaml").write_text(
        """\
schema_version: "1.0"
optimizer_class: optimizer.SimpleOpt
hyperparams:
  llm_context_window_tokens: 65536
  llm_output_reserve_tokens: 4096
  llm_safety_margin_tokens: 256
  llm_chars_per_token: 1.5
  llm_stage_output_reserve_tokens:
    evaluator: 1600
""",
        encoding="utf-8",
    )
    resolver = OptimizationConfigResolver(
        _config(tmp_path),
        registry=ScenarioRegistry(scenarios_dir=scenarios),
    )

    scenario_resolved = resolver.resolve(OptimizeRequest(scenario="llm_budget", agent_name="agent"))
    request_resolved = resolver.resolve(
        OptimizeRequest(
            scenario="llm_budget",
            agent_name="agent",
            hyperparams={
                "llm_context_window_tokens": 131072,
                "llm_output_reserve_tokens": 8192,
                "llm_safety_margin_tokens": 1024,
                "llm_chars_per_token": 3.0,
                "llm_stage_output_reserve_tokens": {"reflect": 5000},
            },
        )
    )

    assert scenario_resolved.llm_context_window_tokens == 65536
    assert scenario_resolved.llm_stage_output_reserve_tokens == {"evaluator": 1600}
    assert request_resolved.llm_context_window_tokens == 131072
    assert request_resolved.llm_output_reserve_tokens == 8192
    assert request_resolved.llm_safety_margin_tokens == 1024
    assert request_resolved.llm_chars_per_token == 3.0
    assert request_resolved.llm_stage_output_reserve_tokens == {"reflect": 5000}
    assert (
        not {
            "llm_context_window_tokens",
            "llm_output_reserve_tokens",
            "llm_safety_margin_tokens",
            "llm_chars_per_token",
            "llm_stage_output_reserve_tokens",
        }
        & request_resolved.extra_hyperparams.keys()
    )


def test_invalid_explicit_value_fails_at_resolve(tmp_path: Path, scenarios_dir: Path) -> None:
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="agent",
        hyperparams={"num_parallel": 0},
    )

    with pytest.raises(ValueError, match="num_parallel"):
        _resolver(tmp_path, scenarios_dir).resolve(request)


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("llm_context_window_tokens", 0),
        ("llm_output_reserve_tokens", 0),
        ("llm_safety_margin_tokens", -1),
        ("llm_chars_per_token", 0),
        ("llm_stage_output_reserve_tokens", {"evaluator": 0}),
    ],
)
def test_invalid_llm_budget_boundary_fails_at_resolve(
    tmp_path: Path,
    scenarios_dir: Path,
    name: str,
    value: object,
) -> None:
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="agent",
        hyperparams={name: value},
    )

    with pytest.raises(ValueError, match=name):
        _resolver(tmp_path, scenarios_dir).resolve(request)


@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf")])
def test_non_finite_llm_budget_fails_at_resolve(
    tmp_path: Path,
    scenarios_dir: Path,
    value: float,
) -> None:
    request = OptimizeRequest(
        scenario="edp_agent",
        agent_name="agent",
        hyperparams={"llm_chars_per_token": value},
    )

    with pytest.raises(ValueError, match="llm_chars_per_token"):
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


# ── managed-doc: F2 透传 managed_doc_kind ──


def test_resolve_passes_managed_doc_kind(tmp_path: Path, scenarios_dir: Path) -> None:
    """managed_doc_kind 经 resolve 抵达 ResolvedOptimizationConfig（runner builder 直接消费）。"""
    resolved = _resolver(tmp_path, scenarios_dir).resolve(
        OptimizeRequest(
            scenario="edp_agent",
            agent_name="agent",
            managed_doc_kind="agent_rule",
        )
    )
    assert resolved.managed_doc_kind == "agent_rule"


def test_resolve_passes_managed_doc_expected_revision(tmp_path: Path, scenarios_dir: Path) -> None:
    resolved = _resolver(tmp_path, scenarios_dir).resolve(
        OptimizeRequest(
            scenario="edp_agent",
            agent_name="agent",
            managed_doc_kind="agent_rule",
            managed_doc_expected_revision="rev-studio-baseline",
        )
    )

    assert resolved.managed_doc_expected_revision == "rev-studio-baseline"


def test_resolve_managed_doc_kind_default_none(tmp_path: Path, scenarios_dir: Path) -> None:
    """不传 managed_doc_kind 时 resolved.managed_doc_kind 为 None（Skill 路径）。"""
    resolved = _resolver(tmp_path, scenarios_dir).resolve(
        OptimizeRequest(scenario="edp_agent", agent_name="agent", skills=["skill_a"])
    )
    assert resolved.managed_doc_kind is None


def test_resolve_strips_managed_doc_kind(tmp_path: Path, scenarios_dir: Path) -> None:
    """resolve 透传时 managed_doc_kind 已由 OptimizeRequest 构造时 strip。"""
    resolved = _resolver(tmp_path, scenarios_dir).resolve(
        OptimizeRequest(
            scenario="edp_agent",
            agent_name="agent",
            managed_doc_kind="  agent_rule  ",
        )
    )
    assert resolved.managed_doc_kind == "agent_rule"


def test_optimizer_runtime_dependencies_excludes_managed_doc_kind(
    tmp_path: Path, scenarios_dir: Path
) -> None:
    """managed_doc_kind 不属于 SkillDocumentOptimizer 构造参数，
    不进入 optimizer_runtime_dependencies()——operator 在 optimizer dependencies
    构造前已创建，该字段供 runner builder 分支用。"""
    resolved = _resolver(tmp_path, scenarios_dir).resolve(
        OptimizeRequest(
            scenario="edp_agent",
            agent_name="agent",
            managed_doc_kind="agent_rule",
        )
    )
    deps = resolved.optimizer_runtime_dependencies()
    assert "managed_doc_kind" not in deps
