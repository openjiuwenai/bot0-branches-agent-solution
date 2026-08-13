# -*- coding: utf-8 -*-
"""减一线阀门切换子模块（从 planner.py 拆出）。

职责：基于已生成的日排产计划，识别加工批次并枚举阀门切换组合。
**只识别 + 枚举，不优化**——"在组合里挑最优"由 calculation.batch_optimizer 完成。
与排产计划生成相互独立，零耦合。

类内按职责分四节（以分节注释隔开），三块算法节两两零直接调用，
仅由编排节串联，共享 batch dict 这一隐式数据契约：
  ① 批次识别       identify_batches            日级明细 → 原油批次
  ② 装置停工拆分   apply_shutdown_windows      按停工边界（小时精度）拆 + 标 shutdown_intervals
  ③ 阀门组合枚举   generate_switch_combinations 2n 种切换位置（无过滤）
  ④ 编排入口       enumerate_valve_switching   串联 ①→②→③，对外唯一入口

停工语义（v2）：
  - 停工不再触发 X/Y 强制改道（forced_mode 已移除）
  - 停工只影响：装置在该时段产出按比例缩减 + 罐容累积更易触发上限
  - 停工装置进料罐照常接收 CDU 来料（出向按非停工比例缩减）
  - 组合数恒为 2^n（无 forced 过滤）
  - 时间精度从天提升到小时（ISO 时间戳 → 月内绝对小时索引）
"""
import logging
from datetime import datetime
from typing import Dict, List, Tuple, Optional

from ..models.scheduling import ProductionPlanDetail
from ..models.refinery import RefineryScenario
from ..config import MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL


def build_device_split_roles(scenario: RefineryScenario) -> Tuple[dict, dict]:
    """从 scenario.material_flows 推导装置的 XY 分流角色。

    逻辑：
      - 找 CDU → tank 的 source_to_tank 边中带 special_var 的罐
      - 通过 tank_to_target 边找到这些罐下游的加工装置
      - 装置的 special_var 角色即为其上游罐的 special_var

    Returns:
      (device_roles, device_names):
        device_roles: {device_id: 'jian1_to_diesel'/'jian1_to_wax'} — 参与分流的装置
        device_names: {device_id: device_name} — 装置中文名（用于日志）
    """
    cdu_id = scenario.start_device_id

    # 罐 → special_var 映射
    tank_special_vars: dict = {}  # tank_id → special_var ('jian1_to_diesel' or 'jian1_to_wax')
    for f in scenario.material_flows.values():
        if (f.flow_type == 'source_to_tank'
                and f.source_device_id == cdu_id
                and f.special_var):
            tank_special_vars[f.tank_id] = f.special_var

    # 装置 → 分流角色
    device_roles: dict = {}
    device_names: dict = {}
    for f in scenario.material_flows.values():
        if f.flow_type == 'tank_to_target':
            tank = f.tank_id
            dev = f.target_device_id
            sv = tank_special_vars.get(tank)
            if sv:
                device_roles[dev] = sv
            # 记录装置名称
            d = scenario.devices.get(dev)
            if d:
                device_names[dev] = d.name

    return device_roles, device_names


class ValveSwitchPlanner:
    """减一线阀门切换：批次识别 + 切换组合枚举。"""

    def __init__(self):
        self.logger = logging.getLogger('calc_service.scheduling.switch_planner')
        if not self.logger.handlers:
            handler = logging.StreamHandler()
            formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
            handler.setFormatter(formatter)
            self.logger.addHandler(handler)
            self.logger.setLevel(logging.INFO)

    # ── ① 批次识别：日级明细 → 原油批次 ───────────────────────────────────

    def identify_batches(self, details: List[ProductionPlanDetail]) -> List[Dict]:
        """识别批次：连续加工相同原油品种的加工段作为一个批次。

        主力原油判定：blend_detail 中配比 > 50% 的原油即为主力；若无过半原油则
        取加工量最大者兜底。当日全行总加工量（主力 + 非主力之和）整体计入该主力
        原油，与"非主力量并入主力"在数学上等价。主力原油相同且日期连续的归为
        同一批次。

        支持小数天数（hours 字段）。
        """
        day_info = {}
        for detail in details:
            day = detail.day_of_month
            hours = getattr(detail, 'hours', 24.0)
            crude_detail = detail.blend_detail
            if not crude_detail:
                continue

            total_amount = sum(v for v in crude_detail.values()
                               if isinstance(v, (int, float)) and v > 0)
            if total_amount < 0.001:
                continue

            # 主力原油：占比>50%，否则取最大
            main_crude = None
            main_amount = 0
            for crude_id, amount in crude_detail.items():
                if isinstance(amount, (int, float)) and amount > 0:
                    ratio = amount / total_amount if total_amount > 0 else 0
                    if ratio > 0.5:
                        main_crude = crude_id
                        main_amount = amount
                        break
                    if amount > main_amount:
                        main_amount = amount
                        main_crude = crude_id
            if main_crude is None:
                continue

            day_info.setdefault(day, {})
            if main_crude not in day_info[day]:
                day_info[day][main_crude] = {'total_amount': total_amount, 'hours': hours}
            else:
                day_info[day][main_crude]['total_amount'] += total_amount
                day_info[day][main_crude]['hours'] += hours

        if not day_info:
            return []

        # 转记录列表
        all_records = []
        for day in sorted(day_info.keys()):
            for main_crude, info in day_info[day].items():
                all_records.append({
                    'day': day, 'main_crude': main_crude,
                    'total_amount': info['total_amount'],
                    'actual_days': info['hours'] / 24.0, 'hours': info['hours']})

        # 按主力原油分组 + 合并连续日期
        crude_groups: Dict[str, list] = {}
        for record in all_records:
            crude_groups.setdefault(record['main_crude'], []).append(record)

        crude_batches = []
        for crude, records in crude_groups.items():
            records.sort(key=lambda x: x['day'])
            current_batch = None
            for record in records:
                day = record['day']
                total_amount = record['total_amount']
                actual_days = record['actual_days']
                if current_batch is None:
                    current_batch = {'start_day': day, 'end_day': day, 'crude_type': crude,
                                     'total_input': total_amount, 'daily_inputs': [total_amount],
                                     'days': actual_days}
                # 连续判定：day 紧接上一条记录的 end_day（day_info 已按天聚合，
                # 同一 (day, crude) 不会重复，故 day==end_day 实际不会命中，保留仅作防御）
                elif day == current_batch['end_day'] or day == current_batch['end_day'] + 1:
                    current_batch['end_day'] = day
                    current_batch['total_input'] += total_amount
                    current_batch['daily_inputs'].append(total_amount)
                    current_batch['days'] += actual_days
                else:
                    crude_batches.append(current_batch)
                    current_batch = {'start_day': day, 'end_day': day, 'crude_type': crude,
                                     'total_input': total_amount, 'daily_inputs': [total_amount],
                                     'days': actual_days}
            if current_batch is not None:
                crude_batches.append(current_batch)

        crude_batches.sort(key=lambda x: x['start_day'])
        for idx, batch in enumerate(crude_batches, 1):
            batch['batch_id'] = idx
        return crude_batches

    # ── ② 装置停工拆分：按停工边界（小时精度）拆 batch + 标 shutdown_intervals ──

    @staticmethod
    def _parse_shutdown_intervals(shutdown_config, plan_month: str = None) -> Dict[str, List[Tuple[float, float]]]:
        """解析停工配置（ISO时间）→ 月内绝对小时索引。

        月内绝对小时索引：当月1日0时 = 0，第N日M时 = (N-1)*24 + M。
        支持分钟精度：第3日8时30分 = 2*24 + 8.5 = 56.5。

        plan_month 给定时，以该月1日0时为基准用 datetime 差值计算小时数，
        正确处理跨月停工（如 start=1月15日、end=2月1日 → end_hour=31*24=744）。
        plan_month 缺省时回退到仅用 day 字段（不处理跨月，保留兼容）。

        Args:
          shutdown_config: [{unit, start_time, end_time}, ...]（ISO时间字符串）
          plan_month: "YYYY-MM" 格式，停工归属月份

        Returns:
          {device_id: [(start_hour, end_hour), ...]}
        """
        # 月初基准（plan_month 给定时）
        month_start: Optional[datetime] = None
        if plan_month:
            try:
                month_start = datetime.strptime(f"{plan_month}-01T00:00", "%Y-%m-%dT00:00")
            except ValueError:
                month_start = None

        intervals: Dict[str, List[Tuple[float, float]]] = {}
        for sc in (shutdown_config or []):
            unit = sc.get('unit')
            if not unit:
                continue
            try:
                st = datetime.fromisoformat(sc.get('start_time'))
                et = datetime.fromisoformat(sc.get('end_time'))
            except (TypeError, ValueError):
                continue
            if month_start is not None:
                # 用 datetime 差值计算，正确处理跨月
                start_hour = (st - month_start).total_seconds() / 3600.0
                end_hour = (et - month_start).total_seconds() / 3600.0
            else:
                # 回退：仅用 day 字段（不处理跨月）
                start_hour = (st.day - 1) * 24 + st.hour + st.minute / 60.0
                end_hour = (et.day - 1) * 24 + et.hour + et.minute / 60.0
            if end_hour <= start_hour:
                continue
            intervals.setdefault(unit, []).append((start_hour, end_hour))
        return intervals

    def apply_shutdown_windows(self, batches: List[Dict], shutdown_config,
                               device_roles: dict = None,
                               device_names: dict = None,
                               plan_month: str = None) -> Tuple[List[Dict], Dict]:
        """按停工时间边界拆分批次（小时精度），标记每段的 shutdown_intervals。

        停工语义（v2）：
          - 不再触发 X/Y 强制改道（forced_mode 已移除）
          - 停工只影响：装置在该时段产出按比例缩减 + 罐容累积更易触发上限
          - 停工装置进料罐照常接收 CDU 来料（出向按非停工比例缩减）
          - 同日多装置停工不冲突（各装置独立标记）
          - device_roles 参数保留但不再使用（仅为兼容旧调用）

        Args:
          batches: identify_batches 产出的批次列表
          shutdown_config: [{unit, start_time, end_time}, ...]（ISO时间字符串）
          device_roles: 保留兼容，不再使用
          device_names: {device_id: name}，用于日志和窗口信息展示
          plan_month: "YYYY-MM"，停工归属月份，用于正确计算月内绝对小时索引（支持跨月）

        Returns:
          (new_batches, shutdown_info):
            new_batches: 拆分后的批次（带 shutdown_intervals 字段，无 forced_mode）
            shutdown_info: {enabled, windows, conflicts(空列表)}
        """
        device_names = device_names or {}
        shutdown_intervals_all = self._parse_shutdown_intervals(shutdown_config, plan_month)

        # 构建 windows 供前端展示（与 _parse_shutdown_intervals 同口径）
        month_start: Optional[datetime] = None
        if plan_month:
            try:
                month_start = datetime.strptime(f"{plan_month}-01T00:00", "%Y-%m-%dT00:00")
            except ValueError:
                month_start = None

        windows: List[Dict] = []
        for sc in (shutdown_config or []):
            unit = sc.get('unit')
            uname = device_names.get(unit, unit)
            try:
                st = datetime.fromisoformat(sc.get('start_time'))
                et = datetime.fromisoformat(sc.get('end_time'))
                if month_start is not None:
                    start_hour = (st - month_start).total_seconds() / 3600.0
                    end_hour = (et - month_start).total_seconds() / 3600.0
                else:
                    start_hour = (st.day - 1) * 24 + st.hour + st.minute / 60.0
                    end_hour = (et.day - 1) * 24 + et.hour + et.minute / 60.0
            except (TypeError, ValueError):
                continue
            windows.append({
                'unit': unit, 'unit_name': uname,
                'start_time': sc.get('start_time'), 'end_time': sc.get('end_time'),
                'start_hour': round(start_hour, 2), 'end_hour': round(end_hour, 2),
            })

        shutdown_info = {'enabled': bool(shutdown_config), 'windows': windows, 'conflicts': []}

        # 无停工：原样返回，shutdown_intervals 为空
        if not shutdown_intervals_all:
            for b in batches:
                b['shutdown_intervals'] = {}
            return batches, shutdown_info

        # 按停工边界拆分批次
        new_batches: List[Dict] = []
        next_id = 1
        for batch in batches:
            sd, ed = int(batch['start_day']), int(batch['end_day'])
            batch_start_hour = (sd - 1) * 24
            batch_end_hour = ed * 24  # 当日结束（不含）
            batch_calendar_hours = batch_end_hour - batch_start_hour

            if batch_calendar_hours <= 0:
                batch['shutdown_intervals'] = {}
                new_batches.append(batch)
                next_id += 1
                continue

            # 收集所有落在该批次范围内的停工边界点
            boundaries = {batch_start_hour, batch_end_hour}
            for dev_id, intervals in shutdown_intervals_all.items():
                for (s, e) in intervals:
                    s_c = max(s, batch_start_hour)
                    e_c = min(e, batch_end_hour)
                    if e_c > s_c:
                        boundaries.add(s_c)
                        boundaries.add(e_c)

            # 按边界点排序，生成子段
            sorted_boundaries = sorted(boundaries)
            for i in range(len(sorted_boundaries) - 1):
                seg_start = sorted_boundaries[i]
                seg_end = sorted_boundaries[i + 1]
                if seg_end <= seg_start:
                    continue

                # 该子段内停工的装置及其重叠区间
                seg_shutdown: Dict[str, List[Tuple[float, float]]] = {}
                for dev_id, intervals in shutdown_intervals_all.items():
                    overlaps = []
                    for (s, e) in intervals:
                        overlap_s = max(s, seg_start)
                        overlap_e = min(e, seg_end)
                        if overlap_e > overlap_s:
                            overlaps.append((overlap_s, overlap_e))
                    if overlaps:
                        seg_shutdown[dev_id] = overlaps

                new_batches.append(self._build_sub_batch(
                    next_id, batch, seg_start, seg_end,
                    batch_start_hour, batch_calendar_hours, seg_shutdown))
                next_id += 1

        return new_batches, shutdown_info

    @staticmethod
    def _build_sub_batch(batch_id: int, batch: Dict,
                         seg_start_hour: float, seg_end_hour: float,
                         batch_start_hour: float, batch_calendar_hours: float,
                         shutdown_intervals: Dict[str, list]) -> Dict:
        """按小时占比从父批次切出子批次。

        物料/天数按子段小时占比分配；shutdown_intervals 仅含该子段内活跃的停工。
        """
        seg_hours = seg_end_hour - seg_start_hour
        share = seg_hours / batch_calendar_hours if batch_calendar_hours > 0 else 0
        sub_total = batch['total_input'] * share
        batch_days = float(batch.get('days', len(batch.get('daily_inputs', []))))
        sub_days = batch_days * share

        # 子段的日历日范围（用于展示）
        seg_start_day = int(seg_start_hour // 24) + 1
        if seg_end_hour % 24 != 0:
            seg_end_day = int(seg_end_hour // 24) + 1
        else:
            seg_end_day = int(seg_end_hour // 24)
        seg_end_day = max(seg_end_day, seg_start_day)
        sub_calendar = seg_end_day - seg_start_day + 1

        return {
            'batch_id': batch_id,
            'crude_type': batch['crude_type'],
            'start_day': seg_start_day,
            'end_day': seg_end_day,
            'total_input': sub_total,
            'daily_inputs': [sub_total / sub_calendar] * sub_calendar if sub_calendar > 0 else [sub_total],
            'days': sub_days,
            'shutdown_intervals': shutdown_intervals,
        }

    # ── ③ 阀门组合枚举：2n 种切换位置（无过滤） ────────────────────────────

    def generate_switch_combinations(self, batches: List[Dict]) -> List[Dict]:
        """生成所有可能的阀门切换位置组合（2^n 种，无过滤）。

        规则：一个月只能切换一次阀门。对于 n 个批次有 n 种切换位置（0~n-1），
        每种位置 × 2 种初始模式（MODE_JIAN1_TO_WAX/MODE_JIAN1_TO_DIESEL），共 2n 种组合。
        停工不再过滤组合（forced_mode 已移除），组合数恒为 2n。
        """
        if not batches:
            return []
        n = len(batches)
        combinations = []
        for switch_pos in range(n):
            for initial_mode in [MODE_JIAN1_TO_WAX, MODE_JIAN1_TO_DIESEL]:
                switches = {}
                desc_parts = []
                for i, batch in enumerate(batches):
                    batch_id = batch['batch_id']
                    # switch_pos=0 表示全程不切换，统一用 initial_mode。
                    # 该特判不可省：否则 i < 0 恒假，所有批次会误走 else（反向分支）。
                    # switch_pos>0 时，第 [0, switch_pos) 批用 initial_mode，
                    # 第 [switch_pos, n) 批切换为反向 mode。
                    if switch_pos == 0 or i < switch_pos:
                        mode = initial_mode
                    else:
                        mode = MODE_JIAN1_TO_DIESEL if initial_mode == MODE_JIAN1_TO_WAX else MODE_JIAN1_TO_WAX
                    switches[batch_id] = mode
                    desc_parts.append(f"批次{batch_id}({batch['crude_type']}):{mode}")

                if switch_pos == 0:
                    switch_desc = f"不切换（全部{initial_mode}）"
                else:
                    other = MODE_JIAN1_TO_DIESEL if initial_mode == MODE_JIAN1_TO_WAX else MODE_JIAN1_TO_WAX
                    switch_desc = (f"切换位置: 第{switch_pos}批次开始"
                                   f"（前{switch_pos}批{initial_mode}，后{n - switch_pos}批{other}）")

                combinations.append({
                    'combination_id': len(combinations) + 1,
                    'switches': switches,
                    'description': '; '.join(desc_parts),
                    'switch_position': switch_pos,
                    'initial_mode': initial_mode,
                    'switch_desc': switch_desc,
                })
        return combinations

    # ── ④ 编排入口：串联 ①→②→③，对外唯一调用点 ──────────────────────────

    def enumerate_valve_switching(self, details: List[ProductionPlanDetail],
                                  shutdown_config=None,
                                  device_roles: dict = None,
                                  device_names: dict = None,
                                  plan_month: str = None) -> Dict:
        """识别批次并生成阀门切换组合（不在此处计算效益，效益由 batch_optimizer 算）。

        停工语义（v2）：不触发 X/Y 改道，只标记 shutdown_intervals。
        device_roles 参数保留但不再使用（仅为兼容旧调用）。

        Args:
          details: 已落盘排产明细
          shutdown_config: 装置停工声明 [{unit, start_time, end_time}, ...]（ISO时间字符串）
          device_roles: 保留兼容，不再使用
          device_names: {device_id: name}，用于日志
          plan_month: "YYYY-MM"，停工归属月份，用于正确计算月内绝对小时索引（支持跨月）

        Returns:
          dict 含 success/batches/combinations/total_combinations/shutdown/message
        """
        try:
            batches = self.identify_batches(details)
            if not batches:
                return {'success': False, 'message': '未找到有效的批次信息'}

            # 停工窗口：按边界（小时精度）拆分批次 + 标 shutdown_intervals
            batches, shutdown_info = self.apply_shutdown_windows(
                batches, shutdown_config, device_roles, device_names, plan_month)

            self.logger.info(f"识别到 {len(batches)} 个批次:")
            for batch in batches:
                si = batch.get('shutdown_intervals', {})
                tag = f"，停工装置: {','.join(si.keys())}" if si else ""
                self.logger.info(
                    f"  批次{batch['batch_id']}: {batch['crude_type']}, "
                    f"第{batch['start_day']}-{batch['end_day']}天, "
                    f"总加工量: {batch['total_input']:.2f}吨{tag}")

            combinations = self.generate_switch_combinations(batches)
            if shutdown_config:
                self.logger.info(f"生成 {len(combinations)} 种阀门切换组合（含停工拆分）")
            else:
                self.logger.info(f"生成 {len(combinations)} 种阀门切换组合")
            for combo in combinations:
                self.logger.info(f"  组合{combo['combination_id']}: {combo['description']}")

            return {
                'success': True,
                'batches': batches,
                'combinations': combinations,
                'total_combinations': len(combinations),
                'shutdown': shutdown_info,
                'message': f'找到{len(batches)}个批次，共{len(combinations)}种切换组合',
            }
        except Exception as e:
            self.logger.error(f"优化阀门切换失败: {e}", exc_info=True)
            return {'success': False, 'message': f'优化失败: {str(e)}'}
