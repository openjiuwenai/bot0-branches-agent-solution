# -*- coding: utf-8 -*-
"""
统一收率选择器（消灭原 solver.py + web_app.py 两套实现）。

原 solve/ 存在两套收率选择逻辑：
  - solver.py._get_effective_yield_rate()：LP 求解器用，不支持航煤
  - web_app.py.get_effective_yield_rate()：直接计算用，支持航煤
两者逻辑相似但独立维护，易产生分歧。本模块统一为一套，LP 和直接计算共用。

装置角色通过 scenario 数据拓扑动态推断（替代硬编码 DEVICE_CYJQ/DEVICE_LYJQ）：
  - 收率切换装置：material_flows 中有 special_var 非空的源装置
  - 航煤主动装置：产品有 yield_rate_3/4 > 0 的装置
  - 航煤被动装置：收率切换装置中非航煤主动的装置
"""
from dataclasses import dataclass
from typing import Dict, Optional, Set

from ..models.refinery import Product
from ..config import YIELD_FIELD_MAP, YIELD_FALLBACK_MAP
from ..config import MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL


@dataclass
class YieldResult:
    """收率选择结果。"""
    yield_rate: float       # 选中的收率值
    yield_type: str         # 字段名，如 'yield_rate_3'
    reason: str             # 人类可读的选择原因


def _resolve_xy_mode(special_vars: Dict[str, float]) -> str:
    """根据减一线分流特殊变量判断模式。

    special_vars 键：
      - 'jian1_to_diesel': 减一线去柴油加氢的流量
      - 'jian1_to_wax': 减一线去蜡油加氢的流量

    Returns:
        MODE_JIAN1_TO_WAX / MODE_JIAN1_TO_DIESEL
    """
    diesel_val = special_vars.get('jian1_to_diesel', 0)
    wax_val = special_vars.get('jian1_to_wax', 0)
    if diesel_val == 0:
        return MODE_JIAN1_TO_WAX
    if wax_val == 0:
        return MODE_JIAN1_TO_DIESEL
    return MODE_JIAN1_TO_DIESEL


def _select_field(device_id: str, xy_mode: str, hangmei_mode: bool,
                  day_index: float, hangmei_m_days: float,
                  hangmei_active_ids: Set[str]) -> str:
    """选择 Product 上的收率字段名。

    航煤工况仅对主动装置（yield_rate_3/4 > 0）生效；
    被动装置始终用非航煤字段。
    """
    is_hangmei_period = (
        hangmei_mode
        and device_id in hangmei_active_ids
        and 0 <= day_index < hangmei_m_days
    )
    condition = ("hangmei" if is_hangmei_period else "normal", xy_mode)
    return YIELD_FIELD_MAP[condition]


def resolve_yield_rate(
    device_id: str,
    product: Product,
    special_vars: Dict[str, float] = None,
    hangmei_mode: bool = False,
    day_index: float = 0,
    hangmei_m_days: float = 0,
    yield_switch_device_ids: Optional[Set[str]] = None,
    hangmei_active_device_ids: Optional[Set[str]] = None,
) -> YieldResult:
    """统一的收率选择函数，LP 求解器和直接计算共用。

    逻辑：
      - 非收率切换装置 → yield_rate
      - 航煤主动装置航煤期内（0 <= day_index < hangmei_m_days）→ yield_rate_3/4
      - 其余 → yield_rate/yield_rate_2（按减一线分流模式）
      - 被动装置的映射与主动装置相反（去蜡油模式用 yield_rate_2，去柴油模式用 yield_rate）

    Args:
        device_id: 装置ID
        product: Product 对象
        special_vars: {'jian1_to_diesel': float, 'jian1_to_wax': float}，可为 None
        hangmei_mode: 是否启用航煤工况
        day_index: 当前天数索引
        hangmei_m_days: 航煤工况天数（M 值）
        yield_switch_device_ids: 收率切换装置ID集合（替代 scenario.yield_switch_device_ids）
        hangmei_active_device_ids: 航煤主动装置ID集合（替代 scenario.hangmei_active_device_ids）
    Returns:
        YieldResult
    """
    if special_vars is None:
        special_vars = {}

    # 装置角色（由调用方传入集合，替代原 scenario 属性推断）
    if yield_switch_device_ids is not None:
        switch_ids = yield_switch_device_ids
    else:
        # fallback：未传入时，所有装置都按收率切换处理
        switch_ids = {device_id}
    if hangmei_active_device_ids is not None:
        hangmei_active_ids = hangmei_active_device_ids
    else:
        hangmei_active_ids = set()

    # 非收率切换装置直接使用 yield_rate
    if device_id not in switch_ids:
        return YieldResult(
            yield_rate=product.yield_rate,
            yield_type='yield_rate',
            reason='非收率切换装置'
        )

    xy_mode = _resolve_xy_mode(special_vars)
    field = _select_field(device_id, xy_mode, hangmei_mode, day_index, hangmei_m_days,
                          hangmei_active_ids)

    # 被动装置不受航煤工况影响，修正字段为非航煤版本
    if device_id not in hangmei_active_ids:
        field = YIELD_FIELD_MAP[("normal", xy_mode)]

    value = getattr(product, field, 0)

    # 航煤字段为 0 时回退到对应非航煤字段
    if value <= 0 and field in YIELD_FALLBACK_MAP:
        fallback_field = YIELD_FALLBACK_MAP[field]
        value = getattr(product, fallback_field, 0)
        field = fallback_field

    # 构建 reason
    is_hangmei_period = (
        hangmei_mode
        and device_id in hangmei_active_ids
        and 0 <= day_index < hangmei_m_days
    )
    if is_hangmei_period:
        reason = f'航煤工况({xy_mode}): 0<=第{day_index}天<M={hangmei_m_days}天'
    else:
        if hangmei_mode and device_id in hangmei_active_ids:
            reason = f'非航煤工况({xy_mode}): 第{day_index}天不在[0,{hangmei_m_days})内'
        else:
            reason = xy_mode

    return YieldResult(yield_rate=value, yield_type=field, reason=reason)
