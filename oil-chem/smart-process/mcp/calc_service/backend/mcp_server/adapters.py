# -*- coding: utf-8 -*-
"""scenario_id 适配层 — MCP 输入复杂度解决方案。

Agent 只传 scenario_id（字符串），适配层内部加载 RefineryScenario 对象注入计算函数。
Scenario 对象不出现在 MCP 输入/输出中，对 Agent 完全透明。

核心类:
    ScenarioAdapter — 单场景加载 + per-session 缓存
    MultiScenarioAdapter — 多场景加载（按 batches 中的 crude_type 批量加载）

MCP 适配函数已按层级拆分到:
    handlers_analysis.py     — MCP-08/09/10/11
    handlers_data.py         — MCP-14/12/13
    handlers_calc.py         — MCP-03/04/05/06/07
    handlers_orchestrate.py  — MCP-01/02
"""

from typing import Dict, List, Optional

from ..data.refinery_repo import RefineryRepository
from ..models.refinery import RefineryScenario


class ScenarioAdapter:
    """scenario_id → RefineryScenario 适配层（单场景）。

    特性:
        - per-session 缓存：同一 scenario_id 多次加载只查一次 DB
        - crude_costs 注入：支持 custom_crude_costs 覆盖
        - 缓存失效：upsert_side_line/yields 后需调用 invalidate()
    """

    def __init__(self, repo: RefineryRepository = None):
        self._repo = repo or RefineryRepository()
        self._cache: Dict[str, RefineryScenario] = {}

    def load(self, scenario_id: str,
             custom_crude_costs: Dict[str, float] = None) -> RefineryScenario:
        """从 scenario_id（crude_type）加载场景，带 session 级缓存。

        Args:
            scenario_id: 原油品种标识（如 "BZ"），对应 RefineryScenario.crude_type
            custom_crude_costs: 自定义原油成本 {crude_type_id: cost}，覆盖 DB 默认值
        Returns:
            RefineryScenario 对象（缓存命中时直接返回）
        """
        cache_key = scenario_id
        if cache_key not in self._cache:
            scenario = self._repo.load_scenario(crude_type=scenario_id)
            if custom_crude_costs:
                scenario.crude_costs = custom_crude_costs
            self._cache[cache_key] = scenario
        return self._cache[cache_key]

    def invalidate(self, scenario_id: str = None):
        """清除缓存（数据变更后调用）。

        Args:
            scenario_id: 指定清除的场景；None=清除全部
        """
        if scenario_id:
            self._cache.pop(scenario_id, None)
        else:
            self._cache.clear()


class MultiScenarioAdapter:
    """batches → Dict[str, RefineryScenario] 适配层（多场景）。

    按 batches 中的 crude_type 字段批量加载场景，供 evaluate_combination /
    optimize_combinations / build_hangmei_context 使用。
    """

    def __init__(self, scenario_adapter: ScenarioAdapter):
        self._scenario = scenario_adapter

    def load_by_batches(self, batches: List[dict],
                        custom_crude_costs: Dict[str, float] = None
                        ) -> Dict[str, RefineryScenario]:
        """按批次列表中的 crude_type 批量加载场景。

        Args:
            batches: 批次列表，每项含 crude_type 字段
            custom_crude_costs: 自定义原油成本
        Returns:
            {crude_type: RefineryScenario} 字典
        """
        scenarios: Dict[str, RefineryScenario] = {}
        for batch in batches:
            crude_type = batch.get('crude_type')
            if crude_type and crude_type not in scenarios:
                scenarios[crude_type] = self._scenario.load(
                    crude_type, custom_crude_costs)
        return scenarios
