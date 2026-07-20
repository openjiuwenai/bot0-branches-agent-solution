"""ScenarioRegistry 单元测试 — optimizer 子类加载。"""

from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock

import pytest

from evo_agent.scenario.registry import ScenarioRegistry
from evo_agent.types import OptimizeRequest


def _make_request(scenario: str = "test_scenario") -> OptimizeRequest:
    return OptimizeRequest(
        scenario=scenario,
        agent_name="test_agent",
        dataset_manifest_path=Path("/tmp/dataset.yaml"),
        adapter_url="http://localhost:9090",
        skills=["test_skill"],
    )


@pytest.fixture
def scenarios_dir(tmp_path: Path) -> Path:
    """创建一个临时 scenarios 目录。"""
    scenarios = tmp_path / "scenarios"
    scenarios.mkdir()
    return scenarios


def _write_scenario_folder(
    scenarios_dir: Path,
    name: str,
    yaml_content: str,
    optimizer_code: str | None = None,
) -> Path:
    """在 scenarios/<name>/ 下创建 scenario.yaml + 可选 optimizer.py。"""
    folder = scenarios_dir / name
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "scenario.yaml").write_text(yaml_content, encoding="utf-8")
    if optimizer_code:
        (folder / "optimizer.py").write_text(optimizer_code, encoding="utf-8")
    return folder


# ── Happy path ──


def test_registry_builds_optimizer_from_folder(scenarios_dir: Path) -> None:
    """验证 registry 加载 scenario.yaml 并实例化 optimizer 子类。"""
    _write_scenario_folder(
        scenarios_dir,
        "my_scenario",
        """\
schema_version: "1.0"
optimizer_class: optimizer.MyOptimizer
hyperparams:
  batch_size: 8
""",
        optimizer_code="""\
class MyOptimizer:
    def __init__(self, **kwargs: object) -> None:
        self.kwargs = kwargs
""",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    request = _make_request(scenario="my_scenario")
    optimizer = registry.build_optimizer(request, dependencies={"agent": MagicMock()})

    assert optimizer.kwargs["batch_size"] == 8


def test_registry_merges_hyperparams_and_dependencies(scenarios_dir: Path) -> None:
    """验证 hyperparams 与 dependencies 合并，dependencies 优先。"""
    _write_scenario_folder(
        scenarios_dir,
        "merge_test",
        """\
schema_version: "1.0"
optimizer_class: optimizer.TestOpt
hyperparams:
  batch_size: 4
  score_threshold: 0.5
""",
        optimizer_code="""\
class TestOpt:
    def __init__(self, **kwargs: object) -> None:
        self.kwargs = kwargs
""",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    request = _make_request(scenario="merge_test")
    fake_agent = MagicMock()

    optimizer = registry.build_optimizer(
        request,
        dependencies={"agent": fake_agent, "batch_size": 16},  # 覆盖 hyperparams
    )

    assert optimizer.kwargs["batch_size"] == 16  # dependencies 优先
    assert optimizer.kwargs["score_threshold"] == 0.5  # hyperparams 保留
    assert optimizer.kwargs["agent"] is fake_agent


def test_registry_filters_extra_kwargs(scenarios_dir: Path) -> None:
    """验证 _filter_kwargs 过滤构造函数不接受的参数。"""
    _write_scenario_folder(
        scenarios_dir,
        "strict_opt",
        """\
schema_version: "1.0"
optimizer_class: optimizer.StrictOpt
hyperparams:
  batch_size: 4
""",
        optimizer_code="""\
class StrictOpt:
    def __init__(self, batch_size: int = 2) -> None:
        self.batch_size = batch_size
""",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    request = _make_request(scenario="strict_opt")

    optimizer = registry.build_optimizer(
        request,
        dependencies={"unknown_param": "should_be_filtered"},
    )

    assert optimizer.batch_size == 4
    assert not hasattr(optimizer, "unknown_param")


# ── Error cases ──


def test_registry_raises_on_missing_scenario(scenarios_dir: Path) -> None:
    """验证找不到场景时抛出 FileNotFoundError。"""
    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    request = _make_request(scenario="nonexistent")

    with pytest.raises(FileNotFoundError, match="Scenario not found"):
        registry.build_optimizer(request, dependencies={})


def test_registry_raises_on_missing_optimizer_class(scenarios_dir: Path) -> None:
    """验证 scenario.yaml 缺少 optimizer_class 时抛出 ValueError。"""
    _write_scenario_folder(
        scenarios_dir,
        "no_class",
        """\
schema_version: "1.0"
hyperparams:
  batch_size: 4
""",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    request = _make_request(scenario="no_class")

    with pytest.raises(ValueError, match="optimizer_class"):
        registry.build_optimizer(request, dependencies={})


def test_registry_raises_on_invalid_class_path(scenarios_dir: Path) -> None:
    """验证 optimizer_class 路径无效时抛出 ImportError。"""
    _write_scenario_folder(
        scenarios_dir,
        "bad_class",
        """\
schema_version: "1.0"
optimizer_class: nonexistent_module.NonexistentClass
""",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    request = _make_request(scenario="bad_class")

    with pytest.raises((ImportError, ModuleNotFoundError)):
        registry.build_optimizer(request, dependencies={})


def test_registry_raises_on_class_without_module_path(scenarios_dir: Path) -> None:
    """验证 optimizer_class 不含模块路径时抛出 ValueError。"""
    _write_scenario_folder(
        scenarios_dir,
        "no_module",
        """\
schema_version: "1.0"
optimizer_class: JustClassName
""",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    request = _make_request(scenario="no_module")

    with pytest.raises(ValueError, match="module path"):
        registry.build_optimizer(request, dependencies={})


# ── 模块隔离 ──


def test_registry_isolates_modules_across_scenarios(scenarios_dir: Path) -> None:
    """验证相同 optimizer.py 模块名不会在不同场景间串用。"""
    for name, marker in [("first", "FIRST"), ("second", "SECOND")]:
        _write_scenario_folder(
            scenarios_dir,
            name,
            """\
schema_version: "1.0"
optimizer_class: optimizer.ScenarioOpt
""",
            optimizer_code=f"""\
class ScenarioOpt:
    marker = {marker!r}
    def __init__(self, **kwargs: object) -> None:
        pass
""",
        )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    first = registry.build_optimizer(_make_request(scenario="first"), dependencies={})
    second = registry.build_optimizer(_make_request(scenario="second"), dependencies={})

    assert first.marker == "FIRST"
    assert second.marker == "SECOND"


# ── 默认场景 ──


def test_registry_default_scenarios_dir_loads_edp_agent() -> None:
    """验证默认 registry 能找到 examples/scenarios/edp_agent。"""
    request = _make_request(scenario="edp_agent")
    try:
        optimizer = ScenarioRegistry().build_optimizer(
            request,
            dependencies={
                "agent": MagicMock(),
                "evaluator": MagicMock(),
                "llm": MagicMock(),
                "model": "test-model",
                "train_cases": MagicMock(),
                "adapter_client": MagicMock(),
                "operators": {},
            },
        )
    except (FileNotFoundError, ImportError, ValueError):
        pytest.skip("examples/scenarios/edp_agent/ not available")

    assert optimizer is not None


# ── Wave 3 依赖注入 ──


def test_build_optimizer_injects_adapter_client(scenarios_dir: Path) -> None:
    """adapter_client 能通过 dependencies 注入到 optimizer 构造函数。"""
    _write_scenario_folder(
        scenarios_dir,
        "with_adapter",
        """\
schema_version: "1.0"
optimizer_class: optimizer.AdapterOpt
""",
        optimizer_code="""\
class AdapterOpt:
    def __init__(self, adapter_client=None, **kwargs: object) -> None:
        self.adapter_client = adapter_client
""",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    request = _make_request(scenario="with_adapter")
    fake_adapter = MagicMock()

    optimizer = registry.build_optimizer(
        request,
        dependencies={"adapter_client": fake_adapter},
    )

    assert optimizer.adapter_client is fake_adapter


def test_build_optimizer_injects_operators(scenarios_dir: Path) -> None:
    """operators 能通过 dependencies 注入到 optimizer 构造函数。"""
    _write_scenario_folder(
        scenarios_dir,
        "with_operators",
        """\
schema_version: "1.0"
optimizer_class: optimizer.OpsOpt
""",
        optimizer_code="""\
class OpsOpt:
    def __init__(self, operators=None, **kwargs: object) -> None:
        self.operators = operators
""",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    request = _make_request(scenario="with_operators")
    fake_operators = {"skill_a": MagicMock(), "skill_b": MagicMock()}

    optimizer = registry.build_optimizer(
        request,
        dependencies={"operators": fake_operators},
    )

    assert optimizer.operators is fake_operators
    assert "skill_a" in optimizer.operators


# ── W7.8: conversation_id_factory passthrough ──


def test_build_optimizer_passes_conversation_id_factory(
    scenarios_dir: Path,
) -> None:
    """build_optimizer() 正确传递 conversation_id_factory 给 optimizer。"""
    _write_scenario_folder(
        scenarios_dir,
        "with_factory",
        """\
schema_version: "1.0"
optimizer_class: optimizer.FactoryOpt
""",
        optimizer_code="""\
class FactoryOpt:
    def __init__(self, conversation_id_factory=None, **kwargs: object) -> None:
        self.conversation_id_factory = conversation_id_factory
""",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    request = _make_request(scenario="with_factory")

    from evo_agent.conversation import ConversationIdFactory

    factory = ConversationIdFactory(run_id="test-run")
    optimizer = registry.build_optimizer(
        request,
        dependencies={"conversation_id_factory": factory},
    )

    assert optimizer.conversation_id_factory is factory


# ── P1: MRO-based **kwargs filtering ──


def test_filter_kwargs_walks_mro_for_var_keyword(scenarios_dir: Path) -> None:
    """子类有 **kwargs 但父类没有 — 未知 key 应被过滤，不穿透到父类。"""
    _write_scenario_folder(
        scenarios_dir,
        "mro_filter",
        """\
schema_version: "1.0"
optimizer_class: optimizer.ChildOpt
""",
        optimizer_code="""\
class ParentOpt:
    def __init__(self, *, batch_size: int = 4, score_threshold: float = 0.5) -> None:
        self.batch_size = batch_size
        self.score_threshold = score_threshold

class ChildOpt(ParentOpt):
    def __init__(self, *, extra_param: str = "default", **kwargs: object) -> None:
        super().__init__(**kwargs)
        self.extra_param = extra_param
""",
    )

    registry = ScenarioRegistry(scenarios_dir=scenarios_dir)
    request = _make_request(scenario="mro_filter")

    # learning_rate is unknown to both ChildOpt and ParentOpt — should be filtered
    optimizer = registry.build_optimizer(
        request,
        dependencies={
            "batch_size": 8,
            "extra_param": "custom",
            "learning_rate": 0.01,  # unknown — must be filtered
        },
    )

    assert optimizer.batch_size == 8
    assert optimizer.extra_param == "custom"
    assert not hasattr(optimizer, "learning_rate")
