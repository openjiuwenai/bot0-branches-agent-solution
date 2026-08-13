# -*- coding: utf-8 -*-
"""航煤工况相关：有效进料计算、上下文构建、组合级 M/N 计算与时段搜索。"""
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from ..logger import get_logger
from ..models.refinery import RefineryScenario, Tank
from ..config import MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL


def _compute_effective_input(scenario, daily_input: float, mode: str,
                              tank_id: str, device_id: str) -> float:
    """计算指定装置的有效进料量（与 compute_revenue 的 resolve_total_feed 同口径）。

    通过 material_flows 拓扑动态查找 CDU→指定储罐的侧线收率之和，
    再除以目标装置的主料配比，得到有效进料量。

    Args:
        scenario: 数据场景
        daily_input: CDU 日均加工量
        mode: 减一线分流模式 MODE_JIAN1_TO_WAX / MODE_JIAN1_TO_DIESEL
        tank_id: 目标储罐ID（如 gyrly_tank_01 / hc_tank_01）
        device_id: 目标加工装置ID（如 cyjq_01 / lyjq_01）
    """
    cdu_id = scenario.start_device_id
    feed_ratio = 0.0
    for flow in scenario.get_downstream_flows(cdu_id):
        if flow.to_device_id == tank_id:
            product = scenario.products.get(flow.from_product_id)
            if not product or product.material_type != 'product':
                continue
            if flow.special_var == 'jian1_to_diesel':
                if mode == MODE_JIAN1_TO_DIESEL:
                    feed_ratio += product.yield_rate
            elif flow.special_var == 'jian1_to_wax':
                if mode == MODE_JIAN1_TO_WAX:
                    feed_ratio += product.yield_rate
            else:
                feed_ratio += product.yield_rate

    main_feeds = scenario.get_main_feeds(device_id)
    main_feed_ratio = max((p.yield_rate for p in main_feeds), default=1.0)

    if main_feed_ratio > 0:
        return daily_input * feed_ratio / main_feed_ratio
    return daily_input * feed_ratio


def _compute_device_effective_input(scenario, daily_input: float, mode: str,
                                     device_id: str) -> float:
    """计算指定加工装置的有效进料量（与 compute_revenue 的 resolve_total_feed 同口径）。

    多装置支持 + 清理硬编码：通过 material_flows 拓扑动态查找装置的上游储罐
    （不再依赖 special_var == 'jian1_to_wax' 硬编码），再计算 CDU→该储罐的侧线收率之和
    ÷ 该装置主料配比 × split_ratio，得到有效进料量。

    Args:
        scenario: 数据场景
        daily_input: CDU 日均加工量
        mode: 减一线分流模式 MODE_JIAN1_TO_WAX / MODE_JIAN1_TO_DIESEL
        device_id: 目标加工装置ID（如 cyjq_01 / cyjq_02 / lyjq_01）
    """
    # 通过拓扑查找装置的上游储罐（替代硬编码 special_var）
    tank_id = None
    split_ratio = 1.0
    for flow in scenario.get_upstream_flows(device_id):
        from_dev = scenario.devices.get(flow.from_device_id)
        if from_dev and isinstance(from_dev, Tank):
                tank_id = flow.from_device_id
                split_ratio = flow.split_ratio
                break
    if not tank_id:
        return 0.0
    eff = _compute_effective_input(scenario, daily_input, mode, tank_id, device_id)
    return eff * split_ratio


def _resolve_hangmei_yield(hp, mode: str) -> tuple:
    """解析航煤产品在某 mode 下的（非航煤收率, 航煤收率）。

    多装置通用收率解析，统一主动/被动装置的 fallback 逻辑：
      MODE_JIAN1_TO_WAX: 非航煤=yield_rate, 航煤=yield_rate_3（fallback yield_rate）
      MODE_JIAN1_TO_DIESEL: 非航煤=yield_rate_2, 航煤=yield_rate_4
              （fallback: yield_rate_3>0→yield_rate_3 主动装置航煤期, 否则→yield_rate_2 被动装置非航煤）

    被动装置 yield_rate_3/4=0，fallback 后 high=low（航煤工况不变）。
    """
    if not hp:
        return 0.0, 0.0
    if mode == MODE_JIAN1_TO_WAX:
        y_low = hp.yield_rate
        y_high = hp.yield_rate_3 if hp.yield_rate_3 > 0 else hp.yield_rate
    else:  # MODE_JIAN1_TO_DIESEL
        y_low = hp.yield_rate_2
        if hp.yield_rate_4 > 0:
            y_high = hp.yield_rate_4
        elif hp.yield_rate_3 > 0:
            y_high = hp.yield_rate_3  # 主动装置：航煤期 MODE_JIAN1_TO_WAX 收率
        else:
            y_high = hp.yield_rate_2  # 被动装置：非航煤收率（航煤工况不变）
    return y_low, y_high


# ── 数据结构 ──────────────────────────────────────────────────────────────

@dataclass
class HangmeiContext:
    """航煤工况全局上下文（跨组合共享，组合内据此计算 M/N）。

    enabled=False 时表示未启用航煤工况，optimize_valve 路由传此默认值即可。
    """
    enabled: bool = False
    target: float = 0.0            # 航煤目标产出吨数
    total_days: float = 0.0        # 所有批次总天数
    daily_input_avg: float = 0.0   # 日均加工量（吨）
    # 多装置支持：主动/被动装置的航煤产品列表（替代单一 product/product_lyjq）
    active_products: list = field(default_factory=list)   # 所有主动装置航煤产品（受航煤工况影响收率）
    passive_products: list = field(default_factory=list)  # 所有被动装置航煤产品（航煤工况不变，纳入 H_default）
    active_device_ids: set = field(default_factory=set)   # 受航煤工况影响的装置ID
    passive_device_ids: set = field(default_factory=set)  # 产航煤但不受航煤工况影响的装置ID
    allow_window_search: bool = True  # 是否搜索最优航煤起始时段（False=固定从第0天开始）
    hangmei_price: float = 0.0     # 航煤产品价格（元/吨，供时段搜索排序用）
    rlydmx_price: float = 0.0      # 燃料油DMX价格（元/吨，向后兼容）
    rlydmx_yields: dict = field(default_factory=dict)  # rlydmx收率 {mode: (high, low)}（向后兼容）
    # 航煤期收率对比产品（cyjq_01 受航煤影响 + lyjq_01 不受影响，仅供前端完整展示）
    # 每项: {product_id, product_name, device_id, device_name, price, changed,
    #        yields: {MODE_JIAN1_TO_WAX: (yield_low, yield_high), MODE_JIAN1_TO_DIESEL: (yield_low, yield_high)}}
    product_deltas: list = field(default_factory=list)

    # ── 向后兼容属性：保留单装置访问入口（返回首个产品）──
    @property
    def product(self):
        """首个主动装置航煤产品（向后兼容，多装置场景请用 active_products）。"""
        return self.active_products[0] if self.active_products else None

    @property
    def product_lyjq(self):
        """首个被动装置航煤产品（向后兼容，多装置场景请用 passive_products）。"""
        return self.passive_products[0] if self.passive_products else None


# ── 航煤上下文构建 ────────────────────────────────────────────────────────

def build_hangmei_context(batches: List[dict],
                          scenarios: Dict[str, RefineryScenario],
                          hangmei_target: Optional[float],
                          custom_crude_costs: Dict, logger=None,
                          plan_month: str = None,
                          prices: Optional[Dict[str, float]] = None) -> HangmeiContext:
    """构建航煤工况上下文（搬迁自 comprehensive_solve L3441-3490 初始化阶段）。

    Args:
        batches: 批次列表
        scenarios: 按原油品种预加载的场景字典
        hangmei_target: 航煤目标产出吨数；None/<=0 表示不启用
        custom_crude_costs: 自定义原油成本
        plan_month: 计划月份（用于从 price_cost 取航煤价格）
    Returns:
        HangmeiContext（enabled=False 表示未启用）

    Scenario 依赖:
        - products: 航煤产品查找（通过 material_name 过滤）
        - get_products_by_material(): 按物料名查产品
        - hangmei_active_device_ids: 航煤主动装置ID列表
        - devices: 装置字典
    """
    if logger is None:
        logger = get_logger()
    ctx = HangmeiContext()
    if not hangmei_target or hangmei_target <= 0:
        return ctx

    # 总天数
    total_days = 0.0
    for batch in batches:
        total_days += float(batch.get('days', len(batch.get('daily_inputs', []))))

    if not batches:
        logger.warn("[航煤工况] 没有批次数据，无法启用航煤工况")
        return ctx

    # 用首批次原油类型获取场景，查找 cyjq_01 的航煤产品
    first_crude = batches[0]['crude_type']
    scenario = scenarios.get(first_crude)
    if scenario is None:
        logger.warning(f"[航煤工况] 未找到首批次原油场景: {first_crude}，无法启用航煤工况")
        return ctx
    # 批量预加载价格已由 Service 层完成，prices 直接传入
    hangmei_products = scenario.get_products_by_material('航煤')
    if not hangmei_products:
        logger.warn("[航煤工况] 未找到航煤产品，无法启用航煤工况")
        return ctx

    # 航煤工况主动装置：yield_rate_3/4 > 0（受航煤工况影响收率）
    active_ids = scenario.hangmei_active_device_ids
    # 航煤工况被动装置：产航煤但 yield_rate_3/4 = 0（航煤工况不变，纳入 H_default）
    passive_ids = {p.source_device_id for p in hangmei_products
                   if p.source_device_id not in active_ids}

    # 多装置支持：收集所有主动/被动装置的航煤产品（不再只取首个）
    # 主动装置的航煤产品列表（通常为柴加，可能含 cyjq_01/cyjq_02 等）
    active_products = [p for p in hangmei_products if p.source_device_id in active_ids]
    if not active_products:
        logger.warn("[航煤工况] 未找到受航煤工况影响的装置，无法启用航煤工况")
        return ctx

    # 被动装置的航煤产品列表（通常为蜡加，航煤工况不变，纳入 H_default）
    passive_products = [p for p in hangmei_products if p.source_device_id in passive_ids]
    for hp_passive in passive_products:
        logger.info(f"[航煤工况] 被动装置航煤产品: {hp_passive.id} "
                    f"yield_rate={hp_passive.yield_rate:.6f}, "
                    f"yield_rate_2={hp_passive.yield_rate_2:.6f} "
                    f"(航煤工况不变，yield_rate_3/4=0→fallback)")

    total_input_all = sum(b['total_input'] for b in batches)
    daily_input_avg = total_input_all / total_days if total_days > 0 else 0

    logger.info(f"========== 航煤工况初始化 ==========")
    logger.info(f"[航煤工况] hangmei_target={hangmei_target:.2f}吨")
    logger.info(f"[航煤工况] 总天数={total_days:.4f}天")
    logger.info(f"[航煤工况] 日均加工量={daily_input_avg:.2f}吨")
    logger.info(f"[航煤工况] 主动装置数={len(active_products)}, 被动装置数={len(passive_products)}")
    # 遍历所有主动装置航煤产品输出收率信息
    for hp_active in active_products:
        logger.info(f"[航煤工况] 主动装置航煤产品: {hp_active.id}")
        logger.info(f"[航煤工况]   yield_rate: {hp_active.yield_rate:.6f} (X=0非航煤)")
        logger.info(f"[航煤工况]   yield_rate_2: {hp_active.yield_rate_2:.6f} (Y=0非航煤)")
        logger.info(f"[航煤工况]   yield_rate_3: {hp_active.yield_rate_3:.6f} (X=0航煤)")
        logger.info(f"[航煤工况]   yield_rate_4: {hp_active.yield_rate_4:.6f} (Y=0航煤)")
    logger.info(f"[航煤工况] M/N将在每个组合计算时动态确定")

    # 取航煤产品价格（供时段搜索排序用，用首个主动装置产品）
    from .revenue_calculator import get_product_price
    hangmei_product = active_products[0]  # 向后兼容：首个主动产品
    hm_price = get_product_price(hangmei_product.id, 0, prices)
    logger.info(f"[航煤工况] 航煤价格: {hm_price:.2f}元/吨 (plan_month={plan_month})")

    # ── 预计算航煤相关装置全部产品（含收率无变化的，供前端完整展示）──
    # 主动装置（如柴加）：受航煤工况影响，yield_rate_3/4 为航煤期收率，可能 ≠ yield_rate/2
    # 被动装置（如蜡加）：不受航煤工况影响，yield_rate_3/4 全为 0，始终 changed=False
    # 收率有变化的参与净增益计算；无变化的 delta_revenue=0，仅供前端展示完整对比。
    relevant_device_ids = active_ids | passive_ids
    rlydmx_price = 0.0  # 向后兼容
    rlydmx_yields = {}  # 向后兼容
    product_deltas = []
    for pid, prod in scenario.products.items():
        if prod.source_device_id not in relevant_device_ids or prod.material_type != 'product':
            continue
        # MODE_JIAN1_TO_WAX: 非航煤=yield_rate, 航煤=yield_rate_3
        # MODE_JIAN1_TO_DIESEL: 非航煤=yield_rate_2, 航煤=yield_rate_4
        x_low, x_high = prod.yield_rate, (prod.yield_rate_3 if prod.yield_rate_3 > 0 else prod.yield_rate)
        y_low, y_high = prod.yield_rate_2, (prod.yield_rate_4 if prod.yield_rate_4 > 0 else prod.yield_rate_2)
        changed = abs(x_high - x_low) >= 1e-9 or abs(y_high - y_low) >= 1e-9
        price = get_product_price(pid, 0, prices)
        dev_name = scenario.devices.get(prod.source_device_id)
        dev_name_str = dev_name.name if dev_name else prod.source_device_id
        product_deltas.append({
            'product_id': pid,
            'product_name': prod.name,
            'device_id': prod.source_device_id,
            'device_name': dev_name_str,
            'price': price,
            'changed': changed,  # 收率是否有变化（无变化的产品 delta_revenue=0，仅供展示）
            'yields': {
                MODE_JIAN1_TO_WAX: (x_low, x_high),
                MODE_JIAN1_TO_DIESEL: (y_low, y_high),
            },
        })
        # 向后兼容：rlydmx 字段
        if changed and prod.material_name == '燃料油DMX':
            rlydmx_price = price
            rlydmx_yields = {
                MODE_JIAN1_TO_WAX: (x_low, x_high),
                MODE_JIAN1_TO_DIESEL: (y_low, y_high),
            }
        tag = '变化' if changed else '不变'
        logger.info(f"[航煤工况] {dev_name_str}产品收率{tag}: {prod.name}({pid}) "
                    f"{MODE_JIAN1_TO_WAX}: {x_low:.6f}→{x_high:.6f}(Δ={x_high-x_low:+.6f}), "
                    f"{MODE_JIAN1_TO_DIESEL}: {y_low:.6f}→{y_high:.6f}(Δ={y_high-y_low:+.6f}), "
                    f"价格={price:.2f}元/吨")

    ctx.enabled = True
    ctx.target = float(hangmei_target)
    ctx.total_days = total_days
    ctx.daily_input_avg = daily_input_avg
    ctx.active_products = active_products      # 多装置：所有主动装置航煤产品列表
    ctx.passive_products = passive_products    # 多装置：所有被动装置航煤产品列表
    ctx.hangmei_price = hm_price
    ctx.rlydmx_price = rlydmx_price
    ctx.rlydmx_yields = rlydmx_yields
    ctx.product_deltas = product_deltas
    ctx.active_device_ids = active_ids
    ctx.passive_device_ids = passive_ids
    return ctx


# ── 组合级航煤 M/N 计算 ───────────────────────────────────────────────────

def _compute_combo_hangmei(combo: dict, batches: List[dict],
                           hangmei_ctx: HangmeiContext, logger,
                           active_effective_inputs: List[dict] = None,
                           passive_effective_inputs: List[dict] = None,
                           batch_yields: List[dict] = None) -> tuple:
    """根据组合各批次减一线方向计算 M/N，并搜索最优航煤起始时段。

    多装置支持：active_effective_inputs/passive_effective_inputs 为每批次各装置的
    有效进料字典 {device_id: float}，batch_yields 为每批次各装置的收率字典。

    不同批次的减一线方向可能不同（MODE_JIAN1_TO_WAX→蜡加 / MODE_JIAN1_TO_DIESEL→柴加），对应不同的
    航煤/非航煤收率分支。H_default 和 M 按各批次实际方向分段计算，而非
    仅用首批次模式近似。

    Returns:
        (combo_hangmei_mode, combo_hangmei_m_days, best_start, summary)
        summary 为航煤摘要 dict（enabled=False 时为空 dict），含 M/N/实际产出/
        偏差/航煤与非航煤收率/首批次方向/最优起始偏移/时段搜索详情，供响应体回传前端展示。
    """
    switches = combo['switches']
    first_batch_mode = switches.get(batches[0]['batch_id'], MODE_JIAN1_TO_DIESEL)
    hp = hangmei_ctx.product  # 向后兼容：首个主动装置航煤产品
    # 防御性检查：无主动装置航煤产品时直接返回（不进入航煤工况）。
    # 正常流程由 evaluate_combination 的 hangmei_ctx.product 守卫拦截，
    # 此处防御直接调用 _compute_combo_hangmei 时的 None 访问（hp.id 于下方日志使用）。
    if not hp:
        logger.warn(f"[组合{combo['combination_id']}航煤工况] 无主动装置航煤产品，跳过航煤工况计算")
        return False, 0.0, 0.0, {'enabled': False}

    # ── 按各批次实际减一线方向构建收率时间轴（多装置）──
    # 每个批次的 mode 决定其航煤/非航煤收率分支：
    #   MODE_JIAN1_TO_WAX(→蜡加): 非航煤=yield_rate, 航煤=yield_rate_3
    #   MODE_JIAN1_TO_DIESEL(→柴加): 非航煤=yield_rate_2, 航煤=yield_rate_4
    # 多装置：每批次存 active_devices/passive_devices 字典，按装置分别记录有效进料和收率
    batch_timeline = []
    cum = 0.0
    for i, b in enumerate(batches):
        days = float(b.get('days', len(b.get('daily_inputs', []))))
        total_input = b.get('total_input', 0)
        daily_input = total_input / days if days > 0 else 0
        mode = switches.get(b['batch_id'], first_batch_mode)
        by = batch_yields[i] if batch_yields and i < len(batch_yields) else None

        # 多装置：构建 active_devices / passive_devices 字典
        active_devices = {}   # {device_id: {effective_input, yield_low, yield_high}}
        passive_devices = {}  # {device_id: {effective_input, yield_low, yield_high}}
        # 主动装置有效进料（多装置字典）
        active_eff_map = active_effective_inputs[i] if active_effective_inputs and i < len(active_effective_inputs) else {}
        passive_eff_map = passive_effective_inputs[i] if passive_effective_inputs and i < len(passive_effective_inputs) else {}

        # 主动装置收率（多装置字典）
        active_yields_map = by.get('active', {}) if by else {}
        passive_yields_map = by.get('passive', {}) if by else {}

        # 停工折减（与 direct_calculator 同口径）：按非停工时长比例缩减 effective_input
        si = b.get('shutdown_intervals') or {}
        batch_hours = days * 24

        # 遍历所有主动装置（用 hangmei_ctx.active_device_ids 确保覆盖全部）
        for did in hangmei_ctx.active_device_ids:
            eff_input = active_eff_map.get(did, 0.0)
            # 停工折减
            intervals = si.get(did)
            if intervals and batch_hours > 0:
                shutdown_hours = sum(e - s for s, e in intervals)
                keep_ratio = 1.0 - min(shutdown_hours / batch_hours, 1.0)
                eff_input *= keep_ratio
            y_low, y_high = active_yields_map.get(did, (0.0, 0.0))
            active_devices[did] = {
                'effective_input': eff_input,
                'yield_low': y_low,
                'yield_high': y_high,
            }
        # 遍历所有被动装置
        for did in hangmei_ctx.passive_device_ids:
            eff_input = passive_eff_map.get(did, 0.0)
            # 停工折减
            intervals = si.get(did)
            if intervals and batch_hours > 0:
                shutdown_hours = sum(e - s for s, e in intervals)
                keep_ratio = 1.0 - min(shutdown_hours / batch_hours, 1.0)
                eff_input *= keep_ratio
            y_low, y_high = passive_yields_map.get(did, (0.0, 0.0))
            passive_devices[did] = {
                'effective_input': eff_input,
                'yield_low': y_low,
                'yield_high': y_high,
            }

        # 向后兼容字段：聚合所有主动/被动装置（供旧日志/summary/fallback 用）
        cyjq_input = sum(info['effective_input'] for info in active_devices.values())
        lyjq_input = sum(info['effective_input'] for info in passive_devices.values())
        # 加权平均收率（按有效进料加权）
        y_low = (sum(info['effective_input'] * info['yield_low'] for info in active_devices.values()) / cyjq_input) if cyjq_input > 0 else 0.0
        y_high = (sum(info['effective_input'] * info['yield_high'] for info in active_devices.values()) / cyjq_input) if cyjq_input > 0 else 0.0
        ly_low = (sum(info['effective_input'] * info['yield_low'] for info in passive_devices.values()) / lyjq_input) if lyjq_input > 0 else 0.0
        ly_high = ly_low  # 被动装置收率不变

        # 主动装置日均有效进料之和（吨/天），用于计算 M 对应的连接主料吨数
        active_daily_input = sum(info['effective_input'] for info in active_devices.values())

        batch_timeline.append({
            'batch_id': b['batch_id'],
            'crude_type': b.get('crude_type', ''),
            'mode': mode,
            'start': cum,
            'end': cum + days,
            'days': days,
            'daily_input': daily_input,                # CDU 日均加工量（仅展示用）
            'active_daily_input': active_daily_input,  # 主动装置日均有效进料之和（吨/天，用于 M_tons 计算）
            # 多装置：按装置分别存储有效进料和收率
            'active_devices': active_devices,          # {device_id: {effective_input, yield_low, yield_high}}
            'passive_devices': passive_devices,        # {device_id: {effective_input, yield_low, yield_high}}
            # 向后兼容字段（取首个装置的值，供旧日志/summary展示用）
            'cyjq_effective_input': cyjq_input,        # 首个主动装置有效进料
            'lyjq_effective_input': lyjq_input,        # 首个被动装置有效进料
            'yield_low': y_low,    # 首个主动装置非航煤收率
            'yield_high': y_high,  # 首个主动装置航煤工况收率
            'lyjq_yield_low': ly_low,    # 首个被动装置非航煤收率
            'lyjq_yield_high': ly_high,  # 首个被动装置航煤工况收率（= ly_low，不变）
        })
        cum += days

    # ── 计算常规（非航煤）工况产出 H_default（多装置遍历累加）──
    # 主动装置：Σ(天数 × 各装置有效进料 × 各装置非航煤收率)
    # 被动装置：Σ(天数 × 各装置有效进料 × 各装置非航煤收率)（航煤工况下被动装置收率不变，delta=0）
    H_default = 0.0
    H_default_cyjq = 0.0   # 主动装置合计（向后兼容日志字段）
    H_default_lyjq = 0.0   # 被动装置合计（向后兼容日志字段）
    for bt in batch_timeline:
        for did, info in bt['active_devices'].items():
            h = bt['days'] * info['effective_input'] * info['yield_low']
            H_default += h
            H_default_cyjq += h
        for did, info in bt['passive_devices'].items():
            h = bt['days'] * info['effective_input'] * info['yield_low']
            H_default += h
            H_default_lyjq += h
    logger.info(f"[组合{combo['combination_id']}航煤工况] H_default: 主动={H_default_cyjq:.2f} + 被动={H_default_lyjq:.2f} = {H_default:.2f}吨")

    # 首批次收率（用于 summary 展示，向后兼容）
    hangmei_yield_high = batch_timeline[0]['yield_high']
    hangmei_yield_low = batch_timeline[0]['yield_low']

    logger.info(f"{'=' * 80}")
    logger.info(f"[组合{combo['combination_id']}] 航煤工况计算开始")
    logger.info(f"[组合{combo['combination_id']}航煤工况] 目标航煤产出: H_target={hangmei_ctx.target:.2f}吨")
    logger.info(f"[组合{combo['combination_id']}航煤工况] 总天数: total_days={hangmei_ctx.total_days:.4f}天")
    logger.info(f"[组合{combo['combination_id']}航煤工况] 产品ID: {hp.id}")
    logger.info(f"[组合{combo['combination_id']}航煤工况] 产品名称: {hp.name}")
    logger.info(f"[组合{combo['combination_id']}航煤工况] 首批次策略: {first_batch_mode}")
    for bt in batch_timeline:
        logger.info(f"[组合{combo['combination_id']}航煤工况]   批次{bt['batch_id']}({bt['crude_type']}, {bt['mode']}): "
                    f"{bt['days']:.2f}天, 日均{bt['daily_input']:.0f}吨, "
                    f"非航煤收率={bt['yield_low']:.4f}, 航煤收率={bt['yield_high']:.4f}")
    logger.info(f"[组合{combo['combination_id']}航煤工况] 默认产出(H_default, 按各批次方向分段): {H_default:.2f}吨")
    logger.info(f"[组合{combo['combination_id']}航煤工况] 需要增加产出: delta_H={hangmei_ctx.target - H_default:.2f}吨")

    best_start = 0.0
    window_details = []
    m_days = 0.0
    n_days = hangmei_ctx.total_days
    actual_H = H_default
    deviation = hangmei_ctx.target - H_default
    combo_hangmei_mode = False
    combo_hangmei_m_days = 0.0

    if hangmei_ctx.target > H_default:
        delta_H = hangmei_ctx.target - H_default
        logger.info(f"[组合{combo['combination_id']}航煤工况] 需要增加产出: delta_H={delta_H:.2f}吨")

        if hangmei_ctx.allow_window_search:
            # ── 搜索最优航煤时段 ──
            # 对每个候选起始位置，从该位置开始按实际批次收率逐天累加航煤增量，
            # 直到满足缺口 delta_H，得到该位置的真实 M。不同起始位置的 M 不同。
            best_start, m_days, window_details = _find_optimal_hangmei_start(
                batch_timeline, delta_H, hangmei_ctx, logger)

            if window_details:
                opt_wd = next((w for w in window_details if w.get('is_optimal')), window_details[0])
                # actual_H = H_default + 最优窗口实际航煤增量（按覆盖批次实际收率）
                actual_H = H_default + opt_wd['hm_total']
                deviation = actual_H - hangmei_ctx.target
            n_days = hangmei_ctx.total_days - m_days
            # 即使不可行（全月航煤不够），也算进入航煤工况（M>0），只是缺口无法补齐
            combo_hangmei_mode, combo_hangmei_m_days = (m_days > 0), m_days

            logger.info(f"[组合{combo['combination_id']}航煤工况] 最优起始: 第{best_start:.2f}天, M={m_days:.4f}天")
            logger.info(f"[组合{combo['combination_id']}航煤工况]   actual_H = H_default({H_default:.0f}) + 窗口增量({actual_H-H_default:.0f}) = {actual_H:.0f}吨")
            logger.info(f"[组合{combo['combination_id']}航煤工况]   偏差={deviation:.2f}吨")
        else:
            # 不搜索窗口时，用加权平均估算 M（fallback，无法确定航煤覆盖哪些批次）
            # 多装置：delta_H 来自所有主动装置（被动装置航煤工况不变），用所有主动装置计算加权收率
            total_input_all = 0.0
            total_low = 0.0
            total_high = 0.0
            for bt in batch_timeline:
                for did, info in bt['active_devices'].items():
                    w = bt['days'] * info['effective_input']
                    total_input_all += w
                    total_low += w * info['yield_low']
                    total_high += w * info['yield_high']
            avg_yield_low = total_low / total_input_all if total_input_all > 0 else 0
            avg_yield_high = total_high / total_input_all if total_input_all > 0 else 0
            daily_input_avg = total_input_all / hangmei_ctx.total_days if hangmei_ctx.total_days > 0 else 0
            daily_delta = daily_input_avg * (avg_yield_high - avg_yield_low)
            if daily_delta > 0:
                m_days = max(0, min(delta_H / daily_delta, hangmei_ctx.total_days))
            n_days = hangmei_ctx.total_days - m_days
            actual_H = H_default + m_days * daily_delta
            deviation = actual_H - hangmei_ctx.target
            combo_hangmei_mode, combo_hangmei_m_days = (m_days > 0), m_days
    else:
        logger.info(f"[组合{combo['combination_id']}航煤工况] hangmei_target({hangmei_ctx.target:.2f}) <= H_default({H_default:.2f})")
        logger.info(f"[组合{combo['combination_id']}航煤工况] 使用非航煤工况，M=0天, N={hangmei_ctx.total_days:.4f}天")

    logger.info(f"[组合{combo['combination_id']}] 航煤工况计算结束")
    logger.info(f"{'=' * 80}")

    # ── 航煤工况边际贡献计算（遍历 cyjq_01 所有收率有变化的产品）──
    # 净增益 = Σ(各产品航煤期收入变化)，已隐含在组合总效益(total_revenue)中，此处仅供展示。
    # 不再硬编码航煤+DMX，而是遍历 hangmei_ctx.product_deltas 中所有收率有变化的产品。
    hm_benefit = 0.0       # 增产收益合计（正数之和，向后兼容字段名）
    rlydmx_loss = 0.0      # 减产损失合计（正数表示损失，向后兼容字段名）
    net_benefit = 0.0      # 净增益 = hm_benefit - rlydmx_loss
    product_deltas_detail = []  # 各产品收入变化明细（供前端展示）
    if combo_hangmei_mode and m_days > 0 and window_details:
        opt_wd = next((w for w in window_details if w.get('is_optimal')), window_details[0])
        # 按产品累加航煤期收入变化
        per_product_delta = {}  # {product_id: total_delta_revenue}
        for bt in batch_timeline:
            overlap = max(0, min(opt_wd['end'], bt['end']) - max(opt_wd['start'], bt['start']))
            if overlap <= 0:
                continue
            for pd in hangmei_ctx.product_deltas:
                y_low, y_high = pd['yields'].get(bt['mode'], (0, 0))
                # 多装置：按产品所属装置取对应的有效进料（替代旧 cyjq/lyjq 单一取值）
                did = pd['device_id']
                if did in bt['passive_devices']:
                    eff_input = bt['passive_devices'][did]['effective_input']
                elif did in bt['active_devices']:
                    eff_input = bt['active_devices'][did]['effective_input']
                else:
                    eff_input = 0.0
                delta_revenue = eff_input * (y_high - y_low) * overlap * pd['price']
                pid = pd['product_id']
                per_product_delta[pid] = per_product_delta.get(pid, 0.0) + delta_revenue
        # 汇总：正变化=增产收益，负变化=减产损失
        gain_total = 0.0
        loss_total = 0.0
        for pd in hangmei_ctx.product_deltas:
            pid = pd['product_id']
            delta = per_product_delta.get(pid, 0.0)
            y_low, y_high = pd['yields'].get(first_batch_mode, (0, 0))
            product_deltas_detail.append({
                'product_id': pid,
                'product_name': pd['product_name'],
                'device_id': pd.get('device_id', ''),
                'device_name': pd.get('device_name', ''),
                'delta_revenue': round(delta, 0),
                'price': pd['price'],
                'yield_low': y_low,
                'yield_high': y_high,
                'changed': pd.get('changed', True),  # 收率是否有变化（前端区分展示）
            })
            if delta >= 0:
                gain_total += delta
            else:
                loss_total += abs(delta)
        hm_benefit = gain_total
        rlydmx_loss = loss_total
        net_benefit = hm_benefit - rlydmx_loss
        logger.info(f"[组合{combo['combination_id']}航煤增益] 净增益={net_benefit:,.0f}元 (最优窗口: 第{opt_wd['start']:.1f}~第{opt_wd['end']:.1f}天)")
        logger.info(f"[组合{combo['combination_id']}航煤增益]   增产收益={hm_benefit:,.0f}元, 减产损失={rlydmx_loss:,.0f}元 (已计入总效益)")
        for d in product_deltas_detail:
            sign = '+' if d['delta_revenue'] >= 0 else '−'
            logger.info(f"[组合{combo['combination_id']}航煤增益]     {d['product_name']}: {sign}{abs(d['delta_revenue']):,.0f}元")

    # 各批次 H_default 计算明细（供前端调测页面展示算法过程）
    h_default_details = []

    # ── M/N 吨维度计算（按连接主料吨数表达航煤期规模，语义清晰）──
    # total_active_tons: 全月主动装置有效进料总量（吨）
    # m_tons: 航煤期覆盖的主动装置有效进料量（吨）= 多少吨主料走了航煤工况
    # n_tons: 非航煤期主动装置有效进料量（吨）
    total_active_tons = sum(bt['days'] * bt['active_daily_input'] for bt in batch_timeline)
    m_tons = 0.0
    if combo_hangmei_mode and m_days > 0 and window_details:
        opt_wd = next((w for w in window_details if w.get('is_optimal')), window_details[0])
        # 遍历批次，计算最优窗口覆盖的主动装置进料吨数
        for bt in batch_timeline:
            overlap = max(0.0, min(opt_wd['end'], bt['end']) - max(opt_wd['start'], bt['start']))
            if overlap > 0:
                m_tons += overlap * bt['active_daily_input']
    n_tons = max(0.0, total_active_tons - m_tons)
    logger.info(f"[组合{combo['combination_id']}航煤工况] 吨维度: 全月主动进料={total_active_tons:,.0f}吨, "
                f"航煤期={m_tons:,.0f}吨, 非航煤期={n_tons:,.0f}吨")
    for bt in batch_timeline:
        # 多装置：遍历所有主动/被动装置累加产出
        active_output = sum(bt['days'] * info['effective_input'] * info['yield_low']
                            for info in bt['active_devices'].values())
        passive_output = sum(bt['days'] * info['effective_input'] * info['yield_low']
                             for info in bt['passive_devices'].values())
        batch_output = active_output + passive_output
        h_default_details.append({
            'batch_id': bt['batch_id'],
            'crude_type': bt['crude_type'],
            'mode': bt['mode'],
            'days': round(bt['days'], 2),
            'daily_input': round(bt['daily_input'], 0),
            # 向后兼容：取首个装置的值展示
            'cyjq_effective_input': round(bt['cyjq_effective_input'], 0),
            'lyjq_effective_input': round(bt['lyjq_effective_input'], 0),
            'yield_low': round(bt['yield_low'], 6),       # 首个主动装置非航煤收率
            'yield_high': round(bt['yield_high'], 6),     # 首个主动装置航煤工况收率
            'lyjq_yield_low': round(bt['lyjq_yield_low'], 6),   # 首个被动装置非航煤收率
            'cyjq_output': round(active_output, 0),       # 主动装置航煤常规产出合计（吨）
            'lyjq_output': round(passive_output, 0),      # 被动装置航煤常规产出合计（吨）
            'batch_output': round(batch_output, 0),       # 该批次常规产出合计（吨）
        })

    # M 计算过程明细（展示最优窗口各批次实际日增量累加过程）
    m_calc = None
    if combo_hangmei_mode and m_days > 0 and window_details:
        opt_wd = next((w for w in window_details if w.get('is_optimal')), window_details[0])
        delta_H = hangmei_ctx.target - H_default
        m_calc = {
            'H_default': round(H_default, 0),
            'target': round(hangmei_ctx.target, 0),
            'delta_H': round(delta_H, 0),               # 缺口（吨）
            'best_start': round(best_start, 2),          # 最优起始偏移（天）
            'covered_batches': opt_wd['covered_batches'], # 最优窗口覆盖批次（含各批次日增量/增量/天数）
            'hm_total': opt_wd['hm_total'],              # 窗口内航煤总增量（吨）
            'feasible': opt_wd.get('feasible', True),    # 该窗口是否满足缺口
            'M': round(m_days, 4),                       # 该位置的实际 M（天）
            'M_tons': round(m_tons, 0),                  # 航煤期连接主料吨数（吨）
        }

    # ── 主动/被动装置列表（供前端动态渲染，不硬编码装置名）──
    active_devs = []
    passive_devs = []
    seen_a, seen_p = set(), set()
    for pd in hangmei_ctx.product_deltas:
        did = pd.get('device_id', '')
        dname = pd.get('device_name', did)
        if pd.get('changed', True) and did not in seen_a:
            active_devs.append({'device_id': did, 'device_name': dname})
            seen_a.add(did)
        elif not pd.get('changed', True) and did not in seen_p:
            passive_devs.append({'device_id': did, 'device_name': dname})
            seen_p.add(did)

    summary = {
        'enabled': True,
        'active': combo_hangmei_mode,          # 是否真正进入航煤期（M>0）
        'feasible': (m_calc or {}).get('feasible', True) if combo_hangmei_mode else True,  # 是否满足目标
        'target': hangmei_ctx.target,
        'm_days': m_days,
        'n_days': n_days,
        'total_days': hangmei_ctx.total_days,
        'm_tons': round(m_tons, 0),              # 航煤期连接主料吨数（吨，语义清晰）
        'n_tons': round(n_tons, 0),              # 非航煤期连接主料吨数（吨）
        'total_active_tons': round(total_active_tons, 0),  # 全月主动装置有效进料总量（吨）
        'active_devices': active_devs,           # 主动装置列表（收率受航煤工况影响）
        'passive_devices': passive_devs,         # 被动装置列表（产航煤但收率不变）
        'H_default': round(H_default, 0),      # 常规（非航煤）工况全月产出（吨）
        'actual_H': actual_H,                  # 航煤工况后实际产出（吨）
        'deviation': deviation,
        'yield_high': hangmei_yield_high,      # 航煤期收率（首批次）
        'yield_low': hangmei_yield_low,        # 非航煤收率（首批次）
        'first_mode': first_batch_mode,        # 首批次方向（决定收率分支）
        'product_id': hp.id,
        'product_name': hp.name,
        'hangmei_start': round(best_start, 2),  # 最优航煤起始偏移（天）
        'window_search': window_details,       # 候选时段对比（供前端展示）
        'daily_input_avg': round(hangmei_ctx.daily_input_avg, 0),  # 日均加工量（吨）
        'h_default_details': h_default_details,  # 各批次常规产出明细
        'm_calc': m_calc,                        # M 计算过程明细
        # 航煤工况边际贡献（已计入组合总效益，此处仅供展示）
        'hm_benefit': round(hm_benefit, 0),    # 增产收益合计（元，正数）
        'rlydmx_loss': round(rlydmx_loss, 0),  # 减产损失合计（元，正数，向后兼容字段名）
        'net_benefit': round(net_benefit, 0),  # 净增益（元）= hm_benefit - rlydmx_loss
        'hm_price': hangmei_ctx.hangmei_price, # 航煤价格（元/吨，向后兼容）
        'rlydmx_price': hangmei_ctx.rlydmx_price,  # rlydmx价格（元/吨，向后兼容）
        'product_deltas_detail': product_deltas_detail,  # 各产品收入变化明细（供前端动态渲染）
    }
    return combo_hangmei_mode, combo_hangmei_m_days, best_start, summary


def _find_optimal_hangmei_start(batch_timeline: List[dict], delta_H: float,
                                hangmei_ctx: HangmeiContext,
                                logger=None) -> tuple:
    """对每个候选起始位置，按实际批次收率逐天累加航煤增量，直到满足缺口 delta_H。

    航煤工况只影响 cyjq_01（柴加）：航煤增产 + rlydmx（燃料油DMX）减产，
    日净收益 = 航煤日增量×航煤价格 + rlydmx日减量×rlydmx价格。
    不同批次的减一线方向不同，航煤/rlydmx 收率分支也不同，日净收益按各批次实际方向计算。

    关键：M 不是预先用全月加权平均算的，而是对每个候选起始位置，从该位置开始
    逐批次累加航煤增量（daily_input × (yield_high - yield_low) × 天数），
    直到累计增量 ≥ delta_H，得到该位置的真实 M。不同起始位置的 M 不同。

    候选起始点 = 每个批次的左边界（航煤时段对齐批次边界，避免批次内拆分）。

    Args:
        batch_timeline: 批次时间轴（含 mode/yield_high/yield_low/daily_input/start/end）
        delta_H: 航煤缺口（目标 - H_default，吨）
        hangmei_ctx: 航煤上下文（含 hangmei_price/rlydmx_price/rlydmx_yields）
        logger: 日志记录器
    Returns:
        (best_start, best_m, window_details)
        best_start: 最优起始偏移天数（0 = 从第0天开始）
        best_m: 最优位置的实际 M 天数
        window_details: 各候选时段的收益对比列表（含该位置的 m_days / hm_total），供前端展示
    """
    if logger is None:
        logger = get_logger()

    total_days = hangmei_ctx.total_days
    hm_price = hangmei_ctx.hangmei_price
    product_deltas = hangmei_ctx.product_deltas

    # 为每个批次计算日航煤增量和日净收益
    # 多装置：日航煤增量 = Σ(各主动装置 effective_input × (yield_high - yield_low))
    # 日净收益 = Σ(各产品 所属装置effective_input × (yield_high - yield_low) × price)
    for bt in batch_timeline:
        mode = bt['mode']
        # 航煤日增量：遍历所有主动装置累加（仅航煤产品，用于判断 M 是否满足缺口 delta_H）
        hm_daily_delta = 0.0
        for did, info in bt['active_devices'].items():
            hm_daily_delta += info['effective_input'] * (info['yield_high'] - info['yield_low'])
        # 日净收益：遍历所有收率有变化的产品（不硬编码航煤+DMX）
        # 多装置：按产品所属装置取对应的有效进料
        daily_benefit = 0.0
        for pd in product_deltas:
            y_low, y_high = pd['yields'].get(mode, (0, 0))
            pd_did = pd['device_id']
            if pd_did in bt['passive_devices']:
                eff_input = bt['passive_devices'][pd_did]['effective_input']
            elif pd_did in bt['active_devices']:
                eff_input = bt['active_devices'][pd_did]['effective_input']
            else:
                eff_input = 0.0
            daily_benefit += eff_input * (y_high - y_low) * pd['price']
        bt['daily_benefit'] = daily_benefit
        bt['hm_daily_delta'] = hm_daily_delta  # 航煤日增量（吨/天），供累加用

    # 候选起始点：每个批次的左边界
    candidates = [0.0]
    cum = 0.0
    for b in batch_timeline[:-1]:
        cum += b['days']
        candidates.append(cum)

    logger.info(f"[航煤时段搜索] delta_H={delta_H:.2f}吨, 总天数={total_days:.2f}, 候选起始点={len(candidates)}个")
    logger.info(f"[航煤时段搜索] 航煤价格={hm_price:.0f}元/t, 收率变化产品数={len(product_deltas)}")
    for bt in batch_timeline:
        logger.info(f"[航煤时段搜索]   批次{bt['batch_id']}({bt['crude_type']},{bt['mode']}): "
                    f"航煤日增量={bt['hm_daily_delta']:,.0f}吨/天, 日净收益={bt['daily_benefit']:,.0f}元/天 "
                    f"(收率差={bt['yield_high']-bt['yield_low']:.4f})")

    # 逐候选起始位置：从 start 开始逐批次累加航煤增量，直到满足 delta_H
    # 航煤工况是连续的 M 天：即使某批次航煤无增量(yield_high==yield_low)，
    # 该批次期间航煤工况仍在运行（rlydmx 仍在减产），天数仍计入 M。
    window_details = []
    best_start = 0.0
    best_benefit = float('-inf')
    best_m = 0.0
    best_feasible = False
    # 不可行时的 fallback：选 hm_accumulated 最大的候选
    best_infeasible_hm = float('-inf')
    best_infeasible_start = 0.0
    best_infeasible_m = 0.0

    for start in candidates:
        m_needed = 0.0          # 该位置满足缺口所需的天数（含无效批次天数）
        hm_accumulated = 0.0    # 累计航煤增量（吨）
        total_benefit = 0.0     # 窗口内总净收益
        covered = []
        satisfied = False

        for bt in batch_timeline:
            if bt['end'] <= start:
                continue
            overlap_start = max(start, bt['start'])
            avail_days = bt['end'] - overlap_start
            if avail_days <= 0:
                continue

            daily_hm_delta = bt['hm_daily_delta']
            daily_benefit = bt['daily_benefit']

            if daily_hm_delta <= 0:
                # 该批次航煤无增量，但航煤工况仍连续（rlydmx 仍在减产）
                # 天数计入 M，收益（通常为负，因 rlydmx 减产损失）也计入
                m_needed += avail_days
                total_benefit += avail_days * daily_benefit
                covered.append({
                    'batch_id': bt['batch_id'],
                    'crude': bt['crude_type'],
                    'mode': bt['mode'],
                    'days': round(avail_days, 2),
                    'daily_input': round(bt['daily_input'], 0),
                    'yield_low': round(bt['yield_low'], 6),
                    'yield_high': round(bt['yield_high'], 6),
                    'hm_daily_delta': 0,
                    'hm_delta': 0,
                    'benefit': round(avail_days * daily_benefit, 0),
                })
                continue

            max_hm_from_batch = daily_hm_delta * avail_days

            if hm_accumulated + max_hm_from_batch >= delta_H:
                # 只需该批次的部分天数即可满足缺口
                days_needed = (delta_H - hm_accumulated) / daily_hm_delta
                m_needed += days_needed
                hm_accumulated += daily_hm_delta * days_needed
                total_benefit += days_needed * daily_benefit
                covered.append({
                    'batch_id': bt['batch_id'],
                    'crude': bt['crude_type'],
                    'mode': bt['mode'],
                    'days': round(days_needed, 2),
                    'daily_input': round(bt['daily_input'], 0),
                    'yield_low': round(bt['yield_low'], 6),
                    'yield_high': round(bt['yield_high'], 6),
                    'hm_daily_delta': round(daily_hm_delta, 0),   # 该批次航煤日增量
                    'hm_delta': round(daily_hm_delta * days_needed, 0),  # 该批次航煤增量
                    'benefit': round(days_needed * daily_benefit, 0),
                })
                satisfied = True
                break  # 已满足缺口
            else:
                # 需要整个批次
                m_needed += avail_days
                hm_accumulated += max_hm_from_batch
                total_benefit += avail_days * daily_benefit
                covered.append({
                    'batch_id': bt['batch_id'],
                    'crude': bt['crude_type'],
                    'mode': bt['mode'],
                    'days': round(avail_days, 2),
                    'daily_input': round(bt['daily_input'], 0),
                    'yield_low': round(bt['yield_low'], 6),
                    'yield_high': round(bt['yield_high'], 6),
                    'hm_daily_delta': round(daily_hm_delta, 0),
                    'hm_delta': round(max_hm_from_batch, 0),
                    'benefit': round(avail_days * daily_benefit, 0),
                })

        feasible = satisfied  # 累计增量是否满足缺口
        end = start + m_needed

        # 选最优：优先可行候选中收益最高的；若全不可行，选增量最大的
        if feasible:
            if total_benefit > best_benefit:
                best_benefit = total_benefit
                best_start = start
                best_m = m_needed
                best_feasible = True
        else:
            if hm_accumulated > best_infeasible_hm:
                best_infeasible_hm = hm_accumulated
                best_infeasible_start = start
                best_infeasible_m = m_needed

        window_details.append({
            'start': round(start, 2),
            'end': round(end, 2),
            'm_days': round(m_needed, 4),          # 该位置的实际 M
            'hm_total': round(hm_accumulated, 0),  # 该窗口航煤总增量（吨）
            'total_benefit': round(total_benefit, 0),
            'covered_batches': covered,
            'feasible': feasible,
            'is_optimal': False,  # 后面统一标记
        })
        status = '✗' if not feasible else ''
        logger.info(f"  起始第{start:6.2f}天: M={m_needed:6.2f}天, 航煤增量={hm_accumulated:>+10,.0f}吨, "
                    f"收益={total_benefit:>+14,.0f}元 {status}")

    # 如果没有可行候选，选增量最大的不可行候选（全月航煤都不够的情况）
    if not best_feasible:
        best_start = best_infeasible_start
        best_m = best_infeasible_m
        best_benefit = float('-inf')  # 没有可行的最优收益

    # 标记最优
    for wd in window_details:
        wd['is_optimal'] = (wd['start'] == round(best_start, 2))

    logger.info(f"[航煤时段搜索] 最优起始: 第{best_start:.2f}天, M={best_m:.2f}天, "
                f"{'可行' if best_feasible else '不可行(全月航煤不够)'}")

    return best_start, best_m, window_details
