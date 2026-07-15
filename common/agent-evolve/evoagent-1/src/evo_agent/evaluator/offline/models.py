"""离线数据集评估的内部数据模型（多组版）。

叶子模块——只依赖标准库与 ``Any``，不引用 API 层或 evaluator 运行时类，
避免与 scorer/pipeline 形成循环导入。一个数据集可含多组（exact_match /
keyword），每组独立 (gold 源 + pred 列 + 指标 + batch_metrics)。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class GroupConfig:
    """一个评估组的配置。

    Args:
        name: 结果标签（如 ``"是否属实"``），用于按组嵌套的 aggregate 键。
        kind: ``"exact_match"``（gold 列 vs pred 列）、``"keyword"``
            （pred 文本是否命中关键词列表中任一词）或 ``"llm_judge"``（用 LLM 做分类：
            从 ``labels`` 里选一个规范标签，不给 gold 防泄漏；scorer 用 ``(gold,
            judged_label)`` 建真实多类混淆矩阵算 P/R/F1）。
        pred_field: 预测列名。
        gold_field: exact_match / llm_judge 的 gold 列名（keyword 模式留空）。
        keywords: keyword 模式的关键词列表（exact_match / llm_judge 留空）。
        json_key: 非空时，``pred_field`` 列的 cell 视为 JSON 对象，按此键路径
            （``a.b`` 点号分隔）解析出该组的 pred 值；为空则直接取列原值。
            用于多组 pred 共存于同一 JSON 列的场景（如模式2 变体：一个 cell 是
            ``{"是否属实":"是","是否供电公司责任":"否"}``，两组各设不同 json_key）。
        labels: 仅 ``llm_judge`` 用；**用户声明的规范标签集合**（llm_judge 必填，
            否则 422）。LLM 从中选一个；保留词 ``"其他"`` 禁止出现在其中（422）。
            gold 列的 distinct 值必须 ⊆ labels（422）。scorer 的 macro P/R/F1 只在
            labels 上平均（排除 fallback 桶 ``"其他"``）。
        extract_key: 仅 ``llm_judge`` 用；**需从 pred 中提取的内容**（llm_judge 必填，否则
            422），如 ``"是否属实"`` / ``"是否供电公司责任"``。LLM 凭对该内容的理解把 pred
            归进 ``labels``——``extract_key`` 提供「在提取什么」的上下文，分类才有的放矢。
            渲染进固定判定 prompt 的 ``{extract_key}`` 占位符（无自定义 prompt）。
        batch_metrics: 该组的 batch 聚合多选项（``"mean"`` + ``"precision"``/
            ``"recall"``/``"f1"``/``"accuracy"`` 四个混淆指标，各勾各返回）。
    """

    name: str
    kind: str  # "exact_match" | "keyword" | "llm_judge"
    pred_field: str
    gold_field: str = ""
    keywords: tuple[str, ...] = ()
    json_key: str = ""
    labels: tuple[str, ...] = ()
    extract_key: str = ""
    batch_metrics: tuple[str, ...] = (
        "mean", "precision", "recall", "f1", "accuracy",
    )


@dataclass(frozen=True)
class MaterializedCase:
    """一条 case × 组 物化后的结果（ingest 阶段产出）。

    Attributes:
        case_id: 唯一标识（取 ``id_field``，缺省用原始索引的字符串）。
        group: 所属组名（``GroupConfig.name``）。
        gold: exact_match 组为 gold 列原值；keyword 组为 keywords 列表。
        extracted: exact_match 组为 pred 列原值（method=``"raw"``）；
            keyword 组为命中的关键词列表（method=``"keyword"``）。
        extraction_method: ``"raw"`` | ``"keyword"`` | ``"json"`` | ``"json_keyword"``。
        judged_label: judge 阶段输出的规范标签（仅 llm_judge 组非空）。LLM 从
            ``group.labels`` 中选出一个；归不进任一声明标签 → 保留词 ``"其他"``。
            非 llm_judge 组留空串 ``""``。scorer 用 ``(gold, judged_label)`` 建多类
            混淆矩阵。
    """

    case_id: str
    group: str
    gold: Any
    extracted: Any
    extraction_method: str
    judged_label: str = ""


@dataclass
class CaseScore:
    """一条 case × 组 的逐指标评分结果（scoring 阶段产出）。"""

    case_id: str
    group: str
    per_metric: dict[str, float] = field(default_factory=dict)
    score: float = 0.0  # 该 case 各 per-case 指标分的 mean（_agg_score）


@dataclass
class EvalSummary:
    """整批评估的汇总结果（按组嵌套 + 跨组综合）。

    ``per_case`` 按 case 嵌套（见 ``summary_to_dict``）；``aggregate`` 键为组名，
    值为该组的聚合 dict（``<per-case 指标名>`` 均值 + ``_overall`` + f1/recall/
    precision/accuracy）；``overall`` 为跨组综合（``_overall`` = 全部 case×组
    composite 的总均值，因每组都覆盖全部 N 条，等价于各组 ``_overall`` 的宏平均）；
    ``extraction_summary`` 统计各 extraction_method 计数。
    """

    per_case: list[CaseScore] = field(default_factory=list)
    aggregate: dict[str, dict[str, float]] = field(default_factory=dict)
    overall: dict[str, float] = field(default_factory=dict)
    extraction_summary: dict[str, int] = field(default_factory=dict)
