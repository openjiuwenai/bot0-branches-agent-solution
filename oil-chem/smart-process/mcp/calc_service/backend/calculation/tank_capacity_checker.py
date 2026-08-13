# -*- coding: utf-8 -*-
"""罐容约束段级检测器。

以罐为中心，基于工况切换时间点构建统一时间分段（segment），
逐罐逐段推演库存，检测安全阈值违规。

当前阶段：检测优先，不做可行性判定。输出 tank_check_result 透传至前端展示。

架构：
  SegmentBuilder  →  构建统一时间分段（航煤边界拆分）
  TankSimulator   →  逐罐逐段推演库存 + 违规检测
  TankCapacityChecker  →  集成入口
"""
from __future__ import annotations
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set, Tuple

from ..models.refinery import DeviceBase, MaterialFlow  # noqa: F401 — type hints only
from ..config import MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL


# ── 数据结构 ──────────────────────────────────────────────────────────────

@dataclass
class Segment:
    """统一时间分段中的一个段。"""
    seg_id: int
    batch_idx: int                    # 所属批次在 batches 列表中的索引
    batch_id: int                     # 所属批次ID
    start_day: float                  # 段起始天数（月内绝对位置）
    end_day: float                    # 段结束天数
    days: float                       # 段天数
    crude_type: str
    mode: str                         # MODE_JIAN1_TO_WAX / MODE_JIAN1_TO_DIESEL
    is_hangmei: bool                  # 是否在航煤工况时段内
    shutdown_intervals: dict = field(default_factory=dict)  # 该段停工装置及小时区间

    def to_dict(self) -> dict:
        return {
            'seg_id': self.seg_id,
            'batch_idx': self.batch_idx,
            'batch_id': self.batch_id,
            'start_day': round(self.start_day, 2),
            'end_day': round(self.end_day, 2),
            'days': round(self.days, 2),
            'crude_type': self.crude_type,
            'mode': self.mode,
            'is_hangmei': self.is_hangmei,
            'shutdown_intervals': self.shutdown_intervals,
            'shutdown_devices': list(self.shutdown_intervals.keys()) if self.shutdown_intervals else [],
        }


@dataclass
class TankViolation:
    """罐容违规记录。"""
    tank_id: str
    tank_name: str
    seg_id: int
    start_day: float
    end_day: float
    capacity: float          # 违规时的库存量
    threshold: float         # 违规的阈值
    violation_type: str      # 'over_high' / 'below_low'
    severity: float          # 超出量（绝对值）

    def to_dict(self) -> dict:
        return {
            'tank_id': self.tank_id,
            'tank_name': self.tank_name,
            'seg_id': self.seg_id,
            'start_day': round(self.start_day, 2),
            'end_day': round(self.end_day, 2),
            'capacity': round(self.capacity, 1),
            'threshold': round(self.threshold, 1),
            'violation_type': self.violation_type,
            'severity': round(self.severity, 1),
        }


@dataclass
class TankTrajectory:
    """单个罐的库存轨迹。"""
    tank_id: str
    tank_name: str
    tank_category: str
    safety_stock_thrd: float
    low_safety_thrd: float
    initial_capacity: float
    points: List[dict] = field(default_factory=list)    # 每个段的 {seg_id, start_cap, end_cap, inflow, outflow, delta}
    violations: List[TankViolation] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            'tank_id': self.tank_id,
            'tank_name': self.tank_name,
            'tank_category': self.tank_category,
            'safety_stock_thrd': round(self.safety_stock_thrd, 1),
            'low_safety_thrd': round(self.low_safety_thrd, 1),
            'initial_capacity': round(self.initial_capacity, 1),
            'points': self.points,
            'violations': [v.to_dict() for v in self.violations],
        }


# ── SegmentBuilder ───────────────────────────────────────────────────────

class SegmentBuilder:
    """构建统一时间分段。

    输入批次已由 switch_planner 按停工边界拆分为子批次。
    本类在此基础上，按航煤工况时段边界进一步拆分跨越边界的批次。

    切换点类型：
      - 油种切换 → 批次边界（已有）
      - 减一线切换 → 批次边界（已有，switch_pos 决定每批次 mode）
      - 装置停工 → 已由 switch_planner 拆分为子批次
      - 航煤工况 → 可能落在批次中间，需本类拆分
    """

    @staticmethod
    def build(batches: List[dict],
              hangmei_enabled: bool = False,
              hangmei_best_start: float = 0.0,
              hangmei_m_days: float = 0.0,
              switches: dict = None) -> List[Segment]:
        """构建统一时间分段列表。

        Args:
            batches: 批次列表（已含停工拆分），每个 batch 含 batch_id/start_day/end_day/days/crude_type/shutdown_intervals
            hangmei_enabled: 航煤工况是否启用
            hangmei_best_start: 航煤最优起始天数（月内绝对位置）
            hangmei_m_days: 航煤工况持续天数
            switches: {batch_id: mode} 阀门切换模式（MODE_JIAN1_TO_WAX/MODE_JIAN1_TO_DIESEL）

        Returns:
            按时间顺序排列的 Segment 列表
        """
        segments: List[Segment] = []
        seg_id = 0
        _switches = switches or {}

        # 航煤时段边界 [hm_start, hm_end)
        hm_start = hangmei_best_start if hangmei_enabled else None
        hm_end = (hangmei_best_start + hangmei_m_days) if hangmei_enabled else None

        # 用累计天数构建连续时间轴，避免日历日号导致的段重叠
        cum_start = 0.0
        for batch_idx, batch in enumerate(batches):
            batch_days = float(batch.get('days', 0))
            batch_start = cum_start
            batch_end = cum_start + batch_days
            cum_start = batch_end
            batch_id = batch.get('batch_id', batch_idx + 1)
            crude_type = batch.get('crude_type', '')
            mode = _switches.get(batch_id, MODE_JIAN1_TO_WAX)
            sd_intervals = batch.get('shutdown_intervals', {})

            if hm_start is not None and hm_end is not None and hm_end > hm_start:
                # 航煤边界落在批次中间：拆分为航煤段和非航煤段
                splits = SegmentBuilder._split_by_hangmei(
                    batch_start, batch_end, hm_start, hm_end)
            else:
                splits = [(batch_start, batch_end, False)]

            # 收集该批次所有停工边界点（天），用于进一步切分
            sd_boundaries: set = set()
            for _dev, intervals in sd_intervals.items():
                for (sh_s, sh_e) in intervals:
                    sd_boundaries.add(sh_s / 24.0)  # 小时→天
                    sd_boundaries.add(sh_e / 24.0)

            for (s, e, is_hm) in splits:
                # 按停工边界进一步切分
                sub_splits = SegmentBuilder._split_by_boundaries(
                    s, e, sd_boundaries)
                for (ss, se) in sub_splits:
                    seg_days = se - ss
                    if seg_days <= 1e-9:
                        continue
                    segments.append(Segment(
                        seg_id=seg_id,
                        batch_idx=batch_idx,
                        batch_id=batch_id,
                        start_day=ss,
                        end_day=se,
                        days=seg_days,
                        crude_type=crude_type,
                        mode=mode,
                        is_hangmei=is_hm,
                        shutdown_intervals=sd_intervals,
                    ))
                    seg_id += 1

        return segments

    @staticmethod
    def _split_by_boundaries(seg_start: float, seg_end: float,
                             boundaries: set
                             ) -> List[Tuple[float, float]]:
        """将段按给定边界点切分为 (start, end) 列表。

        只保留落在 (seg_start, seg_end) 内部的边界点用于切分。
        """
        if not boundaries:
            return [(seg_start, seg_end)]
        # 收集落在段内部的边界点
        inner = sorted(b for b in boundaries if seg_start < b < seg_end)
        if not inner:
            return [(seg_start, seg_end)]
        result = []
        prev = seg_start
        for b in inner:
            result.append((prev, b))
            prev = b
        result.append((prev, seg_end))
        return result

    @staticmethod
    def _split_by_hangmei(batch_start: float, batch_end: float,
                          hm_start: float, hm_end: float
                          ) -> List[Tuple[float, float, bool]]:
        """将批次按航煤时段边界拆分为 (start, end, is_hangmei) 列表。

        航煤时段 [hm_start, hm_end) 与批次 [batch_start, batch_end) 的交集关系：
        - 无交集：1段
        - 批次包含航煤段：3段（前非航煤 + 航煤 + 后非航煤）
        - 批次与航煤段左重叠：2段（航煤 + 后非航煤）
        - 批次与航煤段右重叠：2段（前非航煤 + 航煤）
        - 批次被航煤段包含：1段（全航煤）
        """
        result: List[Tuple[float, float, bool]] = []

        # 无交集
        if batch_end <= hm_start or batch_start >= hm_end:
            return [(batch_start, batch_end, False)]

        # 前非航煤段
        if batch_start < hm_start:
            result.append((batch_start, hm_start, False))

        # 航煤段
        hm_s = max(batch_start, hm_start)
        hm_e = min(batch_end, hm_end)
        if hm_e > hm_s:
            result.append((hm_s, hm_e, True))

        # 后非航煤段
        if batch_end > hm_end:
            result.append((hm_end, batch_end, False))

        return result


# ── TankSimulator ────────────────────────────────────────────────────────

class TankSimulator:
    """逐罐逐段推演库存，检测安全阈值违规。

    每个罐使用独立的时间网格（segment），而非所有罐共用统一网格。
    原因：每个罐的上下游装置不同，其加工时间轴（processing_days）与 CDU 批次
    边界不同步。用 CDU 批次边界统一切分会导致 inflow/outflow 时间分配不准确。

    独立网格切分点 = 上下游装置加工边界 ∪ 停工边界 ∪ 航煤边界。

    进料：按上游装置加工时段计算。上游装置在 [tl_s, tl_e) 加工某批次料，
    日均产出 = batch_output / processing_days，进罐量 = 日均产出 × 重叠天数。
    无上游映射时 fallback 到 direct_calculator 的 input。

    出料：采用月平均日处理量（均匀出料），停工段排除停工装置的出料贡献。
    """

    @staticmethod
    def simulate(segments: List[Segment],
                 batches: List[dict],
                 calc_results: List[dict],
                 intermediate_tank_ids: Set[str],
                 devices: Dict[str, DeviceBase],
                 material_flows: Dict[str, MaterialFlow],
                 tank_initials: Optional[Dict[str, float]] = None,
                 batch_details: Optional[List[dict]] = None,
                 ) -> List[TankTrajectory]:
        """逐罐逐段推演库存。

        Args:
            segments: SegmentBuilder 构建的时间分段
            batches: 批次列表（用于计算月总天数和月平均出料）
            calc_results: 各批次的 calc_result（与 batches 列表对齐）
            intermediate_tank_ids: 中间罐ID集合
            devices: 装置字典 {device_id: DeviceBase}
            material_flows: 物流字典 {flow_id: MaterialFlow}
            tank_initials: {tank_id: initial_capacity} 月初库存

        Returns:
            每个中间罐的 TankTrajectory 列表
        """
        _initials = tank_initials or {}

        # 初始化每个罐的轨迹
        trajectories: Dict[str, TankTrajectory] = {}
        for tank_id in intermediate_tank_ids:
            device = devices.get(tank_id)
            if not device:
                continue
            init_cap = _initials.get(tank_id, device.current_capacity)
            trajectories[tank_id] = TankTrajectory(
                tank_id=tank_id,
                tank_name=device.name,
                tank_category=device.tank_category or 'intermediate',
                safety_stock_thrd=device.safety_stock_thrd,
                low_safety_thrd=device.low_safety_thrd,
                initial_capacity=init_cap,
            )

        if not trajectories:
            return []

        # 1. 计算月总天数
        total_days = sum(float(b.get('days', 0) or 0) for b in batches)
        if total_days <= 0:
            return []

        # 2. 构建罐→下游装置映射（通过 material_flows 拓扑）
        # tank_to_target 流：tank_id（来源罐）→ (flow_id, target_device_id, source_product_id)
        # flow_id 用于从 connection_flows 取物理分配流量
        # source_product_id 指向目的装置的主料product_id，用于从feed_details取实际消耗量
        tank_to_downstream: Dict[str, List[Tuple[str, str, str]]] = {}
        for flow in material_flows.values():
            if flow.flow_type != 'tank_to_target':
                continue
            if flow.tank_id in trajectories:
                tank_to_downstream.setdefault(flow.tank_id, []).append(
                    (flow.id, flow.target_device_id, flow.source_product_id or ''))

        # 2b. 构建罐→上游装置映射（source_to_tank 流），用于按 processing_days 重算进料
        # 存储 (source_device_id, source_product_id) 以便从 all_product_outputs 查找批次产出
        tank_to_upstream: Dict[str, List[Tuple[str, str]]] = {}
        if batch_details:
            for flow in material_flows.values():
                if flow.flow_type != 'source_to_tank':
                    continue
                if flow.tank_id in trajectories and flow.source_device_id:
                    tank_to_upstream.setdefault(flow.tank_id, []).append(
                        (flow.source_device_id, flow.source_product_id or ''))

        # 3. 计算每个中间罐的日出料速率（按下游装置分别记录）
        # 出料速率口径（罐出料 = 该罐供给的物料在下游装置中的实际消耗量）：
        #   - 优先从 feed_details 按 source_product_id 精确匹配物料的 feed_qty（收率反算口径）
        #   - fallback 到 connection_flows（物理分配口径）
        #   - 超容时按 月度能力/装置总主料 折减
        avg_outflow: Dict[str, float] = {}
        device_outflow: Dict[str, Dict[str, float]] = {}  # {tank_id: {device_id: daily_outflow}}
        # 预构建每个 batch 的 feed_map（device_id → feed_detail），避免每个(tank,downstream,batch)重建
        batch_feed_maps: list = []
        for i, batch in enumerate(batches):
            if i >= len(calc_results):
                batch_feed_maps.append({})
                continue
            calc = calc_results[i] or {}
            exp = calc.get('explanation') or {}
            fm: dict = {}
            for fd in (exp.get('feed_details') or []):
                fm[fd.get('device_id')] = fd
            batch_feed_maps.append(fm)
        for tank_id in trajectories:
            downstream_list = tank_to_downstream.get(tank_id, [])
            if not downstream_list:
                avg_outflow[tank_id] = 0.0
                device_outflow[tank_id] = {}
                continue
            dev_out: Dict[str, float] = {}
            for flow_id, ds_id, src_pid in downstream_list:
                # 累计该罐→装置的全月物料消耗量 + 装置总主料 + 停工时长
                total_consumed = 0.0    # 该罐供给物料的实际消耗累计（feed_details口径）
                total_connected = 0.0   # 该罐→装置的物理分配流量累计（fallback口径）
                total_main = 0.0        # 装置总主料累计（用于超容判断）
                sh_hours = 0.0
                cap_day = 0.0
                load_pct = 100.0        # 合并 load_pct 收集，避免冗余第二次遍历
                for i, batch in enumerate(batches):
                    days = float(batch.get('days', 0) or 0)
                    if i >= len(calc_results) or days <= 0:
                        continue
                    calc = calc_results[i] or {}
                    # 物理分配流量：从 connection_flows 取精确的罐→装置流量
                    cf = calc.get('connection_flows', {})
                    connected = float(cf.get(flow_id, 0) or 0)
                    total_connected += connected  # 批次值直接累加
                    du = calc.get('device_utilization', {})
                    u = du.get(ds_id, {})
                    # 装置总主料 + 该罐供给物料的实际消耗量（从 explanation.feed_details 取）
                    # 注意：feed_qty / connected / mft_val 均为批次值，直接累加
                    fd = batch_feed_maps[i].get(ds_id) if batch_feed_maps[i] else None
                    if fd and fd.get('items'):
                        main_items = [it for it in fd['items'] if it.get('label') == '主料']
                        if main_items:
                            total_main += sum(float(it.get('feed_qty', 0)) for it in main_items)  # 已是批次值
                            # 按 source_product_id 精确匹配该罐供给物料的消耗量
                            if src_pid:
                                matched = [it for it in main_items if it.get('product_id', '').split('~')[0] == src_pid]
                                if matched:
                                    total_consumed += sum(float(it.get('feed_qty', 0)) for it in matched)  # 已是批次值
                        else:
                            total_main += connected  # 批次值
                    else:
                        # explanation 无 feed_details：fallback 用 main_feed_totals（批次值）
                        mft = calc.get('main_feed_totals') or {}
                        mft_val = float(mft.get(ds_id, 0) or 0)
                        if mft_val > 0:
                            total_main += mft_val  # 批次值
                        else:
                            total_main += connected  # 批次值
                    if cap_day <= 0:
                        cap_day = float(u.get('safety_stock_thrd', 0) or 0)
                    # 合并 load_pct 收集（第一个非零值即停止）
                    if load_pct == 100.0:
                        lp = float(u.get('refinery_unit_load_percent', 100) or 100)
                        if lp > 0 and lp != 100.0:
                            load_pct = lp
                    # 聚合停工时长
                    si = batch.get('shutdown_intervals') or {}
                    for _dev, _iv in si.items():
                        if _dev == ds_id:
                            sh_hours += sum(e - s for s, e in _iv)
                # 出料口径：优先用 feed_details 的实际消耗量，fallback 到物理分配流量
                use_consumed = total_consumed > 0
                base_total = total_consumed if use_consumed else total_connected
                avg_daily = base_total / total_days if total_days > 0 else 0.0
                # 装置全月无进料（未启用，如 cyjq_02）：跳过，不计入罐出料
                if total_connected <= 0 and total_main <= 0:
                    continue
                # 超容判断：装置总主料 vs 月度能力（与 _build_monthly_load 口径一致）
                eff_days = max(total_days - sh_hours / 24.0, 0.1)
                monthly_capacity = cap_day * eff_days * (load_pct / 100.0)
                if monthly_capacity > 0 and total_main > monthly_capacity:
                    # 超容：按能力折减比折算出料
                    # 折减比 = 月度能力 / 装置总主料（该罐供给的部分同样按此比例折减）
                    reduction_ratio = monthly_capacity / total_main
                    dev_out[ds_id] = avg_daily * reduction_ratio
                else:
                    # 未超容：月平均取料
                    dev_out[ds_id] = avg_daily
            device_outflow[tank_id] = dev_out
            avg_outflow[tank_id] = sum(dev_out.values())

        # 4. 推算装置加工时段（连续排产假设）
        # 装置按 CDU 批次顺序连续加工，每批次加工 processing_days 天。
        # Σ(processing_days) = 月总天数，装置全月持续加工。
        # 但不同批次的加工边界与 CDU 批次边界不同步，需独立推算。
        device_timelines: Dict[str, List[Tuple[float, float, int]]] = {}
        if batch_details:
            cum: Dict[str, float] = {}
            for batch_idx, bd in enumerate(batch_details):
                du = bd.get('device_utilization') or {}
                for dev_id, u in du.items():
                    if not u or u.get('type') in ('tank', 'start'):
                        continue
                    pdays = float(u.get('processing_days', 0) or 0)
                    if pdays <= 0:
                        continue
                    s = cum.get(dev_id, 0.0)
                    e = s + pdays
                    device_timelines.setdefault(dev_id, []).append((s, e, batch_idx))
                    cum[dev_id] = e

        # 5. 逐罐构建独立时间网格并推演库存
        for tank_id, traj in trajectories.items():
            tank_segs = TankSimulator._build_tank_segments(
                tank_id, segments, tank_to_upstream, tank_to_downstream,
                device_timelines, total_days)

            for tseg in tank_segs:
                batch_idx = tseg['batch_idx']
                calc = calc_results[batch_idx] if batch_idx < len(calc_results) else None
                calc = calc or {}
                seg_start_h = tseg['start_day'] * 24.0
                seg_end_h = tseg['end_day'] * 24.0
                seg_shutdown = tseg.get('shutdown_intervals') or {}
                seg_days = tseg['days']

                # ── 进料：按上游装置加工时段计算 ──
                # 上游装置在 [tl_s, tl_e) 加工批次 tl_bidx 的料，
                # 日均产出 = batch_output / processing_days
                # 该段进罐量 = Σ(日均产出 × 与该段的重叠天数)
                upstream_devs = tank_to_upstream.get(tank_id, [])
                inflow_total = 0.0  # 该段总进罐量（吨）
                if upstream_devs and device_timelines:
                    for up_dev, up_pid in upstream_devs:
                        for (tl_s, tl_e, tl_bidx) in device_timelines.get(up_dev, []):
                            overlap_s = max(tl_s, tseg['start_day'])
                            overlap_e = min(tl_e, tseg['end_day'])
                            if overlap_e <= overlap_s:
                                continue
                            overlap_days = overlap_e - overlap_s
                            if tl_bidx >= len(calc_results) or not up_pid:
                                continue
                            tl_calc = calc_results[tl_bidx] or {}
                            all_po = (tl_calc.get('explanation') or {}).get('all_product_outputs', {})
                            # 按 product_id 匹配上游装置产出（不依赖产品名）
                            batch_output = 0.0
                            for _pname, pinfo in (all_po.get(up_dev, {}) or {}).items():
                                if pinfo.get('product_id') == up_pid:
                                    batch_output = float(pinfo.get('output', 0) or 0)
                                    break
                            if batch_output <= 0:
                                continue
                            tl_bd = batch_details[tl_bidx] if tl_bidx < len(batch_details) else {}
                            tl_du = (tl_bd.get('device_utilization') or {}).get(up_dev, {})
                            pdays = float(tl_du.get('processing_days', 0) or 0)
                            if pdays > 0:
                                inflow_total += (batch_output / pdays) * overlap_days
                # fallback: 上游装置无加工时段数据或无产出数据，
                # 用 direct_calculator 的 input（批次值）折算为段值
                if inflow_total <= 0:
                    u = calc.get('device_utilization', {}).get(tank_id, {})
                    batch_days = float(batches[batch_idx].get('days', 0) or 0) if batch_idx < len(batches) else seg_days
                    if batch_days > 0:
                        inflow_total = float(u.get('input', 0) or 0) / batch_days * seg_days
                    else:
                        inflow_total = 0.0

                inflow = inflow_total / seg_days if seg_days > 0 else 0.0

                # ── 出料：月平均日处理量，停工段排除 ──
                outflow_rate = 0.0
                dev_out_map = device_outflow.get(tank_id, {})
                for _flow_id, ds_id, _src_pid in tank_to_downstream.get(tank_id, []):
                    ds_out = dev_out_map.get(ds_id, 0.0)
                    if ds_out <= 0:
                        continue
                    ds_sh_hours = 0.0
                    intervals = seg_shutdown.get(ds_id) or []
                    for (sh_s, sh_e) in intervals:
                        overlap_s = max(sh_s, seg_start_h)
                        overlap_e = min(sh_e, seg_end_h)
                        if overlap_e > overlap_s:
                            ds_sh_hours += (overlap_e - overlap_s)
                    ds_total_h = seg_days * 24.0
                    ds_eff_ratio = max(0.0, (ds_total_h - ds_sh_hours) / ds_total_h) if ds_total_h > 0 else 0.0
                    outflow_rate += ds_out * ds_eff_ratio

                # 上一段结束库存 = 本段起始库存
                if traj.points:
                    start_cap = traj.points[-1]['end_cap']
                else:
                    start_cap = traj.initial_capacity

                delta = (inflow - outflow_rate) * seg_days
                end_cap = start_cap + delta

                point = {
                    'seg_id': tseg['seg_id'],
                    'start_day': round(tseg['start_day'], 2),
                    'end_day': round(tseg['end_day'], 2),
                    'days': round(seg_days, 2),
                    'is_hangmei': tseg['is_hangmei'],
                    'batch_id': tseg['batch_id'],
                    'inflow': round(inflow, 1),
                    'outflow': round(outflow_rate, 1),
                    'delta': round(delta, 1),
                    'start_cap': round(start_cap, 1),
                    'end_cap': round(end_cap, 1),
                    'util_pct': round(end_cap / traj.safety_stock_thrd * 100, 1)
                        if traj.safety_stock_thrd > 0 else None,
                }
                traj.points.append(point)

                # 违规检测：超上限
                if traj.safety_stock_thrd > 0 and end_cap > traj.safety_stock_thrd:
                    traj.violations.append(TankViolation(
                        tank_id=tank_id,
                        tank_name=traj.tank_name,
                        seg_id=tseg['seg_id'],
                        start_day=tseg['start_day'],
                        end_day=tseg['end_day'],
                        capacity=end_cap,
                        threshold=traj.safety_stock_thrd,
                        violation_type='over_high',
                        severity=end_cap - traj.safety_stock_thrd,
                    ))

                # 违规检测：低于下限
                if traj.low_safety_thrd > 0 and end_cap < traj.low_safety_thrd:
                    traj.violations.append(TankViolation(
                        tank_id=tank_id,
                        tank_name=traj.tank_name,
                        seg_id=tseg['seg_id'],
                        start_day=tseg['start_day'],
                        end_day=tseg['end_day'],
                        capacity=end_cap,
                        threshold=traj.low_safety_thrd,
                        violation_type='below_low',
                        severity=traj.low_safety_thrd - end_cap,
                    ))

        return list(trajectories.values())

    @staticmethod
    def _build_tank_segments(
        tank_id: str,
        segments: List[Segment],
        tank_to_upstream: Dict[str, List[Tuple[str, str]]],
        tank_to_downstream: Dict[str, List[Tuple[str, str, str]]],
        device_timelines: Dict[str, List[Tuple[float, float, int]]],
        total_days: float,
    ) -> List[dict]:
        """为单个罐构建独立时间网格。

        切分点：上下游装置加工边界 ∪ 停工边界 ∪ 航煤边界。
        罐的上游/下游装置各自有独立的加工时间轴（processing_days），
        与 CDU 批次边界不同步，因此每个罐需要独立的时间网格。

        Returns:
            [{'seg_id', 'start_day', 'end_day', 'days', 'batch_idx',
              'is_hangmei', 'shutdown_intervals', 'crude_type', 'mode', 'batch_id'}, ...]
        """
        # 收集该罐的上下游装置
        related_devs: set = set()
        for up_dev, _ in tank_to_upstream.get(tank_id, []):
            related_devs.add(up_dev)
        for _, ds_id, _ in tank_to_downstream.get(tank_id, []):
            related_devs.add(ds_id)

        # 收集所有边界点
        boundaries: set = {0.0, total_days}

        # 统一批次边界作为基础切分点（保证所有罐至少有批次级时间粒度）
        for seg in segments:
            boundaries.add(seg.start_day)
            boundaries.add(seg.end_day)

        # 上下游装置加工边界
        for dev_id in related_devs:
            for (s, e, _) in device_timelines.get(dev_id, []):
                boundaries.add(s)
                boundaries.add(e)

        # 停工边界（仅相关装置）
        for seg in segments:
            si = seg.shutdown_intervals or {}
            for dev_id in related_devs:
                if dev_id in si:
                    for (sh_s, sh_e) in si[dev_id]:
                        boundaries.add(sh_s / 24.0)
                        boundaries.add(sh_e / 24.0)

        # 航煤边界（is_hangmei 变化处）
        for i in range(len(segments) - 1):
            if segments[i].is_hangmei != segments[i + 1].is_hangmei:
                boundaries.add(segments[i].end_day)

        # 排序构建段
        sorted_bounds = sorted(boundaries)
        tank_segs: List[dict] = []
        seg_id = 0
        for i in range(len(sorted_bounds) - 1):
            s = sorted_bounds[i]
            e = sorted_bounds[i + 1]
            days = e - s
            if days < 1e-9:
                continue
            # 找到中点落在哪个统一 segment（继承 batch_idx/航煤/油种等信息）
            mid = (s + e) / 2
            batch_idx = 0
            is_hangmei = False
            crude_type = ''
            mode = MODE_JIAN1_TO_WAX
            batch_id = 0
            shutdown_intervals: dict = {}
            for seg in segments:
                if seg.start_day <= mid < seg.end_day:
                    batch_idx = seg.batch_idx
                    is_hangmei = seg.is_hangmei
                    crude_type = seg.crude_type
                    mode = seg.mode
                    batch_id = seg.batch_id
                    shutdown_intervals = seg.shutdown_intervals
                    break
            tank_segs.append({
                'seg_id': seg_id,
                'start_day': s,
                'end_day': e,
                'days': days,
                'batch_idx': batch_idx,
                'is_hangmei': is_hangmei,
                'crude_type': crude_type,
                'mode': mode,
                'batch_id': batch_id,
                'shutdown_intervals': shutdown_intervals,
            })
            seg_id += 1
        return tank_segs


# ── TankCapacityChecker ──────────────────────────────────────────────────

class TankCapacityChecker:
    """罐容约束段级检测器入口。

    集成 SegmentBuilder + TankSimulator，输出 tank_check_result。
    当前阶段：检测优先，不做可行性判定（不回写 combo_feasible）。
    """

    @staticmethod
    def check(batches: List[dict],
              calc_results: List[dict],
              intermediate_tank_ids: Set[str],
              devices: Dict[str, DeviceBase],
              material_flows: Dict[str, MaterialFlow],
              hangmei_enabled: bool = False,
              hangmei_best_start: float = 0.0,
              hangmei_m_days: float = 0.0,
              tank_initials: Optional[Dict[str, float]] = None,
              switches: dict = None,
              batch_details: Optional[List[dict]] = None,
              ) -> dict:
        """执行罐容段级检测。

        Args:
            batches: 批次列表（已含停工拆分）
            calc_results: 各批次 calc_result
            intermediate_tank_ids: 中间罐ID集合
            devices: 装置字典 {device_id: DeviceBase}
            material_flows: 物流字典 {flow_id: MaterialFlow}
            hangmei_enabled: 航煤工况是否启用
            hangmei_best_start: 航煤最优起始天数
            hangmei_m_days: 航煤持续天数
            tank_initials: 月初罐容 {tank_id: capacity}
            switches: {batch_id: mode} 阀门切换模式

        Returns:
            tank_check_result dict
        """
        # 1. 构建时间分段
        segments = SegmentBuilder.build(
            batches, hangmei_enabled, hangmei_best_start, hangmei_m_days, switches=switches)

        # 2. 逐罐逐段推演库存
        trajectories = TankSimulator.simulate(
            segments, batches, calc_results,
            intermediate_tank_ids, devices, material_flows,
            tank_initials, batch_details=batch_details)

        # 3. 汇总违规
        all_violations: List[dict] = []
        for traj in trajectories:
            all_violations.extend(v.to_dict() for v in traj.violations)

        # 4. 按严重程度排序违规（超出量降序）
        all_violations.sort(key=lambda v: v['severity'], reverse=True)

        return {
            'segments': [s.to_dict() for s in segments],
            'tank_trajectories': [t.to_dict() for t in trajectories],
            'violations': all_violations,
            'violation_count': len(all_violations),
            'has_violations': len(all_violations) > 0,
        }
