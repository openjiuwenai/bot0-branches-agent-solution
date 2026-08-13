# -*- coding: utf-8 -*-
"""收入侧计算（纯函数版 — 无 calc_ctx、无 DB 直连）。

职责单一：给定一个批次的装置投入量 + 预加载的引用数据，算出该批次的
  - 产出（各装置所有产品的产量 + 收率 + 收率类型/原因）
  - 收入（仅最终产品：有效输入 × 收率 × 价格）
  - 日销售收入 daily_revenue（caller 按 days 转批次值）

Phase 2 重构：
  - get_product_price: 从 prices dict 获取，不再查 DB
  - build_tank_mapping/build_tank_flow_mapping: 每次从 scenario 内存重算（<0.3ms）
  - resolve_total_feed: 每次从 scenario 内存重算（<0.1ms）
  - compute_revenue: 接收 prices 显式参数
"""
from typing import Dict, List, Tuple, Optional

from ..models.refinery import RefineryScenario
from ..logger import get_logger
from .yield_resolver import resolve_yield_rate


# ── 价格工具 ──────────────────────────────────────────────────────────────

def get_product_price(product_id: str, default_price: float,
                      prices: Optional[Dict[str, float]] = None) -> float:
    """从预加载的价格字典中获取产品价格。纯函数，无 DB 副作用。

    优先级：
      0. prices dict 预加载缓存（由 Service 层预加载后传入）
      1. default_price 兜底
    纯负数/NaN → 0。
    """
    if prices:
        price = prices.get(product_id)
        if price is not None and price >= 0 and price == price:
            return price
    # 默认价格兜底
    if default_price != default_price:  # NaN
        return 0
    if default_price < 0:
        return 0
    return default_price


# ── 产出与收入计算 ───────────────────────────────────────────────────────

def compute_revenue(scenario: RefineryScenario, result: dict,
                    special_vars: Dict, plan_month: str,
                    target_devices: set,
                    days: int = 1,
                    hangmei_mode: bool = False, day_index: float = 0,
                    hangmei_m_days: float = 0,
                    prices: Optional[Dict[str, float]] = None) -> Tuple[List[dict], Dict[str, dict], float]:
    """计算 economic_items / all_product_outputs / 批次总收入。

    统一函数（合并原 compute_revenue + compute_revenue_summary）。
    input_amount / effective_input 均为批次值，产出/收入直接为批次值，无需 ×days。

    Args:
        scenario: 数据场景
        result: 求解结果（取 device_inputs）
        special_vars: 求解特殊变量（jian1_to_diesel/jian1_to_wax，供收率模式判断）
        plan_month: 计划月份
        target_devices: 仅计算这些装置
        days: 周期天数（仅用于航煤跨界批次加权）
        prices: 预加载的产品价格 {product_id: price}，由 Service 层预加载后传入
    Returns:
        (economic_items, all_product_outputs, total_revenue)
        economic_items: 装置级收入列表（批次值）
        all_product_outputs: {装置ID: {产品名: {...}}}（批次值）
        total_revenue: 批次总收入标量

    Scenario 依赖:
        - products: 产品收率/物料类型（产量+收入计算）
        - devices: 装置字典（is_tank 过滤）
        - hangmei_active_device_ids: 航煤主动装置（跨界批次检测）
        - yield_switch_device_ids: 收率切换装置（收率字段选择）
        - processing_device_ids: 加工装置列表（target_devices 由调用方传入）
    """
    device_inputs = result['device_inputs']
    total_feeds = result.get('total_feeds', {})
    economic_items: List[dict] = []
    all_product_outputs: Dict[str, dict] = {}
    total_revenue = 0.0

    for device_id in target_devices:
        input_amount = device_inputs.get(device_id, 0)
        if input_amount <= 0:
            continue
        device = scenario.devices.get(device_id)
        if not device or device.is_tank:
            continue

        # 总进料量：直接引用 direct_calculator 的计算结果
        # （按 max_yr main_feed + target_product_id 定位连接主料，连接主料量/配比）
        effective_input = total_feeds.get(device_id, input_amount)

        device_products = [p for p in scenario.products.values()
                           if p.source_device_id == device_id
                           and p.material_type == 'product']
        if not device_products:
            continue

        all_product_outputs.setdefault(device_id, {})
        device_revenue = 0.0
        products_list: List[dict] = []

        # 航煤跨界批次检测：批次窗口 [day_index, day_index+days) vs 航煤窗口 [0, hangmei_m_days)
        hm_overlap = 0.0
        if hangmei_mode and device_id in scenario.hangmei_active_device_ids and hangmei_m_days > 0:
            hm_overlap = max(0.0, min(hangmei_m_days, day_index + days) - max(0.0, day_index))
        crosses_boundary = 0 < hm_overlap < days

        for p in device_products:
            price = get_product_price(p.id, 0, prices)

            if crosses_boundary:
                # 跨界批次：航煤段 + 非航煤段分别计算
                hm_days = hm_overlap
                normal_days = days - hm_overlap

                yi_hm = resolve_yield_rate(device_id, p, special_vars,
                                           hangmei_mode=True, day_index=max(0.0, day_index),
                                           hangmei_m_days=hangmei_m_days,
                                           yield_switch_device_ids=scenario.yield_switch_device_ids,
                                           hangmei_active_device_ids=scenario.hangmei_active_device_ids)
                yi_nm = resolve_yield_rate(device_id, p, special_vars,
                                           hangmei_mode=True, day_index=hangmei_m_days,
                                           hangmei_m_days=hangmei_m_days,
                                           yield_switch_device_ids=scenario.yield_switch_device_ids,
                                           hangmei_active_device_ids=scenario.hangmei_active_device_ids)

                # 批次产量 = 批次总进料 × 加权平均收率（按天加权）
                product_output = effective_input * (yi_hm.yield_rate * hm_days
                                  + yi_nm.yield_rate * normal_days) / days
                product_revenue = product_output * price

                # 加权收率 = 总产量 / 批次总进料
                effective_yield = product_output / effective_input if effective_input > 0 else 0
                yi = yi_hm  # 以航煤段为主记录
                yi_reason = f'跨界(航煤{hm_days:.1f}天+非航煤{normal_days:.1f}天)'
            else:
                yi = resolve_yield_rate(device_id, p, special_vars,
                                        hangmei_mode=hangmei_mode, day_index=day_index,
                                        hangmei_m_days=hangmei_m_days,
                                        yield_switch_device_ids=scenario.yield_switch_device_ids,
                                        hangmei_active_device_ids=scenario.hangmei_active_device_ids)
                effective_yield = yi.yield_rate
                product_output = effective_input * effective_yield
                product_revenue = product_output * price

            device_revenue += product_revenue

            yield_pct = round(effective_yield * 100, 3)
            batch_output = round(product_output, 4)
            batch_revenue_item = round(product_revenue, 4)

            all_product_outputs[device_id][p.name] = {
                'product_id': p.id,
                'output': batch_output,
                'yield_rate': yield_pct,
                'yield_type': yi.yield_type,
                'yield_reason': yi.reason if not crosses_boundary else yi_reason,
                'price': price,
            }
            products_list.append({
                'product_id': p.id,
                'product_name': p.name,
                'yield_rate': yield_pct,
                'yield_type': yi.yield_type,
                'yield_reason': yi.reason if not crosses_boundary else yi_reason,
                'price': price,
                'output': batch_output,
                'revenue': batch_revenue_item,
            })

        total_revenue += device_revenue
        economic_items.append({
            'device_id': device_id,
            'device_name': device.name,
            'input_amount': round(input_amount, 4),           # 连接主料量 批次值
            'effective_input': round(effective_input, 4),     # 总进料量 批次值
            'revenue': round(device_revenue, 4),              # 批次值
            'ton_revenue': round(device_revenue / effective_input, 4) if effective_input > 0 else 0,
            'products': products_list,
        })

    return economic_items, all_product_outputs, total_revenue
