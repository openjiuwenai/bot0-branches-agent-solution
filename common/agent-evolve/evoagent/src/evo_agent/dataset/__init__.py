"""Dataset manifest 解析 + EvoCase 适配层。"""

from evo_agent.dataset.case import (
    EvoCase,
    evo_case_to_case,
    merge_extra_data,
    parse_evo_cases,
)
from evo_agent.dataset.manifest import DatasetSpec, load_dataset_manifest

__all__ = [
    "DatasetSpec",
    "EvoCase",
    "evo_case_to_case",
    "load_dataset_manifest",
    "merge_extra_data",
    "parse_evo_cases",
]
