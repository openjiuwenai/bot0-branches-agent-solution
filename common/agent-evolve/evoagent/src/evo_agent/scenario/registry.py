"""ScenarioRegistry — 根据场景名加载 scenario.yaml，实例化 optimizer 子类。

场景文件夹结构::

    examples/scenarios/<name>/
    ├── scenario.yaml       # optimizer_class + hyperparams
    ├── optimizer.py        # SkillDocumentOptimizer 子类
    ├── prompts/            # 可选 prompt 覆盖（TF-GRPO: variant_*/rollout_summary/...）
    └── skills/             # 可选初始 skill
"""

from __future__ import annotations

import importlib
import importlib.util
import inspect
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, cast

import yaml

from evo_agent.paths import SCENARIOS_DIR
from evo_agent.types import OptimizeRequest

_SUPPORTED_SCHEMA_VERSION = "1.0"


@dataclass(frozen=True)
class ScenarioConfig:
    """scenario.yaml 解析后的结构化配置。

    Attributes:
        optimizer_class: optimizer 子类的 dotted path。
        hyperparams: 训练超参数，与 dependencies 合并后传给 optimizer。
        adapter_url: Adapter sidecar 地址。
        skills: skill 列表，每项含 name + optimize 标记。
        rollout: rollout 配置（max_turns、extra_data 等）。
    """

    optimizer_class: str
    hyperparams: dict[str, Any] = field(default_factory=dict)
    adapter_url: str = ""
    skills: list[dict[str, Any]] = field(default_factory=list)
    rollout: dict[str, Any] = field(default_factory=dict)

    def get_optimize_skills(self) -> list[str]:
        """返回 optimize=true 的 skill 名称列表。"""
        return [s["name"] for s in self.skills if s.get("optimize", False)]


class ScenarioRegistry:
    """根据 ``OptimizeRequest.scenario`` 加载场景并实例化 optimizer 子类。

    scenario.yaml 格式::

        schema_version: "1.0"
        optimizer_class: optimizer.EDPAgentOptimizer
        hyperparams:
          batch_size: 8
          num_parallel: 8

    dependencies 注入：
        ``build_optimizer()`` 的 ``dependencies`` 参数与 YAML ``hyperparams``
        合并后传给 optimizer 构造函数（``_filter_kwargs`` 自动过滤不接受的参数）。

        标准 dependencies（由 ``optimizer_runner`` 注入）：

        - ``agent``: RemoteAgent 实例
        - ``evaluator``: BaseEvaluator 实例
        - ``llm``: Model 实例
        - ``model``: 模型名称
        - ``train_cases``: 训练用例
        - ``adapter_client``: AdapterClient 实例（Wave 3 新增）
        - ``operators``: dict[str, SkillDocumentOperator]（Wave 3 新增）
        - 以及 batch_size, accumulation, minibatch_size 等超参
    """

    def __init__(self, scenarios_dir: Path | None = None) -> None:
        self._scenarios_dir = scenarios_dir or SCENARIOS_DIR

    def build_optimizer(
        self,
        request: OptimizeRequest,
        dependencies: dict[str, Any],
    ) -> Any:
        """加载场景 optimizer 子类并实例化。

        Parameters
        ----------
        request:
            优化请求，``request.scenario`` 决定加载哪个场景。
        dependencies:
            运行时依赖，与 YAML ``hyperparams`` 合并后传给 optimizer 构造函数。
            dependencies 优先于 hyperparams。
            常见依赖项包括 ``agent``（RemoteAgent）、``evaluator``、``llm``、
            ``adapter_client``（AdapterClient）、``operators``
            （dict[str, SkillDocumentOperator]）等。
            ``_filter_kwargs`` 会自动过滤构造函数不接受的参数。
        """
        scenario_name = request.scenario
        scenario_dir = self._resolve_scenario_dir(scenario_name)
        config = self._load_config(scenario_name)

        optimizer_class_path = config.get("optimizer_class")
        if not optimizer_class_path:
            msg = f"scenario.yaml missing 'optimizer_class': {scenario_name}"
            raise ValueError(msg)

        hyperparams = config.get("hyperparams", {})
        cls = self._resolve_class(optimizer_class_path, scenario_dir=scenario_dir)

        # 合并：hyperparams + dependencies（dependencies 优先）
        merged_kwargs = {**hyperparams, **dependencies}
        merged_kwargs.setdefault("scenario_name", scenario_name)
        merged_kwargs.setdefault("scenarios_dir", self._scenarios_dir)
        filtered = self._filter_kwargs(cls, merged_kwargs)

        return cls(**filtered)

    def load_scenario_config(self, scenario_name: str) -> ScenarioConfig:
        """加载并校验 scenario.yaml，返回 ScenarioConfig。

        Parameters
        ----------
        scenario_name:
            场景名称，对应 ``examples/scenarios/<name>/scenario.yaml``。

        Raises
        ------
        FileNotFoundError
            scenario.yaml 不存在。
        ValueError
            schema_version 缺失或不匹配。
        """
        config = self._load_config(scenario_name)

        version = config.get("schema_version")
        if version is None:
            msg = f"scenario.yaml missing 'schema_version': {scenario_name}"
            raise ValueError(msg)
        if str(version) != _SUPPORTED_SCHEMA_VERSION:
            msg = (
                f"Unsupported schema_version '{version}' "
                f"(expected '{_SUPPORTED_SCHEMA_VERSION}'): {scenario_name}"
            )
            raise ValueError(msg)

        optimizer_class = config.get("optimizer_class", "")
        if not optimizer_class:
            msg = f"scenario.yaml missing 'optimizer_class': {scenario_name}"
            raise ValueError(msg)

        return ScenarioConfig(
            optimizer_class=optimizer_class,
            hyperparams=config.get("hyperparams", {}),
            adapter_url=config.get("adapter_url", ""),
            skills=config.get("skills", []),
            rollout=config.get("rollout", {}),
        )

    def _resolve_scenario_dir(self, scenario_name: str) -> Path:
        """查找场景目录。"""
        folder = self._scenarios_dir / scenario_name
        if folder.is_dir() and (folder / "scenario.yaml").exists():
            return folder
        msg = f"Scenario not found: {folder}"
        raise FileNotFoundError(msg)

    def _load_config(self, scenario_name: str) -> dict[str, Any]:
        """加载场景配置 YAML。"""
        path = self._scenarios_dir / scenario_name / "scenario.yaml"
        if not path.exists():
            msg = f"Scenario config not found: {path}"
            raise FileNotFoundError(msg)
        return self._read_yaml(path)

    @staticmethod
    def _read_yaml(path: Path) -> dict[str, Any]:
        with path.open(encoding="utf-8") as f:
            return cast(dict[str, Any], yaml.safe_load(f))

    @staticmethod
    def _resolve_class(dotted_path: str, scenario_dir: Path | None = None) -> type:
        """通过 dotted path 动态加载类。

        支持场景内相对路径（如 ``optimizer.EDPAgentOptimizer``）
        和全局绝对路径（如 ``evo_agent.xxx.Yyy``）。
        """
        module_path, _, class_name = dotted_path.rpartition(".")
        if not module_path:
            msg = f"Optimizer class must include module path: {dotted_path}"
            raise ValueError(msg)

        # 场景内模块：非 evo_agent. 前缀 + 场景目录中存在对应 .py 文件
        if scenario_dir is not None and not module_path.startswith("evo_agent."):
            module_file = scenario_dir / f"{module_path.replace('.', '/')}.py"
            if module_file.exists():
                module_name = f"_evo_agent_scenario_{scenario_dir.name}_{module_path}"
                spec = importlib.util.spec_from_file_location(module_name, module_file)
                if spec is None or spec.loader is None:
                    msg = f"Cannot load scenario module: {module_file}"
                    raise ImportError(msg)
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                return cast(type, getattr(module, class_name))

        # 全局模块
        module = importlib.import_module(module_path)
        return cast(type, getattr(module, class_name))

    @staticmethod
    def _filter_kwargs(cls: type, kwargs: dict[str, Any]) -> dict[str, Any]:
        """仅传递构造函数接受的参数，过滤多余字段。

        当子类有 ``**kwargs`` 时，沿 MRO 向上查找不含 ``**kwargs`` 的父类，
        合并其显式参数名。这样 ``EDPAgentOptimizer(**kwargs) → SkillDocumentOptimizer``
        链中，未知 key 不会穿透到不接受 ``**kwargs`` 的父类。
        """
        accepted: set[str] = set()
        for klass in cls.__mro__:
            if klass is object:
                continue
            try:
                sig = inspect.signature(klass)
            except (ValueError, TypeError):
                continue
            params = sig.parameters.values()
            has_var_keyword = any(p.kind == inspect.Parameter.VAR_KEYWORD for p in params)
            accepted.update(
                p.name
                for p in params
                if p.kind
                in {inspect.Parameter.POSITIONAL_OR_KEYWORD, inspect.Parameter.KEYWORD_ONLY}
            )
            if not has_var_keyword:
                # 找到不含 **kwargs 的父类 — 以此为过滤边界
                break

        if not accepted:
            # 整条 MRO 链都接受 **kwargs（或无显式参数），透传所有 key
            return kwargs

        return {key: value for key, value in kwargs.items() if key in accepted}
