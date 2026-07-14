"""多组离线评分器 —— 复用 registry 解析的 metric 实例，不复用 MetricEvaluator。

为什么不用 ``MetricEvaluator.evaluate``：它把整个 ``predict`` dict 传给
``metric.compute``（``metric.py:104``）并强制 ``EvaluatedCase.answer`` 为 dict，
dict 被 stringify 成 ``{"answer": "..."}`` 导致 ``exact_match`` 永远失配。离线
评估的预测是"提取出的答案标量"，必须裸传给 ``compute(extracted, gold)``。

按组分流聚合，``batch_metrics`` 为细粒度多选项（``mean`` + 四个混淆指标各自独立，
前端多选勾哪个返回哪个）：
- ``"mean"`` → per-case 指标跨 case 均值 + ``"_overall"``。
- ``"precision"``/``"recall"``/``"f1"``/``"accuracy"`` → 各组混淆 bundle 中对应那一项：
  - exact_match 组 → 按 materialized 的真实 (gold, pred) 标签值建 N×N 混淆矩阵，
    对每个标签算 per-class precision/recall/f1，再 **macro 平均**（标签等权，
    不受支持度影响）；accuracy = 命中数/总数（恒 = 命中率）。各值一般互不相等，
    反映分类报告（类不平衡、误报/漏报不对称）。
  - keyword 组 → 内联二元混淆（所有 case 视为正例：TP=命中数, FN=未中数, FP=TN=0）→
    recall=accuracy=命中率, precision=1.0, f1=2·h/(1+h)。
  - llm_judge 组 → 与 exact_match 同构的 macro 混淆，但「预测标签」来自 LLM 分类
    判定（``judged_label``，不是 pred 文本、也不是 verdict 反推）。judge 是分类器：
    LLM 从用户声明的 ``labels`` 选一个（不读 gold），pred 归不进任一声明标签 →
    保留词 ``"其他"``。scorer 用 ``(gold, judged_label)`` 建真实多类混淆矩阵，macro
    P/R/F1 只在 ``group.labels`` 上平均（排除 ``"其他"`` 桶，桶率另出诊断键）。
    accuracy = judged==gold 的命中率。judged_label 由 pipeline judge 阶段算好后经
    ``judged_label`` kwarg 注入 per-case 指标并写入 materialized。

exact_match 混淆必须用真实标签值（不是 per-case 0/1），否则 micro 单标签会退化成
四项皆=命中率、无信息。混淆矩阵内部恒算，只按勾选过滤输出键。不用
``SetOverlapBatchMetric``：它的 accuracy 是 set-Jaccard（M/(M+2W)），与用户
直觉的"命中率"不符。``SetOverlapBatchMetric`` 仍在 registry 供其它消费者使用。

``_VALID_BATCH_METRICS`` 是 ``batch_metrics`` 的合法名集合（``"mean"`` +
四个混淆名），route 层据此校验，未知名 → 422。

跨组 ``overall``（``summarize`` 产出）含 ``_overall``（各组 composite 均值的宏平均）
+ ``precision``/``recall``/``f1``/``accuracy``（各组混淆 bundle 对应项的宏平均）。
后者意义：模型跨多任务的平均分类表现；与各组 ``batch_metrics`` 勾选无关（overall
是独立"全套"汇总，混淆矩阵恒算全 4 项）。注意：把所有组压成单一"对/不对"二分类
再算 P/R/F1 会退化（无负例 → precision 恒 1）；跨组宏平均各组**已算好的、带正负
结构**的真实指标则不退化。exact_match-only / llm_judge-only 时 ``overall.accuracy == _overall``；
混 keyword 组时 precision 被 keyword 退化值 1.0 抬高，需结合 ``extraction_summary``
解读。
"""

from __future__ import annotations

import statistics
from collections import defaultdict
from typing import Any

from openjiuwen.agent_evolving.evaluator.evaluator import _agg_score

from evo_agent.evaluator.metrics import get_metric
from evo_agent.evaluator.metrics.base import Metric
from evo_agent.evaluator.offline.models import (
    CaseScore,
    EvalSummary,
    GroupConfig,
    MaterializedCase,
)

__all__ = ["OfflineMetricScorer", "VALID_BATCH_METRICS"]

# kind → 该组 per-case 指标名。每组只用一个 per-case 指标。
_KIND_PER_CASE_METRIC: dict[str, str] = {
    "exact_match": "exact_match",
    "keyword": "keyword_hit",
    "llm_judge": "llm_judge",
}

# 混淆 bundle 的四个键（按此稳定顺序输出，与勾选顺序无关）。
_CONFUSION_METRICS: tuple[str, ...] = ("precision", "recall", "f1", "accuracy")
_CONFUSION_SET: set[str] = set(_CONFUSION_METRICS)

# batch_metrics 合法名集合——route 层据此校验。``"mean"`` + 四个混淆名。
VALID_BATCH_METRICS: tuple[str, ...] = ("mean",) + _CONFUSION_METRICS


def _flatten_metric_out(out: Any, metric: Metric) -> dict[str, float]:
    """把 ``compute`` 返回值展平为 ``{name: float}``。

    float → ``{metric.name: float}``；dict → 各子键（值转 float）。
    """
    result: dict[str, float] = {}
    if isinstance(out, dict):
        for key, value in out.items():
            result[str(key)] = _to_float(value)
    else:
        result[metric.name] = _to_float(out)
    return result


def _to_float(value: Any) -> float:
    """尽力转 float；失败记 0.0。"""
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


class OfflineMetricScorer:
    """多组评分器：每组一个 per-case 指标 + 各自的 batch 聚合。

    Args:
        groups: 评估组配置列表。构造时按 ``kind`` 解析每组 per-case 指标实例。
            batch 聚合细粒度多选：``"mean"`` → per-case 指标跨 case 均值 +
            ``"_overall"``；``"precision"``/``"recall"``/``"f1"``/``"accuracy"`` →
            混淆 bundle 中对应项（exact_match：按真实标签 macro 平均；keyword：全正例
            二元混淆）。混淆矩阵恒算，只按勾选输出键。
    """

    def __init__(self, groups: list[GroupConfig]) -> None:
        self._groups = groups
        self._per_case: dict[str, Metric] = {}
        for g in groups:
            metric_name = _KIND_PER_CASE_METRIC.get(g.kind)
            if metric_name is None:
                raise ValueError(f"Unknown group kind {g.kind!r}")
            self._per_case[g.name] = get_metric(metric_name)()

    def score_case(
        self,
        group_name: str,
        extracted: Any,
        gold: Any,
        **kwargs: Any,
    ) -> dict[str, float]:
        """对一条 case 的指定组跑 per-case 指标，返回 ``{metric_name: float}``。

        ``kwargs`` 透传给 ``metric.compute``——``llm_judge`` 组经 ``judged_label``
        注入 pipeline judge 阶段算好的分类标签；其它指标忽略多余 kwarg。
        """
        metric = self._per_case[group_name]
        out = metric.compute(extracted, gold, **kwargs)
        return _flatten_metric_out(out, metric)

    @staticmethod
    def composite(per_metric: dict[str, float]) -> float:
        """该 case 各 per-case 指标分的 mean（``_agg_score``）。无指标时 0.0。"""
        if not per_metric:
            return 0.0
        return float(_agg_score(list(per_metric.values()), "mean"))

    def _group_bundles(
        self,
        group: GroupConfig,
        case_scores: list[CaseScore],
        materialized: list[MaterializedCase],
    ) -> tuple[dict[str, float], dict[str, float]]:
        """算单组的 mean bundle + 完整混淆 bundle（**恒算全 4 项，不按勾选过滤**）。

        mean bundle：``{<per-case 指标名>: 跨 case 均值, "_overall": composite 均值}``。
        混淆 bundle：该 kind 的全 4 项（exact_match：按真实标签 macro 平均；keyword：
        全正例二元混淆 → precision=1.0）。``aggregate_group`` 按勾选过滤输出；
        ``summarize`` 用未过滤的混淆 bundle 算跨组宏平均 overall。
        """
        group_scores = [cs for cs in case_scores if cs.group == group.name]
        group_materialized = [m for m in materialized if m.group == group.name]
        mean_bundle = self._mean_aggregate(group_scores)
        if group.kind == "exact_match":
            conf = self._exact_match_confusion_aggregate(group_scores, group_materialized)
        elif group.kind == "keyword":
            conf = self._keyword_confusion_aggregate(group_scores)
        elif group.kind == "llm_judge":
            conf = self._llm_judge_confusion_aggregate(group_scores, group_materialized, group)
        else:
            conf = {}
        return mean_bundle, conf

    @staticmethod
    def _filtered_output(
        group: GroupConfig,
        mean_bundle: dict[str, float],
        conf: dict[str, float],
    ) -> dict[str, float]:
        """按 ``batch_metrics`` 多选过滤出单组输出。键顺序：mean bundle 原序 +
        ``_CONFUSION_METRICS`` 稳定序，与勾选顺序无关。

        ``其他_count``/``其他_rate`` 是 llm_judge 组的诊断键（LLM 归不进任一声明
        标签的比例），**不**在 ``batch_metrics`` 合名集合里——对 llm_judge 组无条件
        输出，否则会被本过滤丢掉。
        """
        wanted = set(group.batch_metrics)
        result: dict[str, float] = {}
        if "mean" in wanted:
            result.update(mean_bundle)
        if wanted & _CONFUSION_SET:
            for key in _CONFUSION_METRICS:
                if key in wanted and key in conf:
                    result[key] = conf[key]
        # llm_judge 诊断键无条件输出（不在 batch_metrics 合名集合里）。
        if group.kind == "llm_judge":
            for diag in ("其他_count", "其他_rate"):
                if diag in conf:
                    result[diag] = conf[diag]
        return result

    def aggregate_group(
        self,
        group: GroupConfig,
        case_scores: list[CaseScore],
        materialized: list[MaterializedCase],
    ) -> dict[str, float]:
        """产出单组聚合：按 ``batch_metrics`` 多选过滤输出。

        - ``"mean"`` 在勾选时：``{<per-case 指标名>: 跨 case 均值, "_overall": 各 case
          composite 均值}``。
        - ``"precision"``/``"recall"``/``"f1"``/``"accuracy"`` 勾选时：从该 kind 的混淆
          bundle 取对应项（exact_match/llm_judge：按真实标签 macro 平均，各值一般互异；
          keyword：全正例二元混淆 → recall=accuracy=命中率, precision=1.0, f1=2·h/(1+h)）。
        - 混淆矩阵恒算，只按勾选输出键；键顺序固定为 ``_CONFUSION_METRICS``，
          与勾选顺序无关。exact_match 混淆需真实 (gold,pred) 标签，从 materialized
          取；accuracy 用 case_scores 的 exact_match 0/1（权威指标值）。
        """
        mean_bundle, conf = self._group_bundles(group, case_scores, materialized)
        return self._filtered_output(group, mean_bundle, conf)

    @staticmethod
    def _mean_aggregate(case_scores: list[CaseScore]) -> dict[str, float]:
        """对 per-case 各指标键求跨 case 均值 + ``"_overall"``。"""
        per_key: dict[str, list[float]] = {}
        composites: list[float] = []
        for cs in case_scores:
            for key, value in cs.per_metric.items():
                per_key.setdefault(key, []).append(value)
            composites.append(cs.score)
        result: dict[str, float] = {
            key: statistics.fmean(values) for key, values in per_key.items()
        }
        result["_overall"] = statistics.fmean(composites) if composites else 0.0
        return result

    @staticmethod
    def _exact_match_confusion_aggregate(
        case_scores: list[CaseScore],
        materialized: list[MaterializedCase],
    ) -> dict[str, float]:
        """exact_match 组：按真实 (gold, pred) 标签值 macro 平均。

        从 materialized 取该组每条的 (gold, extracted) 标签对，建 N×N 混淆矩阵，
        对每个标签算 per-class precision=TP/(TP+FP)、recall=TP/(TP+FN)、
        f1=2PR/(P+R)，再对标签等权 macro 平均。accuracy 用 case_scores 的
        exact_match 0/1（权威命中值，= 对角线/总数）。n=0 或无标签时全 0。

        用真实标签而非 per-case 0/1：micro 单标签会退化成四项皆=命中率、无信息；
        macro 才能体现误报(FP)/漏报(FN)的不对称与类不平衡。
        """
        n = len(case_scores)
        if n == 0:
            return {"precision": 0.0, "recall": 0.0, "accuracy": 0.0, "f1": 0.0}
        matches = sum(cs.per_metric.get("exact_match", 0.0) for cs in case_scores)
        accuracy = matches / n

        gold_counts: dict[str, int] = defaultdict(int)
        pred_counts: dict[str, int] = defaultdict(int)
        diag: dict[str, int] = defaultdict(int)
        labels: set[str] = set()
        for m in materialized:
            gold = str(m.gold)
            pred = str(m.extracted)
            gold_counts[gold] += 1
            pred_counts[pred] += 1
            labels.add(gold)
            labels.add(pred)
            if gold == pred:
                diag[gold] += 1

        if not labels:
            return {"precision": 0.0, "recall": 0.0, "accuracy": accuracy, "f1": 0.0}

        precisions: list[float] = []
        recalls: list[float] = []
        f1s: list[float] = []
        for label in labels:
            tp = diag[label]
            fp = pred_counts[label] - tp
            fn = gold_counts[label] - tp
            precision = tp / (tp + fp) if (tp + fp) else 0.0
            recall = tp / (tp + fn) if (tp + fn) else 0.0
            f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) else 0.0
            precisions.append(precision)
            recalls.append(recall)
            f1s.append(f1)

        count = len(labels)
        return {
            "precision": sum(precisions) / count,
            "recall": sum(recalls) / count,
            "f1": sum(f1s) / count,
            "accuracy": accuracy,
        }

    @staticmethod
    def _llm_judge_confusion_aggregate(
        case_scores: list[CaseScore],
        materialized: list[MaterializedCase],
        group: GroupConfig,
    ) -> dict[str, float]:
        """llm_judge 组：按真实 ``(gold, judged_label)`` 多类混淆 macro 平均（非退化）。

        与 ``_exact_match_confusion_aggregate`` 同构，但「预测标签」= ``m.judged_label``
        （LLM 分类判定，**不是** verdict 反推的 pred 文本）。judge 是分类器：LLM 从
        ``group.labels`` 选一个标签，归不进任一声明标签 → ``"其他"``。scorer 用
        ``(gold, judged_label)`` 建真实多类混淆矩阵——这样语义一致但 pred 形态自由
        （自然语言、JSON 取值）的 case 都能正确计入 TP/FP/FN。

        - accuracy = mean(per-case ``llm_judge``)（= judged==gold 的命中率）。
        - macro P/R/F1 只在 **``group.labels``** 上平均（声明序，排除 ``"其他"`` 桶）；
          ``"其他"`` 桶另出 ``其他_count``/``其他_rate`` 诊断键。
        - n=0 或无声明 labels 时全 0。

        ``case_scores`` 与 ``materialized`` 同序（都按原 materialized 顺序过滤到该组）。
        """
        n = len(case_scores)
        if n == 0:
            return {"precision": 0.0, "recall": 0.0, "accuracy": 0.0, "f1": 0.0}
        correctness = [cs.per_metric.get("llm_judge", 0.0) for cs in case_scores]
        accuracy = sum(correctness) / n

        labels = group.labels
        if not labels:
            return {"precision": 0.0, "recall": 0.0, "accuracy": accuracy, "f1": 0.0}

        gold_counts: dict[str, int] = defaultdict(int)
        pred_counts: dict[str, int] = defaultdict(int)
        diag: dict[str, int] = defaultdict(int)
        other_count = 0
        for m in materialized:
            gold = str(m.gold)
            pred = m.judged_label if m.judged_label else "其他"
            if pred == "其他":
                other_count += 1
            gold_counts[gold] += 1
            pred_counts[pred] += 1
            if pred == gold:
                diag[gold] += 1

        precisions: list[float] = []
        recalls: list[float] = []
        f1s: list[float] = []
        for label in labels:
            tp = diag[label]
            fp = pred_counts[label] - tp
            fn = gold_counts[label] - tp
            precision = tp / (tp + fp) if (tp + fp) else 0.0
            recall = tp / (tp + fn) if (tp + fn) else 0.0
            f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) else 0.0
            precisions.append(precision)
            recalls.append(recall)
            f1s.append(f1)

        count = len(labels)
        return {
            "precision": sum(precisions) / count,
            "recall": sum(recalls) / count,
            "f1": sum(f1s) / count,
            "accuracy": accuracy,
            "其他_count": float(other_count),
            "其他_rate": other_count / n,
        }

    @staticmethod
    def _keyword_confusion_aggregate(case_scores: list[CaseScore]) -> dict[str, float]:
        """keyword 组：所有 case 视为正例的二元混淆。

        TP=命中数(keyword_hit=1), FN=未中数(0), FP=TN=0（无负例）。
        recall=accuracy=命中率, precision=1.0, f1=2·h/(1+h)。n=0 时全 0。
        """
        n = len(case_scores)
        if n == 0:
            return {"recall": 0.0, "precision": 0.0, "accuracy": 0.0, "f1": 0.0}
        hits = sum(cs.per_metric.get("keyword_hit", 0.0) for cs in case_scores)
        h = hits / n
        precision = 1.0
        recall = h
        accuracy = h
        f1 = (2 * precision * recall) / (precision + recall) if (precision + recall) else 0.0
        return {"recall": recall, "precision": precision, "accuracy": accuracy, "f1": f1}

    def summarize(
        self,
        case_scores: list[CaseScore],
        materialized: list[MaterializedCase],
    ) -> EvalSummary:
        """组装 EvalSummary：per_case 逐条 + 按组 aggregate + 跨组 overall +
        extraction 分布。

        ``overall`` 含两类聚合（均跨组宏平均，与各组 ``batch_metrics`` 勾选无关——
        overall 是独立的"全套"汇总，各组内部混淆矩阵恒算全 4 项）：

        - ``_overall``：各组 ``_overall``（= composite 均值）的宏平均。因每组都覆盖
          全部 N 条，等价于所有 case×组 composite 的总均值。
        - ``precision``/``recall``/``f1``/``accuracy``：各组混淆 bundle 对应项的宏平均。
          意义：模型跨多任务的平均分类表现。exact_match-only / llm_judge-only 时
          ``accuracy == _overall``（因每组 ``_overall == accuracy``）；混 keyword 组时
          precision 被 keyword 的退化值 1.0 抬高，需结合 ``extraction_summary`` 解读。
        """
        aggregate: dict[str, dict[str, float]] = {}
        group_confusions: dict[str, dict[str, float]] = {}
        group_overalls: list[float] = []
        for g in self._groups:
            mean_bundle, conf = self._group_bundles(g, case_scores, materialized)
            aggregate[g.name] = self._filtered_output(g, mean_bundle, conf)
            group_confusions[g.name] = conf
            group_overalls.append(mean_bundle.get("_overall", 0.0))

        overall: dict[str, float] = {
            "_overall": statistics.fmean(group_overalls) if group_overalls else 0.0,
        }
        for key in _CONFUSION_METRICS:
            values = [group_confusions[g.name].get(key, 0.0) for g in self._groups]
            overall[key] = statistics.fmean(values) if values else 0.0

        extraction_summary: dict[str, int] = {}
        for mc in materialized:
            extraction_summary[mc.extraction_method] = (
                extraction_summary.get(mc.extraction_method, 0) + 1
            )
        return EvalSummary(
            per_case=case_scores,
            aggregate=aggregate,
            overall=overall,
            extraction_summary=extraction_summary,
        )
