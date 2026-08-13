# -*- coding: utf-8 -*-
"""组合遍历与最优挑选。"""
from typing import Dict, List, Optional

from ..logger import get_logger
from ..models.results import CombinationOutput
from ..models.refinery import RefineryScenario
from .combination_evaluator import evaluate_combination, CombinationResult
from .hangmei_optimizer import HangmeiContext


# ── 全组合优化 ────────────────────────────────────────────────────────────

def optimize_combinations(batches: List[dict], combinations: List[dict],
                          custom_crude_costs: Dict,
                          scenarios: Dict[str, RefineryScenario],
                          hangmei_ctx: Optional[HangmeiContext] = None,
                          select_month: str = None,
                          final_month: str = None,
                          logger=None,
                          prices: Optional[Dict[str, float]] = None,
                          device_costs: Optional[Dict[int, float]] = None,
                          feed_ratios: Optional[Dict[str, Dict[str, float]]] = None,
                          return_pass1: bool = False) -> dict:
    """遍历所有组合，挑出经济效益最优方案。

    合并自 comprehensive_solve 与 optimize_valve_switching 的组合遍历 + 最优挑选。

    双价格月口径（选方案/算效益解耦）：
      - select_month：选优遍历(summary_only=True)与最优挑选的取价月份（上月，用上月价挑组合）。
      - final_month：Stage2 对选中组合全量重算(summary_only=False)的取价月份（本月，核实实际效益）。
      传 None 时该阶段不按月取价（用默认价），兼容 optimize_valve 等无价格月场景。

    Returns:
        dict 含:
          - combination_results: 所有可行组合的列表
          - optimal_combination: 最优组合（None 表示无可行解）
          - optimal_details: {combination_id, switches, description}（None 表示无可行解）
          - optimal_revenue: 最优总效益（无可行解时为 float('-inf')，路由层可归零）
          - optimal_explanations: 最优组合各批次 explanation
          - optimal_calc_results: 最优组合各批次完整 calc_result

    Scenario 依赖:
        - 继承 evaluate_combination 全部依赖（透传给 evaluate_combination）
    """
    if logger is None:
        logger = get_logger()

    combination_results: List[CombinationOutput] = []
    # R2优化：保留 PASS 1 的 CombinationResult 对象，供 PASS 4 复用物理计算
    pass1_results: Dict[int, CombinationResult] = {}

    # P1优化：价格预加载已由 Service 层完成，prices 直接传入
    # 后续 evaluate_combination 中的 get_product_price 全部命中 prices dict

    optimal_revenue = float('-inf')
    optimal_combination = None
    optimal_details = None
    optimal_explanations: List[dict] = []
    optimal_calc_results: List[dict] = []
    optimal_hangmei_summary: dict = {}

    # P0+P1缓存：跨组合共享，物理计算和航煤预计算只依赖 (batch_id, yield_mode, in_hangmei_window)
    physical_cache: dict = {}
    hangmei_precompute_cache: dict = {}

    for combo in combinations:
        result = evaluate_combination(
            combo, batches, scenarios, custom_crude_costs,
            hangmei_ctx=hangmei_ctx,
            plan_month=select_month, logger=logger,
            summary_only=True,  # 选优阶段：用 select_month(上月)价，summary模式只算利润数字
            prices=prices,
            device_costs=device_costs,
            feed_ratios=feed_ratios,
            _physical_cache=physical_cache,
            _hangmei_precompute_cache=hangmei_precompute_cache)

        # R2优化：保留 CombinationResult 供 PASS 4 复用物理计算
        pass1_results[result.combination_id] = result

        # 不可行组合也保留进 combination_results（feasible=False + 瓶颈信息 + 理论收益），
        # 让前端能展示"为何不可行"并横向对比理论收益，但不参与最优挑选。
        if not result.feasible:
            combo_output = CombinationOutput(
                combination_id=result.combination_id,
                description=result.description,
                switch_position=result.switch_position,
                initial_mode=result.initial_mode,
                switches=result.switches,
                total_revenue=result.total_revenue,
                batch_results=result.batch_results,
                # 保留航煤摘要：单批次口径判不可行的组合，月度口径可能重判可行，
                # 届时仍需展示航煤工况信息，故不清空。
                hangmei_summary=result.hangmei_summary,
                feasible=False,
                bottleneck=result.bottleneck_devices,
                infeasible_summary=result.infeasible_summary,
                tank_check_result=result.tank_check_result,
                # batch_details/monthly_load 由 _finalize_combination_outputs 从 pass1_results 填充
                batch_details=result.batch_details,
                monthly_load=result.monthly_load,
            )
            combination_results.append(combo_output)
            logger.info(f"组合{result.combination_id}: 不可行(理论收益={result.total_revenue:.2f}元) — {result.infeasible_summary}")
            continue

        combo_output = CombinationOutput(
            combination_id=result.combination_id,
            description=result.description,
            switch_position=result.switch_position,
            initial_mode=result.initial_mode,
            switches=result.switches,
            total_revenue=result.total_revenue,
            batch_results=result.batch_results,
            hangmei_summary=result.hangmei_summary,
            feasible=True,
            bottleneck=[],
            infeasible_summary='',
            tank_check_result=result.tank_check_result,
            batch_details=result.batch_details,
            monthly_load=result.monthly_load,
        )
        combination_results.append(combo_output)
        logger.info(f"组合{result.combination_id}: 总效益={result.total_revenue:.2f}元")

        # 最优挑选仅限可行组合：超容组合的理论收益基于超负荷进料量，不可执行
        if result.feasible and result.total_revenue > optimal_revenue:
            optimal_revenue = result.total_revenue
            optimal_combination = combo_output
            optimal_details = {
                'combination_id': result.combination_id,
                'switches': result.switches,
                'description': result.description,
            }
            optimal_explanations = list(result.explanations)
            optimal_calc_results = list(result.calc_results)
            optimal_hangmei_summary = dict(result.hangmei_summary)

    # Stage2 已删除：最优组合的全量重算(summary_only=False)统一延迟到
    # comprehensive_solve 月度重选后执行，避免与 PASS 3 重复计算。
    # final_month 预加载也移至 comprehensive_solve 统一处理。

    # 最优方案日志
    if optimal_combination:
        logger.info(f"========== 最优阀门切换方案 ==========")
        logger.info(f"最优组合ID: {optimal_details['combination_id']}")
        logger.info(f"最优描述: {optimal_details['description']}")
        logger.info(f"最优总效益: {optimal_revenue:.2f}元")
        logger.info(f"各批次详情:")
        for br in optimal_combination.batch_results:
            logger.info(f"  批次{br['batch_id']}({br['crude_type']}): "
                        f"第{br['start_day']}-{br['end_day']}天, "
                        f"加工量: {br['total_input']:.2f}吨, "
                        f"模式: {br['mode']}, "
                        f"效益: {br['revenue']:.2f}元")

    # 所有组合效益对比
    logger.info(f"========== 所有组合效益对比 ==========")
    for cr in sorted(combination_results, key=lambda x: x.total_revenue, reverse=True):
        logger.info(f"组合{cr.combination_id}: {cr.description} -> 效益: {cr.total_revenue:.2f}元")

    # 字段顺序：结论先行，明细后行；pass1_results 仅在 return_pass1=True 时返回
    result = {
        # ── ① 结论型 ──
        'optimal_details': optimal_details,
        'optimal_revenue': optimal_revenue,
        'optimal_combination': optimal_combination,
        # ── ② 最优组合明细 ──
        'optimal_explanations': optimal_explanations,
        'optimal_calc_results': optimal_calc_results,
        'optimal_hangmei_summary': optimal_hangmei_summary,
        # ── ③ 全量组合列表 ──
        'combination_results': combination_results,
    }
    if return_pass1:
        result['pass1_results'] = pass1_results
    return result
