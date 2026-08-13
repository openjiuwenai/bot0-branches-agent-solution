# -*- coding: utf-8 -*-
"""MCP 编排入口层适配函数（MCP-01/02/17）。

MCP-01: 综合求解 — 排产→批次→阀门枚举→选优（全链路）
MCP-02: 阀门优化 — 基于已存在计划重新优化阀门切换（半链路）
MCP-17: 数据准备 — 加载排产计划→批次划分→阀门组合枚举（分步编排入口）

编排层直接委托 SolveService 实例，不额外加载场景（Service 内部自管）。
"""

from typing import Optional
import copy


def handle_prepare_solve_data(
    plan_month: str,
    shutdown_config: Optional[list] = None,
    plan_source: str = 'lp',
) -> dict:
    """MCP-17: 数据准备 — 加载排产计划→批次划分→阀门组合枚举。

    分步编排的入口工具。从 DB 加载已有排产计划，识别加工批次，
    枚举阀门切换组合，产出供 MCP-06/14/15/16 使用的中间数据。

    Agent 分步编排链：
      prepare_solve_data → preload_reference_data → optimize_combinations
      → assess_feasibility → select_optimal → render_economic_summary

    Args:
        plan_month: 计划月份（如 "2026-07"），用于构造 plan_id 和停工小时索引
        shutdown_config: 停工声明 [{unit, start_time, end_time}]
        plan_source: 'lp' 读 production_plan_details（默认），
                     'cp_sat' 读 cp_sat_plan_details
    Returns:
        {success, plan_id, batches, combinations, custom_crude_costs, batch_count, combination_count}
    """
    from ..service.solve_service import SolveService, _SolveAbort

    service = SolveService()
    repo = service._sched_repo()
    plan_id = f"PLAN-{plan_month.replace('-', '')}"

    try:
        # ① 加载排产计划
        plan = service._produce_plan(
            repo, plan_id, plan_month, [], 0, False, False,
            plan_source=plan_source)

        # ② 批次划分 + 阀门组合枚举
        switches = service._enumerate_switches(
            copy.deepcopy(plan.details), shutdown_config,
            plan_month=plan_month)

        return {
            'success': True,
            'plan_id': plan_id,
            'batches': switches.batches,
            'combinations': switches.combinations,
            'custom_crude_costs': plan.custom_crude_costs,
            'batch_count': len(switches.batches),
            'combination_count': len(switches.combinations),
        }
    except _SolveAbort as e:
        return {'success': False, 'message': str(e)}
    except Exception as e:
        return {'success': False, 'message': f'数据准备失败: {e}'}


def handle_solve_refinery_plan(
    plan_month: str,
    production_plans_input: list,
    monthly_crude_input: float,
    blend_mode: bool = False,
    save_data: bool = True,
    hangmei_target: Optional[float] = None,
    shutdown_config: list = None,
    simplified: bool = True,
    feasibility_rules: dict = None,
    selection_strategy: dict = None,
) -> dict:
    """MCP-01: 综合求解。

    生成/复用计划 → 批次+阀门组合 → 逐组合效益评估 → 选优。

    Args:
        plan_month: 计划月份（如 "2026-07"）
        production_plans_input: 排产计划输入（原油品种+加工量）
        monthly_crude_input: 月度原油加工总量（吨）
        blend_mode: 是否混炼
        save_data: 是否持久化排产结果
        hangmei_target: 航煤目标产出（吨），None=不启用
        shutdown_config: 停工声明 [{unit, start_time, end_time}]
        simplified: 简化模式（裁剪组合列表大对象）
        feasibility_rules: 可行性规则参数（传给 MCP-15 的 rules）
        selection_strategy: 选优策略参数（传给 MCP-16 的 strategy）
    Returns:
        {success, optimal_combination, economic_summary, flow_diagram, ...}
    """
    from ..service.solve_service import SolveService

    service = SolveService()
    return service.comprehensive_solve(
        plan_month=plan_month,
        production_plans_input=production_plans_input,
        monthly_crude_input=monthly_crude_input,
        blend_mode=blend_mode,
        save_data=save_data,
        hangmei_target=hangmei_target,
        shutdown_config=shutdown_config,
        simplified=simplified,
        feasibility_rules=feasibility_rules,
        selection_strategy=selection_strategy,
    )


def handle_optimize_valve_switches(plan_id: str,
                                   feasibility_rules: dict = None,
                                   selection_strategy: dict = None) -> dict:
    """MCP-02: 优化阀门切换位置。

    基于已存在计划，不重新生成排产、不启用航煤工况。

    Args:
        plan_id: 已有排产计划 ID（如 "PLAN-202607"）
        feasibility_rules: 可行性规则参数（传给 MCP-15 的 rules）
        selection_strategy: 选优策略参数（传给 MCP-16 的 strategy）
    Returns:
        {success, optimal_combination, economic_summary, ...}
    """
    from ..service.solve_service import SolveService

    service = SolveService()
    return service.optimize_valve(
        plan_id=plan_id, simplified=True,
        feasibility_rules=feasibility_rules,
        selection_strategy=selection_strategy,
    )
