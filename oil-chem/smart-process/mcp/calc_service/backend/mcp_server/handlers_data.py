# -*- coding: utf-8 -*-
"""MCP 数据预加载 + 可视化层适配函数（MCP-14/12/13）。

MCP-14: 从 DB 预加载价格/成本/配比。
MCP-12: 流程图数据构建。
MCP-13: 装置进料来源拆解。
"""

from typing import Dict, Optional

from .adapters import ScenarioAdapter


def handle_preload_reference_data(
    batches: list,
    plan_month: str,
    custom_crude_costs: dict = None,
    adapter: ScenarioAdapter = None,
) -> dict:
    """MCP-14: 预加载价格/成本/配比。

    按 batches 中的 crude_type 加载场景，调用 SolveService 预加载引用数据。

    Args:
        batches: 批次列表（确定原油品种范围）
        plan_month: 计划月份（价格查表）
        custom_crude_costs: 自定义原油成本 {crude_type_id: cost}
        adapter: 外部注入的 ScenarioAdapter（复用缓存）
    Returns:
        {prices: {...}, device_costs: {...}, feed_ratios: {...}}
    """
    from ..service.solve_service import SolveService
    from .adapters import MultiScenarioAdapter

    sa = adapter or ScenarioAdapter()
    msa = MultiScenarioAdapter(sa)
    scenarios = msa.load_by_batches(batches, custom_crude_costs)

    service = SolveService()
    prices, device_costs, feed_ratios = service._preload_reference_data(
        batches, scenarios, plan_month, custom_crude_costs)

    return {
        "prices": prices,
        "device_costs": device_costs,
        "feed_ratios": feed_ratios,
    }


def handle_build_flow_diagram(
    scenario_id: str,
    device_util: dict,
    device_inputs: dict,
    connection_flows: dict,
    monthly_load: dict = None,
    adapter: ScenarioAdapter = None,
) -> dict:
    """MCP-12: 流程图数据构建（数字孪生视图）。

    构建全装置流程图数据（节点+边），供前端渲染。

    Args:
        scenario_id: 原油品种标识（如 "BZ"）
        device_util: 装置利用率数据
        device_inputs: 装置进料数据
        connection_flows: 连接流量数据
        monthly_load: 月度负荷数据
        adapter: 外部注入的 ScenarioAdapter
    Returns:
        {nodes: [...], edges: [...]}
    """
    from ..calculation.flow_diagram_builder import build_flow_diagram

    sa = adapter or ScenarioAdapter()
    scenario = sa.load(scenario_id)

    return build_flow_diagram(
        scenario=scenario,
        device_util=device_util,
        device_inputs=device_inputs,
        connection_flows=connection_flows,
        monthly_load=monthly_load,
    )


def handle_build_device_input_sources(
    scenario_id: str,
    device_id: str,
    connection_flows: dict,
    special_vars: dict,
    mode: str,
    adapter: ScenarioAdapter = None,
) -> list:
    """MCP-13: 装置进料来源拆解。

    拆解某装置的进料来源（连接级流量），用于"为何超"计算链展示。

    Args:
        scenario_id: 原油品种标识
        device_id: 目标装置ID
        connection_flows: 连接流量数据
        special_vars: 特殊变量 {jian1_to_diesel, jian1_to_wax}
        mode: 收率模式
        adapter: 外部注入的 ScenarioAdapter
    Returns:
        [{source_device, product_name, yield_rate, special_var, flow, ...}, ...]
    """
    from ..calculation.flow_diagram_builder import build_device_input_sources

    sa = adapter or ScenarioAdapter()
    scenario = sa.load(scenario_id)

    return build_device_input_sources(
        scenario=scenario,
        device_id=device_id,
        connection_flows=connection_flows,
        special_vars=special_vars,
        mode=mode,
    )
