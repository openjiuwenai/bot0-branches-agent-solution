# -*- coding: utf-8 -*-
"""参数化选优引擎。

替代 solve_service._select_optimal 的硬编码选优逻辑，
支持多目标加权、风险偏好等可定制策略。

选优策略字段（strategy_json）:
    objective: str = "revenue"
        选优目标模式：
        - "revenue": 最大收益（当前默认行为）
        - "feasibility_margin": 最大可行性边际（优先选负荷最低/最安全的方案）
        - "multi_objective": 多目标加权（需配合 weights 字段）
        - "risk_averse": 风险规避（综合收益与可行性，惩罚超容）
    weights: dict = {"revenue": 1.0}
        multi_objective 模式下的权重配置，可选目标：
        - revenue: 总收益（万元）
        - feasibility_margin: 可行性边际（超容越少越好）
        - hangmei_output: 航煤产出
        每个值需归一化后加权。
    prefer_near_feasible: bool = True
        无完全可行方案时，是否接受接近可行方案。
        True: 选超容最轻的接近可行方案（当前默认行为）。
        False: 无完全可行方案时返回 None。
    penalty_factor: float = 1.0
        risk_averse 模式下的超容惩罚系数。值越大，对超容越敏感。
    min_revenue: float = 0.0
        最低收益门槛。低于此值的组合不参与选优。
"""
from __future__ import annotations
from typing import List, Dict, Any, Optional, Tuple
from dataclasses import dataclass, field
import math

from ..models.results import CombinationOutput


# ── 默认策略（等价于当前硬编码行为）──────────────────────────────────────

DEFAULT_SELECTION_STRATEGY: Dict[str, Any] = {
    "objective": "revenue",
    "weights": {"revenue": 1.0},
    "prefer_near_feasible": True,
    "penalty_factor": 1.0,
    "min_revenue": 0.0,
}


@dataclass
class SelectionResult:
    """选优结果。"""
    combination_id: str
    description: str = ""
    total_revenue: float = 0.0
    score: float = 0.0
    feasible: bool = True
    near_feasible: bool = False
    is_temporary: bool = False
    infeasible_summary: str = ""
    details: Dict[str, Any] = field(default_factory=dict)


def _get_field(output: Any, name: str, default=None):
    """兼容 CombinationOutput 对象和 dict。"""
    if isinstance(output, dict):
        return output.get(name, default)
    return getattr(output, name, default)


def _compute_overload_penalty(output: Any) -> float:
    """超容惩罚乘积：∏ max(util_i, 100) + 罐容违规加权。

    沿用原 _select_optimal 的 fallback 逻辑。
    """
    ml = _get_field(output, 'monthly_load', {}) or {}
    penalty = 1.0
    for dev in (ml.get('devices') or []):
        util = dev.get('monthly_util', 0.0)
        penalty *= max(util, 100.0)
    tc = _get_field(output, 'tank_check_result', {}) or {}
    penalty *= (1 + (tc.get('violation_count', 0) * 0.5))
    return penalty


def _compute_feasibility_margin(output: Any) -> float:
    """可行性边际：所有装置距满负荷的余量平均值（百分比）。

    正值表示有余量，负值表示超容。值越大越安全。
    """
    ml = _get_field(output, 'monthly_load', {}) or {}
    devices = ml.get('devices') or []
    if not devices:
        return 100.0
    margins = [100.0 - d.get('monthly_util', 100.0) for d in devices]
    return sum(margins) / len(margins)


def _normalize(values: List[float]) -> List[float]:
    """将一组值归一化到 [0, 1] 区间。"""
    if not values:
        return []
    vmin, vmax = min(values), max(values)
    if vmax == vmin:
        return [0.5] * len(values)
    return [(v - vmin) / (vmax - vmin) for v in values]


def select_optimal(
    combination_results: List[Any],
    strategy: Optional[Dict[str, Any]] = None,
    pass1_results: Optional[Dict[str, Any]] = None,
) -> Optional[SelectionResult]:
    """对一组组合执行参数化选优。

    Args:
        combination_results: CombinationOutput 对象列表或 dict 列表。
            需已经过 assess_feasibility 判定 feasible/near_feasible。
        strategy: 选优策略 dict。传 None 使用默认策略。
        pass1_results: PASS 1 的 {combination_id: CombinationResult} 映射。
            内部调用时传入，用于获取 calc_results/explanations。
            MCP 调用时不传（MCP 层不需要中间数据）。

    Returns:
        SelectionResult 或 None（无组合数据时）。
    """
    if not combination_results:
        return None

    if strategy is None or not strategy:
        strategy = dict(DEFAULT_SELECTION_STRATEGY)
    else:
        merged = dict(DEFAULT_SELECTION_STRATEGY)
        merged.update(strategy)
        strategy = merged

    objective = strategy.get("objective", "revenue")
    weights = strategy.get("weights", {"revenue": 1.0})
    prefer_near = strategy.get("prefer_near_feasible", True)
    penalty_factor = strategy.get("penalty_factor", 1.0)
    min_revenue = strategy.get("min_revenue", 0.0)

    # ── 按 feasible / near_feasible 分桶 ──
    feasible_list: List[Any] = []
    near_list: List[Any] = []
    for output in combination_results:
        if _get_field(output, 'feasible', False):
            feasible_list.append(output)
        elif _get_field(output, 'near_feasible', False):
            near_list.append(output)

    # ── 最低收益门槛过滤 ──
    def _passes_revenue(output) -> bool:
        rev = _get_field(output, 'total_revenue', 0.0)
        return rev >= min_revenue

    feasible_list = [o for o in feasible_list if _passes_revenue(o)]
    near_list = [o for o in near_list if _passes_revenue(o)]

    # ── 选优函数 ──
    def _score(output) -> float:
        """根据 objective 模式计算综合评分。"""
        revenue = _get_field(output, 'total_revenue', 0.0)

        if objective == "revenue":
            return revenue

        elif objective == "feasibility_margin":
            # 优先选可行性边际最大的（最安全的方案）
            return _compute_feasibility_margin(output)

        elif objective == "risk_averse":
            # 收益 - 超容惩罚
            penalty = _compute_overload_penalty(output)
            # penalty 可能很大（∏），取对数压缩
            log_penalty = math.log10(max(penalty, 1.0))
            return revenue - penalty_factor * log_penalty * 10000

        elif objective == "multi_objective":
            # 多目标加权（需归一化，但单组合无法归一化，退化为加权求和）
            # 在批量模式下由调用方先归一化再传入
            margin = _compute_feasibility_margin(output)
            hm = _get_field(output, 'hangmei_summary', {}) or {}
            hm_output = hm.get('total_output', 0.0)
            w_rev = weights.get("revenue", 0.0)
            w_margin = weights.get("feasibility_margin", 0.0)
            w_hm = weights.get("hangmei_output", 0.0)
            return w_rev * revenue + w_margin * margin * 1000 + w_hm * hm_output

        else:
            return revenue

    # ── 可行组合中选最优 ──
    best: Optional[Any] = None
    best_score = float('-inf')
    if feasible_list:
        # multi_objective 模式下先归一化再评分
        if objective == "multi_objective" and len(feasible_list) > 1:
            revenues = [_get_field(o, 'total_revenue', 0.0) for o in feasible_list]
            margins = [_compute_feasibility_margin(o) for o in feasible_list]
            hm_outputs = [
                (_get_field(o, 'hangmei_summary', {}) or {}).get('total_output', 0.0)
                for o in feasible_list
            ]
            norm_rev = _normalize(revenues)
            norm_margin = _normalize(margins)
            norm_hm = _normalize(hm_outputs)
            w_rev = weights.get("revenue", 0.0)
            w_margin = weights.get("feasibility_margin", 0.0)
            w_hm = weights.get("hangmei_output", 0.0)
            for i, output in enumerate(feasible_list):
                score = w_rev * norm_rev[i] + w_margin * norm_margin[i] + w_hm * norm_hm[i]
                if score > best_score:
                    best_score = score
                    best = output
        else:
            for output in feasible_list:
                score = _score(output)
                if score > best_score:
                    best_score = score
                    best = output

    # ── 接近可行组合中选最优（fallback）──
    best_near: Optional[Any] = None
    best_near_score = float('-inf')
    if near_list:
        if objective == "multi_objective" and len(near_list) > 1:
            # 对 near_list 也做归一化
            revenues = [_get_field(o, 'total_revenue', 0.0) for o in near_list]
            margins = [_compute_feasibility_margin(o) for o in near_list]
            norm_rev = _normalize(revenues)
            norm_margin = _normalize(margins)
            w_rev = weights.get("revenue", 0.0)
            w_margin = weights.get("feasibility_margin", 0.0)
            for i, output in enumerate(near_list):
                score = w_rev * norm_rev[i] + w_margin * norm_margin[i]
                if score > best_near_score:
                    best_near_score = score
                    best_near = output
        else:
            for output in near_list:
                score = _score(output)
                if score > best_near_score:
                    best_near_score = score
                    best_near = output

    # ── 终极 fallback：无可行且无接近可行，选超容最轻的 ──
    is_temporary = False
    if best is None and best_near is None and combination_results:
        if not prefer_near:
            return None
        # 按超容惩罚乘积选最轻
        best_near = min(combination_results, key=_compute_overload_penalty)
        is_temporary = True
        # 标记为接近可行（若原为不可行）
        if not isinstance(best_near, dict):
            best_near.near_feasible = True
            ml = best_near.monthly_load or {}
            max_util = max((d.get('monthly_util', 0) for d in (ml.get('devices') or [])), default=0)
            overload_count = ml.get('overload_count', 0)
            tc = best_near.tank_check_result or {}
            tank_violations = tc.get('violation_count', 0)
            best_near.infeasible_summary = (
                f"接近可行：{overload_count}台装置超容（最高负荷{max_util:.1f}%），"
                f"罐容违规{tank_violations}处"
            )

    # ── 组装结果 ──
    selected = best if best is not None else best_near
    if selected is None:
        return None

    if best is None and best_near is not None:
        is_temporary = True

    # 获取中间数据（内部调用时从 pass1_results）
    calc_results = []
    explanations = []
    if pass1_results:
        combo_id = _get_field(selected, 'combination_id')
        pr = pass1_results.get(combo_id)
        if pr:
            calc_results = getattr(pr, 'calc_results', []) if not isinstance(pr, dict) else pr.get('calc_results', [])
            explanations = getattr(pr, 'explanations', []) if not isinstance(pr, dict) else pr.get('explanations', [])

    return SelectionResult(
        combination_id=_get_field(selected, 'combination_id', ''),
        description=_get_field(selected, 'description', ''),
        total_revenue=_get_field(selected, 'total_revenue', 0.0),
        score=best_score if best is not None else best_near_score,
        feasible=_get_field(selected, 'feasible', False),
        near_feasible=_get_field(selected, 'near_feasible', False),
        is_temporary=is_temporary,
        infeasible_summary=_get_field(selected, 'infeasible_summary', ''),
        details={
            'calc_results': calc_results,
            'explanations': explanations,
            'hangmei_summary': _get_field(selected, 'hangmei_summary', {}),
            'switches': _get_field(selected, 'switches', {}),
            'feasibility_margin': round(_compute_feasibility_margin(selected), 1),
            'overload_penalty': round(_compute_overload_penalty(selected), 1),
        },
    )
