# -*- coding: utf-8 -*-
"""MCP 计算层服务封装包。

14 个 MCP 适配函数按层级组织:

    编排入口层 (2):
        handle_solve_refinery_plan          — MCP-01 综合求解
        handle_optimize_valve_switches      — MCP-02 阀门优化

    独立计算层 (5):
        handle_calculate_physical           — MCP-03 批次物理计算
        handle_calculate_direct             — MCP-04 批次完整计算
        handle_evaluate_valve_combination   — MCP-05 阀门组合评估
        handle_optimize_combinations        — MCP-06 组合寻优
        handle_init_hangmei_context         — MCP-07 航煤工况初始化

    分析渲染层 (4):
        handle_aggregate_batch_economics    — MCP-08 批次经济聚合
        handle_render_economic_summary      — MCP-09 经济效益文本说明
        handle_build_economic_breakdown     — MCP-10 效益拆解
        handle_analyze_jian1_switch         — MCP-11 减一线切换点分析

    可视化+数据层 (3):
        handle_build_flow_diagram           — MCP-12 流程图数据
        handle_build_device_input_sources   — MCP-13 装置进料来源拆解
        handle_preload_reference_data       — MCP-14 引用数据预加载

适配层核心类:
    ScenarioAdapter — scenario_id → RefineryScenario（单场景 + 缓存）
    MultiScenarioAdapter — batches → Dict[str, RefineryScenario]（多场景）
"""

# 适配层核心类
from .adapters import ScenarioAdapter, MultiScenarioAdapter

# 序列化工具
from .serializer import to_jsonable, serialize_hangmei_context, serialize_combination_result

# 编排入口层 (MCP-01/02)
from .handlers_orchestrate import (
    handle_solve_refinery_plan,
    handle_optimize_valve_switches,
)

# 独立计算层 (MCP-03/04/05/06/07)
from .handlers_calc import (
    handle_calculate_physical,
    handle_calculate_direct,
    handle_evaluate_valve_combination,
    handle_optimize_combinations,
    handle_init_hangmei_context,
)

# 分析渲染层 (MCP-08/09/10/11)
from .handlers_analysis import (
    handle_aggregate_batch_economics,
    handle_render_economic_summary,
    handle_build_economic_breakdown,
    handle_analyze_jian1_switch,
)

# 可视化+数据层 (MCP-12/13/14)
from .handlers_data import (
    handle_build_flow_diagram,
    handle_build_device_input_sources,
    handle_preload_reference_data,
)

__all__ = [
    # 适配层
    'ScenarioAdapter',
    'MultiScenarioAdapter',
    # 序列化
    'to_jsonable',
    'serialize_hangmei_context',
    'serialize_combination_result',
    # MCP-01/02
    'handle_solve_refinery_plan',
    'handle_optimize_valve_switches',
    # MCP-03/04/05/06/07
    'handle_calculate_physical',
    'handle_calculate_direct',
    'handle_evaluate_valve_combination',
    'handle_optimize_combinations',
    'handle_init_hangmei_context',
    # MCP-08/09/10/11
    'handle_aggregate_batch_economics',
    'handle_render_economic_summary',
    'handle_build_economic_breakdown',
    'handle_analyze_jian1_switch',
    # MCP-12/13/14
    'handle_build_flow_diagram',
    'handle_build_device_input_sources',
    'handle_preload_reference_data',
]
