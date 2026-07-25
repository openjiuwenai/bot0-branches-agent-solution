"""dataset.yaml → CaseLoader + Evaluator。

Evaluator 不通过数据集名称前缀自动猜测。每个数据集目录必须显式提供
dataset.yaml，声明训练样本、验证划分、Evaluator dotted path 和可选参数。
"""

from __future__ import annotations

import importlib
import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any, cast

import yaml
from openjiuwen.agent_evolving.dataset import Case, CaseLoader

from evo_agent.evaluator.evaluators.llm import LLMEvaluator
from evo_agent.evaluator.evaluators.metric import MetricEvaluator

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class DatasetSpec:
    """数据集 manifest 解析结果。"""

    name: str
    cases_path: Path
    train_cases: CaseLoader
    val_cases: CaseLoader
    evaluator: Any  # BaseEvaluator


def load_dataset_manifest(
    path: Path,
    *,
    eval_runtime: dict[str, Any] | None = None,
) -> DatasetSpec:
    """读取 dataset.yaml，实例化 CaseLoader 与 Evaluator。

    dataset.yaml 格式::

        schema_version: "1.0"
        name: fund_dataset_v2
        cases: items.json
        train_split: 0.8
        seed: 0
        evaluator:
          type: llm | metric | custom
          # type: llm 时从 eval_runtime 注入 model_config / model_client_config
          # type: metric 时使用 metric: exact_match
          # type: custom 或无 type 时使用 dotted_path（向后兼容）

    Parameters
    ----------
    path:
        dataset.yaml 文件路径。``cases`` 路径相对于此文件所在目录。
    eval_runtime:
        运行时注入的评估器配置（model_config, model_client_config, prompt_template）。
    """
    if not path.exists():
        msg = f"Dataset manifest not found: {path}"
        raise FileNotFoundError(msg)

    with path.open(encoding="utf-8") as f:
        config = cast(dict[str, Any], yaml.safe_load(f))

    # 必填字段
    name = config.get("name")
    cases_file = config.get("cases")
    evaluator_config = config.get("evaluator")

    if not name:
        msg = "dataset.yaml missing required field: 'name'"
        raise ValueError(msg)
    if not cases_file:
        msg = "dataset.yaml missing required field: 'cases'"
        raise ValueError(msg)
    if not evaluator_config:
        msg = "dataset.yaml missing required field: 'evaluator'"
        raise ValueError(msg)

    # 加载 cases（相对于 dataset.yaml 所在目录）
    cases_path = path.parent / cases_file
    cases = _load_cases(cases_path)

    # 划分 train/val
    train_split = float(config.get("train_split", 0.8))
    seed = int(config.get("seed", 0))
    loader = CaseLoader(cases)
    train_cases, val_cases = loader.split(train_split, seed=seed)

    # 加载 evaluator
    evaluator = _build_evaluator(evaluator_config, eval_runtime=eval_runtime or {})

    return DatasetSpec(
        name=name,
        cases_path=cases_path,
        train_cases=train_cases,
        val_cases=val_cases,
        evaluator=evaluator,
    )


def _load_cases(path: Path) -> list[Case]:
    """从 JSON/JSONL 文件加载 Case 列表。

    自动检测格式：
    - **JSONL**（``.jsonl`` 后缀）：每行一个 JSON 对象
    - **JSON 数组**：标准 ``[...]`` 格式
    - **agent-core 格式**：首个元素含 ``case_id`` + ``inputs: dict`` → 直接构造 Case
    - **EvoCase 格式**：首个元素含 ``id`` + ``inputs``/``turns``: list → 经 EvoCase 适配层转换
    """
    if not path.exists():
        msg = f"Cases file not found: {path}"
        raise FileNotFoundError(msg)

    # 支持 JSONL（每行一个 JSON 对象）
    if path.suffix == ".jsonl":
        data: list[dict[str, Any]] = []
        with path.open(encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    data.append(json.loads(line))
    else:
        with path.open(encoding="utf-8") as f:
            data = json.load(f)

    if not isinstance(data, list):
        msg = f"Cases file must be a JSON array or JSONL: {path}"
        raise ValueError(msg)

    if not data:
        return []

    if _is_evo_format(data[0]):
        from evo_agent.dataset.case import evo_case_to_case, parse_evo_cases

        return [evo_case_to_case(evo) for evo in parse_evo_cases(data)]

    return [Case(**item) for item in data]


def _is_evo_format(first: dict[str, Any]) -> bool:
    """检测首个元素是否为 EvoCase 格式（含 ``id`` + ``inputs: list``）。"""
    return "id" in first and isinstance(first.get("inputs"), list)


def _build_evaluator(
    config: dict[str, Any],
    *,
    eval_runtime: dict[str, Any] | None = None,
) -> Any:
    """根据 type 字段分发构建 Evaluator。

    - type: llm → _build_llm_evaluator (从 eval_runtime 注入 LLM 配置)
    - type: metric → _build_metric_evaluator (确定性评估，无需 runtime)
    - type: custom → _build_custom_evaluator (dotted_path 逃生口)
    - 无 type → _build_custom_evaluator (向后兼容旧格式)
    """
    runtime = eval_runtime or {}
    evaluator_type = config.get("type")

    if evaluator_type == "llm":
        return _build_llm_evaluator(config, runtime)
    elif evaluator_type == "metric":
        return _build_metric_evaluator(config)
    elif evaluator_type == "custom":
        return _build_custom_evaluator(config, runtime)
    elif not evaluator_type:
        return _build_custom_evaluator(config, runtime)  # 向后兼容
    else:
        raise ValueError(f"Unknown evaluator type: {evaluator_type!r}")


def _build_llm_evaluator(config: dict[str, Any], runtime: dict[str, Any]) -> LLMEvaluator:
    """构建 LLMEvaluator，从 runtime 注入 model_config 和 model_client_config。"""
    model_config = runtime.get("model_config")
    model_client_config = runtime.get("model_client_config")

    if model_config is None:
        raise ValueError("type: llm requires eval_runtime['model_config']")
    if model_client_config is None:
        raise ValueError("type: llm requires eval_runtime['model_client_config']")

    # prompt_template 优先级: runtime > dataset.yaml > None
    runtime_prompt = runtime.get("prompt_template")
    prompt_template = (
        runtime_prompt if runtime_prompt is not None else config.get("prompt_template")
    )

    aggregate = config.get("aggregate", "mean")
    return LLMEvaluator(
        model_config=model_config,
        model_client_config=model_client_config,
        aggregate=aggregate,
        prompt_template=prompt_template,
    )


def _build_metric_evaluator(config: dict[str, Any]) -> MetricEvaluator:
    """构建 MetricEvaluator（确定性评估器，不需要 runtime 注入）。

    支持 ``extract``：从 answer 文本中抽取配置字段后再 exact_match。
    与 ``evo_agent.evaluator.factory.create_evaluator`` 行为对齐。
    """
    from evo_agent.evaluator.factory import create_evaluator

    payload = dict(config)
    payload.setdefault("type", "metric")
    evaluator = create_evaluator(payload)
    if not isinstance(evaluator, MetricEvaluator):
        raise TypeError(f"expected MetricEvaluator, got {type(evaluator).__name__}")
    return evaluator


def _build_custom_evaluator(config: dict[str, Any], runtime: dict[str, Any]) -> Any:
    """通过 dotted path 动态加载自定义 Evaluator 类并实例化。"""
    dotted_path = config.get("dotted_path", "")
    kwargs = config.get("kwargs", {})

    if not dotted_path:
        msg = "evaluator.dotted_path is required for custom/no-type evaluators"
        raise ValueError(msg)

    module_path, _, class_name = dotted_path.rpartition(".")
    if not module_path:
        msg = f"Invalid evaluator dotted path: {dotted_path}"
        raise ValueError(msg)

    module = importlib.import_module(module_path)
    cls = getattr(module, class_name)
    # Merge runtime config into kwargs (runtime takes precedence)
    merged_kwargs = {**kwargs, **runtime}
    return cls(**merged_kwargs)


def build_dataset_from_request(
    data_path: Path,
    evaluator_prompt: str,
    train_split: float,
    val_split: float,
    eval_runtime: dict[str, Any],
    seed: int | None = None,
    *,
    evaluator_config: dict[str, Any] | None = None,
) -> DatasetSpec:
    """从 API 请求参数直接构建 DatasetSpec，不依赖 dataset.yaml。

    Parameters
    ----------
    data_path:
        原始数据文件路径（已通过 path validator 校验）。
    evaluator_prompt:
        评估 prompt（来自 evaluator_template.prompt）；``type=llm`` 时注入
        ``prompt_template``。保留该参数以兼容旧调用方。
    train_split:
        训练集比例。
    val_split:
        验证集比例。
    eval_runtime:
        运行时 LLM 配置（model_config, model_client_config）。
    seed:
        随机种子，用于可复现的 train/val 切分。None 时使用随机种子。
    evaluator_config:
        评估器配置。缺省 ``{"type": "metric"}``。支持 ``llm`` / ``metric``
        （与 CLI ``dataset.yaml`` 对齐）。
    """
    # 1. 加载 cases
    cases = _load_cases(data_path)
    if not cases:
        raise ValueError(f"Dataset file is empty: {data_path}")

    # 2. 切分（seed=None 时随机，避免固定切分导致训练/验证集偏差）
    if seed is None:
        import random

        seed = random.randint(0, 2**31 - 1)
        logger.info("train/val split using random seed=%d", seed)

    loader = CaseLoader(cases)
    train_cases, val_cases = loader.split(train_split, seed=seed)

    # 3. 切分后校验
    if len(train_cases.get_cases()) < 1:
        raise ValueError(
            f"Train set empty after split (total={len(cases)}, "
            f"train_split={train_split}). Increase dataset size."
        )
    if len(val_cases.get_cases()) < 1:
        raise ValueError(
            f"Validation set empty after split (total={len(cases)}, "
            f"val_split={val_split}). Increase dataset size."
        )

    # 4. 构建 evaluator（api 可指定 llm / metric；默认 metric）
    cfg: dict[str, Any] = dict(evaluator_config) if evaluator_config else {"type": "metric"}
    eval_type = str(cfg.get("type", "metric")).strip() or "metric"
    if eval_type not in {"llm", "metric"}:
        raise ValueError(
            f"Unsupported evaluator type for API dataset: {eval_type!r}. "
            "Supported: 'llm', 'metric'."
        )
    cfg["type"] = eval_type
    if eval_type == "llm":
        # prompt 优先级：显式 prompt_template > evaluator_prompt 参数
        if "prompt_template" not in cfg and evaluator_prompt:
            cfg["prompt_template"] = evaluator_prompt
    evaluator = _build_evaluator(cfg, eval_runtime=eval_runtime)

    return DatasetSpec(
        name=data_path.stem,
        cases_path=data_path,
        train_cases=train_cases,
        val_cases=val_cases,
        evaluator=evaluator,
    )
