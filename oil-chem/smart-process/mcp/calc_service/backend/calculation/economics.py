# -*- coding: utf-8 -*-
"""可解释性分析（v2 — 纯函数版，无 calc_ctx、无 DB 直连）。

重构要点：
  Stage1: 移除 _build_decision_reasons / _build_key_decisions /
          _build_diversion_scenarios / _build_algorithm_details 等死代码
  Stage2: 统一为 generate_explanation（原 generate_summary 已合并，逻辑完全相同）
  Stage4: 消除日值→批次值的 ×days 循环（compute_revenue/compute_costs 直接输出批次值）
  Phase 2: prices/device_costs 由 Service 层预加载后显式传入，不再查 DB
           classify_cdu_products 每次从 scenario 内存重算（<0.3ms）
"""
from typing import Dict, List, Optional

from ..config import MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL
from ..models.refinery import RefineryScenario, ProcessingUnit
from ..logger import get_logger
from .yield_resolver import resolve_yield_rate
from .revenue_calculator import compute_revenue, get_product_price
from .cost_calculator import compute_costs, CostBreakdown

# 仅计算加工装置的效益（前端只展示加工装置）
# 动态推断：非 tank、非 start 的装置
TARGET_DEVICES = None  # 运行时从 scenario.processing_device_ids 动态获取


def classify_cdu_products(scenario: RefineryScenario) -> dict:
    """从 material_flows 拓扑推导 CDU 侧线产品的分流分组。

    替代硬编码的 CHANG_NAMES / JIAN1_NAME / JIAN_OTHER_NAMES。
    基于 source_to_tank 边的 special_var 分类：
      - 有 special_var='jian1_to_diesel' 或 'jian1_to_wax' 的边 → split（按分流比例）
      - 仅 special_var=None 的边 → fixed，按所在罐的归属判断方向

    Returns:
      {
        'diesel_fixed': List[Product],  # 固定去柴油方向
        'wax_fixed': List[Product],     # 固定去蜡油方向
        'split': List[Product],         # 分流产品（如减一线）
        'diesel_device_names': str,     # 柴油方向目标装置名（汇总行标题用）
        'wax_device_names': str,        # 蜡油方向目标装置名
      }
    """
    cdu_id = scenario.start_device_id

    # 罐 → special_var 集合（从 source_to_tank 边推导）
    tank_sv: Dict[str, set] = {}
    for f in scenario.material_flows.values():
        if f.flow_type == 'source_to_tank' and f.source_device_id == cdu_id:
            if f.tank_id not in tank_sv:
                tank_sv[f.tank_id] = set()
            if f.special_var:
                tank_sv[f.tank_id].add(f.special_var)

    # 罐 → 目标装置名列表（从 tank_to_target 边推导）
    tank_to_dev_names: Dict[str, List[str]] = {}
    for f in scenario.material_flows.values():
        if f.flow_type == 'tank_to_target':
            dev = scenario.devices.get(f.target_device_id)
            if dev:
                tank_to_dev_names.setdefault(f.tank_id, []).append(dev.name)

    diesel_dev_names: set = set()
    wax_dev_names: set = set()
    result = {'diesel_fixed': [], 'wax_fixed': [], 'split': []}

    # 遍历 CDU 的 source_to_tank 边，按 product 分类
    seen_product_ids: set = set()
    for f in scenario.material_flows.values():
        if f.flow_type != 'source_to_tank' or f.source_device_id != cdu_id:
            continue
        prod = scenario.products.get(f.source_product_id)
        if not prod or prod.material_type != 'product':
            continue
        # 同一产品多条边时，优先按 special_var 分类（避免重复计入）
        if prod.id in seen_product_ids:
            continue
        seen_product_ids.add(prod.id)

        sv = f.special_var
        if sv in ('jian1_to_diesel', 'jian1_to_wax'):
            # 分流产品
            result['split'].append(prod)
            # 收集两侧装置名
            for f2 in scenario.material_flows.values():
                if (f2.flow_type == 'source_to_tank'
                        and f2.source_device_id == cdu_id
                        and f2.special_var in ('jian1_to_diesel', 'jian1_to_wax')):
                    for dn in tank_to_dev_names.get(f2.tank_id, []):
                        if f2.special_var == 'jian1_to_diesel':
                            diesel_dev_names.add(dn)
                        else:
                            wax_dev_names.add(dn)
        else:
            # 固定产品 — 按所在罐的 special_var 归属判断方向
            sv_set = tank_sv.get(f.tank_id, set())
            if 'jian1_to_diesel' in sv_set:
                result['diesel_fixed'].append(prod)
                for dn in tank_to_dev_names.get(f.tank_id, []):
                    diesel_dev_names.add(dn)
            elif 'jian1_to_wax' in sv_set:
                result['wax_fixed'].append(prod)
                for dn in tank_to_dev_names.get(f.tank_id, []):
                    wax_dev_names.add(dn)
            # 无 special_var 的罐：无法判断方向，跳过分组

    result['diesel_device_names'] = '/'.join(sorted(diesel_dev_names)) if diesel_dev_names else '柴油'
    result['wax_device_names'] = '/'.join(sorted(wax_dev_names)) if wax_dev_names else '蜡油'
    return result


def _find_start_device_input(device_inputs: Dict[str, float],
                             scenario: RefineryScenario) -> float:
    """起点装置（常减压）的批次投入量。"""
    for device_id, device_input in device_inputs.items():
        device = scenario.devices.get(device_id)
        if device and isinstance(device, ProcessingUnit) and device.is_start:
            return device_input
    return 0.0


# ── Stage2: 轻量级 summary 计算 ──────────────────────────────────────────

def _build_cjy_outputs(scenario: RefineryScenario, result: dict,
                       special_vars: Dict, days: int = 1) -> dict:
    """构建常减压各侧线产出数据 + 直馏柴油/直馏蜡油汇总行。

    减一线按 special_vars 分流：
      jian1_to_diesel → 去柴加（直馏柴油汇总），jian1_to_wax → 去蜡加（直馏蜡油汇总）
    """
    device_inputs = result.get('device_inputs', {})
    cjy_input = 0.0
    for did, din in device_inputs.items():
        dev = scenario.devices.get(did)
        if dev and isinstance(dev, ProcessingUnit) and dev.is_start:
            cjy_input = din
            break
    if cjy_input <= 0:
        return {}

    diesel_val = special_vars.get('jian1_to_diesel', 0)
    wax_val = special_vars.get('jian1_to_wax', 0)
    # jian1_to_diesel/jian1_to_wax 是绝对值（批次吨量），需转为比例：jian1_to_diesel/(sum) 为去柴加比例
    total_xy = diesel_val + wax_val
    if total_xy > 0:
        jian1_ratio_diesel = diesel_val / total_xy  # 减一线去柴加比例
        jian1_ratio_wax = wax_val / total_xy  # 减一线去蜡加比例
    else:
        jian1_ratio_diesel = 0
        jian1_ratio_wax = 0

    # 从 material_flows 拓扑推导侧线分组（每次从 scenario 内存重算，<0.3ms）
    groups = classify_cdu_products(scenario)
    diesel_fixed_ids = {p.id for p in groups['diesel_fixed']}
    wax_fixed_ids = {p.id for p in groups['wax_fixed']}
    split_ids = {p.id for p in groups['split']}

    # 遍历常减压产品，构建各侧线产出
    cdu_id = scenario.start_device_id
    outputs: Dict[str, dict] = {}
    diesel_fixed_total = 0.0  # 固定柴油方向（批次值）
    wax_fixed_total = 0.0     # 固定蜡油方向（批次值）
    split_total = 0.0         # X/Y 分流产品（批次值）

    for p in scenario.products.values():
        if p.source_device_id != cdu_id or p.material_type != 'product':
            continue
        yi = resolve_yield_rate(cdu_id, p, special_vars,
                                hangmei_mode=False, day_index=0, hangmei_m_days=0,
                                yield_switch_device_ids=scenario.yield_switch_device_ids,
                                hangmei_active_device_ids=scenario.hangmei_active_device_ids)
        effective_yield = yi.yield_rate
        product_output = cjy_input * effective_yield
        yield_pct = round(effective_yield * 100, 3)
        batch_output = round(product_output, 4)

        outputs[p.name] = {
            'product_id': p.id,
            'output': batch_output,
            'yield_rate': yield_pct,
            'yield_type': yi.yield_type,
            'yield_reason': yi.reason,
            'price': 0,  # 常减压侧线非最终产品，无售价
        }

        if p.id in split_ids:
            split_total += product_output
        elif p.id in diesel_fixed_ids:
            diesel_fixed_total += product_output
        elif p.id in wax_fixed_ids:
            wax_fixed_total += product_output

    # 汇总行：直馏柴油 = 固定柴油 + 分流×X比例
    diesel_total = diesel_fixed_total + split_total * jian1_ratio_diesel
    if diesel_total > 0:
        outputs[f'直馏柴油(汇总→{groups["diesel_device_names"]})'] = {
            'output': round(diesel_total, 4),
            'yield_rate': round(diesel_total / cjy_input * 100, 3) if cjy_input > 0 else 0,
            'yield_type': '汇总',
            'yield_reason': f'固定+分流×{jian1_ratio_diesel:.2f}',
            'price': 0,
        }

    # 汇总行：直馏蜡油 = 分流×Y比例 + 固定蜡油
    wax_total = split_total * jian1_ratio_wax + wax_fixed_total
    if wax_total > 0:
        outputs[f'直馏蜡油(汇总→{groups["wax_device_names"]})'] = {
            'output': round(wax_total, 4),
            'yield_rate': round(wax_total / cjy_input * 100, 3) if cjy_input > 0 else 0,
            'yield_type': '汇总',
            'yield_reason': f'分流×{jian1_ratio_wax:.2f}+固定',
            'price': 0,
        }

    return outputs


def _calc_ton_metrics(batch_revenue: float, batch_feed: float,
                      batch_process: float, batch_input: float) -> dict:
    """计算批次级吨收指标（以常减压批次投入量为基准）。

    所有指标量纲均为 元/吨：表示每加工1吨原油的收益/成本。
    """
    if batch_input <= 0:
        return {'revenue': 0, 'feed_cost': 0, 'process_cost': 0, 'profit': 0}
    return {
        'revenue': round(batch_revenue / batch_input, 2),        # 收入吨收
        'feed_cost': round(batch_feed / batch_input, 2),          # 原料吨成本
        'process_cost': round(batch_process / batch_input, 2),    # 加工吨成本
        'profit': round((batch_revenue - batch_feed - batch_process) / batch_input, 2),  # 利润吨收
    }


# ── 主入口 ────────────────────────────────────────────────────────────────

def generate_explanation(scenario: RefineryScenario, result: dict,
                         days: int = 1,
                         plan_month: str = None,
                         hangmei_mode: bool = False, day_index: float = 0,
                         hangmei_m_days: float = 0,
                         prices: Optional[Dict[str, float]] = None,
                         device_costs: Optional[Dict[int, float]] = None) -> dict:
    """生成求解结果的可解释性分析（统一入口，PASS 1/3/4 共用）。

    重构要点：
      Stage1: 移除 decision_reasons / key_decisions / diversion_scenarios /
              algorithm_details / summary 等死代码计算
      Stage2: 原 generate_summary 已合并（逻辑完全相同），统一入口
      Stage3: target_devices 下推到 compute_revenue / compute_costs
      Stage4: compute_revenue / compute_costs 直接输出批次值，消除 ×days 循环
      Stage5: 利润用 cost.total_cost 直接计算，避免日均值中间推导
      Phase 2: prices/device_costs 由 Service 层预加载后显式传入

    Scenario 依赖:
        - products: 产品收率/物料类型/物料名称（收入/成本计算的数据源）
        - processing_device_ids: 加工装置列表（确定 target_devices）
        - start_device_id: 常减压装置ID（起点投入量基准）
        - hangmei_active_device_ids: 航煤主动装置（跨界批次判断）
        - yield_switch_device_ids: 收率切换装置（收率字段选择）
        - material_flows: 物流拓扑（_build_cjy_outputs 侧线汇总）
        - get_main_feeds(): 装置主料查询（_build_cjy_outputs）
    """
    device_inputs = result['device_inputs']
    special_vars = result.get('special_vars', {})

    # 产出与收入（统一函数，返回 total_revenue 批次值）
    target_devices = scenario.processing_device_ids
    economic_items, all_product_outputs, total_revenue = compute_revenue(
        scenario, result, special_vars, plan_month,
        target_devices, days,
        hangmei_mode=hangmei_mode, day_index=day_index, hangmei_m_days=hangmei_m_days,
        prices=prices)

    # 成本（统一函数，返回 CostBreakdown）
    cost = compute_costs(scenario, result, plan_month, target_devices, days,
                         all_product_outputs=all_product_outputs,
                         device_costs=device_costs, prices=prices)

    # 利润（收入 − 成本，直接用批次总值）
    batch_revenue = total_revenue
    profit = batch_revenue - cost.total_cost

    # 起点装置批次投入量
    start_device_input = _find_start_device_input(device_inputs, scenario)

    # 吨收指标（以常减压批次投入量为基准，元/吨）
    ton_metrics = _calc_ton_metrics(total_revenue, cost.total_feed_cost, cost.process_cost, start_device_input)

    # 常减压各侧线产出 + 直馏柴油/直馏蜡油汇总
    cjy_outputs = _build_cjy_outputs(scenario, result, special_vars, days)
    if cjy_outputs:
        all_product_outputs[scenario.start_device_id] = cjy_outputs

    # 组装最终结果（Stage4: 无需 ×days 循环，compute_revenue 已输出批次值）
    economic_items.sort(key=lambda x: x['revenue'], reverse=True)

    explanation = {
        # 空字段保持向后兼容（前端不使用）
        'summary': '',
        'key_decisions': [],
        'diversion_scenarios': [],
        'decision_reasons': [],
        'algorithm_details': [],
        # 核心数据
        'economic_analysis': economic_items,
        'all_product_outputs': all_product_outputs,
        'total_revenue': batch_revenue,
        'crude_cost': cost.total_feed_cost,
        'energy_cost': cost.process_cost,
        'total_cost': cost.total_cost,
        'total_profit': profit,
        'total_economic_benefit': profit,
        # 成本明细
        'feed_details': cost.feed_details,
        'process_details': cost.process_details,
        # 元数据
        'days': days,
        'batch_input': start_device_input,
        'ton_metrics': ton_metrics,
    }

    return explanation
