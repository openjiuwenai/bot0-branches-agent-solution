# -*- coding: utf-8 -*-
"""组合评估：CombinationResult、组合评估、经济重算与共享辅助函数。"""
import copy
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from ..logger import get_logger
from ..models.refinery import RefineryScenario
from ..config import DEFAULT_JIAN1_YIELD, DEVICE_LYJQ, DEVICE_CYJQ
from ..config import MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL
from .direct_calculator import calculate_direct
from .economics import generate_explanation
from .tank_capacity_checker import TankCapacityChecker
from .batch_builder import build_batch_details_list, build_monthly_load, merge_intervals
from .hangmei_optimizer import (
    HangmeiContext,
    _compute_device_effective_input,
    _resolve_hangmei_yield,
    _compute_combo_hangmei,
)


@dataclass
class CombinationResult:
    """单个组合的评估结果。feasible=False 表示该组合不可行（含超容瓶颈信息）。"""
    combination_id: str
    description: str
    switch_position: int
    initial_mode: str
    switches: Dict
    total_revenue: float
    total_cost: float = 0.0  # 月度折减后成本（用于选优对比）
    batch_results: List[dict] = field(default_factory=list)
    explanations: List[dict] = field(default_factory=list)   # 各批次 explanation
    calc_results: List[dict] = field(default_factory=list)    # 各批次完整 calc_result
    hangmei_summary: dict = field(default_factory=dict)       # 航煤工况摘要（enabled=False 时空）
    # 可行性标记（默认 True，超容时 False）+ 瓶颈信息
    feasible: bool = True
    bottleneck_devices: List[dict] = field(default_factory=list)  # 超容装置列表（首个不可行批次）
    infeasible_summary: str = ""                                  # 不可行原因摘要（如"蜡加超容1877吨"）
    tank_check_result: dict = field(default_factory=dict)         # 罐容段级检测结果
    batch_details: List[dict] = field(default_factory=list)        # 含 processing_days（提前构建）
    monthly_load: dict = field(default_factory=dict)              # 月度负荷（提前构建）


def _precompute_hangmei_batch_data(
    batches: List[dict],
    scenarios: Dict[str, RefineryScenario],
    switches: dict,
    _hangmei_precompute_cache: Optional[dict] = None,
) -> tuple:
    """预计算每批次航煤主动/被动装置的有效输入量和收率。

    返回:
        (active_effective_inputs, passive_effective_inputs, batch_yields)
    """
    active_effective_inputs = []
    passive_effective_inputs = []
    batch_yields = []

    for b in batches:
        b_crude = b.get('crude_type', '')
        b_scenario = scenarios.get(b_crude)
        if b_scenario is None:
            continue
        b_days = float(b.get('days', len(b.get('daily_inputs', []))))
        b_total = b.get('total_input', 0)
        b_daily = b_total / b_days if b_days > 0 else 0
        b_mode = switches.get(b['batch_id'], switches.get(batches[0]['batch_id'], MODE_JIAN1_TO_DIESEL))

        hm_cache_key = (b['batch_id'], b_mode)
        cached_hm = _hangmei_precompute_cache.get(hm_cache_key) if _hangmei_precompute_cache else None
        if cached_hm:
            active_eff_b, passive_eff_b, active_yields_b, passive_yields_b = cached_hm
        else:
            hm_products_b = b_scenario.get_products_by_material('航煤')
            active_ids_b = b_scenario.hangmei_active_device_ids
            passive_ids_b = {p.source_device_id for p in hm_products_b
                             if p.source_device_id not in active_ids_b}

            active_eff_b = {}
            active_yields_b = {}
            for did in active_ids_b:
                active_eff_b[did] = _compute_device_effective_input(b_scenario, b_daily, b_mode, did)
                hp_d = next((p for p in hm_products_b if p.source_device_id == did), None)
                active_yields_b[did] = _resolve_hangmei_yield(hp_d, b_mode)
            passive_eff_b = {}
            passive_yields_b = {}
            for did in passive_ids_b:
                passive_eff_b[did] = _compute_device_effective_input(b_scenario, b_daily, b_mode, did)
                hp_d = next((p for p in hm_products_b if p.source_device_id == did), None)
                passive_yields_b[did] = _resolve_hangmei_yield(hp_d, b_mode)

            if _hangmei_precompute_cache is not None:
                _hangmei_precompute_cache[hm_cache_key] = (active_eff_b, passive_eff_b, active_yields_b, passive_yields_b)

        active_effective_inputs.append(active_eff_b)
        passive_effective_inputs.append(passive_eff_b)
        batch_yields.append({
            'active': active_yields_b,
            'passive': passive_yields_b,
        })

    return active_effective_inputs, passive_effective_inputs, batch_yields


def _apply_monthly_capacity_reduction(
    batch_results: List[dict],
    calc_results: List[dict],
    capacity_only: bool = False,
    logger=None,
) -> tuple:
    """月度加工能力折减：按装置顺序累计原料量，超容部分按比例折减收益。

    返回:
        (deduction_amount, batch_ratios)
        - deduction_amount: 折减总金额（调用方从 total_revenue 中扣减）
        - batch_ratios: {batch_idx: {device_id: ratio}}
    """
    batch_ratios: dict = {}
    if not batch_results or not calc_results or capacity_only:
        return 0.0, batch_ratios

    # 1. 聚合停工时长（合并重叠区间）
    raw_iv: dict = {}
    for b in batch_results:
        si = b.get('shutdown_intervals') or {}
        for dev_id, intervals in si.items():
            raw_iv.setdefault(dev_id, []).extend(intervals)
    shutdown_hours_by_dev = {d: sum(e - s for s, e in merge_intervals(iv))
                             for d, iv in raw_iv.items()}

    # 2. 全月天数
    total_days = sum(float(b.get('days', 0) or
                           (b.get('end_day', 0) - b.get('start_day', 0)))
                     for b in batch_results) or 31.0

    # 3. 收集每个装置在各批次的 (revenue, 总主料量, days, batch_idx)
    device_batches: dict = {}
    for i, b in enumerate(batch_results):
        calc = calc_results[i] or {}
        exp = calc.get('explanation') or {}
        ea = exp.get('economic_analysis') or []
        feed_details = exp.get('feed_details') or []
        dev_main_feed: dict = {}
        for fd in feed_details:
            fd_did = fd.get('device_id')
            if not fd_did:
                continue
            items = fd.get('items') or []
            main_total = sum(float(it.get('feed_qty', 0) or 0)
                             for it in items if it.get('label') == '主料')
            dev_main_feed[fd_did] = main_total
        days = float(b.get('days', 0) or
                     (b.get('end_day', 0) - b.get('start_day', 0)) or 0)
        for item in ea:
            did = item.get('device_id')
            rev = float(item.get('revenue', 0) or 0)
            inp = dev_main_feed.get(did, float(item.get('input_amount', 0) or 0))
            if did and rev != 0:
                device_batches.setdefault(did, []).append((rev, inp, days, i))

    # 4. 对每个加工装置做顺序折减
    total_deduction = 0.0
    for did, items in device_batches.items():
        sh = shutdown_hours_by_dev.get(did, 0)
        eff_days = max(total_days - sh / 24.0, 0.1)
        cap_day = 0.0
        load_pct = 100.0
        for calc in calc_results:
            du = (calc or {}).get('device_utilization') or {}
            u = du.get(did) or {}
            cap_day = float(u.get('safety_stock_thrd', 0) or 0)
            load_pct = float(u.get('refinery_unit_load_percent', 100) or 100)
            if cap_day > 0:
                break
        if cap_day <= 0:
            continue
        full_capacity = cap_day * eff_days
        capacity = full_capacity * (load_pct / 100.0)

        cumulative = 0.0
        for rev, inp, days, batch_idx in items:
            raw_material = inp
            remaining = capacity - cumulative
            if remaining <= 0:
                batch_ratio = 0.0
                exp_i = (calc_results[batch_idx] or {}).get('explanation') or {}
                batch_profit = float(exp_i.get('total_economic_benefit', 0) or 0)
                batch_total_revenue = float(exp_i.get('total_revenue', 0) or 0)
                if batch_total_revenue > 0:
                    device_profit = batch_profit * (rev / batch_total_revenue)
                    total_deduction += device_profit
            elif cumulative + raw_material > capacity:
                batch_ratio = remaining / raw_material if raw_material > 0 else 0.0
                exp_i = (calc_results[batch_idx] or {}).get('explanation') or {}
                batch_profit = float(exp_i.get('total_economic_benefit', 0) or 0)
                batch_total_revenue = float(exp_i.get('total_revenue', 0) or 0)
                if batch_total_revenue > 0:
                    device_profit = batch_profit * (rev / batch_total_revenue)
                    total_deduction += device_profit * (1 - batch_ratio)
            else:
                batch_ratio = 1.0
            batch_ratios.setdefault(batch_idx, {})[did] = batch_ratio
            cumulative += raw_material

    return total_deduction, batch_ratios


def _apply_hangmei_output_correction(
    hangmei_summary: dict,
    calc_results: List[dict],
    batch_ratios: dict,
    combo_id: str = '',
    logger=None,
) -> dict:
    """航煤产出修正：用 calc_results 真实航煤产量替代估算值，并按 batch_ratios 加权折减。

    原地修改 hangmei_summary 并返回。
    """
    if hangmei_summary.get('active') and calc_results:
        active_dids = {d['device_id'] for d in hangmei_summary.get('active_devices', [])}
        passive_dids = {d['device_id'] for d in hangmei_summary.get('passive_devices', [])}

        effective_H = 0.0
        for i, hd in enumerate(hangmei_summary.get('h_default_details', [])):
            calc_i = calc_results[i] if i < len(calc_results) else {}
            exp_i = (calc_i or {}).get('explanation') or {}
            ea_i = exp_i.get('economic_analysis') or []
            batch_actual_hm = 0.0
            for item in ea_i:
                did = item.get('device_id', '')
                if did in active_dids or did in passive_dids:
                    for prod in item.get('products', []):
                        if '航煤' in prod.get('product_name', ''):
                            batch_actual_hm += float(prod.get('output', 0) or 0)

            devs = batch_ratios.get(i, {})
            if devs and batch_actual_hm > 0:
                active_ratios = [devs.get(d, 1.0) for d in active_dids if d in devs]
                passive_ratios = [devs.get(d, 1.0) for d in passive_dids if d in devs]
                active_ratio = sum(active_ratios) / len(active_ratios) if active_ratios else 1.0
                passive_ratio = sum(passive_ratios) / len(passive_ratios) if passive_ratios else 1.0
                active_hm = 0.0
                passive_hm = 0.0
                for item in ea_i:
                    did = item.get('device_id', '')
                    for prod in item.get('products', []):
                        if '航煤' in prod.get('product_name', ''):
                            out = float(prod.get('output', 0) or 0)
                            if did in active_dids:
                                active_hm += out
                            elif did in passive_dids:
                                passive_hm += out
                total_hm = active_hm + passive_hm
                if total_hm > 0:
                    ratio = (active_hm * active_ratio + passive_hm * passive_ratio) / total_hm
                else:
                    ratio = 1.0
            else:
                ratio = 1.0

            effective_H += batch_actual_hm * ratio

        hangmei_summary['effective_H'] = round(effective_H, 0)
        hangmei_gap = max(0, hangmei_summary.get('target', 0) - effective_H)
        hangmei_summary['hangmei_gap'] = round(hangmei_gap, 0)
        if hangmei_gap > 0:
            hangmei_summary['feasible'] = False
            if logger and combo_id:
                logger.info(f"[组合{combo_id}航煤工况] 实际产出={effective_H:.0f}吨, "
                            f"缺口={hangmei_gap:.0f}吨 (目标={hangmei_summary['target']:.0f}吨, "
                            f"估算产出={hangmei_summary.get('actual_H', 0):.0f}吨)")
    else:
        hangmei_summary['effective_H'] = hangmei_summary.get('actual_H', 0)
        hangmei_summary['hangmei_gap'] = 0

    return hangmei_summary


def _build_batch_details_with_overload(
    batch_results: List[dict],
    calc_results: List[dict],
    explanations: List[dict],
) -> tuple:
    """构建 batch_details + monthly_load，并重算超容装置的 processing_days/daily_consumption。

    返回:
        (batch_details, monthly_load)
    """
    batch_details: List[dict] = []
    monthly_load: dict = {}
    try:
        batch_details = build_batch_details_list(batch_results, calc_results, explanations)
        monthly_load = build_monthly_load(batch_details)
        ml_devices = (monthly_load or {}).get('devices') or []
        overloaded_dids = {d['device_id'] for d in ml_devices if d.get('is_overloaded')}
        for bd in (batch_details or []):
            du = bd.get('device_utilization') or {}
            feed_map = {fd.get('device_id'): fd for fd in (bd.get('feed_details') or [])}
            for did, u in du.items():
                if not u or u.get('type') in ('tank', 'start') or did not in overloaded_dids:
                    continue
                fd = feed_map.get(did)
                if not fd:
                    continue
                main_items = [i for i in (fd.get('items') or []) if i.get('label') == '主料']
                if not main_items:
                    continue
                total_main_feed = sum(float(i.get('feed_qty', 0)) for i in main_items)
                if total_main_feed <= 0:
                    continue
                cap = float(u.get('safety_stock_thrd', 0) or 0)
                load_pct = float(u.get('refinery_unit_load_percent', 100) or 100)
                if cap > 0:
                    processable = cap * (load_pct / 100.0)
                    u['daily_consumption'] = round(processable, 1)
                    u['processing_days'] = round(total_main_feed / processable, 2) if processable > 0 else 0
    except Exception:
        batch_details = []
        monthly_load = {}

    return batch_details, monthly_load


# ── 单组合评估 ────────────────────────────────────────────────────────────

def evaluate_combination(combo: dict, batches: List[dict],
                         scenarios: Dict[str, RefineryScenario],
                         custom_crude_costs: Dict,
                         hangmei_ctx: Optional[HangmeiContext] = None,
                         plan_month: str = None,
                         logger=None,
                         capacity_only: bool = False,
                         summary_only: bool = False,
                         prices: Optional[Dict[str, float]] = None,
                         device_costs: Optional[Dict[int, float]] = None,
                         feed_ratios: Optional[Dict[str, Dict[str, float]]] = None,
                         _physical_cache: dict = None,
                         _hangmei_precompute_cache: dict = None) -> Optional[CombinationResult]:
    """评估单个阀门切换组合的各批次经济效益。

    合并自 comprehensive_solve 与 optimize_valve_switching 中重复的批次循环。

    Args:
        combo: 组合 dict（含 combination_id/description/switches/...）
        batches: 批次列表
        scenarios: 按原油品种预加载的场景字典
        custom_crude_costs: 自定义原油成本
        hangmei_ctx: 航煤工况上下文，None 或 enabled=False 即非航煤
        plan_month: 计划月份，None 表示不按月份取价
    Returns:
        CombinationResult；任一批次不可行时返回 None

    Scenario 依赖:
        - products: 产品字典（直接访问）
        - intermediate_tank_ids: 中间罐ID列表（直接访问）
        - devices: 装置字典（直接访问）
        - material_flows: 物流字典（直接访问）
        - 继承 calculate_direct 全部依赖（透传给 calculate_direct）
    """
    if logger is None:
        logger = get_logger()

    switches = combo['switches']
    total_revenue = 0.0
    total_cost = 0.0  # 月度折减时同步调整成本
    batch_results: List[dict] = []
    explanations: List[dict] = []
    calc_results: List[dict] = []
    # 组合可行性跟踪：任一批次超容则组合不可行；收集全部超容批次瓶颈供前端展示
    combo_feasible = True
    combo_bottlenecks: List[dict] = []

    logger.info(f"计算组合{combo['combination_id']}: {combo.get('switch_desc', '')}")

    # ── 组合级航煤 M/N 计算 + 最优时段搜索（仅航煤工况启用时）──
    combo_hangmei_mode = False
    combo_hangmei_m_days = 0.0
    hangmei_best_start = 0.0
    hangmei_summary: dict = {}
    if hangmei_ctx and hangmei_ctx.enabled and hangmei_ctx.product:
        active_effective_inputs, passive_effective_inputs, batch_yields = \
            _precompute_hangmei_batch_data(
                batches, scenarios, switches, _hangmei_precompute_cache)
        combo_hangmei_mode, combo_hangmei_m_days, hangmei_best_start, hangmei_summary = _compute_combo_hangmei(
            combo, batches, hangmei_ctx, logger,
            active_effective_inputs=active_effective_inputs,
            passive_effective_inputs=passive_effective_inputs,
            batch_yields=batch_yields)
    else:
        logger.info(f"[组合{combo['combination_id']}] 航煤工况未启用")

    # ── 逐批次计算 ──
    for batch in batches:
        batch_id = batch['batch_id']
        mode = switches.get(batch_id, MODE_JIAN1_TO_WAX)
        total_input = batch['total_input']
        days = float(batch.get('days', len(batch.get('daily_inputs', []))))
        batch_crude_type = batch['crude_type']

        # 按原油品种获取场景
        scenario = scenarios.get(batch_crude_type)
        if scenario is None:
            logger.warning(f"[组合{combo['combination_id']}批次{batch_id}] 未找到原油场景: {batch_crude_type}，跳过")
            continue

        # 当前批次的绝对天数索引（月内位置）
        current_day_index = 0.0
        for prev_batch in batches:
            if prev_batch['batch_id'] == batch_id:
                break
            current_day_index += float(prev_batch.get('days', len(prev_batch.get('daily_inputs', []))))

        # 航煤时段判断：[hangmei_best_start, hangmei_best_start + M) 内为航煤期
        # 传给 calculate_direct 的 day_index 用相对偏移（减去 best_start），
        # 使 yield_resolver 的 day_index < hangmei_m_days 判断自然生效
        hm_end = hangmei_best_start + combo_hangmei_m_days
        is_hangmei_period = (combo_hangmei_mode
                             and hangmei_best_start <= current_day_index < hm_end)
        batch_end_index = current_day_index + days
        crosses_boundary = (combo_hangmei_mode
                            and current_day_index < hm_end < batch_end_index)
        # 相对偏移：航煤时段前的批次 day_index < 0（自然落入非航煤期）
        shifted_day_index = current_day_index - hangmei_best_start

        logger.info(f"[组合{combo['combination_id']}批次{batch_id}] 开始计算")
        logger.info(f"[组合{combo['combination_id']}批次{batch_id}] 策略: {mode}, 天数: {days:.4f}, 总输入: {total_input:.2f}吨")
        logger.info(f"[组合{combo['combination_id']}批次{batch_id}] 绝对天数索引: {current_day_index:.4f}, 航煤偏移: {hangmei_best_start:.4f}, 相对索引: {shifted_day_index:.4f}")
        logger.info(f"[组合{combo['combination_id']}批次{batch_id}] 航煤工况启用: {combo_hangmei_mode}, M天数: {combo_hangmei_m_days:.4f}, 航煤时段: [{hangmei_best_start:.1f}, {hm_end:.1f})")
        logger.info(f"[组合{combo['combination_id']}批次{batch_id}] 是否处于航煤工况阶段: {is_hangmei_period}")
        if crosses_boundary:
            logger.info(f"[组合{combo['combination_id']}批次{batch_id}] WARNING: 批次跨越航煤/非航煤工况边界")

        # P0缓存：物理计算只依赖 (batch_id, yield_mode, in_hangmei_window)
        # 经济计算依赖 (plan_month, summary_only, hangmei_mode, day_index, hangmei_m_days)
        # 缓存命中且经济参数匹配时，跳过 generate_explanation（省2n²→≤2n次经济计算）
        in_hangmei_window = (combo_hangmei_mode and 0 <= shifted_day_index < combo_hangmei_m_days)
        cache_key = (batch_id, mode, in_hangmei_window)
        econ_key = (plan_month, bool(summary_only), bool(capacity_only),
                    combo_hangmei_mode, shifted_day_index, combo_hangmei_m_days)

        cached_entry = _physical_cache.get(cache_key) if _physical_cache else None
        if cached_entry:
            # 缓存命中：复用物理结果
            calc_result = copy.deepcopy(cached_entry['result'])
            if cached_entry.get('econ_key') == econ_key and cached_entry.get('explanation') is not None:
                # 经济参数完全匹配（同价格月+同航煤参数），直接复用explanation，跳过经济计算
                calc_result['explanation'] = copy.deepcopy(cached_entry['explanation'])
            else:
                # 经济参数不匹配（航煤跨界/不同价格月），仅重跑经济计算
                explanation = generate_explanation(scenario, calc_result, days, plan_month=plan_month,
                                            hangmei_mode=combo_hangmei_mode, day_index=shifted_day_index,
                                            hangmei_m_days=combo_hangmei_m_days,
                                            prices=prices, device_costs=device_costs)
                calc_result['explanation'] = explanation
                # 更新缓存中的explanation和econ_key，供后续命中
                cached_entry['explanation'] = copy.deepcopy(explanation)
                cached_entry['econ_key'] = econ_key
            feasible = calc_result.get('feasible', True)
        else:
            # 缓存未命中：正常调用 calculate_direct
            feasible, calc_result = calculate_direct(
                scenario, total_input, mode, logger,
                total_input_amount=total_input, days=days,
                hangmei_mode=combo_hangmei_mode, hangmei_m_days=combo_hangmei_m_days,
                day_index=shifted_day_index, plan_month=plan_month,
                shutdown_intervals=batch.get('shutdown_intervals'),
                capacity_only=capacity_only,
                summary_only=summary_only,
                prices=prices,
                device_costs=device_costs,
                feed_ratios=feed_ratios,
            )
            # 存入缓存（物理结果 + explanation + 经济参数key）
            if _physical_cache is not None:
                cached_result = copy.deepcopy(calc_result)
                cached_explanation = cached_result.pop('explanation', None)
                _physical_cache[cache_key] = {
                    'result': cached_result,
                    'explanation': cached_explanation,
                    'econ_key': econ_key,
                }

        # 减一线分流：MODE_JIAN1_TO_WAX 全去蜡油加氢，MODE_JIAN1_TO_DIESEL 全去柴油加氢
        # （在不可行判断前算好，超容批次也带分流信息，供前端展示完整计算链）
        # 停工折减：减一线去向装置停工时，实际被消化的量按 keep_ratio 缩减；
        # 未消化的部分物理上累积在中间罐（hc_tank_01/gyrly_tank_01）中，罐容检测会反映。
        jian1_yield = DEFAULT_JIAN1_YIELD
        for product_id, product in scenario.products.items():
            if 'jian1' in product_id:
                jian1_yield = product.yield_rate
                break
        jian1_production = total_input * jian1_yield

        # 计算减一线去向装置的停工折减比例
        si = batch.get('shutdown_intervals') or {}
        batch_hours = days * 24 if days else 0

        def _keep_ratio(dev_id):
            """减一线去向装置的停工折减：返回非停工时长占比。"""
            intervals = si.get(dev_id, [])
            if not intervals or batch_hours <= 0:
                return 1.0
            shutdown_hours = sum(e - s for s, e in intervals)
            return 1.0 - min(shutdown_hours / batch_hours, 1.0)

        if mode == MODE_JIAN1_TO_WAX:
            jian1_to_wax = jian1_production * _keep_ratio(DEVICE_LYJQ)
            jian1_to_diesel = 0
        else:  # MODE_JIAN1_TO_DIESEL
            jian1_to_diesel = jian1_production * _keep_ratio(DEVICE_CYJQ)
            jian1_to_wax = 0

        # 单批次日均超容不否定组合可行性（实际可逐日微调进料，月度未超容即可行）。
        # 仅收集瓶颈信息用于展示，继续累加理论收益供横向对比。
        if not feasible:
            bts = calc_result.get('bottleneck_devices', [])
            combo_bottlenecks.extend(bts)
            summary_parts = [f"{d['device_name']}超容{d['excess']:.0f}吨" for d in bts[:3]]
            infeasible_summary = "；".join(summary_parts) if summary_parts else calc_result.get('message', '不可行')
            logger.info(f"批次{batch_id}日均超容(不否定组合): {infeasible_summary}")
        else:
            infeasible_summary = ''

        explanation = calc_result.get('explanation', {})
        revenue = explanation.get('total_economic_benefit', 0) if not capacity_only else 0
        total_revenue += revenue
        # 累计成本（用于月度折减时同步调整）
        batch_cost = explanation.get('total_cost', 0) if not capacity_only else 0
        total_cost += batch_cost

        batch_results.append({
            'batch_id': batch_id,
            'crude_type': batch['crude_type'],
            'start_day': batch['start_day'],
            'end_day': batch['end_day'],
            'days': batch.get('days', len(batch.get('daily_inputs', []))),
            'total_input': total_input,
            'mode': mode,
            'daily_input': total_input / days if days > 0 else total_input,
            'revenue': revenue,
            'jian1_to_diesel': jian1_to_diesel,
            'jian1_to_wax': jian1_to_wax,
            'is_hangmei_period': is_hangmei_period,
            'shutdown_intervals': batch.get('shutdown_intervals', {}),
        })
        explanations.append(explanation)
        calc_results.append(calc_result)

    # 组合可行性：任一批次超容即不可行。汇总全部超容批次的瓶颈装置（按 device_id 去重）
    all_bottlenecks: List[dict] = []
    seen_bids = set()
    for b in combo_bottlenecks:
        if b['device_id'] not in seen_bids:
            seen_bids.add(b['device_id'])
            all_bottlenecks.append(b)
    # 日均超容信息（不否定可行性，仅用于展示）

    # 提前构建 batch_details（含 processing_days），供罐容校验和后续 _enrich 复用
    batch_details, monthly_load = _build_batch_details_with_overload(
        batch_results, calc_results, explanations)

    # 日均超容信息
    if all_bottlenecks:
        summary_parts = [f"{d['device_name']}超容{d['excess']:.0f}吨" for d in all_bottlenecks[:3]]
        final_infeasible_summary = "；".join(summary_parts) if summary_parts else ""
        logger.info(f"组合{combo['combination_id']}: 含日均超容批次，理论收益={total_revenue:.2f}元 | {final_infeasible_summary}")
    else:
        final_infeasible_summary = ""

    # ── 罐容段级检测（违规即否定可行性，选优阶段也执行）──
    tank_check_result = {}
    if batches and calc_results:
        # 取最后一个批次的场景（罐参数不随油种变化）
        last_crude = batches[-1]['crude_type'] if batches else None
        scenario_for_tank = scenarios.get(last_crude) if last_crude else None
        if scenario_for_tank:
            hm_enabled = bool(hangmei_summary.get('enabled'))
            hm_best_start = float(hangmei_summary.get('hangmei_start', 0) or 0)
            hm_m_days = float(hangmei_summary.get('m_days', 0) or 0)
            try:
                tank_check_result = TankCapacityChecker.check(
                    batches=batches,
                    calc_results=calc_results,
                    intermediate_tank_ids=scenario_for_tank.intermediate_tank_ids,
                    devices=scenario_for_tank.devices,
                    material_flows=scenario_for_tank.material_flows,
                    hangmei_enabled=hm_enabled,
                    hangmei_best_start=hm_best_start,
                    hangmei_m_days=hm_m_days,
                    switches=switches,
                    batch_details=batch_details,
                )
                # 罐容违规 → 不可行
                if tank_check_result.get('has_violations'):
                    combo_feasible = False
                    vlist = tank_check_result.get('violations') or []
                    tank_names = sorted({v.get('tank_name', v.get('tank_id', '?')) for v in vlist})
                    tank_summary = "、".join(tank_names[:3])
                    if final_infeasible_summary:
                        final_infeasible_summary += f"；罐容超限：{tank_summary}"
                    else:
                        final_infeasible_summary = f"罐容超限：{tank_summary}"
            except Exception as e:
                logger.warning(f"组合{combo['combination_id']}: 罐容段级检测异常: {e}")

    # ── 月度加工能力折减（v2）─────────────────────────────────────────
    # 停工时 CDU 仍全量产出原料注入罐，这些原料需在非停工期补加工。
    # 若全月原料产出 > 装置加工能力（safety_stock_thrd × effective_days × 负荷率%），
    # 超出部分无法加工，对应收入应扣除。
    # 折减方式：按批次顺序累计原料量，超出能力后的批次收入折减（先产先加工）。
    # batch_ratios: {batch_idx: {device_id: processing_ratio}}  跟踪每批次各装置的实际加工比例
    # 用于航煤产出折减修正：月负荷折减后部分批次未加工/部分加工，航煤实际产出 < actual_H
    _deduction, batch_ratios = _apply_monthly_capacity_reduction(
        batch_results, calc_results, capacity_only=capacity_only, logger=logger)
    total_revenue -= _deduction

    # ── 航煤产出修正（实际口径 + 负荷折减） ─────────────────────
    # 两个修正维度：
    #   (1) 口径修正：_compute_combo_hangmei 的 actual_H 是估算口径（effective_input × yield × days），
    #       偏高于 calculate_direct 的实际物料平衡计算。用 calc_results 中的真实航煤产量替代。
    #   (2) 负荷折减：月负荷折减后部分批次未加工/部分加工（缓存在中间罐），航煤实际产出进一步降低。
    _apply_hangmei_output_correction(
        hangmei_summary, calc_results, batch_ratios,
        combo_id=combo['combination_id'], logger=logger)

    return CombinationResult(
        combination_id=combo['combination_id'],
        description=combo['description'],
        switch_position=combo.get('switch_position', 0),
        initial_mode=combo.get('initial_mode', MODE_JIAN1_TO_WAX),
        switches=switches,
        total_revenue=total_revenue,
        total_cost=total_cost,
        batch_results=batch_results,
        explanations=explanations,
        calc_results=calc_results,
        hangmei_summary=hangmei_summary,
        feasible=combo_feasible,
        bottleneck_devices=all_bottlenecks,
        infeasible_summary=final_infeasible_summary,
        tank_check_result=tank_check_result,
        batch_details=batch_details,
        monthly_load=monthly_load,
    )


def recompute_combination_economics(
    prev_result: CombinationResult,
    batches: List[dict],
    scenarios: Dict[str, RefineryScenario],
    plan_month: str,
    hangmei_ctx: Optional[HangmeiContext] = None,
    logger=None,
    custom_crude_costs: Dict = None,
    prices: Optional[Dict[str, float]] = None,
    device_costs: Optional[Dict[int, float]] = None,
    feed_ratios: Optional[Dict[str, Dict[str, float]]] = None,
    _econ_cache: Dict = None) -> CombinationResult:
    """复用已有物理计算结果，仅用新价格月重算经济效益。

    P4修复：航煤搜索依赖价格，用本月价重跑航煤搜索而非直接复用PASS 1结果。
    若航煤参数变化(hangmei_changed=True)，受影响批次需重算物理计算；
    否则复用 PASS 1 物理结果，仅重跑 generate_explanation。
    """
    if logger is None:
        logger = get_logger()

    switches = prev_result.switches
    calc_results = list(prev_result.calc_results)  # 浅拷贝，hangmei_changed时替换元素不影响原列表
    old_batch_results = prev_result.batch_results
    old_hangmei_summary = prev_result.hangmei_summary or {}

    total_revenue = 0.0
    total_cost = 0.0
    new_explanations: List[dict] = []
    new_batch_results: List[dict] = []

    # P4: 用本月价重跑航煤搜索（航煤搜索依赖价格，不能直接复用PASS 1结果）
    old_best_start = float(old_hangmei_summary.get('hangmei_start', 0) or 0)
    old_m_days = float(old_hangmei_summary.get('m_days', 0) or 0)
    hangmei_summary: dict = {}

    if hangmei_ctx and hangmei_ctx.enabled and hangmei_ctx.product:
        # 用本月价重跑航煤预计算
        active_effective_inputs, passive_effective_inputs, batch_yields = \
            _precompute_hangmei_batch_data(
                batches, scenarios, switches)
        new_mode, new_m_days, new_best_start, new_hangmei_summary = _compute_combo_hangmei(
            {'combination_id': prev_result.combination_id, 'switches': switches},
            batches, hangmei_ctx, logger,
            active_effective_inputs=active_effective_inputs,
            passive_effective_inputs=passive_effective_inputs,
            batch_yields=batch_yields)

        # 比较航煤参数是否变化
        hangmei_changed = (abs(new_best_start - old_best_start) > 1e-6 or abs(new_m_days - old_m_days) > 1e-6)

        combo_hangmei_mode = new_mode
        combo_hangmei_m_days = new_m_days
        hangmei_best_start = new_best_start
        hangmei_summary = new_hangmei_summary
    else:
        combo_hangmei_mode = False
        combo_hangmei_m_days = 0.0
        hangmei_best_start = 0.0
        hangmei_summary = dict(old_hangmei_summary)
        hangmei_changed = False

    for i, batch in enumerate(batches):
        batch_id = batch['batch_id']
        calc = calc_results[i] if i < len(calc_results) else {}
        old_br = old_batch_results[i] if i < len(old_batch_results) else {}

        batch_crude_type = batch['crude_type']
        scenario = scenarios.get(batch_crude_type)
        if scenario is None:
            continue

        days = float(batch.get('days', len(batch.get('daily_inputs', []))))
        total_input = batch['total_input']
        daily_input = total_input / days if days > 0 else total_input

        # 航煤时段判断（复用 PASS 1 的搜索结果）
        current_day_index = 0.0
        for prev_batch in batches:
            if prev_batch['batch_id'] == batch_id:
                break
            current_day_index += float(prev_batch.get('days', len(prev_batch.get('daily_inputs', []))))

        hm_end = hangmei_best_start + combo_hangmei_m_days
        is_hangmei_period = (combo_hangmei_mode
                             and hangmei_best_start <= current_day_index < hm_end)
        shifted_day_index = current_day_index - hangmei_best_start

        # P0经济缓存：同(batch, mode, 航煤参数)的explanation结果跨组合复用
        mode = switches.get(batch_id, switches.get(batches[0]['batch_id'], MODE_JIAN1_TO_DIESEL))
        if is_hangmei_period:
            econ_key = (batch_id, mode, True, round(shifted_day_index, 6), round(combo_hangmei_m_days, 6))
        else:
            econ_key = (batch_id, mode, False)

        if _econ_cache is not None and econ_key in _econ_cache:
            explanation = copy.deepcopy(_econ_cache[econ_key])
        else:
            # 复用物理结果，仅重跑 generate_explanation（用新 plan_month 取价）
            explanation = generate_explanation(
                scenario, calc, days, plan_month,
                hangmei_mode=combo_hangmei_mode, day_index=shifted_day_index,
                hangmei_m_days=combo_hangmei_m_days,
                prices=prices, device_costs=device_costs)
            if _econ_cache is not None:
                _econ_cache[econ_key] = copy.deepcopy(explanation)

        # 更新 calc_result 中的 explanation
        calc['explanation'] = explanation

        revenue = explanation.get('total_economic_benefit', 0)
        total_revenue += revenue
        batch_cost = explanation.get('total_cost', 0)
        total_cost += batch_cost

        new_batch_results.append({
            'batch_id': batch_id,
            'crude_type': batch['crude_type'],
            'start_day': batch['start_day'],
            'end_day': batch['end_day'],
            'days': batch.get('days', len(batch.get('daily_inputs', []))),
            'total_input': total_input,
            'mode': old_br.get('mode', MODE_JIAN1_TO_WAX),
            'daily_input': daily_input,
            'revenue': revenue,
            'jian1_to_diesel': old_br.get('jian1_to_diesel', 0),
            'jian1_to_wax': old_br.get('jian1_to_wax', 0),
            'is_hangmei_period': is_hangmei_period,
            'shutdown_intervals': batch.get('shutdown_intervals', {}),
        })
        new_explanations.append(explanation)

    # ── 月度加工能力折减（与 evaluate_combination 同逻辑，用新价格重算）──
    _deduction, batch_ratios = _apply_monthly_capacity_reduction(
        new_batch_results, calc_results, logger=logger)
    total_revenue -= _deduction

    # ── 航煤产出修正（用新价格重算）──
    hangmei_summary = dict(old_hangmei_summary)
    _apply_hangmei_output_correction(
        hangmei_summary, calc_results, batch_ratios, logger=logger)

    # 提前构建 batch_details（含 processing_days），与 evaluate_combination 同逻辑
    batch_details, monthly_load = _build_batch_details_with_overload(
        new_batch_results, calc_results, new_explanations)

    return CombinationResult(
        combination_id=prev_result.combination_id,
        description=prev_result.description,
        switch_position=prev_result.switch_position,
        initial_mode=prev_result.initial_mode,
        switches=switches,
        total_revenue=total_revenue,
        total_cost=total_cost,
        batch_results=new_batch_results,
        explanations=new_explanations,
        calc_results=calc_results,
        hangmei_summary=hangmei_summary,
        feasible=prev_result.feasible,
        bottleneck_devices=prev_result.bottleneck_devices,
        infeasible_summary=prev_result.infeasible_summary,
        tank_check_result=prev_result.tank_check_result,
        batch_details=batch_details,
        monthly_load=monthly_load,
    )
