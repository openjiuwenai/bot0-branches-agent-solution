# -*- coding: utf-8 -*-
"""参数化可行性规则引擎。

替代 solve_service._reassess_feasibility 的硬编码逻辑，
将罐容/负荷/航煤等约束的判断阈值参数化，供 Agent 通过 MCP 工具定制。

规则字段（rules_json）:
    max_load_rate: float = 100.0
        月度平均负荷率上限（百分比，100.0=满负荷）。
        超过此值的装置计入 overload_count。
    tank_capacity_strict: bool = True
        罐容违规是否作为硬约束（True=违规即不可行，False=仅标记不否决）。
    max_overload_count: int = 0
        允许的超容装置数量上限。超过此数 → 不可行。
        设为 0 表示只要有超容即判不可行（当前默认行为）。
    max_overload_ratio: float = 0.0
        允许的最大超容比例（百分比，如 10.0 表示允许超容 110%）。
        超过此比例的装置才算"严重超容"，计入严重违规。
    min_hangmei_output: float = 0.0
        航煤最低产出约束（吨）。组合的航煤产出低于此值 → 不可行。
        仅对启用航煤工况的场景生效。
    require_all_feasible: bool = False
        是否要求所有批次均可行（True=任一批次不可行则组合不可行）。
"""
from __future__ import annotations
from typing import List, Dict, Any, Optional
from dataclasses import dataclass, field

from ..models.results import CombinationOutput


# ── 默认规则（等价于当前硬编码行为）──────────────────────────────────────

DEFAULT_FEASIBILITY_RULES: Dict[str, Any] = {
    "max_load_rate": 100.0,
    "tank_capacity_strict": True,
    "max_overload_count": 0,
    "max_overload_ratio": 0.0,
    "min_hangmei_output": 0.0,
    "require_all_feasible": False,
}


@dataclass
class FeasibilityResult:
    """单个组合的可行性评估结果。"""
    combination_id: str
    feasible: bool
    near_feasible: bool = False
    infeasible_summary: str = ""
    details: Dict[str, Any] = field(default_factory=dict)


def _get_field(output: Any, name: str, default=None):
    """兼容 CombinationOutput 对象和 dict。"""
    if isinstance(output, dict):
        return output.get(name, default)
    return getattr(output, name, default)


def assess_feasibility(
    combination_results: List[Any],
    rules: Optional[Dict[str, Any]] = None,
) -> List[FeasibilityResult]:
    """对一组组合执行参数化可行性判断。

    Args:
        combination_results: CombinationOutput 对象列表或 dict 列表。
            每个元素需包含: monthly_load, tank_check_result, hangmei_summary,
            feasible(原始), infeasible_summary, batch_results。
        rules: 规则参数 dict。传 None 或空 dict 使用默认规则。

    Returns:
        List[FeasibilityResult]，与输入列表等长，顺序一致。

    内部调用（solve_service）:
        直接传 CombinationOutput 对象列表，函数原地修改 feasible/near_feasible/
        infeasible_summary 字段，同时返回 FeasibilityResult 供日志/展示。

    MCP 调用:
        传 dict 列表（JSON 反序列化），返回 FeasibilityResult 列表，
        不修改原始 dict（MCP 层负责序列化返回）。
    """
    if rules is None or not rules:
        rules = dict(DEFAULT_FEASIBILITY_RULES)
    else:
        # 合并默认值：只覆盖传入的字段
        merged = dict(DEFAULT_FEASIBILITY_RULES)
        merged.update(rules)
        rules = merged

    max_load_rate = rules.get("max_load_rate", 100.0)
    tank_strict = rules.get("tank_capacity_strict", True)
    max_overload_count = rules.get("max_overload_count", 0)
    max_overload_ratio = rules.get("max_overload_ratio", 0.0)
    min_hangmei = rules.get("min_hangmei_output", 0.0)
    require_all = rules.get("require_all_feasible", False)

    results: List[FeasibilityResult] = []

    for output in combination_results:
        ml = _get_field(output, 'monthly_load', {}) or {}
        tc = _get_field(output, 'tank_check_result', {}) or {}
        hm = _get_field(output, 'hangmei_summary', {}) or {}
        batch_results = _get_field(output, 'batch_results', []) or []

        # ── 1. 罐容检查 ──
        tank_violations = tc.get('violation_count', 0)
        tank_failed = tank_strict and tank_violations > 0

        # ── 2. 负荷检查 ──
        devices = ml.get('devices') or []
        # 按可配置阈值重判超容
        overloaded = [
            d for d in devices
            if d.get('monthly_util', 0.0) > max_load_rate
        ]
        # 严重超容（超过 max_overload_ratio 的额外比例）
        severe_overloaded = [
            d for d in devices
            if d.get('monthly_util', 0.0) > 100.0 + max_overload_ratio
        ] if max_overload_ratio > 0 else overloaded

        overload_count = len(overloaded)
        severe_count = len(severe_overloaded)
        max_util = max((d.get('monthly_util', 0.0) for d in devices), default=0.0)

        # ── 3. 航煤产出检查 ──
        hangmei_failed = False
        hangmei_msg = ""
        if min_hangmei > 0 and hm:
            actual_output = hm.get('total_output', 0.0)
            if actual_output < min_hangmei:
                hangmei_failed = True
                hangmei_msg = f"航煤产出{actual_output:.0f}吨 < 最低要求{min_hangmei:.0f}吨"

        # ── 4. 批次级可行性检查 ──
        batch_feasible = all(
            br.get('feasible', True) for br in batch_results
        ) if require_all else True

        # ── 5. 综合判定 ──
        reasons = []

        if tank_failed:
            reasons.append(f"罐容超限：{tank_violations}处违规")

        if hangmei_failed:
            reasons.append(hangmei_msg)

        if not batch_feasible:
            reasons.append("存在不可行批次")

        if reasons:
            # 硬约束违规 → 不可行
            feasible = False
            near_feasible = False
            summary = "；".join(reasons)
        elif overload_count > max_overload_count:
            # 超容但无硬约束违规 → 接近可行
            feasible = False
            near_feasible = True
            summary = f"接近可行：{overload_count}台装置超容（最高负荷{max_util:.1f}%）"
        else:
            # 完全可行
            feasible = True
            near_feasible = False
            summary = ""

        # 保留原有 infeasible_summary（若新判定为不可行且无新摘要，则沿用旧摘要）
        if not feasible and not summary:
            old_summary = _get_field(output, 'infeasible_summary', '')
            summary = old_summary or "不可行"

        # 写回对象（内部调用时生效；MCP 调用时 dict 不被修改）
        if not isinstance(output, dict):
            output.feasible = feasible
            output.near_feasible = near_feasible
            output.infeasible_summary = summary

        results.append(FeasibilityResult(
            combination_id=_get_field(output, 'combination_id', ''),
            feasible=feasible,
            near_feasible=near_feasible,
            infeasible_summary=summary,
            details={
                'tank_violations': tank_violations,
                'overload_count': overload_count,
                'severe_overload_count': severe_count,
                'max_util': round(max_util, 1),
                'hangmei_output': hm.get('total_output', 0.0) if hm else 0.0,
                'rules_applied': rules,
            },
        ))

    return results
