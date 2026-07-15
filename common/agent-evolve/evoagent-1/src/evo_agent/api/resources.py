"""ResourceResolver — DEPRECATED

.. deprecated:: Wave 8
    ADR-0005 确定 API 入口不再走 ResourceResolver 协议。
    route 层 _normalize() + build_dataset_from_request() 替代了
    ResourceResolver 的职责。保留此模块仅为向后兼容，后续版本移除。
"""

from __future__ import annotations

import importlib
import warnings
from pathlib import Path
from typing import Any, Protocol, runtime_checkable

from evo_agent.dataset.manifest import DatasetSpec, load_dataset_manifest


@runtime_checkable
class ResourceResolver(Protocol):
    """资源解析协议：将引用（ID 或路径）解析为运行时实例。"""

    def resolve_dataset(self, ref: str) -> DatasetSpec:
        """解析数据集引用，返回 DatasetSpec。"""
        ...

    def resolve_evaluator(self, ref: str) -> Any:
        """解析评估器引用，返回 BaseEvaluator 实例。"""
        ...


class LocalResolver:
    """DEPRECATED — 从本地文件加载资源（现场部署 / 开发调试）。"""

    def __init__(self) -> None:
        warnings.warn(
            "LocalResolver is deprecated (ADR-0005). "
            "Use route layer _normalize() + build_dataset_from_request() instead.",
            DeprecationWarning,
            stacklevel=2,
        )

    def resolve_dataset(self, ref: str) -> DatasetSpec:
        """从本地 manifest 文件加载数据集。"""
        path = Path(ref)
        if not path.exists():
            msg = f"Dataset manifest not found: {path}"
            raise FileNotFoundError(msg)
        return load_dataset_manifest(path)

    def resolve_evaluator(self, ref: str) -> Any:
        """通过 dotted path 加载评估器类并实例化。"""
        module_path, _, class_name = ref.rpartition(".")
        if not module_path:
            msg = f"Invalid evaluator dotted path: {ref}"
            raise ValueError(msg)
        module = importlib.import_module(module_path)
        cls = getattr(module, class_name)
        return cls()
