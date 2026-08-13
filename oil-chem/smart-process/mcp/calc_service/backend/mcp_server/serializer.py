# -*- coding: utf-8 -*-
"""MCP 序列化工具 — Python 对象 → JSON 兼容结构。

dataclass / set / Decimal 等不可 JSON 序列化的类型统一在此转换。
适配函数调用 to_jsonable() 确保返回值可直接序列化为 JSON。
"""

from dataclasses import asdict, is_dataclass
from decimal import Decimal
from typing import Any, Dict


def to_jsonable(obj: Any) -> Any:
    """递归将 Python 对象转为 JSON 可序列化结构。

    处理类型:
        - Decimal → float
        - set → list
        - dataclass → dict（递归）
        - dict/list/tuple → 递归
        - 其他 → str() 兜底
    """
    if obj is None:
        return None
    if isinstance(obj, (str, int, float, bool)):
        return obj
    if isinstance(obj, Decimal):
        return float(obj)
    if isinstance(obj, set):
        return [to_jsonable(v) for v in obj]
    if isinstance(obj, dict):
        return {str(k): to_jsonable(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [to_jsonable(v) for v in obj]
    if is_dataclass(obj) and not isinstance(obj, type):
        return {k: to_jsonable(v) for k, v in asdict(obj).items()}
    return str(obj)


def serialize_hangmei_context(ctx) -> Dict:
    """HangmeiContext → JSON dict。

    供 MCP-07 输出、MCP-05/06 输入传递。
    """
    if ctx is None:
        return None
    return {
        'enabled': ctx.enabled,
        'target': ctx.target,
        'total_days': ctx.total_days,
        'daily_input_avg': ctx.daily_input_avg,
        'active_products': to_jsonable(ctx.active_products),
        'passive_products': to_jsonable(ctx.passive_products),
        'active_device_ids': list(ctx.active_device_ids),
        'passive_device_ids': list(ctx.passive_device_ids),
        'allow_window_search': ctx.allow_window_search,
        'hangmei_price': ctx.hangmei_price,
        'rlydmx_price': ctx.rlydmx_price,
        'rlydmx_yields': to_jsonable(ctx.rlydmx_yields),
        'product_deltas': to_jsonable(ctx.product_deltas),
    }


def serialize_combination_result(result) -> Dict:
    """CombinationResult → JSON dict。

    字段顺序：结论先行（feasible/revenue），明细后行（calc_results/explanations）。
    """
    if result is None:
        return None
    return {
        # ── ① 结论型（Agent 最关心）──
        'feasible': result.feasible,
        'total_revenue': result.total_revenue,
        'combination_id': result.combination_id,
        'description': result.description,
        'infeasible_summary': result.infeasible_summary,
        'bottleneck_devices': to_jsonable(result.bottleneck_devices),
        # ── ② 配置型 ──
        'switch_position': result.switch_position,
        'initial_mode': result.initial_mode,
        'switches': to_jsonable(result.switches),
        'total_cost': result.total_cost,
        # ── ③ 明细型（大体积，排后面）──
        'batch_results': to_jsonable(result.batch_results),
        'hangmei_summary': to_jsonable(result.hangmei_summary),
        'tank_check_result': to_jsonable(result.tank_check_result),
        'monthly_load': to_jsonable(result.monthly_load),
        'explanations': to_jsonable(result.explanations),
        'batch_details': to_jsonable(result.batch_details),
        'calc_results': to_jsonable(result.calc_results),
    }
