# -*- coding: utf-8 -*-
"""MCP 分析渲染层适配函数（MCP-08/09/10/11）。

S 级纯数据变换函数，封装成本最低。
"""

from typing import Dict, List, Optional

from .adapters import ScenarioAdapter, MultiScenarioAdapter


def handle_aggregate_batch_economics(
    optimal_explanations: list,
    monthly_load: dict = None,
    start_device_id: str = None,
) -> dict:
    """MCP-08: 聚合最优组合各批次经济效益。

    统一聚合各批次 explanation（SSOT），供文本/结构化两个渲染方法共用。

    Args:
        optimal_explanations: 最优组合各批次的 explanation 列表
        monthly_load: 月度负荷数据（含停工折算口径）
        start_device_id: 起始装置ID（常减压）
    Returns:
        聚合后的经济效益 dict
    """
    from ..calculation.economic_reporter import aggregate_economics

    return aggregate_economics(optimal_explanations, monthly_load, start_device_id)


def handle_render_economic_summary(
    agg: dict,
    actual_profit: float = None,
    near_feasible: bool = False,
) -> str:
    """MCP-09: 从聚合数据生成经济效益说明文字。

    纯渲染层，不做任何聚合计算。

    Args:
        agg: MCP-08 聚合后的经济效益 dict
        actual_profit: 实际利润（覆盖计算值）
        near_feasible: 是否近可行方案
    Returns:
        经济效益说明文字
    """
    from ..calculation.economic_reporter import build_economic_explanation

    return build_economic_explanation(agg, actual_profit, near_feasible)


def handle_build_economic_breakdown(
    agg: dict,
    processing_device_ids: list = None,
    actual_profit: float = None,
) -> dict:
    """MCP-10: 从聚合数据生成结构化效益拆解。

    纯数据变换，与 MCP-09 同源同值。

    Args:
        agg: MCP-08 聚合后的经济效益 dict
        processing_device_ids: 加工装置ID列表（JSON 传 list，内部转 set）
        actual_profit: 实际利润（覆盖计算值）
    Returns:
        结构化效益拆解 dict
    """
    from ..calculation.economic_reporter import build_economic_breakdown

    pids = set(processing_device_ids) if processing_device_ids else None
    return build_economic_breakdown(agg, pids, actual_profit)


def handle_analyze_jian1_switch(
    optimal_combo: dict,
    batches: list,
    calc_results: dict = None,
    adapter: ScenarioAdapter = None,
) -> dict:
    """MCP-11: 减一线切换点供需分析。

    分析切换前时段的 CDU 产出 vs 设备月平均负荷消耗。

    Args:
        optimal_combo: 最优组合信息
        batches: 批次列表（含 crude_type 字段，适配层据此加载场景）
        calc_results: 计算结果（可选）
        adapter: 外部注入的 ScenarioAdapter（复用缓存）
    Returns:
        减一线切换分析 dict
    """
    from ..calculation.switch_analysis import build_jian1_switch_analysis

    sa = adapter or ScenarioAdapter()
    msa = MultiScenarioAdapter(sa)
    scenarios = msa.load_by_batches(batches)

    return build_jian1_switch_analysis(optimal_combo, batches, scenarios, calc_results)
