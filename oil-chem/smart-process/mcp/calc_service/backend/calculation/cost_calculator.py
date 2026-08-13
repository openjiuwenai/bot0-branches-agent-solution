# -*- coding: utf-8 -*-
"""成本计算（v4 — 纯函数版，无 calc_ctx、无 DB 直连）。

重构要点：
  - total_feed 直接引用 direct_calculator 的 total_feeds 结果
  - 合并 compute_costs + compute_costs_summary 为统一函数（方案B）
  - target_devices 过滤下推（非目标装置跳过）
  - Phase 2: device_costs/prices 由 Service 层预加载后显式传入，不再查 DB
"""
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from ..models.refinery import RefineryScenario, ProcessingUnit
from ..logger import get_logger
from .revenue_calculator import get_product_price


# ── 装置加工成本工具 ──────────────────────────────────────────────────────

def _get_device_cost_for_id(api_costs: Dict[int, float],
                            solve_device_id: str, default_cost: float,
                            scenario: RefineryScenario = None) -> float:
    """根据 calc_service 装置 ID 获取加工成本。

    优先从 scenario.devices[device_id].backend_device_id 查找映射，
    向后兼容：若 scenario 为 None 或设备无 backend_device_id，返回 default_cost。
    """
    backend_id = None
    if scenario:
        dev = scenario.devices.get(solve_device_id)
        if dev and isinstance(dev, ProcessingUnit) and dev.backend_device_id:
            backend_id = dev.backend_device_id
    if backend_id and backend_id in api_costs:
        return api_costs[backend_id]
    return default_cost


# ── 成本计算结果 ──────────────────────────────────────────────────────────

@dataclass
class CostBreakdown:
    """单批次成本的结构化结果。"""
    total_feed_cost: float       # 主料 + 辅料 总成本（批次值）
    process_cost: float          # 装置加工成本（批次值）
    total_cost: float            # 进料 + 加工（批次值）
    daily_feed_cost: float       # 日均值（批次值 / days，向后兼容）
    daily_process_cost: float
    daily_cost: float
    feed_details: List[dict] = field(default_factory=list)
    process_details: List[dict] = field(default_factory=list)


# ── 成本计算（方案B：合并 summary/full）────────────────────────────────────

def compute_costs(scenario: RefineryScenario, result: dict,
                  plan_month: str,
                  target_devices: set,
                  days: int = 1,
                  all_product_outputs: dict = None,
                  device_costs: Optional[Dict[int, float]] = None,
                  prices: Optional[Dict[str, float]] = None) -> CostBreakdown:
    """计算进料成本 / 加工成本 / 总成本。

    统一函数（合并原 compute_costs + compute_costs_summary）。
    始终返回 CostBreakdown，caller 按需取字段。

    Args:
        scenario: 数据场景
        result: 求解结果（取 device_inputs）
        plan_month: 计划月份
        target_devices: 仅计算这些装置
        days: 周期天数
        device_costs: 预加载的装置加工成本 {backend_device_id: unit_cost}，由 Service 层传入
        prices: 预加载的产品价格 {product_id: price}，由 Service 层传入
    Returns:
        CostBreakdown（含日值和批次值字段）

    Scenario 依赖:
        - devices: 装置字典（加工成本查表，is_tank 过滤）
        - products: 产品收率/物料类型（主料/辅料成本计算）
        - material_flows: 物流拓扑（upstream_output_map 构建）
    """
    logger = get_logger()
    api_device_costs = device_costs or {}
    device_inputs = result.get('device_inputs', {})
    total_feeds = result.get('total_feeds', {})

    # 构建上游产出查找映射：{(target_device_id, product_name): batch_output}
    # 用于 feed_details 中主料 feed_qty 取上游装置实际产出值，保证与投入产出表一致
    upstream_output_map: Dict[tuple, float] = {}
    if all_product_outputs:
        # 预构建 {product_name: output_info} 扁平字典，避免内层两层循环
        output_by_name: Dict[str, float] = {}
        for outputs in all_product_outputs.values():
            for pname, pinfo in outputs.items():
                output_by_name[pname] = pinfo.get('output', 0)
        for flow in scenario.material_flows.values():
            if flow.flow_type != 'tank_to_target':
                continue
            tank_id = flow.tank_id
            target_dev = flow.target_device_id
            # 找罐的产品（source_device_id == tank_id），按产品名从扁平字典查找
            for p in scenario.products.values():
                if p.source_device_id == tank_id and p.material_type in ('main_feed', 'product'):
                    qty = output_by_name.get(p.name, 0)
                    if qty > 0:
                        upstream_output_map[(target_dev, p.name)] = qty
                    break

    feed_details: List[dict] = []
    process_details: List[dict] = []
    total_feed_cost = 0.0
    total_process_cost = 0.0

    for device_id in target_devices:
        input_qty = device_inputs.get(device_id, 0)
        if input_qty <= 0:
            continue
        device = scenario.devices.get(device_id)
        if not device or device.is_tank:
            continue

        # ── 进料成本 ──
        # 总进料量：直接引用 direct_calculator 的计算结果
        # （按 max_yr main_feed + target_product_id 定位连接主料，连接主料量/配比）
        total_feed = total_feeds.get(device_id, input_qty)

        device_feed_cost = 0.0
        device_feed_items: List[dict] = []
        for p in scenario.products.values():
            if p.source_device_id != device_id:
                continue
            if p.material_type not in ('main_feed', 'auxiliary'):
                continue
            # 主料：优先取上游装置产出值（与投入产出表一致，已是批次值）
            # 辅料：用 total_feed × yield_rate 估算（批次值）
            if p.material_type == 'main_feed':
                upstream_qty = upstream_output_map.get((device_id, p.name), 0)
                if upstream_qty > 0:
                    feed_qty = upstream_qty  # 已是批次值
                else:
                    feed_qty = total_feed * p.yield_rate  # 估算批次值
            else:
                feed_qty = total_feed * p.yield_rate  # 辅料批次值
            if feed_qty <= 0:
                continue
            feed_price = get_product_price(p.id, 0, prices)
            feed_cost = feed_qty * feed_price  # 批次成本
            device_feed_cost += feed_cost
            total_feed_cost += feed_cost  # 批次值直接累加
            mat_label = '主料' if p.material_type == 'main_feed' else '辅料'
            device_feed_items.append({
                'product_id': p.id,
                'name': p.name,
                'material_type': p.material_type,
                'yield_rate': p.yield_rate,
                'feed_qty': feed_qty,  # 批次值
                'price': feed_price,
                'cost': feed_cost,  # 批次成本
                'ton_cost': p.yield_rate * feed_price,  # 吨消耗成本(元/吨总进料)
                'label': mat_label,
            })

        if device_feed_items:
            feed_details.append({
                'device_id': device_id,
                'device_name': device.name,
                'input_qty': total_feed,            # 总进料量(含辅料) 批次值
                'main_feed_qty': input_qty,         # 主料投入量 批次值
                'feed_cost': device_feed_cost,
                'ton_feed_cost': device_feed_cost / total_feed if total_feed > 0 else 0,
                'items': device_feed_items,
            })

        # ── 加工成本 ──
        unit_cost = _get_device_cost_for_id(api_device_costs, device_id, 0.0, scenario)
        if unit_cost <= 0:
            # 默认加工成本：CDU 50元/吨，加工装置 100元/吨
            dev = scenario.devices.get(device_id)
            if dev and isinstance(dev, ProcessingUnit) and dev.is_start:
                unit_cost = 50.0
            elif dev and dev.is_processing_unit and not dev.is_start:
                unit_cost = 100.0
            else:
                unit_cost = 0.0

        # 加工成本基数：总进料量 = 连接主料量 / 连接主料配比（total_feed 已在上面算好）
        process_base = total_feed
        dev_process_cost = process_base * unit_cost  # 总进料量 × 单位加工成本
        total_process_cost += dev_process_cost  # 批次值直接累加
        process_details.append({
            'device_id': device_id,
            'device_name': device.name,
            'input_qty': process_base,  # 总进料量(批次值)
            'unit_cost': unit_cost,
            'process_cost': dev_process_cost,
        })

    # ── 汇总 ──
    total_cost = total_feed_cost + total_process_cost

    logger.info(
        f"[成本] v3: 批次进料={total_feed_cost:,.0f}, "
        f"批次加工={total_process_cost:,.0f}, "
        f"批次成本={total_cost:,.0f}, "
        f"周期={days}天"
    )

    return CostBreakdown(
        total_feed_cost=total_feed_cost,
        process_cost=total_process_cost,
        total_cost=total_cost,
        daily_feed_cost=total_feed_cost / days if days > 0 else total_feed_cost,
        daily_process_cost=total_process_cost / days if days > 0 else total_process_cost,
        daily_cost=total_cost / days if days > 0 else total_cost,
        feed_details=feed_details,
        process_details=process_details,
    )
