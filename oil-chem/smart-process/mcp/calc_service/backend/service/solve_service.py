# -*- coding: utf-8 -*-
"""求解编排服务层。

承接原 api/plan_routes.py 中的业务编排与 helper（_load_scenario_fn /
_extract_crude_costs / _build_economic_explanation / _update_device_load_rate），
让 api 路由回归"参数解析 + 响应封装"。依赖方向：
  service → {calculation, scheduling, data} → models

不直接持有 Flask 对象；返回普通 dict（未 clean），由路由层 jsonify。
"""
import copy
from dataclasses import dataclass
from typing import Optional, Dict

from ..logger import get_logger
from ..data.refinery_repo import RefineryRepository
from ..data.scheduling_repo import SchedulingRepository
from ..scheduling.switch_planner import ValveSwitchPlanner
from ..calculation.batch_optimizer import (
    optimize_combinations, build_hangmei_context, evaluate_combination,
)
from ..calculation.economics import classify_cdu_products
from ..models.results import CombinationOutput
from ..calculation.batch_builder import build_batch_details_list, build_monthly_load, merge_intervals
from ..calculation.switch_analysis import build_jian1_switch_analysis
from ..calculation.economic_reporter import (
    aggregate_economics, build_economic_explanation, build_economic_breakdown,
)
from ..calculation.flow_diagram_builder import (
    build_flow_diagram, build_device_input_sources,
)
from ..rules import (
    assess_feasibility, select_optimal,
    DEFAULT_FEASIBILITY_RULES, DEFAULT_SELECTION_STRATEGY,
)


# ── 编排阶段对象 ──────────────────────────────────────────────────────────
# 三个业务流程各自产出一个阶段对象，替代原先步骤间的 dict['key'] 解包，
# 让编排入口读起来就是「① 排产 → ② 批次+组合 → ③ 效益选优」三步。

@dataclass
class PlanStage:
    """流程①产物：排产计划明细 + 自定义原油成本。"""
    details: list
    custom_crude_costs: dict


@dataclass
class SwitchStage:
    """流程②产物：批次划分 + 阀门切换组合。"""
    batches: list
    combinations: list


@dataclass
class SolutionStage:
    """流程③产物：逐组合评估结果 + 最优组合。"""
    combination_results: list
    optimal_combination: Optional[dict]   # 原 optimal_details；None 表示无可行解
    optimal_revenue: float
    optimal_explanations: list
    optimal_calc_results: list
    optimal_hangmei_summary: dict = None  # 航煤工况摘要（None=未启用航煤）
    pass1_results: dict = None            # R2优化：PASS 1 的 CombinationResult 对象，供 PASS 4 复用物理计算


def _prev_month(m: str) -> str:
    """'YYYY-MM' → 上个月（处理1月跨年）。用于选方案阶段取上月价格/成本。"""
    y, mo = int(m[:4]), int(m[5:7])
    pm = 12 if mo == 1 else mo - 1
    py = y - 1 if mo == 1 else y
    return f"{py:04d}-{pm:02d}"


class _SolveAbort(Exception):
    """编排流程提前终止，携带面向用户的 message。

    可选 payload：停工冲突等场景需把 shutdown 摘要（含 conflicts）回传前端，
    仅 _enumerate_switches 失败时设置，其它中止点不用。
    """

    def __init__(self, message: str, shutdown: dict = None):
        super().__init__(message)
        self.shutdown = shutdown


# 精简模式下各组合保留的字段（仅剔除 batch_details 大嵌套对象）
# 保留 batch_results（批次时间线/减一线分流）+ monthly_load（月度负荷面板/对比表）
_SLIM_COMBO_KEEP = {
    'combination_id', 'description', 'switch_position', 'initial_mode',
    'switches', 'total_revenue', 'feasible', 'near_feasible',
    'infeasible_summary', 'bottleneck', 'hangmei_summary', 'tank_check_result',
    'batch_results', 'monthly_load',
}

# 摘要模式下组合只保留极简字段（供 Agent / MCP 直接读取，不截断）
_SUMMARY_COMBO_KEEP = {
    'combination_id', 'description', 'total_revenue',
    'feasible', 'near_feasible', 'infeasible_summary',
}


def _slim_combo_results(rows: list) -> list:
    """精简组合列表：剔除 batch_details（含 device_inputs/economic_analysis/feed_details 等大嵌套对象）。

    保留 batch_results / monthly_load / tank_check_result 供前端批次时间线、月度负荷面板、
    组合对比表正常渲染。
    """
    return [{k: v for k, v in r.items() if k in _SLIM_COMBO_KEEP} for r in rows]


def _summary_combo_results(rows: list) -> list:
    """摘要组合列表：只保留 id/收益/可行性/描述，供 Agent / MCP 直接读取。

    相比 _slim_combo_results 进一步剔除 batch_results / monthly_load / tank_check_result
    等前端渲染专用字段，将每个组合压缩到一行摘要。
    """
    return [{k: v for k, v in r.items() if k in _SUMMARY_COMBO_KEEP} for r in rows]


class SolveService:
    """排产计划生成与综合求解编排。"""

    def __init__(self, logger=None):
        self.logger = logger or get_logger()

    # ── 仓库工厂 ──────────────────────────────────────────────────────────

    def _sched_repo(self) -> SchedulingRepository:
        return SchedulingRepository()

    def _refinery_repo(self) -> RefineryRepository:
        return RefineryRepository()

    def _load_tank_initials(self, year_month: str) -> dict:
        """加载指定月份的中间罐月初容量，返回 {tank_id: initial_capacity}。"""
        try:
            rows = self._refinery_repo().load_tank_monthly_initial(year_month)
            return {r['tank_id']: r['initial_capacity'] for r in rows}
        except Exception:
            return {}

    def _load_scenario_fn(self, crude_type: str = None,
                          custom_crude_costs: dict = None):
        """加载炼化场景。custom_crude_costs 非空时覆盖场景原油成本。

        等价原 solve/web_app.py 的 load_scenario(crude_type, custom_crude_costs)。
        """
        repo = self._refinery_repo()
        scenario = repo.load_scenario(
            start_device_id=None, crude_type=crude_type)
        if custom_crude_costs:
            scenario.crude_costs = custom_crude_costs
            self.logger.info(f"使用自定义原油成本数据: {len(scenario.crude_costs)} 种原油")
        return scenario

    def _preload_reference_data(self, batches, scenarios, plan_month,
                               custom_crude_costs=None):
        """统一预加载引用数据（价格 + 成本 + 配比）。

        由 Service 层在调用计算层前一次性预加载，产出三个 flat dict 传给
        optimize_combinations / evaluate_combination / recompute_combination_economics
        等函数的 prices / device_costs / feed_ratios 参数。

        Args:
            batches: 批次列表
            scenarios: 场景字典（按原油品种），函数内会按需填充
            plan_month: 计划月份（None 时返回空 dict，兼容 optimize_valve 无价格月场景）
            custom_crude_costs: 自定义原油成本（传给 _load_scenario_fn）
        Returns:
            (prices, device_costs, feed_ratios)
            prices: {product_id: price}  flat dict
            device_costs: {backend_device_id: unit_cost}
            feed_ratios: {crude_type: {proc_id: ratio}}
        """
        from ..data.refinery_repo import RefineryRepository
        from data_service.repositories import price_repo
        from ..data.db import SessionLocal

        repo = RefineryRepository()

        # 1. 确保所有批次对应的场景已加载
        for batch in batches:
            crude_type = batch.get('crude_type')
            if crude_type not in scenarios:
                scenarios[crude_type] = self._load_scenario_fn(
                    crude_type=crude_type,
                    custom_crude_costs=custom_crude_costs)

        # 预加载价格：preload_prices 返回 product_id → price 的 flat dict
        prices = {}
        if plan_month:
            for crude_type, scenario in scenarios.items():
                scenario_prices = repo.preload_prices(
                    plan_month, scenario.products)
                prices.update(scenario_prices)

        # 2. 装置成本
        device_costs = {}
        if plan_month:
            try:
                with SessionLocal() as db:
                    device_costs = price_repo.load_device_costs(db, plan_month)
            except Exception as e:
                self.logger.warning(f"加载装置成本失败: {e}")

        # 3. 进料配比
        feed_ratios = {}
        for crude_type, scenario in scenarios.items():
            ratios = {}
            for proc_id in scenario.processing_device_ids:
                ratios[proc_id] = scenario.get_feed_ratio(proc_id, crude_type)
            feed_ratios[crude_type] = ratios

        return prices, device_costs, feed_ratios

    # ── 业务 helper ────────────────────────────────────────────────────────

    @staticmethod
    def _extract_crude_costs(plans_input: list) -> dict:
        """从 production_plans_input 提取 {crude_type_id: cost}。"""
        costs = {}
        for item in plans_input:
            # 兼容 dict 与 ProductionPlansInput 对象
            crude_id = item.get('crude_type_id') if isinstance(item, dict) else item.crude_type_id
            cost = item.get('cost') if isinstance(item, dict) else item.cost
            if crude_id and cost and cost > 0:
                costs[crude_id] = cost
        return costs

    @staticmethod
    def _build_batch_details(combination_results, optimal_combination,
                             optimal_calc_results, optimal_explanations,
                             tank_initials: dict = None) -> list:
        """把最优组合各批次的 calc_result + explanation 按 batch 透传给前端，
        供「效益预测」页渲染装置级计算过程（投入→负荷→减一线→柴加/蜡加→收率→效益）。

        响应里的 optimal_combination 是瘦 dict（仅 combination_id/description/switches，
        无 batch_results），需在 combination_results 里按 id 取回完整组合拿 batch_results。
        batch_results / explanations / calc_results 同源于 evaluate_combination 同一循环
        （append 顺序一致），按索引对齐；带 len 守卫防错位。返回 [] 当无可行解或数据缺失。
        """
        if not optimal_combination or not optimal_calc_results or not optimal_explanations:
            return []
        combo_id = optimal_combination.get('combination_id')
        full_combo = next((c for c in combination_results
                           if c.combination_id == combo_id), None)
        if not full_combo:
            return []
        return build_batch_details_list(
            full_combo.batch_results, optimal_calc_results, optimal_explanations, tank_initials)

    @staticmethod
    def _finalize_combination_outputs(pass1_results, combination_results,
                                       tank_initials: dict = None) -> None:
        """从 pass1_results 填充 CombinationOutput 的 batch_details 和 monthly_load。

        重构后：CombinationOutput 在 batch_optimizer 中已从 CombinationResult 携带
        batch_details/monthly_load（含 processing_days），本函数仅在缺失时 fallback 重建。
        不再有下划线临时键的 pop 操作。
        """
        for output in combination_results:
            if output.batch_details:
                continue  # 已从 evaluate_combination 携带
            # Fallback：从 pass1_results 重建（兼容旧路径）
            pr = pass1_results.get(output.combination_id) if pass1_results else None
            calc_results = pr.calc_results if pr else []
            explanations = pr.explanations if pr else []
            output.batch_details = build_batch_details_list(
                output.batch_results, calc_results, explanations, tank_initials)
            output.monthly_load = build_monthly_load(output.batch_details)
            SolveService._annotate_device_processing_days(
                output.batch_details, output.monthly_load)

    @staticmethod
    def _reassess_feasibility(combination_results, rules=None) -> None:
        """按月度口径重定可行性，委托参数化规则引擎。

        默认规则等价于原硬编码行为：
          罐容违规 → 不可行；负荷超容但罐容满足 → 接近可行；否则 → 可行。
        传入 rules dict 可覆盖阈值，供 Agent 定制。

        Args:
            combination_results: CombinationOutput 列表（原地修改 feasible 等字段）。
            rules: 规则参数 dict，传 None 使用默认规则。
        """
        assess_feasibility(combination_results, rules)

    @staticmethod
    def _select_optimal(pass1_results, combination_results,
                        strategy=None) -> Optional[dict]:
        """在月度可行组合中重选最优，委托参数化选优引擎。

        默认策略等价于原硬编码行为：
          选优优先级：可行 > 接近可行 > 不可行。
          可行/接近可行内部按 total_revenue 最高选取。
          无可行且无接近可行时，按超容惩罚乘积选最轻组合。
        传入 strategy dict 可覆盖选优模式（多目标/风险规避等）。

        Returns:
            {combination_id, switches, description, total_revenue,
             calc_results, explanations, hangmei_summary, [near_feasible]}
            或 None（无组合数据时）。
        """
        result = select_optimal(combination_results, strategy, pass1_results)
        if result is None:
            return None
        out = {
            'combination_id': result.combination_id,
            'switches': result.details.get('switches', {}),
            'description': result.description,
            'total_revenue': result.total_revenue,
            'calc_results': result.details.get('calc_results', []),
            'explanations': result.details.get('explanations', []),
            'hangmei_summary': result.details.get('hangmei_summary', {}),
        }
        if result.is_temporary or result.near_feasible:
            out['near_feasible'] = True
        return out

    @staticmethod
    def _annotate_device_processing_days(batch_details, monthly_load) -> None:
        """超容重算 processing_days / daily_consumption。

        对月度超容装置，按满负荷能力（safety_stock_thrd × load_percent）重算加工天数，
        使 Σ(processing_days) 反映装置实际可加工时长（而非全月天数）。
        """
        if not batch_details or not monthly_load:
            return
        ml_devices = monthly_load.get('devices') or []
        overloaded_dids = {d['device_id'] for d in ml_devices if d.get('is_overloaded')}
        for bd in batch_details:
            du = bd.get('device_utilization') or {}
            feed_map = {}
            for fd in (bd.get('feed_details') or []):
                feed_map[fd.get('device_id')] = fd
            for did, u in du.items():
                if not u or u.get('type') in ('tank', 'start'):
                    continue
                if did not in overloaded_dids:
                    continue
                fd = feed_map.get(did)
                if not fd:
                    continue
                main_items = [i for i in (fd.get('items') or []) if i.get('label') == '主料']
                if not main_items:
                    continue
                total_main_feed = sum(float(i.get('feed_qty', 0)) for i in main_items)
                if total_main_feed <= 0:
                    continue
                cap = float(u.get('safety_stock_thrd', 0) or 0)
                load_pct = float(u.get('refinery_unit_load_percent', 100) or 100)
                if cap > 0:
                    processable = cap * (load_pct / 100.0)
                    u['daily_consumption'] = round(processable, 1)
                    u['processing_days'] = round(total_main_feed / processable, 2) if processable > 0 else 0

    def _update_device_load_rate(self, details, batches, optimal_calc_results, repo):
        """把最优组合的起始装置(cjy_01)负荷率写回 plan details 并落盘。

        迁移自 comprehensive_solve L3729-3751。
        """
        if not optimal_calc_results or not details:
            return
        try:
            batch_day_map = {}
            for batch_idx, batch in enumerate(batches):
                for day in range(batch['start_day'], batch['end_day'] + 1):
                    batch_day_map[day] = batch_idx

            for detail in details:
                day = detail.day_of_month
                if day in batch_day_map:
                    calc_result = optimal_calc_results[batch_day_map[day]]
                    device_util = calc_result.get('device_utilization', {})
                    # 查找 CDU 装置利用率
                    cdu_id = next((did for did, du in device_util.items()
                                   if du.get('type') == 'start'), None)
                    if cdu_id:
                        detail.device_load_rate = device_util[cdu_id].get('utilization', 0)

            repo.save_production_plan_details(details)
            self.logger.info(f"已更新 {len(details)} 条计划详情的device_load_rate")
        except Exception as e:
            self.logger.warning(f"更新device_load_rate失败: {e}")

    # ── 编排步骤（对应业务三流程）──────────────────────────────────────────
    #
    # ① 排产       _produce_plan / _load_existing_plan  → PlanStage
    # ② 批次+组合   _enumerate_switches                  → SwitchStage
    # ③ 效益选优    _evaluate_and_pick                   → SolutionStage
    #
    # 早退统一以 _SolveAbort 抛出，由编排入口捕获转为 {'success': False, 'message'}。

    def _load_existing_plan(self, repo: SchedulingRepository, plan_id: str,
                            plan_source: str = 'lp') -> Optional[PlanStage]:
        """加载已落盘计划，返回 PlanStage；计划不存在返回 None（交由调用方决定语义）。

        plan_source: 'lp' 读 production_plan_details（客户实际排产），
                     'cp_sat' 读 cp_sat_plan_details（CP-SAT 排产结果）。
        """
        if plan_source == 'cp_sat':
            details = repo.load_cp_sat_plan_details(plan_id)
        else:
            details = repo.load_production_plan_details(plan_id)
        if not details:
            return None
        custom_crude_costs = self._extract_crude_costs(repo.load_production_plans_input())
        return PlanStage(details=details, custom_crude_costs=custom_crude_costs)

    def _produce_plan(self, repo, plan_id, plan_month, production_plans_input,
                      monthly_crude_input, blend_mode, save_data,
                      plan_source: str = 'lp') -> PlanStage:
        """流程①：复用已有计划，否则生成新计划。失败抛 _SolveAbort。

        plan_source='cp_sat' 时从 cp_sat_plan_details 读取 CP-SAT 排产结果，
        不走 LP 生成路径（production_plans_input 可为空）。
        """
        existing = self._load_existing_plan(repo, plan_id, plan_source)
        if existing is not None:
            self.logger.info(f"已有计划详情 {len(existing.details)} 条 (source={plan_source})，直接进行求解")
            return existing

        # CP-SAT 模式下没有已落盘结果，且不生成新 LP 计划
        if plan_source == 'cp_sat':
            raise _SolveAbort('CP-SAT 排产结果不存在，请先在排产求解页运行 CP-SAT 排产')

        # LP 排产已删除，统一走 CP-SAT。如需生成新计划，请先在排产求解页运行。
        raise _SolveAbort('排产计划不存在，请先在排产求解页运行 CP-SAT 排产后再进行效益预测')

    def _enumerate_switches(self, details, shutdown_config=None, plan_month: str = None) -> SwitchStage:
        """流程②：识别批次 + 枚举阀门切换组合（只识别+枚举，不评估）。失败抛 _SolveAbort。

        shutdown_config：装置停工声明 [{unit, start_time, end_time}, ...]，透传给
        ValveSwitchPlanner 按停工边界拆分批次。plan_month 用于正确计算月内绝对小时
        索引（支持跨月停工，如 end_time 落到下月）。
        """
        from ..scheduling.switch_planner import build_device_split_roles
        # 加载场景获取装置分流角色（数据驱动，替代硬编码 DEVICE_LYJQ/DEVICE_CYJQ）
        try:
            scenario = self._load_scenario_fn()
            device_roles, device_names = build_device_split_roles(scenario)
        except Exception as e:
            self.logger.warning(f"加载装置分流角色失败，停工配置将被跳过: {e}")
            device_roles, device_names = {}, {}

        valve_result = ValveSwitchPlanner().enumerate_valve_switching(
            details, shutdown_config=shutdown_config,
            device_roles=device_roles, device_names=device_names,
            plan_month=plan_month)
        if not valve_result.get('success'):
            raise _SolveAbort(valve_result.get('message', '优化失败'),
                              shutdown=valve_result.get('shutdown'))
        batches = valve_result['batches']
        combinations = valve_result['combinations']
        self.logger.info(f"识别到 {len(batches)} 个批次，生成 {len(combinations)} 种组合")
        return SwitchStage(batches=batches, combinations=combinations)

    def _evaluate_and_pick(self, batches, combinations, custom_crude_costs,
                           hangmei_ctx, select_month, final_month,
                           prices: Optional[Dict[str, float]] = None,
                           device_costs: Optional[Dict[int, float]] = None,
                           feed_ratios: Optional[Dict[str, Dict[str, float]]] = None,
                           scenarios: Dict = None) -> SolutionStage:
        """流程③：逐组合评估效益并选取最优（hangmei_ctx=None 表示非航煤工况）。

        双价格月口径（与单一 plan_month 的原行为不同）：
          - select_month：选优阶段（summary_only=True 遍历所有组合挑最优）取价月份，
            通常为 plan_month 的上个月——用上月价格/成本挑减一线切换组合。
          - final_month：最终核算阶段（对选中组合 summary_only=False 全量重算）取价月份，
            通常为 plan_month 本月——选中方案按本月价格/成本核实实际效益。
        两者通过 scenarios 共存（prices/device_costs 按月分桶，不串扰）。
        """
        opt = optimize_combinations(
            batches, combinations, custom_crude_costs, scenarios,
            hangmei_ctx=hangmei_ctx, select_month=select_month,
            final_month=final_month, logger=self.logger,
            prices=prices, device_costs=device_costs, feed_ratios=feed_ratios,
            return_pass1=True)
        return SolutionStage(
            combination_results=opt['combination_results'],
            optimal_combination=opt['optimal_details'],
            optimal_revenue=opt['optimal_revenue'],
            optimal_explanations=opt['optimal_explanations'],
            optimal_calc_results=opt['optimal_calc_results'],
            optimal_hangmei_summary=opt.get('optimal_hangmei_summary'),
            pass1_results=opt.get('pass1_results'),
        )

    def _eval_all_combos_final(self, batches, combinations, custom_crude_costs,
                                hangmei_target, scenarios, final_month,
                                hangmei_ctx=None,
                                pass1_results: dict = None,
                                prev_combination_results: list = None,
                                precomputed_optimal: dict = None,
                                prices: Optional[Dict[str, float]] = None,
                                device_costs: Optional[Dict[int, float]] = None,
                                feed_ratios: Optional[Dict[str, Dict[str, float]]] = None) -> dict:
        """用本月价(final_month)对全部组合做 summary_only 批量评估，供前端"本月价组合对比表"。

        R2+R3优化：复用 PASS 1 的物理计算结果（calc_results/罐容检测/航煤搜索），
        只重跑 generate_explanation（价格相关）+ 月度能力折减 + 航煤产出修正。
        monthly_load/feasible 与价格无关，直接从 PASS 1 结果复用。
        R1优化：最优组合已由 PASS 3 完成 generate_explanation 全量重算，直接复用。

        Args:
            pass1_results: PASS 1 的 {combination_id: CombinationResult} 映射
            prev_combination_results: PASS 1 enrich 后的 combination_results（含 monthly_load/feasible/batch_details）
            precomputed_optimal: PASS 3 已算好的最优组合结果（含 calc_results/explanations/total_revenue）

        Returns: {'combination_results_final': [...], 'final_optimal_combo_id': int|None}
        """
        from ..calculation.batch_optimizer import (
            evaluate_combination, recompute_combination_economics)
        if not combinations or not final_month:
            return {'combination_results_final': [], 'final_optimal_combo_id': None}

        self.logger.info(f"========== 本月价全组合批量评估: {final_month} ==========")

        # 构建 PASS 1 结果索引（按 combination_id），用于复用 monthly_load/feasible
        prev_by_id: dict = {}
        if prev_combination_results:
            for cr in prev_combination_results:
                prev_by_id[cr.combination_id] = cr

        combo_rows = []
        econ_cache = {}  # P0经济缓存：跨组合共享 explanation 结果
        for combo in combinations:
            combo_id = combo['combination_id']
            prev_cr = prev_by_id.get(combo_id)

            # R1优化：最优组合直接复用 PASS 3 的 generate_explanation 全量结果
            if precomputed_optimal and combo_id == precomputed_optimal['combination_id']:
                # 从 PASS 1 的 CombinationResult 取物理/结构信息
                p1 = pass1_results.get(combo_id) if pass1_results else None
                if p1:
                    from ..calculation.batch_optimizer import CombinationResult
                    result = CombinationResult(
                        combination_id=combo_id,
                        description=p1.description,
                        switch_position=p1.switch_position,
                        initial_mode=p1.initial_mode,
                        switches=p1.switches,
                        total_revenue=precomputed_optimal['total_revenue'],
                        total_cost=0.0,
                        batch_results=p1.batch_results,
                        explanations=precomputed_optimal['explanations'],
                        calc_results=precomputed_optimal['calc_results'],
                        hangmei_summary=precomputed_optimal.get('hangmei_summary') or p1.hangmei_summary,
                        feasible=p1.feasible,
                        bottleneck_devices=p1.bottleneck_devices,
                        infeasible_summary=p1.infeasible_summary,
                        tank_check_result=precomputed_optimal.get('tank_check_result') or p1.tank_check_result,
                        batch_details=precomputed_optimal.get('batch_details') or p1.batch_details,
                        monthly_load=p1.monthly_load,
                    )
                else:
                    # 降级：无 PASS 1 缓存
                    result = evaluate_combination(
                        combo, batches, scenarios, custom_crude_costs,
                        hangmei_ctx=hangmei_ctx,
                        plan_month=final_month, logger=self.logger, summary_only=False,
                        prices=prices, device_costs=device_costs, feed_ratios=feed_ratios)
            elif pass1_results and combo_id in pass1_results:
                # R2优化：复用 PASS 1 物理计算，仅重跑经济计算
                result = recompute_combination_economics(
                    pass1_results[combo_id], batches, scenarios,
                    final_month, hangmei_ctx, self.logger,
                    custom_crude_costs=custom_crude_costs,
                    prices=prices, device_costs=device_costs, feed_ratios=feed_ratios,
                    _econ_cache=econ_cache)
            else:
                # 降级：无 PASS 1 缓存时走完整 evaluate_combination
                result = evaluate_combination(
                    combo, batches, scenarios, custom_crude_costs,
                    hangmei_ctx=hangmei_ctx,
                    plan_month=final_month, logger=self.logger, summary_only=True,
                    prices=prices, device_costs=device_costs, feed_ratios=feed_ratios)

            combo_row = CombinationOutput(
                combination_id=result.combination_id,
                description=result.description,
                switch_position=result.switch_position,
                initial_mode=result.initial_mode,
                switches=result.switches,
                total_revenue=result.total_revenue,
                batch_results=result.batch_results,
                hangmei_summary=result.hangmei_summary,
                bottleneck=result.bottleneck_devices,
                infeasible_summary=result.infeasible_summary,
                tank_check_result=result.tank_check_result or {},
            )

            # R3优化：复用 PASS 1 的 monthly_load/feasible/batch_details（与价格无关）
            # 但若 recompute 产出了新的 batch_details（价格变化导致），优先用新的
            if prev_cr:
                # recompute 后 batch_details 可能变化（如经济折减不同），优先用 result 的
                if result.batch_details:
                    combo_row.batch_details = result.batch_details
                    combo_row.monthly_load = result.monthly_load
                else:
                    combo_row.monthly_load = prev_cr.monthly_load
                    combo_row.batch_details = prev_cr.batch_details
                combo_row.feasible = prev_cr.feasible
                combo_row.near_feasible = prev_cr.near_feasible
            else:
                combo_row.feasible = result.feasible
                combo_row.batch_details = result.batch_details
                combo_row.monthly_load = result.monthly_load

            combo_rows.append(combo_row)

        # R3优化：monthly_load 已复用，只需按本月价 total_revenue 重选最优
        # （可行性状态已从 PASS 1 复用，无需重新判定）
        final_optimal_id = None
        best_revenue = float('-inf')
        best_near_revenue = float('-inf')
        best_near_id = None
        for cr in combo_rows:
            if cr.feasible:
                if cr.total_revenue > best_revenue:
                    best_revenue = cr.total_revenue
                    final_optimal_id = cr.combination_id
            elif cr.near_feasible:
                if cr.total_revenue > best_near_revenue:
                    best_near_revenue = cr.total_revenue
                    best_near_id = cr.combination_id
        # Fallback：无可行但有接近可行
        if final_optimal_id is None and best_near_id is not None:
            final_optimal_id = best_near_id

        if final_optimal_id is not None:
            self.logger.info(f"本月价({final_month})选优组合: #{final_optimal_id} "
                             f"效益={best_revenue if best_revenue > float('-inf') else best_near_revenue:.2f}元")
        return {'combination_results_final': combo_rows, 'final_optimal_combo_id': final_optimal_id}

    # ── 编排入口 ──────────────────────────────────────────────────────────

    def generate_plan(self, plan_month: str, production_plans_input,
                      monthly_crude_input, blend_mode: bool, save_data: bool) -> dict:
        """生成排厂计划（懒加载：优先复用 DB 已落盘计划）。

        注：旧 LP 排产（generate_plan_core）已删除，统一走 CP-SAT。
        未传 production_plans_input 时优先返回 DB 已落盘的计划明细，
        并逐天补算 device_inputs_by_mode。无已落盘计划时返回错误提示。
        """
        repo = self._sched_repo()
        if not production_plans_input:
            existing = self._load_existing_plan(repo, f"PLAN-{plan_month.replace('-', '')}")
            if existing is not None:
                self.logger.info(f"未传输入，复用已有计划 {len(existing.details)} 条明细（懒加载）")
                details_list = [d.to_dict() for d in existing.details]
                self._enrich_device_inputs(details_list)
                return {'success': True, 'details': details_list,
                        'message': f'复用已有计划，共 {len(details_list)} 条明细'}
        return {'success': False,
                'message': '排产计划不存在，请先在排产求解页运行 CP-SAT 排产'}

    def _enrich_device_inputs(self, details_list: list) -> None:
        """给每条 detail 就地补上 device_inputs_by_mode 字段（慧炼收率预测依赖）。

        物料流公式跟 calculate_direct 一致（同一套 material_flows + products）：
        每天按 blend_detail 算 MODE_JIAN1_TO_WAX/MODE_JIAN1_TO_DIESEL 两种工况下各装置进料量。
        未覆盖油用当月最大覆盖油作代理。
        """
        import json as _json
        from ..scheduling.device_input_calc import (
            build_flow_topology, load_yield_tables, compute_device_inputs_by_mode,
        )
        try:
            scenario = self._load_scenario_fn()
            topology = build_flow_topology(scenario)
            products_by_crude = self._refinery_repo().load_products_grouped()
            yield_tables = load_yield_tables(products_by_crude, topology)
        except Exception as e:
            self.logger.warning(f"device_inputs 计算: 加载失败，跳过: {e}")
            for d in details_list:
                d['device_inputs_by_mode'] = None
            return

        # 当月最大覆盖油作 fallback（未覆盖油的代理）
        month_totals: Dict[str, float] = {}
        for d in details_list:
            bd = d.get('blend_detail')
            if isinstance(bd, str):
                try:
                    bd = _json.loads(bd)
                except Exception:
                    bd = {}
            for cid, qty in (bd or {}).items():
                if isinstance(qty, (int, float)) and qty > 0:
                    month_totals[cid] = month_totals.get(cid, 0) + float(qty)
        covered = {k: v for k, v in month_totals.items() if k in yield_tables}
        fallback_crude = max(covered, key=covered.get) if covered else None

        for d in details_list:
            bd = d.get('blend_detail')
            if isinstance(bd, str):
                try:
                    bd = _json.loads(bd)
                except Exception:
                    bd = {}
            try:
                d['device_inputs_by_mode'] = compute_device_inputs_by_mode(
                    bd or {}, yield_tables, topology, fallback_crude=fallback_crude)
            except Exception as e:
                self.logger.warning(f"day {d.get('day_of_month')} device_inputs 计算失败: {e}")
                d['device_inputs_by_mode'] = None

    def comprehensive_solve(self, plan_month: str, production_plans_input,
                            monthly_crude_input, blend_mode: bool, save_data: bool,
                            hangmei_target, shutdown_config=None,
                            plan_source: str = 'lp',
                            simplified: bool = True,
                            feasibility_rules: dict = None,
                            selection_strategy: dict = None) -> dict:
        """综合求解：生成/复用计划 → 批次+阀门组合 → 逐组合效益选优。

        三个业务流程在此编排：
          ① _produce_plan        排产（复用已有计划或生成新计划）
          ② _enumerate_switches  批次划分 + 减一线切换组合枚举
          ③ _evaluate_and_pick   逐组合效益评估 + 选最优
        返回普通 dict（未 clean NaN，由路由层清理）；早退返回 {'success': False, 'message'}；
        异常向上抛由路由层兜底。

        shutdown_config：装置停工声明 [{unit, start_time, end_time}, ...]，仅作用于
        流程②（按停工边界拆分批次、标记 shutdown_intervals），不影响排产原油加工量。
        plan_source: 'lp'(默认) 读 production_plan_details；
                     'cp_sat' 读 cp_sat_plan_details（CP-SAT 排产结果）。
        """
        repo = self._sched_repo()
        plan_id = f"PLAN-{plan_month.replace('-', '')}"
        self.logger.info(f"========== 综合求解开始: 计划月份={plan_month}, save_data={save_data}, plan_source={plan_source} ==========")

        try:
            # ① 排产
            plan = self._produce_plan(repo, plan_id, plan_month, production_plans_input,
                                      monthly_crude_input, blend_mode, save_data,
                                      plan_source=plan_source)

            # ② 批次划分 + 阀门切换组合枚举（停工声明在此生效）
            self.logger.info(f"========== 开始优化阀门切换: 计划ID={plan_id} ==========")
            switches = self._enumerate_switches(copy.deepcopy(plan.details), shutdown_config,
                                                plan_month=plan_month)

            # ③ 逐组合效益评估 + 选优（航煤工况上下文随 hangmei_target 启用）
            # 双价格月口径：选方案用上月价(prev_month)，核算效益用本月价(plan_month)
            prev_month = _prev_month(plan_month) if plan_month else None
            self.logger.info(f"========== 双价格月: 选优={prev_month} / 核算={plan_month} ==========")
            # 选优阶段预加载引用数据（上月价 + 成本 + 配比）
            scenarios: Dict = {}
            prices_prev, device_costs_prev, feed_ratios_prev = self._preload_reference_data(
                switches.batches, scenarios, prev_month, plan.custom_crude_costs)
            # 选优阶段航煤价也按上月（build_hangmei_context 冻结价格到上下文）
            hangmei_ctx = build_hangmei_context(
                switches.batches, scenarios,
                hangmei_target, plan.custom_crude_costs, self.logger,
                plan_month=prev_month, prices=prices_prev)
            solution = self._evaluate_and_pick(
                switches.batches, switches.combinations,
                plan.custom_crude_costs, hangmei_ctx,
                select_month=prev_month, final_month=plan_month,
                prices=prices_prev, device_costs=device_costs_prev,
                feed_ratios=feed_ratios_prev, scenarios=scenarios)
        except _SolveAbort as abort:
            resp = {'success': False, 'message': str(abort)}
            if getattr(abort, 'shutdown', None):
                resp['shutdown'] = abort.shutdown
            return resp

        # 结果组装（可行性口径=月度平均负荷，由三步重定+重选最优）
        # 先 finalize+reassess：算 monthly_load + 按月度重定 feasible + 重选最优组合
        tank_initials = self._load_tank_initials(plan_month)
        self._finalize_combination_outputs(
            solution.pass1_results, solution.combination_results, tank_initials)
        self._reassess_feasibility(solution.combination_results, feasibility_rules)
        monthly_optimal = self._select_optimal(
            solution.pass1_results, solution.combination_results, selection_strategy)

        # 本月价预加载 + 航煤上下文（最终核算 + 全组合评估共用，避免重复 build_hangmei_context）
        final_hangmei_ctx = None
        prices_final, device_costs_final, feed_ratios_final = {}, {}, {}
        if plan_month:
            prices_final, device_costs_final, feed_ratios_final = self._preload_reference_data(
                switches.batches, scenarios, plan_month,
                plan.custom_crude_costs)
            if hangmei_target:
                final_hangmei_ctx = build_hangmei_context(
                    switches.batches, scenarios,
                    hangmei_target, plan.custom_crude_costs, self.logger,
                    plan_month=plan_month, prices=prices_final)

        if monthly_optimal:
            optimal_combination = {
                'combination_id': monthly_optimal['combination_id'],
                'switches': monthly_optimal['switches'],
                'description': monthly_optimal['description'],
            }
            # selection_revenue = 选优阶段(上月价)选中组合的效益，供前端展示"选方案"步
            selection_revenue = monthly_optimal['total_revenue']
            # 最终核算：对月度重选后的最优组合，用本月价(plan_month)全量重算(summary_only=False)
            # 保证 economic_breakdown/optimal_revenue 为本月价口径。selection_revenue 保留上月价。
            # 复用选优阶段 scenarios（已按月分桶预加载上月价；此处再补本月价）。
            final_combo = next(
                (c for c in switches.combinations
                 if c['combination_id'] == optimal_combination['combination_id']), None)
            if final_combo is not None and plan_month:
                self.logger.info(
                    f"========== 最终核算: 组合{optimal_combination['combination_id']} "
                    f"按本月价({plan_month})重算 ==========")
                # 本月价预加载 + 航煤上下文已在上方统一构建（final_hangmei_ctx），此处直接复用
                full_result = evaluate_combination(
                    final_combo, switches.batches, scenarios,
                    plan.custom_crude_costs,
                    hangmei_ctx=final_hangmei_ctx, plan_month=plan_month,
                    logger=self.logger, summary_only=False,
                    prices=prices_final, device_costs=device_costs_final,
                    feed_ratios=feed_ratios_final)
                # 可行性口径：此处不依据 full_result.feasible（单批次超容即不可行，过严），
                # 因为 _reassess_feasibility 已按月度平均负荷口径重定可行并选中此组合。
                # 月度可行即可执行（个别批次日均超容可逐日微调进料）。超容批次的 total_revenue
                # 仍为理论收益，与本口径一致。故只要有重算结果即采用其本月价收益/拆解。
                if full_result:
                    optimal_calc_results = list(full_result.calc_results)
                    optimal_explanations = list(full_result.explanations)
                    optimal_revenue = full_result.total_revenue
                    optimal_hangmei = full_result.hangmei_summary or monthly_optimal.get('hangmei_summary', {})
                    optimal_tank_check = full_result.tank_check_result or {}
                    final_month_label = plan_month
                else:
                    # 重算异常（罕见）：回退上月价结果
                    optimal_calc_results = monthly_optimal['calc_results']
                    optimal_explanations = monthly_optimal['explanations']
                    optimal_revenue = monthly_optimal['total_revenue']
                    optimal_hangmei = monthly_optimal.get('hangmei_summary', {})
                    optimal_tank_check = {}
                    final_month_label = prev_month  # 标注为上月价，避免值与标签矛盾
                    self.logger.warning(
                        f"本月价({plan_month})核算组合{optimal_combination['combination_id']}重算异常，"
                        f"回退上月价({prev_month})结果")
            else:
                # 无 plan_month（optimize_valve 等场景）或组合字典缺失：沿用选优结果
                optimal_calc_results = monthly_optimal['calc_results']
                optimal_explanations = monthly_optimal['explanations']
                optimal_revenue = monthly_optimal['total_revenue']
                optimal_hangmei = monthly_optimal.get('hangmei_summary', {})
                optimal_tank_check = {}
                final_month_label = prev_month
            _temp_note = '（接近可行：所有组合均不满足罐容或负荷约束，选取超容最轻方案）' if monthly_optimal.get('near_feasible') else ''
            message = (f'找到{len(switches.batches)}个批次，共{len(switches.combinations)}种组合，'
                       f'最优方案为组合{optimal_combination["combination_id"]}{_temp_note}，'
                       f'选优效益({prev_month or "上月"}价){selection_revenue:.2f}元，'
                       f'核算效益({final_month_label or "本月"}价){optimal_revenue:.2f}元')
        else:
            optimal_combination = None
            selection_revenue = 0
            optimal_revenue = 0
            optimal_calc_results = solution.optimal_calc_results
            optimal_explanations = solution.optimal_explanations
            optimal_hangmei = solution.optimal_hangmei_summary or {}
            optimal_tank_check = {}
            final_month_label = plan_month
            message = f'找到{len(switches.batches)}个批次，共{len(switches.combinations)}种组合，但无有效组合数据'

        self.logger.info(f"========== 综合求解完成 ==========")

        # 写回 device_load_rate（基于最优组合的起始装置负荷率）
        # 修正：原版无条件写回（绕过 save_data），DB 版改为受 save_data 控制，
        # 使"只读求解"（save_data=False）不再产生写副作用。
        if save_data and optimal_calc_results:
            self._update_device_load_rate(plan.details, switches.batches,
                                          optimal_calc_results, repo)

        # 经济效益说明（文本串，向后兼容老页面）+ 结构化拆解（业务页装置级/产品级表）
        # 两者共用同一份聚合数据（_aggregate_economics），保证数字完全一致
        _ml = (monthly_optimal or {}).get('monthly_load') if monthly_optimal else None
        _nf = bool((monthly_optimal or {}).get('near_feasible')) if monthly_optimal else False
        # 加载场景获取加工装置列表（动态排除CDU和储罐）+ CDU device_id
        try:
            scenario = self._load_scenario_fn()
            proc_ids = scenario.processing_device_ids
            cdu_id = scenario.start_device_id
        except Exception:
            proc_ids = None
            cdu_id = None
        _agg = aggregate_economics(optimal_explanations, monthly_load=_ml, start_device_id=cdu_id)
        economic_explanation = build_economic_explanation(
            _agg, actual_profit=optimal_revenue, near_feasible=_nf)
        economic_breakdown = build_economic_breakdown(
            _agg, proc_ids, actual_profit=optimal_revenue)
        # 各批次装置级计算过程（投入→负荷→减一线→柴加/蜡加→收率→效益），供预测页详细展示
        optimal_batch_details = self._build_batch_details(
            solution.combination_results, optimal_combination,
            optimal_calc_results, optimal_explanations, tank_initials)

        # 本月价全组合批量评估（供前端"本月价组合对比表"）：
        # R2+R3优化：复用 PASS 1 物理计算 + monthly_load，仅重跑经济计算
        # R1优化：最优组合已由 PASS 3 完成 generate_explanation 全量重算，PASS 4 直接复用
        precomputed_optimal = None
        if optimal_combination and optimal_calc_results:
            precomputed_optimal = {
                'combination_id': optimal_combination['combination_id'],
                'calc_results': optimal_calc_results,
                'explanations': optimal_explanations,
                'total_revenue': optimal_revenue,
                'hangmei_summary': optimal_hangmei,
                'tank_check_result': optimal_tank_check,
                'batch_details': optimal_batch_details,
            }
        final_eval = self._eval_all_combos_final(
            switches.batches, switches.combinations, plan.custom_crude_costs,
            hangmei_target, scenarios, plan_month,
            hangmei_ctx=final_hangmei_ctx,
            pass1_results=solution.pass1_results,
            prev_combination_results=solution.combination_results,
            precomputed_optimal=precomputed_optimal,
            prices=prices_final, device_costs=device_costs_final,
            feed_ratios=feed_ratios_final)

        # 减一线切换点供需分析（切换前 CDU 产出 vs 设备月平均负荷消耗）
        jian1_switch_analysis = {}
        if optimal_combination:
            opt_combo_dict = next(
                (c for c in switches.combinations
                 if c['combination_id'] == optimal_combination['combination_id']), None)
            if opt_combo_dict:
                jian1_switch_analysis = build_jian1_switch_analysis(
                    opt_combo_dict, switches.batches,
                    scenarios,
                    calc_results=optimal_calc_results)

        # 字段顺序：结论先行（Agent 优先读到），数据后行（前端渲染用）
        result = {
            # ── ① 结论型（Agent 最关心，排最前）──
            'success': True,
            'message': message,
            'optimal_combination': optimal_combination,
            'optimal_revenue': optimal_revenue,
            'has_feasible': bool(optimal_combination),
            # ── ② 口径信息（结论的补充上下文）──
            'selection_price_month': prev_month,
            'final_price_month': final_month_label,
            'selection_revenue': selection_revenue,
            # ── ③ 文字说明（Agent 可直接读取）──
            'economic_explanation': economic_explanation,
            # ── ④ 概要数字（轻量）──
            'total_combinations': len(switches.combinations),
            # ── ⑤ 列表型数据（Agent 按需查看，排后面）──
            'batches': switches.batches,
            'combination_results': [cr.to_dict() for cr in solution.combination_results],
            'final_optimal_combo_id': final_eval['final_optimal_combo_id'],
            # ── ⑥ 渲染型（前端专用，simplified=True 时置 None）──
            'economic_breakdown': economic_breakdown,
            'optimal_batch_details': optimal_batch_details,
            'hangmei_summary': optimal_hangmei,
            'tank_check_result': optimal_tank_check,
            'jian1_switch_analysis': jian1_switch_analysis,
            'combination_results_final': [cr.to_dict() for cr in final_eval['combination_results_final']],
        }
        # 精简模式裁剪（两级）：
        #   simplified=True  → 摘要级：组合列表只留 id/收益/可行性，渲染型字段置 None（Agent/MCP 用）
        #   simplified=False → 完整级：全量返回（前端渲染用）
        if simplified:
            result['combination_results'] = _summary_combo_results(result['combination_results'])
            result['combination_results_final'] = None
            result['optimal_batch_details'] = None
            result['economic_breakdown'] = None
            result['jian1_switch_analysis'] = None
            result['tank_check_result'] = None
            result['hangmei_summary'] = None
            result['simplified'] = True
        else:
            result['simplified'] = False
        return result

    def optimize_valve(self, plan_id: str, simplified: bool = False,
                       feasibility_rules: dict = None,
                       selection_strategy: dict = None) -> dict:
        """优化减一线阀门切换位置（基于已存在计划，不重新生成、不启用航煤工况）。

        复用 ②③ 两个编排步骤；流程①退化为「加载已存在计划」，计划不存在直接早退
        （与原路由一致：不打"开始优化"日志）。返回普通 dict（不 clean NaN，与原路由行为一致）。

        simplified=True 时裁剪渲染型字段（Agent/MCP 用），False 全量返回（前端用）。
        """
        repo = self._sched_repo()

        # ① 排产：仅加载已存在计划
        plan = self._load_existing_plan(repo, plan_id)
        if plan is None:
            return {'success': False, 'message': f'计划不存在: {plan_id}'}

        self.logger.info(f"========== 开始优化阀门切换: 计划ID={plan_id} ==========")
        try:
            # ② 批次划分 + 阀门切换组合枚举（原 optimize_valve 不 deepcopy，保持一致）
            switches = self._enumerate_switches(plan.details)
            # ③ 逐组合效益评估 + 选优（非航煤工况：hangmei_ctx=None，无价格月=用默认价）
            scenarios: Dict = {}
            prices, device_costs, feed_ratios = self._preload_reference_data(
                switches.batches, scenarios, None, plan.custom_crude_costs)
            solution = self._evaluate_and_pick(
                switches.batches, switches.combinations,
                plan.custom_crude_costs, None, None, None,
                prices=prices, device_costs=device_costs, feed_ratios=feed_ratios,
                scenarios=scenarios)
        except _SolveAbort as abort:
            resp = {'success': False, 'message': str(abort)}
            if getattr(abort, 'shutdown', None):
                resp['shutdown'] = abort.shutdown
            return resp

        # 结果组装（可行性口径=月度平均负荷，由三步重定+重选最优）
        # 从 plan_id 提取年月加载月初容量（PLAN-202607 → 2026-07）
        _ym = f"{plan_id[5:9]}-{plan_id[9:11]}" if len(plan_id) >= 11 else None
        tank_initials = self._load_tank_initials(_ym) if _ym else {}
        self._finalize_combination_outputs(
            solution.pass1_results, solution.combination_results, tank_initials)
        self._reassess_feasibility(solution.combination_results, feasibility_rules)
        monthly_optimal = self._select_optimal(
            solution.pass1_results, solution.combination_results, selection_strategy)

        if monthly_optimal:
            optimal_combination = {
                'combination_id': monthly_optimal['combination_id'],
                'switches': monthly_optimal['switches'],
                'description': monthly_optimal['description'],
            }
            optimal_revenue = monthly_optimal['total_revenue']
            optimal_calc_results = monthly_optimal['calc_results']
            optimal_explanations = monthly_optimal['explanations']
            message = (f'找到{len(switches.batches)}个批次，共{len(switches.combinations)}种组合，'
                       f'最优方案为组合{optimal_combination["combination_id"]}，'
                       f'总效益{optimal_revenue:.2f}元')
        else:
            optimal_combination = None
            optimal_revenue = 0
            optimal_calc_results = solution.optimal_calc_results
            optimal_explanations = solution.optimal_explanations
            message = f'找到{len(switches.batches)}个批次，共{len(switches.combinations)}种组合，但未找到可行方案（展示各组合理论收益供对比）'

        optimal_batch_details = self._build_batch_details(
            solution.combination_results, optimal_combination,
            optimal_calc_results, optimal_explanations, tank_initials)

        # 字段顺序：结论先行（Agent 优先读到），数据后行（前端渲染用）
        result = {
            # ── ① 结论型 ──
            'success': True,
            'message': message,
            'optimal_combination': optimal_combination,
            'optimal_revenue': optimal_revenue,
            'has_feasible': bool(optimal_combination),
            # ── ② 概要数字 ──
            'total_combinations': len(switches.combinations),
            # ── ③ 列表型数据 ──
            'batches': switches.batches,
            'combination_results': [cr.to_dict() for cr in solution.combination_results],
            # ── ④ 渲染型 ──
            'optimal_batch_details': optimal_batch_details,
        }
        if simplified:
            result['combination_results'] = _summary_combo_results(result['combination_results'])
            result['optimal_batch_details'] = None
            result['simplified'] = True
        else:
            result['simplified'] = False
        return result

    def _annotate_combo_capacity(self, batches: list, combinations: list,
                                 custom_crude_costs: dict) -> list:
        """给每个切换组合追加轻量容量校验信息（不算效益）。

        复用 evaluate_combination(capacity_only=True) 跑各批次 direct_calculator，
        但跳过 generate_explanation 的经济计算。给每个组合 dict 写入：
          feasible / infeasible_summary / bottleneck / device_notes
          monthly_load（月度装置负荷聚合）/ overload_details（聚合月度的装置明细）
        device_notes 汇总各装置的 note（如"6000提升到8000"），供前端标注阈值调整。
        可行性口径：月度平均负荷（monthly_load.overload_count==0 即可行）。
        任一组合校验异常时降级为"未校验"，不阻断枚举结果展示。
        """
        scenarios: Dict = {}
        prices, device_costs, feed_ratios = self._preload_reference_data(
            batches, scenarios, None, custom_crude_costs)
        for combo in combinations:
            try:
                result = evaluate_combination(
                    combo, batches, scenarios, custom_crude_costs,
                    hangmei_ctx=None,
                    plan_month=None, logger=self.logger, capacity_only=True,
                    prices=prices, device_costs=device_costs, feed_ratios=feed_ratios)
                # 汇总各装置 note（去重，按装置名）
                dev_notes: Dict[str, str] = {}
                for cr in (result.calc_results or []):
                    for did, u in (cr.get('device_utilization') or {}).items():
                        if u.get('type') in ('tank',):
                            continue
                        note = u.get('note') or ''
                        if note:
                            dev_notes[u.get('name', did)] = note
                combo['device_notes'] = dev_notes
                # 月度负荷聚合（按月度平均负荷口径判定可行性）
                # capacity_only 路径 generate_explanation 产出 feed_details，需从 calc_results 提取
                explanations = [cr.get('explanation', {}) for cr in (result.calc_results or [])]
                batch_details = build_batch_details_list(
                    result.batch_results, result.calc_results,
                    explanations)
                monthly_load = build_monthly_load(batch_details)
                combo['monthly_load'] = monthly_load
                feasible = (monthly_load.get('overload_count') == 0)
                combo['feasible'] = feasible
                combo['infeasible_summary'] = result.infeasible_summary
                combo['bottleneck'] = result.bottleneck_devices
                # 装置负荷计算链明细（聚合月度），供前端展开数字孪生
                if result.calc_results:
                    combo['overload_details'] = [self._build_batch_detail(
                        result.calc_results, result.batch_results,
                        scenarios, feasible, monthly_load)]
                else:
                    combo['overload_details'] = []
            except Exception as e:
                self.logger.warning(f"组合{combo.get('combination_id')} 容量校验失败: {e}")
                combo['feasible'] = None  # None 表示未校验（区别于 True/False）
                combo['infeasible_summary'] = ''
                combo['bottleneck'] = []
                combo['device_notes'] = {}
                combo['monthly_load'] = None
                combo['overload_details'] = []
        return combinations

    @staticmethod
    def _pick_representative_batch(calc_results: list) -> int:
        """可行组合选代表批次：取 CDU(type=='start') 进料(input)最大的批次。
        找不到 CDU 时回退到第一个有 device_utilization 的批次。
        """
        best_idx, best_input = -1, -1.0
        for idx, cr in enumerate(calc_results):
            du = cr.get('device_utilization') or {}
            for u in du.values():
                if u.get('type') == 'start':
                    inp = float(u.get('input', 0) or 0)
                    if inp != inp:  # NaN
                        inp = 0
                    if inp > best_input:
                        best_input, best_idx = inp, idx
        if best_idx >= 0:
            return best_idx
        # 找不到 CDU：取第一个有 device_utilization 的批次
        for idx, cr in enumerate(calc_results):
            if cr.get('device_utilization'):
                return idx
        return 0

    def _build_batch_detail(self, calc_results: list, batch_results: list,
                            scenarios: Dict, feasible: bool,
                            monthly_load: dict = None) -> dict:
        """聚合所有批次的装置数据为月度汇总（装置表 + 流程图）。

        设备月度字段（monthly_input/monthly_capacity/monthly_util/is_overloaded）
        优先取自 monthly_load（含停工折算的正确口径），确保与组合对比表口径一致。
        代表批次（CDU 进料最大，由 _pick_representative_batch 选取）用于取进料来源
        拆解 input_sources、流程图 flow_diagram 及批次级字段。
        """
        calc_results = calc_results or []
        batch_results = batch_results or []
        if not calc_results:
            return {}
        rep_idx = self._pick_representative_batch(calc_results)
        rep_cr = calc_results[rep_idx]
        rep_br = batch_results[rep_idx] if rep_idx < len(batch_results) else {}

        # 月度聚合：device_id → {name, note}（仅用于 name/note，月度数值取自 monthly_load）
        dev_agg = {}
        for i, cr in enumerate(calc_results):
            du = cr.get('device_utilization') or {}
            for did, u in du.items():
                if u.get('type') not in ('start', 'normal'):
                    continue
                if did not in dev_agg:
                    dev_agg[did] = {
                        'name': u.get('name', did),
                        'note': u.get('note') or '',
                    }

        # 代表批次的场景/连接，用于 input_sources（保持代表批次物流走向）
        di = rep_cr.get('device_inputs') or {}
        cf = rep_cr.get('connection_flows') or {}
        sv = rep_cr.get('special_vars') or {}
        crude = rep_br.get('crude_type', '')
        scenario = scenarios.get(crude)

        # 月度聚合流程图数据：累加各批次的 input 和 connection_flows（均为批次值，直接累加）
        monthly_du = {}   # device_id → {input: 月度总进料, name, type}
        monthly_cf = {}   # conn_id → 月度总流量
        for i, cr in enumerate(calc_results):
            du = cr.get('device_utilization') or {}
            cfl = cr.get('connection_flows') or {}
            for did, u in du.items():
                inp = float(u.get('input', 0) or 0)
                if did not in monthly_du:
                    monthly_du[did] = {
                        'input': 0.0,
                        'name': u.get('name', did),
                        'type': u.get('type', ''),
                    }
                monthly_du[did]['input'] += inp
            for cid, flow in cfl.items():
                monthly_cf[cid] = monthly_cf.get(cid, 0.0) + float(flow or 0)

        # 月度负荷数据优先取自 monthly_load（含停工折算的正确口径）
        ml_devices = {}
        if monthly_load and monthly_load.get('devices'):
            for d in monthly_load['devices']:
                ml_devices[d['device_id']] = d

        devices = []
        for did, agg in dev_agg.items():
            ml = ml_devices.get(did, {})
            devices.append({
                'device_id': did,
                'name': agg['name'],
                'note': agg['note'],
                'monthly_input': ml.get('monthly_input', 0),
                'monthly_capacity': ml.get('monthly_capacity', 0),
                'monthly_util': ml.get('monthly_util', 0),
                'is_overloaded': ml.get('is_overloaded', False),
                'input_sources': build_device_input_sources(
                    scenario, did, monthly_cf, sv, rep_br.get('mode', '')),
            })
        # 按月度利用率降序，超容的排前
        devices.sort(key=lambda x: (0 if x['is_overloaded'] else 1, -x['monthly_util']))

        # 流程图使用月度聚合数据（节点=月度总进料，连线=月度总流量，加工装置含月度负荷）
        flow_diagram = build_flow_diagram(scenario, monthly_du, {}, monthly_cf, monthly_load)
        return {
            'batch_id': rep_br.get('batch_id'),
            'crude_type': crude,
            'mode': rep_br.get('mode', ''),
            'daily_input': round(rep_br.get('daily_input', 0), 1),
            'total_input': round(rep_br.get('total_input', 0), 1),
            'jian1_to_diesel': round(rep_br.get('jian1_to_diesel', 0), 1),
            'jian1_to_wax': round(rep_br.get('jian1_to_wax', 0), 1),
            'devices': devices,
            'flow_diagram': flow_diagram,
            'feasible': feasible,
        }

    def enumerate_switches(self, plan_month: str, shutdown_config=None) -> dict:
        """仅流程②：加载已落盘排产明细 → 批次划分 + 减一线切换组合枚举（不评估效益）。

        供「批次划分与切换组合识别」页使用：输入月份，基于该月已存的
        production_plan_details 做批次识别与组合枚举，不跑 LP、不算效益。
        计划不存在直接早退（与 optimize_valve 一致）。

        在组合枚举基础上追加轻量容量校验：对每个组合复用 direct_calculator 的
        容量检查（capacity_only=True，跳过经济计算），返回 feasible/瓶颈/note，
        让切换组合表能体现"哪个组合可行、装置够不够、阈值为何提高"。

        shutdown_config：装置停工声明 [{unit, start_time, end_time}, ...]，按停工边界
        拆分批次并标记 shutdown_intervals，返回中携带 shutdown 摘要供前端展示。
        """
        repo = self._sched_repo()
        plan_id = f"PLAN-{plan_month.replace('-', '')}"
        plan = self._load_existing_plan(repo, plan_id)
        if plan is None:
            return {'success': False,
                    'message': f'计划 {plan_id} 不存在或无明细，请先在排产求解页生成并保存计划'}

        self.logger.info(f"========== 批次划分与切换组合识别: 计划ID={plan_id} ==========")
        try:
            valve_result = ValveSwitchPlanner().enumerate_valve_switching(
                copy.deepcopy(plan.details), shutdown_config=shutdown_config,
                plan_month=plan_month)
        except _SolveAbort as abort:
            return {'success': False, 'message': str(abort)}

        if not valve_result.get('success'):
            return {'success': False,
                    'message': valve_result.get('message', '识别失败'),
                    'shutdown': valve_result.get('shutdown')}

        batches = valve_result['batches']
        combinations = valve_result['combinations']

        # 轻量容量校验：对每个组合复用 direct_calculator 的容量检查（capacity_only=True，
        # 跳过经济计算），给组合表加 feasible/瓶颈/note，体现"哪个组合可行、装置够不够、阈值为何提高"
        combinations = self._annotate_combo_capacity(batches, combinations, plan.custom_crude_costs)

        return {
            'success': True,
            'plan_id': plan_id,
            'plan_month': plan_month,
            'batches': batches,
            'combinations': combinations,
            'total_combinations': len(combinations),
            'shutdown': valve_result.get('shutdown'),
            'message': valve_result.get('message'),
        }
