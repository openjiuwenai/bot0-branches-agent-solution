# -*- coding: utf-8 -*-
"""
直接计算器（v2 — 纯函数版，无 calc_ctx、无 DB 直连）。

Phase 2 重构：
  - 移除 calc_ctx 参数，派生缓存（device_order/main_feed_yr/target_flow_map）每次重算（<1ms）
  - feed_ratios 由 Service 层预加载后显式传入
  - prices/device_costs 透传给 generate_explanation
"""
from typing import Dict, Optional, Tuple

from ..models.refinery import RefineryScenario, ProcessingUnit, Tank
from ..logger import get_logger
from .yield_resolver import resolve_yield_rate
from .economics import generate_explanation
from ..config import MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL

# 兼容旧 DB 值 'X'/'Y' → 语义键（新 DB 直接存储 'jian1_to_diesel'/'jian1_to_wax'）
SV_KEY_MAP = {'X': 'jian1_to_diesel', 'Y': 'jian1_to_wax'}


def _build_topology(scenario: RefineryScenario) -> list:
    """BFS 拓扑排序（每次重算，<0.5ms，无需缓存）。"""
    device_order = []
    visited = set()
    queue = [scenario.start_device_id]
    visited.add(scenario.start_device_id)

    while queue:
        device_id = queue.pop(0)
        device_order.append(device_id)
        for flow in scenario.get_downstream_flows(device_id):
            to_device_id = flow.to_device_id
            if to_device_id not in visited:
                visited.add(to_device_id)
                queue.append(to_device_id)

    for device_id in scenario.devices:
        if device_id not in visited:
            device_order.append(device_id)

    return device_order


def _init_special_vars(yield_mode: str, input_amount: float,
                       scenario: RefineryScenario) -> Optional[dict]:
    """计算 jian1 理论总量并设置特殊变量 X/Y。"""
    if yield_mode not in (MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL):
        return None

    cdu_id = scenario.start_device_id
    jian1_product = scenario.products.get(f"{cdu_id}_jian1") if cdu_id else None
    jian1_yield = jian1_product.yield_rate if jian1_product else 0
    jian1_theoretical_total = input_amount * jian1_yield

    if yield_mode == MODE_JIAN1_TO_WAX:
        return {'jian1_to_diesel': 0, 'jian1_to_wax': jian1_theoretical_total}
    else:  # MODE_JIAN1_TO_DIESEL
        return {'jian1_to_diesel': jian1_theoretical_total, 'jian1_to_wax': 0}


def _resolve_eff_input(scenario: RefineryScenario, dev_id: str, dev_input: float,
                       connection_flows: dict, main_feed_yr: dict,
                       target_flow_map: dict) -> float:
    """按 max_yr main_feed + target_product_id 计算总进料量。

    新逻辑：连接主料量 = connection_flows[material_flow.id]，
            总进料量 = 连接主料量 / max_yr main_feed.yield_rate
    fallback: device_input / main_feed_yr（旧逻辑）

    connection_flows 由调用方传入，正向遍历和交叉边修正后均取最新值。
    """
    if dev_id == scenario.start_device_id:
        return dev_input
    main_feeds = scenario.get_main_feeds(dev_id)
    if not main_feeds:
        return dev_input
    max_yr_feed = max(main_feeds, key=lambda p: p.yield_rate)
    if max_yr_feed.yield_rate <= 0:
        return dev_input
    mf_id = target_flow_map.get((dev_id, max_yr_feed.id))
    if mf_id:
        connected_qty = connection_flows.get(mf_id, 0)
        if connected_qty > 0:
            return connected_qty / max_yr_feed.yield_rate
    # fallback: 旧逻辑
    mf_yr = main_feed_yr.get(dev_id, 1.0)
    return dev_input / mf_yr if 0 < mf_yr < 1.0 else dev_input


def _forward_traverse(scenario, device_order, input_amount, yield_mode, special_vars,
                      feed_ratios, hangmei_mode, day_index, hangmei_m_days, logger):
    """正向遍历：拓扑序计算 device_inputs, connection_flows, total_feeds。

    返回 (final_device_inputs, main_feed_totals, total_feeds,
          connection_flows, main_feed_yr, target_flow_map)。
    main_feed_yr / target_flow_map 返回供 _fix_cross_edges 中的 _resolve_eff_input 使用。
    """
    # 进料配比 — 从 Service 层预加载的 feed_ratios 获取
    current_crude_type = scenario.crude_type if scenario.crude_type else 'bozhong_25_1'
    if feed_ratios and current_crude_type in feed_ratios:
        device_feed_ratios = feed_ratios[current_crude_type]
    else:
        device_feed_ratios = {}

    # 单次正向遍历：拓扑序保证处理装置 D 时所有上游连接已算完
    final_device_inputs = {}
    main_feed_totals = {}
    total_feeds = {}
    connection_flows = {}

    # 预构建加工装置的主料收率映射 — 每次从 scenario 内存重算（<0.3ms）
    main_feed_yr: Dict[str, float] = {}
    for p in scenario.products.values():
        if p.material_type == 'main_feed' and p.source_device_id in scenario.processing_device_ids:
            existing = main_feed_yr.get(p.source_device_id)
            if not existing or p.yield_rate > existing:
                main_feed_yr[p.source_device_id] = p.yield_rate

    # 预构建 (target_device_id, target_product_id) → material_flow_id 映射 — 每次重算（<0.3ms）
    # 用于按 max_yr main_feed 的 target_product_id 定位连接主料对应的物流边
    target_flow_map: Dict[Tuple[str, str], str] = {}
    for mf in scenario.material_flows.values():
        if mf.flow_type in ('tank_to_target', 'direct') and mf.target_device_id and mf.target_product_id:
            target_flow_map[(mf.target_device_id, mf.target_product_id)] = mf.id

    logger.info(f"========== {yield_mode} 正向遍历计算开始 ==========")
    for device_id in device_order:
        # 计算装置输入量（start 装置直接取 input_amount）
        if device_id == scenario.start_device_id:
            device_input = input_amount
        else:
            upstream_flows = scenario.get_upstream_flows(device_id)
            device_input = sum(connection_flows.get(flow.id, 0) for flow in upstream_flows)
        final_device_inputs[device_id] = device_input

        # 总进料量 = 连接主料量 / 连接主料配比（按 max_yr + target_product_id 定位）
        eff_input = _resolve_eff_input(scenario, device_id, device_input,
                                       connection_flows, main_feed_yr, target_flow_map)
        total_feeds[device_id] = eff_input

        # 进料配比（仅影响 main_feed_totals，不改变流量计算）
        if device_id in device_feed_ratios:
            feed_ratio = device_feed_ratios[device_id]
            main_feed_totals[device_id] = device_input * feed_ratio
        else:
            main_feed_totals[device_id] = device_input

        logger.info(f"[{yield_mode}] {device_id} 总进料={eff_input:.3f}吨，主料={main_feed_totals[device_id]:.3f}吨")

        # 计算下游连接流量
        downstream_flows = scenario.get_downstream_flows(device_id)
        for flow in downstream_flows:
            conn_id = flow.id
            if flow.special_var:
                sv_key = SV_KEY_MAP.get(flow.special_var, flow.special_var)
                if sv_key in special_vars:
                    connection_flows[conn_id] = special_vars[sv_key]
                    continue
            from_dev = scenario.devices.get(device_id)
            # 储罐作为源端时通过率恒为 1.0（物理中转），不依赖 product 配置
            if from_dev and from_dev.is_tank:
                fl = device_input
            else:
                product = scenario.products.get(flow.from_product_id)
                if product:
                    yield_info = resolve_yield_rate(
                        device_id, product, special_vars,
                        hangmei_mode, day_index, hangmei_m_days,
                        yield_switch_device_ids=scenario.yield_switch_device_ids,
                        hangmei_active_device_ids=scenario.hangmei_active_device_ids,
                    )
                    # 加工装置产品产出 = 总进料 × 收率（与 revenue_calculator 口径一致）
                    fl = eff_input * yield_info.yield_rate
                else:
                    fl = 0
            # 罐→加工装置：按 split_ratio 分配
            fl *= flow.split_ratio
            connection_flows[conn_id] = fl

    logger.info(f"========== {yield_mode} 正向遍历计算结束 ==========")

    return final_device_inputs, main_feed_totals, total_feeds, connection_flows, main_feed_yr, target_flow_map


def _fix_cross_edges(scenario, device_order, final_device_inputs, total_feeds,
                     connection_flows, main_feed_yr, target_flow_map, yield_mode, logger):
    """修正 BFS 拓扑序交叉边：从完整 connection_flows 重算 device_inputs 和 total_feeds。

    BFS 拓扑序可能存在交叉边（后处理装置→先处理装置），
    需从已完整的 connection_flows 重新读取 device_inputs 和 total_feeds。
    原地修改 final_device_inputs 和 total_feeds。
    """
    for device_id in device_order:
        if device_id == scenario.start_device_id:
            continue
        upstream_flows = scenario.get_upstream_flows(device_id)
        total_input = sum(connection_flows.get(flow.id, 0) for flow in upstream_flows)
        old_input = final_device_inputs.get(device_id, 0)
        if abs(total_input - old_input) > 1e-6:
            logger.info(f"[{yield_mode}] {device_id} 输入量修正: {old_input:.3f} -> {total_input:.3f}")
            final_device_inputs[device_id] = total_input
            # 交叉边导致 device_input 变化，total_feeds 需基于完整 connection_flows 重算
            new_eff = _resolve_eff_input(scenario, device_id, total_input,
                                         connection_flows, main_feed_yr, target_flow_map)
            old_eff = total_feeds.get(device_id, 0)
            if abs(new_eff - old_eff) > 1e-6:
                logger.info(f"[{yield_mode}] {device_id} 总进料修正: {old_eff:.3f} -> {new_eff:.3f}")
                total_feeds[device_id] = new_eff


def _apply_shutdown_scaling(final_device_inputs, connection_flows, shutdown_intervals,
                            days, scenario, yield_mode, logger):
    """停工按小时比例缩减：装置产出/连接 flow 按非停工时长比例缩减。

    停工语义（v2）：不再触发 X/Y 改道，只按停工时长比例缩减装置产能。
    shutdown_intervals = {device_id: [(start_hour, end_hour), ...]}
    批次时长 days（支持小数），停工占比 = 停工小时 / (days * 24)。
    进料罐照常接收 CDU 来料（原料成本照计），仅罐→停工装置的出向 flow 缩减。
    原地修改 final_device_inputs 和 connection_flows。
    """
    if not shutdown_intervals or not days or days <= 0:
        return
    batch_hours = days * 24
    for dev_id, intervals in shutdown_intervals.items():
        if dev_id not in final_device_inputs:
            continue
        shutdown_hours = sum(e - s for s, e in intervals)
        shutdown_ratio = min(shutdown_hours / batch_hours, 1.0) if batch_hours > 0 else 0
        if shutdown_ratio <= 0:
            continue
        keep_ratio = 1.0 - shutdown_ratio
        logger.info(f"[{yield_mode}] 停工缩减：{dev_id} device_inputs "
                    f"{final_device_inputs[dev_id]:.3f} -> {final_device_inputs[dev_id] * keep_ratio:.3f} "
                    f"(停工{shutdown_hours:.1f}h/{batch_hours:.1f}h={shutdown_ratio:.1%})")
        final_device_inputs[dev_id] *= keep_ratio
        scaled_conns = []
        for flow_id, flow in scenario.material_flows.items():
            if flow.from_device_id == dev_id or flow.to_device_id == dev_id:
                old_flow = connection_flows.get(flow_id, 0)
                if abs(old_flow) > 1e-9:
                    connection_flows[flow_id] = old_flow * keep_ratio
                    scaled_conns.append(f"{flow_id}({old_flow:.3f}->{old_flow * keep_ratio:.3f})")
        if scaled_conns:
            logger.info(f"[{yield_mode}] 停工缩减：{dev_id} 相关连接 flow 缩减: "
                        f"{', '.join(scaled_conns)}")


def _build_device_utilization(scenario, final_device_inputs, connection_flows, days):
    """构建装置利用率数据（仅进料+基础信息，不含单批次超容判定）。

    容量校验已移至月度口径（_build_monthly_load）和罐容检测（TankCapacityChecker）。
    device_utilization 仅保留进料和基础信息，超容判定统一在月度口径。
    跳过停用装置（enabled=False），使其不出现在 device_utilization 和后续月度负荷中。
    """
    device_utilization = {}
    for device_id, device in scenario.devices.items():
        if not device.enabled and not (isinstance(device, ProcessingUnit) and device.is_start):
            continue
        inflow = final_device_inputs.get(device_id, 0)
        # 出向流量(日值)：该装置所有出向连接 flow 之和。成品罐无出向连接→0(只进不出)。
        outflow = sum(connection_flows.get(f.id, 0) for f in scenario.material_flows.values()
                      if f.from_device_id == device_id)

        if inflow != inflow:  # NaN 检查
            inflow = 0

        # 收率切换被动装置的展示投入量只按上游罐主进料 H 算，
        # 不计主动装置回流 B。回流 B 仍参与产出/收入计算。
        if device_id in scenario.yield_switch_device_ids and device_id not in scenario.hangmei_active_device_ids:
            upstream = scenario.get_upstream_flows(device_id)
            tank_ids = scenario.tank_device_ids
            main_inflow = sum(connection_flows.get(f.id, 0) for f in upstream
                              if f.from_device_id in tank_ids)
        else:
            main_inflow = inflow
        if main_inflow != main_inflow:  # NaN 检查
            main_inflow = 0

        du_entry = {
            'name': device.name,
            'type': device.type if isinstance(device, ProcessingUnit) else 'tank',
            'tank_category': device.tank_category if isinstance(device, Tank) else None,
            'note': device.note,
            'input': main_inflow,
            'total_input': inflow,
            'outflow': outflow,
            # 罐容库存指标（仅 tank 类型有意义；加工装置为 0）
            'current_capacity': device.current_capacity,
            'safety_stock_thrd': device.safety_stock_thrd,
            'low_safety_thrd': device.low_safety_thrd,
            'refinery_unit_load_percent': device.refinery_unit_load_percent,
        }

        # CDU 批次级负荷判定（CDU 日均稳定，批次级判定有意义）
        # main_inflow 现为批次值，需除以 days 得日均量再与日均能力比较
        if isinstance(device, ProcessingUnit) and device.is_start:
            load_pct = device.refinery_unit_load_percent or 100
            cdu_daily_cap = device.safety_stock_thrd * (load_pct / 100.0)
            du_entry['cdu_daily_cap'] = round(cdu_daily_cap, 1)
            daily_inflow = main_inflow / days if days > 0 else main_inflow
            du_entry['cdu_overload'] = daily_inflow > cdu_daily_cap + 1e-6 if cdu_daily_cap > 0 else False

        device_utilization[device_id] = du_entry

    return device_utilization


def calculate_physical(
    scenario: RefineryScenario,
    input_amount: float,
    yield_mode: str,
    logger=None,
    total_input_amount: float = None,
    days: int = 1,
    hangmei_mode: bool = False,
    hangmei_m_days: float = 0,
    day_index: float = 0,
    shutdown_intervals: dict = None,
    feed_ratios: Optional[Dict[str, Dict[str, float]]] = None,
) -> Tuple[bool, dict]:
    """纯物理计算（不含经济计算/价格参数）。

    执行 BFS 拓扑遍历、流量计算、停工缩减、装置利用率构建，
    返回物理字段 result_dict（不含 explanation）。

    Args:
        scenario: RefineryScenario 对象
        input_amount: 批次输入量
        yield_mode: MODE_JIAN1_TO_WAX 或 MODE_JIAN1_TO_DIESEL
        logger: 日志记录器
        total_input_amount: 总输入量（用于 result_dict.input_amount），None 则用 input_amount
        days: 周期天数
        hangmei_mode: 是否启用航煤工况
        hangmei_m_days: 航煤工况天数（M 值）
        day_index: 当前天数索引
        shutdown_intervals: 本批次停工装置及月内绝对小时区间，格式
            {device_id: [(start_hour, end_hour), ...]}。停工时段内装置产出按
            非停工时长比例缩减（非整段置零）；进料罐照常接收 CDU 来料，
            仅罐→停工装置的出向 flow 按同比例缩减。None 或空表示无停工。
        feed_ratios: 预加载的进料配比 {crude_type: {proc_id: ratio}}，由 Service 层传入
    Returns:
        (feasible, result_dict) — result_dict 不含 explanation

    Scenario 依赖:
        - devices: 装置字典（能力/类型）
        - material_flows: 物流拓扑（BFS遍历）
        - products: 产品收率/物料类型/物料名称
        - start_device_id: 常减压装置ID
        - yield_switch_device_ids: 收率切换装置
        - hangmei_active_device_ids: 航煤主动装置
        - processing_device_ids: 加工装置列表
        - tank_device_ids: 储罐装置列表
        - crude_type: 原油品种标识
        - get_upstream_flows(): 上游物流查询
        - get_downstream_flows(): 下游物流查询
        - get_main_feeds(): 装置主料查询
    """
    if logger is None:
        logger = get_logger()

    try:
        logger.info(f"========== calculate_direct开始：yield_mode={yield_mode} ==========")

        special_vars = _init_special_vars(yield_mode, input_amount, scenario)
        if special_vars is None:
            return False, {'message': f'不支持的模式: {yield_mode}'}

        logger.info(f"[{yield_mode}] 设置特殊变量后: jian1_to_diesel={special_vars.get('jian1_to_diesel')}, jian1_to_wax={special_vars.get('jian1_to_wax')}")

        device_order = _build_topology(scenario)

        final_device_inputs, main_feed_totals, total_feeds, connection_flows, main_feed_yr, target_flow_map = \
            _forward_traverse(scenario, device_order, input_amount, yield_mode, special_vars,
                              feed_ratios, hangmei_mode, day_index, hangmei_m_days, logger)

        _fix_cross_edges(scenario, device_order, final_device_inputs, total_feeds,
                         connection_flows, main_feed_yr, target_flow_map, yield_mode, logger)

        _apply_shutdown_scaling(final_device_inputs, connection_flows, shutdown_intervals,
                                days, scenario, yield_mode, logger)

        device_utilization = _build_device_utilization(
            scenario, final_device_inputs, connection_flows, days)

        result = {
            'device_inputs': final_device_inputs,
            'main_feed_totals': main_feed_totals,  # 新增：主进料总量（用于装置负荷校验）
            'total_feeds': total_feeds,            # 新增：总进料（用于产出计算）
            'connection_flows': connection_flows,
            'special_vars': special_vars,
            'device_utilization': device_utilization,
            'input_amount': total_input_amount if total_input_amount is not None else input_amount,
            'feasible': True,
            'bottleneck_devices': [],
        }
        return True, result

    except Exception as e:
        logger.error(f"[{yield_mode}] 直接计算失败: {e}")
        import traceback
        logger.error(traceback.format_exc())
        return False, {'message': f'直接计算失败: {str(e)}'}


def calculate_direct(
    scenario: RefineryScenario,
    input_amount: float,
    yield_mode: str,
    logger=None,
    total_input_amount: float = None,
    days: int = 1,
    hangmei_mode: bool = False,
    hangmei_m_days: float = 0,
    day_index: float = 0,
    plan_month: str = None,
    shutdown_intervals: dict = None,
    capacity_only: bool = False,
    summary_only: bool = False,
    prices: Optional[Dict[str, float]] = None,
    device_costs: Optional[Dict[int, float]] = None,
    feed_ratios: Optional[Dict[str, Dict[str, float]]] = None,
) -> Tuple[bool, dict]:
    """直接计算（兼容包装）：调用 calculate_physical 得到物理结果，再根据
    summary_only/capacity_only 调用 generate_explanation（统一入口），
    将 explanation 写入 result_dict 并返回。原有调用方无需修改。

    Scenario 依赖:
        - 继承 calculate_physical 全部依赖
        - processing_device_ids: 加工装置列表（generate_explanation 直接使用）
        - start_device_id: 常减压装置ID（generate_explanation 直接使用）
    """
    if logger is None:
        logger = get_logger()

    feasible, result = calculate_physical(
        scenario, input_amount, yield_mode, logger,
        total_input_amount=total_input_amount, days=days,
        hangmei_mode=hangmei_mode, hangmei_m_days=hangmei_m_days,
        day_index=day_index, shutdown_intervals=shutdown_intervals,
        feed_ratios=feed_ratios)

    if 'message' in result:  # 物理计算出错
        return feasible, result

    if capacity_only or summary_only:
        # capacity_only 也需 explanation 产出 feed_details，
        # 供 _build_monthly_load 计算主料负荷量（连接主料 vs 总主料占比）
        explanation = generate_explanation(scenario, result, days, plan_month=plan_month,
                                hangmei_mode=hangmei_mode, day_index=day_index,
                                hangmei_m_days=hangmei_m_days,
                                prices=prices, device_costs=device_costs)
    else:
        explanation = generate_explanation(scenario, result, days, plan_month=plan_month,
                                    hangmei_mode=hangmei_mode, day_index=day_index,
                                    hangmei_m_days=hangmei_m_days,
                                    prices=prices, device_costs=device_costs)
    total_economic_benefit = explanation.get('total_economic_benefit', 0)
    result['explanation'] = explanation

    special_vars = result.get('special_vars', {})
    jian1_theoretical_total = special_vars.get('jian1_to_diesel', 0) + special_vars.get('jian1_to_wax', 0)
    logger.info(f"[{yield_mode}] 直接计算{'成功' if feasible else '完成(含超容，理论收益)'}")
    if yield_mode == MODE_JIAN1_TO_WAX:
        logger.info(f"减一线去蜡油加氢: jian1_to_diesel=0, jian1_to_wax={jian1_theoretical_total:,.3f}吨")
        logger.info(f"cyjq_01使用yield_rate，lyjq_01使用yield_rate_2")
    else:
        logger.info(f"减一线去柴油加氢: jian1_to_diesel={jian1_theoretical_total:,.3f}吨, jian1_to_wax=0")
        logger.info(f"cyjq_01使用yield_rate_2，lyjq_01使用yield_rate")

    logger.info(f"总经济效益: {total_economic_benefit:,.2f}元 ({total_economic_benefit/10000:,.2f}万元)")

    return feasible, result
