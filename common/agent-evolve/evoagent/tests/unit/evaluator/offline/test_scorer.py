"""OfflineMetricScorer 单元测试（多组版）。

关键：``exact_match`` 裸传（阻抗点已解决）；keyword 组内联二元混淆
（precision=1、recall=accuracy=命中率、f1=2h/(1+h)）；多组 aggregate 按组嵌套。
"""

from __future__ import annotations

import pytest

from evo_agent.evaluator.offline.models import CaseScore, GroupConfig, MaterializedCase
from evo_agent.evaluator.offline.scorer import OfflineMetricScorer


def _em_group(name: str = "是否属实") -> GroupConfig:
    return GroupConfig(
        name=name,
        kind="exact_match",
        pred_field="p",
        gold_field="g",
        batch_metrics=("mean", "precision", "recall", "f1", "accuracy"),
    )


def _kw_group(name: str = "责任判定") -> GroupConfig:
    return GroupConfig(
        name=name,
        kind="keyword",
        pred_field="p",
        keywords=("属实", "供电公司责任"),
        batch_metrics=("mean", "precision", "recall", "f1", "accuracy"),
    )


def _scorer(groups: list[GroupConfig]) -> OfflineMetricScorer:
    return OfflineMetricScorer(groups)


def test_score_case_exact_match_bare_strings() -> None:
    """裸 extracted == 裸 gold → exact_match=1.0（阻抗点）。"""
    scorer = _scorer([_em_group()])
    pm = scorer.score_case("是否属实", "Paris", "Paris")
    assert pm["exact_match"] == 1.0


def test_score_case_mismatch() -> None:
    scorer = _scorer([_em_group()])
    pm = scorer.score_case("是否属实", "London", "Paris")
    assert pm["exact_match"] == 0.0


def test_composite_mean() -> None:
    assert OfflineMetricScorer.composite({"exact_match": 1.0}) == 1.0
    assert OfflineMetricScorer.composite({"a": 0.0, "b": 1.0}) == 0.5
    assert OfflineMetricScorer.composite({}) == 0.0


def test_keyword_hit_compute() -> None:
    """keyword 组 per-case 指标 = keyword_hit：命中任一词=1，未中=0。"""
    scorer = _scorer([_kw_group()])
    keywords = ("属实", "供电公司责任")
    # "责任判定文本" 不含任一关键词 → 0
    assert scorer.score_case("责任判定", "责任判定文本", keywords)["keyword_hit"] == 0.0
    # 命中
    assert scorer.score_case("责任判定", "该诉求属实", keywords)["keyword_hit"] == 1.0
    assert scorer.score_case("责任判定", "不属于供电公司责任", keywords)["keyword_hit"] == 1.0


def _mc(
    case_id: str,
    group: str,
    gold: object,
    extracted: object,
    method: str = "raw",
    judged_label: str = "",
) -> MaterializedCase:
    return MaterializedCase(
        case_id=case_id,
        group=group,
        gold=gold,
        extracted=extracted,
        extraction_method=method,
        judged_label=judged_label,
    )


def _cs(case_id: str, group: str, per_metric: dict[str, float]) -> CaseScore:
    return CaseScore(
        case_id=case_id,
        group=group,
        per_metric=per_metric,
        score=OfflineMetricScorer.composite(per_metric),
    )


def test_aggregate_exact_match_group_mean_and_macro_confusion() -> None:
    """exact_match 组：mean 出 exact_match 均值+_overall；混淆按真实标签 macro 平均，
    四项一般互异（非退化）。4 条否/否否/否是/是 hit3 miss1 → accuracy=0.75；
    类不平衡(否3是1)下 precision/recall/f1 互不相等。"""
    g = _em_group()
    scorer = _scorer([g])
    case_scores = [
        _cs("1", g.name, {"exact_match": 1.0}),
        _cs("2", g.name, {"exact_match": 1.0}),
        _cs("3", g.name, {"exact_match": 0.0}),
        _cs("4", g.name, {"exact_match": 1.0}),
    ]
    materialized = [
        _mc("1", g.name, "否", "否"),
        _mc("2", g.name, "否", "否"),
        _mc("3", g.name, "否", "是"),  # gold 否 pred 是 → 误报
        _mc("4", g.name, "是", "是"),
    ]
    agg = scorer.aggregate_group(g, case_scores, materialized)
    assert agg["exact_match"] == 0.75  # mean
    assert agg["_overall"] == 0.75
    assert agg["accuracy"] == 0.75  # 命中率
    # macro：否 tp2 fp0 fn1 → P1.0 R0.667 F1 0.8；是 tp1 fp1 fn0 → P0.5 R1.0 F1 0.667
    assert agg["precision"] == pytest.approx((1.0 + 0.5) / 2)  # 0.75
    assert agg["recall"] == pytest.approx((2 / 3 + 1.0) / 2)  # 0.8333
    assert agg["f1"] == pytest.approx((0.8 + (2 / 3)) / 2)  # 0.7333
    # 关键：四项不再全等（macro 非退化）
    assert not {agg["precision"], agg["recall"], agg["f1"]} == {agg["accuracy"]}


def test_aggregate_keyword_group_confusion() -> None:
    """keyword 组：内联二元混淆——3 命中 1 未中 → h=0.75。"""
    g = _kw_group()
    scorer = _scorer([g])
    case_scores = [
        _cs("1", g.name, {"keyword_hit": 1.0}),
        _cs("2", g.name, {"keyword_hit": 1.0}),
        _cs("3", g.name, {"keyword_hit": 1.0}),
        _cs("4", g.name, {"keyword_hit": 0.0}),
    ]
    agg = scorer.aggregate_group(g, case_scores, [])
    h = 0.75
    assert agg["recall"] == h
    assert agg["accuracy"] == h
    assert agg["precision"] == 1.0
    assert agg["f1"] == 2 * h / (1 + h)
    # mean 部分：keyword_hit 均值 = 命中率
    assert agg["keyword_hit"] == h


def test_aggregate_keyword_group_all_miss() -> None:
    """全未中 → h=0 → f1=0, recall=0, precision=1（无负例）。"""
    g = _kw_group()
    scorer = _scorer([g])
    case_scores = [_cs("1", g.name, {"keyword_hit": 0.0}), _cs("2", g.name, {"keyword_hit": 0.0})]
    agg = scorer.aggregate_group(g, case_scores, [])
    assert agg["recall"] == 0.0
    assert agg["precision"] == 1.0
    assert agg["f1"] == 0.0
    assert agg["accuracy"] == 0.0


def test_aggregate_selective_metrics_only_emits_requested() -> None:
    """batch_metrics 只勾 ``["f1","accuracy"]`` → 只输出这两项，不含 mean/precision/
    recall/_overall/<per-case 指标名>。键顺序固定（与勾选顺序无关）。"""
    g = GroupConfig(
        name="是否属实",
        kind="exact_match",
        pred_field="p",
        gold_field="g",
        batch_metrics=("f1", "accuracy"),
    )
    scorer = _scorer([g])
    case_scores = [
        _cs("1", g.name, {"exact_match": 1.0}),
        _cs("2", g.name, {"exact_match": 1.0}),
        _cs("3", g.name, {"exact_match": 0.0}),
        _cs("4", g.name, {"exact_match": 1.0}),
    ]
    materialized = [
        _mc("1", g.name, "否", "否"),
        _mc("2", g.name, "否", "否"),
        _mc("3", g.name, "否", "是"),
        _mc("4", g.name, "是", "是"),
    ]
    agg = scorer.aggregate_group(g, case_scores, materialized)
    assert list(agg.keys()) == ["f1", "accuracy"]  # 稳定顺序
    assert agg["accuracy"] == 0.75
    assert agg["f1"] == pytest.approx((0.8 + (2 / 3)) / 2)  # 0.7333
    # 未勾选的不出现
    for absent in ("precision", "recall", "mean", "_overall", "exact_match"):
        assert absent not in agg


def test_summarize_multi_group_nested() -> None:
    """多组：aggregate 按组名嵌套；extraction_summary 统计 method。"""
    em = _em_group("是否属实")
    kw = _kw_group("责任判定")
    scorer = _scorer([em, kw])
    case_scores = [
        _cs("1", em.name, {"exact_match": 1.0}),
        _cs("2", em.name, {"exact_match": 0.0}),
        _cs("1", kw.name, {"keyword_hit": 1.0}),
        _cs("2", kw.name, {"keyword_hit": 0.0}),
    ]
    materialized = [
        _mc("1", em.name, "Paris", "Paris", "raw"),
        _mc("2", em.name, "Paris", "London", "raw"),
        _mc("1", kw.name, ["属实"], ["属实"], "keyword"),
        _mc("2", kw.name, ["属实"], [], "keyword"),
    ]
    summary = scorer.summarize(case_scores, materialized)
    assert set(summary.aggregate.keys()) == {"是否属实", "责任判定"}
    assert summary.aggregate["是否属实"]["exact_match"] == 0.5
    assert summary.aggregate["责任判定"]["keyword_hit"] == 0.5
    assert summary.extraction_summary == {"raw": 2, "keyword": 2}
    # 跨组 overall：_overall = 各组 composite 均值的宏平均 = (0.5+0.5)/2 = 0.5；
    # P/R/F1/Acc = 各组混淆 bundle 对应项的宏平均（与勾选无关，overall 恒算全套）。
    #   是否属实(Paris/London 2 类,1 对 1 错): P=0.5 R=0.25 F1=0.3333 Acc=0.5
    #   责任判定(keyword 1 命中 1 未中):       P=1.0 R=0.5  F1=0.6667 Acc=0.5
    #   → overall: P=0.75 R=0.375 F1=0.5 Acc=0.5
    #     （混 keyword：precision 被 keyword 退化值 1.0 抬高）
    assert summary.overall["_overall"] == pytest.approx(0.5)
    assert summary.overall["precision"] == pytest.approx(0.75)
    assert summary.overall["recall"] == pytest.approx(0.375)
    assert summary.overall["f1"] == pytest.approx(0.5)
    assert summary.overall["accuracy"] == pytest.approx(0.5)
    assert set(summary.overall.keys()) == {
        "_overall",
        "precision",
        "recall",
        "f1",
        "accuracy",
    }


def test_overall_cross_group_macro_exact_match_only() -> None:
    """纯 exact_match 双组（mode2 场景）：overall 的 P/R/F1/Acc = 各组对应项宏平均，
    有意义（各组带正负结构、非退化）；accuracy == _overall（因每组 _overall == accuracy）。

    组A「是否属实」否/否否否是(1对0错... 简化 2 条 1 对 1 错, gold 是/否)：见下；
    组B「供电责任」同理。overall 用未过滤的混淆 bundle，与各组 batch_metrics 无关。
    """
    g_a = _em_group("是否属实")
    g_b = _em_group("是否供电公司责任")
    # 两个组各 2 条，A 组 1 对 1 错，B 组 1 对 1 错
    case_scores = [
        _cs("1", g_a.name, {"exact_match": 1.0}),
        _cs("2", g_a.name, {"exact_match": 0.0}),
        _cs("1", g_b.name, {"exact_match": 1.0}),
        _cs("2", g_b.name, {"exact_match": 0.0}),
    ]
    materialized = [
        _mc("1", g_a.name, "是", "是"),
        _mc("2", g_a.name, "是", "否"),  # gold 是 pred 否 → 漏报
        _mc("1", g_b.name, "否", "否"),
        _mc("2", g_b.name, "否", "是"),  # gold 否 pred 是 → 误报
    ]
    scorer = _scorer([g_a, g_b])
    summary = scorer.summarize(case_scores, materialized)

    # 两组结构对称（gold 各集中在单一标签、1 对 1 错），macro 相同：
    #   是/否各算 per-class：正类 P=1 R=0.5 F1 0.667；负类 P=0 R=0 F1 0
    #   → 每组 macro P=0.5 R=0.25 F1=0.3333 Acc=0.5
    # overall 跨组宏平均：P=0.5 R=0.25 F1=0.3333 Acc=0.5；Acc==_overall
    assert summary.overall["_overall"] == pytest.approx(0.5)
    assert summary.overall["accuracy"] == pytest.approx(0.5)
    assert summary.overall["accuracy"] == summary.overall["_overall"]  # exact_match-only 恒等
    assert summary.overall["precision"] == pytest.approx(0.5)
    assert summary.overall["recall"] == pytest.approx(0.25)
    assert summary.overall["f1"] == pytest.approx(1 / 3)


def _lj_group(name: str = "是否属实") -> GroupConfig:
    return GroupConfig(
        name=name,
        kind="llm_judge",
        pred_field="p",
        gold_field="g",
        labels=("否", "是"),
        extract_key="是否属实",
        batch_metrics=("mean", "precision", "recall", "f1", "accuracy"),
    )


def test_score_case_llm_judge_reads_judged_label_kwarg() -> None:
    """llm_judge per-case 指标 = judged_label==gold；经 judged_label kwarg 注入；缺省 0.0。"""
    scorer = _scorer([_lj_group()])
    assert scorer.score_case("是否属实", "pred", "否", judged_label="否")["llm_judge"] == 1.0
    assert scorer.score_case("是否属实", "pred", "否", judged_label="是")["llm_judge"] == 0.0
    # 缺省（无 kwarg）→ 0.0
    assert scorer.score_case("是否属实", "pred", "否")["llm_judge"] == 0.0


def test_aggregate_llm_judge_macro_non_degenerate() -> None:
    """llm_judge 组：按真实 (gold, judged_label) 多类混淆 macro 平均，非退化。

    4 条（gold 否/否/是/是），judged_label 直接由 fixture 设（模拟 judge 阶段产出）：
      case1 gold=否 judged=否   → TP(否)          llm_judge=1.0
      case2 gold=否 judged=否   → TP(否)          llm_judge=1.0（语义一致但 pred 文本不同也判对）
      case3 gold=是 judged=否   → FP(否)/FN(是)   llm_judge=0.0
      case4 gold=是 judged=是   → TP(是)          llm_judge=1.0
    accuracy=mean(llm_judge)=0.75。macro 在 labels=("否","是") 上平均（排除"其他"）。
    """
    g = _lj_group()
    scorer = _scorer([g])
    case_scores = [
        _cs("1", g.name, {"llm_judge": 1.0}),
        _cs("2", g.name, {"llm_judge": 1.0}),
        _cs("3", g.name, {"llm_judge": 0.0}),
        _cs("4", g.name, {"llm_judge": 1.0}),
    ]
    materialized = [
        _mc("1", g.name, "否", "否", judged_label="否"),
        _mc("2", g.name, "否", "不是", judged_label="否"),
        _mc("3", g.name, "是", "否", judged_label="否"),
        _mc("4", g.name, "是", "是", judged_label="是"),
    ]
    agg = scorer.aggregate_group(g, case_scores, materialized)
    assert agg["llm_judge"] == 0.75  # mean = correctness 均值
    assert agg["_overall"] == 0.75
    assert agg["accuracy"] == 0.75  # judged==gold 命中率
    # 否: tp2 fp1 fn0 → P=2/3 R=1.0 F1=0.8；是: tp1 fp0 fn1 → P=1.0 R=0.5 F1=2/3
    assert agg["precision"] == pytest.approx((2 / 3 + 1.0) / 2)  # 5/6 ≈ 0.8333
    assert agg["recall"] == pytest.approx((1.0 + 0.5) / 2)  # 0.75
    assert agg["f1"] == pytest.approx((0.8 + 2 / 3) / 2)  # 11/15 ≈ 0.7333
    # 关键：precision 非退化（≠1.0，因有真实负例 judged≠gold）
    assert agg["precision"] != 1.0
    # 诊断键：无人归入"其他"桶
    assert agg["其他_count"] == 0.0
    assert agg["其他_rate"] == 0.0


def test_aggregate_llm_judge_other_bucket_diagnostic() -> None:
    """LLM 归不进任一声明标签 → "其他" 桶；桶率另出诊断键，不进 macro 分母。"""
    g = _lj_group()
    scorer = _scorer([g])
    case_scores = [
        _cs("1", g.name, {"llm_judge": 1.0}),
        _cs("2", g.name, {"llm_judge": 0.0}),  # judged="其他" ≠ gold 否
    ]
    materialized = [
        _mc("1", g.name, "否", "否", judged_label="否"),
        _mc("2", g.name, "否", "无关文本", judged_label="其他"),
    ]
    agg = scorer.aggregate_group(g, case_scores, materialized)
    assert agg["其他_count"] == 1.0
    assert agg["其他_rate"] == 0.5
    # "其他"不进 macro 分母。否: tp1 fp0 fn1(第二条 gold=否 但判"其他") → P=1 R=0.5 F1=2/3；
    # 是: tp0 fp0 fn0 → 全 0。
    assert agg["precision"] == pytest.approx((1.0 + 0.0) / 2)  # 0.5
    assert agg["recall"] == pytest.approx((0.5 + 0.0) / 2)  # 0.25
    assert agg["f1"] == pytest.approx((2 / 3 + 0.0) / 2)  # 1/3
    assert agg["accuracy"] == 0.5


def test_summarize_llm_judge_overall_non_degenerate() -> None:
    """单组 llm_judge：overall 与该组 aggregate 一致；precision 非退化。"""
    g = _lj_group()
    scorer = _scorer([g])
    case_scores = [
        _cs("1", g.name, {"llm_judge": 1.0}),
        _cs("2", g.name, {"llm_judge": 0.0}),
    ]
    materialized = [
        _mc("1", g.name, "是", "是", judged_label="是"),
        _mc("2", g.name, "是", "否", judged_label="否"),  # judged≠gold → FP(否)/FN(是)
    ]
    summary = scorer.summarize(case_scores, materialized)
    # 是: tp1 fp0 fn1 → P=1 R=0.5 F1=2/3；否: tp0 fp1 fn0 → P=0 R=0 F1=0
    # macro P=0.5 R=0.25 F1=1/3；accuracy=0.5；==_overall
    assert summary.overall["_overall"] == pytest.approx(0.5)
    assert summary.overall["accuracy"] == pytest.approx(0.5)
    assert summary.overall["accuracy"] == summary.overall["_overall"]  # llm_judge-only 恒等
    assert summary.overall["precision"] == pytest.approx(0.5)  # 非退化
    assert summary.overall["recall"] == pytest.approx(0.25)
    assert summary.overall["f1"] == pytest.approx(1 / 3)
    assert summary.extraction_summary == {"raw": 2}


if __name__ == "__main__":
    import pytest

    pytest.main([__file__, "-v"])
