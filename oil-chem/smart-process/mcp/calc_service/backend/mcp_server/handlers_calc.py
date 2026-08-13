# -*- coding: utf-8 -*-
"""MCP 独立计算层适配函数（MCP-03/04/05/06/07）。

MCP-03: 批次物理计算（scenario_id 适配）
MCP-04: 批次完整计算（物理+经济）
MCP-05: 阀门组合评估（多场景 + hangmei_ctx）
MCP-06: 组合寻优（多场景 + hangmei_ctx）
MCP-07: 航煤工况初始化（多场景 → HangmeiContext 序列化）
"""

from typing import Dict, List, Optional

from .adapters import ScenarioAdapter, MultiScenarioAdapter
from .serializer import to_jsonable, serialize_hangmei_context, serialize_combination_result


def handle_calculate_physical(
    scenario_id: str,
    input_amount: float,
    yield_mode: str,
    days: int = 1,
    hangmei_mode: bool = False,
    hangmei_m_days: float = 0,
    day_index: float = 0,
    shutdown_intervals: dict = None,
    feed_ratios: Optional[Dict] = None,
    custom_crude_costs: Dict[str, float] = None,
    adapter: ScenarioAdapter = None,
) -> dict:
    """MCP-03: 批次物理计算。

    给定场景和批次参数，计算 BFS 拓扑遍历后的装置进出料流量、利用率。

    Args:
        scenario_id: 原油品种标识（如 "BZ"）
        input_amount: 批次输入量（吨/天）
        yield_mode: 收率模式 ("JIAN1_TO_WAX" / "JIAN1_TO_DIESEL")
        days: 批次天数
        hangmei_mode: 是否航煤工况
        hangmei_m_days: 航煤工况天数（M 值）
        day_index: 天数索引（月内位置）
        shutdown_intervals: 停工区间 {device_id: [(start_h, end_h)]}
        feed_ratios: 进料配比（可选）
        custom_crude_costs: 自定义原油成本
        adapter: 外部注入的 ScenarioAdapter
    Returns:
        {feasible, device_inputs, connection_flows, device_utilization, special_vars}
    """
    from ..calculation.direct_calculator import calculate_physical

    sa = adapter or ScenarioAdapter()
    scenario = sa.load(scenario_id, custom_crude_costs)

    feasible, result = calculate_physical(
        scenario=scenario,
        input_amount=input_amount,
        yield_mode=yield_mode,
        days=days,
        hangmei_mode=hangmei_mode,
        hangmei_m_days=hangmei_m_days,
        day_index=day_index,
        shutdown_intervals=shutdown_intervals,
        feed_ratios=feed_ratios,
    )

    return {
        "feasible": feasible,
        "device_inputs": result.get("device_inputs", {}),
        "connection_flows": result.get("connection_flows", {}),
        "device_utilization": result.get("device_utilization", {}),
        "special_vars": to_jsonable(result.get("special_vars", {})),
    }


def handle_calculate_direct(
    scenario_id: str,
    input_amount: float,
    yield_mode: str,
    days: int = 1,
    hangmei_mode: bool = False,
    hangmei_m_days: float = 0,
    day_index: float = 0,
    plan_month: str = None,
    shutdown_intervals: dict = None,
    capacity_only: bool = False,
    summary_only: bool = False,
    prices: Optional[Dict[str, float]] = None,
    device_costs: Optional[Dict] = None,
    feed_ratios: Optional[Dict] = None,
    custom_crude_costs: Dict[str, float] = None,
    adapter: ScenarioAdapter = None,
) -> dict:
    """MCP-04: 批次完整计算（物理+经济）。

    在物理计算基础上叠加经济效益计算，返回含 explanation 的完整结果。

    Args:
        scenario_id: 原油品种标识
        input_amount: 批次输入量（吨/天）
        yield_mode: 收率模式
        days: 批次天数
        hangmei_mode: 是否航煤工况
        hangmei_m_days: 航煤工况天数
        day_index: 天数索引
        plan_month: 计划月份（价格查表）
        shutdown_intervals: 停工区间
        capacity_only: 仅算能力（不含经济）
        summary_only: 简化模式
        prices: 预加载价格表
        device_costs: 预加载装置加工成本
        feed_ratios: 进料配比
        custom_crude_costs: 自定义原油成本
        adapter: 外部注入的 ScenarioAdapter
    Returns:
        {feasible, explanation, device_inputs, connection_flows, device_utilization}
    """
    from ..calculation.direct_calculator import calculate_direct

    sa = adapter or ScenarioAdapter()
    scenario = sa.load(scenario_id, custom_crude_costs)

    feasible, result = calculate_direct(
        scenario=scenario,
        input_amount=input_amount,
        yield_mode=yield_mode,
        days=days,
        hangmei_mode=hangmei_mode,
        hangmei_m_days=hangmei_m_days,
        day_index=day_index,
        plan_month=plan_month,
        shutdown_intervals=shutdown_intervals,
        capacity_only=capacity_only,
        summary_only=summary_only,
        prices=prices,
        device_costs=device_costs,
        feed_ratios=feed_ratios,
    )

    return {
        "feasible": feasible,
        "explanation": to_jsonable(result.get("explanation", {})),
        "device_inputs": result.get("device_inputs", {}),
        "connection_flows": result.get("connection_flows", {}),
        "device_utilization": result.get("device_utilization", {}),
    }


def handle_evaluate_valve_combination(
    combo: dict,
    batches: list,
    custom_crude_costs: dict,
    hangmei_ctx: dict = None,
    plan_month: str = None,
    capacity_only: bool = False,
    summary_only: bool = False,
    prices: Optional[dict] = None,
    device_costs: Optional[dict] = None,
    feed_ratios: Optional[dict] = None,
    adapter: ScenarioAdapter = None,
) -> dict:
    """MCP-05: 评估单个阀门切换组合的各批次经济效益。

    scenarios 由适配层按 batches 中的 crude_type 自动加载。

    Args:
        combo: 阀门组合定义
        batches: 批次列表
        custom_crude_costs: 原油成本 {crude_type_id: cost}
        hangmei_ctx: MCP-07 产出的航煤上下文 dict（None=不启用航煤）
        plan_month: 计划月份
        capacity_only: 仅算能力
        summary_only: 简化模式
        prices: 预加载价格表
        device_costs: 预加载装置成本
        feed_ratios: 进料配比
        adapter: 外部注入的 ScenarioAdapter
    Returns:
        CombinationResult 序列化 dict（含 feasible/revenue/explanations/...）
    """
    from ..calculation.combination_evaluator import evaluate_combination
    from ..calculation.hangmei_optimizer import HangmeiContext

    sa = adapter or ScenarioAdapter()
    msa = MultiScenarioAdapter(sa)
    scenarios = msa.load_by_batches(batches, custom_crude_costs)

    # hangmei_ctx: dict → HangmeiContext（简化：None 时不启用）
    hm_ctx = None
    if hangmei_ctx and hangmei_ctx.get('enabled'):
        hm_ctx = HangmeiContext(
            enabled=hangmei_ctx.get('enabled', False),
            target=hangmei_ctx.get('target', 0.0),
            total_days=hangmei_ctx.get('total_days', 0.0),
            daily_input_avg=hangmei_ctx.get('daily_input_avg', 0.0),
            active_products=hangmei_ctx.get('active_products', []),
            passive_products=hangmei_ctx.get('passive_products', []),
            active_device_ids=set(hangmei_ctx.get('active_device_ids', [])),
            passive_device_ids=set(hangmei_ctx.get('passive_device_ids', [])),
            allow_window_search=hangmei_ctx.get('allow_window_search', True),
            hangmei_price=hangmei_ctx.get('hangmei_price', 0.0),
            rlydmx_price=hangmei_ctx.get('rlydmx_price', 0.0),
            rlydmx_yields=hangmei_ctx.get('rlydmx_yields', {}),
            product_deltas=hangmei_ctx.get('product_deltas', []),
        )

    result = evaluate_combination(
        combo=combo, batches=batches, scenarios=scenarios,
        custom_crude_costs=custom_crude_costs,
        hangmei_ctx=hm_ctx, plan_month=plan_month,
        capacity_only=capacity_only, summary_only=summary_only,
        prices=prices, device_costs=device_costs, feed_ratios=feed_ratios,
    )

    if result is None:
        return {"feasible": False, "reason": "评估失败"}

    return serialize_combination_result(result)


def handle_optimize_combinations(
    batches: list,
    combinations: list,
    custom_crude_costs: dict,
    hangmei_ctx: dict = None,
    select_month: str = None,
    final_month: str = None,
    prices: Optional[dict] = None,
    device_costs: Optional[dict] = None,
    feed_ratios: Optional[dict] = None,
    adapter: ScenarioAdapter = None,
) -> dict:
    """MCP-06: 遍历所有阀门切换组合，挑出经济效益最优方案。

    Args:
        batches: 批次列表
        combinations: 阀门组合列表
        custom_crude_costs: 原油成本
        hangmei_ctx: MCP-07 产出的航煤上下文 dict
        select_month: 选优月份（上月价）
        final_month: 核算月份（本月价）
        prices: 预加载价格表
        device_costs: 预加载装置成本
        feed_ratios: 进料配比
        adapter: 外部注入的 ScenarioAdapter
    Returns:
        {optimal_combination, optimal_revenue, all_results, ...}
    """
    from ..calculation.combination_optimizer import optimize_combinations
    from ..calculation.hangmei_optimizer import HangmeiContext

    sa = adapter or ScenarioAdapter()
    msa = MultiScenarioAdapter(sa)
    scenarios = msa.load_by_batches(batches, custom_crude_costs)

    # hangmei_ctx: dict → HangmeiContext
    hm_ctx = None
    if hangmei_ctx and hangmei_ctx.get('enabled'):
        hm_ctx = HangmeiContext(
            enabled=hangmei_ctx.get('enabled', False),
            target=hangmei_ctx.get('target', 0.0),
            total_days=hangmei_ctx.get('total_days', 0.0),
            daily_input_avg=hangmei_ctx.get('daily_input_avg', 0.0),
            active_products=hangmei_ctx.get('active_products', []),
            passive_products=hangmei_ctx.get('passive_products', []),
            active_device_ids=set(hangmei_ctx.get('active_device_ids', [])),
            passive_device_ids=set(hangmei_ctx.get('passive_device_ids', [])),
            allow_window_search=hangmei_ctx.get('allow_window_search', True),
            hangmei_price=hangmei_ctx.get('hangmei_price', 0.0),
            rlydmx_price=hangmei_ctx.get('rlydmx_price', 0.0),
            rlydmx_yields=hangmei_ctx.get('rlydmx_yields', {}),
            product_deltas=hangmei_ctx.get('product_deltas', []),
        )

    result = optimize_combinations(
        batches=batches, combinations=combinations,
        custom_crude_costs=custom_crude_costs,
        scenarios=scenarios,
        hangmei_ctx=hm_ctx,
        select_month=select_month, final_month=final_month,
        prices=prices, device_costs=device_costs, feed_ratios=feed_ratios,
    )

    return to_jsonable(result)


def handle_init_hangmei_context(
    batches: list,
    hangmei_target: float,
    custom_crude_costs: dict,
    plan_month: str = None,
    prices: Optional[dict] = None,
    adapter: ScenarioAdapter = None,
) -> dict:
    """MCP-07: 初始化航煤工况上下文。

    根据航煤目标产出，初始化航煤工况上下文。

    Args:
        batches: 批次列表
        hangmei_target: 航煤目标产出（吨）
        custom_crude_costs: 原油成本
        plan_month: 计划月份
        prices: 预加载价格表
        adapter: 外部注入的 ScenarioAdapter
    Returns:
        HangmeiContext 序列化后的 dict（供 MCP-05/06 传入）
    """
    from ..calculation.hangmei_optimizer import build_hangmei_context

    sa = adapter or ScenarioAdapter()
    msa = MultiScenarioAdapter(sa)
    scenarios = msa.load_by_batches(batches, custom_crude_costs)

    ctx = build_hangmei_context(
        batches=batches, scenarios=scenarios,
        hangmei_target=hangmei_target,
        custom_crude_costs=custom_crude_costs,
        plan_month=plan_month, prices=prices,
    )

    return serialize_hangmei_context(ctx)


# ── MCP-15/16: 参数化规则引擎（供 Agent 定制可行性判断与选优策略）──────


def handle_assess_feasibility(
    combination_results: list,
    rules: dict = None,
) -> dict:
    """MCP-15: 参数化可行性判断。

    对 optimize_combinations(MCP-06) 返回的组合列表，按自定义规则重新判定可行性。
    Agent 可根据用户意图组装 rules_json，覆盖默认的硬编码阈值。

    Args:
        combination_results: 组合结果列表（MCP-06 的 combination_results 字段，
            或 MCP-05 单个结果的列表包装）。
            每个元素是 dict，需包含 monthly_load / tank_check_result /
            hangmei_summary / batch_results 字段。
        rules: 规则参数 dict，可选字段：
            - max_load_rate (float, 默认 100.0): 月度平均负荷率上限（百分比）
            - tank_capacity_strict (bool, 默认 True): 罐容违规是否硬约束
            - max_overload_count (int, 默认 0): 允许的超容装置数上限
            - max_overload_ratio (float, 默认 0.0): 允许的超容比例
            - min_hangmei_output (float, 默认 0.0): 航煤最低产出（吨）
            - require_all_feasible (bool, 默认 False): 是否要求所有批次可行
    Returns:
        {assessments: [{combination_id, feasible, near_feasible, infeasible_summary, details}], summary}
    """
    from ..rules.feasibility import assess_feasibility

    results = assess_feasibility(combination_results, rules)

    return {
        "assessments": [
            {
                "combination_id": r.combination_id,
                "feasible": r.feasible,
                "near_feasible": r.near_feasible,
                "infeasible_summary": r.infeasible_summary,
                "details": r.details,
            }
            for r in results
        ],
        "summary": {
            "total": len(results),
            "feasible": sum(1 for r in results if r.feasible),
            "near_feasible": sum(1 for r in results if r.near_feasible),
            "infeasible": sum(1 for r in results if not r.feasible and not r.near_feasible),
        },
    }


def handle_select_optimal(
    combination_results: list,
    strategy: dict = None,
) -> dict:
    """MCP-16: 参数化选优。

    对已评估的组合列表，按自定义选优策略选取最优方案。
    替代原先硬编码的 max(revenue) 逻辑，支持多目标加权、风险规避等策略。

    Args:
        combination_results: 组合结果列表（需已经过 assess_feasibility 判定，
            或包含 feasible/near_feasible 字段的 MCP-06 输出）。
        strategy: 选优策略 dict，可选字段：
            - objective (str, 默认 "revenue"):
              "revenue" | "feasibility_margin" | "risk_averse" | "multi_objective"
            - weights (dict, 默认 {"revenue": 1.0}):
              multi_objective 模式下的权重，可选 revenue/feasibility_margin/hangmei_output
            - prefer_near_feasible (bool, 默认 True): 无可行时是否接受接近可行
            - penalty_factor (float, 默认 1.0): risk_averse 模式超容惩罚系数
            - min_revenue (float, 默认 0.0): 最低收益门槛
    Returns:
        {combination_id, description, total_revenue, score, feasible, near_feasible,
         is_temporary, infeasible_summary, details}
    """
    from ..rules.selection import select_optimal

    result = select_optimal(combination_results, strategy)

    if result is None:
        return {"combination_id": None, "reason": "无满足条件的组合"}

    return {
        "combination_id": result.combination_id,
        "description": result.description,
        "total_revenue": result.total_revenue,
        "score": result.score,
        "feasible": result.feasible,
        "near_feasible": result.near_feasible,
        "is_temporary": result.is_temporary,
        "infeasible_summary": result.infeasible_summary,
        "details": result.details,
    }
