# -*- coding: utf-8 -*-
"""计算层 MCP 工具注册 — 将 17 个 handle_* 函数注册为 @mcp.tool()。

每个工具的 name/description 与 MCP 设计文档中的 MCP-01~17 编号对齐，
Agent 可通过工具名称语义化选择调用。

工具分组:
    编排入口层 (3):  solve_refinery_plan / optimize_valve_switches /
                     prepare_solve_data
    独立计算层 (5):  calculate_batch_physical / calculate_batch_full /
                     evaluate_valve_combination / optimize_combinations /
                     init_hangmei_context
    分析渲染层 (4):  aggregate_batch_economics / render_economic_summary /
                     build_economic_breakdown / analyze_jian1_switch
    可视化+数据层 (3): build_flow_diagram / build_device_input_sources /
                     preload_reference_data
    参数化规则引擎 (2): assess_feasibility / select_optimal
"""

from typing import Optional


def register_calc_tools(mcp):
    """将 17 个计算层适配函数注册为 MCP 工具。"""

    # ── 编排入口层 (MCP-01/02/17) ──────────────────────────

    @mcp.tool()
    def prepare_solve_data(
        plan_month: str,
        shutdown_config: Optional[list] = None,
        plan_source: str = 'lp',
    ) -> dict:
        """MCP-17: 数据准备 — 加载排产计划→批次划分→阀门组合枚举。

        分步编排的入口工具。从 DB 加载已有排产计划，识别加工批次，
        枚举阀门切换组合，产出供 MCP-06/14/15/16 使用的中间数据。
        Agent 分步编排时的第一步调用。

        Args:
            plan_month: 计划月份（如 "2026-07"）
            shutdown_config: 停工声明 [{unit, start_time, end_time}]
            plan_source: 'lp' 读 production_plan_details（默认），
                         'cp_sat' 读 cp_sat_plan_details
        Returns:
            {success, plan_id, batches, combinations, custom_crude_costs,
             batch_count, combination_count}
        """
        from .handlers_orchestrate import handle_prepare_solve_data
        return handle_prepare_solve_data(
            plan_month=plan_month,
            shutdown_config=shutdown_config,
            plan_source=plan_source,
        )

    @mcp.tool()
    def solve_refinery_plan(
        plan_month: str,
        production_plans_input: list,
        monthly_crude_input: float,
        blend_mode: bool = False,
        save_data: bool = True,
        hangmei_target: Optional[float] = None,
        shutdown_config: Optional[list] = None,
        simplified: bool = True,
        feasibility_rules: Optional[dict] = None,
        selection_strategy: Optional[dict] = None,
    ) -> dict:
        """MCP-01: 综合求解 — 排产→批次→阀门枚举→选优（全链路）。

        给定计划月份和排产输入，自动生成批次和阀门组合，评估各组合经济效益，
        返回最优方案。支持通过 feasibility_rules / selection_strategy 参数
        定制可行性判断和选优策略（详见 MCP-15/16）。

        Args:
            plan_month: 计划月份（如 "2026-07"）
            production_plans_input: 排产计划输入（原油品种+加工量）
            monthly_crude_input: 月度原油加工总量（吨）
            blend_mode: 是否混炼
            save_data: 是否持久化排产结果
            hangmei_target: 航煤目标产出（吨），None=不启用航煤工况
            shutdown_config: 停工声明 [{unit, start_time, end_time}]
            simplified: 简化模式（裁剪大对象）
            feasibility_rules: 可行性规则（如 {"max_load_rate": 95.0}）
            selection_strategy: 选优策略（如 {"objective": "risk_averse"}）
        Returns:
            {success, optimal_combination, economic_summary, flow_diagram, ...}
        """
        from .handlers_orchestrate import handle_solve_refinery_plan
        return handle_solve_refinery_plan(
            plan_month=plan_month,
            production_plans_input=production_plans_input,
            monthly_crude_input=monthly_crude_input,
            blend_mode=blend_mode,
            save_data=save_data,
            hangmei_target=hangmei_target,
            shutdown_config=shutdown_config,
            simplified=simplified,
            feasibility_rules=feasibility_rules,
            selection_strategy=selection_strategy,
        )

    @mcp.tool()
    def optimize_valve_switches(
        plan_id: str,
        feasibility_rules: Optional[dict] = None,
        selection_strategy: Optional[dict] = None,
    ) -> dict:
        """MCP-02: 优化阀门切换位置（基于已存在计划，半链路）。

        不重新生成排产、不启用航煤工况，仅对已有计划的阀门切换位置重新优化。
        支持通过 feasibility_rules / selection_strategy 参数定制规则。

        Args:
            plan_id: 已有排产计划 ID
            feasibility_rules: 可行性规则（如 {"max_load_rate": 95.0}）
            selection_strategy: 选优策略（如 {"objective": "risk_averse"}）
        Returns:
            {success, optimal_combination, economic_summary, ...}
        """
        from .handlers_orchestrate import handle_optimize_valve_switches
        return handle_optimize_valve_switches(
            plan_id=plan_id,
            feasibility_rules=feasibility_rules,
            selection_strategy=selection_strategy,
        )

    # ── 独立计算层 (MCP-03/04/05/06/07) ──────────────────────

    @mcp.tool()
    def calculate_batch_physical(
        scenario_id: str,
        input_amount: float,
        yield_mode: str,
        days: int = 1,
        hangmei_mode: bool = False,
        hangmei_m_days: float = 0,
        day_index: float = 0,
        shutdown_intervals: Optional[dict] = None,
        feed_ratios: Optional[dict] = None,
        custom_crude_costs: Optional[dict] = None,
    ) -> dict:
        """MCP-03: 批次物理计算 — BFS 拓扑遍历，计算装置进出料流量和利用率。

        给定场景和批次参数，计算全装置物流拓扑的进出料流量、装置利用率，
        不含经济计算。

        Args:
            scenario_id: 原油品种标识（如 "BZ"）
            input_amount: 批次输入量（吨/天）
            yield_mode: 收率模式 ("JIAN1_TO_WAX" 减一线去蜡油 / "JIAN1_TO_DIESEL" 减一线去柴油)
            days: 批次天数
            hangmei_mode: 是否航煤工况
            hangmei_m_days: 航煤工况天数（M 值）
            day_index: 天数索引（月内位置）
            shutdown_intervals: 停工区间 {device_id: [(start_h, end_h)]}
            feed_ratios: 进料配比（可选，场景内部可自动推导）
            custom_crude_costs: 自定义原油成本 {crude_type_id: cost}
        Returns:
            {feasible, device_inputs, connection_flows, device_utilization, special_vars}
        """
        from .handlers_calc import handle_calculate_physical
        return handle_calculate_physical(
            scenario_id=scenario_id,
            input_amount=input_amount,
            yield_mode=yield_mode,
            days=days,
            hangmei_mode=hangmei_mode,
            hangmei_m_days=hangmei_m_days,
            day_index=day_index,
            shutdown_intervals=shutdown_intervals,
            feed_ratios=feed_ratios,
            custom_crude_costs=custom_crude_costs,
        )

    @mcp.tool()
    def calculate_batch_full(
        scenario_id: str,
        input_amount: float,
        yield_mode: str,
        days: int = 1,
        hangmei_mode: bool = False,
        hangmei_m_days: float = 0,
        day_index: float = 0,
        plan_month: Optional[str] = None,
        shutdown_intervals: Optional[dict] = None,
        capacity_only: bool = False,
        summary_only: bool = False,
        prices: Optional[dict] = None,
        device_costs: Optional[dict] = None,
        feed_ratios: Optional[dict] = None,
        custom_crude_costs: Optional[dict] = None,
    ) -> dict:
        """MCP-04: 批次完整计算 — 物理计算 + 经济效益计算。

        在 MCP-03 物理计算基础上叠加经济效益计算（收入/成本/利润），
        返回含 explanation 的完整结果。

        Args:
            scenario_id: 原油品种标识
            input_amount: 批次输入量（吨/天）
            yield_mode: 收率模式
            days: 批次天数
            hangmei_mode: 是否航煤工况
            hangmei_m_days: 航煤工况天数
            day_index: 天数索引
            plan_month: 计划月份（价格查表）
            shutdown_intervals: 停工区间
            capacity_only: 仅算能力（不含经济）
            summary_only: 简化模式（只算利润数字）
            prices: 预加载价格表（MCP-14 产出）
            device_costs: 预加载装置加工成本（MCP-14 产出）
            feed_ratios: 进料配比（MCP-14 产出）
            custom_crude_costs: 自定义原油成本
        Returns:
            {feasible, explanation, device_inputs, connection_flows, device_utilization}
        """
        from .handlers_calc import handle_calculate_direct
        return handle_calculate_direct(
            scenario_id=scenario_id,
            input_amount=input_amount,
            yield_mode=yield_mode,
            days=days,
            hangmei_mode=hangmei_mode,
            hangmei_m_days=hangmei_m_days,
            day_index=day_index,
            plan_month=plan_month,
            shutdown_intervals=shutdown_intervals,
            capacity_only=capacity_only,
            summary_only=summary_only,
            prices=prices,
            device_costs=device_costs,
            feed_ratios=feed_ratios,
            custom_crude_costs=custom_crude_costs,
        )

    @mcp.tool()
    def evaluate_valve_combination(
        combo: dict,
        batches: list,
        custom_crude_costs: dict,
        hangmei_ctx: Optional[dict] = None,
        plan_month: Optional[str] = None,
        capacity_only: bool = False,
        summary_only: bool = False,
        prices: Optional[dict] = None,
        device_costs: Optional[dict] = None,
        feed_ratios: Optional[dict] = None,
    ) -> dict:
        """MCP-05: 评估单个阀门切换组合的各批次经济效益。

        按 batches 中的 crude_type 自动加载场景，评估给定阀门组合的可行性
        和经济效益。

        Args:
            combo: 阀门组合定义 {switch_position, initial_mode, ...}
            batches: 批次列表（每项含 crude_type, input_amount, days 等）
            custom_crude_costs: 原油成本 {crude_type_id: cost}
            hangmei_ctx: MCP-07 产出的航煤上下文 dict（None=不启用航煤）
            plan_month: 计划月份
            capacity_only: 仅算能力
            summary_only: 简化模式
            prices: 预加载价格表（MCP-14 产出）
            device_costs: 预加载装置成本（MCP-14 产出）
            feed_ratios: 进料配比（MCP-14 产出）
        Returns:
            CombinationResult 序列化 dict（含 feasible/revenue/explanations/...）
        """
        from .handlers_calc import handle_evaluate_valve_combination
        return handle_evaluate_valve_combination(
            combo=combo, batches=batches,
            custom_crude_costs=custom_crude_costs,
            hangmei_ctx=hangmei_ctx, plan_month=plan_month,
            capacity_only=capacity_only, summary_only=summary_only,
            prices=prices, device_costs=device_costs, feed_ratios=feed_ratios,
        )

    @mcp.tool()
    def optimize_combinations(
        batches: list,
        combinations: list,
        custom_crude_costs: dict,
        hangmei_ctx: Optional[dict] = None,
        select_month: Optional[str] = None,
        final_month: Optional[str] = None,
        prices: Optional[dict] = None,
        device_costs: Optional[dict] = None,
        feed_ratios: Optional[dict] = None,
    ) -> dict:
        """MCP-06: 遍历所有阀门切换组合，挑出经济效益最优方案。

        Args:
            batches: 批次列表
            combinations: 阀门组合列表（MCP-05 可预评估单个）
            custom_crude_costs: 原油成本
            hangmei_ctx: MCP-07 产出的航煤上下文 dict
            select_month: 选优月份（上月价）
            final_month: 核算月份（本月价）
            prices: 预加载价格表
            device_costs: 预加载装置成本
            feed_ratios: 进料配比
        Returns:
            {optimal_combination, optimal_revenue, all_results, ...}
        """
        from .handlers_calc import handle_optimize_combinations
        return handle_optimize_combinations(
            batches=batches, combinations=combinations,
            custom_crude_costs=custom_crude_costs,
            hangmei_ctx=hangmei_ctx,
            select_month=select_month, final_month=final_month,
            prices=prices, device_costs=device_costs, feed_ratios=feed_ratios,
        )

    @mcp.tool()
    def init_hangmei_context(
        batches: list,
        hangmei_target: float,
        custom_crude_costs: dict,
        plan_month: Optional[str] = None,
        prices: Optional[dict] = None,
    ) -> dict:
        """MCP-07: 初始化航煤工况上下文。

        根据航煤目标产出，构建航煤工况上下文（主动/被动装置、产品收率等），
        供 MCP-05/06 传入。

        Args:
            batches: 批次列表
            hangmei_target: 航煤目标产出（吨）
            custom_crude_costs: 原油成本
            plan_month: 计划月份
            prices: 预加载价格表
        Returns:
            HangmeiContext 序列化 dict（供 MCP-05/06 的 hangmei_ctx 参数）
        """
        from .handlers_calc import handle_init_hangmei_context
        return handle_init_hangmei_context(
            batches=batches, hangmei_target=hangmei_target,
            custom_crude_costs=custom_crude_costs,
            plan_month=plan_month, prices=prices,
        )

    # ── 分析渲染层 (MCP-08/09/10/11) ─────────────────────────

    @mcp.tool()
    def aggregate_batch_economics(
        optimal_explanations: list,
        monthly_load: Optional[dict] = None,
        start_device_id: Optional[str] = None,
    ) -> dict:
        """MCP-08: 聚合最优组合各批次经济效益（SSOT）。

        统一聚合各批次 explanation，供 MCP-09 文本渲染和 MCP-10 结构化拆解共用。

        Args:
            optimal_explanations: 最优组合各批次的 explanation 列表
            monthly_load: 月度负荷数据
            start_device_id: 起始装置ID（常减压）
        Returns:
            聚合后的经济效益 dict
        """
        from .handlers_analysis import handle_aggregate_batch_economics
        return handle_aggregate_batch_economics(
            optimal_explanations=optimal_explanations,
            monthly_load=monthly_load,
            start_device_id=start_device_id,
        )

    @mcp.tool()
    def render_economic_summary(
        agg: dict,
        actual_profit: Optional[float] = None,
        near_feasible: bool = False,
    ) -> str:
        """MCP-09: 从聚合数据生成经济效益说明文字（纯渲染层）。

        Args:
            agg: MCP-08 聚合后的经济效益 dict
            actual_profit: 实际利润（覆盖计算值）
            near_feasible: 是否近可行方案
        Returns:
            经济效益说明文字
        """
        from .handlers_analysis import handle_render_economic_summary
        return handle_render_economic_summary(
            agg=agg, actual_profit=actual_profit, near_feasible=near_feasible,
        )

    @mcp.tool()
    def build_economic_breakdown(
        agg: dict,
        processing_device_ids: Optional[list] = None,
        actual_profit: Optional[float] = None,
    ) -> dict:
        """MCP-10: 从聚合数据生成结构化效益拆解（纯数据变换）。

        Args:
            agg: MCP-08 聚合后的经济效益 dict
            processing_device_ids: 加工装置ID列表
            actual_profit: 实际利润（覆盖计算值）
        Returns:
            结构化效益拆解 dict
        """
        from .handlers_analysis import handle_build_economic_breakdown
        return handle_build_economic_breakdown(
            agg=agg, processing_device_ids=processing_device_ids,
            actual_profit=actual_profit,
        )

    @mcp.tool()
    def analyze_jian1_switch(
        optimal_combo: dict,
        batches: list,
        calc_results: Optional[dict] = None,
    ) -> dict:
        """MCP-11: 减一线切换点供需分析。

        分析切换前时段的 CDU 产出 vs 设备月平均负荷消耗。

        Args:
            optimal_combo: 最优组合信息
            batches: 批次列表（含 crude_type，适配层据此加载场景）
            calc_results: 计算结果（可选）
        Returns:
            减一线切换分析 dict
        """
        from .handlers_analysis import handle_analyze_jian1_switch
        return handle_analyze_jian1_switch(
            optimal_combo=optimal_combo, batches=batches,
            calc_results=calc_results,
        )

    # ── 可视化+数据层 (MCP-12/13/14) ─────────────────────────

    @mcp.tool()
    def build_flow_diagram(
        scenario_id: str,
        device_util: dict,
        device_inputs: dict,
        connection_flows: dict,
        monthly_load: Optional[dict] = None,
    ) -> dict:
        """MCP-12: 构建全装置流程图数据（数字孪生视图）。

        生成节点（装置）和边（物流连接）数据，供前端渲染流程图。

        Args:
            scenario_id: 原油品种标识
            device_util: 装置利用率数据
            device_inputs: 装置进料数据
            connection_flows: 连接流量数据
            monthly_load: 月度负荷数据
        Returns:
            {nodes: [...], edges: [...]}
        """
        from .handlers_data import handle_build_flow_diagram
        return handle_build_flow_diagram(
            scenario_id=scenario_id, device_util=device_util,
            device_inputs=device_inputs, connection_flows=connection_flows,
            monthly_load=monthly_load,
        )

    @mcp.tool()
    def build_device_input_sources(
        scenario_id: str,
        device_id: str,
        connection_flows: dict,
        special_vars: dict,
        mode: str,
    ) -> list:
        """MCP-13: 装置进料来源拆解。

        拆解某装置的进料来源（连接级流量），用于"为何超"计算链展示。

        Args:
            scenario_id: 原油品种标识
            device_id: 目标装置ID
            connection_flows: 连接流量数据
            special_vars: 特殊变量 {jian1_to_diesel, jian1_to_wax}
            mode: 收率模式
        Returns:
            [{source_device, product_name, yield_rate, special_var, flow, ...}, ...]
        """
        from .handlers_data import handle_build_device_input_sources
        return handle_build_device_input_sources(
            scenario_id=scenario_id, device_id=device_id,
            connection_flows=connection_flows, special_vars=special_vars,
            mode=mode,
        )

    @mcp.tool()
    def preload_reference_data(
        batches: list,
        plan_month: str,
        custom_crude_costs: Optional[dict] = None,
    ) -> dict:
        """MCP-14: 预加载引用数据（价格 + 成本 + 配比）。

        按 batches 中的原油品种和计划月份，从 DB 预加载价格表、装置加工成本、
        进料配比。产出供 MCP-04/05/06 使用。

        Args:
            batches: 批次列表（确定原油品种范围）
            plan_month: 计划月份（价格查表）
            custom_crude_costs: 自定义原油成本
        Returns:
            {prices: {...}, device_costs: {...}, feed_ratios: {...}}
        """
        from .handlers_data import handle_preload_reference_data
        return handle_preload_reference_data(
            batches=batches, plan_month=plan_month,
            custom_crude_costs=custom_crude_costs,
        )

    # ── 参数化规则引擎层 (MCP-15/16) ───────────────────────

    @mcp.tool()
    def assess_feasibility(
        combination_results: list,
        rules: Optional[dict] = None,
    ) -> dict:
        """MCP-15: 参数化可行性判断 — 按自定义规则重新判定组合可行性。

        对 optimize_combinations(MCP-06) 返回的组合列表，用可配置的规则参数
        替代默认硬编码阈值。Agent 可根据用户意图组装 rules，实现定制化约束。

        Args:
            combination_results: 组合结果列表（MCP-06 的 combination_results）
            rules: 规则参数（可选，不传用默认规则）:
                - max_load_rate: 月度平均负荷率上限（默认100.0）
                - tank_capacity_strict: 罐容违规是否硬约束（默认True）
                - max_overload_count: 允许的超容装置数（默认0）
                - max_overload_ratio: 允许的超容比例（默认0.0）
                - min_hangmei_output: 航煤最低产出吨（默认0.0）
                - require_all_feasible: 是否要求所有批次可行（默认False）
        Returns:
            {assessments: [{combination_id, feasible, near_feasible, infeasible_summary, details}], summary}
        """
        from .handlers_calc import handle_assess_feasibility
        return handle_assess_feasibility(
            combination_results=combination_results,
            rules=rules,
        )

    @mcp.tool()
    def select_optimal(
        combination_results: list,
        strategy: Optional[dict] = None,
    ) -> dict:
        """MCP-16: 参数化选优 — 按自定义策略选取最优组合。

        替代硬编码的 max(revenue) 逻辑，支持多目标加权、风险规避等选优策略。
        需在 assess_feasibility(MCP-15) 之后调用。

        Args:
            combination_results: 组合结果列表（含 feasible/near_feasible 标记）
            strategy: 选优策略（可选，不传用默认策略）:
                - objective: "revenue"|"feasibility_margin"|"risk_averse"|"multi_objective"
                - weights: multi_objective模式权重 {revenue, feasibility_margin, hangmei_output}
                - prefer_near_feasible: 无可行时是否接受接近可行（默认True）
                - penalty_factor: risk_averse超容惩罚系数（默认1.0）
                - min_revenue: 最低收益门槛（默认0.0）
        Returns:
            {combination_id, description, total_revenue, score, feasible, near_feasible, details}
        """
        from .handlers_calc import handle_select_optimal
        return handle_select_optimal(
            combination_results=combination_results,
            strategy=strategy,
        )
