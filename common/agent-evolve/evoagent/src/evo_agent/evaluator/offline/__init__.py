"""离线数据集评估模块（多组版）。

multipart 上传 → 字段映射 → 多组物化（exact_match 用 pred 原值；keyword 派生命中
关键词集）→ per-case 指标逐条评分 + 按组聚合（``batch_metrics`` 细粒度多选：
``"mean"`` + ``"precision"``/``"recall"``/``"f1"``/``"accuracy"``，各勾各返回；
exact_match→单标签 micro 分类，keyword→内联二元混淆）。复用 registry 解析的 metric
实例，不复用 ``MetricEvaluator.evaluate``（dict-stringify 阻抗见 ``scorer.py``）。

支持数据集格式：json/jsonl/csv/xlsx。
"""

from __future__ import annotations

from evo_agent.evaluator.offline.extractor import (
    AnswerExtractor,
    ExtractionConfig,
    ExtractionResult,
)
from evo_agent.evaluator.offline.models import (
    CaseScore,
    EvalSummary,
    GroupConfig,
    MaterializedCase,
)
from evo_agent.evaluator.offline.pipeline import (
    OfflineEvalRequest,
    ingest,
    load_raw_records,
    run_offline_eval,
    summary_to_dict,
)
from evo_agent.evaluator.offline.scorer import VALID_BATCH_METRICS, OfflineMetricScorer

__all__ = [
    "AnswerExtractor",
    "CaseScore",
    "EvalSummary",
    "ExtractionConfig",
    "ExtractionResult",
    "GroupConfig",
    "MaterializedCase",
    "OfflineEvalRequest",
    "OfflineMetricScorer",
    "VALID_BATCH_METRICS",
    "ingest",
    "load_raw_records",
    "run_offline_eval",
    "summary_to_dict",
]
