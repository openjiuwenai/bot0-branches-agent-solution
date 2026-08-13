# -*- coding: utf-8 -*-
"""减一线切换点供需分析（从 solve_service.py 下移）。"""
from typing import Dict, Optional

from ..models.refinery import RefineryScenario, ProcessingUnit, Tank
from ..config import MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL
from .economics import classify_cdu_products


def build_jian1_switch_analysis(optimal_combo, batches,
                                scenarios: Dict[str, RefineryScenario],
                                calc_results=None) -> dict:
    """构建减一线切换点供需分析（切换前时段的 CDU 产出 vs 设备月平均负荷消耗）。

    切换前批次用 initial_mode 判定减一线归属：
      MODE_JIAN1_TO_DIESEL → 减一线归直馏柴油（→柴加）
      MODE_JIAN1_TO_WAX → 减一线归直馏蜡油（→蜡加）

    CDU 直馏柴油产出 = Σ(daily_input × (常一+常二+常三+减一线×X_ratio) × days)
    CDU 直馏蜡油产出 = Σ(daily_input × (减一线×Y_ratio+减二+减三+减四) × days)

    设备月平均负荷消耗（与 build_batch_details_list 口径一致）：
      月平均日处理量 = Σ(各批次总主料量) ÷ 月总天数
      切换前消耗 = 月平均日处理量 × 切换前CDU天数
      加工天数 = 切换前总主料量 ÷ 月平均日处理量

    Scenario 依赖:
        - start_device_id: 常减压装置ID
        - material_flows: 物流拓扑
        - devices: 装置字典
        - products: 产品字典
        - 透传给 classify_cdu_products: start_device_id, material_flows, devices, products
    """
    if not optimal_combo or not batches:
        return {}

    switch_pos = optimal_combo.get('switch_position', 0)
    initial_mode = optimal_combo.get('initial_mode', MODE_JIAN1_TO_DIESEL)

    # 切换前批次（switch_pos=0 表示不切换，取全部批次）
    before_batches = batches[:switch_pos] if switch_pos > 0 else batches
    if not before_batches:
        return {}

    # 切换时间点 = 切换前批次天数累加（CDU时间）
    switch_day = sum(float(b.get('days', 0) or 0) for b in before_batches)
    switch_batch_id = batches[switch_pos]['batch_id'] if switch_pos < len(batches) else None

    # 月总天数（全月所有批次）
    total_days = sum(float(b.get('days', 0) or 0) for b in batches)
    if total_days <= 0:
        return {}

    # 减一线归属比例
    if initial_mode == MODE_JIAN1_TO_DIESEL:
        jian1_to_diesel_ratio = 1.0
        jian1_to_wax_ratio = 0.0
    else:
        jian1_to_diesel_ratio = 0.0
        jian1_to_wax_ratio = 1.0

    # ── 从 calc_results 提取各批次的 device_utilization，计算月平均日处理量 ──
    # 月平均日处理量 = Σ(下游装置 input × days) / total_days
    # 需要区分柴加方向和蜡加方向的装置
    diesel_device_ids = set()
    wax_device_ids = set()

    # 用第一个场景的拓扑确定柴加/蜡加装置
    first_scenario = None
    for b in batches:
        sc = scenarios.get(b.get('crude_type', ''))
        if sc:
            first_scenario = sc
            break
    if not first_scenario:
        return {}

    cdu_id = first_scenario.start_device_id
    groups = classify_cdu_products(first_scenario)

    # 通过 CDU→tank 物流的 special_var 判断柴油/蜡油方向
    # 再通过 tank→device 物流找到下游加工装置
    for flow in first_scenario.get_downstream_flows(cdu_id):
        to_dev = first_scenario.devices.get(flow.to_device_id)
        if not to_dev or not isinstance(to_dev, Tank):
            continue
        product = first_scenario.products.get(flow.from_product_id)
        if not product or product.material_type != 'product':
            continue
        tank_id = flow.to_device_id
        # 找该 tank 流向的加工装置
        for flow2 in first_scenario.get_downstream_flows(tank_id):
            target_dev = first_scenario.devices.get(flow2.to_device_id)
            if target_dev and isinstance(target_dev, ProcessingUnit) and not target_dev.is_start:
                if flow.special_var == 'jian1_to_diesel':
                    diesel_device_ids.add(flow2.to_device_id)
                elif flow.special_var == 'jian1_to_wax':
                    wax_device_ids.add(flow2.to_device_id)

    # 计算月平均日处理量
    diesel_monthly_input = 0.0
    wax_monthly_input = 0.0
    for i, b in enumerate(batches):
        b_days = float(b.get('days', 0) or 0)
        if b_days <= 0:
            continue
        if calc_results and i < len(calc_results):
            calc = calc_results[i] or {}
            du = calc.get('device_utilization', {})
            for did in diesel_device_ids:
                u = du.get(did, {})
                diesel_monthly_input += float(u.get('input', 0) or 0)
            for did in wax_device_ids:
                u = du.get(did, {})
                wax_monthly_input += float(u.get('input', 0) or 0)

    diesel_avg_daily = diesel_monthly_input / total_days if total_days > 0 else 0.0
    wax_avg_daily = wax_monthly_input / total_days if total_days > 0 else 0.0

    # ── CDU 产出 + 切换前消耗 ──
    diesel_cdu_output = 0.0
    wax_cdu_output = 0.0

    for b in before_batches:
        b_crude = b.get('crude_type', '')
        b_days = float(b.get('days', 0) or 0)
        b_total = b.get('total_input', 0) or 0
        b_daily = b_total / b_days if b_days > 0 else 0
        if b_daily <= 0:
            continue

        scenario = scenarios.get(b_crude)
        if not scenario:
            continue

        groups = classify_cdu_products(scenario)
        diesel_fixed_yield = sum(p.yield_rate for p in groups['diesel_fixed'])
        wax_fixed_yield = sum(p.yield_rate for p in groups['wax_fixed'])
        split_yield = sum(p.yield_rate for p in groups['split'])

        diesel_yield = diesel_fixed_yield + split_yield * jian1_to_diesel_ratio
        wax_yield = wax_fixed_yield + split_yield * jian1_to_wax_ratio

        diesel_cdu_output += b_daily * diesel_yield * b_days
        wax_cdu_output += b_daily * wax_yield * b_days

    # 设备消耗 = 月平均日处理量 × 切换前CDU天数
    diesel_device_demand = diesel_avg_daily * switch_day
    wax_device_demand = wax_avg_daily * switch_day

    # 加工天数 = 切换前CDU产出 ÷ 月平均日处理量
    diesel_processing_days = diesel_cdu_output / diesel_avg_daily if diesel_avg_daily > 0 else 0
    wax_processing_days = wax_cdu_output / wax_avg_daily if wax_avg_daily > 0 else 0

    mode_cn = '→ 柴油加氢' if initial_mode == MODE_JIAN1_TO_DIESEL else '→ 蜡油加氢'

    return {
        # ── ① 结论型（供需缺口，Agent 最关心）──
        'diesel_diff': round(diesel_cdu_output - diesel_device_demand, 1),
        'wax_diff': round(wax_cdu_output - wax_device_demand, 1),
        # ── ② 切换信息 ──
        'switch_day': round(switch_day, 2),
        'switch_batch_id': switch_batch_id,
        'initial_mode': initial_mode,
        'initial_mode_cn': mode_cn,
        # ── ③ 过程参数 ──
        'diesel_avg_daily': round(diesel_avg_daily, 1),
        'wax_avg_daily': round(wax_avg_daily, 1),
        'diesel_processing_days': round(diesel_processing_days, 2),
        'wax_processing_days': round(wax_processing_days, 2),
        # ── ④ 明细（供详细分析）──
        'diesel': {
            'cdu_output': round(diesel_cdu_output, 1),
            'device_demand': round(diesel_device_demand, 1),
            'diff': round(diesel_cdu_output - diesel_device_demand, 1),
        },
        'wax': {
            'cdu_output': round(wax_cdu_output, 1),
            'device_demand': round(wax_device_demand, 1),
            'diff': round(wax_cdu_output - wax_device_demand, 1),
        },
    }
