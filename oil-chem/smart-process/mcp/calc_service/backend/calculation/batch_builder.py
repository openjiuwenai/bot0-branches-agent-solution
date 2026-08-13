# -*- coding: utf-8 -*-
"""批次详情构建器（从 service 层下移，消除 calculation→service 反向依赖）。

职责：
  - build_batch_details_list: 从 batch_results/calc_results/explanations 构建批次详情
  - build_monthly_load: 从 batch_details 聚合月度装置负荷
  - merge_intervals: 合并重叠区间（被 build_monthly_load 使用）
"""
import copy
from typing import List, Dict, Tuple


def merge_intervals(ivs):
    """合并重叠/相邻的时间区间，返回去重后的区间列表。"""
    if not ivs:
        return []
    sorted_iv = sorted(ivs, key=lambda x: x[0])
    merged = [list(sorted_iv[0])]
    for s, e in sorted_iv[1:]:
        if s <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], e)
        else:
            merged.append([s, e])
    return [(s, e) for s, e in merged]


def build_batch_details_list(batch_results, calc_results, explanations, tank_initials: dict = None):
    """从单个组合的 batch_results / calc_results / explanations 构建批次详情列表。

    供 optimal_batch_details 和每个可行组合的 combo_result.batch_details 共用。
    含罐容链式累计（start_capacity/end_capacity）。device_utilization 深拷贝，不污染原始数据。

    Args:
      tank_initials: {tank_id: initial_capacity} 中间罐月初容量，优先于 current_capacity
    """
    n = min(len(batch_results), len(calc_results), len(explanations))
    if n == 0:
        return []

    details = []
    for i in range(n):
        br = batch_results[i]
        calc = calc_results[i] or {}
        exp = explanations[i] or {}
        details.append({
            'batch_id': br.get('batch_id'),
            'crude_type': br.get('crude_type'),
            'start_day': br.get('start_day'),
            'end_day': br.get('end_day'),
            'mode': br.get('mode'),
            'total_input': br.get('total_input', 0),
            'revenue': br.get('revenue', 0),
            'jian1_to_diesel': br.get('jian1_to_diesel', 0),
            'jian1_to_wax': br.get('jian1_to_wax', 0),
            'is_hangmei_period': br.get('is_hangmei_period', False),
            'shutdown_intervals': br.get('shutdown_intervals', {}),
            'device_inputs': calc.get('device_inputs', {}),
            'main_feed_totals': calc.get('main_feed_totals', {}),  # 新增：主进料总量
            'total_feeds': calc.get('total_feeds', {}),            # 新增：总进料
            'device_utilization': copy.deepcopy(calc.get('device_utilization', {})),
            'special_vars': calc.get('special_vars', {}),
            'economic_analysis': exp.get('economic_analysis', []),
            'all_product_outputs': exp.get('all_product_outputs', {}),
            'feed_details': exp.get('feed_details', []),
            'process_details': exp.get('process_details', []),
            'diversion_scenarios': exp.get('diversion_scenarios', []),
            'costs': {
                'crude_cost': exp.get('crude_cost', 0),
                'energy_cost': exp.get('energy_cost', 0),
                'total_cost': exp.get('total_cost', 0),
                'total_profit': exp.get('total_profit', 0),
                'total_revenue': exp.get('total_revenue', 0),
                'daily_input': exp.get('daily_input', 0) or br.get('daily_input', 0),
                'days': exp.get('days', 0) or br.get('days', 0),
                'ton_metrics': exp.get('ton_metrics', {}),
            },
        })

    # 加工装置月平均负荷下的加工天数与日消耗速率
    # 月平均日处理量 = Σ(各批次总主料负荷量) / 月总天数  （feed_qty 已是批次值）
    # daily_consumption = 月平均日处理量（各批次相同，体现装置均匀取料）
    # processing_days = 本批次总主料量 / 月平均日处理量
    # 注：Σ(processing_days) = 月总天数，体现装置全月持续加工
    total_days = sum(float(d.get('costs', {}).get('days', 0) or 0) for d in details)
    # 第一遍：累计每个装置的月度主料总量（feed_qty 已是批次值，直接累加）
    monthly_main_feed: dict = {}
    for detail in details:
        du = detail['device_utilization']
        feed_map = {}
        for fd in (detail.get('feed_details') or []):
            feed_map[fd.get('device_id')] = fd
        for did, u in du.items():
            if not u or u.get('type') in ('tank', 'start'):
                continue
            fd = feed_map.get(did)
            if not fd:
                continue
            items = fd.get('items', [])
            main_items = [i for i in items if i.get('label') == '主料']
            if not main_items:
                continue
            total_main_feed = sum(float(i.get('feed_qty', 0)) for i in main_items)
            monthly_main_feed[did] = monthly_main_feed.get(did, 0) + total_main_feed
    # 月平均日处理量
    avg_daily_rate = {did: (v / total_days if total_days > 0 else 0)
                      for did, v in monthly_main_feed.items()}
    # 第二遍：按月平均日处理量计算每批次的加工天数
    for detail in details:
        du = detail['device_utilization']
        feed_map = {}
        for fd in (detail.get('feed_details') or []):
            feed_map[fd.get('device_id')] = fd
        for did, u in du.items():
            if not u or u.get('type') in ('tank', 'start'):
                continue
            fd = feed_map.get(did)
            if not fd:
                continue
            items = fd.get('items', [])
            main_items = [i for i in items if i.get('label') == '主料']
            if not main_items:
                continue
            total_main_feed = sum(float(i.get('feed_qty', 0)) for i in main_items)
            if total_main_feed <= 0:
                continue
            avg_rate = avg_daily_rate.get(did, 0)
            if avg_rate > 0:
                u['daily_consumption'] = round(avg_rate, 1)
                u['processing_days'] = round(total_main_feed / avg_rate, 2)
            else:
                u['daily_consumption'] = 0
                u['processing_days'] = 0

    # CDU（type="start"）无主料输入，不参与上述计算。
    # 但其加工时间即为批次天数，补充 processing_days 使罐容检测走统一逻辑。
    for detail in details:
        batch_days = float(detail.get('costs', {}).get('days', 0) or 0)
        if batch_days <= 0:
            continue
        du = detail.get('device_utilization') or {}
        for did, u in du.items():
            if not u or u.get('type') != 'start':
                continue
            u['processing_days'] = round(batch_days, 2)
            u['daily_consumption'] = float(u.get('input', 0) or 0) / batch_days if batch_days > 0 else 0

    return details


def build_monthly_load(batch_details) -> dict:
    """从单组合的 batch_details 聚合月度装置负荷（展示+检测层）。

    与单批次日均校验（direct_calculator，input ≤ effective_capacity）互补：
    本函数按月度维度聚合——只要月度平均未超容，实际可逐日微调进料，
    个别批次日均超容并不必然导致整月不可行。
    月度能力 = safety_stock_thrd × effective_days（满负荷100%），
    月度负荷 = Σ(总主料负荷量)，
    可行性判定：月负荷率 > 负荷率阈值(%) 时判定为超容。
    总主料负荷量 = Σ(feed_qty where label='主料') from feed_details.items，
    与 processing_days 计算口径完全一致（safety_stock_thrd = 总主料负荷能力）。

    Returns:
      {total_days, devices:[...], overload_count, summary}
      devices 按 monthly_util 降序。
    """
    if not batch_details:
        return {'total_days': 0, 'devices': [],
                'overload_count': 0, 'summary': '无批次数据'}

    total_days = sum(float(d.get('costs', {}).get('days', 0) or 0)
                     for d in batch_details)
    if total_days <= 0:
        return {'total_days': 0, 'devices': [],
                'overload_count': 0, 'summary': '无有效天数'}

    # 装置停工时长（小时）聚合：从各批次 shutdown_intervals 按 device_id 汇总
    # 用于折算有效加工天数：effective_days = total_days - shutdown_hours / 24
    # 注意：批次日范围可能有边界重叠（identify_batches 的 end_day == 下一批次 start_day），
    # 拆分后子批次的 shutdown_intervals 会有重叠区间，必须先合并再求和，否则重复计数。
    raw_intervals_by_device: dict = {}
    for d in batch_details:
        si = d.get('shutdown_intervals') or {}
        for dev_id, intervals in si.items():
            raw_intervals_by_device.setdefault(dev_id, []).extend(intervals)

    shutdown_hours_by_device: dict = {}
    for dev_id, intervals in raw_intervals_by_device.items():
        merged = merge_intervals(intervals)
        shutdown_hours_by_device[dev_id] = sum(e - s for s, e in merged)

    # 装置月度累计：device_id → {name, type, input_sum(=Σ总主料负荷量), cap_day}
    # 停工折算口径（v2）：
    #   - monthly_capacity = safety_stock_thrd × effective_days（扣除停工时长）✓ 已有
    #   - monthly_input（分子）= 上游罐全月 input 累计（不减停工），反映"全月应加工原料"
    #     停工时 CDU 仍全量产出注入罐，这些原料需要在非停工期补加工，故分子不减停工。
    #     实现方式：对加工装置，找其上游中间罐，用罐的 input（CDU全量产出）作为分子。
    #     若找不到上游罐（如 CDU 本身），回退到装置自身 input。
    dev_agg = {}
    # 第一遍：累计罐的 input，建立 罐→下游装置 映射（非停工批次中 罐.outflow==装置.input）
    tank_input_sum: dict = {}  # {tank_id: Σ(input × days)}
    tank_to_device: dict = {}  # {tank_id: device_id}（从非停工批次推断）
    for d in batch_details:
        days = float(d.get('costs', {}).get('days', 0) or 0)
        du = d.get('device_utilization', {}) or {}
        for did, u in du.items():
            if not u:
                continue
            t = u.get('type', 'normal')
            if t == 'tank':
                tk_in = float(u.get('input', 0) or 0)
                tank_input_sum[did] = tank_input_sum.get(did, 0) + tk_in
                tk_out = float(u.get('outflow', 0) or 0)
                if tk_out > 0.01:
                    # 非停工批次：罐 outflow = 下游装置 input，建立映射
                    for other_did, other_u in du.items():
                        if other_did == did:
                            continue
                        if other_u and other_u.get('type') not in ('tank', 'start'):
                            if abs(float(other_u.get('input', 0) or 0) - tk_out) / max(tk_out, 1) < 0.05:
                                tank_to_device[did] = other_did
                                break
    # 反向映射：加工装置 → 上游罐
    device_to_tank = {v: k for k, v in tank_to_device.items()}

    for d in batch_details:
        days = float(d.get('costs', {}).get('days', 0) or 0)
        du = d.get('device_utilization', {}) or {}
        # 构建该批次 feed_details 的 device_id → items 映射
        feed_map = {}
        for fd in (d.get('feed_details') or []):
            feed_map[fd.get('device_id')] = fd
        for did, u in du.items():
            t = u.get('type', 'normal')
            if t == 'tank':
                continue
            # 加工装置：月度加工量 = Σ(总主料负荷量)
            # feed_qty 已是批次值，直接累加；
            # 回退 device_utilization.input（批次值）直接累加
            fd = feed_map.get(did)
            inflow = 0.0
            if fd and fd.get('items'):
                main_items = [i for i in fd['items'] if i.get('label') == '主料']
                if main_items:
                    inflow = sum(float(i.get('feed_qty', 0)) for i in main_items)  # 批次值
            if inflow <= 0:
                inflow = float(u.get('input', 0) or 0)  # 批次值
            entry = dev_agg.setdefault(did, {
                'name': u.get('name', did),
                'type': t,
                'input_sum': 0.0,
                'connected_sum': 0.0,   # 连接主料累计（用于算占比）
                'safety_stock_thrd': float(u.get('safety_stock_thrd', 0) or 0),
                'load_percent': float(u.get('refinery_unit_load_percent', 100) or 100),
            })
            entry['input_sum'] += inflow  # 批次值直接累加
            # 累计连接主料（装置 input），用于计算连接主料占比
            entry['connected_sum'] += float(u.get('input', 0) or 0)

    # 装置：月度能力 = safety_stock_thrd × effective_days（满负荷100%，扣除停工时长）
    # effective_days = total_days - shutdown_hours / 24（停工时长折算天数）
    # 月负荷分子：用上游罐全月 inflow（含停工期 CDU 注入）折算总主料，
    #   确保停工期累积原料计入加工压力。
    #   分子 = 罐全月inflow × (总主料 / 连接主料) = 罐全月inflow / 连接主料占比
    devices = []
    for did, a in dev_agg.items():
        shutdown_hours = shutdown_hours_by_device.get(did, 0)
        # 月负荷分子：有上游罐时用罐全月inflow折算总主料（含停工期注入）
        tank_id = device_to_tank.get(did)
        if tank_id and tank_id in tank_input_sum and tank_input_sum[tank_id] > 0:
            tank_inflow = tank_input_sum[tank_id]
            # 连接主料占比 = 装置input累计 / 总主料累计（从非停工段算出）
            connected_sum = a.get('connected_sum', 0)
            input_sum = a.get('input_sum', 0)
            if connected_sum > 0 and input_sum > 0:
                proportion = connected_sum / input_sum
            else:
                proportion = 1.0
            # 月负荷分子 = 罐全月inflow / 连接主料占比（= 罐全月inflow对应的总主料）
            monthly_input = tank_inflow / proportion if proportion > 0 else tank_inflow
        else:
            # 无上游罐（如常减压）：用装置自身 input_sum
            monthly_input = a['input_sum']
        effective_days = max(total_days - shutdown_hours / 24.0, 0.1)
        monthly_capacity = a['safety_stock_thrd'] * effective_days
        monthly_util = (monthly_input / monthly_capacity * 100.0) if monthly_capacity > 0 else 0.0
        # 负荷率阈值：月负荷率超过此阈值则判定超容，未加工量 = 超过阈值部分
        load_pct = a.get('load_percent', 100)
        is_over = monthly_capacity > 0 and monthly_util > load_pct + 1e-6
        threshold_capacity = monthly_capacity * (load_pct / 100.0)
        unprocessed = max(0.0, monthly_input - threshold_capacity) if monthly_capacity > 0 else 0.0
        devices.append({
            'device_id': did,
            'name': a['name'],
            'type': a['type'],
            'monthly_input': round(monthly_input, 1),
            'daily_avg': round(monthly_input / total_days, 1),
            'monthly_capacity': round(monthly_capacity, 0),
            'monthly_util': round(monthly_util, 1),
            'load_percent': load_pct,
            'shutdown_hours': round(shutdown_hours, 1),
            'effective_days': round(effective_days, 2),
            'unprocessed_material': round(unprocessed, 1),
            'is_overloaded': is_over,
        })
    devices.sort(key=lambda x: x['monthly_util'], reverse=True)

    overload_count = sum(1 for d_ in devices if d_['is_overloaded'])
    if overload_count > 0:
        names = '、'.join(d_['name'] for d_ in devices if d_['is_overloaded'])
        summary = f'月度装置负荷：{overload_count}台超容（{names}）'
    else:
        summary = '月度装置负荷：全部装置月度未超容'

    return {
        'total_days': round(total_days, 2),
        'devices': devices,
        'overload_count': overload_count,
        'summary': summary,
    }
